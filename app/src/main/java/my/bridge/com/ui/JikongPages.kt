package my.bridge.com.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import my.bridge.com.jikong.BleDevice
import my.bridge.com.jikong.JikongBleClient
import my.bridge.com.jikong.JikongController
import my.bridge.com.jikong.JikongPrefs

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
)

private val DarkColors = JkColors(
    bg = Color(0xFF000000), card = Color(0xFF26282B), text = Color(0xFFE8EAED),
    gray = Color(0xFF8A919F), divider = Color(0xFF33363A),
    green = Color(0xFF00E050), cyan = Color(0xFF00B8D4), red = Color(0xFFE5484D), blue = Color(0xFF4D8BF5),
    track = Color(0xFF3A3D40), tabbar = Color(0xFF1A1C1F), tabline = Color(0xFF222222),
    g1 = Color(0xFF00E050), g2 = Color(0xFF00C8A0), g3 = Color(0xFF00A8E8),
    numA = Color(0xFFC8CED6), numB = Color(0xFF6B727C), onfill = Color(0xFF06210E),
)

private val LightColors = JkColors(
    bg = Color(0xFFEEF1F5), card = Color(0xFFFFFFFF), text = Color(0xFF17191D),
    gray = Color(0xFF5D6672), divider = Color(0xFFDFE3E8),
    green = Color(0xFF00A344), cyan = Color(0xFF0097B8), red = Color(0xFFD93036), blue = Color(0xFF2F6FD8),
    track = Color(0xFFD7DBE1), tabbar = Color(0xFFFFFFFF), tabline = Color(0xFFE3E6EA),
    g1 = Color(0xFF00B050), g2 = Color(0xFF00A8A0), g3 = Color(0xFF0A84D0),
    numA = Color(0xFF2A2F36), numB = Color(0xFF9AA2AD), onfill = Color(0xFFFFFFFF),
)

/** CompositionLocal 提供当前主题配色。 */
private val LocalJkColors = androidx.compose.runtime.compositionLocalOf { DarkColors }

/** 根据背景主题模式（AUTO 跟随时间 / 手动指定优先）解析是否深色。 */
private fun resolveDarkTheme(context: android.content.Context): Boolean {
    return when (JikongPrefs.theme(context)) {
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
 * 复刻极空官方 APP（JK BMS）三页结构：首页（仪表盘）/ 实时状态（详细数据）/ 参数设置。
 * 数据字段偏移已按真机字节逐字段校准确认（peff74/BHP1000 偏移）。
 */
@Composable
fun JikongPage() {
    val context = LocalContext.current
    val controller = remember { JikongController.instance }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 主 Tab：实时状态 / 首页 / 参数设置（定档原型顺序，默认首页）
    var tab by remember { mutableIntStateOf(1) }

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

    fun doScan() {
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
            return
        }
        if (!JikongBleClient.isBluetoothEnabled(context)) {
            Toast.makeText(context, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
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

    fun doConnect(dev: BleDevice) {
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
            val msg = if (ok) "已连接 ${dev.name}" else "连接失败，请靠近设备重试"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 页面可见性守卫：ON_RESUME 标记可见并开始轮询，ON_PAUSE 释放
    DisposableEffect(Unit) {
        controller.setPageVisible(true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.setPageVisible(true)
                Lifecycle.Event.ON_PAUSE -> controller.setPageVisible(false)
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

    // 背景主题：自动跟随时间（默认），手动指定浅色/深色则优先
    var themeMode by remember { mutableIntStateOf(JikongPrefs.theme(context)) }
    val isDark = resolveDarkTheme(context)
    val colors = if (isDark) DarkColors else LightColors

    androidx.compose.runtime.CompositionLocalProvider(LocalJkColors provides colors) {
        Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
            // 顶部状态栏：连接状态 + 设备名 + 主题切换 + 断开按钮
            ConnectionBar(
                connected = controller.status.connected,
                deviceName = controller.status.deviceName,
                themeMode = themeMode,
                onThemeCycle = {
                    val next = (themeMode + 1) % 3
                    themeMode = next
                    JikongPrefs.setTheme(context, next)
                },
                onDisconnect = { controller.disconnect() },
            )

            if (!controller.status.connected) {
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
                )
            } else {
                // 已连接：底部导航三页（首页 / 实时状态 / 参数设置），对齐官方
                Column(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        0 -> RealtimeView(controller)
                        1 -> HomeView(controller)
                        2 -> ParamView(controller)
                    }
                }
                // 底部导航栏
                BottomNavBar(tab) { tab = it }
            }
        }
    }
}

// ==================== 底部导航栏（定档：图标 + 选中指示条，顺序 实时状态/首页/参数设置） ====================
// 图标 path 数据与定档 HTML 一致（24×24，stroke=2，fill=none）；参数设置为圆+射线
private val JkTabs = listOf(
    Triple("实时状态", "M4 20V10l8-6 8 6v10", false),
    Triple("首页", "M4 20V10l8-6 8 6v10", false),
    Triple("参数设置", "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9L17 7M7 17l-2.1 2.1", true),
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
                .padding(vertical = 6.dp),
        ) {
            JkTabs.forEachIndexed { i, (label, path, withCircle) ->
                val active = selected == i
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 选中指示条（26×3 圆角绿条）；未选中用等高占位保持图标对齐
                    if (active) {
                        Box(Modifier.width(26.dp).height(3.dp).background(c.green, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.height(2.dp))
                    } else {
                        Spacer(Modifier.height(5.dp))
                    }
                    TabIcon(path, withCircle, if (active) c.green else c.gray)
                    Spacer(Modifier.height(3.dp))
                    Text(label, fontSize = 11.sp, color = if (active) c.green else c.gray)
                }
            }
        }
    }
}

/** 24×24 描边小图标（path 数据来自定档 HTML，等比缩放到 22dp）。 */
@Composable
private fun TabIcon(pathData: String, withCircle: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val s = size.width / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            if (withCircle) {
                drawCircle(tint, radius = 3f, center = Offset(12f, 12f), style = Stroke(2f))
            }
            val p = PathParser().parsePathString(pathData).toPath()
            drawPath(p, tint, style = Stroke(2f))
        }
    }
}

// ==================== 连接状态栏 ====================
@Composable
private fun ConnectionBar(
    connected: Boolean,
    deviceName: String,
    themeMode: Int,
    onThemeCycle: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (connected) JkGreen else c.gray, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (connected) "已连接 · $deviceName" else "未连接",
            color = if (connected) JkGreen else c.gray,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        // 主题切换：自动 → 浅色 → 深色（循环）
        val themeLabel = when (themeMode) {
            JikongPrefs.THEME_LIGHT -> "浅色"
            JikongPrefs.THEME_DARK -> "深色"
            else -> "自动"
        }
        TextButton(onClick = onThemeCycle) { Text(themeLabel, color = JkBlue, fontSize = 13.sp) }
        if (connected) {
            TextButton(onClick = onDisconnect) { Text("断开", color = JkRed) }
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
                                Text(dev.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${dev.address} · 信号 ${dev.rssi} dBm",
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
private fun HomeView(controller: JikongController) {
    // 订阅版本号：数据变化触发本页重组，实现实时刷新
    controller.statusVersion.value
    val s = controller.status
    val c = LocalJkColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // 充电/放电/均衡 一行三列（inline 圆点+文字，定档样式）
        StatusRow(s)
        Spacer(Modifier.height(12.dp))

        // 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊）
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            JkGauge(s.soc, s.totalVoltage, s.current, s.totalVoltage * s.current)
        }
        Spacer(Modifier.height(4.dp))

        // 状态提示条（盾牌图标 + 电池运行良好）
        HealthBanner()
        Spacer(Modifier.height(12.dp))

        // 数据网格一：最高电压 / 最低电压 / 最大压差 / 均衡电流
        QuadGrid(
            listOf(
                QuadCell("最高电压(V)", fmt(s.maxCellVoltage), c.blue),
                QuadCell("最低电压(V)", fmt(s.minCellVoltage), c.red),
                QuadCell("最大压差(V)", fmt(s.diffVoltage), c.green),
                QuadCell("均衡电流(A)", fmt(s.balanceCurrent), c.green),
            )
        )
        Spacer(Modifier.height(10.dp))

        // 数据网格二：电池容量 / 剩余容量 / 最高温度 / 电池类型
        QuadGrid(
            listOf(
                QuadCell("电池容量(Ah)", fmt(s.nominalCapacity), c.green),
                QuadCell("剩余容量(Ah)", fmt(s.remainCapacity), c.green),
                QuadCell("最高温度(℃)", fmt(s.maxTemperature), c.green),
                QuadCell("电池类型", if (s.batteryType.isBlank()) "--" else s.batteryType, c.green),
            )
        )
        Spacer(Modifier.height(10.dp))

        // 电流 / 功率 + 双向进度条 + 状态 / 剩余时间
        CurrentPowerCard(s)
        Spacer(Modifier.height(12.dp))
    }
}

/** 首页三分栏状态行（充电/放电/均衡，inline 同行样式，定档）。充电/放电按电流方向判断。 */
@Composable
private fun StatusRow(s: my.bridge.com.jikong.JikongStatus) {
    val c = LocalJkColors.current
    // 电流方向：>阈值充电，<阈值放电，否则静止（充放电不可能同时开启）
    val charging = s.current > 0.1f
    val discharging = s.current < -0.1f
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
        StatusCellInline("均衡", s.balanceOn, Modifier.weight(1f))
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

/** 电流/功率卡片（定档：同行基线标注 + 中点 0 双向进度条 + 状态/剩余时间）。 */
@Composable
private fun CurrentPowerCard(s: my.bridge.com.jikong.JikongStatus) {
    val c = LocalJkColors.current
    // 功率现场算：总电压 × 电流，保证与顶部仪表盘电流同源同帧，杜绝滞后。
    val power = s.totalVoltage * s.current
    // 剩余时间本地计算（保护板不提供）：充电=(标称-剩余)/电流，放电=剩余/电流，单位小时
    val remainSeconds = localRemainTime(s)
    val charging = s.current > 0.1f
    val discharging = s.current < -0.1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 电流(A) x.xx   功率(W) xxx.x（同行基线对齐，定档样式）
        Row {
            Text("电流(A)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(fmt(s.current), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(22.dp))
            Text("功率(W)", fontSize = 13.sp, color = c.gray, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(fmt(power), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.green, modifier = Modifier.alignByBaseline())
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
        val maxCharge = if (s.chargeCurrentLimit > 0f) s.chargeCurrentLimit else 50f
        val maxDischarge = if (s.dischargeCurrentLimit > 0f) s.dischargeCurrentLimit else 100f
        val chargeFrac = if (charging) (s.current / maxCharge).coerceIn(0f, 1f) else 0f
        val dischFrac = if (discharging) (-s.current / maxDischarge).coerceIn(0f, 1f) else 0f
        Box(modifier = Modifier.fillMaxWidth().height(7.dp)) {
            Box(Modifier.matchParentSize().background(c.track, RoundedCornerShape(3.5.dp)))
            // 充电填充：右端固定在中点，宽度 = 电流/充电限流（占左半）
            Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight()) {
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
            // 放电填充：左端固定在中点，宽度 = |电流|/放电限流（占右半）
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(dischFrac)
                        .background(c.red, RoundedCornerShape(0.dp, 3.5.dp, 3.5.dp, 0.dp)),
                )
            }
            // 0 点刻度：4×17dp 亮白，上下各探出 5dp
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = -(2.dp))
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
            .padding(16.dp),
    ) {
        // 电池电压 / 电池电流 两列（官方：绿点 + 大号绿色数值）
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValueCell("电池电压(V)", fmt(s.totalVoltage), Modifier.weight(1f))
            BigValueCell("电池电流(A)", fmt(s.current), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        // 「实时数据」双列区块（官方：左列/右列标签:数值 对齐）
        JkSectionTitle("实时数据")
        Card(colors = CardDefaults.cardColors(containerColor = c.card)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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
        Spacer(Modifier.height(16.dp))

        // 单体电压（三列网格，编号胶囊，01 最高红色高亮）
        JkSectionTitle("单体电压 (V)")
        CellGrid(s.cellVoltages, 24)

        Spacer(Modifier.height(16.dp))

        // 均衡线电阻（三列网格）
        JkSectionTitle("均衡线电阻 (Ω)")
        ResistanceGrid(s.cellResistances, 24)
    }
}

/** 大号数值单元格（官方：绿点 + 绿色大数值）。 */
@Composable
private fun BigValueCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalJkColors.current
    Row(
        modifier = modifier
            .background(c.card, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(JkGreen, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 12.sp, color = c.gray)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
    Spacer(Modifier.height(10.dp))
}

// 单体电压三列网格（对齐官方：编号胶囊 + 绿色数值，最高/最低红色高亮，未接显示 --）
@Composable
private fun CellGrid(values: List<Float>, total: Int) {
    val c = LocalJkColors.current
    val maxIdx = if (values.isNotEmpty()) values.indices.maxByOrNull { values[it] } ?: -1 else -1
    val minIdx = if (values.isNotEmpty()) values.indices.minByOrNull { values[it] } ?: -1 else -1
    Column {
        for (rowStart in 0 until total step 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in rowStart until (rowStart + 3).coerceAtMost(total)) {
                    val has = i < values.size
                    val v = if (has) values[i] else 0f
                    val num = String.format("%02d", i + 1)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(c.card, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 编号胶囊（深蓝底白字，官方样式）
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1F3A66), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(num, fontSize = 10.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(5.dp))
                        if (has) {
                            // 最高电压=红色、最低电压=蓝色，两种颜色区分（不能都是红色）
                            val color = when (i) {
                                maxIdx -> JkRed
                                minIdx -> JkBlue
                                else -> c.text
                            }
                            Text(
                                "%.3f".format(v),
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
            Spacer(Modifier.height(8.dp))
        }
    }
}

// 内阻三列网格（对齐官方：24 格，未接显示 0.000）
@Composable
private fun ResistanceGrid(values: List<Float>, total: Int) {
    val c = LocalJkColors.current
    Column {
        for (rowStart in 0 until total step 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in rowStart until (rowStart + 3).coerceAtMost(total)) {
                    val has = i < values.size
                    val v = if (has) values[i] else 0f
                    val num = String.format("%02d", i + 1)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(c.card, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1F3A66), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(num, fontSize = 10.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "%.3f".format(v),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (has) c.text else c.gray,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
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

// ==================== 参数设置（对齐官方 + 保留远程控制） ====================
@Composable
private fun ParamView(controller: JikongController) {
    // 订阅版本号：数据变化触发本页重组，实现实时刷新
    controller.statusVersion.value
    val s = controller.status
    val c = LocalJkColors.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("保护参数", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(4.dp))
        Text("修改参数会写入保护板（需设备密码），请谨慎操作。", fontSize = 12.sp, color = c.gray)
        Spacer(Modifier.height(12.dp))

        ParamRow("单体过压保护", s.cellFullVoltage, "V") { v ->
            launchWrite(controller) { controller.writeParam(0x01, (v * 1000).toInt()) }
        }
        ParamRow("单体欠压保护", s.cellEmptyVoltage, "V") { v ->
            launchWrite(controller) { controller.writeParam(0x02, (v * 1000).toInt()) }
        }
        ParamRow("充电过温", s.chargeOverTemp, "℃") { v ->
            launchWrite(controller) { controller.writeParam(0x03, (v * 10).toInt()) }
        }
        ParamRow("放电过温", s.dischargeOverTemp, "℃") { v ->
            launchWrite(controller) { controller.writeParam(0x04, (v * 10).toInt()) }
        }
        ParamRow("充电限流", s.chargeCurrentLimit, "A") { v ->
            launchWrite(controller) { controller.writeParam(0x05, (v * 100).toInt()) }
        }
        ParamRow("放电限流", s.dischargeCurrentLimit, "A") { v ->
            launchWrite(controller) { controller.writeParam(0x06, (v * 100).toInt()) }
        }

        Spacer(Modifier.height(16.dp))
        Text("远程控制", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = JkGreen)
        Spacer(Modifier.height(8.dp))

        ControlCard("充电 MOS", s.chargeMosOn) { on ->
            kotlinx.coroutines.GlobalScope.launch {
                try { controller.setMos(charge = true, discharge = null, on = on) }
                catch (e: Exception) { android.util.Log.w("Jikong", "充电 MOS 控制失败", e) }
            }
        }
        ControlCard("放电 MOS", s.dischargeMosOn) { on ->
            kotlinx.coroutines.GlobalScope.launch {
                try { controller.setMos(charge = null, discharge = true, on = on) }
                catch (e: Exception) { android.util.Log.w("Jikong", "放电 MOS 控制失败", e) }
            }
        }
        ControlCard("主动均衡", s.balanceOn) { on ->
            kotlinx.coroutines.GlobalScope.launch {
                try { controller.setBalance(on) }
                catch (e: Exception) { android.util.Log.w("Jikong", "均衡控制失败", e) }
            }
        }
    }
}

private fun launchWrite(controller: JikongController, block: suspend () -> Boolean) {
    kotlinx.coroutines.GlobalScope.launch {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("Jikong", "写参数失败", e)
        }
    }
}

@Composable
private fun ParamRow(label: String, value: Float, unit: String, onSave: (Float) -> Unit) {
    val c = LocalJkColors.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = c.card)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f))
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = c.text),
                )
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 13.sp, color = c.gray)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val v = text.toFloatOrNull()
                    if (v != null) {
                        onSave(v)
                        editing = false
                    }
                }) { Text("保存", color = JkBlue) }
            } else {
                Text(fmt(value) + " " + unit, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { text = value.toString(); editing = true }) { Text("修改", color = JkBlue) }
            }
        }
    }
}

@Composable
private fun ControlCard(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalJkColors.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = c.card)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(if (on) "已开启" else "已关闭", fontSize = 12.sp, color = if (on) JkGreen else c.gray)
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}

private fun fmt(v: Float): String =
    if (kotlin.math.abs(v) >= 100) "%.1f".format(v) else "%.2f".format(v)
