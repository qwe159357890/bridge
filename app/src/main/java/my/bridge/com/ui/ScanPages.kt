package my.bridge.com.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import my.bridge.com.scanner.DedupStrategy
import my.bridge.com.scanner.ScanAnalyzer
import my.bridge.com.scanner.ScanFormats
import my.bridge.com.scanner.ScanPrefs
import my.bridge.com.scanner.ScanState
import my.bridge.com.scanner.ScanStore
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

    /** 会话选择页（设置里关了「自动进入今日会话」）。 */
    CHOOSER,

    /** 扫码主界面。 */
    SCANNER,

    /** 会话记录列表（打开指定日期）。 */
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
 * v1 纯本地闭环：相机识别 → 本地按日会话存储 → CSV 导出（服务端通道下一版接入）。
 * 生命周期照智能控制纪律：进入绑定相机，onDispose 全部释放。
 */
@Composable
fun ScanPage() {
    val context = LocalContext.current
    val ui = ScanState.state

    // 首次组合时同步读档并决定初始视图（§5.5 场景 A/B）
    var view by remember {
        mutableStateOf(
            run {
                ScanStore.ensureLoaded(context)
                val today = ScanStore.todaySession()
                when {
                    today == null -> ScanView.CROSS_DAY
                    ScanPrefs.autoEnterToday(context) -> ScanView.SCANNER
                    else -> ScanView.CHOOSER
                }
            }
        )
    }
    var listDate by remember { mutableStateOf(ScanStore.todayKey()) }

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
            onCreateToday = { ScanStore.createTodaySession(context); view = ScanView.SCANNER },
            onOpenHistory = {
                listDate = ScanStore.todayKey()
                view = ScanView.SESSION_LIST
            },
            onOpenDate = { listDate = it; view = ScanView.SESSION_LIST },
        )
        ScanView.CHOOSER -> ScanChooserPage(
            todayTick = todayTick,
            onEnterToday = { view = ScanView.SCANNER },
            onOpenDate = { listDate = it; view = ScanView.SESSION_LIST },
        )
        ScanView.SCANNER -> ScanCameraPage(
            todayTick = todayTick,
            onOpenList = {
                listDate = ScanStore.todayKey()
                view = ScanView.SESSION_LIST
            },
            onOpenChooser = { view = ScanView.CHOOSER },
        )
        ScanView.SESSION_LIST -> ScanSessionListPage(
            date = listDate,
            todayTick = todayTick,
            onBack = {
                view = if (ScanStore.todaySession() != null) ScanView.SCANNER else ScanView.CROSS_DAY
            },
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
    onOpenDate: (String) -> Unit,
) {
    val ui = ScanState.state
    val today = ScanStore.todayKey()
    val history = ui.sessions.filter { it.date != today }
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
                            "今天是 ${ScanStore.displayName(today).removeSuffix(" 出库单")}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        Text("还没有今日出库单", color = ScanSub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onCreateToday,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text("创建今日出库单", fontWeight = FontWeight.SemiBold)
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
            HistorySessionList(history, onOpenDate, Modifier.weight(1f))
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }
}

// ============ 会话选择页（场景 A 关） ============

@Composable
private fun ScanChooserPage(
    todayTick: Long,
    onEnterToday: () -> Unit,
    onOpenDate: (String) -> Unit,
) {
    val ui = ScanState.state
    val today = ScanStore.todayKey()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("选择会话", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEnterToday() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ScanBrand.copy(alpha = 0.08f)),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ScanStore.displayName(today), fontWeight = FontWeight.SemiBold)
                    Text(
                        "今日会话 · ${ui.sessions.find { it.date == today }?.records?.size ?: 0} 条",
                        color = ScanSub, fontSize = 12.sp,
                    )
                }
                Text("进入 ›", color = ScanBrand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
        val history = ui.sessions.filter { it.date != today }
        if (history.isNotEmpty()) {
            Text("历史会话", color = ScanSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            HistorySessionList(history, onOpenDate, Modifier.weight(1f))
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }
}

/** 历史会话列表（只读，点击查看记录）。 */
@Composable
private fun HistorySessionList(
    history: List<my.bridge.com.scanner.ScanSession>,
    onOpenDate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(history, key = { it.date }) { session ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenDate(session.date) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${session.records.size} 条", color = ScanSub, fontSize = 12.sp)
                    }
                    Text("›", color = ScanSub, fontSize = 16.sp)
                }
            }
        }
    }
}

// ============ 扫码主界面 ============

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanCameraPage(
    todayTick: Long,
    onOpenList: () -> Unit,
    onOpenChooser: () -> Unit,
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
    // 单次模式：确认一条后暂停分析器，等用户点「继续扫描」
    var singlePaused by remember { mutableStateOf(false) }

    // 最近一次确认的结果（成功卡 / 重复提示卡共用）
    var lastValue by remember { mutableStateOf<String?>(null) }
    var lastFormat by remember { mutableStateOf<String?>(null) }
    var lastIsDuplicate by remember { mutableStateOf(false) }
    var lastAt by remember { mutableLongStateOf(0L) }

    // 「询问」弹窗
    var askValue by remember { mutableStateOf<String?>(null) }
    var askFormat by remember { mutableStateOf<String?>(null) }
    // 清空确认弹窗
    var confirmClear by remember { mutableStateOf(false) }

    // 识别处理器的最新引用：分析器在绑定时创建（捕获旧闭包会读到过期 mode），
    // 因此分析器只持有 handlerRef，每次重组由 SideEffect 刷新指向最新 lambda。
    val handlerRef = remember { mutableStateOf<(String, String, Boolean) -> Unit>({ _, _, _ -> }) }

    // 相机相关（bindKey 变化 = 重建分析器，用于设置保存后刷新码制白名单）
    var bindKey by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analyzerRef = remember { mutableStateOf<ScanAnalyzer?>(null) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "scan-analyzer") } }

    // 屏幕常亮
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzerRef.value?.release()
            analyzerRef.value = null
            analysisExecutor.shutdown()
        }
    }

    // 识别确认 → 业务处理（判重 / 入库 / 反馈）。analyzer 回调在主线程。
    val handleBarcode: (String, String, Boolean) -> Unit = { value, format, manual ->
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
                    lastAt = System.currentTimeMillis()
                    ScanFeedback.beepAndVibrate(context)
                    if (mode == ScanMode.SINGLE) {
                        analyzerRef.value?.paused = true
                        singlePaused = true
                    }
                }
            }
            is ScanStore.Decision.Duplicate -> {
                lastValue = value
                lastFormat = ScanFormats.display(if (manual) ScanFormats.MANUAL else format)
                lastIsDuplicate = true
                lastAt = System.currentTimeMillis()
                ScanFeedback.toast(context, decision.reason)
            }
            ScanStore.Decision.Ask -> {
                lastValue = value
                lastFormat = ScanFormats.display(if (manual) ScanFormats.MANUAL else format)
                lastIsDuplicate = true
                askValue = value
                askFormat = format
            }
        }
    }
    SideEffect { handlerRef.value = handleBarcode }

    val today = ScanStore.todayKey()
    val session = ui.sessions.find { it.date == today }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // 会话头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ScanStore.displayName(today), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "${mode.label}模式 · ${session?.records?.size ?: 0} 条",
                    color = ScanSub, fontSize = 12.sp,
                )
            }
            TextButton(onClick = onOpenChooser) { Text("切换", color = ScanBrand) }
            TextButton(onClick = onOpenList) { Text("记录", color = ScanBrand) }
        }
        @Suppress("UNUSED_EXPRESSION") todayTick

        Spacer(Modifier.height(8.dp))

        // 相机取景区 + 取景框
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(ScanDark),
        ) {
            if (hasCam) {
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
                DisposableEffect(hasCam, bindKey) {
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
                // 取景框遮罩（四角括号 + 激光线）
                ViewfinderOverlay(modifier = Modifier.fillMaxSize())
            } else {
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
            }
        }

        // 最近结果卡
        val showResult = lastValue != null && System.currentTimeMillis() - lastAt < 4000
        if (showResult) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (lastIsDuplicate) ScanOrangeSoft else ScanGreenSoft)
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
                        "$lastFormat · ${if (lastIsDuplicate) "重复" else "已加入会话"}",
                        fontSize = 11.sp,
                        color = if (lastIsDuplicate) ScanOrange else ScanGreen,
                    )
                }
            }
        }

        // 单次模式下等待继续
        if (mode == ScanMode.SINGLE && singlePaused) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    analyzerRef.value?.reset()
                    analyzerRef.value?.paused = false
                    singlePaused = false
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
                analyzerRef.value?.reset()
                analyzerRef.value?.paused = false
            }
            Spacer(Modifier.width(8.dp))
            ScanModeChip("连续", mode == ScanMode.CONTINUOUS, Modifier.weight(1f)) {
                mode = ScanMode.CONTINUOUS
                analyzerRef.value?.reset()
                analyzerRef.value?.paused = false
            }
            Spacer(Modifier.width(8.dp))
            ScanModeChip("手动输入", false, Modifier.weight(1f)) { showManual = true }
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
        AlertDialog(
            onDismissRequest = { showManual = false },
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
                        showManual = false
                        if (v.isNotEmpty()) handleBarcode(v, ScanFormats.MANUAL, true)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) { Text("取消") }
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
                        ScanFeedback.beepAndVibrate(context)
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
                ScanFeedback.toast(context, "扫码设置已保存")
            },
        )
    }

    // 清空确认
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空今日记录？") },
            text = { Text("将删除今日会话的全部 ${session?.records?.size ?: 0} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ScanStore.clearSession(context, ScanStore.todayKey())
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
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
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
        val left = w * 0.14f
        val right = w * 0.86f
        val top = h * 0.22f
        val bottom = h * 0.62f
        val len = 44f
        val stroke = Stroke(width = 8f, cap = StrokeCap.Round)
        val color = Color.White.copy(alpha = 0.9f)
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

// ============ 会话记录页 ============

@Composable
private fun ScanSessionListPage(
    date: String,
    todayTick: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ui = ScanState.state
    val session = ui.sessions.find { it.date == date }
    val records = session?.records ?: emptyList()
    var confirmClear by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ScanStore.displayName(date), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("共 ${records.size} 条", color = ScanSub, fontSize = 12.sp)
            }
            TextButton(
                onClick = { exportCsvAndShare(context, date) },
                enabled = records.isNotEmpty(),
            ) { Text("导出CSV", color = ScanBrand) }
            TextButton(
                onClick = { confirmClear = true },
                enabled = records.isNotEmpty(),
            ) { Text("清空", color = ScanOrange) }
        }
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("暂无记录", color = ScanSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(records, key = { it.id }) { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        Text("✓", color = ScanGreen)
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") todayTick
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空该会话？") },
            text = { Text("将删除全部 ${records.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ScanStore.clearSession(context, date)
                    confirmClear = false
                }) { Text("清空", color = ScanOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 生成 CSV 到 cache/exports 并拉起系统分享。 */
private fun exportCsvAndShare(context: android.content.Context, date: String) {
    runCatching {
        val csv = ScanStore.exportCsv(context, date) ?: return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "scan_$date.csv")
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
    var autoEnter by remember { mutableStateOf(ScanPrefs.autoEnterToday(context)) }
    var dedup by remember { mutableStateOf(ScanPrefs.dedup(context)) }
    var formats by remember { mutableStateOf(ScanPrefs.formats(context)) }
    var sound by remember { mutableStateOf(ScanPrefs.sound(context)) }
    var vibration by remember { mutableStateOf(ScanPrefs.vibration(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // 自动进入今日会话
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("打开自动进入今日会话", fontSize = 14.sp)
                        Text(
                            "会话按日自动创建；关 = 打开先停在选择页",
                            color = ScanSub, fontSize = 11.sp,
                        )
                    }
                    Switch(checked = autoEnter, onCheckedChange = { autoEnter = it })
                }
                Spacer(Modifier.height(12.dp))
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 白名单全关视为无效，兜底回全选（与 ScanPrefs.formats 一致）
                ScanPrefs.setAutoEnterToday(context, autoEnter)
                ScanPrefs.setDedup(context, dedup)
                ScanPrefs.setFormats(context, if (formats.isEmpty()) ScanFormats.ALL.toSet() else formats)
                ScanPrefs.setSound(context, sound)
                ScanPrefs.setVibration(context, vibration)
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============ 反馈（提示音 / 震动 / Toast） ============

private object ScanFeedback {

    private var lastToastAt = 0L
    private var lastToastMsg: String? = null

    fun beepAndVibrate(context: android.content.Context) {
        if (ScanPrefs.sound(context)) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { runCatching { tone.release() } }, 400,
                )
            }
        }
        if (ScanPrefs.vibration(context)) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(android.os.VibratorManager::class.java)
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
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
}
