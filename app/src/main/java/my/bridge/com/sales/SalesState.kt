package my.bridge.com.sales

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 销售页 UI 状态快照。任何写入都 copy 全新对象，保证 Compose 可靠重组。 */
data class SalesUiState(
    val initialized: Boolean = false,
    val loggedIn: Boolean = false,
    val user: SalesUser? = null,
    val serverUrl: String = "",
    val wsConnected: Boolean = false,
    val pageLoading: Boolean = false,
    val actionLoading: Boolean = false,
    val exporting: Boolean = false,
    val productType: SalesProductType = SalesProductType.SPONGE,
    val records: List<SalesRecord> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 100,
    val keyword: String = "",
    val globalSearch: Boolean = false,
    val filters: SalesFilters = SalesFilters(sortColumn = "date", sortOrder = "desc"),
    val columnFilters: Map<String, String> = emptyMap(),
    val shops: List<String> = emptyList(),
    val selectMode: Boolean = false,
    val selectedIds: Set<Int> = emptySet(),
    val onlineUsers: List<SalesOnlineUser> = emptyList(),
    val editLocks: Map<String, SalesEditLock> = emptyMap(),
    val notices: List<SalesNotice> = emptyList(),
    /** 全域搜索命中其它产品类型时的切换提示；null=无。 */
    val searchAllPrompt: List<SalesSearchAllResult>? = null,
    val readingMode: Boolean = false,
    val toolbarCollapsed: Boolean = false,
) {
    val totalPages: Int get() = ((total + size - 1) / size).coerceAtLeast(1)
}

/** 销售页全局状态中心（对应参考项目 auth store + afterSales store + useWebSocket 状态）。 */
object SalesState {

    fun interface ToastListener {
        fun onToast(message: String, isError: Boolean)
    }

    private var _state by mutableStateOf(SalesUiState())
    val state: SalesUiState get() = _state

    @Volatile
    private var toastListener: ToastListener? = null

    fun setToastListener(listener: ToastListener?) {
        toastListener = listener
    }

    @Synchronized
    private fun mutate(block: (SalesUiState) -> SalesUiState) {
        _state = block(_state)
    }

    fun initFromPrefs(serverUrl: String, user: SalesUser?, loggedIn: Boolean, productType: SalesProductType, pageSize: Int, toolbarCollapsed: Boolean) {
        mutate {
            it.copy(
                initialized = true,
                serverUrl = serverUrl,
                user = user,
                loggedIn = loggedIn,
                productType = productType,
                size = pageSize,
                toolbarCollapsed = toolbarCollapsed,
            )
        }
    }

    fun setServerUrl(url: String) = mutate { it.copy(serverUrl = url) }

    fun loginSuccess(user: SalesUser, productType: SalesProductType) {
        mutate {
            it.copy(
                loggedIn = true,
                user = user,
                productType = productType,
                page = 1,
                records = emptyList(),
                total = 0,
                selectedIds = emptySet(),
                selectMode = false,
                editLocks = emptyMap(),
                notices = emptyList(),
            )
        }
    }

    /** 刷新用户信息（/auth/me）：只更新 user/登录态/产品类型，不清空已加载的列表数据。
     *  与 loginSuccess 的区别是后者用于登录场景需清空旧数据，此处是会话内刷新，清空会
     *  与并发的 loadList 产生竞态，导致列表先填后被清空（表格空白）。 */
    fun refreshUser(user: SalesUser, productType: SalesProductType) {
        mutate {
            it.copy(
                loggedIn = true,
                user = user,
                productType = productType,
            )
        }
    }

    fun logoutLocal() {
        mutate {
            SalesUiState(
                initialized = true,
                loggedIn = false,
                serverUrl = it.serverUrl,
                toolbarCollapsed = it.toolbarCollapsed,
            )
        }
    }

    fun setWsConnected(connected: Boolean) = mutate { if (it.wsConnected != connected) it.copy(wsConnected = connected) else it }
    fun setPageLoading(loading: Boolean) = mutate { if (it.pageLoading != loading) it.copy(pageLoading = loading) else it }
    fun setActionLoading(loading: Boolean) = mutate { if (it.actionLoading != loading) it.copy(actionLoading = loading) else it }
    fun setExporting(exporting: Boolean) = mutate { if (it.exporting != exporting) it.copy(exporting = exporting) else it }

    fun setProductType(type: SalesProductType) = mutate {
        it.copy(productType = type, page = 1, keyword = "", columnFilters = emptyMap(), filters = it.filters.copy(shopSerial = ""), selectedIds = emptySet(), selectMode = false)
    }

    fun setKeyword(keyword: String) = mutate { it.copy(keyword = keyword, page = 1) }
    fun setGlobalSearch(enabled: Boolean) = mutate { it.copy(globalSearch = enabled) }
    fun setPage(page: Int) = mutate { it.copy(page = page.coerceAtLeast(1)) }
    fun setPageSize(size: Int) = mutate { it.copy(size = size, page = 1) }
    fun setFilters(filters: SalesFilters) = mutate { it.copy(filters = filters, page = 1) }
    fun resetFilters() = mutate {
        it.copy(filters = SalesFilters(sortColumn = it.filters.sortColumn, sortOrder = it.filters.sortOrder), columnFilters = emptyMap(), page = 1)
    }
    fun setSort(column: String, order: String) = mutate { it.copy(filters = it.filters.copy(sortColumn = column, sortOrder = order)) }
    fun setColumnFilter(column: String, value: String?) = mutate {
        val next = it.columnFilters.toMutableMap()
        if (value == null) next.remove(column) else next[column] = value
        it.copy(columnFilters = next.toMap(), page = 1)
    }
    fun clearColumnFilters() = mutate { it.copy(columnFilters = emptyMap(), page = 1) }

    fun setShops(shops: List<String>) = mutate { it.copy(shops = shops.toList()) }

    fun setList(result: SalesListResult) = mutate {
        it.copy(records = result.records, total = result.total, page = result.page, size = result.size, pageLoading = false)
    }

    fun setSelectMode(enabled: Boolean) = mutate {
        if (enabled) it.copy(selectMode = true) else it.copy(selectMode = false, selectedIds = emptySet())
    }
    fun toggleSelected(id: Int) = mutate {
        val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
        it.copy(selectedIds = next)
    }
    fun setSelected(ids: Set<Int>) = mutate { it.copy(selectedIds = ids.toSet()) }

    fun setReadingMode(enabled: Boolean) = mutate { it.copy(readingMode = enabled) }
    fun setToolbarCollapsed(collapsed: Boolean) = mutate { it.copy(toolbarCollapsed = collapsed) }
    fun setSearchAllPrompt(results: List<SalesSearchAllResult>?) = mutate { it.copy(searchAllPrompt = results?.toList()) }

    fun setOnlineUsers(users: List<SalesOnlineUser>) = mutate { it.copy(onlineUsers = users.toList()) }
    fun upsertOnlineUser(user: SalesOnlineUser) = mutate {
        val next = it.onlineUsers.filterNot { u -> u.username == user.username } + user
        it.copy(onlineUsers = next)
    }
    fun removeOnlineUser(username: String) = mutate { it.copy(onlineUsers = it.onlineUsers.filterNot { u -> u.username == username }) }
    fun updateOnlineDevices(username: String, count: Int, devices: List<SalesOnlineDevice>) = mutate {
        it.copy(onlineUsers = it.onlineUsers.map { u -> if (u.username == username) u.copy(device_count = count, devices = devices) else u })
    }

    fun lockKey(type: SalesProductType, recordId: Int): String = "${type.key}:$recordId"

    fun putEditLock(key: String, lock: SalesEditLock) = mutate { it.copy(editLocks = it.editLocks + (key to lock)) }
    fun removeEditLock(key: String) = mutate { it.copy(editLocks = it.editLocks - key) }
    fun lockFor(type: SalesProductType, recordId: Int): SalesEditLock? = _state.editLocks[lockKey(type, recordId)]

    fun addNotice(text: String, isError: Boolean = false, durationMs: Long = 3000L): Long {
        val id = System.currentTimeMillis() + (0..999).random()
        mutate { it.copy(notices = (it.notices + SalesNotice(id, text, isError)).takeLast(4)) }
        return id
    }

    fun removeNotice(id: Long) = mutate { it.copy(notices = it.notices.filterNot { n -> n.id == id }) }

    fun toast(message: String, isError: Boolean = false) {
        toastListener?.onToast(message, isError)
    }

    /** WS 业务增量同步（照 stores/afterSales.ts handleWsMessage；仅当前产品类型）。 */
    fun applyWsBusiness(type: SalesProductType, action: String, record: SalesRecord?, recordId: Int?, changes: Map<String, String>) {
        if (type != _state.productType) return
        mutate { s ->
            when (action) {
                "create" -> if (record != null) {
                    val next = if (s.page == 1) listOf(record) + s.records.filterNot { it.id == record.id } else s.records
                    s.copy(records = next, total = s.total + 1)
                } else s
                "delete" -> if (recordId != null) {
                    s.copy(records = s.records.filterNot { it.id == recordId }, total = (s.total - 1).coerceAtLeast(0))
                } else s
                "update" -> if (recordId != null) {
                    s.copy(records = s.records.map { recordItem ->
                        if (recordItem.id != recordId) recordItem else applyChanges(recordItem, changes)
                    })
                } else s
                else -> s
            }
        }
    }

    fun updateLocalCell(recordId: Int, column: String, value: String) = mutate {
        it.copy(records = it.records.map { r -> if (r.id == recordId) r.withCell(column, value) else r })
    }

    private fun applyChanges(record: SalesRecord, changes: Map<String, String>): SalesRecord {
        var next = record
        changes.forEach { (key, value) -> next = next.withCell(key, value) }
        return next
    }
}
