package my.bridge.com.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import kotlin.math.PI

// 极空页配色（深色科技风，对齐 BMS 仪表盘常见视觉）
private val JkBlue = Color(0xFF1A6EE8)
private val JkGreen = Color(0xFF2E9E5B)
private val JkRed = Color(0xFFE5484D)
private val JkOrange = Color(0xFFED9A3C)
private val JkInk = Color(0xFF1F2329)
private val JkGray = Color(0xFF8A919F)
private val JkBg = Color(0xFFF5F6F8)
private val JkCard = Color(0xFFFFFFFF)

/**
 * 极空保护板页面（主页第 6 个 Tab，组网之后）。
 *
 * 复刻极空官方 APP（JK BMS）核心界面，并额外接入 Bridge 服务端：
 * - 页面内可扫描/连接 BLE，实时查看总压/电流/SOC/单体电压/温度/保护状态；
 * - 连接后状态经 WS 上报服务端，管理后台可远程查看与下发控制/写参数命令；
 * - 页面可见时周期轮询，离开释放（省电优先，照 RelaySwitchPage 约定）。
 *
 * 无真机前提下，UI 完整、协议按文档实现；真机联调以实际抓包微调协议字段。
 */
@Composable
fun JikongPage() {
    val context = LocalContext.current
    val controller = remember { JikongController.instance }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 二级 Tab：实时 / 单体 / 温度 / 保护 / 参数 / 控制
    var tab by remember { mutableIntStateOf(0) }

    // 扫描结果 / 扫描中 / 连接中
    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

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
            val result = JikongBleClient.scan(context, durationMs = 8000)
            devices = result
            scanning = false
        }
    }

    fun doConnect(dev: BleDevice) {
        connecting = true
        kotlinx.coroutines.GlobalScope.launch {
            val ok = controller.connect(context, dev.address)
            connecting = false
            if (ok) {
                Toast.makeText(context, "已连接 ${dev.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "连接失败，请靠近设备重试", Toast.LENGTH_SHORT).show()
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

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部状态栏：连接状态 + 设备名 + 断开按钮
        ConnectionBar(
            connected = controller.status.connected,
            deviceName = controller.status.deviceName,
            onDisconnect = { controller.disconnect() },
        )

        if (!controller.status.connected) {
            // 未连接：扫描/连接界面
            ScanConnectView(
                devices = devices,
                scanning = scanning,
                connecting = connecting,
                onScan = { doScan() },
                onConnect = { doConnect(it) },
            )
        } else {
            // 已连接：二级 Tab 内容
            TabRow(selectedTabIndex = tab, containerColor = JkCard) {
                listOf("实时", "单体", "温度", "保护", "参数", "控制").forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(t, fontSize = 14.sp) },
                    )
                }
            }
            when (tab) {
                0 -> RealtimeView(controller)
                1 -> CellVoltageView(controller)
                2 -> TemperatureView(controller)
                3 -> ProtectionView(controller)
                4 -> ParamView(controller)
                5 -> ControlView(controller)
            }
        }
    }
}

// ==================== 连接状态栏 ====================
@Composable
private fun ConnectionBar(connected: Boolean, deviceName: String, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(JkCard)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (connected) JkGreen else JkGray, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (connected) "已连接 · $deviceName" else "未连接",
            color = if (connected) JkGreen else JkGray,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
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
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
) {
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
            "极空保护板通常命名为 JK-xxx / JK_B2A8Sxx。扫描前请先开启蓝牙，并靠近设备（10 米内）。",
            color = JkGray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty() && !scanning) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂未扫描到设备", color = JkGray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(devices) { _, dev ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = JkCard),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !connecting) { onConnect(dev) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${dev.address} · 信号 ${dev.rssi} dBm",
                                    fontSize = 12.sp,
                                    color = JkGray,
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

// ==================== 实时总览 ====================
@Composable
private fun RealtimeView(controller: JikongController) {
    val s = controller.status
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // SOC 圆盘
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = JkCard),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SocRing(soc = s.soc)
                Spacer(Modifier.height(12.dp))
                Text("剩余电量 SOC", color = JkGray, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 总压 / 电流 / 功率 三格
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("总电压", fmt(s.totalVoltage) + " V", JkBlue, Modifier.weight(1f))
            MetricCard("电流", fmt(s.current) + " A", if (s.current >= 0) JkGreen else JkOrange, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("功率", fmt(s.power) + " W", JkInk, Modifier.weight(1f))
            MetricCard("剩余容量", fmt(s.remainCapacity) + " Ah", JkGray, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("压差", fmt(s.diffVoltage) + " V", JkRed, Modifier.weight(1f))
            MetricCard("温差", fmt(s.tempDiff) + " ℃", JkOrange, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // MOS 状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = JkCard),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                MosBadge("充电 MOS", s.chargeMosOn, Modifier.weight(1f))
                MosBadge("放电 MOS", s.dischargeMosOn, Modifier.weight(1f))
                MosBadge("均衡", s.balanceOn, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SocRing(soc: Int) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val stroke = 16.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            // 背景环
            drawArc(
                color = Color(0xFFEEF0F3),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // 前景环
            val sweep = 270f * (soc.coerceIn(0, 100) / 100f)
            drawArc(
                color = when {
                    soc > 60 -> JkGreen
                    soc > 20 -> JkBlue
                    else -> JkRed
                },
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$soc", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = JkInk)
            Text("%", fontSize = 14.sp, color = JkGray)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = JkCard)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, fontSize = 12.sp, color = JkGray)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

@Composable
private fun MosBadge(label: String, on: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(if (on) JkGreen else JkGray, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = JkGray)
        Text(if (on) "开启" else "关闭", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) JkGreen else JkGray)
    }
}

// ==================== 单体电压 ====================
@Composable
private fun CellVoltageView(controller: JikongController) {
    val s = controller.status
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("单体电压（${s.cellCount} 串）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("压差 ${fmt(s.diffVoltage)}V", fontSize = 13.sp, color = JkRed)
        }
        Spacer(Modifier.height(8.dp))
        if (s.cellVoltages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("暂无单体电压数据", color = JkGray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(s.cellVoltages) { idx, v ->
                    val isMax = idx == s.maxCellIndex
                    val isMin = idx == s.minCellIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isMax || isMin) Color(0x14E5484D) else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("第 ${idx + 1} 串", fontSize = 13.sp, color = JkGray, modifier = Modifier.width(72.dp))
                        // 电压条
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .background(Color(0xFFEEF0F3), RoundedCornerShape(4.dp)),
                        ) {
                            val frac = if (s.maxCellVoltage > 0) (v / s.maxCellVoltage).coerceIn(0f, 1f) else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(frac)
                                    .height(8.dp)
                                    .background(
                                        if (isMax) JkRed else if (isMin) JkOrange else JkBlue,
                                        RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(fmt(v) + " V", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

// ==================== 温度 ====================
@Composable
private fun TemperatureView(controller: JikongController) {
    val s = controller.status
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("温度传感器（${s.tempCount} 个）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("温差 ${fmt(s.tempDiff)}℃", fontSize = 13.sp, color = JkOrange)
        }
        Spacer(Modifier.height(8.dp))
        if (s.temperatures.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("暂无温度数据", color = JkGray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(s.temperatures) { idx, t ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = JkCard)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("T${idx + 1}", fontSize = 13.sp, color = JkGray, modifier = Modifier.width(48.dp))
                            val tempColor = when {
                                t >= 55 -> JkRed
                                t >= 45 -> JkOrange
                                else -> JkBlue
                            }
                            Text(fmt(t) + " ℃", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tempColor, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ==================== 保护状态 ====================
@Composable
private fun ProtectionView(controller: JikongController) {
    val s = controller.status
    val states = s.protectionStates
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("保护/告警状态", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (states.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("暂无保护状态数据", color = JkGray)
            }
        } else {
            states.forEach { (name, triggered) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JkCard, RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (triggered) JkRed else JkGreen, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (triggered) "触发" else "正常",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (triggered) JkRed else JkGreen,
                    )
                }
            }
        }
    }
}

// ==================== 参数 ====================
@Composable
private fun ParamView(controller: JikongController) {
    val s = controller.status
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("保护参数", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("修改参数会写入保护板（需设备密码），请谨慎操作。", fontSize = 12.sp, color = JkGray)
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
    }
}

private fun launchWrite(controller: JikongController, block: suspend () -> Boolean) {
    kotlinx.coroutines.GlobalScope.launch { block() }
}

@Composable
private fun ParamRow(label: String, value: Float, unit: String, onSave: (Float) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = JkCard)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                )
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 13.sp, color = JkGray)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val v = text.toFloatOrNull()
                    if (v != null) {
                        onSave(v)
                        editing = false
                    }
                }) { Text("保存", color = JkBlue) }
            } else {
                Text(fmt(value) + " " + unit, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { text = value.toString(); editing = true }) { Text("修改", color = JkBlue) }
            }
        }
    }
}

// ==================== 控制 ====================
@Composable
private fun ControlView(controller: JikongController) {
    val s = controller.status
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("远程控制", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("控制充放电 MOS 与均衡开关。", fontSize = 12.sp, color = JkGray)
        Spacer(Modifier.height(12.dp))

        ControlCard("充电 MOS", s.chargeMosOn) { on ->
            kotlinx.coroutines.GlobalScope.launch { controller.setMos(charge = true, discharge = null, on = on) }
        }
        ControlCard("放电 MOS", s.dischargeMosOn) { on ->
            kotlinx.coroutines.GlobalScope.launch { controller.setMos(charge = null, discharge = true, on = on) }
        }
        ControlCard("主动均衡", s.balanceOn) { on ->
            kotlinx.coroutines.GlobalScope.launch { controller.setBalance(on) }
        }
    }
}

@Composable
private fun ControlCard(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = JkCard)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(if (on) "已开启" else "已关闭", fontSize = 12.sp, color = if (on) JkGreen else JkGray)
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}

private fun fmt(v: Float): String =
    if (kotlin.math.abs(v) >= 100) "%.1f".format(v) else "%.2f".format(v)
