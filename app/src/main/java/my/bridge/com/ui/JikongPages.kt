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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
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
import my.bridge.com.jikong.JikongProximityService
import my.bridge.com.jikong.JikongSpeaker
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
 * - exitToken 自增触发 MainScreen 梯子上退（v0.2.94 方案A）：immersive 中→显示工具栏；
 *   工具栏已显示→跳第一个非极空页面（无论极空排第几位，行为统一）。
 */
object JkUiState {
    var immersive by androidx.compose.runtime.mutableStateOf(false)
    var exitToken by androidx.compose.runtime.mutableIntStateOf(0)
    // v0.2.102：管理后台远程改设置时自增，页面 LaunchedEffect 订阅后重读 Prefs 刷新 UI
    // （主题/小喇叭/播报设置都是 remember 快照读，不订阅的话远程改了本地界面不动）。
    var remoteThemeTick by androidx.compose.runtime.mutableIntStateOf(0)
    var remoteSpeakTick by androidx.compose.runtime.mutableIntStateOf(0)
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

    var connectAttemptSeq = 0   // v0.2.93：连接尝试序号（防陈旧 attempt 误重置 UI 状态/误触发回退扫描）

    fun doConnect(dev: BleDevice, isDefault: Boolean = false, fallbackScan: Boolean = false) {
        connecting = true
        val myAttempt = ++connectAttemptSeq
        kotlinx.coroutines.GlobalScope.launch {
            val ok = try {
                controller.connect(context, dev.address)
            } catch (e: Exception) {
                android.util.Log.w("Jikong", "连接失败", e)
                false
            }
            // v0.2.93：被更新尝试抢占的旧 attempt 不动 UI（connecting 由新 attempt 接管），
            // 也不触发「回退正常扫描」（旧 attempt 的 fallback 会和新连接打架）
            if (myAttempt != connectAttemptSeq) return@launch
            connecting = false
            // v0.2.98：进演示模式会掐掉在飞尝试（cancelPendingConnect→ok=false），
            // 此时失败提示与「回退扫描」都是对演示的干扰——静默收尾。
            if (controller.demoMode) return@launch
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
        // v0.2.93：已有连接尝试在飞（自动连接/上次直连还没结果）→ 用户明确要点扫描，
        // 直接正常扫描立即出列表，不再叠加一次 10s 的默认直连（延时 10~50s 的另一元凶）。
        // 在飞的自动连接不打断——它若成功页面自然进入数据页（用户拍板的硬要求）。
        if (tryDefault && defMac.isNotBlank() && !connecting && !controller.connectInFlight) {
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

    // v0.2.91 防僵尸：进入极空页时距上次扫描超过 10 分钟即清空列表（陈旧 RSSI/已消失设备）
    LaunchedEffect(Unit) {
        if (controller.lastScanAt > 0L &&
            System.currentTimeMillis() - controller.lastScanAt > JikongController.SCAN_STALE_MS
        ) {
            controller.clearScanCache()
        }
    }

    // v0.2.88：进入极空页自动连默认设备（用户拍板）。仅未连接、非演示、无在飞连接尝试、
    // 有默认设备、有连接权限且蓝牙开启时尝试一次；失败仅 Toast，不自动转扫描（点扫描按钮才兜底）。
    LaunchedEffect(Unit) {
        if (controller.status.connected || controller.demoMode) return@LaunchedEffect
        // v0.2.93：已有连接尝试在飞（用户手快先点了扫描/连接）→ 不叠加自动连接
        if (connecting || controller.connectInFlight) return@LaunchedEffect
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
        // v0.2.104：立即上报（reportStatus 按 status JSON 去重，Prefs 变化不在 status 里）
        controller.forceReport()
    }
    // v0.2.104：管理后台改「时速仪表盘」开关（jikong_set_config）→ Prefs 已被改（执行器已
    // 同步启停 SpeedDash）→ tick 自增 → 这里重读状态，HomeView 的 DisposableEffect(dashEnabled)
    // 与设置页开关随重组自动跟随。
    androidx.compose.runtime.LaunchedEffect(JkUiState.remoteSpeakTick) {
        val remote = JikongPrefs.dashEnabled(context)
        if (remote != dashEnabled) {
            dashEnabled = remote
            if (remote) SpeedDash.start(context) else SpeedDash.stop()
        }
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
                    // v0.2.98：演示模式后台化即退出（无真实连接不会自行断开，用户拍板；
                    // 真机连接不受影响，后台保持既有 keepConnection 策略）
                    if (controller.demoMode) controller.disconnect()
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
    // v0.2.102：管理后台切深/浅色 → 命令下发 → Prefs 已被改 → tick 自增 → 这里重读即时生效
    androidx.compose.runtime.LaunchedEffect(JkUiState.remoteThemeTick) {
        themeMode = JikongPrefs.theme(context)
    }
    val isDark = resolveDarkTheme(context, themeMode)
    val colors = if (isDark) DarkColors else LightColors
    val cycleTheme: () -> Unit = {
        val next = (themeMode + 1) % 3
        themeMode = next
        JikongPrefs.setTheme(context, next)
        // v0.2.103：立即上报——reportStatus 按 status JSON 去重，主题变化不改 status，
        // 不强制上报则管理后台永远同步不到新主题（用户 2026-11-08 实证）。
        controller.forceReport()
    }

    // 沉浸态（v0.2.67，微信/销售页同款）：进入极空页隐藏主页顶部 Tab 栏与 APP 底部工具栏
    // （v0.2.83 红队修正：仪表盘「开关开即启动」的 Effect 已移入 HomeView——
    //   只有已连接才有 HomeView，杜绝未连接时白白开 GPS）
    DisposableEffect(Unit) {
        JkUiState.immersive = true
        onDispose {
            JkUiState.immersive = false
            // v0.2.103：演示退出判定从 HomeView 迁到本层——原先挂在 HomeView 的
            // DisposableEffect 上，切「实时/统计/设置」内部 Tab 会卸载 HomeView 误退出
            // 演示（2026-11-08 用户实证）。本层只在离开极空页整体时触发，语义正确；
            // 回 APP 后台由上方 ON_PAUSE 处理，行为不变。
            if (controller.demoMode) controller.disconnect()
        }
    }

    // 返回键统一梯子（v0.2.94 方案A，用户拍板，微信式逐级上退）：
    // 子页→回仪表盘；back①弹提示；窗口内 back②→exitToken++（MainScreen 解释：
    // 沉浸中→显示工具栏；工具栏已显示→跳第一个非极空页面）。
    // v0.2.91：已连接且不在首页 Tab 时，第一次按返回先回首页 Tab，再走梯子逻辑
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler {
        if (controller.status.connected && tab != 0) {
            tab = 0
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {
            JkUiState.exitToken++
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次显示导航栏", Toast.LENGTH_SHORT).show()
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
    // v0.2.90：小喇叭静音开关（持久化；进程启动后首次播报不受影响）
    muted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
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
        // v0.2.90 小喇叭静音：🔊=播报中 / 🔇=已静音（持久化；启动后首次强制播报不受影响）
        if (onToggleMute != null) {
            Text(
                if (muted) "🔇" else "🔊",
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onToggleMute)
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
    // v0.2.90：小喇叭静音（持久化，跨重启保留；启动后首次强制播报在 Controller 层不受此影响）
    var muted by remember { mutableStateOf(JikongPrefs.muted(context)) }
    // v0.2.102：管理后台「小喇叭」开关同步（命令改 Prefs 后 tick 自增，此处重读刷新）
    androidx.compose.runtime.LaunchedEffect(JkUiState.remoteSpeakTick) {
        muted = JikongPrefs.muted(context)
    }

    // v0.2.83 仪表盘生命周期门控（红队修正：HomeView 只在已连接时存在）——
    // 进入首页（连接建立/回前台）且开关开才启动 GPS+积分；离开即注销，零后台成本。
    // v0.2.103：演示退出判定已上移到极空页根层（原先在此处 onDispose 里，切内部
    // Tab 卸载 HomeView 会误退出演示）；这里只负责 GPS/积分的启停。
    DisposableEffect(dashEnabled) {
        if (dashEnabled) SpeedDash.start(context)
        onDispose { SpeedDash.stop() }
    }

    // v0.2.97 TTS 分段预取（用户拍板「拉全后完全本地」）：进首页后台把 0..100+词组拉进
    // 本地缓存（幂等，已缓存段零网络跳过）；全部就绪后播报字节拼接零网络零延迟。
    LaunchedEffect(Unit) { JikongSpeaker.prefetchAll(context) }

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
                // v0.2.90：小喇叭静音开关（持久化；进程启动后首次强制播报不受影响）
                muted = muted,
                onToggleMute = {
                    muted = !muted
                    JikongPrefs.setMuted(context, muted)
                    // v0.2.103：静音状态立即上报（同主题：状态 JSON 不含 app 块，去重会拦）
                    controller.forceReport()
                    Toast.makeText(
                        context,
                        if (muted) "已静音：之后不再播报（启动后首次电量播报除外）" else "已恢复播报",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
        Spacer(Modifier.height(2.dp))

        // v0.2.98：演示模式横幅（amber 点 + 「刷新模拟数据」按钮）。点刷新 = BMS 总览 +
        // 仪表盘全部随机重生成（合理范围联动：平均时速<即时速、时长=里程÷均速）。
        if (controller.demoMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.card, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(c.amber, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "演示模式 · 模拟数据（切离本页或后台自动退出）",
                    fontSize = 11.sp, color = c.amber, modifier = Modifier.weight(1f),
                )
                Text(
                    "刷新模拟数据",
                    fontSize = 12.sp, color = c.blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { controller.refreshDemoData() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }

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

        // v0.2.92 双击播报处理（圆形大表盘 + 时速卡共用；v0.2.91 只挂时速卡，用户双击圆形
        // 仪表盘无反应）：电量(+低电预警)；有时速追加 时速+剩余续航。显式手势绕过静音开关。
        // v0.2.95：改分段本地组合（词组+数字段全命中本地缓存时零网络拼接播放）；
        // 速度/续航取整播报；>100 的数值段首播按需拉取后进缓存；失败回退整句（fallback）。
        // v0.2.102：组合逻辑抽到 JikongController.speakDashboardNow（管理后台双击表盘
        // 远程触发 jikong_speak 命令复用同一份，保证两端话术永远一致）。
        val onDashDoubleTap: () -> Unit = {
            controller.speakDashboardNow(context)
        }

        // 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊）
        // v0.2.92：外层 Box 挂双击手势（手势区=表盘整行，高即表盘高）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDashDoubleTap() }) },
            contentAlignment = Alignment.Center,
        ) {
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
                avgSpeed = SpeedDash.avgSpeedKmh,
                // v0.2.95：里程/平均时速改 GPS 口径（与保护板解耦，修平均时速恒 0）
                tripKm = SpeedDash.gpsTripKm,
                tripSeconds = SpeedDash.tripSeconds,
                bmsStale = SpeedDash.bmsStale,
                permNeeded = SpeedDash.gpsPermissionNeeded,
                onReset = { SpeedDash.resetTrip(context) },
                // v0.2.92：双击播报改用共用处理（圆形大表盘同款），见 onDashDoubleTap
                onDoubleTap = onDashDoubleTap,
            )
            Spacer(Modifier.height(6.dp))
        }

        // 数据网格一：最高电压 / 最低电压 / 最大压差 / 均衡电流
        QuadGrid(
            listOf(
                QuadCell("最高电压(V)", fmt(s.maxCellVoltage), c.blue),
                QuadCell("最低电压(V)", fmt(s.minCellVoltage), c.red),
                QuadCell("最大压差(V)", fmt3(s.diffVoltage), c.green),
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
    avgSpeed: Float,
    tripKm: Float,
    tripSeconds: Float,
    bmsStale: Boolean,
    permNeeded: Boolean,
    onReset: () -> Unit,
    onDoubleTap: () -> Unit = {},   // v0.2.91：双击播报电量/时速/续航（显式手势，绕过静音）
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
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
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
        // 行1（v0.2.90 三列）：时速 | 平均时速（本次行程）| 剩余续航
        // v0.2.95 行距收紧：28sp 值去 includeFontPadding（字体上下各省 ~6dp）
        val tight = androidx.compose.ui.text.TextStyle(
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("时速(km/h)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (gpsOk) "%.1f".format(speed) else "--",
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.green, style = tight,
                )
            }
            // v0.2.106：中列去 CenterHorizontally（后台 dash-col 默认左对齐，两行「平均」起点才能垂直对齐）
            Column(Modifier.weight(1f)) {
                Text("平均时速(km/h)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (avgSpeed > 0f) "%.1f".format(avgSpeed) else "--",
                    // v0.2.91：与时速/剩余续航同级（28sp Bold）；颜色保留 text 色以区别时速绿/续航蓝
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.text, style = tight,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("剩余续航(km)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (rangeKm >= 0f) "%.0f".format(rangeKm) else "--",
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, style = tight,
                    color = if (bmsStale) c.gray else c.blue,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // v0.2.96 间距真因修复：原 DividerV() 是竖线（1dp 宽 × 16dp 高），放在行间撑出
        // 4+16+4=24dp 大空隙（用户实测「行距太大」根因）；改横向 1dp 细线。
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(4.dp))
        // 行2：瞬时 | 平均 | 里程（v0.2.90：电耗单位可切换 Wh/km ↔ kWh/100km，只改显示层）
        val kwh100 = JikongPrefs.unitKwh100(context)
        fun fmtCons(whkm: Float): String =
            if (kwh100) "%.2f".format(whkm / 10f) else if (whkm >= 100f) "%.0f".format(whkm) else "%.1f".format(whkm)
        val consUnit = if (kwh100) "kWh/100km" else "Wh/km"
        Row {
            Column(Modifier.weight(1f)) {
                Text("瞬时($consUnit)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (instWhKm > 0f) fmtCons(instWhKm) else "--",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("平均($consUnit)", fontSize = 11.sp, color = c.gray)
                Text(
                    if (avgWhKm > 0f) fmtCons(avgWhKm) else "--",
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
        Spacer(Modifier.height(4.dp))
        // 行3：时长 + 清零（v0.2.95：TextButton→clickable Text，消除 48dp 最小触控目标撑出的大空隙）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本次时长 ${fmtDuration(tripSeconds)}", fontSize = 11.sp, color = c.gray, modifier = Modifier.weight(1f))
            Text(
                "清零行程", fontSize = 11.sp, color = c.gray,
                modifier = Modifier.clickable { onReset() }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
                    DetailRow("最大压差", fmt3(s.diffVoltage) + "V")
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
    // v0.2.89 语音播报设置状态
    var speakEnabled by remember { mutableStateOf(JikongPrefs.speakEnabled(context)) }
    var speakIntervalMin by remember { mutableIntStateOf(JikongPrefs.speakIntervalMin(context)) }
    var speakLowSoc by remember { mutableIntStateOf(JikongPrefs.speakLowSoc(context)) }
    var speakWarnRepeatMin by remember { mutableIntStateOf(JikongPrefs.speakWarnRepeatMin(context)) }
    // v0.2.115 播报音量提升：主开关（默认开=智能检测）+ 强制开关（默认关=无条件拉满）
    var volumeBoostEnabled by remember { mutableStateOf(JikongPrefs.volumeBoostEnabled(context)) }
    var volumeBoostForce by remember { mutableStateOf(JikongPrefs.volumeBoostForce(context)) }
    // v0.2.90 单位/电费/里程折扣设置状态（★ 必须在下方 LaunchedEffect 之前声明）
    var unitKwh100 by remember { mutableStateOf(JikongPrefs.unitKwh100(context)) }
    var elecPrice by remember { mutableFloatStateOf(JikongPrefs.elecPrice(context)) }
    var odoDiscount by remember { mutableFloatStateOf(JikongPrefs.odoDiscount(context)) }
    // v0.2.107 靠近自动连接开关状态（★ 先于下方 Effect 声明；纯本地开关无远程同步需求）
    var proximityAuto by remember { mutableStateOf(JikongPrefs.proximityAutoConnect(context)) }
    // v0.2.102：管理后台改播报设置 → 命令下发 → Prefs 已被改 → tick 自增 → 这里重读刷新
    // v0.2.104：jikong_set_config（仪表盘/单位/电费/折扣）同用本 tick，一并重读
    androidx.compose.runtime.LaunchedEffect(JkUiState.remoteSpeakTick) {
        speakEnabled = JikongPrefs.speakEnabled(context)
        speakIntervalMin = JikongPrefs.speakIntervalMin(context)
        speakLowSoc = JikongPrefs.speakLowSoc(context)
        speakWarnRepeatMin = JikongPrefs.speakWarnRepeatMin(context)
        volumeBoostEnabled = JikongPrefs.volumeBoostEnabled(context)
        volumeBoostForce = JikongPrefs.volumeBoostForce(context)
        unitKwh100 = JikongPrefs.unitKwh100(context)
        elecPrice = JikongPrefs.elecPrice(context)
        odoDiscount = JikongPrefs.odoDiscount(context)
    }
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

        // v0.2.107 靠近自动连接：开=对默认设备挂 autoConnect 后台监听（控制器级低占空比，
        // 省电），靠近保护板自动连接并播报电量（后台也播）；走远断开自动重试；仅极空页
        // 手动点断开会暂停（重进本页/重启 APP 恢复）。关=完全维持现状。
        Spacer(Modifier.height(16.dp))
        Text("靠近自动连接", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("靠近默认保护板自动连接", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "无需打开极空页，靠近默认设备即自动连接并播报电量；走远自动断开、回来自动重连；在本页点断开后暂停，重新进入本页恢复。后台监听极省电、不耗流量。",
                        fontSize = 11.sp, color = c.gray,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = proximityAuto,
                    onCheckedChange = { on ->
                        if (on && JikongPrefs.defaultMac(context).isBlank()) {
                            // 功能依赖默认设备：无默认设备回弹 + 提示
                            Toast.makeText(context, "请先连接设备并设为默认设备", Toast.LENGTH_SHORT).show()
                        } else {
                            proximityAuto = on
                            JikongPrefs.setProximityAutoConnect(context, on)
                            if (on) JikongProximityService.evaluate(context)
                            else JikongProximityService.stop(context)
                            // Prefs 变更必须手动上报（reportStatus 按 status JSON 去重）
                            controller.forceReport()
                        }
                    },
                )
            }
        }

        // v0.2.89 语音播报：开关 + 播报间隔 + 低电量阈值 + 预警提醒间隔。
        // 判定规则（用户拍板）：每帧持续判定——电量≠上一帧或≠上次播报值→播；
        // 相同且距上次播报≥间隔→播；低于阈值首次跌破立即播带提醒，此后按预警提醒间隔重复提醒。
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
                            "本页打开时播报电量：电量变化即播；相同且超播报间隔再播；低于阈值立即提醒并按预警间隔重复提醒。",
                            fontSize = 11.sp, color = c.gray,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = speakEnabled,
                        onCheckedChange = {
                            speakEnabled = it
                            JikongPrefs.setSpeakEnabled(context, it)
                            // v0.2.104：立即上报（后台刷新即同步）
                            controller.forceReport()
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
                        controller.forceReport()
                    },
                    onPlus = {
                        speakIntervalMin = (speakIntervalMin + 1).coerceAtMost(120)
                        JikongPrefs.setSpeakIntervalMin(context, speakIntervalMin)
                        controller.forceReport()
                    },
                )
                Spacer(Modifier.height(2.dp))
                JkStepperRow(
                    label = "低电量阈值",
                    value = "$speakLowSoc %",
                    onMinus = {
                        speakLowSoc = (speakLowSoc - 1).coerceAtLeast(1)
                        JikongPrefs.setSpeakLowSoc(context, speakLowSoc)
                        controller.forceReport()
                    },
                    onPlus = {
                        speakLowSoc = (speakLowSoc + 1).coerceAtMost(50)
                        JikongPrefs.setSpeakLowSoc(context, speakLowSoc)
                        controller.forceReport()
                    },
                )
                Spacer(Modifier.height(2.dp))
                JkStepperRow(
                    label = "预警提醒间隔",
                    value = "$speakWarnRepeatMin 分钟",
                    onMinus = {
                        speakWarnRepeatMin = (speakWarnRepeatMin - 1).coerceAtLeast(1)
                        JikongPrefs.setSpeakWarnRepeatMin(context, speakWarnRepeatMin)
                        controller.forceReport()
                    },
                    onPlus = {
                        speakWarnRepeatMin = (speakWarnRepeatMin + 1).coerceAtMost(60)
                        JikongPrefs.setSpeakWarnRepeatMin(context, speakWarnRepeatMin)
                        controller.forceReport()
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "低于阈值立即播报「电量低，请注意充电」，之后即使电量不变也按预警提醒间隔重复提醒；首次跌破阈值不受间隔限制立即播。需要设备装有系统文字转语音（TTS）引擎。",
                    fontSize = 11.sp, color = c.gray,
                )
                Spacer(Modifier.height(10.dp))
                // v0.2.115 播报音量提升：主开关（默认开=智能检测）+ 强制开关（默认关=无条件拉满）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("播报时提升音量", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "每次播报前临时把媒体音量拉到最大、播完恢复；若检测到其他程序正在放声音（听歌/看视频/导航）则不拉，避免打断。",
                            fontSize = 11.sp, color = c.gray,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = volumeBoostEnabled,
                        onCheckedChange = {
                            volumeBoostEnabled = it
                            JikongPrefs.setVolumeBoostEnabled(context, it)
                            controller.forceReport()
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("强制提升", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "打开后不检测其他程序，所有播报只要音量不是最大就拉满。仅在「播报时提升音量」开启时生效。",
                            fontSize = 11.sp, color = c.gray,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = volumeBoostForce,
                        enabled = volumeBoostEnabled,
                        onCheckedChange = {
                            volumeBoostForce = it
                            JikongPrefs.setVolumeBoostForce(context, it)
                            controller.forceReport()
                        },
                    )
                }
            }
        }

        // v0.2.90 单位与花费：电耗显示单位（只改显示层）+ 电费单价（统计页花费计算）
        Spacer(Modifier.height(16.dp))
        Text("单位与花费", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
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
                    Text("电耗单位", fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
                    // 分段单选：Wh/km | kWh/100km（1 kWh/100km = 10 Wh/km）
                    listOf(false to "Wh/km", true to "kWh/100km").forEach { (sel, label) ->
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (unitKwh100 == sel) Color.White else c.gray,
                            modifier = Modifier
                                .background(
                                    if (unitKwh100 == sel) JkBlue else c.bg,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable {
                                    unitKwh100 = sel
                                    JikongPrefs.setUnitKwh100(context, sel)
                                    // v0.2.104：立即上报（后台刷新即同步）
                                    controller.forceReport()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                JkStepperRow(
                    label = "电费单价",
                    value = "%.2f 元/度".format(elecPrice),
                    onMinus = {
                        elecPrice = (((elecPrice * 20).toInt() - 1).coerceAtLeast(1)) / 20f
                        JikongPrefs.setElecPrice(context, elecPrice)
                        controller.forceReport()
                    },
                    onPlus = {
                        elecPrice = (((elecPrice * 20).toInt() + 1).coerceAtMost(200)) / 20f
                        JikongPrefs.setElecPrice(context, elecPrice)
                        controller.forceReport()
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "单位切换只影响显示（首页仪表盘瞬时/平均电耗），内部计算一律 Wh/km。" +
                        "电费单价用于统计页区间总花费与平均花费（元/100km）。",
                    fontSize = 11.sp, color = c.gray,
                )
            }
        }

        // v0.2.90 里程折扣：三轮车表显里程偏大时按折扣换算（只影响统计页显示层）
        Spacer(Modifier.height(16.dp))
        Text("里程折扣", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.card),
        ) {
            Column(Modifier.padding(14.dp)) {
                JkStepperRow(
                    label = "三轮车表显里程折扣",
                    value = "%.1f".format(odoDiscount),
                    onMinus = {
                        odoDiscount = (((odoDiscount * 10).toInt() - 1).coerceAtLeast(1)) / 10f
                        JikongPrefs.setOdoDiscount(context, odoDiscount)
                        controller.forceReport()
                    },
                    onPlus = {
                        odoDiscount = (((odoDiscount * 10).toInt() + 1).coerceAtMost(10)) / 10f
                        JikongPrefs.setOdoDiscount(context, odoDiscount)
                        controller.forceReport()
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "三轮车表显里程偏大时（如真实行驶 10km 表显 15km），设折扣换算真实里程。" +
                        "设为 1.0 时统计页与原来完全一致；设为其它值（0.1~1.0）时统计页里程按折扣显示、" +
                        "电耗按真实里程重算变大，原值以括号小字保留。台账数据库始终存原始输入。",
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
        // v0.2.90：生命周期累计平均（装机起累积的经验数据，估算链优先用它），老用户兜底 histAvg
        val lifeAvg = if (totalKm >= 1f && totalWh > 0f) totalWh / totalKm
            else JikongPrefs.histAvgWhKm(context)
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
                    Text("生命周期电耗", fontSize = 11.sp, color = c.gray)
                    Text(
                        if (lifeAvg > 0f) "%.1f".format(lifeAvg) else "--",
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
                    "续航 = 剩余电量能量 ÷ 预测电耗（EV 通行算法）。预测电耗优先级（v0.2.90）：本次行程平均 > " +
                        "生命周期累计平均（装机起累积、随里程越来越准）> 会话 EMA 暖场 > 参考值 20 Wh/km。" +
                        "所有数值为估算，仅供参考。保护板参数修改需设备密码，本应用不提供。",
                    fontSize = 11.sp, color = c.gray,
                )
            }
        }
    }
}

/** v0.2.90：统计页概要单元卡（数值+单位+标签+可选小字）。RowScope 扩展（weight(1f)）。 */
@Composable
private fun RowScope.JkSumCell(
    label: String,
    value: String,
    unit: String,
    c: JkColors,
    highlight: Boolean = false,
    sub: String? = null,
) {
    Column(
        Modifier.weight(1f).background(c.bg, RoundedCornerShape(10.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (highlight) c.green else c.text)
            if (value != "--") {
                Spacer(Modifier.width(2.dp))
                Text(unit, fontSize = 9.sp, color = c.gray)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = c.gray)
        if (sub != null) {
            Text(sub, fontSize = 8.sp, color = c.gray)   // 折扣≠1 时括号保留原值（调用方自带「原 」前缀；v0.2.91 修重复前缀）
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

/** v0.2.92：DateRangePicker 返回所选日期「UTC 零点」毫秒，转成本地当天零点
 *  （UTC+8 下不转会差 8 小时导致区间首尾错日）。 */
private fun jkLocalDayStart(utcDayMs: Long): Long {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = utcDayMs
    return java.util.Calendar.getInstance().apply {
        clear()
        set(
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            0, 0, 0,
        )
    }.timeInMillis
}

// v0.2.102：jkDemoRecords() 已迁入 JikongController（演示台账需随 jikong_state 上报
// demo_stats 给管理后台，Composable 私有状态报不了）；StatsView 直接读 controller.demoOdoRecords。

@OptIn(ExperimentalMaterial3Api::class)   // v0.2.92：DateRangePicker（自定义时间段）
@Composable
private fun StatsView(controller: JikongController) {
    val c = LocalJkColors.current
    val context = LocalContext.current
    controller.statusVersion.value // 订阅 BMS 数据版本：循环容量/总压实时值随 0x02 帧刷新
    // v0.2.104：订阅远程设置 tick——后台 jikong_set_config 改折扣/电费后，此处重算立即生效
    //（不订阅则 Prefs 快照值冻结在上次重组，要等下帧 BMS 数据才刷新）
    JkUiState.remoteSpeakTick

    var recordsVersion by remember { mutableIntStateOf(0) } // 记录集版本（增删/同步后自增驱动重读）
    var odoText by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    val demoMode = controller.demoMode

    // v0.2.91：演示模式用内存演示台账（不读写真实存储、不触发同步），退出演示自动还原
    // v0.2.102：台账本体迁入 controller.demoOdoRecords（后台统计页需同源上报演示数据）
    val records = if (demoMode) controller.demoOdoRecords else remember(recordsVersion) { JikongStatsStore.records(context) }
    val segs = remember(records) { jkBuildSegs(records) }
    val pending = if (demoMode) 0 else remember(recordsVersion) { JikongStatsStore.unsyncedCount(context) }

    // 概要（第一条基准 → 最后一条；仅统计有效段）
    val validSegs = segs.filterNotNull().filter { it.valid }
    val totalKm = if (records.size >= 2) records.last().odoKm - records.first().odoKm else 0f
    val totalWh = validSegs.sumOf { (it.deltaAh * it.avgVolt).toDouble() }.toFloat()
    val totalValidKm = validSegs.sumOf { it.deltaKm.toDouble() }.toFloat()
    val avgWhKm = if (totalValidKm > 0.5f) totalWh / totalValidKm else 0f

    // v0.2.90 显示层设置：里程折扣（只影响显示，台账存原始输入）+ 电费单价（花费计算）
    val discount = JikongPrefs.odoDiscount(context)
    val price = JikongPrefs.elecPrice(context)
    val totalKmDisp = totalKm * discount
    val avgWhKmDisp = if (avgWhKm > 0f) avgWhKm / discount else 0f   // 折扣后里程变短 → 电耗变大
    val totalCost = totalWh / 1000f * price                          // 区间总花费（元）
    val avgCost100 = if (avgWhKmDisp > 0f) avgWhKmDisp * price / 10f else 0f  // 平均花费（元/100km）

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
                if (demoMode) {
                    // v0.2.91：演示模式记一笔 → 只追加内存演示行（不落盘、不同步）
                    // v0.2.102：迁到 controller 列表 + forceReport（后台统计页同步看到）
                    controller.demoOdoRecords.add(
                        JkMileageRecord(
                            id = "demo_${System.currentTimeMillis()}",
                            ts = System.currentTimeMillis(),
                            odoKm = odo,
                            cycleAh = controller.status.cycleCapacity,
                            volt = controller.status.totalVoltage,
                            synced = true,
                        )
                    )
                    odoText = ""
                    controller.forceReport()
                    Toast.makeText(context, "已记录（演示数据，退出演示即还原）", Toast.LENGTH_SHORT).show()
                } else {
                    JikongStatsStore.add(context, odo, controller.status.cycleCapacity, controller.status.totalVoltage)
                    odoText = ""
                    recordsVersion++
                    // 录入后静默自动同步一次（失败不打扰，保留待同步标记）
                    JikongStatsSync.sync(context, silent = true) { _, _ -> recordsVersion++ }
                    Toast.makeText(context, "已记录", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val onDelete: (String) -> Unit = { id ->
        if (demoMode) {
            // v0.2.91：演示模式删除 → 仅动内存演示行（v0.2.102 迁 controller + forceReport）
            controller.demoOdoRecords.removeAll { it.id == id }
            controller.forceReport()
        } else {
            val needTombstone = JikongStatsStore.delete(context, id)
            if (needTombstone) JikongStatsStore.enqueueDelete(context, id)
            recordsVersion++
            // 已同步行删除 → 墓碑静默上行；未同步行仅动本地（无需同步）
            if (needTombstone) JikongStatsSync.sync(context, silent = true) { _, _ -> recordsVersion++ }
        }
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
            Box(Modifier.size(7.dp).background(if (demoMode) c.amber else c.green, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                if (demoMode) "已连接 · 演示数据" else "已连接 · JK-BMS",
                fontSize = 11.sp, color = if (demoMode) c.amber else c.gray,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- 概要五卡（v0.2.90：3+2 两行；里程/派生量按折扣显示，花费按电费计算）----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    JkSumCell("记录区间里程", if (totalKmDisp > 0f) "%.1f".format(totalKmDisp) else "--", "km",
                        sub = if (discount < 1f && totalKm > 0f) "原 %.1f".format(totalKm) else null, c = c)
                    Spacer(Modifier.width(8.dp))
                    JkSumCell("区间总耗电", if (totalWh > 0f) "%.2f".format(totalWh / 1000f) else "--", "kWh", c = c)
                    Spacer(Modifier.width(8.dp))
                    JkSumCell("区间总花费", if (totalCost > 0f) "%.2f".format(totalCost) else "--", "元",
                        highlight = true, c = c)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    JkSumCell("平均电耗", if (avgWhKmDisp > 0f) "%.1f".format(avgWhKmDisp) else "--", "Wh/km",
                        sub = if (discount < 1f && avgWhKm > 0f) "原 %.1f".format(avgWhKm) else null, c = c)
                    Spacer(Modifier.width(8.dp))
                    JkSumCell("平均花费", if (avgCost100 > 0f) "%.2f".format(avgCost100) else "--", "元/100km",
                        highlight = true, c = c)
                    Spacer(Modifier.weight(1f))   // 行 2 只有 2 卡，补齐网格
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 阶段统计（v0.2.91：近一周/近一月/一季度/近一年，默认近一月）----
        // 口径：段按其记录时间归属窗口；里程按折扣显示、电耗按折扣里程重算（同概要）。
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("阶段统计", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    Text("按记录时间归属", fontSize = 10.sp, color = c.gray)
                }
                Spacer(Modifier.height(8.dp))
                // v0.2.93：去掉「近」字减宽（chip 太多时「自定义」被挤到竖排），并禁自动换行
                val ranges = remember {
                    listOf("一周" to 7L, "一月" to 30L, "一季度" to 90L, "一年" to 365L)
                }
                var stageIdx by remember { mutableStateOf(1) }   // 默认近一月
                // v0.2.92 自定义时间段：stageIdx == ranges.size 表示自定义；点 chip 弹日期区间选择器
                var showRangePicker by remember { mutableStateOf(false) }
                var customRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ranges.forEachIndexed { i, (label, _) ->
                        val sel = i == stageIdx
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (sel) c.onfill else c.gray,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .background(if (sel) c.green else c.bg, RoundedCornerShape(14.dp))
                                .clickable { stageIdx = i }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                        if (i < ranges.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    val selCustom = stageIdx == ranges.size
                    Text(
                        "自定义",
                        fontSize = 12.sp,
                        color = if (selCustom) c.onfill else c.gray,
                        fontWeight = if (selCustom) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(if (selCustom) c.green else c.bg, RoundedCornerShape(14.dp))
                            .clickable {
                                stageIdx = ranges.size
                                showRangePicker = true
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                // 已选自定义区间时展示具体日期（MM-dd ~ MM-dd）
                if (stageIdx == ranges.size) {
                    customRange?.let { r ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "自定义区间：${JkDateFmt.format(r.first)} ~ ${JkDateFmt.format(r.second)}",
                            fontSize = 10.sp, color = c.cyan,
                        )
                    }
                    // v0.2.92 自定义时间段弹窗：Material3 DateRangePicker（起点+终点两次点选）
                    if (showRangePicker) {
                        val pickerState = rememberDateRangePickerState(
                            initialSelectedStartDateMillis = customRange?.first,
                            initialSelectedEndDateMillis = customRange?.second,
                        )
                        Dialog(onDismissRequest = { showRangePicker = false }) {
                            Surface(shape = RoundedCornerShape(14.dp), color = c.card) {
                                // 极空页是自绘配色（无全局 MaterialTheme 包装），此处临时提供
                                // 亮/暗 colorScheme，让 DateRangePicker 默认配色跟随当前主题渲染
                                MaterialTheme(
                                    colorScheme = if (c.bg.luminance() < 0.5f) darkColorScheme() else lightColorScheme(),
                                ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        "选择自定义时间段",
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    DateRangePicker(
                                        state = pickerState,
                                        showModeToggle = false,
                                        title = null,
                                        headline = null,
                                        modifier = Modifier.height(420.dp),
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(onClick = { showRangePicker = false }) {
                                            Text("取消", color = c.gray)
                                        }
                                        TextButton(
                                            onClick = {
                                                val s0 = pickerState.selectedStartDateMillis
                                                val s1 = pickerState.selectedEndDateMillis
                                                if (s0 != null && s1 != null) {
                                                    customRange = Pair(s0, s1)
                                                    stageIdx = ranges.size
                                                }
                                                showRangePicker = false
                                            },
                                            enabled = pickerState.selectedEndDateMillis != null,
                                        ) {
                                            Text(
                                                "确定",
                                                color = if (pickerState.selectedEndDateMillis != null) c.green else c.gray,
                                            )
                                        }
                                    }
                                }
                                }   // MaterialTheme
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // v0.2.92：自定义 → 日期区间 [start, end+1天)（picker 给 UTC 零点，转本地零点防差日）；
                // 预设 → 近 N 天。段仍按记录时间归属。
                val dayMs = 24 * 3600_000L
                val range = customRange
                val stageSegs = if (stageIdx == ranges.size) {
                    if (range == null) emptyList()
                    else {
                        val start = jkLocalDayStart(range.first)
                        val end = jkLocalDayStart(range.second) + dayMs
                        validSegs.filter { it.rec.ts >= start && it.rec.ts < end }
                    }
                } else {
                    val cutoff = System.currentTimeMillis() - ranges[stageIdx].second * dayMs
                    validSegs.filter { it.rec.ts >= cutoff }
                }
                val stageKmRaw = stageSegs.sumOf { it.deltaKm.toDouble() }.toFloat()
                val stageWh = stageSegs.sumOf { (it.deltaAh * it.avgVolt).toDouble() }.toFloat()
                val stageKmDisp = stageKmRaw * discount
                val stageAvgWh = if (stageKmRaw > 0.1f) stageWh / stageKmRaw / discount else 0f
                val stageCost = stageWh / 1000f * price
                val stageAvgCost = if (stageAvgWh > 0f) stageAvgWh * price / 10f else 0f
                Row {
                    JkSumCell("阶段里程", if (stageKmDisp > 0f) "%.1f".format(stageKmDisp) else "--", "km",
                        sub = if (discount < 1f && stageKmRaw > 0f) "原 %.1f".format(stageKmRaw) else null, c = c)
                    Spacer(Modifier.width(8.dp))
                    JkSumCell("阶段花费", if (stageCost > 0f) "%.2f".format(stageCost) else "--", "元",
                        highlight = true, c = c)
                    Spacer(Modifier.width(8.dp))
                    JkSumCell("平均花费", if (stageAvgCost > 0f) "%.2f".format(stageAvgCost) else "--", "元/100km", c = c)
                }
                if (stageSegs.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (stageIdx == ranges.size && range == null) "请先选择起止日期"
                        else "该时间段内还没有记录",
                        fontSize = 10.sp, color = c.gray,
                    )
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
                // v0.2.90 记一笔门控（用户拍板）：必须成功连接且读到数据（循环容量>0）才可记录
                val canAdd = controller.hasDataFrame && controller.status.cycleCapacity > 0f
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
                        enabled = canAdd,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.green,
                            contentColor = c.onfill,
                            disabledContainerColor = c.track,
                            disabledContentColor = c.gray,
                        ),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("记一笔", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (!canAdd) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ 未连接保护板或数据未到，暂不能记录（连接成功并读到数据后自动恢复）",
                        fontSize = 10.sp, color = c.amber,
                    )
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

        // ---- 能耗曲线（v0.2.90：左轴 Wh/km · 右轴 元/100km 双刻度，X 轴按折扣里程）----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.card)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("能耗曲线", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    if (avgWhKmDisp > 0f) {
                        Text(
                            "平均 %.1f Wh/km ≈ %.2f 元/100km".format(avgWhKmDisp, avgCost100),
                            fontSize = 11.sp, color = c.cyan,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "左轴 Wh/km（绿）· 右轴 元/100km（琥珀，按电费 %.2f 元/度换算）".format(price),
                    fontSize = 10.sp, color = c.gray,
                )
                Spacer(Modifier.height(8.dp))
                if (validSegs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("录入两条以上记录后显示电耗曲线", fontSize = 12.sp, color = c.gray)
                    }
                } else {
                    JkStatsCurve(validSegs, avgWhKmDisp, c, discount, price)
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
                        Text(
                            if (demoMode) "${records.size} 条演示 · 退出即还原" else "${records.size} 条 · 新在上 · 存本机",
                            fontSize = 10.sp, color = c.gray,
                        )
                    }
                    if (demoMode) {
                        // v0.2.91：演示模式无同步（不碰服务端）
                        Text("演示 · 不同步", fontSize = 11.sp, color = c.gray)
                    } else {
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
                            JkStatsRow(records[idx], seg, c, discount, price) { onDelete(records[idx].id) }
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
                        "该段自动标记无效、不进曲线，并以新容量重新为基准。" +
                        if (discount < 1f) "当前里程折扣 %.1f：所有里程按折扣显示（真实里程），电耗/花费按真实里程重算，原值以小字保留。".format(discount)
                        else "",
                    fontSize = 11.sp, color = c.gray, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 一行台账记录（日期/未同步徽标 | 里程+距上次 | Δ明细 | 电耗+花费 | 删除）。v0.2.90 加折扣显示与花费。 */
@Composable
private fun JkStatsRow(
    rec: JkMileageRecord,
    seg: JkSeg?,
    c: JkColors,
    discount: Float,
    price: Float,
    onDelete: () -> Unit,
) {
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
        // 里程列（v0.2.90：折扣≠1 时主显折扣后值、小字保留原值）
        Column(Modifier.width(78.dp)) {
            Text("%.1f".format(rec.odoKm * discount), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (discount < 1f) {
                Text("原 %.1f".format(rec.odoKm), fontSize = 8.sp, color = c.gray)
            }
            Text(
                if (seg == null) "基准 · 容量 ${fmt(rec.cycleAh)}Ah"
                else "距上次 +%.1f km".format(seg.deltaKm * discount),
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
        // 电耗列（v0.2.90：折扣≠1 时按真实里程重算变大、小字保留原值；下方加该段花费）
        Column(horizontalAlignment = Alignment.End) {
            when {
                seg == null -> Text("基准", fontSize = 11.sp, color = c.gray)
                !seg.valid -> Text("--", fontSize = 12.sp, color = c.gray)
                else -> {
                    val whKmDisp = seg.whKm / discount
                    val color = when {
                        whKmDisp >= 38f -> c.amber   // 偏高（载重/爬坡）
                        whKmDisp <= 31f -> c.green   // 偏优
                        else -> c.text
                    }
                    Text("%.1f".format(whKmDisp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
                    Text("Wh/km", fontSize = 9.sp, color = c.gray)
                    if (discount < 1f) Text("原 %.1f".format(seg.whKm), fontSize = 8.sp, color = c.gray)
                    Text(
                        "≈%.2f 元".format(seg.deltaAh * seg.avgVolt / 1000f * price),
                        fontSize = 9.sp, color = c.gray,
                    )
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

/**
 * 能耗折线图（Canvas 手绘：网格 + 折线 + 数据点 + 平均线 + 双轴标签）。
 * v0.2.90：左轴 Wh/km、右轴 元/100km（＝左值 × 电费 ÷ 10，线性换算共用一条曲线）、X 轴折扣里程。
 */
@Composable
private fun JkStatsCurve(segs: List<JkSeg>, avgWhKm: Float, c: JkColors, discount: Float, price: Float) {
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val padL = 34.dp.toPx()
        val padR = 36.dp.toPx()   // v0.2.90：右轴标签占位
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        val ys = segs.map { it.whKm / discount }   // 折扣后真实里程口径（原值仍在台账括号保留）
        var yMin = ys.min() * 0.9f
        var yMax = ys.max() * 1.1f
        if (yMax - yMin < 2f) { // 电耗过近时拉开量程，避免折线贴成一条线
            val mid = (yMax + yMin) / 2f
            yMin = mid - 1f; yMax = mid + 1f
        }
        val xMin = segs.first().rec.odoKm * discount
        val xMax = segs.last().rec.odoKm * discount
        val xSpan = (xMax - xMin).coerceAtLeast(0.01f)

        fun xOf(v: Float) = padL + (v - xMin) / xSpan * plotW
        fun yOf(v: Float) = padT + (1f - (v - yMin) / (yMax - yMin)) * plotH

        // 横向网格 + 左轴标签（Wh/km，灰）+ 右轴标签（元/100km，琥珀）
        val labelPaint = android.graphics.Paint().apply {
            color = c.gray.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true
        }
        val rightPaint = android.graphics.Paint().apply {
            color = c.amber.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        for (k in 0..2) {
            val yv = yMin + (yMax - yMin) * k / 2f
            val py = yOf(yv)
            drawLine(c.divider, Offset(padL, py), Offset(padL + plotW, py), 1.dp.toPx())
            drawIntoCanvas { it.nativeCanvas.drawText("%.0f".format(yv), 0f, py + 4.dp.toPx(), labelPaint) }
            // 右轴：元/100km = Wh/km × 电费 ÷ 10（线性换算，与曲线同形）
            val cost = yv * price / 10f
            drawIntoCanvas {
                it.nativeCanvas.drawText("%.1f".format(cost), size.width, py + 4.dp.toPx(), rightPaint)
            }
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
        val pts = segs.map { Offset(xOf(it.rec.odoKm * discount), yOf(it.whKm / discount)) }
        for (i in 0 until pts.size - 1) {
            drawLine(c.green, pts[i], pts[i + 1], 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        pts.forEach {
            drawCircle(c.bg, 4.dp.toPx(), it)                 // 底圈（描边感）
            drawCircle(c.green, 2.8.dp.toPx(), it)
        }

        // X 轴标签（首/中/尾里程，折扣后）
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

/** v0.2.102：压差等小数值三位小数（0.008V 级别两位会四舍五入成 0.01，用户要求 3 位）。 */
private fun fmt3(v: Float): String = "%.3f".format(v)
