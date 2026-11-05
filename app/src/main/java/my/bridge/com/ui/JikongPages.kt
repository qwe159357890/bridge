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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

// 极空页配色（对齐官方 APP：纯黑背景 + 荧光绿数字）
private val JkBlue = Color(0xFF1A6EE8)
private val JkGreen = Color(0xFF00E050)   // 官方荧光绿数字
private val JkRed = Color(0xFFE5484D)

// 深色主题配色（对齐官方：纯黑背景 + 深灰卡片 + 荧光绿）
private val DarkBg = Color(0xFF000000)
private val DarkCard = Color(0xFF1A1C1E)
private val DarkText = Color(0xFFE8EAED)
private val DarkGray = Color(0xFF8A919F)
private val DarkDivider = Color(0xFF2A2C30)

// 浅色主题配色
private val LightBg = Color(0xFFF5F6F8)
private val LightCard = Color(0xFFFFFFFF)
private val LightText = Color(0xFF1F2329)
private val LightGray = Color(0xFF8A919F)
private val LightDivider = Color(0xFFEEF0F3)

/** 主题配色（按 isDark 二选一）。 */
private data class JkColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val gray: Color,
    val divider: Color,
)

private val DarkColors = JkColors(DarkBg, DarkCard, DarkText, DarkGray, DarkDivider)
private val LightColors = JkColors(LightBg, LightCard, LightText, LightGray, LightDivider)

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

    // 主 Tab：首页 / 实时状态 / 参数设置（对齐官方）
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
                        0 -> HomeView(controller)
                        1 -> RealtimeView(controller)
                        2 -> ParamView(controller)
                    }
                }
                // 底部导航栏
                BottomNavBar(tab) { tab = it }
            }
        }
    }
}

// ==================== 底部导航栏 ====================
@Composable
private fun BottomNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card)
            .padding(vertical = 6.dp),
    ) {
        listOf("首页", "实时状态", "参数设置").forEachIndexed { i, label ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected == i) JkGreen else c.gray,
                )
            }
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

// ==================== 首页（仪表盘，对齐官方 JK BMS） ====================
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 三模式状态（充电/放电/均衡，竖线分隔，官方样式）
        StatusRow(s)
        Spacer(Modifier.height(16.dp))

        // 中央仪表盘（SOC + 电压电流胶囊 + 刻度）
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SocRing(s.soc, s.totalVoltage, s.current)
        }
        Spacer(Modifier.height(14.dp))

        // 状态提示条（盾牌图标 + 电池运行良好）
        HealthBanner()
        Spacer(Modifier.height(12.dp))

        // 数据网格一：最高电压 / 最低电压 / 最大压差 / 均衡电流（官方 4 列竖线分隔）
        QuadGrid(
            listOf(
                QuadCell("最高电压(V)", fmt(s.maxCellVoltage), JkBlue),
                QuadCell("最低电压(V)", fmt(s.minCellVoltage), JkRed),
                QuadCell("最大压差(V)", fmt(s.diffVoltage), JkGreen),
                QuadCell("均衡电流(A)", fmt(s.balanceCurrent), JkGreen),
            )
        )
        Spacer(Modifier.height(12.dp))

        // 数据网格二：电池容量 / 剩余容量 / 最高温度 / 电池类型
        QuadGrid(
            listOf(
                QuadCell("电池容量(Ah)", fmt(s.nominalCapacity), JkGreen),
                QuadCell("剩余容量(Ah)", fmt(s.remainCapacity), JkGreen),
                QuadCell("最高温度(℃)", fmt(s.maxTemperature), JkGreen),
                QuadCell("电池类型", if (s.batteryType.isBlank()) "--" else s.batteryType, JkGreen),
            )
        )
        Spacer(Modifier.height(12.dp))

        // 电流 / 功率 + 滑动条 + 状态 / 剩余时间
        CurrentPowerCard(s)
        Spacer(Modifier.height(12.dp))
    }
}

/** 首页三分栏状态行（充电/放电/均衡）。 */
@Composable
private fun StatusRow(s: my.bridge.com.jikong.JikongStatus) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(10.dp))
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCell("充电", s.chargeMosOn, Modifier.weight(1f))
        DividerV()
        StatusCell("放电", s.dischargeMosOn, Modifier.weight(1f))
        DividerV()
        StatusCell("均衡", s.balanceOn, Modifier.weight(1f))
    }
}

@Composable
private fun DividerV() {
    val c = LocalJkColors.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(c.divider),
    )
}

@Composable
private fun StatusCell(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val c = LocalJkColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(9.dp).background(if (on) JkGreen else JkRed, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            if (on) "开启" else "关闭",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (on) JkGreen else JkRed,
        )
    }
}

/** 盾牌状态横幅（电池运行良好）。 */
@Composable
private fun HealthBanner() {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B2A2A), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 盾牌图标（含对勾）
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                // 简化盾牌形状
                drawPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.08f)
                        lineTo(size.width * 0.92f, size.height * 0.22f)
                        lineTo(size.width * 0.92f, size.height * 0.55f)
                        quadraticBezierTo(size.width * 0.92f, size.height * 0.8f, size.width * 0.5f, size.height * 0.95f)
                        quadraticBezierTo(size.width * 0.08f, size.height * 0.8f, size.width * 0.08f, size.height * 0.55f)
                        lineTo(size.width * 0.08f, size.height * 0.22f)
                        close()
                    },
                    color = JkGreen,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("电池运行良好", color = Color(0xFFE8F5EC), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 官方四列网格卡片（竖线分隔）。 */
@Composable
private fun QuadGrid(cells: List<QuadCell>) {
    val c = LocalJkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(10.dp))
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) DividerV()
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(cell.value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cell.accent)
                Spacer(Modifier.height(4.dp))
                Text(cell.label, fontSize = 10.sp, color = c.gray, textAlign = TextAlign.Center)
            }
        }
    }
}

private data class QuadCell(val label: String, val value: String, val accent: Color)

@Composable
private fun SocRing(soc: Int, voltage: Float, current: Float) {
    val c = LocalJkColors.current
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(210.dp)) {
            val stroke = 20.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            // 背景环（深灰）
            drawArc(
                color = c.divider,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // 前景环（绿→青渐变）
            val sweep = 270f * (soc.coerceIn(0, 100) / 100f)
            drawArc(
                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    0f to JkGreen,
                    1f to Color(0xFF00D4FF),
                ),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$soc", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = JkGreen)
            Text("%", fontSize = 18.sp, color = JkGreen)
            Spacer(Modifier.height(10.dp))
            // 电压电流圆角胶囊
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fmt(voltage) + "V",
                    fontSize = 13.sp,
                    color = c.text,
                    modifier = Modifier
                        .background(c.card, RoundedCornerShape(20.dp))
                        .border(1.dp, JkGreen, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Text(
                    fmt(current) + "A",
                    fontSize = 13.sp,
                    color = c.text,
                    modifier = Modifier
                        .background(c.card, RoundedCornerShape(20.dp))
                        .border(1.dp, JkGreen, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 电流/功率卡片（含滑动条 + 状态 + 剩余时间）。 */
@Composable
private fun CurrentPowerCard(s: my.bridge.com.jikong.JikongStatus) {
    val c = LocalJkColors.current
    Card(colors = CardDefaults.cardColors(containerColor = c.card)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // 电流 + 功率
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("电流(A)", fontSize = 12.sp, color = c.gray)
                    Spacer(Modifier.height(3.dp))
                    Text(fmt(s.current), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = JkGreen)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("功率(W)", fontSize = 12.sp, color = c.gray)
                    Spacer(Modifier.height(3.dp))
                    Text(fmt(s.power), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = JkGreen)
                }
            }
            Spacer(Modifier.height(14.dp))
            // 水平滑动条（电流指示）
            val ratio = (s.current.coerceIn(-100f, 100f) / 100f).let { (it + 1f) / 2f }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(c.divider, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(JkBlue, RoundedCornerShape(4.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            // 状态 + 剩余时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状态：", fontSize = 13.sp, color = c.gray)
                Text(
                    if (s.current > 0.1f) "充电" else if (s.current < -0.1f) "放电" else "静止",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = JkGreen,
                )
                Spacer(Modifier.weight(1f))
                Text("剩余时间：", fontSize = 13.sp, color = c.gray)
                Text(
                    if (s.remainTimeSeconds > 0) formatDuration(s.remainTimeSeconds) else "--",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = JkGreen,
                )
            }
        }
    }
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
                    DetailRow("电池功率", fmt(s.power) + "W")
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
                    val isExtreme = has && (i == maxIdx || i == minIdx)
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
                            Text(
                                "%.3f".format(v),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isExtreme) JkRed else c.text,
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
