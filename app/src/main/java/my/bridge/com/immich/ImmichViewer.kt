package my.bridge.com.immich

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.foundation.layout.fillMaxSize as fillMaxSize1

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/** 查看器动作行单项数据（图标 + 文案）。 */
private data class ViewerAction(
    val label: String,
    val icon: ImageVector,
)

/**
 * Immich 大图查看器：黑底全屏 + 左右翻页 + 顶部信息栏 + 底部渐变动作行 + EXIF 详情面板。
 *
 * 复刻要点（asset_viewer/bottom_bar.widget.dart / 原型 .viewer）：
 * - HorizontalPager 左右翻页（与原版 swipe 手势一致）；
 * - 图片：Coil 加载 size=preview 高清预览图（jpeg），双指缩放 1~5 倍 + 双击复位；
 * - 视频：Media3 ExoPlayer 直连 video/playback（无 HLS 路由，实测统一该端点），
 *   请求头带 x-immich-user-token（DefaultHttpDataSource）；
 * - 顶栏：‹ 返回 + 拍摄时间 + ♡ 收藏 + ⓘ 信息 + ⋮ 更多；
 * - 底部动作行：分享 / 下载 / 收藏 / 删除（原图下载进缓存后走系统分享/保存）；
 * - ⓘ 打开 EXIF 详情面板（自底部滑入，深色圆角面板，含真实人物区）。
 *
 * :param vm: 相册视图模型（收藏/删除/详情拉取）
 * :param assets: 查看器资产列表（当前网格上下文）
 * :param startIndex: 起始页索引
 * :param onClose: 关闭查看器回调
 * :param onOpenPerson: 点击人物回调（关查看器开人物照片页）
 */
@Composable
fun ImmichViewer(
    vm: ImmichViewModel,
    assets: List<ImmichAsset>,
    startIndex: Int,
    onClose: () -> Unit,
    onOpenPerson: (ImmichPerson) -> Unit,
) {
    val p = LocalImmichPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 翻页状态
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0))) { assets.size }
    // 当前页资产
    val currentAsset by remember { derivedStateOf { assets.getOrNull(pagerState.currentPage) } }
    // 收藏态覆盖表（vm.toggleFavorite 后即时反映）
    val favoriteOverrides by vm.favoriteOverrides.collectAsState()
    // EXIF 详情面板显隐 + 当前资产详情缓存
    var detailOpen by remember { mutableStateOf(false) }
    var assetDetail by remember { mutableStateOf<ImmichAsset?>(null) }
    // 翻页时重置面板与详情缓存（本地资产无服务端详情，跳过拉取）
    LaunchedEffect(pagerState.currentPage) {
        detailOpen = false
        assetDetail = null
        currentAsset?.let { asset ->
            if (!asset.isLocal) {
                vm.loadAssetDetail(context, asset.id) { loaded -> assetDetail = loaded }
            }
        }
    }
    // 返回键：优先收起详情面板，再关闭查看器
    BackHandler {
        if (detailOpen) {
            detailOpen = false
        } else {
            onClose()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 大图翻页区
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { assets[it].id },
        ) { page ->
            val asset = assets[page]
            // 本地未上传资产：图片直读 contentUri；视频 ExoPlayer 播本地 Uri（无令牌/无网络）
            if (asset.isLocal) {
                if (asset.isVideo) ImmichLocalVideoPlayer(asset = asset) else ImmichPreviewImage(asset = asset)
            } else {
                if (asset.isVideo) ImmichVideoPlayer(asset = asset) else ImmichPreviewImage(asset = asset)
            }
        }
        // 顶栏：返回 + 时间 + 收藏 + 信息 + 更多（原型 .v-top）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "关闭查看器",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onClose() }
                    .padding(8.dp),
            )
            Text(
                currentAsset?.let { ImmichTimeline.dateText(it) } ?: "",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            // 本地资产无收藏/更多（服务端专属动作），时间行前带「未上传」标记
            if (currentAsset?.isLocal == true) {
                Text(
                    "未上传",
                    color = Color(0xFFFFC107),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Color(0x33FFC107), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            } else {
                val favOn = (favoriteOverrides[currentAsset?.id] ?: currentAsset?.isFavorite) ?: false
                Icon(
                    if (favOn) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (favOn) p.heart else Color.White,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            currentAsset?.let { asset ->
                                vm.toggleFavorite(context, asset.id, !favOn)
                                DiagLog.i(NTAG, "查看器收藏切换：${asset.fileName} → ${!favOn}")
                            }
                        }
                        .padding(8.dp),
                )
            }
            Icon(
                Icons.Filled.Info,
                contentDescription = "详细信息",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { detailOpen = !detailOpen }
                    .padding(8.dp),
            )
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { DiagLog.i(NTAG, "查看器更多菜单（只读复刻暂占位）") }
                    .padding(8.dp),
            )
        }
        // 底部区域：EXIF 面板（自底部滑入）+ 渐变动作行
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // EXIF 详情面板（原型 .detail-panel）
            androidx.compose.animation.AnimatedVisibility(
                visible = detailOpen,
                enter = androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.slideOutVertically { it },
            ) {
                ImmichExifPanel(
                    listAsset = currentAsset,
                    detail = assetDetail,
                    onOpenPerson = onOpenPerson,
                )
            }
            // 底部渐变 + 动作行 + 文件名行（原型 .v-bottom）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x99000000), Color(0xB3000000)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 18.dp),
            ) {
                // 动作行：本地资产 = 分享 + 上传；云端资产 = 分享 / 下载 / 收藏 / 删除（spaceAround 均分）
                val favOn2 = (favoriteOverrides[currentAsset?.id] ?: currentAsset?.isFavorite) ?: false
                if (currentAsset?.isLocal == true) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ImmichViewerAction(ViewerAction("分享", Icons.Filled.Share), Modifier.weight(1f)) {
                            currentAsset?.let { asset ->
                                scope.launch { shareAsset(context, vm, asset) }
                            }
                        }
                        ImmichViewerAction(ViewerAction("上传", Icons.Filled.Upload), Modifier.weight(1f)) {
                            currentAsset?.let { asset ->
                                DiagLog.i(NTAG, "手动上传本地资产：${asset.fileName}")
                                Toast.makeText(context, "开始上传 ${asset.fileName}", Toast.LENGTH_SHORT).show()
                                vm.uploadLocalAsset(context, asset)
                                onClose()
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ImmichViewerAction(ViewerAction("分享", Icons.Filled.Share), Modifier.weight(1f)) {
                            currentAsset?.let { asset ->
                                scope.launch {
                                    shareAsset(context, vm, asset)
                                }
                            }
                        }
                        ImmichViewerAction(ViewerAction("下载", Icons.Filled.Download), Modifier.weight(1f)) {
                            currentAsset?.let { asset ->
                                scope.launch {
                                    saveAsset(context, vm, asset)
                                }
                            }
                        }
                        ImmichViewerAction(
                            ViewerAction("收藏", if (favOn2) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder),
                            Modifier.weight(1f),
                            tint = if (favOn2) p.heart else Color.White,
                        ) {
                            currentAsset?.let { asset ->
                                vm.toggleFavorite(context, asset.id, !favOn2)
                            }
                        }
                        ImmichViewerAction(ViewerAction("删除", Icons.Filled.Delete), Modifier.weight(1f)) {
                            currentAsset?.let { asset ->
                                vm.trashAsset(context, asset.id)
                                Toast.makeText(context, "已移入回收站", Toast.LENGTH_SHORT).show()
                                onClose()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 文件名行（原型 .v-fn）
                Text(
                    currentAsset?.let { asset ->
                        val exif = assetDetail?.exif
                        val sizeText = exif?.let { formatSize(it.fileSizeInByte) } ?: ""
                        val resText = if (exif != null && exif.width > 0) " · ${exif.width}×${exif.height}" else ""
                        "${asset.fileName}$resText${if (sizeText.isNotBlank()) " · $sizeText" else ""}"
                    } ?: "",
                    fontSize = 11.sp,
                    color = Color(0xBFFFFFFF),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/**
 * 查看器图片页：Coil 加载 preview 预览图 + 双指缩放/平移/双击复位。
 *
 * :param asset: 图片资产
 */
@Composable
private fun ImmichPreviewImage(asset: ImmichAsset) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    // 本地资产直读 contentUri（默认 Coil 实例）；云端资产走 Immich 预览端点
    val isLocal = asset.isLocal
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (isLocal) {
                ImmichLocalMedia.localUri(asset)
            } else {
                ImageRequest.Builder(context)
                    .data(immichThumbnailUrl(context, asset.id, preview = true))
                    .crossfade(true)
                    .build()
            },
            imageLoader = if (isLocal) context.imageLoader else ImmichImageLoader.get(context),
            contentDescription = asset.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(asset.id) {
                    detectTransformGestures(
                        onGesture = { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (newScale > 1f) {
                                offset + pan + (centroid - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)) * (newScale / scale - 1f)
                            } else {
                                androidx.compose.ui.geometry.Offset.Zero
                            }
                            scale = newScale
                        },
                    )
                }
                .pointerInput(asset.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * 查看器视频页：Media3 ExoPlayer 直连 video/playback（请求头带会话令牌）。
 * 点屏切换播放/暂停，中央显示状态图标。
 *
 * :param asset: 视频资产
 */
@Composable
private fun ImmichVideoPlayer(asset: ImmichAsset) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    DiagLog.e(NTAG, "视频播放失败（${asset.fileName}）：${error.message}", error)
                }
            })
        }
    }
    // 首次组合建媒体源（DefaultHttpDataSource 带令牌头；销毁时释放播放器）
    LaunchedEffect(asset.id) {
        DiagLog.i(NTAG, "视频播放建立媒体源：${asset.fileName}")
        val token = ImmichPrefs.token(context)
        val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("x-immich-user-token" to token))
            .setAllowCrossProtocolRedirects(true)
        player.setMediaSource(
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(ImmichApiClient(context).playbackUrl(asset.id))),
        )
        player.prepare()
    }
    DisposableEffect(asset.id) {
        onDispose { player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { player.playWhenReady = !player.playWhenReady },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 中央状态图标（缓冲圈 / 播放暂停键）
        if (isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(44.dp),
            )
        } else if (!isPlaying) {
            Text("▶", fontSize = 52.sp, color = Color(0xB3FFFFFF))
        }
    }
}

/**
 * 查看器视频页（本地资产）：ExoPlayer 播放 MediaStore Uri（默认数据源，无令牌）。
 *
 * :param asset: 本地视频资产
 */
@Composable
private fun ImmichLocalVideoPlayer(asset: ImmichAsset) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    DiagLog.e(NTAG, "本地视频播放失败（${asset.fileName}）：${error.message}", error)
                }
            })
        }
    }
    // 本地 Uri 用默认数据源；销毁时释放播放器
    LaunchedEffect(asset.id) {
        ImmichLocalMedia.localUri(asset)?.let { uri ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }
    DisposableEffect(asset.id) {
        onDispose { player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { player.playWhenReady = !player.playWhenReady },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(44.dp),
            )
        } else if (!isPlaying) {
            Text("▶", fontSize = 52.sp, color = Color(0xB3FFFFFF))
        }
    }
}

/**
 * 查看器底部单个动作项：图标 + 文案竖排（原型 .v-act）。
 *
 * :param action: 动作数据（图标 + 文案）
 * :param modifier: 布局参数（外层 weight 均分）
 * :param tint: 图标/文案颜色（收藏态传心形红）
 * :param onClick: 点击回调
 */
@Composable
private fun ImmichViewerAction(
    action: ViewerAction,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onClick() },
    ) {
        Icon(
            action.icon,
            contentDescription = action.label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(action.label, fontSize = 11.sp, color = tint)
    }
}

/**
 * EXIF 详情面板：深色圆角自底部面板，展示真实拍摄信息 / 文件信息 / 人物区。
 *
 * 数据优先级：资产详情（GET /assets/{id} 全量 exifInfo + people）→ 列表条目（缺则显示基础行）。
 *
 * :param listAsset: 列表条目资产（详情未拉到时兜底）
 * :param detail: 资产详情（全量 EXIF + 人物）
 * :param onOpenPerson: 点击人物回调
 */
@Composable
private fun ImmichExifPanel(
    listAsset: ImmichAsset?,
    detail: ImmichAsset?,
    onOpenPerson: (ImmichPerson) -> Unit,
) {
    val asset = detail ?: listAsset ?: return
    val exif = asset.exif
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Column {
            // 顶部拖动手柄条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF555555), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(14.dp))
            Text(ImmichTimeline.dateText(asset), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
            Text(ImmichTimeline.dateTextFull(asset), fontSize = 12.sp, color = Color(0xFF9AA0A6))
            Spacer(Modifier.height(14.dp))
            // 文件信息行（文件名 / 大小 / 分辨率）
            ImmichExifRow("📷") {
                Text(asset.fileName, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                Text(
                    buildString {
                        val size = exif?.fileSizeInByte ?: 0
                        if (size > 0) append(formatSize(size))
                        if (exif != null && exif.width > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${exif.width}×${exif.height}")
                        }
                        if (isEmpty()) append("文件信息加载中…")
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF9AA0A6),
                )
            }
            // 拍摄设备行（真实 EXIF：厂商型号 / 光圈 / 快门 / ISO / 焦距）
            if (exif != null && (exif.make.isNotBlank() || exif.model.isNotBlank())) {
                ImmichExifRow("⚙️") {
                    Text(
                        listOf(exif.make, exif.model).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" ").ifBlank { "未知设备" },
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        buildString {
                            val parts = mutableListOf<String>()
                            if (exif.iso > 0) parts.add("ISO ${exif.iso}")
                            if (exif.fNumber > 0) parts.add("ƒ/${exif.fNumber}")
                            if (exif.exposureTime.isNotBlank()) parts.add("${exif.exposureTime}s")
                            if (exif.focalLength > 0) parts.add("${exif.focalLength}mm")
                            append(parts.joinToString(" · ").ifBlank { "拍摄参数未知" })
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF9AA0A6),
                    )
                }
            }
            // 人物区（D8 只读复刻：原版详情面板顶部即人物；点击进人物照片页）
            if (asset.people.isNotEmpty()) {
                Text("人物", fontSize = 12.sp, color = Color(0xFF9AA0A6))
                Spacer(Modifier.height(8.dp))
                ImmichPeopleRow(people = asset.people, onOpenPerson = onOpenPerson)
            }
        }
    }
}

/**
 * EXIF 面板人物横排：Coil 人脸头像圆 + 名字，点击进人物照片页。
 *
 * :param people: 人物列表
 * :param onOpenPerson: 点击人物回调
 */
@Composable
private fun ImmichPeopleRow(people: List<ImmichPerson>, onOpenPerson: (ImmichPerson) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        people.forEach { person ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onOpenPerson(person) },
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color(0xFF2A2A2D), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(ImmichApiClient(context).personThumbnailUrl(person.id))
                            .size(160)
                            .build(),
                        imageLoader = ImmichImageLoader.get(context),
                        contentDescription = person.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(person.name, fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

/**
 * EXIF 面板单行：左侧表意圆标 + 右侧内容插槽（原型 .dp-row）。
 *
 * :param emoji: 行首表意 emoji
 * :param content: 行内容（Column 插槽）
 */
@Composable
private fun ImmichExifRow(emoji: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 16.sp, modifier = Modifier.width(34.dp))
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

// ============ 文件大小 / 下载分享工具 ============

/**
 * 字节数转人读文案（B / KB / MB / GB，1 位小数）。
 *
 * :param bytes: 字节数
 * :return: 人读文案
 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / (1 shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * 下载原图到缓存 exports 目录（FileProvider 已声明 cache-path exports/）。
 *
 * :param context: 任意 Context
 * :param vm: 视图模型（ApiClient 下载）
 * :param asset: 资产
 * :return: 缓存文件
 */
private suspend fun downloadToCache(context: android.content.Context, vm: ImmichViewModel, asset: ImmichAsset): java.io.File =
    withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val dest = java.io.File(dir, asset.fileName)
        ImmichApiClient(context).downloadOriginal(asset.id, dest)
        dest
    }

/**
 * 系统分享资产（原图下载进缓存 → FileProvider URI → ACTION_SEND）。
 *
 * :param context: 任意 Context
 * :param vm: 视图模型
 * :param asset: 资产
 */
private suspend fun shareAsset(context: android.content.Context, vm: ImmichViewModel, asset: ImmichAsset) {
    try {
        // 本地资产直接拷贝缓存分享（无网络）；云端资产下载原图后分享
        val file = if (asset.isLocal) {
            copyLocalToCache(context, asset)
        } else {
            downloadToCache(context, vm, asset)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (asset.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${asset.fileName}"))
    } catch (e: Exception) {
        DiagLog.e(NTAG, "分享失败（${asset.fileName}）：${e.message}", e)
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 本地资产拷贝到缓存 exports 目录（分享用，流式拷贝）。
 *
 * :param context: 任意 Context
 * :param asset: 本地资产
 * :return: 缓存文件
 */
private suspend fun copyLocalToCache(context: android.content.Context, asset: ImmichAsset): java.io.File =
    withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val dest = java.io.File(dir, asset.fileName)
        val uri = ImmichLocalMedia.localUri(asset) ?: throw ImmichException("找不到本机文件：${asset.fileName}", null)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ImmichException("读取本机文件失败：${asset.fileName}", null)
        dest
    }

/**
 * 保存资产到系统图库（API 29+ 存 MediaStore Pictures/Immich；旧版本存应用外部目录并提示路径）。
 *
 * :param context: 任意 Context
 * :param vm: 视图模型
 * :param asset: 资产
 */
private suspend fun saveAsset(context: android.content.Context, vm: ImmichViewModel, asset: ImmichAsset) {
    try {
        val file = downloadToCache(context, vm, asset)
        val saved = withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, asset.fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (asset.isVideo) "video/mp4" else "image/jpeg")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        if (asset.isVideo) android.provider.MediaStore.Video.Media.RELATIVE_PATH else android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    )
                }
                val collection = if (asset.isVideo) {
                    android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val uri = context.contentResolver.insert(collection, values)
                uri?.let { u ->
                    context.contentResolver.openOutputStream(u)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
                uri != null
            } else {
                // 旧版本：存应用外部文件目录（免权限），提示路径
                val dir = java.io.File(context.getExternalFilesDir(null), "Immich").apply { mkdirs() }
                file.copyTo(java.io.File(dir, asset.fileName), overwrite = true)
                true
            }
        }
        if (saved) {
            Toast.makeText(context, "已保存 ${asset.fileName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        DiagLog.e(NTAG, "保存失败（${asset.fileName}）：${e.message}", e)
        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
