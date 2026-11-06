package my.bridge.com.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import my.bridge.com.R
import my.bridge.com.jikong.BleDevice
import my.bridge.com.jikong.JikongBleClient
import my.bridge.com.jikong.JikongController
import my.bridge.com.jikong.JikongPrefs
import my.bridge.com.jikong.JkMileageRecord
import my.bridge.com.jikong.JikongStatsStore
import my.bridge.com.jikong.JikongStatsSync
import my.bridge.com.jikong.SpeedDash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 极空页配色（定档：assets/design/ui-mockup-jikong-home.html，深浅两套 CSS 变量 1:1 对应）
private val JkBlue = Color(0xFF1A6EE8)    // 仅旧页面/按钮用
private val JkGreen = Color(0xFF00E050)   // 官方荧光绿数字（旧页面用，首页走 JkColors.green）
private val JkRed = Color(0xFFE5484D)

/** 主题配色（定档 HTML :root / body.light 全量变量）。 */
private data class JkColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val gray: Color,
    val divider: Color,
    val green: Color,
    val cyan: Color,
    val red: Color,
    val blue: Color,
    val track: Color,   // 进度条/弧灰色轨道
    val tabbar: Color,  // 底部导航背景
    val tabline: Color, // 底部导航顶部分隔线
    val g1: Color, val g2: Color, val g3: Color, // 仪表盘弧渐变三段
    val numA: Color,    // 刻度大数字（逢10）
    val numB: Color,    // 刻度小数字（逢5）
    val onfill: Color,  // 实心胶囊上的文字色
    val badge: Color,   // 实时页序号徽标底色
    val badgeText: Color, // 序号徽标文字色
    val amber: Color,   // 单体电压最低值（与最高红色区分明显）
)

private val DarkColors = JkColors(
    bg = Color(0xFF000000), card = Color(0xFF26282B), text = Color(0xFFE8EAED),
    gray = Color(0xFF8A919F), divider = Color(0xFF33363A),
    green = Color(0xFF00E050), cyan = Color(0xFF00B8D4), red = Color(0xFFE5484D), blue = Color(0xFF4D8BF5),
    track = Color(0xFF3A3D40), tabbar = Color(0xFF1A1C1F), tabline = Color(0xFF222222),
    g1 = Color(0xFF00E050), g2 = Color(0xFF00C8A0), g3 = Color(0xFF00A8E8),
    numA = Color(0xFFC8CED6), numB = Color(0xFF6B727C), onfill = Color(0xFF06210E),
    badge = Color(0xFF1F3A66), badgeText = Color(0xFFFFFFFF),
    amber = Color(0xFFFFD60A),
)

private val LightColors = JkColors(
    bg = Color(0xFFEEF1F5), card = Color(0xFFFFFFFF), text = Color(0xFF17191D),
    gray = Color(0xFF5D6672), divider = Color(0xFFDFE3E8),
    green = Color(0xFF00A344), cyan = Color(0xFF0097B8), red = Color(0xFFD93036), blue = Color(0xFF2F6FD8),
    track = Color(0xFFD7DBE1), tabbar = Color(0xFFFFFFFF), tabline = Color(0xFFE3E6EA),
    g1 = Color(0xFF00B050), g2 = Color(0xFF00A8A0), g3 = Color(0xFF0A84D0),
    numA = Color(0xFF2A2F36), numB = Color(0xFF9AA2AD), onfill = Color(0xFFFFFFFF),
    badge = Color(0xFFD6E4FF), badgeText = Color(0xFF1A3C8C),
    amber = Color(0xFFB45309),
)

/** CompositionLocal 提供当前主题配色。 */
private val LocalJkColors = androidx.compose.runtime.compositionLocalOf { DarkColors }

/**
 * 极空页沉浸态（照 SshUiState/WechatState 状态驱动惯例）：
 * - immersive=true 时 MainScreen 隐藏主页顶部 Tab 栏与 APP 底部工具栏（微信/销售页同款沉浸）；
 * - exitToken 自增触发 MainScreen 的 LaunchedEffect 把 homeTab 打回首 Tab（双击返回键回主页）。
 */
object JkUiState {
    var immersive by androidx.compose.runtime.mutableStateOf(false)
    var exitToken by androidx.compose.runtime.mutableIntStateOf(0)
}

/** 根据主题模式（AUTO 跟随时间 / 手动指定优先）解析是否深色。mode 由调用方传入状态值，保证点击即时切换。 */
private fun resolveDarkTheme(context: android.content.Context, mode: Int): Boolean {
    return when (mode) {
        JikongPrefs.THEME_LIGHT -> false
        JikongPrefs.THEME_DARK -> true
        else -> {
            // 自动：跟随当前时间，白天(6:00~18:00)浅色、夜晚深色
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            hour < 6 || hour >= 18
        }
    }
}

/**
 * 极空保护板页面（主页第 6 个 Tab，组网之后）。
 *
 * 复刻极空官方 APP（JK BMS）三页结构：首页（仪表盘）/ 实时状态（详细数据）/ 参数设置；
 * v0.2.85 在实时状态与参数设置之间新增第 4 页「统计」（里程台账/能耗曲线，自研扩展）。
 * 数据字段偏移已按真机字节逐字段校准确认（peff74/BHP1000 偏移）。
 */
@Composable
fun JikongPage() {
    val context = LocalContext.current
    val controller = remember { JikongController.instance }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 主 Tab：首页 / 实时状态 / 统计 / 参数设置（首页在最左，默认首页）
    var tab by remember { mutableIntStateOf(0) }

    // 扫描结果：从 controller 读进程级暂存（切页不丢失）；扫描中/连接中为页面级瞬态
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    // 「显示全部设备」降级开关：默认按极空 UUID 过滤（官方行为）；真机 UUID 不匹配时切全部
    var showAll by remember { mutableStateOf(false) }

    // 权限：BLE 扫描需要定位权限（Android 12+ 需要 BLUETOOTH_SCAN，12- 需要定位）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 授权结果交给 scan 前的检查 */ }

    fun hasBlePermission(): Boolean {
        val a = context
        val fine = a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val scan = android.os.Build.VERSION.SDK_INT < 31 ||
            a.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connect = android.os.Build.VERSION.SDK_INT < 31 ||
            a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return (fine || coarse) && scan && connect
    }

    // v0.2.88：连接只需 BLUETOOTH_CONNECT（12+），不需要定位/扫描权限（自动连默认设备用）
    fun hasConnectPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** 蓝牙就绪检查（权限 + 开关），不就绪时弹对应 Toast 并按需请求权限。 */
    fun ensureBleReady(): Boolean {
        if (!hasBlePermission()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
            permLauncher.launch(perms.toTypedArray())
            return false
        }
        if (!JikongBleClient.isBluetoothEnabled(context)) {
            Toast.makeText(context, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** 设备显示名：昵称优先（v0.2.88）。 */
    fun displayNameOf(mac: String, fallback: String?): String =
        JikongPrefs.nickname(context, mac) ?: fallback?.takeIf { it.isNotBlank() } ?: mac

    /** 正常扫描（不含默认设备逻辑；供点扫描与默认设备回退共用）。 */
    fun doNormalScan() {
        scanning = true
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val result = JikongBleClient.scan(context, durationMs = 8000, filterByUuid = !showAll)
                controller.cacheScanResult(result)
            } catch (e: Exception) {
                android.util.Log.w("Jikong", "扫描失败", e)
            } finally {
                scanning = false
            }
        }
    }

    fun doConnect(dev: BleDevice, isDefault: Boolean = false, fallbackScan: Boolean = false) {
        connecting = true
        kotlinx.coroutines.GlobalScope.launch {
            val ok = try {
                controller.connect(context, dev.address)
            } catch (e: Exception) {
                android.util.Log.w("Jikong", "连接失败", e)
                false
            }
            connecting = false
            // Toast 必须在主线程（GlobalScope.launch 默认后台线程无 Looper，直接 Toast 会 NPE 崩溃）
            val msg = when {
                ok -> "已连接 ${displayNameOf(dev.address, dev.name)}"
                isDefault && fallbackScan -> "默认设备连接失败，转为正常扫描"
                else -> "连接失败，请靠近设备重试"
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                // v0.2.88：默认设备直连失败 → 自动回退正常扫描（只回退一次，防循环）。
                // 权限/蓝牙在进入默认直连前刚检查过，直接复用 doNormalScan（也避免
                // 局部函数 doConnect 前向引用 doScan 的循环依赖）。
                if (!ok && fallbackScan) {
                    doNormalScan()
                }
            }
        }
    }

    fun doScan(tryDefault: Boolean = true) {
        if (!ensureBleReady()) return
        // v0.2.88 默认设备：点扫描时若有默认设备，跳过扫描直接连接；
        // 连接失败自动回退正常扫描（doConnect 里触发 doScan(tryDefault=false)）。
        val defMac = JikongPrefs.defaultMac(context)
        if (tryDefault && defMac.isNotBlank()) {
            val cached = controller.cachedDevices.firstOrNull { it.address == defMac }
            doConnect(
                BleDevice(defMac, displayNameOf(defMac, cached?.name), cached?.rssi ?: 0),
                isDefault = true,
                fallbackScan = true,
            )
            return
        }
        doNormalScan()
    }

    // v0.2.88：进入极空页自动连默认设备（用户拍板）。仅未连接、非演示、有默认设备、
    // 有连接权限且蓝牙开启时尝试一次；失败仅 Toast，不自动转扫描（点扫描按钮才兜底）。
    LaunchedEffect(Unit) {
        if (controller.status.connected || controller.demoMode) return@LaunchedEffect
        val defMac = JikongPrefs.defaultMac(context)
        if (defMac.isBlank() || !hasConnectPermission()) return@LaunchedEffect
        if (!JikongBleClient.isBluetoothEnabled(context)) return@LaunchedEffect
        val cached = controller.cachedDevices.firstOrNull { it.address == defMac }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            Toast.makeText(context, "正在连接默认设备…", Toast.LENGTH_SHORT).show()
        }
        doConnect(
            BleDevice(defMac, displayNameOf(defMac, cached?.name), cached?.rssi ?: 0),
            isDefault = true,
        )
    }

    // v0.2.83 时速仪表盘开关（默认关）：开=页面可见时启动 GPS+积分；关=完全零成本。
    // 状态化（主题切换同款经验）：改开关即时生效，UI 与 SpeedDash 生命周期同步联动。
    // 注意：必须先于下方生命周期 DisposableEffect 声明（观察者闭包引用本状态）。
    var dashEnabled by remember { mutableStateOf(JikongPrefs.dashEnabled(context)) }
    val toggleDash: (Boolean) -> Unit = { on ->
        dashEnabled = on
        JikongPrefs.setDashEnabled(context, on)
        if (on) SpeedDash.start(context) else SpeedDash.stop()
    }

    // 页面可见性守卫：ON_RESUME 标记可见并开始轮询，ON_PAUSE 释放
    DisposableEffect(Unit) {
        controller.setPageVisible(true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    controller.setPageVisible(true)
                    // v0.2.83：仪表盘跟随页面可见性（开关开 + 已连接才启动；stop/start 均幂等）
                    if (dashEnabled && controller.status.connected) SpeedDash.start(context) else SpeedDash.stop()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    controller.setPageVisible(false)
                    SpeedDash.stop()   // 离开页面 → GPS 注销 + 积分暂停（零后台成本）
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.setPageVisible(false)
        }
    }

    // 订阅状态版本号：数据每变一次这里就重组，子页面随之实时刷新。
    // 读一次即可建立 Compose 快照订阅关系（值本身不重要，关键是触发重组）。
    controller.statusVersion.value

    // 背景主题：自动跟随时间（默认），手动指定浅色/深色则优先。
    // v0.2.67：resolveDarkTheme 改为吃本状态值——改前在函数内读 Prefs，Compose 订阅不到，
    // 导致点击「浅色/深色」标签变了但页面配色要下次进入才切换。
    var themeMode by remember { mutableIntStateOf(JikongPrefs.theme(context)) }
    val isDark = resolveDarkTheme(context, themeMode)
    val colors = if (isDark) DarkColors else LightColors
    val cycleTheme: () -> Unit = {
        val next = (themeMode + 1) % 3
        themeMode = next
        JikongPrefs.setTheme(context, next)
    }

    // 沉浸态（v0.2.67，微信/销售页同款）：进入极空页隐藏主页顶部 Tab 栏与 APP 底部工具栏
    // （v0.2.83 红队修正：仪表盘「开关开即启动」的 Effect 已移入 HomeView——
    //   只有已连接才有 HomeView，杜绝未连接时白白开 GPS）
    DisposableEffect(Unit) {
        JkUiState.immersive = true
        onDispose { JkUiState.immersive = false }
    }

    // 双击返回键回主页（微信/销售同款）：第 1 次 Toast 提示，窗口内第 2 次回主页首 Tab
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {
            JkUiState.exitToken++
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次返回主页", Toast.LENGTH_SHORT).show()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalJkColors provides colors) {
        Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
            if (!controller.status.connected) {
                // 未连接：主题/连接状态栏保留在顶部（此时无首页可承载），下方扫描/连接界面
                ConnectionBar(
                    connected = false,
                    deviceName = controller.status.deviceName,
                    themeMode = themeMode,
                    onThemeCycle = cycleTheme,
                    onDisconnect = { controller.disconnect() },
                )
                // 未连接：扫描/连接界面（扫描结果读 controller 进程级暂存，切页不丢失）
                ScanConnectView(
                    devices = controller.cachedDevices,
                    scanning = scanning,
                    connecting = connecting,
                    lastScanAt = controller.lastScanAt,
                    showAll = showAll,
                    onShowAllChange = { showAll = it },
                    onScan = { doScan() },
                    onConnect = { doConnect(it) },
                    defaultMac = JikongPrefs.defaultMac(context),
                    nicknameOf = { JikongPrefs.nickname(context, it) },
                )
            } else {
                // 已连接：底部导航四页；连接状态栏只在首页滚动内容内显示并随上滑消失（v0.2.67）
                Column(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        0 -> HomeView(
                            controller,
                            themeMode = themeMode,
                            onThemeCycle = cycleTheme,
                            onDisconnect = { controller.disconnect() },
                            dashEnabled = dashEnabled,
                        )
                        1 -> RealtimeView(controller)
                        2 -> StatsView(controller)
                        3 -> SettingsView(controller, dashEnabled = dashEnabled, onDashToggle = toggleDash)
                    }
                }
                // 底部导航栏
                BottomNavBar(tab) { tab = it }
            }
        }
    }
}

// ==================== 底部导航栏（极简图标版：首页/实时状态/统计/参数设置，首页最左） ====================
// v0.2.70：改用用户提供的图标（assets/icons/*.svg → res/drawable VectorDrawable，填充风格），
// 颜色由 Icon tint 统一控制（选中绿 / 未选中灰）；v0.2.85 第 3 位新增「统计」（ic_jk_stats）。
private val JkTabs = listOf(
    R.drawable.ic_jk_home,
    R.drawable.ic_jk_realtime,
    R.drawable.ic_jk_stats,
    R.drawable.ic_jk_settings,
)

@Composable
private fun BottomNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalJkColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.tabline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.tabbar)
                .padding(vertical = 1.dp),
        ) {
            JkTabs.forEachIndexed { i, iconRes ->
                val active = selected == i
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 选中指示条（18×2 圆角绿条）；未选中用等高占位保持图标对齐
                    if (active) {
                        Box(Modifier.width(18.dp).height(2.dp).background(c.green, RoundedCornerShape(1.dp)))
                        Spacer(Modifier.height(1.dp))
                    } else {
                        Spacer(Modifier.height(3.dp))
                    }
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = if (active) c.green else c.gray,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
// ==================== 连接状态栏（单行紧凑卡片；已连接时仅存在于首页滚动内容中） ====================
@Composable
private fun ConnectionBar(
    connected: Boolean,
    deviceName: String,
    themeMode: Int,
    onThemeCycle: () -> Unit,
    onDisconnect: () -> Unit,
    // v0.2.88：已连接时额外能力——点设备名改名、设为/取消默认设备
    deviceAddress: String = "",
    isDefault: Boolean = false,
    onRename: (() -> Unit)? = null,
    onToggleDefault: ((Boolean) -> Unit)? = null,
) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (connected) c.green else c.gray, CircleShape))
        Spacer(Modifier.width(6.dp))
        val nameText = if (connected) {
            if (isDefault) "已连接 · $deviceName · 默认" else "已连接 · $deviceName"
        } else "未连接"
        Text(
            text = nameText,
            color = if (connected) c.green else c.gray,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (connected && onRename != null) {
                        Modifier.clickable(onClick = onRename)
                    } else Modifier
                ),
        )
        if (connected && onToggleDefault != null && deviceAddress.isNotBlank()) {
            // v0.2.88：设为/取消默认设备（文字开关，与主题切换同款紧凑样式）
            Text(
                if (isDefault) "取消默认" else "设默认",
                color = if (isDefault) JkGreen else JkBlue,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onToggleDefault(!isDefault) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // 主题切换：自动 → 浅色 → 深色（循环）；紧凑文本（TextButton 有最小高度/宽度，易挤成两行）
        val themeLabel = when (themeMode) {
            JikongPrefs.THEME_LIGHT -> "浅色"
            JikongPrefs.THEME_DARK -> "深色"
            else -> "自动"
        }
        Text(
            themeLabel,
            color = JkBlue,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier
                .clickable(onClick = onThemeCycle)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        if (connected) {
            Text(
                "断开",
                color = c.red,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onDisconnect)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

// ==================== 扫描连接视图 ====================
@Composable
private fun ScanConnectView(
    devices: List<BleDevice>,
    scanning: Boolean,
    connecting: Boolean,
    lastScanAt: Long,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    defaultMac: String = "",
    nicknameOf: (String) -> String? = { null },
) {
    val c = LocalJkColors.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = JkBlue),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("扫描中…")
            } else {
                Text("扫描极空设备")
            }
        }
        // v0.2.88：已设默认设备时显示提示（点扫描=直连默认设备，失败自动转正常扫描）
        if (defaultMac.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "默认：${nicknameOf(defaultMac) ?: defaultMac}（点扫描直连该设备）",
                fontSize = 12.sp,
                color = JkGreen,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "扫描前请先开启蓝牙并靠近设备（10 米内）。仅显示极空保护板，如需查看其它蓝牙设备请勾选下方选项。",
            color = c.gray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        // 降级开关：显示全部蓝牙设备
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = showAll,
                onCheckedChange = onShowAllChange,
            )
            Text("显示全部蓝牙设备（不按极空过滤）", fontSize = 12.sp, color = c.gray)
        }
        Spacer(Modifier.height(4.dp))
        // 演示模式：不连保护板，直接灌入示例数据查看界面效果（用于复刻验证）
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { JikongController.instance.enterDemoMode() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("进入演示模式（无需连接设备）", fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "演示模式用一份真实示例数据填充页面，便于在远离设备时核对界面是否复刻官方 APP；连接真实设备后自动退出。",
            color = c.gray,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))

        // 上次扫描时间提示
        if (lastScanAt > 0 && devices.isNotEmpty()) {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(lastScanAt))
            Text("上次扫描 $fmt（点「扫描极空设备」刷新）", fontSize = 11.sp, color = c.gray)
        }
        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty() && !scanning) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂未扫描到极空设备", color = c.gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(devices) { _, dev ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = c.card),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !connecting) { onConnect(dev) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                // v0.2.88：有昵称时昵称为主标题、原名降为小字
                                val nick = nicknameOf(dev.address)
                                Text(
                                    nick ?: dev.name,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (nick != null) "原名 ${dev.name} · ${dev.address} · 信号 ${dev.rssi} dBm"
                                    else "${dev.address} · 信号 ${dev.rssi} dBm",
                                    fontSize = 12.sp,
                                    color = c.gray,
                                )
                            }
                            if (connecting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("连接", color = JkBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 首页（定档 ui-mockup-jikong-home.html v14，1:1 复刻） ====================
@Composable
private fun HomeView(
    controller: JikongController,
    themeMode: Int,
    onThemeCycle: () -> Unit,
    onDisconnect: () -> Unit,
    dashEnabled: Boolean = false,
) {
    // 订阅版本号：数据变化触发本页重组，实现实时刷新
    controller.statusVersion.value
    // v0.2.83：时速仪表盘 1Hz 版本号（车速/续航/电耗基元状态每秒刷新）
    SpeedDash.version.value
    val context = LocalContext.current
    val s = controller.status
    val c = LocalJkColors.current

    // v0.2.88：默认设备 / 改名弹窗状态。昵称与默认值的显示随 statusVersion（~1s/帧）
    // 自动重组刷新，无需额外 tick。
    var defaultMac by remember { mutableStateOf(JikongPrefs.defaultMac(context)) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // v0.2.83 仪表盘生命周期门控（红队修正：HomeView 只在已连接时存在）——
    // 进入首页（连接建立/回前台）且开关开才启动 GPS+积分；离开即注销，零后台成本。
    DisposableEffect(dashEnabled) {
        if (dashEnabled) SpeedDash.start(context)
        onDispose { SpeedDash.stop() }
    }

    // v0.2.68：进入首页默认滚到「充电/放电/均衡」行置顶；v0.2.72：少滚 8dp，
    // 让连接栏底部稍微露一条边（用户要求）；连接栏仍可通过上滑完全回看。
    val scrollState = rememberScrollState()
    var connBarHeightPx by remember { mutableStateOf(0) }
    var initialScrolled by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(connBarHeightPx) {
        if (!initialScrolled && connBarHeightPx > 0) {
            initialScrolled = true
            val peekPx = with(density) { 6.dp.toPx() }
            scrollState.scrollTo((connBarHeightPx - peekPx.toInt()).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
    ) {
        // 连接状态栏（已连接 · 设备名 · 浅色/深色 · 断开）：仅首页显示，默认滚出视野、可上滑回看
        // v0.2.88：显示昵称优先；点设备名弹改名框；「设默认/取消默认」文字开关
        Box(Modifier.onSizeChanged { connBarHeightPx = it.height }) {
            ConnectionBar(
                connected = true,
                deviceName = JikongPrefs.nickname(context, s.deviceAddress) ?: s.deviceName,
                themeMode = themeMode,
                onThemeCycle = onThemeCycle,
                onDisconnect = onDisconnect,
                deviceAddress = s.deviceAddress,
                isDefault = defaultMac == s.deviceAddress,
                onRename = { renameText = JikongPrefs.nickname(context, s.deviceAddress) ?: ""; showRename = true },
                onToggleDefault = { on ->
                    defaultMac = if (on) s.deviceAddress else ""
                    JikongPrefs.setDefaultMac(context, defaultMac)
                    Toast.makeText(
                        context,
                        if (on) "已设为默认设备" else "已取消默认设备",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
        Spacer(Modifier.height(2.dp))

        // v0.2.88：改名弹窗（点连接栏设备名触发；留空=清除昵称）
        if (showRename) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("设备昵称", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        placeholder = { Text("留空清除昵称") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        JikongPrefs.setNickname(context, s.deviceAddress, renameText)
                        showRename = false
                        Toast.makeText(
                            context,
                            if (renameText.isBlank()) "已清除昵称" else "昵称已保存",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showRename = false }) { Text("取消") }
                },
            )
        }

        // 充电/放电/均衡 一行三列（inline 圆点+文字，定档样式）
        // v0.2.79：改传基元参数（Boolean）。旧签名收整个 JikongStatus——Compose 强跳过
        // （编译器 2.0.20+ 默认开启）对 unstable 参数按**引用**比较，而 status 对象是
        // 被原地修改的同一实例 → 引用永不变 → 本行被跳过、冻结在旧帧（用户实证：
        // 「均衡电流已归零但均衡仍显示开启」）。基元参数按值比较，每帧真实刷新。
        StatusRow(
            charging = s.current > 0.1f,
            discharging = s.current < -0.1f,
            balanceOn = s.balanceOn,
        )
        Spacer(Modifier.height(12.dp))

        // 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊）
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            JkGauge(s.soc, s.totalVoltage, s.current, s.totalVoltage * s.current)
        }
        Spacer(Modifier.height(2.dp))

        // 状态提示条（盾牌图标 + 电池运行良好）
        HealthBanner()
        Spacer(Modifier.height(6.dp))

        // v0.2.83 时速仪表盘卡片（EV 仪表盘：时速/续航/电耗/行程）——紧跟「电池运行良好」下方。
        // 开关关闭时不 compose（不占布局、不启动任何资源）。全部基元参数（强跳过陷阱）。
        if (dashEnabled) {
            SpeedDashCard(
                speed = SpeedDash.speedKmh,
                gpsOk = SpeedDash.gpsFresh,
                rangeKm = SpeedDash.rangeKm,
                instWhKm = SpeedDash.instWhKm,
                avgWhKm = SpeedDash.avgWhKm,
                tripKm = SpeedDash.tripKm,
                tripSeconds = SpeedDash.tripSeconds,
                bmsStale = SpeedDash.bmsStale,
                permNeeded = SpeedDash.gpsPermissionNeeded,
                onReset = { SpeedDash.resetTrip(context) },
            )
            Spacer(Modifier.height(6.dp))
        }

        // 数据网格一：最高电压 / 最低电压 / 最大压差 / 均衡电流
        QuadGrid(
            listOf(
                QuadCell("最高电压(V)", fmt(s.maxCellVoltage), c.blue),
                QuadCell("最低电压(V)", fmt(s.minCellVoltage), c.red),
                QuadCell("最大压差(V)", fmt(s.diffVoltage), c.green),
                QuadCell("均衡电流(A)", fmt(s.balanceCurrent), c.green),
            )
        )
        Spacer(Modifier.height(6.dp))

        // 数据网格二：电池容量 / 剩余容量 / 最高温度 / 电池类型
        QuadGrid(
            listOf(
                QuadCell("电池容量(Ah)", fmt(s.nominalCapacity), c.green),
                QuadCell("剩余容量(Ah)", fmt(s.remainCapacity), c.green),
                QuadCell("最高温度(℃)", fmt(s.maxTemperature), c.green),
                QuadCell("电池类型", if (s.batteryType.isBlank()) "--" else s.batteryType, c.green),
            )
        )
        Spacer(Modifier.height(6.dp))

        // 电流 / 功率 + 双向进度条 + 状态 / 剩余时间
        // v0.2.79：改传基元参数。旧签名收整个 JikongStatus 实例 → 强跳过按引用比较
        // → 卡片被跳过冻结（用户实证：「底部电流值不变化/只变化几次，与仪表盘明显
        // 不同步」——仪表盘 JkGauge 收基元参数每帧刷新，本卡收对象参数被跳过）。
        CurrentPowerCard(
            current = s.current,
            power = s.totalVoltage * s.current,
            chargeCurrentLimit = s.chargeCurrentLimit,
            dischargeCurrentLimit = s.dischargeCurrentLimit,
            remainSeconds = localRemainTime(s),
            hasData = controller.hasDataFrame,
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** 首页三分栏状态行（充电/放电/均衡，inline 同行样式，定档）。
 *  v0.2.79：参数改基元（见 HomeView 调用处注释——强跳过冻结问题）。
 *  均衡 = BMS 状态字节 @172（「正在均衡」，真机实证与均衡电流联动：0.6A↔1 / 0A↔0），
 *  不是均衡开关设置；修完冻结后此灯将随 BMS 实际均衡动作实时亮灭。 */
@Composable
private fun StatusRow(charging: Boolean, discharging: Boolean, balanceOn: Boolean) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCellInline("充电", charging, Modifier.weight(1f))
        DividerV(16)
        StatusCellInline("放电", discharging, Modifier.weight(1f))
        DividerV(16)
        StatusCellInline("均衡", balanceOn, Modifier.weight(1f))
    }
}

@Composable
private fun DividerV(height: Int = 16) {
    val c = LocalJkColors.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(height.dp)
            .background(c.divider),
    )
}

/** 定档 inline 状态单元：圆点 + 标签 + 状态同一行居中。 */
@Composable
private fun StatusCellInline(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val c = LocalJkColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (on) c.green else c.red, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 14.sp, color = c.text)
        Spacer(Modifier.width(5.dp))
        Text(if (on) "开启" else "关闭", fontSize = 14.sp, color = if (on) c.green else c.red)
    }
}

/** 状态横幅（绿色圆角方块 + 白色对勾 + 电池运行良好，定档样式）。 */
@Composable
private fun HealthBanner() {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 18dp 绿色圆角方块内画白色对勾（定档 SVG：M5 12l5 5L19 7）
        Box(
            modifier = Modifier.size(18.dp).background(c.green, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                val sc = size.width / 24f
                withTransform({ scale(sc, sc, pivot = Offset.Zero) }) {
                    val p = PathParser().parsePathString("M5 12l5 5L19 7").toPath()
                    drawPath(p, Color.White, style = Stroke(3f, cap = StrokeCap.Round))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("电池运行良好", color = c.text, fontSize = 14.sp)
    }
}

/** 时长格式化：秒 → "m:ss" / "h:mm:ss"。 */
private fun fmtDuration(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * v0.2.83 时速仪表盘卡片（EV 仪表盘精简版，紧贴「电池运行良好」下方）。
 * 全部基元参数（Compose 强跳过按值比较，每帧真实刷新——v0.2.79 冻结教训）。
 *
 * 布局：
 * 行1：时速大字（km/h） | 剩余续航（km，BMS 缺数据显 --）
 * 行2：瞬时电耗 | 平均电耗 | 本次里程（Wh/km 显 1 位小数）
 * 行3：本次时长 + 清零按钮；权限缺失时整卡降级为授权引导。
 */
@Composable
private fun SpeedDashCard(
    speed: Float,
    gpsOk: Boolean,
    rangeKm: Float,
    instWhKm: Float,
    avgWhKm: Float,
    tripKm: Float,
    tripSeconds: Float,
    bmsStale: Boolean,
    permNeeded: Boolean,
    onReset: () -> Unit,
) {
    val c = LocalJkColors.current
    val context = LocalContext.current
    // 精确定位权限：缺失时请求（SpeedDash.onPermissionGranted 里会重试启动）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) SpeedDash.onPermissionGranted(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (permNeeded) {
            // 权限引导行（不弹系统框轰炸，用户点按钮才申请）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时速仪表盘需要精确定位权限", fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text("授权", color = c.blue) }
            }
            return@Column
        }
        // 行1：时速 | 续航
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("时速(km/h)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (gpsOk) "%.1f".format(speed) else "--",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.green,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("剩余续航(km)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (rangeKm >= 0f) "%.0f".format(rangeKm) else "--",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = if (bmsStale) c.gray else c.blue,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        DividerV()
        Spacer(Modifier.height(6.dp))
        // 行2：瞬时 | 平均 | 里程
        Row {
            Column(Modifier.weight(1f)) {
                Text("瞬时(Wh/km)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (instWhKm > 0f) "%.1f".format(instWhKm) else "--",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("平均(Wh/km)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (avgWhKm > 0f) "%.1f".format(avgWhKm) else "--",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("本次里程(km)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (tripKm > 0f) "%.2f".format(tripKm) else "--",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 行3：时长 + 清零
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本次时长 ${fmtDuration(tripSeconds)}", fontSize = 11.sp, color = c.gray, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("清零行程", fontSize = 11.sp, color = c.gray)
            }
        }
        if (bmsStale) {
            Text("保护板数据未到，续航估算暂停", fontSize = 11.sp, color = c.amber)
        }
    }
}

/** 定档四列网格卡片（17sp 数值 + 11sp 标签 + 60% 高竖线分隔）。 */
@Composable
private fun QuadGrid(cells: List<QuadCell>) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) Box(Modifier.width(1.dp).height(30.dp).background(c.divider))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(cell.value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cell.accent)
                Spacer(Modifier.height(5.dp))
                Text(cell.label, fontSize = 11.sp, color = c.gray, textAlign = TextAlign.Center)
            }
        }
    }
}

private data class QuadCell(val label: String, val value: String, val accent: Color)

/**
 * 定档仪表盘（ui-mockup-jikong-home.html v14 1:1 复刻）：
 * 300° 主弧缺口朝下（圆心 160,160 / R=126 / 线宽 14，viewBox 320 坐标系），
 * 灰色全弧轨道 + 渐变进度弧（位置渐变，同 SVG userSpaceOnUse），
 * 内圈 0~100 三级刻度带（逢10: 16px/2.2，逢5: 12px/1.7，其余 8px/1.1），
 * 外围刻度数字（逢5标注：逢10 大号 numA 加粗，其余 numB），无任何发光特效。
 */
@Composable
private fun JkGauge(soc: Int, voltage: Float, current: Float, power: Float) {
    val c = LocalJkColors.current
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth(0.885f).aspectRatio(1f)) {
            val scale = size.width / 320f
            val cx = 160f * scale
            val cy = 160f * scale
            val R = 126f * scale
            val sw = 14f * scale
            val topLeft = Offset(cx - R, cy - R)
            val arcSize = Size(2 * R, 2 * R)

            // 灰色轨道（全弧 300°，起点 120° → 缺口朝下，butt 端帽）
            drawArc(
                color = c.track,
                startAngle = 120f, sweepAngle = 300f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Butt),
            )
            // 渐变进度弧：位置渐变（左缘 g1 → 中 g2 → 右缘 g3），只画 soc 比例段
            val sweep = 300f * (soc.coerceIn(0, 100) / 100f)
            if (sweep > 0f) {
                val brush = Brush.horizontalGradient(
                    0f to c.g1, 0.5f to c.g2, 1f to c.g3,
                    startX = cx - R, endX = cx + R,
                )
                drawArc(
                    brush = brush,
                    startAngle = 120f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Butt),
                )
            }

            // 内圈刻度带：外端 rOut=113（viewBox 单位），绿描边圆帽
            val rOut = 113f * scale
            for (i in 0..100) {
                val rad = Math.toRadians(120.0 + 3.0 * i)
                val cosA = kotlin.math.cos(rad).toFloat()
                val sinA = kotlin.math.sin(rad).toFloat()
                val (lenU, wU) = when {
                    i % 10 == 0 -> 16f to 2.2f
                    i % 5 == 0 -> 12f to 1.7f
                    else -> 8f to 1.1f
                }
                val rIn = (113f - lenU) * scale
                drawLine(
                    color = c.green,
                    start = Offset(cx + rOut * cosA, cy + rOut * sinA),
                    end = Offset(cx + rIn * cosA, cy + rIn * sinA),
                    strokeWidth = wU * scale,
                    cap = StrokeCap.Round,
                )
            }

            // 外围刻度数字：r=144，逢5标注；逢10 → numA 11px 加粗，其余 numB 9px
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.textAlign = android.graphics.Paint.Align.CENTER
                val nr = 144f * scale
                for (i in 0..100 step 5) {
                    val major = i % 10 == 0
                    val rad = Math.toRadians(120.0 + 3.0 * i)
                    val nx = cx + nr * kotlin.math.cos(rad).toFloat()
                    val ny = cy + nr * kotlin.math.sin(rad).toFloat() + (if (major) 3.5f else 3f) * scale
                    paint.textSize = (if (major) 11f else 9f) * scale
                    paint.isFakeBoldText = major
                    paint.color = (if (major) c.numA else c.numB).toArgb()
                    canvas.nativeCanvas.drawText(i.toString(), nx, ny, paint)
                }
            }
        }
        // 中央内容：SOC（%同大小）+ 描边胶囊（电压/电流）+ 实心胶囊（功率）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$soc%",
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
                color = c.green,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fmt(voltage) + "V",
                    fontSize = 15.sp,
                    color = c.green,
                    modifier = Modifier
                        .border(1.dp, c.green, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
                Text(
                    fmt(current) + "A",
                    fontSize = 15.sp,
                    color = c.green,
                    modifier = Modifier
                        .border(1.dp, c.green, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                fmt(power) + "W",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = c.onfill,
                modifier = Modifier
                    .background(c.green, RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 电流/功率卡片（定档：同行基线标注 + 中点 0 双向进度条 + 状态/剩余时间）。
 * v0.2.77：新增 hasData——本会话还没收到任何 0x02 实时帧时，电流/功率显示 `--`
 * 而非 0.00（真机 2026-11-06 反馈「电流和功率没有显示」：连接瞬间/断流期间读到的
 * 0.00 与真实静止 0 值无法区分，被当成没数据）；数据一到即与仪表盘同帧刷新。
 * v0.2.79：参数全部改基元（current/power/限流/剩余秒/hasData）——收整个 JikongStatus
 * 时被 Compose 强跳过按引用比较跳过（实例原地改、引用不变 → 冻结），这就是
 * 「底部电流与仪表盘不同步」的根因；基元按值比较，仪表盘动它必动。
 */
@Composable
private fun CurrentPowerCard(
    current: Float,
    power: Float,
    chargeCurrentLimit: Float,
    dischargeCurrentLimit: Float,
    remainSeconds: Int,
    hasData: Boolean,
) {
    val c = LocalJkColors.current
    val charging = current > 0.1f
    val discharging = current < -0.1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
    ) {
        // 电流(A) x.xx   功率(W) xxx.x（同行基线对齐，定档样式）
        Row {
            Text("电流(A)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(
                if (hasData) fmt(current) else "--",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(22.dp))
            Text("功率(W)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(
                if (hasData) fmt(power) else "--",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.height(10.dp))

        // 端点标注：左充电 / 中0 / 右放电（0 点数字亮白加粗）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("充电", fontSize = 10.sp, color = c.gray)
            Text("0", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("放电", fontSize = 10.sp, color = c.gray)
        }
        Spacer(Modifier.height(8.dp))

        // 双向进度条：中点=0，左半充电（绿→青渐变，向左）、右半放电（红，向右），
        // 半程 = 满量程（充电用参数充电限流、放电用放电限流）；0 点亮白加高刻度
        val maxCharge = if (chargeCurrentLimit > 0f) chargeCurrentLimit else 50f
        val maxDischarge = if (dischargeCurrentLimit > 0f) dischargeCurrentLimit else 100f
        val chargeFrac = if (charging) (current / maxCharge).coerceIn(0f, 1f) else 0f
        val dischFrac = if (discharging) (-current / maxDischarge).coerceIn(0f, 1f) else 0f
        Box(modifier = Modifier.fillMaxWidth().height(7.dp)) {
            Box(Modifier.matchParentSize().background(c.track, RoundedCornerShape(3.5.dp)))
            // 充电填充：占**左半**（wrapper 左对齐半宽），内层右端锚在 wrapper 右缘 = 条中点(0点)，
            // 电流越大越向左延伸（v0.2.67 修复：改前 wrapper/内层 align 用反，0 点跑到最左、填充从右缘开始）
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(chargeFrac)
                        .background(
                            Brush.horizontalGradient(0f to c.cyan, 1f to c.green),
                            RoundedCornerShape(3.5.dp, 0.dp, 0.dp, 3.5.dp),
                        ),
                )
            }
            // 放电填充：占**右半**（wrapper 右对齐半宽），内层左端锚在 wrapper 左缘 = 条中点(0点)，
            // |电流|越大越向右延伸
            Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(dischFrac)
                        .background(c.red, RoundedCornerShape(0.dp, 3.5.dp, 3.5.dp, 0.dp)),
                )
            }
            // 0 点刻度：4×17dp 亮白，双轴居中 = 进度条正中（与 HTML .center 一致）
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 4.dp, height = 17.dp)
                    .background(c.text, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.height(6.dp))

        // 状态 + 剩余时间（充电→「满 时:分:秒」，放电→「空 时:分:秒」）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("状态:", fontSize = 13.sp, color = c.gray)
            Spacer(Modifier.width(2.dp))
            Text(
                if (charging) "充电" else if (discharging) "放电" else "静止",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.green,
            )
            Spacer(Modifier.weight(1f))
            Text("剩余时间:", fontSize = 13.sp, color = c.gray)
            Spacer(Modifier.width(2.dp))
            Text(
                if (remainSeconds > 0) (if (charging) "满 " else "空 ") + formatDuration(remainSeconds) else "--",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.green,
            )
        }
    }
}

/** 本地计算剩余时间（秒）：充电=(标称-剩余)/电流，放电=剩余/|电流|；静止或电流过小返回 0。 */
private fun localRemainTime(s: my.bridge.com.jikong.JikongStatus): Int {
    val cur = s.current
    if (kotlin.math.abs(cur) < 0.05f) return 0 // 静止/电流过小，无法估算
    val ah = if (cur > 0) {
        // 充电：还需充入的容量
        (s.nominalCapacity - s.remainCapacity).coerceAtLeast(0f)
    } else {
        // 放电：剩余容量
        s.remainCapacity.coerceAtLeast(0f)
    }
    if (ah <= 0f) return 0
    val hours = ah / kotlin.math.abs(cur)
    return (hours * 3600).toInt()
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val sec = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, sec)
}

// ==================== 实时状态（对齐官方 JK BMS） ====================
@Composable
private fun RealtimeView(controller: JikongController) {
    // 订阅版本号：数据变化触发本页重组，实现实时刷新
    controller.statusVersion.value
    val s = controller.status
    val c = LocalJkColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
    ) {
        // 电池电压 / 电池电流：一行卡片，标签与数值同行（对齐官方截图，样式借鉴首页功率行）；紧凑边距
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.card, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("电池电压(V)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(fmt(s.totalVoltage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(22.dp))
            Text("电池电流(A)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(fmt(s.current), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(6.dp))

        // 「实时数据」双列区块（官方：左列/右列标签:数值 对齐）
        JkSectionTitle("实时数据")
        Card(colors = CardDefaults.cardColors(containerColor = c.card)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                // 左列
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("电池功率", fmt(s.totalVoltage * s.current) + "W")
                    DetailRow("电池容量", fmt(s.nominalCapacity) + "Ah")
                    DetailRow("剩余容量", fmt(s.remainCapacity) + "Ah")
                    DetailRow("功率温度", if (s.temperatures.isNotEmpty()) fmt(s.temperatures[0]) + "℃" else "--")
                    DetailRow("电池温度1", if (s.temperatures.size > 1) fmt(s.temperatures[1]) + "℃" else "--")
                    DetailRow("电池温度2", if (s.temperatures.size > 2) fmt(s.temperatures[2]) + "℃" else "--")
                    DetailRow("加热状态", if (s.heaterOn) "开启" else "关闭")
                    DetailRow("均衡", if (s.balanceOn) "开启" else "关闭")
                }
                Spacer(Modifier.width(24.dp))
                // 右列
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("单体平均", fmt(s.avgCellVoltage) + "V")
                    DetailRow("最大压差", fmt(s.diffVoltage) + "V")
                    DetailRow("均衡电流", fmt(s.balanceCurrent) + "A")
                    DetailRow("循环次数", "${s.cycleCount}次")
                    DetailRow("循环容量", fmt(s.cycleCapacity) + "Ah")
                    DetailRow("加热电流", fmt(s.heaterCurrent) + "A")
                    DetailRow("充电器状态", if (s.chargerState.isBlank()) "未插入" else s.chargerState)
                    DetailRow("电池类型", if (s.batteryType.isBlank()) "--" else s.batteryType)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 单体电压（单个卡片：3 列 × 8 行，序号徽标与数值同行）
        JkSectionTitle("单体电压 (V)")
        CellGrid(s.cellVoltages, 24)

        Spacer(Modifier.height(6.dp))

        // 均衡线电阻（与单体电压同款单卡片网格）
        JkSectionTitle("均衡线电阻 (mΩ)")
        ResistanceGrid(s.cellResistances, 24)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = c.gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

@Composable
private fun JkSectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(JkGreen, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
    }
    Spacer(Modifier.height(6.dp))
}

// 单体电压网格（官方样式：单个卡片内 3 列 × 8 行，序号徽标与数值同行）
@Composable
private fun CellGrid(values: List<Float>, total: Int) {
    val maxIdx = if (values.isNotEmpty()) values.indices.maxByOrNull { values[it] } ?: -1 else -1
    val minIdx = if (values.isNotEmpty()) values.indices.minByOrNull { values[it] } ?: -1 else -1
    TriGrid(values, total, maxIdx, minIdx)
}

/**
 * 实时页通用网格：一个卡片内 3 列 × 8 行，每格 = 序号徽标 + 数值同行（对齐官方 APP）。
 * **竖向（列优先）排列**：左列 1-8、中列 9-16、右列 17-24（对齐官方 APP 的排布）。
 * maxIdx/minIdx 用于单体电压最高（红）/最低（琥珀黄）高亮；未接入的节显示 --。
 */
@Composable
private fun TriGrid(
    values: List<Float>,
    total: Int,
    maxIdx: Int = -1,
    minIdx: Int = -1,
    format: (Float) -> String = { "%.3f".format(it) },
) {
    val c = LocalJkColors.current
    val rows = (total + 2) / 3 // 每列行数（24 → 8）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        for (r in 0 until rows) {
            Row {
                for (col in 0 until 3) {
                    val i = col * rows + r
                    if (i >= total) continue
                    val has = i < values.size
                    val num = String.format("%02d", i + 1)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 序号徽标：数字尽量占满、整体尺寸不变（大字 + 小边距）
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 24.dp)
                                .background(c.badge, RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(num, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.badgeText)
                        }
                        Spacer(Modifier.width(7.dp))
                        if (has) {
                            val color = when (i) {
                                maxIdx -> c.red
                                minIdx -> c.amber
                                else -> c.text
                            }
                            Text(
                                format(values[i]),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = color,
                            )
                        } else {
                            Text("--", fontSize = 14.sp, color = c.gray)
                        }
                    }
                }
            }
        }
    }
}

// 内阻网格（与单体电压同款单卡片样式；未接显示 --）
@Composable
private fun ResistanceGrid(values: List<Float>, total: Int) {
    TriGrid(values, total)
}

@Composable
private fun MosBadge(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val c = LocalJkColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(if (on) JkGreen else c.gray, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = c.gray)
        Text(if (on) "开启" else "关闭", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) JkGreen else c.gray)
    }
}

// ==================== 设置（v0.2.83 清空重写：不再设置保护板参数/写保护板） ====================
// 用户拍板（2026-11-06）：保护板参数修改需要设备密码、且无必要——全部删除；
// 页面只保留「时速仪表盘」开关与累计统计。0x01 帧解析保留（剩余时间计算依赖标称容量）。
@Composable
private fun SettingsView(
    controller: JikongController,
    dashEnabled: Boolean,
    onDashToggle: (Boolean) -> Unit,
) {
    val c = LocalJkColors.current
    val context = LocalContext.current
    // 订阅仪表盘 1Hz 版本号：累计统计实时刷新（红队修正：否则 @Volatile 变化不触发重组）
    SpeedDash.version.value
    // v0.2.88 语音播报设置状态
    var speakEnabled by remember { mutableStateOf(JikongPrefs.speakEnabled(context)) }
    var speakIntervalMin by remember { mutableIntStateOf(JikongPrefs.speakIntervalMin(context)) }
    var speakLowSoc by remember { mutableIntStateOf(JikongPrefs.speakLowSoc(context)) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("仪表盘", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))

        // 时速仪表盘开关：开=首页显示卡片并启动 GPS+积分；关=不显示、不启动任何资源（零成本）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("时速仪表盘", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "首页显示时速/续航/电耗卡片。使用 GPS 定位（仅页面打开时工作），会增加少量耗电；关闭后完全停止。",
                        fontSize = 11.sp, color = c.gray,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = dashEnabled,
                    onCheckedChange = { onDashToggle(it) },
                )
            }
        }
        if (SpeedDash.gpsPermissionNeeded) {
            Spacer(Modifier.height(4.dp))
            Text("缺少精确定位权限：请到系统设置或首页卡片中授权。", fontSize = 11.sp, color = c.amber)
        }

        // v0.2.88 语音播报：开关 + 播报间隔 + 低电量阈值。
        // 判定规则（用户拍板）：每次连接成功后首个页面可见的含电量帧判定一次，连接期间不再播；
        // 电量≠上次播报值→播，相同且距上次播报≥间隔→播（跨连接生效，持久化防重启误播）。
        Spacer(Modifier.height(16.dp))
        Text("语音播报", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("连接后播报电量", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "连接成功并获取到电量后语音播报一次（仅本页打开时）。电量与上次播报相同且未超间隔则不播。",
                            fontSize = 11.sp, color = c.gray,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = speakEnabled,
                        onCheckedChange = {
                            speakEnabled = it
                            JikongPrefs.setSpeakEnabled(context, it)
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                JkStepperRow(
                    label = "播报间隔",
                    value = "$speakIntervalMin 分钟",
                    onMinus = {
                        speakIntervalMin = (speakIntervalMin - 1).coerceAtLeast(1)
                        JikongPrefs.setSpeakIntervalMin(context, speakIntervalMin)
                    },
                    onPlus = {
                        speakIntervalMin = (speakIntervalMin + 1).coerceAtMost(120)
                        JikongPrefs.setSpeakIntervalMin(context, speakIntervalMin)
                    },
                )
                Spacer(Modifier.height(2.dp))
                JkStepperRow(
                    label = "低电量阈值",
                    value = "$speakLowSoc %",
                    onMinus = {
                        speakLowSoc = (speakLowSoc - 1).coerceAtLeast(1)
                        JikongPrefs.setSpeakLowSoc(context, speakLowSoc)
                    },
                    onPlus = {
                        speakLowSoc = (speakLowSoc + 1).coerceAtMost(50)
                        JikongPrefs.setSpeakLowSoc(context, speakLowSoc)
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "低于阈值时播报追加「电量低，请注意充电」。需要设备装有系统文字转语音（TTS）引擎。",
                    fontSize = 11.sp, color = c.gray,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("累计统计", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))
        // P2 持久化：跨会话累计（进程存活期间实时显示，落盘 30s 一次）
        val totalKm = JikongPrefs.totalKm(context) + SpeedDash.tripKm
        val totalWh = JikongPrefs.totalWh(context) + SpeedDash.tripWh
        val histAvg = if (SpeedDash.avgWhKm > 0f) SpeedDash.avgWhKm else JikongPrefs.histAvgWhKm(context)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("累计里程(km)", fontSize = 11.sp, color = c.gray)
                    Text(
                        if (totalKm > 0f) "%.2f".format(totalKm) else "--",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("累计耗电(Wh)", fontSize = 11.sp, color = c.gray)
                    Text(
                        if (totalWh > 0f) "%.0f".format(totalWh) else "--",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("历史电耗(Wh/km)", fontSize = 11.sp, color = c.gray)
                    Text(
                        if (histAvg > 0f) "%.1f".format(histAvg) else "--",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("说明", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "续航 = 剩余电量能量 ÷ 近期平均电耗（EV 通行算法）。" +
                        "刚开始使用时按参考值 20 Wh/km 估算，骑行积累数据后自动修正为您的真实电耗。" +
                        "所有数值为估算，仅供参考。保护板参数修改需设备密码，本应用不提供。",
                    fontSize = 11.sp, color = c.gray,
                )
            }
        }
    }
}

/** v0.2.88：设置页紧凑步进行（－ 值 ＋），用于播报间隔/低电量阈值。 */
@Composable
private fun JkStepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
        Text(
            "－", fontSize = 16.sp, color = JkBlue, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Text(value, fontSize = 13.sp, color = c.text, modifier = Modifier.defaultMinSize(minWidth = 66.dp))
        Text(
            "＋", fontSize = 16.sp, color = JkBlue, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

// ==================== 统计（v0.2.85：三轮车里程台账 + 能耗曲线 + MySQL 留档） ====================
// 定档原型：assets/design/ui-mockup-jikong-stats.html。
// 录入永远本地成功（JikongStatsStore，synced=false）；同步失败保留待同步标记可重试
// （JikongStatsSync，录入后静默自动一次 + 手动按钮兜底，失败 Toast 服务端错误）。
// 强跳过铁律：本页所有卡片只收基元/不可变参数，不传 controller.status 可变对象。

/** 相邻两记录的能耗段（prev → rec）。valid=false（ΔAh≤0 / Δkm≤0 / 无电压）不进曲线与统计。 */
private data class JkSeg(
    val rec: JkMileageRecord,
    val deltaKm: Float,
    val deltaAh: Float,
    val avgVolt: Float,
    val whKm: Float,
    val valid: Boolean,
)

/** 计算 segments：结果与 records 等长，index 0 为 null（基准行无段）。 */
private fun jkBuildSegs(records: List<JkMileageRecord>): List<JkSeg?> {
    val out = mutableListOf<JkSeg?>()
    records.forEachIndexed { i, rec ->
        if (i == 0) {
            out.add(null)
        } else {
            val prev = records[i - 1]
            val dKm = rec.odoKm - prev.odoKm
            val dAh = rec.cycleAh - prev.cycleAh
            val avgV = if (rec.volt > 0f && prev.volt > 0f) (rec.volt + prev.volt) / 2f else rec.volt
            val whKm = if (dKm > 0.01f && dAh > 0f && avgV > 0f) dAh * avgV / dKm else -1f
            out.add(JkSeg(rec, dKm, dAh, avgV, whKm, whKm > 0f))
        }
    }
    return out
}

private val JkDateFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
private val JkTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun StatsView(controller: JikongController) {
    val c = LocalJkColors.current
    val context = LocalContext.current
    controller.statusVersion.value // 订阅 BMS 数据版本：循环容量/总压实时值随 0x02 帧刷新

    var recordsVersion by remember { mutableIntStateOf(0) } // 记录集版本（增删/同步后自增驱动重读）
    var odoText by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    val records = remember(recordsVersion) { JikongStatsStore.records(context) }
    val segs = remember(records) { jkBuildSegs(records) }
    val pending = remember(recordsVersion) { JikongStatsStore.unsyncedCount(context) }

    // 概要（第一条基准 → 最后一条；仅统计有效段）
    val validSegs = segs.filterNotNull().filter { it.valid }
    val totalKm = if (records.size >= 2) records.last().odoKm - records.first().odoKm else 0f
    val totalWh = validSegs.sumOf { (it.deltaAh * it.avgVolt).toDouble() }.toFloat()
    val totalValidKm = validSegs.sumOf { it.deltaKm.toDouble() }.toFloat()
    val avgWhKm = if (totalValidKm > 0.5f) totalWh / totalValidKm else 0f

    // ---- 动作 ----
    val onAdd: () -> Unit = {
        val odo = odoText.toFloatOrNull()
        when {
            odo == null || odo <= 0f ->
                Toast.makeText(context, "请输入有效的里程数", Toast.LENGTH_SHORT).show()
            records.isNotEmpty() && odo <= records.last().odoKm ->
                Toast.makeText(context, "里程须大于上一条（${fmt(records.last().odoKm)} km）", Toast.LENGTH_SHORT).show()
            controller.status.cycleCapacity <= 0f ->
                Toast.makeText(context, "未读取到循环容量，请等连接出数后重试", Toast.LENGTH_SHORT).show()
            else -> {
                JikongStatsStore.add(context, odo, controller.status.cycleCapacity, controller.status.totalVoltage)
                odoText = ""
                recordsVersion++
                // 录入后静默自动同步一次（失败不打扰，保留待同步标记）
                JikongStatsSync.sync(context, silent = true) { _, _ -> recordsVersion++ }
                Toast.makeText(context, "已记录", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onDelete: (String) -> Unit = { id ->
        val needTombstone = JikongStatsStore.delete(context, id)
        if (needTombstone) JikongStatsStore.enqueueDelete(context, id)
        recordsVersion++
        // 已同步行删除 → 墓碑静默上行；未同步行仅动本地（无需同步）
        if (needTombstone) JikongStatsSync.sync(context, silent = true) { _, _ -> recordsVersion++ }
    }
    val onManualSync: () -> Unit = {
        if (!syncing) {
            syncing = true
            JikongStatsSync.sync(context, silent = false) { _, msg ->
                syncing = false
                recordsVersion++
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // ---- 标题行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("统计", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(7.dp).background(c.green, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text("已连接 · JK-BMS", fontSize = 11.sp, color = c.gray)
        }
        Spacer(Modifier.height(12.dp))

        // ---- 概要三卡 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Row(Modifier.padding(12.dp)) {
                listOf(
                    "记录区间里程" to (if (totalKm > 0f) "%.1f".format(totalKm) else "--"),
                    "区间总耗电" to (if (totalWh > 0f) "%.2f".format(totalWh / 1000f) else "--"),
                    "平均电耗" to (if (avgWhKm > 0f) "%.1f".format(avgWhKm) else "--"),
                ).forEachIndexed { i, (label, value) ->
                    if (i > 0) Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).background(c.bg, RoundedCornerShape(10.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (i == 2) c.green else c.text)
                            if (value != "--") {
                                Spacer(Modifier.width(2.dp))
                                Text(if (i == 0) "km" else if (i == 1) "kWh" else "Wh/km", fontSize = 9.sp, color = c.gray)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(label, fontSize = 10.sp, color = c.gray)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 录入卡 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("录入里程", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    Text("手动输入 · 容量自动读取", fontSize = 11.sp, color = c.gray)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        modifier = Modifier.weight(1f),
                        label = { Text("当前三轮车里程（km）", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.green,
                            unfocusedBorderColor = c.divider,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.green,
                            focusedContainerColor = c.bg,
                            unfocusedContainerColor = c.bg,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onAdd,
                        colors = ButtonDefaults.buttonColors(containerColor = c.green, contentColor = c.onfill),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("记一笔", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val cyc = controller.status.cycleCapacity
                val volt = controller.status.totalVoltage
                val hasData = controller.hasDataFrame
                Row(
                    Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (cyc > 0f) "循环容量 %.1f Ah · 总压 %.1f V".format(cyc, volt) else "循环容量 -- · 总压 --",
                        fontSize = 11.sp, color = if (cyc > 0f) c.cyan else c.gray,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (hasData) "● 已自动读取" else "等待数据…",
                        fontSize = 10.sp, color = if (hasData) c.green else c.gray,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "每次录入 = 记一行（时间 + 里程 + 当时循环容量/电压）。里程须大于上一条；" +
                        "服务端/数据库离线时本机照常记录，稍后手动或自动同步。",
                    fontSize = 10.sp, color = c.gray, lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 能耗曲线 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("能耗曲线", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    if (avgWhKm > 0f) Text("平均 %.1f Wh/km".format(avgWhKm), fontSize = 11.sp, color = c.cyan)
                }
                Spacer(Modifier.height(8.dp))
                if (validSegs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("录入两条以上记录后显示电耗曲线", fontSize = 12.sp, color = c.gray)
                    }
                } else {
                    JkStatsCurve(validSegs, avgWhKm, c)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 记录台账（固定高度，最新在最上，基准垫底） ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记录台账", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Text("${records.size} 条 · 新在上 · 存本机", fontSize = 10.sp, color = c.gray)
                    }
                    Button(
                        onClick = onManualSync,
                        enabled = pending > 0 && !syncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.blue,
                            contentColor = Color.White,
                            disabledContainerColor = c.track,
                            disabledContentColor = c.gray,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            if (syncing) "同步中…" else if (pending > 0) "⇅ 同步 $pending 条" else "已同步",
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("还没有记录，先在上方记一笔", fontSize = 12.sp, color = c.gray)
                    }
                } else {
                    // 固定高度 + 内部滑动（规避 LazyColumn 无界高度塌缩：必须 height 固定值）
                    LazyColumn(Modifier.fillMaxWidth().height(264.dp)) {
                        val display = records.indices.reversed().map { i -> i to segs[i] }
                        itemsIndexed(display) { _, item ->
                            val (idx, seg) = item
                            JkStatsRow(records[idx], seg, c) { onDelete(records[idx].id) }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 口径说明 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(14.dp)) {
                Text("口径说明", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "电耗 = Δ循环容量(Ah) × 平均电压(V) ÷ Δ里程(km)，即行驶这段路电池实际放出的电能。" +
                        "循环容量由保护板累计（只增不减），充电不影响它；换电池 / BMS 重置会使容量回退，" +
                        "该段自动标记无效、不进曲线，并以新容量重新为基准。",
                    fontSize = 11.sp, color = c.gray, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 一行台账记录（日期/未同步徽标 | 里程+距上次 | Δ明细 | 电耗 | 删除）。 */
@Composable
private fun JkStatsRow(rec: JkMileageRecord, seg: JkSeg?, c: JkColors, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 日期列 + 未同步徽标
        Column(Modifier.width(52.dp)) {
            Text(JkDateFmt.format(Date(rec.ts)), fontSize = 10.sp, color = c.gray)
            Text(JkTimeFmt.format(Date(rec.ts)), fontSize = 10.sp, color = c.gray)
            if (!rec.synced) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "未同步",
                    fontSize = 8.sp, color = c.amber,
                    modifier = Modifier.border(1.dp, c.amber, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
                )
            }
        }
        // 里程列
        Column(Modifier.width(78.dp)) {
            Text("%.1f".format(rec.odoKm), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(
                if (seg == null) "基准 · 容量 ${fmt(rec.cycleAh)}Ah"
                else "距上次 +%.1f km".format(seg.deltaKm),
                fontSize = 9.sp, color = c.gray,
            )
        }
        // Δ 明细列
        Column(Modifier.weight(1f)) {
            if (seg == null) {
                Text("第一条记录", fontSize = 10.sp, color = c.gray)
                Text("作为计算起点", fontSize = 10.sp, color = c.gray)
            } else if (!seg.valid) {
                Text("本段无效（容量回退）", fontSize = 10.sp, color = c.amber)
            } else {
                Text("Δ%.1f Ah · %.1f V".format(seg.deltaAh, seg.avgVolt), fontSize = 10.sp, color = c.gray)
                Text("%.2f kWh".format(seg.deltaAh * seg.avgVolt / 1000f), fontSize = 10.sp, color = c.gray)
            }
        }
        // 电耗列
        Column(horizontalAlignment = Alignment.End) {
            when {
                seg == null -> Text("基准", fontSize = 11.sp, color = c.gray)
                !seg.valid -> Text("--", fontSize = 12.sp, color = c.gray)
                else -> {
                    val color = when {
                        seg.whKm >= 38f -> c.amber   // 偏高（载重/爬坡）
                        seg.whKm <= 31f -> c.green   // 偏优
                        else -> c.text
                    }
                    Text("%.1f".format(seg.whKm), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
                    Text("Wh/km", fontSize = 9.sp, color = c.gray)
                }
            }
        }
        // 删除
        Spacer(Modifier.width(6.dp))
        Text(
            "✕", fontSize = 13.sp, color = c.gray,
            modifier = Modifier.clickable(onClick = onDelete).padding(4.dp),
        )
    }
}

/** 能耗折线图（Canvas 手绘：网格 + 折线 + 数据点 + 平均线 + 轴标签）。 */
@Composable
private fun JkStatsCurve(segs: List<JkSeg>, avgWhKm: Float, c: JkColors) {
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val padL = 34.dp.toPx()
        val padR = 8.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        val ys = segs.map { it.whKm }
        var yMin = ys.min() * 0.9f
        var yMax = ys.max() * 1.1f
        if (yMax - yMin < 2f) { // 电耗过近时拉开量程，避免折线贴成一条线
            val mid = (yMax + yMin) / 2f
            yMin = mid - 1f; yMax = mid + 1f
        }
        val xMin = segs.first().rec.odoKm
        val xMax = segs.last().rec.odoKm
        val xSpan = (xMax - xMin).coerceAtLeast(0.01f)

        fun xOf(v: Float) = padL + (v - xMin) / xSpan * plotW
        fun yOf(v: Float) = padT + (1f - (v - yMin) / (yMax - yMin)) * plotH

        // 横向网格 + Y 轴标签（3 条）
        val labelPaint = android.graphics.Paint().apply {
            color = c.gray.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true
        }
        for (k in 0..2) {
            val yv = yMin + (yMax - yMin) * k / 2f
            val py = yOf(yv)
            drawLine(c.divider, Offset(padL, py), Offset(padL + plotW, py), 1.dp.toPx())
            drawIntoCanvas { it.nativeCanvas.drawText("%.0f".format(yv), 0f, py + 4.dp.toPx(), labelPaint) }
        }

        // 平均线（虚线）
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(
            c.cyan, Offset(padL, yOf(avgWhKm)), Offset(padL + plotW, yOf(avgWhKm)),
            1.5.dp.toPx(), pathEffect = dash,
        )
        drawIntoCanvas {
            it.nativeCanvas.drawText(
                "平均 %.1f".format(avgWhKm), padL + plotW - 46.dp.toPx(),
                yOf(avgWhKm) - 4.dp.toPx(), labelPaint,
            )
        }

        // 折线 + 数据点
        val pts = segs.map { Offset(xOf(it.rec.odoKm), yOf(it.whKm)) }
        for (i in 0 until pts.size - 1) {
            drawLine(c.green, pts[i], pts[i + 1], 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        pts.forEach {
            drawCircle(c.bg, 4.dp.toPx(), it)                 // 底圈（描边感）
            drawCircle(c.green, 2.8.dp.toPx(), it)
        }

        // X 轴标签（首/中/尾里程）
        val xLabels = listOf(xMin, (xMin + xMax) / 2f, xMax)
        xLabels.forEachIndexed { i, xv ->
            val tx = xOf(xv)
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    "%.0f".format(xv),
                    (tx - 12.dp.toPx()).coerceAtLeast(0f),
                    size.height - 4.dp.toPx(),
                    labelPaint,
                )
            }
        }
    }
}

private fun fmt(v: Float): String =
    if (kotlin.math.abs(v) >= 100) "%.1f".format(v) else "%.2f".format(v)
