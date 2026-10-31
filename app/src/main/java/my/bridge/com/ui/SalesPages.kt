@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package my.bridge.com.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import my.bridge.com.sales.CookieStatus
import my.bridge.com.sales.ExpressResult
import my.bridge.com.sales.SALES_COLUMNS
import my.bridge.com.sales.SalesApiClient
import my.bridge.com.sales.SalesColumn
import my.bridge.com.sales.SalesController
import my.bridge.com.sales.SalesEditLock
import my.bridge.com.sales.SalesFilters
import my.bridge.com.sales.SalesPrefs
import my.bridge.com.sales.SalesProductType
import my.bridge.com.sales.SalesRecord
import my.bridge.com.sales.SalesState
import my.bridge.com.sales.SalesUiState
import java.util.Calendar

private val SalesBrand = Color(0xFF1A6EE8)
private val SalesGreen = Color(0xFF2E9E5B)
private val SalesRed = Color(0xFFE5484D)
private val SalesOrange = Color(0xFFE8862E)
private val SalesSub = Color(0xFF6B7280)
private val SalesBorder = Color(0xFFE5E7EB)

private data class CellEditState(
    val record: SalesRecord,
    val column: SalesColumn,
    val value: String,
)

private data class RowMenuState(val record: SalesRecord, val column: SalesColumn)
private data class HeaderMenuState(val column: SalesColumn)
private data class ToggleState(val record: SalesRecord, val column: String)
private data class ExpressDialogState(val record: SalesRecord, val column: String, val number: String)
private data class SmartPromptState(val record: SalesRecord, val kind: String, val value: String)
private data class ForceUnlockState(val record: SalesRecord, val column: SalesColumn, val lock: SalesEditLock)

private data class RecordFormState(val record: SalesRecord? = null) {
    val isEdit: Boolean get() = record != null
}

/**
 * 销售页（底部导航「销售」）。
 *
 * 复刻 references/SalesRecordRemote 的移动端售后主页；Bridge 自适配项：
 * - 点击底部「销售」后由 MainScreen 隐藏底部导航，本页左上角提供「返回导航」；
 * - 桌面右键映射为长按，表头右键映射为长按列头，双击仍用于单元格编辑；
 * - 阅读模式锁横屏，退出/离开页面恢复原方向。
 */
@Composable
fun SalesPage(onShowBottomBar: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ui = SalesState.state

    var columnConfigTick by remember { mutableIntStateOf(0) }
    var showFilter by remember { mutableStateOf(false) }
    var showColumnConfig by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showOnlineUsers by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var cellEdit by remember { mutableStateOf<CellEditState?>(null) }
    var formState by remember { mutableStateOf<RecordFormState?>(null) }
    var rowMenu by remember { mutableStateOf<RowMenuState?>(null) }
    var headerMenu by remember { mutableStateOf<HeaderMenuState?>(null) }
    var toggleState by remember { mutableStateOf<ToggleState?>(null) }
    var deleteTarget by remember { mutableStateOf<SalesRecord?>(null) }
    var expressState by remember { mutableStateOf<ExpressDialogState?>(null) }
    var expressResult by remember { mutableStateOf<ExpressResult?>(null) }
    var expressLoading by remember { mutableStateOf(false) }
    var smartPrompt by remember { mutableStateOf<SmartPromptState?>(null) }
    var forceUnlock by remember { mutableStateOf<ForceUnlockState?>(null) }
    var savedOrientation by remember { mutableStateOf<Int?>(null) }

    val user = ui.user
    val allowedTypes = remember(user) { user?.allowedTypes() ?: SalesProductType.entries.toList() }
    val visibleColumns = remember(ui.readingMode, columnConfigTick, user) {
        effectiveVisibleColumns(context, user, ui.readingMode)
    }

    DisposableEffect(Unit) {
        SalesState.setToastListener { message, isError ->
            mainHandler.post {
                Toast.makeText(context, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
        SalesController.enterPage(context)
        onDispose {
            SalesController.exitPage()
            SalesState.setToastListener(null)
            savedOrientation?.let { activity?.requestedOrientation = it }
        }
    }

    LaunchedEffect(ui.readingMode) {
        if (activity == null) return@LaunchedEffect
        if (ui.readingMode) {
            if (savedOrientation == null) savedOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            savedOrientation?.let { activity.requestedOrientation = it }
            savedOrientation = null
        }
    }

    // 返回键分层：阅读模式/选择模式/筛选面板先退，浮层菜单最后注册优先关闭。
    BackHandler(enabled = ui.readingMode) { SalesState.setReadingMode(false) }
    BackHandler(enabled = ui.selectMode && !ui.readingMode) { SalesState.setSelectMode(false) }
    BackHandler(enabled = showFilter && !ui.readingMode) { showFilter = false }
    BackHandler(enabled = showOnlineUsers) { showOnlineUsers = false }
    BackHandler(enabled = headerMenu != null) { headerMenu = null }
    BackHandler(enabled = rowMenu != null) { rowMenu = null }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!ui.loggedIn) {
            SalesLoginPage(onShowBottomBar = onShowBottomBar)
        } else {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (!ui.readingMode) {
                    SalesTopBar(
                        ui = ui,
                        onShowBottomBar = onShowBottomBar,
                        onRefresh = { SalesController.refreshAll(context) },
                        onOnlineUsers = { showOnlineUsers = true },
                        onLogout = { showLogoutConfirm = true },
                    )
                    SalesToolbar(
                        ui = ui,
                        allowedTypes = allowedTypes,
                        onTypeChange = { SalesController.setProductType(context, it) },
                        onSearch = { keyword, global -> SalesController.setKeyword(context, keyword, global) },
                        onFilter = { showFilter = !showFilter },
                        onColumns = { showColumnConfig = true },
                        onSettings = { showSettings = true },
                        onToggleSelect = { SalesState.setSelectMode(!ui.selectMode) },
                        onExport = {
                            if (ui.selectMode && ui.selectedIds.isNotEmpty()) {
                                scope.launch { SalesController.exportExcel(context, visibleColumns.map { it.prop }, ui.selectedIds) }
                            } else {
                                showExport = true
                            }
                        },
                        onAdd = {
                            if (ui.user?.canCreate == true) {
                                formState = RecordFormState()
                            } else {
                                SalesState.toast("当前账号无新增权限", isError = true)
                            }
                        },
                    )
                    if (showFilter) {
                        SalesFilterPanel(
                            ui = ui,
                            onApply = { filters ->
                                SalesController.setFilters(context, filters)
                                showFilter = false
                            },
                            onReset = {
                                SalesController.resetFilters(context)
                                showFilter = false
                            },
                            onClose = { showFilter = false },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SalesTable(
                        ui = ui,
                        visibleColumns = visibleColumns,
                        onCellDouble = { record, column -> handleCellDouble(
                            context = context,
                            ui = ui,
                            record = record,
                            column = column,
                            onOpenEdit = { cellEdit = it },
                            onToggle = { toggleState = it },
                            onForceUnlock = { forceUnlock = it },
                        ) },
                        onRowLong = { record, column -> rowMenu = RowMenuState(record, column) },
                        onHeaderLong = { column -> headerMenu = HeaderMenuState(column) },
                    )
                    if (ui.pageLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }

                if (ui.readingMode) {
                    SalesReadingBar(
                        ui = ui,
                        onExit = { SalesState.setReadingMode(false) },
                        onColumns = { showColumnConfig = true },
                        onPage = { SalesController.setPage(context, it) },
                    )
                } else {
                    SalesPaginationBar(
                        ui = ui,
                        onPage = { SalesController.setPage(context, it) },
                        onPageSize = { size ->
                            if (size == 9999) SalesController.setVirtualScroll(context, true) else {
                                SalesPrefs.setVirtualScroll(context, false)
                                SalesController.setPageSize(context, size)
                            }
                        },
                        onReading = { SalesState.setReadingMode(true) },
                    )
                }
            }
        }

        SalesNoticeOverlay(ui)
    }

    // ---------------- 弹窗组 ----------------
    cellEdit?.let { state ->
        SalesCellEditDialog(
            state = state,
            onDismiss = {
                SalesController.unlockEdit(state.record.id)
                cellEdit = null
            },
            onSave = { value ->
                scope.launch {
                    val result = SalesController.updateCell(context, state.record.id, state.column.prop, value)
                    if (result.isSuccess) {
                        SalesState.toast("保存成功")
                        SalesController.unlockEdit(state.record.id)
                        cellEdit = null
                    }
                }
            },
        )
    }

    formState?.let { state ->
        SalesRecordFormDialog(
            state = state,
            productType = ui.productType,
            onDismiss = { formState = null },
            onSaved = { formState = null },
        )
    }

    rowMenu?.let { menu ->
        SalesRowMenuDialog(
            menu = menu,
            ui = ui,
            onDismiss = { rowMenu = null },
            onEdit = {
                rowMenu = null
                if (ui.user?.canEditAll == true) formState = RecordFormState(menu.record)
                else SalesState.toast("当前账号无编辑记录权限", isError = true)
            },
            onToggleSign = { rowMenu = null; toggleState = ToggleState(menu.record, "signed") },
            onTogglePicked = { rowMenu = null; toggleState = ToggleState(menu.record, "picked_up") },
            onExpress = {
                rowMenu = null
                val number = when (menu.column.prop) {
                    "send_express" -> menu.record.send_express
                    "return_express" -> menu.record.return_express
                    else -> menu.record.return_express.ifBlank { menu.record.send_express }
                }
                if (number.isBlank()) {
                    SalesState.toast("该列无快递单号，无法查询物流", isError = true)
                } else {
                    expressResult = null
                    expressState = ExpressDialogState(menu.record, menu.column.prop, number)
                }
            },
            onColor = { color ->
                rowMenu = null
                scope.launch {
                    SalesController.updateCell(context, menu.record.id, "row_color", color)
                        .onSuccess { SalesState.toast(if (color.isBlank()) "已清除颜色" else "已标记颜色") }
                }
            },
            onDelete = { rowMenu = null; deleteTarget = menu.record },
        )
    }

    headerMenu?.let { menu ->
        SalesHeaderMenuDialog(
            menu = menu,
            ui = ui,
            onDismiss = { headerMenu = null },
            onSort = { order ->
                SalesController.setSort(context, menu.column.prop, order)
                headerMenu = null
            },
            onClearSort = {
                SalesController.setSort(context, "date", "desc")
                headerMenu = null
            },
            onFilter = {
                SalesController.setColumnFilter(context, menu.column.prop, ui.columnFilters[menu.column.prop] ?: "")
                headerMenu = null
            },
            onClearFilter = {
                SalesController.setColumnFilter(context, menu.column.prop, null)
                headerMenu = null
            },
            onHide = {
                saveColumnVisibility(context, ui.readingMode, visibleColumns, menu.column.prop, false)
                columnConfigTick++
                headerMenu = null
                SalesState.toast("已隐藏「${menu.column.label}」")
            },
        )
    }

    toggleState?.let { toggle ->
        val isSign = toggle.column == "signed"
        val targetOn = if (isSign) toggle.record.signed != "已签收" else toggle.record.picked_up != 1
        SalesConfirmDialog(
            title = if (isSign) (if (targetOn) "确认签收" else "取消签收") else (if (targetOn) "确认取件" else "取消取件"),
            message = if (isSign) {
                if (targetOn) "确定将此记录标记为\"已签收\"？签收后表示货物已收到。" else "确定将此记录的签收状态改回\"未签收\"？此操作需谨慎。"
            } else {
                if (targetOn) "确定将此记录标记为\"已取件\"？" else "确定将此记录的取件状态改回\"未取件\"？"
            },
            confirmText = if (isSign) (if (targetOn) "确认签收" else "取消签收") else (if (targetOn) "确认取件" else "取消取件"),
            onDismiss = { toggleState = null },
            onConfirm = {
                scope.launch {
                    val value = if (isSign) (if (targetOn) "已签收" else "未签收") else (if (targetOn) "1" else "0")
                    SalesController.updateCell(context, toggle.record.id, toggle.column, value)
                        .onSuccess {
                            SalesState.toast(
                                if (isSign) "已切换为$value" else "已切换为${if (targetOn) "已取件" else "未取件"}",
                            )
                        }
                }
                toggleState = null
            },
        )
    }

    deleteTarget?.let { record ->
        SalesConfirmDialog(
            title = "确认删除",
            message = "确定删除该售后记录（ID: ${record.id}）？",
            confirmText = "删除",
            danger = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch { SalesController.deleteRecord(context, record.id) }
                deleteTarget = null
            },
        )
    }

    expressState?.let { state ->
        SalesExpressDialog(
            state = state,
            result = expressResult,
            loading = expressLoading,
            onDismiss = { expressState = null },
            onQuery = { number ->
                expressState = state.copy(number = number)
                expressLoading = true
                scope.launch {
                    val result = SalesController.queryExpress(context, number)
                    expressLoading = false
                    result.onSuccess { express ->
                        expressResult = express
                        if (express.success) {
                            when {
                                state.column == "return_express" && express.state == "已签收" && state.record.signed != "已签收" ->
                                    smartPrompt = SmartPromptState(state.record, "signed", "已签收")
                                state.column == "return_express" && express.company.isNotBlank() && state.record.return_express_company.isBlank() ->
                                    smartPrompt = SmartPromptState(state.record, "return_express_company", express.company)
                            }
                        }
                    }
                }
            },
        )
        // 打开弹窗时自动查询（照参考 nextTick(queryExpress)）
        LaunchedEffect(state.record.id, state.column) {
            if (expressResult == null && !expressLoading && state.number.isNotBlank()) {
                expressLoading = true
                val result = SalesController.queryExpress(context, state.number)
                expressLoading = false
                result.onSuccess { express ->
                    expressResult = express
                    if (express.success) {
                        when {
                            state.column == "return_express" && express.state == "已签收" && state.record.signed != "已签收" ->
                                smartPrompt = SmartPromptState(state.record, "signed", "已签收")
                            state.column == "return_express" && express.company.isNotBlank() && state.record.return_express_company.isBlank() ->
                                smartPrompt = SmartPromptState(state.record, "return_express_company", express.company)
                        }
                    }
                }
            }
        }
    }

    smartPrompt?.let { prompt ->
        SalesConfirmDialog(
            title = if (prompt.kind == "signed") "签收状态更新" else "自动填充",
            message = if (prompt.kind == "signed") {
                "物流显示已签收，但当前记录签收状态未更新。是否将签收状态设置为\"已签收\"？"
            } else {
                "识别到退货快递公司：${prompt.value}，是否自动填充到\"退货快递公司\"列？"
            },
            confirmText = if (prompt.kind == "signed") "更新" else "填充",
            dismissText = "跳过",
            onDismiss = {
                smartPrompt = null
                // 签收更新后还要继续检查快递公司填充（照参考两个提示可顺序出现）
                val express = expressResult
                val state = expressState
                if (prompt.kind == "signed" && express != null && state != null &&
                    state.column == "return_express" && express.company.isNotBlank() && state.record.return_express_company.isBlank()
                ) {
                    smartPrompt = SmartPromptState(state.record, "return_express_company", express.company)
                }
            },
            onConfirm = {
                scope.launch {
                    SalesController.updateCell(context, prompt.record.id, prompt.kind, prompt.value)
                        .onSuccess {
                            SalesState.toast(if (prompt.kind == "signed") "签收状态已更新" else "退货快递公司已填充")
                        }
                }
                smartPrompt = null
                val express = expressResult
                val state = expressState
                if (prompt.kind == "signed" && express != null && state != null &&
                    state.column == "return_express" && express.company.isNotBlank() && state.record.return_express_company.isBlank()
                ) {
                    smartPrompt = SmartPromptState(state.record, "return_express_company", express.company)
                }
            },
        )
    }

    forceUnlock?.let { pending ->
        SalesConfirmDialog(
            title = "编辑锁定",
            message = "该记录正被 ${pending.lock.display_name.ifBlank { pending.lock.username }} 编辑，是否强制解锁？",
            confirmText = "强制解锁",
            onDismiss = { forceUnlock = null },
            onConfirm = {
                forceUnlock = null
                scope.launch {
                    SalesController.unlockEdit(pending.record.id)
                    delay(300)
                    if (SalesController.lockEdit(pending.record.id)) {
                        cellEdit = CellEditState(pending.record, pending.column, rawCellValue(pending.record, pending.column.prop))
                    }
                }
            },
        )
    }

    if (showColumnConfig) {
        SalesColumnConfigDialog(
            user = user,
            readingMode = ui.readingMode,
            currentVisible = visibleColumns.map { it.prop }.toSet(),
            onDismiss = { showColumnConfig = false },
            onSave = { visibleProps ->
                val map = SALES_COLUMNS.associate { it.prop to (it.prop in visibleProps) }
                SalesPrefs.saveColumnConfig(context, if (ui.readingMode) SalesPrefs.ColumnMode.READING else SalesPrefs.ColumnMode.MOBILE, map)
                columnConfigTick++
                showColumnConfig = false
                SalesState.toast("列配置已保存")
            },
            onReset = {
                SalesPrefs.clearColumnConfig(context, if (ui.readingMode) SalesPrefs.ColumnMode.READING else SalesPrefs.ColumnMode.MOBILE)
                columnConfigTick++
                SalesState.toast("已恢复默认列配置")
            },
        )
    }

    if (showSettings) {
        SalesSettingsDialog(onDismiss = { showSettings = false })
    }

    if (showExport) {
        SalesExportDialog(
            ui = ui,
            onDismiss = { showExport = false },
            onExport = { start, end ->
                showExport = false
                scope.launch { SalesController.exportExcel(context, visibleColumns.map { it.prop }, null, start, end) }
            },
        )
    }

    if (showOnlineUsers) {
        SalesOnlineUsersDialog(ui = ui, onDismiss = { showOnlineUsers = false })
    }

    if (showLogoutConfirm) {
        SalesConfirmDialog(
            title = "退出登录",
            message = "${ui.user?.displayName ?: ""}（${ui.user?.roleLabel ?: ""}）\n退出登录将断开实时同步连接",
            confirmText = "退出登录",
            danger = true,
            onDismiss = { showLogoutConfirm = false },
            onConfirm = {
                showLogoutConfirm = false
                scope.launch { SalesController.logout(context) }
            },
        )
    }

    ui.searchAllPrompt?.let { results ->
        val message = buildString {
            append("当前产品类型无搜索结果，在其他类型中找到：\n")
            results.forEach { append("· ${it.product_name}：${it.total}条\n") }
            append("是否切换到首个有结果的产品类型？")
        }
        SalesConfirmDialog(
            title = "增强搜索",
            message = message,
            confirmText = "切换",
            onDismiss = { SalesController.dismissSearchAllPrompt() },
            onConfirm = {
                SalesController.applySearchAllTarget(context, SalesProductType.fromKey(results.first().product_type))
            },
        )
    }
}

// ====================================================================
// 登录页
// ====================================================================

@Composable
private fun SalesLoginPage(onShowBottomBar: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = SalesState.state
    var username by remember { mutableStateOf(SalesPrefs.accounts(context).firstOrNull()?.username ?: "") }
    var password by remember { mutableStateOf(SalesPrefs.accounts(context).firstOrNull()?.password ?: "") }
    var rememberPassword by remember { mutableStateOf(SalesPrefs.accounts(context).firstOrNull()?.password?.isNotBlank() == true) }
    var loading by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var conflictMessage by remember { mutableStateOf<String?>(null) }
    val accounts = SalesPrefs.accounts(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onShowBottomBar) { Text("‹ 返回导航", color = SalesBrand) }
        }
        Spacer(Modifier.height(28.dp))
        Text("售后管理系统", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SalesBrand)
        Text("远程协同版 · Bridge 集成", fontSize = 12.sp, color = SalesSub, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(22.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Box {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (accounts.isNotEmpty()) {
                                IconButton(onClick = { accountExpanded = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "历史账号")
                                }
                            }
                        },
                    )
                    DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(account.username, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            SalesPrefs.removeAccount(context, account.username)
                                            accountExpanded = false
                                            if (username == account.username) {
                                                password = ""
                                                rememberPassword = false
                                            }
                                        }) { Text("删除", fontSize = 12.sp, color = SalesRed) }
                                    }
                                },
                                onClick = {
                                    username = account.username
                                    password = SalesPrefs.accountPassword(context, account.username)
                                    rememberPassword = password.isNotBlank()
                                    accountExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { /* 点击登录按钮统一处理 */ }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                    Text("记住密码", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            SalesState.toast("请输入用户名和密码", isError = true)
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val result = SalesController.login(context, username, password, rememberPassword, force = false)
                            loading = false
                            result.onFailure { error ->
                                if (error is SalesApiClient.ApiException && error.conflict) {
                                    conflictMessage = error.message
                                } else {
                                    SalesState.toast(error.message ?: "登录失败", isError = true)
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SalesBrand),
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("登录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { showServerDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("服务器地址", color = SalesBrand)
                }
                if (ui.serverUrl.isBlank()) {
                    Text(
                        "APK 场景需先配置服务器地址，例如 http://192.168.31.226:15153",
                        fontSize = 11.sp,
                        color = SalesOrange,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    conflictMessage?.let { message ->
        SalesConfirmDialog(
            title = "登录冲突",
            message = "$message\n是否强制登录？强制登录将使已登录的设备自动下线。",
            confirmText = "强制登录",
            dismissText = "取消登录",
            danger = true,
            onDismiss = { conflictMessage = null },
            onConfirm = {
                conflictMessage = null
                loading = true
                scope.launch {
                    val result = SalesController.login(context, username, password, rememberPassword, force = true)
                    loading = false
                    result.onFailure { SalesState.toast(it.message ?: "强制登录失败", isError = true) }
                }
            },
        )
    }

    if (showServerDialog) {
        SalesServerDialog(
            initial = SalesPrefs.serverUrl(context),
            onDismiss = { showServerDialog = false },
        )
    }
}

@Composable
private fun SalesServerDialog(initial: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器地址") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: http://192.168.1.100:15153") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "请输入完整地址，包含协议和端口；WebSocket 地址将自动推导（http→ws, https→wss）。",
                    fontSize = 12.sp,
                    color = SalesSub,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (SalesController.setServerUrl(context, value)) {
                    onDismiss()
                    SalesState.toast("服务器地址已保存")
                } else {
                    SalesState.toast("地址必须以 http:// 或 https:// 开头", isError = true)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    SalesPrefs.setServerUrl(context, "")
                    SalesState.setServerUrl("")
                    SalesState.toast("已恢复默认服务器地址")
                    onDismiss()
                }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

// ====================================================================
// 主框架：顶栏 / 工具栏 / 筛选 / 表格 / 分页 / 阅读条
// ====================================================================

@Composable
private fun SalesTopBar(
    ui: SalesUiState,
    onShowBottomBar: () -> Unit,
    onRefresh: () -> Unit,
    onOnlineUsers: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onShowBottomBar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回导航", tint = SalesBrand)
            }
            Text("售后", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (ui.wsConnected) SalesGreen.copy(alpha = 0.12f) else SalesSub.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (ui.wsConnected) SalesGreen else SalesSub, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(if (ui.wsConnected) "已连接" else "未连接", fontSize = 11.sp, color = if (ui.wsConnected) SalesGreen else SalesSub)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            IconButton(onClick = onOnlineUsers) {
                BadgedBox(badge = { if (ui.onlineUsers.isNotEmpty()) Badge { Text(ui.onlineUsers.size.toString()) } }) {
                    Icon(Icons.Filled.People, contentDescription = "在线用户")
                }
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出登录", tint = SalesRed)
            }
        }
    }
}

@Composable
private fun SalesToolbar(
    ui: SalesUiState,
    allowedTypes: List<SalesProductType>,
    onTypeChange: (SalesProductType) -> Unit,
    onSearch: (String, Boolean) -> Unit,
    onFilter: () -> Unit,
    onColumns: () -> Unit,
    onSettings: () -> Unit,
    onToggleSelect: () -> Unit,
    onExport: () -> Unit,
    onAdd: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var searchText by remember(ui.productType) { mutableStateOf(ui.keyword) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // 产品类型切换（照移动端 radio 组，按权限过滤）
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                allowedTypes.forEach { type ->
                    val selected = ui.productType == type
                    Text(
                        type.label,
                        fontSize = 13.sp,
                        color = if (selected) Color.White else SalesSub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) SalesBrand else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onTypeChange(type) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("搜索收货人/电话/单号", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        onSearch(searchText.trim(), ui.globalSearch)
                    }),
                    modifier = Modifier.weight(1f).height(48.dp),
                    trailingIcon = {
                        IconButton(onClick = {
                            keyboard?.hide()
                            onSearch(searchText.trim(), ui.globalSearch)
                        }) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { SalesState.setGlobalSearch(!ui.globalSearch) }) {
                    Checkbox(checked = ui.globalSearch, onCheckedChange = { SalesState.setGlobalSearch(it) })
                    Text("全域", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolbarButton("筛选", onFilter)
                ToolbarButton("列配置", onColumns)
                ToolbarButton("⚙ 设置", onSettings)
                ToolbarButton(if (ui.selectMode) "✓ 选择(${ui.selectedIds.size})" else "☐ 选择", onToggleSelect)
                ToolbarButton(if (ui.exporting) "导出中…" else "导出Excel", onExport)
                if (ui.user?.canCreate == true) ToolbarButton("＋ 新增", onAdd, primary = true)
            }
        }
    }
}

@Composable
private fun ToolbarButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (primary) Color.White else SalesBrand,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) SalesBrand else SalesBrand.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun SalesFilterPanel(ui: SalesUiState, onApply: (SalesFilters) -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
    var signed by remember { mutableStateOf(ui.filters.signed) }
    var dateStart by remember { mutableStateOf(ui.filters.dateStart) }
    var dateEnd by remember { mutableStateOf(ui.filters.dateEnd) }
    var hasReturn by remember { mutableStateOf(ui.filters.hasReturnExpress) }
    var shop by remember { mutableStateOf(ui.filters.shopSerial) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            SalesDropdown("签收状态", signed.ifBlank { "全部" }, listOf("全部", "未签收", "已签收")) { signed = if (it == "全部") "" else it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SalesDateField("开始日期", dateStart, { dateStart = it }, Modifier.weight(1f))
                SalesDateField("结束日期", dateEnd, { dateEnd = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            SalesDropdown("退货快递", when (hasReturn) { true -> "有退货快递"; false -> "无退货快递"; null -> "全部" }, listOf("全部", "有退货快递", "无退货快递")) {
                hasReturn = when (it) { "有退货快递" -> true; "无退货快递" -> false; else -> null }
            }
            Spacer(Modifier.height(8.dp))
            SalesDropdown("店铺序号", shop.ifBlank { "全部" }, listOf("全部") + ui.shops) { shop = if (it == "全部") "" else it }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭") }
                TextButton(onClick = onReset) { Text("重置", color = SalesRed) }
                Button(onClick = {
                    onApply(ui.filters.copy(signed = signed, dateStart = dateStart, dateEnd = dateEnd, hasReturnExpress = hasReturn, shopSerial = shop))
                }, colors = ButtonDefaults.buttonColors(containerColor = SalesBrand)) { Text("应用筛选") }
            }
        }
    }
}

@Composable
private fun SalesTable(
    ui: SalesUiState,
    visibleColumns: List<SalesColumn>,
    onCellDouble: (SalesRecord, SalesColumn) -> Unit,
    onRowLong: (SalesRecord, SalesColumn) -> Unit,
    onHeaderLong: (SalesColumn) -> Unit,
) {
    val context = LocalContext.current
    val selectionWidth = if (ui.selectMode) 46 else 0
    val totalWidth = visibleColumns.sumOf { it.width } + selectionWidth
    val scrollState = rememberScrollState(initial = SalesPrefs.tableScrollX(context, ui.productType))

    DisposableEffect(ui.productType) {
        onDispose { SalesPrefs.saveTableScrollX(context, ui.productType, scrollState.value) }
    }
    LaunchedEffect(ui.records.size) {
        val saved = SalesPrefs.tableScrollX(context, ui.productType)
        if (scrollState.value == 0 && saved > 0) scrollState.scrollTo(saved)
    }

    if (visibleColumns.isEmpty()) {
        EmptySalesBox("当前账号没有可见列，请联系管理员配置 visible_columns")
        return
    }

    my.bridge.com.core.common.DiagLog.i("销售页", "SalesTable 渲染：records=${ui.records.size} visibleColumns=${visibleColumns.size} pageLoading=${ui.pageLoading}")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 表头（固定，横向滚动；与数据行共享 scrollState 实现横向同步）
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .heightIn(min = 44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.width(totalWidth.dp).fillMaxHeight()) {
                if (ui.selectMode) {
                    Box(Modifier.width(46.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = ui.selectedIds.size == ui.records.size && ui.records.isNotEmpty(),
                            onCheckedChange = { checked -> SalesState.setSelected(if (checked) ui.records.map { it.id }.toSet() else emptySet()) },
                        )
                    }
                }
                visibleColumns.forEach { column ->
                    Column(
                        modifier = Modifier
                            .width(column.width.dp)
                            .fillMaxHeight()
                            .combinedClickable(onClick = {}, onLongClick = { onHeaderLong(column) })
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(column.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (ui.filters.sortColumn == column.prop) {
                                Text(if (ui.filters.sortOrder == "asc") "↑" else "↓", fontSize = 11.sp, color = SalesBrand)
                            }
                            if (ui.columnFilters.containsKey(column.prop)) {
                                Box(Modifier.padding(start = 3.dp).size(6.dp).background(SalesBrand, CircleShape))
                            }
                        }
                        if (ui.columnFilters.containsKey(column.prop)) {
                            var value by remember(column.prop, ui.columnFilters[column.prop]) { mutableStateOf(ui.columnFilters[column.prop] ?: "") }
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                singleLine = true,
                                placeholder = { Text("搜索此列", fontSize = 10.sp) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { SalesController.setColumnFilter(context, column.prop, value) }),
                                modifier = Modifier.fillMaxWidth().height(34.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (ui.records.isEmpty() && !ui.pageLoading) {
            EmptySalesBox(if (ui.keyword.isBlank()) "暂无售后记录" else "未找到匹配记录")
        } else {
            // 数据体：横向滚动容器（与表头共享 scrollState 同步）包裹 LazyColumn（纵向滚动）。
            // 关键：LazyColumn 必须在「有确定高度」的容器里（这里 Box 用 weight(1f) 占满剩余高度），
            // 不能直接放进 horizontalScroll 的 Column 里——否则视口高度约束被放宽，item 不渲染。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                LazyColumn(modifier = Modifier.width(totalWidth.dp).fillMaxHeight()) {
                    items(ui.records, key = { it.id }) { record ->
                        SalesTableRow(
                            ui = ui,
                            record = record,
                            visibleColumns = visibleColumns,
                            onCellDouble = onCellDouble,
                            onRowLong = onRowLong,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesTableRow(
    ui: SalesUiState,
    record: SalesRecord,
    visibleColumns: List<SalesColumn>,
    onCellDouble: (SalesRecord, SalesColumn) -> Unit,
    onRowLong: (SalesRecord, SalesColumn) -> Unit,
) {
    val rowBg = parseRowColor(record.row_color)?.copy(alpha = 0.18f) ?: MaterialTheme.colorScheme.surface
    if (record.id <= 1052 && record.id >= 1050) {
        my.bridge.com.core.common.DiagLog.i("销售页", "SalesTableRow 渲染 id=${record.id} receiver=${record.receiver} goods=${record.goods}")
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).background(rowBg)) {
        if (ui.selectMode) {
            Box(Modifier.width(46.dp).fillMaxHeight().clickable { SalesState.toggleSelected(record.id) }, contentAlignment = Alignment.Center) {
                Checkbox(checked = record.id in ui.selectedIds, onCheckedChange = { SalesState.toggleSelected(record.id) })
            }
        }
        visibleColumns.forEachIndexed { index, column ->
            val locked = SalesState.lockFor(ui.productType, record.id) != null
            Box(
                modifier = Modifier
                    .width(column.width.dp)
                    .fillMaxHeight()
                    .border(width = 0.5.dp, color = SalesBorder)
                    .combinedClickable(
                        onClick = { if (ui.selectMode) SalesState.toggleSelected(record.id) },
                        onDoubleClick = { if (!ui.selectMode) onCellDouble(record, column) },
                        onLongClick = { if (!ui.selectMode) onRowLong(record, column) },
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                contentAlignment = if (column.prop == "signed" || column.prop == "picked_up" || column.prop == "return_freight") Alignment.Center else Alignment.CenterStart,
            ) {
                when (column.prop) {
                    "signed" -> StatusTag(record.signed, if (record.signed == "已签收") SalesGreen else SalesOrange)
                    "picked_up" -> StatusTag(if (record.picked_up == 1) "已取件" else "未取件", if (record.picked_up == 1) SalesGreen else SalesSub)
                    else -> Text(
                        displayCellValue(record, column.prop),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (column.prop == "return_freight") TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (locked && index == 0) {
                    Text("🔒", fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(1.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SalesPaginationBar(ui: SalesUiState, onPage: (Int) -> Unit, onPageSize: (Int) -> Unit, onReading: () -> Unit) {
    var sizeMenu by remember { mutableStateOf(false) }
    val virtual = ui.size >= 9999
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReading) { Text("阅读", color = SalesBrand) }
            Spacer(Modifier.weight(1f))
            if (virtual) {
                Text("共 ${ui.total} 条（已加载全部）", fontSize = 12.sp, color = SalesSub)
            } else {
                TextButton(onClick = { if (ui.page > 1) onPage(ui.page - 1) }, enabled = ui.page > 1) { Text("◀") }
                pageNumbers(ui.page, ui.totalPages).forEach { page ->
                    Text(
                        page.toString(),
                        fontSize = 12.sp,
                        color = if (page == ui.page) Color.White else SalesBrand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (page == ui.page) SalesBrand else Color.Transparent)
                            .clickable { onPage(page) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                TextButton(onClick = { if (ui.page < ui.totalPages) onPage(ui.page + 1) }, enabled = ui.page < ui.totalPages) { Text("▶") }
                Box {
                    TextButton(onClick = { sizeMenu = true }) { Text("${ui.size}/页", fontSize = 12.sp) }
                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                        listOf(20, 50, 100, 200, 9999).forEach { size ->
                            DropdownMenuItem(text = { Text(if (size == 9999) "全量" else "$size/页") }, onClick = {
                                sizeMenu = false
                                onPageSize(size)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesReadingBar(ui: SalesUiState, onExit: () -> Unit, onColumns: () -> Unit, onPage: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("退出阅读", color = SalesBrand) }
            Text("|", color = SalesBorder)
            TextButton(onClick = onColumns) { Text("列", color = SalesBrand) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { if (ui.page > 1) onPage(ui.page - 1) }, enabled = ui.page > 1) { Text("◀") }
            Text("${ui.page}/${ui.totalPages}", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
            TextButton(onClick = { if (ui.page < ui.totalPages) onPage(ui.page + 1) }, enabled = ui.page < ui.totalPages) { Text("▶") }
        }
    }
}

@Composable
private fun EmptySalesBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = SalesSub, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SalesNoticeOverlay(ui: SalesUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        ui.notices.forEach { notice ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (notice.isError) SalesRed.copy(alpha = 0.94f) else Color(0xF0243348)),
                modifier = Modifier.padding(bottom = 6.dp).width(260.dp),
            ) {
                Text(notice.text, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

// ====================================================================
// 弹窗：单元格编辑 / 表单 / 物流 / 配置 / 导出 / 在线用户
// ====================================================================

@Composable
private fun SalesCellEditDialog(state: CellEditState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(state.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑${state.column.label}") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = state.column.prop != "remarks",
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (state.column.prop == "return_freight" || state.column.prop == "picked_up") KeyboardType.Decimal else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSave(value) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存", color = SalesBrand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SalesRecordFormDialog(state: RecordFormState, productType: SalesProductType, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(state.record ?: SalesRecord(date = todayString())) }
    var saving by remember { mutableStateOf(false) }
    var matchHint by remember { mutableStateOf(false) }
    val lastMatch = remember { mutableStateMapOf<String, String>() }

    fun autoMatch(field: String, value: String) {
        if (state.isEdit || value.isBlank() || lastMatch[field] == value) return
        lastMatch[field] = value
        scope.launch {
            val match = SalesController.matchSales(context, field, value)
            if (match != null) {
                var changed = false
                fun fill(key: String, current: String, set: (String) -> SalesRecord) {
                    if (current.isBlank() && !match[key].isNullOrBlank()) {
                        form = set(match[key]!!)
                        changed = true
                    }
                }
                fill("serial", form.serial) { form.copy(serial = it) }
                fill("shop_serial", form.shop_serial) { form.copy(shop_serial = it) }
                fill("date", form.date) { form.copy(date = it) }
                fill("goods", form.goods) { form.copy(goods = it) }
                fill("send_express", form.send_express) { form.copy(send_express = it) }
                fill("order_no", form.order_no) { form.copy(order_no = it) }
                fill("receiver", form.receiver) { form.copy(receiver = it) }
                fill("phone", form.phone) { form.copy(phone = it) }
                if (changed) {
                    matchHint = true
                    SalesState.toast("已自动匹配并填充销售记录信息")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isEdit) "编辑售后记录" else "新增售后记录") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (matchHint) {
                    Text("已根据销售记录自动填充空白字段", color = SalesGreen, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("序号", form.serial, { form = form.copy(serial = it) }, Modifier.weight(1f))
                    FormField("店铺序号", form.shop_serial, { form = form.copy(shop_serial = it) }, Modifier.weight(1f))
                }
                SalesDateField("日期 *", form.date, { form = form.copy(date = it) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("收货人 *", form.receiver, { form = form.copy(receiver = it) }, Modifier.weight(1f), onBlur = { autoMatch("receiver", it) })
                    FormField("电话 *", form.phone, { form = form.copy(phone = it) }, Modifier.weight(1f), placeholder = "如 REF-13800138000", onBlur = { autoMatch("phone", it) })
                }
                FormField("货物 *", form.goods, { form = form.copy(goods = it) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("订单号", form.order_no, { form = form.copy(order_no = it) }, Modifier.weight(1f), onBlur = { autoMatch("order_no", it) })
                    FormField("发货快递", form.send_express, { form = form.copy(send_express = it) }, Modifier.weight(1f), onBlur = { autoMatch("send_express", it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("退货快递", form.return_express, { form = form.copy(return_express = it) }, Modifier.weight(1f))
                    FormField("退货快递公司", form.return_express_company, { form = form.copy(return_express_company = it) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("退货运费", if (form.return_freight == 0.0) "" else form.return_freight.toString().removeSuffix(".0"), {
                        form = form.copy(return_freight = it.toDoubleOrNull() ?: 0.0)
                    }, Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                    Column(Modifier.weight(1f)) {
                        Text("签收状态", fontSize = 12.sp, color = SalesSub)
                        SalesDropdown("", form.signed, listOf("未签收", "已签收")) { form = form.copy(signed = it) }
                    }
                }
                FormField("行颜色", form.row_color, { form = form.copy(row_color = it) }, Modifier.fillMaxWidth(), placeholder = "#f56c6c / #e6a23c / #67c23a / #409eff")
                FormField("备注", form.remarks, { form = form.copy(remarks = it) }, Modifier.fillMaxWidth(), singleLine = false)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val missing = buildList {
                    if (form.date.isBlank()) add("日期")
                    if (form.receiver.isBlank()) add("收货人")
                    if (form.phone.isBlank()) add("电话")
                    if (form.goods.isBlank()) add("货物")
                }
                if (missing.isNotEmpty()) {
                    SalesState.toast("请填写：${missing.joinToString("、")}", isError = true)
                    return@TextButton
                }
                saving = true
                scope.launch {
                    val result = if (state.isEdit) SalesController.updateRecord(context, form) else SalesController.createRecord(context, form)
                    saving = false
                    result.onSuccess { onSaved() }
                }
            }) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (state.isEdit) "保存" else "添加", color = SalesBrand)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SalesExpressDialog(state: ExpressDialogState, result: ExpressResult?, loading: Boolean, onDismiss: () -> Unit, onQuery: (String) -> Unit) {
    var number by remember { mutableStateOf(state.number) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("物流查询") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("快递单号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onQuery(number.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (result != null && (result.company.isNotBlank() || result.state.isNotBlank())) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (result.company.isNotBlank()) StatusTag(result.company, SalesBrand)
                        if (result.state.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            StatusTag(result.state, if (result.state == "已签收") SalesGreen else SalesOrange)
                        }
                    }
                }
                if (loading) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (result != null) {
                    if (result.traces.isEmpty()) {
                        Text(result.message.ifBlank { "未查询到物流信息" }, color = SalesSub, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            items(result.traces) { trace ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    Text("●", color = SalesBrand, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(trace.time, fontSize = 11.sp, color = SalesSub)
                                        Text(trace.desc, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onQuery(number.trim()) }) { Text("查询", color = SalesBrand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SalesColumnConfigDialog(
    user: my.bridge.com.sales.SalesUser?,
    readingMode: Boolean,
    currentVisible: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    val visible = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        SALES_COLUMNS.forEach { visible[it.prop] = it.prop in currentVisible }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (readingMode) "阅读模式列配置" else "表格列配置") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                items(SALES_COLUMNS.filter { user?.canSeeColumn(it.prop) != false }) { column ->
                    Row(
                        Modifier.fillMaxWidth().clickable { visible[column.prop] = !(visible[column.prop] ?: false) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = visible[column.prop] ?: false, onCheckedChange = { visible[column.prop] = it })
                        Text(column.label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(visible.filterValues { it }.keys) }) { Text("保存", color = SalesBrand) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复默认", color = SalesRed) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun SalesSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(SalesPrefs.serverUrl(context)) }
    var cookie by remember { mutableStateOf("") }
    var cookieStatus by remember { mutableStateOf<CookieStatus?>(null) }
    var savingCookie by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        SalesController.cookieStatus(context).onSuccess { cookieStatus = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统设置") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: http://192.168.1.100:15153") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("WebSocket 地址将自动推导（http→ws, https→wss）", fontSize = 11.sp, color = SalesSub, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Text("百度 Cookie（物流查询依赖）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val status = cookieStatus
                    if (status == null) {
                        Text("状态获取中…", fontSize = 12.sp, color = SalesSub)
                    } else {
                        StatusTag(if (status.has_cookie) "已配置（${status.cookie_count}）" else "未配置", if (status.has_cookie) SalesGreen else SalesSub)
                        if (status.has_bduss) {
                            Spacer(Modifier.width(6.dp))
                            StatusTag("含 BDUSS", SalesGreen)
                        }
                    }
                }
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("粘贴完整 Cookie") },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                Text(
                    "获取步骤：电脑浏览器登录百度 → F12 开发者工具 → 网络 → 刷新页面 → 任意请求 → 请求头中的 Cookie → 全选复制粘贴到此处。",
                    fontSize = 11.sp,
                    color = SalesSub,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (cookie.isBlank()) {
                    SalesState.toast("请输入Cookie内容", isError = true)
                    return@TextButton
                }
                savingCookie = true
                scope.launch {
                    SalesController.saveCookie(context, cookie)
                        .onSuccess {
                            cookie = ""
                            SalesController.cookieStatus(context).onSuccess { cookieStatus = it }
                        }
                    savingCookie = false
                }
            }) { Text(if (savingCookie) "保存中…" else "保存Cookie", color = SalesBrand) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (SalesController.setServerUrl(context, server)) SalesState.toast("服务器地址已保存")
                    else SalesState.toast("地址必须以 http:// 或 https:// 开头", isError = true)
                }) { Text("保存地址") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun SalesExportDialog(ui: SalesUiState, onDismiss: () -> Unit, onExport: (String, String) -> Unit) {
    var start by remember { mutableStateOf(defaultMonthStart()) }
    var end by remember { mutableStateOf(todayString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出Excel") },
        text = {
            Column {
                Text("产品类型：${ui.productType.label}", fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                SalesDateField("开始日期 *", start, { start = it }, Modifier.fillMaxWidth())
                SalesDateField("结束日期 *", end, { end = it }, Modifier.fillMaxWidth())
                Text("将按当前筛选/搜索条件和可见列导出，行颜色会一并写入。", fontSize = 12.sp, color = SalesSub, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (start.isBlank() || end.isBlank()) SalesState.toast("请选择导出的时间段", isError = true)
                else onExport(start, end)
            }) { Text("导出", color = SalesBrand) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SalesOnlineUsersDialog(ui: SalesUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线用户（${ui.onlineUsers.size}）") },
        text = {
            if (ui.onlineUsers.isEmpty()) {
                Text("暂无在线用户", color = SalesSub)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ui.onlineUsers) { user ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(user.display_name.ifBlank { user.username }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                StatusTag(if (user.role == "admin") "管理员" else if (user.role == "manager") "经理" else "操作员", if (user.role == "admin") SalesRed else if (user.role == "manager") SalesOrange else SalesBrand)
                            }
                            Text("${user.device_count} 台设备 · ${user.connected_at.take(19).replace('T', ' ')}", fontSize = 11.sp, color = SalesSub)
                            user.devices.forEach { device ->
                                Text("· ${device.client_type}/${device.os} ${device.browser}", fontSize = 11.sp, color = SalesSub)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SalesRowMenuDialog(
    menu: RowMenuState,
    ui: SalesUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleSign: () -> Unit,
    onTogglePicked: () -> Unit,
    onExpress: () -> Unit,
    onColor: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val column = menu.column.prop
    val user = ui.user
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录操作") },
        text = {
            Column {
                if (user?.canEditAll == true) MenuItem("✏️ 编辑记录", onEdit)
                if (user?.canEditColumn("signed") == true) MenuItem("✅ 切换签收", onToggleSign)
                if (user?.canEditColumn("picked_up") == true) MenuItem("📦 切换取件", onTogglePicked)
                if (column == "send_express" || column == "return_express") MenuItem("🚚 查询物流", onExpress)
                if (user?.canEditColumn("row_color") == true) {
                    Text("标记颜色", fontSize = 13.sp, color = SalesSub, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("#f56c6c" to "红", "#e6a23c" to "黄", "#67c23a" to "绿", "#409eff" to "蓝", "" to "清除").forEach { (color, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onColor(color) }) {
                                Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseRowColor(color) ?: Color.Transparent).border(1.dp, SalesBorder, RoundedCornerShape(5.dp)))
                                Text(label, fontSize = 10.sp)
                            }
                        }
                    }
                }
                if (user?.canDelete == true) MenuItem("🗑 删除记录", onDelete, danger = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SalesHeaderMenuDialog(
    menu: HeaderMenuState,
    ui: SalesUiState,
    onDismiss: () -> Unit,
    onSort: (String) -> Unit,
    onClearSort: () -> Unit,
    onFilter: () -> Unit,
    onClearFilter: () -> Unit,
    onHide: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("列：${menu.column.label}") },
        text = {
            Column {
                MenuItem("↑ 升序排列", onClick = { onSort("asc") })
                MenuItem("↓ 降序排列", onClick = { onSort("desc") })
                MenuItem("✕ 清除排序", onClearSort, enabled = ui.filters.sortColumn == menu.column.prop && (menu.column.prop != "date" || ui.filters.sortOrder != "desc"))
                MenuItem("🔍 搜索此列", onFilter)
                MenuItem("✕ 清除列搜索", onClearFilter, enabled = ui.columnFilters.containsKey(menu.column.prop))
                MenuItem("🙈 隐藏此列", onHide)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        color = if (!enabled) SalesSub else if (danger) SalesRed else MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    )
}

@Composable
private fun SalesConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String = "取消",
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = if (danger) SalesRed else SalesBrand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

// ====================================================================
// 小组件 / 工具函数
// ====================================================================

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onBlur: (String) -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder, fontSize = 11.sp) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(vertical = 4.dp).onFocusChanged { if (!it.isFocused) onBlur(value) },
    )
}

@Composable
private fun SalesDateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("YYYY-MM-DD", fontSize = 11.sp) },
            trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = "选择日期") },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable {
            val cal = Calendar.getInstance()
            runCatching {
                val parts = value.split("-")
                if (parts.size == 3) cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
            DatePickerDialog(
                context,
                { _, year, month, day -> onChange("%04d-%02d-%02d".format(year, month + 1, day)) },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        })
    }
}

@Composable
private fun SalesDropdown(label: String, value: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { if (label.isNotBlank()) Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    onSelected(option)
                })
            }
        }
    }
}

private fun handleCellDouble(
    context: android.content.Context,
    ui: SalesUiState,
    record: SalesRecord,
    column: SalesColumn,
    onOpenEdit: (CellEditState) -> Unit,
    onToggle: (ToggleState) -> Unit,
    onForceUnlock: (ForceUnlockState) -> Unit,
) {
    val user = ui.user ?: return
    if (!user.canEditColumn(column.prop)) return
    if (column.prop == "signed" || column.prop == "picked_up") {
        onToggle(ToggleState(record, column.prop))
        return
    }
    val editable = setOf("serial", "shop_serial", "date", "receiver", "phone", "goods", "order_no", "send_express", "return_express", "return_express_company", "return_freight", "picked_up", "remarks", "row_color")
    if (column.prop !in editable) return
    val lock = SalesState.lockFor(ui.productType, record.id)
    if (lock != null && lock.username != user.username) {
        if (SalesController.canForceUnlock(lock)) {
            onForceUnlock(ForceUnlockState(record, column, lock))
        } else {
            SalesState.toast("该记录正被 ${lock.display_name.ifBlank { lock.username }} 编辑，您无权强制解锁", isError = true)
        }
        return
    }
    // 自己其它窗口持有锁：照参考同级别不能强解，提示即可
    if (lock != null && lock.username == user.username) {
        SalesState.toast("该记录正被你在其他窗口编辑，同级别无法强制解锁", isError = true)
        return
    }
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        if (SalesController.lockEdit(record.id)) {
            onOpenEdit(CellEditState(record, column, rawCellValue(record, column.prop)))
        }
    }
}

private fun effectiveVisibleColumns(context: android.content.Context, user: my.bridge.com.sales.SalesUser?, readingMode: Boolean): List<SalesColumn> {
    val allowed = SALES_COLUMNS.filter { user?.canSeeColumn(it.prop) != false }
    val config = SalesPrefs.columnConfig(context, if (readingMode) SalesPrefs.ColumnMode.READING else SalesPrefs.ColumnMode.MOBILE)
    if (config == null) {
        return allowed.filter { if (readingMode) it.readingDefault else it.mobileDefault }
    }
    return allowed.filter { config[it.prop] ?: (if (readingMode) it.readingDefault else it.mobileDefault) }
}

private fun saveColumnVisibility(context: android.content.Context, readingMode: Boolean, visibleColumns: List<SalesColumn>, prop: String, visible: Boolean) {
    val current = SALES_COLUMNS.associate { it.prop to (it.prop in visibleColumns.map { c -> c.prop }.toSet()) }
    val next = current + (prop to visible)
    SalesPrefs.saveColumnConfig(context, if (readingMode) SalesPrefs.ColumnMode.READING else SalesPrefs.ColumnMode.MOBILE, next)
}

private fun rawCellValue(record: SalesRecord, prop: String): String = when (prop) {
    "picked_up" -> record.picked_up.toString()
    "return_freight" -> record.return_freight.toString().removeSuffix(".0")
    else -> record.value(prop)
}

private fun displayCellValue(record: SalesRecord, prop: String): String {
    val value = record.value(prop)
    return if (prop == "phone" && value.startsWith("REF-")) value.drop(4) else value
}

private fun parseRowColor(color: String): Color? = runCatching {
    if (color.isBlank()) return null
    Color(android.graphics.Color.parseColor(color))
}.getOrNull()

private fun pageNumbers(current: Int, total: Int): List<Int> {
    if (total <= 5) return (1..total).toList()
    val start = (current - 2).coerceIn(1, total - 4)
    return (start until start + 5).toList()
}

private fun todayString(): String {
    val cal = Calendar.getInstance()
    return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
}

private fun defaultMonthStart(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MONTH, -1)
    return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
}
