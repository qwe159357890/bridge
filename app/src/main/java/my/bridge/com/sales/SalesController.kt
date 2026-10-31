package my.bridge.com.sales

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 销售页业务编排层。
 *
 * 生命周期照智能控制/快递扫码：进入销售页 [enterPage]、离开 [exitPage]；
 * 所有 REST 均为 suspend，WS 事件统一落到 [SalesState]，UI 只读快照。
 */
object SalesController {

    private const val TAG = "销售页"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var api: SalesApiClient? = null
    private val ws = SalesWsClient()

    @Volatile
    private var pageActive = false

    @Volatile
    private var fetchGeneration = 0L

    @Synchronized
    private fun ensureInit(context: Context) {
        val app = context.applicationContext
        if (appContext == null) {
            appContext = app
            api = SalesApiClient(app)
            val user = SalesPrefs.user(app)
            val token = SalesPrefs.token(app)
            val initialType = initialProductType(user, SalesPrefs.productType(app))
            SalesState.initFromPrefs(
                serverUrl = SalesPrefs.serverUrl(app),
                user = user,
                loggedIn = token.isNotBlank() && user != null,
                productType = initialType,
                pageSize = if (SalesPrefs.virtualScroll(app)) 9999 else SalesPrefs.pageSize(app),
                toolbarCollapsed = SalesPrefs.toolbarCollapsed(app),
            )
        }
    }

    fun enterPage(context: Context) {
        ensureInit(context)
        pageActive = true
        val state = SalesState.state
        if (state.loggedIn) {
            connectWs(context)
            refreshMe(context)
            refreshAll(context)
        }
    }

    fun exitPage() {
        pageActive = false
        fetchGeneration++
        ws.disconnect()
        SalesState.setWsConnected(false)
    }

    // ------------------------------------------------------------------
    // 登录 / 会话
    // ------------------------------------------------------------------

    suspend fun login(context: Context, username: String, password: String, remember: Boolean, force: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            val app = appContext!!
            runCatching {
                val result = api!!.login(username.trim(), password, force)
                SalesPrefs.saveLogin(app, result.token, result.user)
                SalesPrefs.saveAccount(app, username.trim(), password, remember)
                val type = initialProductType(result.user, SalesProductType.SPONGE)
                SalesPrefs.setProductType(app, type)
                SalesState.loginSuccess(result.user, type)
                SalesState.toast("登录成功")
                connectWs(app)
                refreshAll(app)
            }
        }

    suspend fun logout(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInit(context)
        val app = appContext!!
        runCatching {
            runCatching { api!!.logout() }
            ws.disconnect()
            SalesPrefs.clearLogin(app)
            SalesState.logoutLocal()
            SalesState.setWsConnected(false)
        }
    }

    fun onForceLogout(message: String) {
        val app = appContext ?: return
        ws.disconnect()
        SalesPrefs.clearLogin(app)
        SalesState.logoutLocal()
        SalesState.setWsConnected(false)
        SalesState.toast(message.ifBlank { "登录状态已失效，请重新登录" }, isError = true)
    }

    fun refreshMe(context: Context) {
        ensureInit(context)
        if (!SalesState.state.loggedIn) return
        scope.launch {
            try {
                val user = api!!.me()
                SalesPrefs.saveLogin(context.applicationContext, SalesPrefs.token(context), user)
                SalesState.refreshUser(user, initialProductType(user, SalesState.state.productType))
            } catch (e: Exception) {
                handleApiError(e, silent = true)
            }
        }
    }

    private fun initialProductType(user: SalesUser?, preferred: SalesProductType): SalesProductType {
        val allowed = user?.allowedTypes().orEmpty()
        return when {
            allowed.isEmpty() -> SalesProductType.SPONGE
            preferred in allowed -> preferred
            else -> allowed.first()
        }
    }

    // ------------------------------------------------------------------
    // WS
    // ------------------------------------------------------------------

    private fun connectWs(context: Context) {
        if (!pageActive || !SalesState.state.loggedIn) return
        ws.connect(context, object : SalesWsClient.Listener {
            override fun onConnectedChanged(connected: Boolean) {
                SalesState.setWsConnected(connected)
            }

            override fun onClientId(clientId: String) {
                SalesPrefs.setClientId(context, clientId)
            }

            override fun onOnlineUsers(users: List<SalesOnlineUser>) = SalesState.setOnlineUsers(users)
            override fun onUserOnline(user: SalesOnlineUser) = SalesState.upsertOnlineUser(user)
            override fun onUserOffline(username: String) = SalesState.removeOnlineUser(username)
            override fun onUserDeviceChange(username: String, deviceCount: Int, devices: List<SalesOnlineDevice>) =
                SalesState.updateOnlineDevices(username, deviceCount, devices)

            override fun onEditLockChanged(lockKey: String, lock: SalesEditLock?) {
                if (lockKey.isBlank()) return
                if (lock == null) SalesState.removeEditLock(lockKey) else SalesState.putEditLock(lockKey, lock)
            }

            override fun onEditLockConflict(conflict: SalesLockConflict) {
                val current = SalesState.state.user
                val canForce = (current?.roleLevel ?: 0) > roleLevel(conflict.role)
                val base = conflict.message.ifBlank { "记录已被 ${conflict.locked_by_display_name} 锁定编辑" }
                val id = SalesState.addNotice(
                    if (canForce) "$base（您有权强制解锁，可重新双击尝试）" else base,
                    isError = !canForce,
                )
                scope.launch {
                    delay(5000)
                    SalesState.removeNotice(id)
                }
            }

            override fun onNotification(notification: SalesNotification) {
                if (notification.product_type != SalesState.state.productType.key) return
                val id = SalesState.addNotice(notification.message())
                scope.launch {
                    delay(3000)
                    SalesState.removeNotice(id)
                }
            }

            override fun onBusiness(
                action: String,
                type: SalesProductType,
                record: SalesRecord?,
                recordId: Int?,
                changes: Map<String, String>,
            ) {
                SalesState.applyWsBusiness(type, action, record, recordId, changes)
            }

            override fun onForceLogout(message: String) = this@SalesController.onForceLogout(message)
        })
    }

    suspend fun lockEdit(recordId: Int): Boolean = suspendCancellableCoroutine { cont ->
        val type = SalesState.state.productType
        if (!ws.isConnected()) {
            // 参考项目断线时发送无响应，5s 后默认成功；这里保留「离线也允许尝试」语义但快速失败给 REST 兜底。
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        ws.lockEdit(type, recordId) { success -> if (cont.isActive) cont.resume(success) }
    }

    fun unlockEdit(recordId: Int) {
        ws.unlockEdit(SalesState.state.productType, recordId)
    }

    fun canForceUnlock(lock: SalesEditLock): Boolean {
        val my = SalesState.state.user?.roleLevel ?: 0
        return my > roleLevel(lock.role)
    }

    private fun roleLevel(role: String): Int = when (role) {
        "admin" -> 3
        "manager" -> 2
        else -> 1
    }

    // ------------------------------------------------------------------
    // 列表 / 筛选 / 分页
    // ------------------------------------------------------------------

    fun refreshAll(context: Context) {
        loadList(context)
        loadShops(context)
    }

    fun setProductType(context: Context, type: SalesProductType) {
        ensureInit(context)
        SalesPrefs.setProductType(context, type)
        SalesState.setProductType(type)
        refreshAll(context)
    }

    fun setKeyword(context: Context, keyword: String, globalSearch: Boolean) {
        SalesState.setKeyword(keyword)
        SalesState.setGlobalSearch(globalSearch)
        loadList(context)
    }

    fun setPage(context: Context, page: Int) {
        SalesState.setPage(page)
        loadList(context)
    }

    fun setPageSize(context: Context, size: Int) {
        SalesPrefs.setPageSize(context, size)
        SalesState.setPageSize(size)
        loadList(context)
    }

    fun setVirtualScroll(context: Context, enabled: Boolean) {
        SalesPrefs.setVirtualScroll(context, enabled)
        SalesState.setPageSize(if (enabled) 9999 else 100)
        loadList(context)
    }

    fun setFilters(context: Context, filters: SalesFilters) {
        SalesState.setFilters(filters)
        loadList(context)
    }

    fun resetFilters(context: Context) {
        SalesState.resetFilters()
        loadList(context)
    }

    fun setSort(context: Context, column: String, order: String) {
        SalesState.setSort(column, order)
        loadList(context)
    }

    fun setColumnFilter(context: Context, column: String, value: String?) {
        SalesState.setColumnFilter(column, value)
        loadList(context)
    }

    fun loadList(context: Context) {
        ensureInit(context)
        if (!SalesState.state.loggedIn) return
        val myGen = ++fetchGeneration
        val snapshot = SalesState.state
        SalesState.setPageLoading(true)
        scope.launch {
            try {
                val result = api!!.list(
                    type = snapshot.productType,
                    page = snapshot.page,
                    size = snapshot.size,
                    keyword = snapshot.keyword,
                    filters = snapshot.filters,
                    columnFilters = snapshot.columnFilters,
                )
                if (myGen != fetchGeneration) return@launch
                SalesState.setList(result)
                maybePromptGlobalSearch(context, snapshot, result)
            } catch (e: Exception) {
                if (myGen == fetchGeneration) {
                    SalesState.setPageLoading(false)
                    handleApiError(e)
                }
            }
        }
    }

    private suspend fun maybePromptGlobalSearch(context: Context, snapshot: SalesUiState, result: SalesListResult) {
        if (!snapshot.globalSearch || snapshot.keyword.isBlank() || result.total != 0) {
            SalesState.setSearchAllPrompt(null)
            return
        }
        runCatching {
            val results = api!!.searchAll(snapshot.keyword, snapshot.productType)
                .filter { it.total > 0 }
            SalesState.setSearchAllPrompt(results.ifEmpty { null })
        }.onFailure { handleApiError(it, silent = true) }
    }

    fun applySearchAllTarget(context: Context, target: SalesProductType) {
        val keyword = SalesState.state.keyword
        SalesState.setSearchAllPrompt(null)
        SalesPrefs.setProductType(context, target)
        SalesState.setProductType(target)
        SalesState.setKeyword(keyword)
        refreshAll(context)
    }

    fun dismissSearchAllPrompt() = SalesState.setSearchAllPrompt(null)

    fun loadShops(context: Context) {
        ensureInit(context)
        if (!SalesState.state.loggedIn) return
        val type = SalesState.state.productType
        scope.launch {
            runCatching { api!!.shops(type) }
                .onSuccess { SalesState.setShops(it) }
                .onFailure { handleApiError(it, silent = true) }
        }
    }

    // ------------------------------------------------------------------
    // 编辑 / 新增 / 删除 / 物流 / 设置 / 导出
    // ------------------------------------------------------------------

    suspend fun updateCell(context: Context, recordId: Int, column: String, value: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            runCatching {
                api!!.updateCell(recordId, SalesState.state.productType, column, value)
                SalesState.updateLocalCell(recordId, column, value)
            }.onFailure { handleApiError(it) }
        }

    suspend fun createRecord(context: Context, record: SalesRecord): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching {
            api!!.create(record, SalesState.state.productType)
            SalesState.toast("添加成功")
            loadList(context)
        }.onFailure { handleApiError(it) }
    }

    suspend fun updateRecord(context: Context, record: SalesRecord): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching {
            api!!.update(record, SalesState.state.productType)
            SalesState.toast("更新成功")
            loadList(context)
        }.onFailure { handleApiError(it) }
    }

    suspend fun deleteRecord(context: Context, recordId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching {
            api!!.delete(recordId, SalesState.state.productType)
            SalesState.toast("删除成功")
            loadList(context)
        }.onFailure { handleApiError(it) }
    }

    suspend fun matchSales(context: Context, field: String, value: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            runCatching { api!!.matchSales(SalesState.state.productType, field, value) }
                .onFailure { handleApiError(it, silent = true) }
                .getOrNull()
        }

    suspend fun queryExpress(context: Context, expressNo: String): Result<ExpressResult> =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            runCatching { api!!.queryExpress(expressNo) }.onFailure { handleApiError(it) }
        }

    suspend fun cookieStatus(context: Context): Result<CookieStatus> = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching { api!!.bdCookieStatus() }.onFailure { handleApiError(it) }
    }

    suspend fun saveCookie(context: Context, cookie: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching {
            api!!.saveBdCookie(cookie)
            SalesState.toast("百度Cookie保存成功")
        }.onFailure { handleApiError(it) }
    }

    fun setServerUrl(context: Context, url: String): Boolean {
        ensureInit(context)
        if (!SalesPrefs.isValidServerUrl(url)) return false
        SalesPrefs.setServerUrl(context, url)
        SalesState.setServerUrl(SalesPrefs.serverUrl(context))
        if (SalesState.state.loggedIn) {
            ws.disconnect()
            connectWs(context)
            refreshAll(context)
        }
        return true
    }

    suspend fun exportExcel(
        context: Context,
        visibleColumns: List<String>,
        selectedIds: Set<Int>?,
        dateStart: String? = null,
        dateEnd: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureInit(context)
        val app = appContext!!
        SalesState.setExporting(true)
        runCatching {
            val s = SalesState.state
            val file = api!!.exportExcel(
                type = s.productType,
                keyword = s.keyword,
                filters = s.filters,
                visibleColumns = visibleColumns,
                recordsForColor = s.records,
                selectedIds = selectedIds,
                dateStart = dateStart,
                dateEnd = dateEnd,
            )
            val path = saveToDownloads(app, file)
            SalesState.toast("已导出：$path")
            path
        }.onFailure { handleApiError(it) }
            .also { SalesState.setExporting(false) }
    }

    private fun saveToDownloads(context: Context, file: SalesExportFile): String {
        val filename = file.filename.ifBlank { "售后记录.xlsx" }
        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bridge")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw SalesApiClient.ApiException(0, "无法创建下载文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                ?: throw SalesApiClient.ApiException(0, "无法写入下载文件")
            return "下载/Bridge/$filename"
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val out = File(dir, filename)
        FileOutputStream(out).use { it.write(file.bytes) }
        return out.absolutePath
    }

    // ------------------------------------------------------------------
    // 错误处理
    // ------------------------------------------------------------------

    private fun handleApiError(error: Throwable, silent: Boolean = false) {
        if (error is SalesApiClient.ApiException && error.status == 401) {
            onForceLogout(error.message ?: "登录已过期，请重新登录")
            return
        }
        val message = when (error) {
            is SalesApiClient.ApiException -> error.message ?: "请求失败"
            else -> "网络连接失败"
        }
        if (!silent) SalesState.toast(message, isError = true)
        DiagLog.w(TAG, "销售接口异常：$message")
    }
}
