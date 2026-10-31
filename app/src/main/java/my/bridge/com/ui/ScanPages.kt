package my.bridge.com.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import my.bridge.com.R
import my.bridge.com.scanner.DedupStrategy
import my.bridge.com.scanner.ScanAnalyzer
import my.bridge.com.scanner.ScanFormats
import my.bridge.com.scanner.ScanPrefs
import my.bridge.com.scanner.ScanRecord
import my.bridge.com.scanner.ScanSession
import my.bridge.com.scanner.ScanState
import my.bridge.com.scanner.ScanStore
import my.bridge.com.scanner.ScanUploadClient
import my.bridge.com.scanner.key
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// 配色（与原型 ui-mockup-barcode-scanner.html 对齐）
private val ScanBrand = Color(0xFF1A6EE8)
private val ScanGreen = Color(0xFF2E9E5B)
private val ScanGreenSoft = Color(0xFFE8F6EE)
private val ScanOrange = Color(0xFFE8862E)
private val ScanOrangeSoft = Color(0xFFFDF3E7)
private val ScanDark = Color(0xFF17181C)
private val ScanSub = Color(0xFF6B7280)
private val ScanBorder = Color(0xFFE5E7EB)

/** 页面内部视图状态。 */
private enum class ScanView {
    /** 跨天提示（今天还没有会话）。 */
    CROSS_DAY,

    /** 会话管理（全部会话增删改 + 进入扫码）。 */
    CHOOSER,

    /** 扫码主界面。 */
    SCANNER,

    /** 会话记录列表（按会话 id 打开）。 */
    SESSION_LIST,
}

/** 扫描模式。 */
private enum class ScanMode(val label: String) {
    SINGLE("单次"),
    CONTINUOUS("连续"),
}

/**
 * 快递扫码页（主页第 4 个 Tab「快递扫码」，智能控制之后）。
 *
 * v1 纯本地闭环：相机识别 → 本地会话存储 → CSV 导出（服务端通道下一版接入）。
 * v0.1.95：入口设置（会话列表/直接扫码）、会话自由增删改、记录级编辑删除、
 * 同码冷却节流、相机按需启停（单次扫完/手动输入/离开页面即释放）。
 */
/**
 * scanResult 回执统一分发（v0.2.04）：扫码实时上报 / 重复补报 / 记录页单击 / 批量发送共用。
 * 映射：duplicate→「重复录入」 / miss→「取消」+可选卡片标记 / hit→TTS 地址播报。
 */
private fun dispatchScanReceipt(
    context: android.content.Context,
    receipt: ScanUploadClient.Receipt,
    onMissCard: (() -> Unit)? = null,
) {
    when (receipt.result) {
        "duplicate" -> ScanFeedback.serverDuplicate(context)
        "miss" -> {
            onMissCard?.invoke()
            ScanFeedback.miss(context)
        }
        "hit" -> ScanFeedback.speakAddress(context, receipt.receiver, receipt.town)
    }
}

@Composable
fun ScanPage() {
    val context = LocalContext.current
    val ui = ScanState.state

    // 首次组合时同步读档并决定初始视图：
    // 入口设置=会话列表 → 会话管理；否则今日有会话 → 直接扫码，无 → 跨天创建提示。
    var view by remember {
        mutableStateOf(
            run {
                ScanStore.ensureLoaded(context)
                when {
                    ScanPrefs.entryMode(context) == ScanPrefs.ENTRY_LIST -> ScanView.CHOOSER
                    ScanStore.todaySession() != null -> ScanView.SCANNER
                    else -> ScanView.CROSS_DAY
                }
            }
        )
    }
    var listSessionId by remember { mutableStateOf("") }

    // 返回键（v0.1.97）：扫码模块内任何「非入口视图」（记录列表/会话管理/跨天提示…），
    // 返回都先回到「入口设置」决定的入口视图——入口=会话列表 → 会话管理；
    // 入口=直接扫码 → 扫码页（今日无会话则跨天提示）。已在入口视图时不拦截，
    // 交给全局返回（主页 Tab 切换 / 退出提示）。
    val entryOf: () -> ScanView = {
        when {
            ScanPrefs.entryMode(context) == ScanPrefs.ENTRY_LIST -> ScanView.CHOOSER
            ScanStore.todaySession() != null -> ScanView.SCANNER
            else -> ScanView.CROSS_DAY
        }
    }
    val goToEntry = { view = entryOf() }
    BackHandler(enabled = view != entryOf()) { goToEntry() }

    // 提示音预热（SoundPool 解码异步，进页面就加载避免首扫无声）
    LaunchedEffect(Unit) { ScanFeedback.init(context) }

    // 跨天监听：页面停留期间跨过 0 点 → 下次入库自动切新会话，此处在扫码页顶部
    // 显示的会话名需要跟着变。用一个轻量定时刷新 todayKey 即可。
    var todayTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            todayTick = System.currentTimeMillis()
        }
    }

    when (view) {
        ScanView.CROSS_DAY -> ScanCrossDayPage(
            todayTick = todayTick,
            onCreateToday = { view = ScanView.SCANNER },
            onOpenHistory = {
                listSessionId = ScanStore.todaySession()?.key ?: ""
                view = ScanView.CHOOSER
            },
            onOpenSession = { listSessionId = it; view = ScanView.SESSION_LIST },
        )
        ScanView.CHOOSER -> ScanChooserPage(
            todayTick = todayTick,
            onEnterScan = { view = ScanView.SCANNER },
            onOpenSession = { listSessionId = it; view = ScanView.SESSION_LIST },
        )
        ScanView.SCANNER -> ScanCameraPage(
            todayTick = todayTick,
            onOpenManager = { view = ScanView.CHOOSER },
            onOpenRecords = { id ->
                listSessionId = id
                view = ScanView.SESSION_LIST
            },
        )
        ScanView.SESSION_LIST -> ScanSessionListPage(
            sessionId = listSessionId,
            todayTick = todayTick,
            onBack = goToEntry, // 页内「‹ 返回」按钮与系统返回键行为一致
        )
    }
    // ui 只读引用防未使用告警
    @Suppress("UNUSED_EXPRESSION")
    ui
}

// ============ 跨天提示页（场景 B） ============

@Composable
private fun ScanCrossDayPage(
    todayTick: Long,
    onCreateToday: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val ui = ScanState.state
    val today = ScanStore.todayKey()
    val history = ui.sessions.filter { it.date != today }
    var showCreate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(ScanBrand.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📅", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "今天是 ${ScanStore.defaultName(today)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        Text("还没有今日会话", color = ScanSub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text("创建今日会话", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = ScanSub,
                    ),
                ) {
                    Text("查看历史")
                }
            }
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("历史会话", color = ScanSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SessionManageRows(
                sessions = history,
                todayTag = false,
                onOpen = onOpenSession,
                modifier = Modifier.weight(1f),
            )
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }

    // 创建弹窗：默认文本「10月30日」，可编辑，确认后创建
    if (showCreate) {
        ScanTextInputDialog(
            title = "创建今日会话",
            initial = ScanStore.defaultName(today),
            confirmLabel = "创建",
            onConfirm = { name ->
                ScanStore.createSession(context, name)
                showCreate = false
                onCreateToday()
            },
            onDismiss = { showCreate = false },
        )
    }
}

// ============ 会话管理页（选择会话 + 自由增删改） ============

@Composable
private fun ScanChooserPage(
    todayTick: Long,
    onEnterScan: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val ui = ScanState.state
    val today = ScanStore.todayKey()
    var showCreate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("会话管理", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = onEnterScan,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(21.dp),
            ) {
                Text("进入扫码", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { showCreate = true },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(21.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = ScanBrand,
                ),
            ) {
                Text("＋ 新建会话", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.sessions.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有会话，点「新建会话」开始", color = ScanSub)
            }
        } else {
            SessionManageRows(
                sessions = ui.sessions,
                todayTag = true,
                onOpen = onOpenSession,
                modifier = Modifier.weight(1f),
            )
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }

    if (showCreate) {
        ScanTextInputDialog(
            title = "新建会话",
            initial = ScanStore.defaultName(today),
            confirmLabel = "创建",
            onConfirm = { name ->
                ScanStore.createSession(context, name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** 会话行列表：点击查看记录，行尾菜单（重命名 / 删除）。 */
@Composable
private fun SessionManageRows(
    sessions: List<ScanSession>,
    todayTag: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = ScanStore.todayKey()
    var renameTarget by remember { mutableStateOf<ScanSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ScanSession?>(null) }

    LazyColumn(modifier = modifier) {
        items(sessions, key = { it.key }) { session ->
            var menuOpen by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpen(session.key) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (todayTag && session.date == today) {
                                Text(
                                    "今日",
                                    fontSize = 10.sp,
                                    color = ScanBrand,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ScanBrand.copy(alpha = 0.10f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(session.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            "${session.date} · ${session.records.size} 条",
                            color = ScanSub, fontSize = 12.sp,
                        )
                    }
                    Box {
                        Text(
                            "⋮",
                            fontSize = 18.sp,
                            color = ScanSub,
                            modifier = Modifier
                                .clickable { menuOpen = true }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = { menuOpen = false; renameTarget = session },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = ScanOrange) },
                                onClick = { menuOpen = false; deleteTarget = session },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        ScanTextInputDialog(
            title = "重命名会话",
            initial = target.name,
            onConfirm = { name ->
                ScanStore.renameSession(context, target.key, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = { Text("将删除「${target.name}」及其全部 ${target.records.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ScanStore.deleteSessionById(context, target.key)
                    deleteTarget = null
                }) { Text("删除", color = ScanOrange) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 通用文本输入弹窗（创建会话 / 重命名 / 编辑单号共用）。 */
@Composable
private fun ScanTextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============ 扫码主界面 ============

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanCameraPage(
    todayTick: Long,
    onOpenManager: () -> Unit,
    onOpenRecords: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val ui = ScanState.state

    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCam = granted }
    LaunchedEffect(Unit) {
        if (!hasCam) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var mode by remember { mutableStateOf(ScanMode.CONTINUOUS) }
    var torchOn by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    // 相机启停总开关（v0.1.95）：单次扫完一条 / 打开手动输入 → 关相机省电；
    // 再点「单次/连续」chip 或「继续扫描」才重新绑定。
    var cameraActive by remember { mutableStateOf(true) }
    var singlePaused by remember { mutableStateOf(false) }
    // 手动输入弹窗打开前的相机状态，关闭时恢复
    var manualPrevActive by remember { mutableStateOf(false) }

    // 最近一次确认的结果（成功卡 / 重复提示卡共用）
    var lastValue by remember { mutableStateOf<String?>(null) }
    var lastFormat by remember { mutableStateOf<String?>(null) }
    var lastIsDuplicate by remember { mutableStateOf(false) }
    var lastAt by remember { mutableLongStateOf(0L) }

    // 结果动画（v0.2.02）：lastAt 变化即重放。flash 驱动取景框四角闪色外扩，
    // shake 驱动重复卡轻摇；中央徽章（ScanFlashBadge）内部自带时序。
    val flash = remember { Animatable(0f) }
    LaunchedEffect(lastAt) {
        if (lastAt == 0L) return@LaunchedEffect
        flash.snapTo(0f)
        flash.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
        delay(430)
        flash.animateTo(0f, tween(280))
    }
    val density = LocalDensity.current
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(lastAt) {
        if (lastAt == 0L || !lastIsDuplicate) {
            shakeX.snapTo(0f)
            return@LaunchedEffect
        }
        val amp = with(density) { 5.dp.toPx() }
        shakeX.animateTo(-amp, tween(55))
        shakeX.animateTo(amp, tween(90))
        shakeX.animateTo(-amp * 0.6f, tween(75))
        shakeX.animateTo(0f, tween(85))
    }

    // —— 上传到 PC（v0.2.02，扩展 Barcode to PC 协议）——
    // 开关/地址变化即重建客户端（DisposableEffect 负责 start/stop，跟随页面生命周期）。
    var uploadOn by remember { mutableStateOf(ScanPrefs.uploadEnabled(context)) }
    var uploadHostText by remember { mutableStateOf(ScanPrefs.uploadHost(context)) }
    var uploadState by remember { mutableStateOf(ScanUploadClient.State.IDLE) }
    // 服务器回执「未匹配」标记：命中该状态时结果卡追加提示 + 播 quxiao
    var lastServerMiss by remember { mutableStateOf(false) }
    val uploadClient = remember(uploadOn, uploadHostText) {
        if (uploadOn && ScanPrefs.uploadUrl(context) != null) {
            ScanUploadClient(
                context,
                onReceipt = { receipt ->
                    dispatchScanReceipt(context, receipt) { lastServerMiss = true }
                },
                onStateChanged = { uploadState = it },
            )
        } else {
            null
        }
    }
    DisposableEffect(uploadClient) {
        uploadClient?.start()
        onDispose { uploadClient?.stop() }
    }

    // 「询问」弹窗
    var askValue by remember { mutableStateOf<String?>(null) }
    var askFormat by remember { mutableStateOf<String?>(null) }
    // 清空确认弹窗
    var confirmClear by remember { mutableStateOf(false) }

    // 同码冷却（v0.1.95）：同一单号处理过后 N 毫秒内再次识别 → 完全静默忽略。
    // 先于去重策略执行、对所有策略生效——堵住「对准一个包裹连续弹窗/连续入库」。
    val cooldownMap = remember { HashMap<String, Long>() }

    // 识别处理器的最新引用：分析器在绑定时创建（捕获旧闭包会读到过期 mode），
    // 因此分析器只持有 handlerRef，每次重组由 SideEffect 刷新指向最新 lambda。
    val handlerRef = remember { mutableStateOf<(String, String, Boolean) -> Unit>({ _, _, _ -> }) }

    // 相机相关（bindKey 变化 = 重建分析器，用于设置保存后刷新码制白名单）
    var bindKey by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analyzerRef = remember { mutableStateOf<ScanAnalyzer?>(null) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    // v0.1.98 防崩：包装为 safeExecutor——shutdown 后相机内部线程仍可能投递帧/
    // 拍摄回调，直接 execute 会抛 RejectedExecutionException（未捕获 → 进程崩溃）。
    val analysisInner = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "scan-analyzer") } }
    val analysisExecutor = remember { my.bridge.com.util.safeExecutor(analysisInner) }

    // 屏幕常亮
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 页面级兜底释放（离开 Tab 时）：手电筒关 + 分析器释放 + 线程池关停（v0.1.95 排查项）
    DisposableEffect(Unit) {
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            analyzerRef.value?.release()
            analyzerRef.value = null
            analysisInner.shutdown()
        }
    }

    // 识别确认 → 业务处理（冷却节流 → 判重 → 入库 / 反馈）。analyzer 回调在主线程。
    val handleBarcode: (String, String, Boolean) -> Unit = handler@{ value, format, manual ->
        val now = System.currentTimeMillis()
        // —— 同码冷却窗口：静默忽略，不弹窗 / 不响音 / 不提示（手动输入豁免） ——
        if (!manual) {
            val lastHandled = cooldownMap[value]
            if (lastHandled != null && now - lastHandled < ScanPrefs.cooldownMs(context)) {
                Timber.tag("快递扫码").d("冷却窗口内忽略重复识别 %s", value)
                return@handler
            }
        }
        cooldownMap[value] = now
        if (cooldownMap.size > 64) {
            cooldownMap.entries.removeAll { now - it.value > 60_000 }
        }

        val session = ScanStore.todaySession()
        val lastRecord = session?.records?.lastOrNull()?.value
        when (val decision = ScanStore.evaluate(context, value, lastRecord)) {
            is ScanStore.Decision.Accept -> {
                val record = ScanStore.addRecord(
                    context, value,
                    if (manual) ScanFormats.MANUAL else format,
                    manual = manual,
                )
                if (record != null) {
                    lastValue = value
                    lastFormat = if (manual) ScanFormats.display(ScanFormats.MANUAL)
                    else ScanFormats.display(format)
                    lastIsDuplicate = false
                    lastAt = now
                    lastServerMiss = false
                    ScanFeedback.success(context)
                    // 逐条实时上报 PC（SalesRecordSystem）；离线/未开启时静默跳过
                    ScanStore.todaySession()?.let { uploadClient?.upload(it, record) }
                    if (mode == ScanMode.SINGLE) {
                        // 单次模式：扫完一条立即停相机（unbind 全部用例，取景框隐藏）
                        cameraActive = false
                        singlePaused = true
                    }
                }
            }
            is ScanStore.Decision.Duplicate -> {
                lastValue = value
                lastFormat = ScanFormats.display(if (manual) ScanFormats.MANUAL else format)
                lastIsDuplicate = true
                lastAt = now
                ScanFeedback.duplicate(context)
                ScanFeedback.toast(context, decision.reason)
            }
            ScanStore.Decision.Ask -> {
                lastValue = value
                lastFormat = ScanFormats.display(if (manual) ScanFormats.MANUAL else format)
                lastIsDuplicate = true
                ScanFeedback.duplicate(context)
                askValue = value
                askFormat = format
            }
        }
    }
    SideEffect { handlerRef.value = handleBarcode }

    val today = ScanStore.todayKey()
    val session = ui.sessions.find { it.date == today && it.records.isNotEmpty() }
        ?: ui.sessions.find { it.date == today }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // 会话头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    session?.name ?: ScanStore.defaultName(today),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    "${mode.label}模式 · ${session?.records?.size ?: 0} 条",
                    color = ScanSub, fontSize = 12.sp,
                )
            }
            // 上传状态徽标（v0.2.02）：仅在上传开启时显示，一眼确认 PC 57891 是否在线
            if (uploadOn) {
                val (dotColor, dotLabel) = when (uploadState) {
                    ScanUploadClient.State.ONLINE -> ScanGreen to "PC在线"
                    ScanUploadClient.State.CONNECTING -> ScanOrange to "连接中"
                    ScanUploadClient.State.OFFLINE -> Color(0xFFD64545) to "PC离线"
                    ScanUploadClient.State.IDLE -> ScanSub to "未连接"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(dotLabel, color = ScanSub, fontSize = 11.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onOpenManager) { Text("会话", color = ScanBrand) }
            TextButton(
                onClick = {
                    val id = session?.key
                    if (id != null) onOpenRecords(id) else ScanFeedback.toast(context, "今日还没有会话记录")
                },
            ) { Text("记录", color = ScanBrand) }
        }
        @Suppress("UNUSED_EXPRESSION") todayTick

        Spacer(Modifier.height(8.dp))

        // 相机取景区 + 取景框（cameraActive=false 时整体不参与组合 → 相机自动 unbind 释放）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(ScanDark),
        ) {
            if (!hasCam) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("需要相机权限才能扫码", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限")
                    }
                }
            } else if (cameraActive) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            // 供下方相机绑定 Effect 使用（factory 作用域外拿不到局部变量）
                            previewViewRef.value = this
                        }
                    },
                )
                // 相机绑定（bindKey 变化时重建分析器以应用新码制白名单）
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(bindKey) {
                    val previewView = previewViewRef.value
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    val analyzer = ScanAnalyzer(
                        formatNames = ScanPrefs.formats(context),
                        voteThreshold = 2,
                        rescueAfterMisses = 12,
                        onConfirmed = { value, format ->
                            handlerRef.value?.invoke(value, format, false)
                        },
                        captureStill = { onResult ->
                            // 高清单帧救援：用 ImageCapture 拍一张
                            val capture = captureRef.value
                            if (capture == null) {
                                onResult(null)
                            } else {
                                capture.takePicture(
                                    analysisExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bitmap = runCatching { image.toBitmap() }.getOrNull()
                                            image.close()
                                            onResult(bitmap)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Timber.tag("快递扫码").w(exception, "救援取图异常")
                                            onResult(null)
                                        }
                                    },
                                )
                            }
                        },
                    )
                    analyzerRef.value = analyzer
                    val mainExecutor = ContextCompat.getMainExecutor(context)
                    if (previewView == null) {
                        Timber.tag("快递扫码").w("PreviewView 尚未创建，跳过相机绑定")
                        onDispose { analyzer.release() }
                    } else {
                        providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysisSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    )
                                )
                                .build()
                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(analysisSelector)
                                .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )
                                .build()
                                .also { it.setAnalyzer(analysisExecutor, analyzer) }
                            val captureSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    )
                                )
                                .build()
                            val capture = ImageCapture.Builder()
                                .setResolutionSelector(captureSelector)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            captureRef.value = capture
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis, capture,
                            )
                            camera?.cameraControl?.enableTorch(torchOn)
                        }.onFailure { Timber.tag("快递扫码").e(it, "相机绑定失败") }
                    }, mainExecutor)
                    onDispose {
                        runCatching { providerFuture.get().unbindAll() }
                        camera = null
                        captureRef.value = null
                    }
                    }
                }
                // 取景框遮罩（四角括号 + 激光线；v0.2.02 加识别结果闪色外扩）
                ViewfinderOverlay(
                    modifier = Modifier.fillMaxSize(),
                    flash = flash.value,
                    duplicate = lastIsDuplicate,
                )
                // 识别结果中央徽章：成功=绿勾描画 / 重复=橙色感叹号（弹性弹出→淡出）
                ScanFlashBadge(
                    tick = lastAt,
                    isDuplicate = lastIsDuplicate,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 相机已停止（单次扫完 / 手动输入中）：不调用摄像头
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("📷", fontSize = 40.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (mode == ScanMode.SINGLE) "单次扫描完成，点「继续扫描」再次开启相机" else "相机已停止",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // 最近结果卡（v0.2.02：上滑入场替代硬出现；重复时轻摇）
        val showResult = lastValue != null && System.currentTimeMillis() - lastAt < 4000
        AnimatedVisibility(
            visible = showResult,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = shakeX.value }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (lastIsDuplicate) ScanOrangeSoft else ScanGreenSoft)
                        // v0.2.04：重复卡点击 = 不入库、补报 PC（回执处理与扫码一致）
                        .clickable {
                            val value = lastValue
                            if (lastIsDuplicate && value != null) {
                                if (uploadState == ScanUploadClient.State.ONLINE) {
                                    uploadClient?.uploadText(session, value)
                                    ScanFeedback.toast(context, "已补报 PC：$value")
                                } else {
                                    ScanFeedback.toast(context, "PC 离线或未开启上传，无法补报")
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (lastIsDuplicate) "⚠️" else "✓",
                        fontSize = 16.sp,
                        color = if (lastIsDuplicate) ScanOrange else ScanGreen,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            lastValue ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                        Text(
                            // v0.2.02：服务器回执 miss 时追加提示；v0.2.04 重复卡提示可点击补报
                            if (lastServerMiss && !lastIsDuplicate) "$lastFormat · 已加入会话 · 服务器未匹配"
                            else if (lastIsDuplicate) "$lastFormat · 重复 · 点击可补报 PC"
                            else "$lastFormat · 已加入会话",
                            fontSize = 11.sp,
                            color = if (lastIsDuplicate) ScanOrange else ScanGreen,
                        )
                    }
                }
            }
        }

        // 单次模式下等待继续
        if (mode == ScanMode.SINGLE && singlePaused) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    singlePaused = false
                    cameraActive = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(21.dp),
            ) {
                Text("继续扫描", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 底部：模式 chips + 手动输入 + 手电筒 + 设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanModeChip("单次", mode == ScanMode.SINGLE, Modifier.weight(1f)) {
                mode = ScanMode.SINGLE
                singlePaused = false
                cameraActive = true
            }
            Spacer(Modifier.width(8.dp))
            ScanModeChip("连续", mode == ScanMode.CONTINUOUS, Modifier.weight(1f)) {
                mode = ScanMode.CONTINUOUS
                singlePaused = false
                cameraActive = true
            }
            Spacer(Modifier.width(8.dp))
            ScanModeChip("手动输入", false, Modifier.weight(1f)) {
                // 手动输入不需要相机：打开弹窗前先停相机，关闭时恢复原状态
                manualPrevActive = cameraActive
                cameraActive = false
                showManual = true
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (torchOn) ScanBrand else MaterialTheme.colorScheme.surface)
                    .clickable {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🔦", fontSize = 17.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showSettings = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙️", fontSize = 17.sp)
            }
        }
    }

    // 手动输入弹窗
    if (showManual) {
        var text by remember { mutableStateOf("") }
        val closeManual = {
            showManual = false
            cameraActive = manualPrevActive
        }
        AlertDialog(
            onDismissRequest = { closeManual() },
            title = { Text("手动输入单号") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    singleLine = true,
                    label = { Text("快递单号") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = text
                        closeManual()
                        if (v.isNotEmpty()) handleBarcode(v, ScanFormats.MANUAL, true)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { closeManual() }) { Text("取消") }
            },
        )
    }

    // 「询问」去重弹窗
    askValue?.let { dupValue ->
        var rememberChoice by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askValue = null },
            title = { Text("单号已存在") },
            text = {
                Column {
                    Text(dupValue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("该单号已扫过，是否再次添加？", color = ScanSub, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Text("总是接受重复单号", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (rememberChoice) {
                            ScanPrefs.setDedup(context, DedupStrategy.ALWAYS_ACCEPT)
                            bindKey++
                        }
                        ScanStore.addRecord(
                            context, dupValue,
                            askFormat ?: ScanFormats.MANUAL, manual = false, force = true,
                        )
                        ScanFeedback.success(context)
                        askValue = null
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { askValue = null }) { Text("丢弃") }
            },
        )
    }

    // 设置弹窗
    if (showSettings) {
        ScanSettingsDialog(
            onDismiss = { showSettings = false },
            onSaved = {
                showSettings = false
                bindKey++ // 重建分析器应用新码制白名单
                // 同步上传设置（开关/地址变化 → remember key 变化 → 重建客户端）
                uploadOn = ScanPrefs.uploadEnabled(context)
                uploadHostText = ScanPrefs.uploadHost(context)
                ScanFeedback.toast(context, "扫码设置已保存")
            },
        )
    }

    // 清空今日会话确认
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空今日记录？") },
            text = { Text("将删除「${session?.name ?: ScanStore.defaultName(today)}」的全部 ${session?.records?.size ?: 0} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    session?.let { ScanStore.clearSession(context, it.key) }
                    confirmClear = false
                }) { Text("清空", color = ScanOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** ImageCapture 引用（救援取图用，扫码页持有）。 */
private val captureRef = androidx.compose.runtime.mutableStateOf<ImageCapture?>(null)

/** 模式 chip。 */
@Composable
private fun ScanModeChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (active) ScanBrand else MaterialTheme.colorScheme.surface)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.White else ScanSub,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 取景框遮罩：四角括号 + 扫描线动画。 */
@Composable
private fun ViewfinderOverlay(
    modifier: Modifier = Modifier,
    flash: Float = 0f,
    duplicate: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "laser")
    val laserY by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "laserY",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 识别确认瞬间：括号向外扩 + 闪绿（重复=橙），flash∈[0,1] 由页面级 Animatable 驱动
        val expand = 14f * flash
        val left = w * 0.14f - expand
        val right = w * 0.86f + expand
        val top = h * 0.22f - expand
        val bottom = h * 0.62f + expand
        val len = 44f
        val stroke = Stroke(width = 8f, cap = StrokeCap.Round)
        val base = Color.White.copy(alpha = 0.9f)
        val flashColor = if (duplicate) ScanOrange else ScanGreen
        val color = if (flash > 0f) lerp(base, flashColor, flash) else base
        // 四角括号
        fun corner(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(x2, y2), Offset(x3, y3), stroke.width, StrokeCap.Round)
        }
        corner(left, top + len, left, top, left + len, top)
        corner(right - len, top, right, top, right, top + len)
        corner(right, bottom - len, right, bottom, right - len, bottom)
        corner(left + len, bottom, left, bottom, left, bottom - len)
        // 激光线
        val y = top + (bottom - top) * laserY
        drawLine(
            ScanGreen,
            Offset(left + 8f, y),
            Offset(right - 8f, y),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 识别结果中央徽章（v0.2.02）：[tick] 变化即重放一次性动画。
 * 成功 = 绿色圆 + 白勾描画（PathMeasure 逐段画出）；重复 = 橙色圆 + 白色感叹号。
 * 时序：弹出（scale 0.3→1.15→1.0，带 overshoot）→ 停留 → 淡出，全程约 0.9s。
 */
@Composable
private fun ScanFlashBadge(
    tick: Long,
    isDuplicate: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0L) return@LaunchedEffect
        scale.snapTo(0.3f)
        alpha.snapTo(0f)
        progress.snapTo(0f)
        alpha.animateTo(1f, tween(60))
        scale.animateTo(1.15f, tween(130, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(90, easing = FastOutSlowInEasing))
        progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        delay(340)
        alpha.animateTo(0f, tween(220))
    }
    if (alpha.value > 0.01f) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                    },
            ) {
                val d = size.minDimension
                drawCircle(if (isDuplicate) ScanOrange else ScanGreen, radius = d / 2f)
                val stroke = Stroke(width = d * 0.085f, cap = StrokeCap.Round)
                if (isDuplicate) {
                    // 感叹号：竖条 + 圆点
                    val cx = size.width / 2f
                    drawLine(
                        Color.White,
                        Offset(cx, d * 0.26f),
                        Offset(cx, d * 0.58f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(Color.White, radius = stroke.width * 0.85f, center = Offset(cx, d * 0.76f))
                } else {
                    // 对勾描画：PathMeasure 按进度截取片段
                    val path = Path().apply {
                        moveTo(size.width * 0.27f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.74f, size.height * 0.33f)
                    }
                    val pm = PathMeasure()
                    pm.setPath(path, false)
                    val seg = Path()
                    pm.getSegment(0f, pm.length * progress.value, seg, true)
                    drawPath(seg, Color.White, style = stroke)
                }
            }
        }
    }
}

// ============ 会话记录页（记录级管理：复制 / 编辑 / 删除 / 上报 / 多选批量发送） ============

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanSessionListPage(
    sessionId: String,
    todayTick: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ui = ScanState.state
    val session = ui.sessions.find { it.key == sessionId }
    val records = session?.records ?: emptyList()
    var headerMenu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ScanRecord?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    // —— 上报到 PC（v0.2.04）：单击上报 + 多选批量发送，回执处理与扫码页同构 ——
    var uploadState by remember { mutableStateOf(ScanUploadClient.State.IDLE) }
    val uploadClient = remember {
        ScanUploadClient(
            context,
            onReceipt = { receipt ->
                dispatchScanReceipt(context, receipt) {
                    ScanFeedback.toast(context, "服务器未匹配：${receipt.text}")
                }
            },
            onStateChanged = { uploadState = it },
        )
    }
    DisposableEffect(uploadClient) {
        uploadClient.start()
        onDispose { uploadClient.stop() }
    }

    // —— 多选模式（v0.2.04）：长按进入 → 勾选 → 全选/全取消 → 全部发送 ——
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    // 多选模式下返回键 = 退出多选（不退出页面）
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selected.clear()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    session?.name ?: "会话不存在",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                Text(
                    session?.let { "${it.date} · 共 ${it.records.size} 条" } ?: "",
                    color = ScanSub, fontSize = 12.sp,
                )
            }
            TextButton(
                onClick = { session?.let { exportCsvAndShare(context, it.key) } },
                enabled = records.isNotEmpty(),
            ) { Text("导出CSV", color = ScanBrand) }
            Box {
                Text(
                    "⋮",
                    fontSize = 18.sp,
                    color = ScanSub,
                    modifier = Modifier
                        .clickable { headerMenu = true }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名会话") },
                        onClick = { headerMenu = false; renameOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("清空记录", color = ScanOrange) },
                        onClick = { headerMenu = false; confirmClear = true },
                        enabled = records.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("删除会话", color = ScanOrange) },
                        onClick = { headerMenu = false; deleteOpen = true },
                    )
                }
            }
        }
        // 多选操作行（v0.2.04）：全选/全取消切换 + 全部发送 + 退出
        if (selectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (selected.size == records.size) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(records.map { it.id })
                    }
                }) {
                    Text(
                        if (selected.size == records.size) "全取消" else "全选",
                        color = ScanBrand,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (uploadState == ScanUploadClient.State.ONLINE) {
                            val targets = records.filter { it.id in selected }
                            targets.forEach { uploadClient.uploadText(session, it.value) }
                            ScanFeedback.toast(context, "已上报 ${targets.size} 条到 PC")
                        } else {
                            ScanFeedback.toast(context, "PC 离线或未开启上传，无法发送")
                        }
                        selectionMode = false
                        selected.clear()
                    },
                    enabled = selected.isNotEmpty(),
                ) { Text("全部发送(${selected.size})", color = ScanBrand) }
                TextButton(onClick = {
                    selectionMode = false
                    selected.clear()
                }) { Text("退出多选", color = ScanSub) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (session == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("会话不存在或已删除", color = ScanSub)
            }
        } else if (records.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("暂无记录", color = ScanSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(records, key = { it.id }) { record ->
                    var menuOpen by remember { mutableStateOf(false) }
                    val isSelected = record.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v0.2.04：单击=上报 PC（多选模式=切换勾选）；长按=进入多选
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selected.remove(record.id)
                                        else selected.add(record.id)
                                    } else if (uploadState == ScanUploadClient.State.ONLINE) {
                                        uploadClient.uploadText(session, record.value)
                                        ScanFeedback.toast(context, "已上报：${record.value}")
                                    } else {
                                        ScanFeedback.toast(context, "PC 离线或未开启上传，无法上报")
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selected.clear()
                                        selected.add(record.id)
                                    }
                                },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(record.id)
                                    else selected.remove(record.id)
                                },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.value,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            Text(
                                "${ScanFormats.display(record.format)} · ${timeFmt.format(Date(record.timestamp))}" +
                                    if (record.manual) " · 手动输入" else "",
                                color = ScanSub, fontSize = 11.sp,
                            )
                        }
                        if (selectionMode) {
                            Text(
                                if (isSelected) "已选" else "未选",
                                color = if (isSelected) ScanBrand else ScanSub,
                                fontSize = 11.sp,
                            )
                        } else {
                            Text("✓", color = ScanGreen, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Box {
                                Text(
                                    "⋮",
                                    fontSize = 16.sp,
                                    color = ScanSub,
                                    modifier = Modifier
                                        .clickable { menuOpen = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("复制单号") },
                                        onClick = {
                                            menuOpen = false
                                            clipboard?.setPrimaryClip(
                                                ClipData.newPlainText("单号", record.value)
                                            )
                                            ScanFeedback.toast(context, "已复制单号")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("上报 PC") },
                                        onClick = {
                                            menuOpen = false
                                            if (uploadState == ScanUploadClient.State.ONLINE) {
                                                uploadClient.uploadText(session, record.value)
                                                ScanFeedback.toast(context, "已上报：${record.value}")
                                            } else {
                                                ScanFeedback.toast(context, "PC 离线或未开启上传，无法上报")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("编辑") },
                                        onClick = { menuOpen = false; editTarget = record },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除", color = ScanOrange) },
                                        onClick = {
                                            menuOpen = false
                                            ScanStore.deleteRecord(context, sessionId, record.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }

    // 重命名会话
    if (renameOpen && session != null) {
        ScanTextInputDialog(
            title = "重命名会话",
            initial = session.name,
            onConfirm = { name ->
                ScanStore.renameSession(context, sessionId, name)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }
    // 编辑单号
    editTarget?.let { record ->
        ScanTextInputDialog(
            title = "编辑单号",
            initial = record.value,
            onConfirm = { newValue ->
                ScanStore.updateRecord(context, sessionId, record.id, newValue)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
    // 删除会话确认
    if (deleteOpen && session != null) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除会话？") },
            text = { Text("将删除「${session.name}」及其全部 ${session.records.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ScanStore.deleteSessionById(context, sessionId)
                    deleteOpen = false
                    onBack()
                }) { Text("删除", color = ScanOrange) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
        )
    }
    // 清空确认
    if (confirmClear && session != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空该会话？") },
            text = { Text("将删除全部 ${records.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ScanStore.clearSession(context, sessionId)
                    confirmClear = false
                }) { Text("清空", color = ScanOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 生成 CSV 到 cache/exports 并拉起系统分享。 */
private fun exportCsvAndShare(context: android.content.Context, id: String) {
    runCatching {
        val csv = ScanStore.exportCsv(context, id) ?: return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "scan_$id.csv")
        file.writeText(csv)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出扫码记录"))
        Timber.tag("快递扫码").i("导出 CSV %s（%d 字节）", file.name, file.length())
    }.onFailure { Timber.tag("快递扫码").e(it, "导出 CSV 失败") }
}

// ============ 设置弹窗 ============

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var entryMode by remember { mutableStateOf(ScanPrefs.entryMode(context)) }
    var cooldownMs by remember { mutableStateOf(ScanPrefs.cooldownMs(context)) }
    var dedup by remember { mutableStateOf(ScanPrefs.dedup(context)) }
    var formats by remember { mutableStateOf(ScanPrefs.formats(context)) }
    var sound by remember { mutableStateOf(ScanPrefs.sound(context)) }
    var vibration by remember { mutableStateOf(ScanPrefs.vibration(context)) }
    var uploadOn by remember { mutableStateOf(ScanPrefs.uploadEnabled(context)) }
    var uploadHost by remember { mutableStateOf(ScanPrefs.uploadHost(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // 进入入口
                Text("进入快递扫码页时", color = ScanSub, fontSize = 13.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { entryMode = ScanPrefs.ENTRY_SCAN },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = entryMode == ScanPrefs.ENTRY_SCAN,
                        onClick = { entryMode = ScanPrefs.ENTRY_SCAN },
                    )
                    Text("直接进入扫码页", fontSize = 13.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { entryMode = ScanPrefs.ENTRY_LIST },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = entryMode == ScanPrefs.ENTRY_LIST,
                        onClick = { entryMode = ScanPrefs.ENTRY_LIST },
                    )
                    Text("进入会话列表（手动选）", fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                // 同码冷却
                Text("同码冷却（同一单号静默忽略窗口）", color = ScanSub, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1000L, 2000L, 2500L, 3000L, 5000L).forEach { ms ->
                        val active = cooldownMs == ms
                        Text(
                            text = if (active) "${ms / 1000.0}s ✓" else "${ms / 1000.0}s",
                            fontSize = 12.sp,
                            color = if (active) ScanBrand else ScanSub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (active) ScanBrand.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { cooldownMs = ms }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Text(
                    "对准同一包裹时摄像头会反复识别；冷却窗口内同码完全静默，防止连续弹窗/连续入库",
                    color = ScanSub, fontSize = 10.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("重复单号策略", color = ScanSub, fontSize = 13.sp)
                DedupStrategy.entries.forEach { strategy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dedup = strategy },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = dedup == strategy, onClick = { dedup = strategy })
                        Text(strategy.label, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("格式白名单（码制）", color = ScanSub, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanFormats.ALL.forEach { format ->
                        val active = format in formats
                        Text(
                            text = if (active) "$format ✓" else format,
                            fontSize = 11.sp,
                            color = if (active) ScanBrand else ScanSub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (active) ScanBrand.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable {
                                    formats = if (active) formats - format else formats + format
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提示音", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = sound, onCheckedChange = { sound = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("震动反馈", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vibration, onCheckedChange = { vibration = it })
                }
                Spacer(Modifier.height(10.dp))
                // —— 上传到 PC（v0.2.02，扩展 Barcode to PC 协议）——
                Text("上传到 PC（销售记录系统）", color = ScanSub, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("扫码后实时上报 PC 端签收", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = uploadOn, onCheckedChange = { uploadOn = it })
                }
                if (uploadOn) {
                    OutlinedTextField(
                        value = uploadHost,
                        onValueChange = { uploadHost = it },
                        label = { Text("PC 地址") },
                        placeholder = { Text("192.168.31.5:57891") },
                        supportingText = { Text("PC 运行销售记录系统（端口默认 57891），手机与 PC 需同一局域网") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 白名单全关视为无效，兜底回全选（与 ScanPrefs.formats 一致）
                ScanPrefs.setEntryMode(context, entryMode)
                ScanPrefs.setCooldownMs(context, cooldownMs)
                ScanPrefs.setDedup(context, dedup)
                ScanPrefs.setFormats(context, if (formats.isEmpty()) ScanFormats.ALL.toSet() else formats)
                ScanPrefs.setSound(context, sound)
                ScanPrefs.setVibration(context, vibration)
                ScanPrefs.setUploadEnabled(context, uploadOn)
                ScanPrefs.setUploadHost(context, uploadHost)
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============ 反馈（提示音 / 震动 / Toast） ============

/**
 * 提示音/震动/TTS 反馈（v0.1.95 重做感知，v0.2.03 音效映射细化）。
 *
 * 成熟扫码软件惯例（ZXing BeepManager / 工业扫描枪）：
 * - 成功音 = 短促高频音效，重复/警示音 = 明显不同的警示音，播放音量拉满；
 * - 震动 200~300ms。
 * 音效文件（原始文件在 assets/sounds/，打包进 res/raw/）：
 * - res/raw/scan_ok.wav  =「扫描咻.wav」（扫码成功）
 * - res/raw/warning.wav  =「warning.wav」（本地扫码重复，v0.2.03 由重复录入.wav 换来）
 * - res/raw/scan_dup.wav =「重复录入.wav」（服务器 scanResult{duplicate} 回执，v0.2.03 起改由回执触发）
 * - res/raw/scan_miss.wav=「取消.wav」（服务器 scanResult{miss} 回执）
 * hit 回执走 TTS 播报「receiver town」（与 PC 端 text2speech.play 文案一致）。
 */
private object ScanFeedback {

    private var soundPool: SoundPool? = null
    private var okId = 0
    private var dupId = 0
    private var missId = 0
    private var warnId = 0

    // TTS（v0.2.03）：hit 回执地址播报。init 期异步预热，避免首次播报无声。
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    /** 页面进入时预热（SoundPool 解码异步，提前加载避免首扫无声）。 */
    fun init(context: android.content.Context) {
        // TTS 只初始化一次（页面可能反复进出）
        if (tts == null) {
            runCatching {
                tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                    ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                    if (ttsReady) {
                        runCatching { tts?.language = java.util.Locale.CHINA }
                    }
                }
            }.onFailure { Timber.tag("快递扫码").e(it, "TTS 初始化失败") }
        }
        if (soundPool != null) return
        runCatching {
            // v0.1.96：走 USAGE_MEDIA（媒体通道）——SONIFICATION/通知通道系统音量通常
            // 偏低且可能被勿扰模式静音；媒体通道音量一般最高。音源本身已 0dBFS 满幅、
            // 播放音量 1.0，应用侧全部拉满（绝对上限=系统媒体音量，App 不能越过）。
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
            okId = soundPool?.load(context, R.raw.scan_ok, 1) ?: 0
            dupId = soundPool?.load(context, R.raw.scan_dup, 1) ?: 0
            missId = soundPool?.load(context, R.raw.scan_miss, 1) ?: 0
            warnId = soundPool?.load(context, R.raw.warning, 1) ?: 0
        }.onFailure { Timber.tag("快递扫码").e(it, "提示音初始化失败") }
    }

    private fun play(id: Int) {
        val pool = soundPool ?: return
        if (id != 0) runCatching { pool.play(id, 1f, 1f, 1, 0, 1f) }
    }

    /** 扫码成功：高频短 beep + 长震动。 */
    fun success(context: android.content.Context) {
        if (ScanPrefs.sound(context)) play(okId)
        if (ScanPrefs.vibration(context)) vibrate(context, 250)
    }

    /** 本地扫码重复/警示（v0.2.03 换音）：warning.wav 两短警示响。 */
    fun duplicate(context: android.content.Context) {
        if (ScanPrefs.sound(context)) play(warnId)
    }

    /**
     * 服务器重复录入（v0.2.03）：PC 端 scanResult{duplicate} 回执触发。
     * 音源 = SalesRecordSystem 的「重复录入.wav」（chongfuluru）同源。
     */
    fun serverDuplicate(context: android.content.Context) {
        if (ScanPrefs.sound(context)) play(dupId)
    }

    /**
     * 服务器未匹配（v0.2.02）：PC 端 scanResult{miss} 回执触发。
     * 音源 = SalesRecordSystem 的「取消.wav」（quxiao）同源。
     */
    fun miss(context: android.content.Context) {
        if (ScanPrefs.sound(context)) play(missId)
    }

    /**
     * 服务器命中地址播报（v0.2.03）：PC 端 scanResult{hit} 回执触发。
     * 文案与 PC 端一致：`"{receiver} {town}"`（text2speech.play 同款）。
     * QUEUE_FLUSH 打断上一条，连续扫码时只播最新地址。跟随「提示音」开关。
     */
    fun speakAddress(context: android.content.Context, receiver: String, town: String) {
        if (!ScanPrefs.sound(context)) return
        if (!ttsReady) return
        val text = "$receiver $town".trim()
        if (text.isEmpty()) return
        runCatching {
            tts?.speak(
                text,
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                "scan_hit",
            )
        }.onFailure { Timber.tag("快递扫码").e(it, "地址播报失败") }
    }

    private fun vibrate(context: android.content.Context, ms: Long) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun toast(context: android.content.Context, message: String) {
        // 相同文案 3 秒内只弹一次（连续模式对准重复码时防刷屏）
        val now = System.currentTimeMillis()
        if (message == lastToastMsg && now - lastToastAt < 3000) return
        lastToastMsg = message
        lastToastAt = now
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var lastToastAt = 0L
    private var lastToastMsg: String? = null
}
