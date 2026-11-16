package my.bridge.com.immich

import coil.compose.AsyncImage
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich「照片」Tab：顶栏（logo + 备份角标 + 头像）+ 三级精细分组时间线网格。
 *
 * 复刻要点（timeline/constants.dart 实锤）：
 * - 3 列方格、2px 间距；
 * - 分组头：标签 + 资产计数胶囊 + 折叠箭头；
 * - 右侧月份拖动条（scrubber）：悬浮浮层，点月份跳转；
 * - 左下角存储指示药丸。
 * 分组采用 Bridge 自定义三级精细度（用户拍板 2026-11-16）：今天 → 最近一周逐天
 * （昨天起 7 天，头文案「M月D日 星期X」）→ 更早一律按月。
 * 缩略图经 Coil（ImmichImageLoader 带 token 头）加载 size=thumbnail webp。
 * 未配置账号时显示配置引导卡片。
 *
 * :param vm: 相册视图模型（时间线/统计状态）
 * :param onOpenViewer: 点击网格块回调（进入大图查看器）
 */
@Composable
fun ImmichPhotosTab(vm: ImmichViewModel, onOpenViewer: (List<ImmichAsset>, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val groups by vm.timelineGroups.collectAsState()
    val loading by vm.timelineLoading.collectAsState()
    val loadingMore by vm.timelineLoadingMore.collectAsState()
    val exhausted by vm.timelineExhausted.collectAsState()
    val stats by vm.statistics.collectAsState()
    val configured by vm.configured.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalImmichPalette.current.bg),
    ) {
        // 顶栏：immich logo + 备份状态角标 + 用户头像（immich_sliver_app_bar.dart 同构）
        ImmichTopBar()
        if (!configured) {
            ImmichConfigGuide()
            return@Column
        }
        // 时间线区域：网格 + 悬浮的拖动条 / 存储指示
        Box(modifier = Modifier.weight(1f)) {
            val gridState = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            // 分组头全局索引表（三级分组：今天/最近一周逐天/按月）
            val headerIndices = remember(groups) { ImmichTimeline.headerIndices(groups) }
            // 分组折叠状态（label → 收起；折叠按钮修复：2026-11-16 反馈问题 1）
            var collapsed by remember { mutableStateOf(setOf<String>()) }
            // 全网格条目总数（头 + 块），无限滚动触发用
            val totalItems = groups.sumOf { 1 + it.assets.size }
            // 无限滚动：snapshotFlow 监听最后一个可见索引，接近底部（剩 30 条内）触发下一页。
            // 修复（2026-11-16 反馈问题 2）：原 derivedStateOf 捕获了首帧 totalItems 导致翻页永不触发。
            LaunchedEffect(groups) {
                snapshotFlow {
                    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                }.collect { idx ->
                    if (totalItems > 0 && idx >= totalItems - 30) vm.loadMoreTimeline(context)
                }
            }
            // 当前滚动位置对应的月份（拖动条高亮用）
            val monthLabels = remember(groups) { groups.map { it.monthLabel }.distinct() }
            val currentMonth by remember {
                derivedStateOf {
                    var mi = 0
                    headerIndices.forEachIndexed { i, idx ->
                        if (gridState.firstVisibleItemIndex >= idx) mi = i
                    }
                    groups.getOrNull(mi)?.monthLabel ?: ""
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEachIndexed { gi, group ->
                    // 分组头：跨满整行（折叠按钮可收起/展开该组）
                    item(key = "header_${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                        ImmichGroupHeader(
                            group = group,
                            collapsed = group.label in collapsed,
                            onToggle = {
                                collapsed = if (group.label in collapsed) {
                                    collapsed - group.label
                                } else {
                                    collapsed + group.label
                                }
                            },
                        )
                    }
                    // 折叠的组只渲染头，不渲染资产块
                    if (group.label !in collapsed) {
                        items(group.assets.size, key = { group.assets[it].id }) { ti ->
                            val asset = group.assets[ti]
                            ImmichAssetTile(
                                asset = asset,
                                onClick = {
                                    DiagLog.i(NTAG, "点击缩略图 → 查看器（${asset.fileName}）")
                                    onOpenViewer(group.assets, ti)
                                },
                            )
                        }
                    }
                }
                // 加载状态条（首页 loading / 无限滚动 loadingMore）
                if (loading || loadingMore) {
                    item(key = "loading_tl", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (loading) "加载中…" else "加载更多…",
                            fontSize = 12.sp,
                            color = LocalImmichPalette.current.sub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            // 右侧月份拖动条：点月份名跳转到该月第一个分组头（align 由调用方 BoxScope 提供）
            ImmichScrubber(
                modifier = Modifier.align(Alignment.CenterEnd),
                months = monthLabels,
                currentMonth = currentMonth,
                onSelect = { label ->
                    DiagLog.i(NTAG, "拖动条跳转月份：$label")
                    val gi = groups.indices.firstOrNull { groups[it].monthLabel == label } ?: return@ImmichScrubber
                    scope.launch { gridState.scrollToItem(headerIndices[gi]) }
                },
            )
            // 左下角存储指示药丸（原型 .storage）
            ImmichStoragePill(
                modifier = Modifier.align(Alignment.BottomStart),
                stats = stats,
            )
        }
    }
}

/**
 * Immich 顶栏：logo 圆徽 + 「immich」字标 + 备份图标角标 + 用户头像。
 *
 * :param 无参数（数据从组合局部读取）
 */
@Composable
fun ImmichTopBar() {
    val p = LocalImmichPalette.current
    // 用户头像首字母（服务端用户名，未配置时 q 兜底）
    val userName = my.bridge.com.immich.ImmichPrefs.serverUserName(
        androidx.compose.ui.platform.LocalContext.current,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // logo 圆徽：conic 渐变近似（两层圆 + 内圈 bg 色镂空），下接 immich 字标
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        Brush.sweepGradient(
                            listOf(p.accentDeep, p.accent, p.bg, p.accentDeep),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(p.bg, CircleShape),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "immich",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = p.text,
            )
        }
        // 备份状态图标：角标显示 ✓（同步完成态）
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Backup,
                contentDescription = "备份状态",
                tint = p.accent,
                modifier = Modifier
                    .size(40.dp)
                    .padding(8.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .background(p.surface3, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 8.sp, color = p.text)
            }
        }
        // 用户头像：品牌深色圆 + 服务端用户名首字母
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(32.dp)
                .background(p.accentDeep, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                userName.firstOrNull()?.uppercase() ?: "Q",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 未配置账号引导卡片（未填服务端账密时的时间线占位）。
 */
@Composable
fun ImmichConfigGuide() {
    val p = LocalImmichPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🏠", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text("相册服务未配置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "去 设置 → 相册 填写服务地址与账号密码，\n即可浏览 Immich 服务端照片。",
            fontSize = 13.sp,
            color = p.sub,
            lineHeight = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 分组头：标签（今天 / 周几+日期 / 月份）+ 资产计数胶囊 + 折叠/展开按钮（原型 .month-head 同款）。
 *
 * :param group: 分组段数据（三级精细分组）
 * :param collapsed: 该组是否已折叠
 * :param onToggle: 点击折叠按钮回调（展开 ⇄ 收起）
 */
@Composable
internal fun ImmichGroupHeader(
    group: ImmichTimeline.ImmichGroup,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val p = LocalImmichPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text)
        Spacer(Modifier.width(6.dp))
        Text(
            group.countLabel,
            fontSize = 12.sp,
            color = p.sub,
            modifier = Modifier
                .background(p.surface2, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        // 折叠/展开按钮：二级表面色圆底 + Material 箭头图标（展开态朝上收起 / 折叠态朝下展开）
        if (onToggle != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(p.surface2, CircleShape)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (collapsed) "展开 ${group.label}" else "收起 ${group.label}",
                    tint = p.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 资产网格块：Coil 缩略图 + 视频 ▶ / 时长角标（全部网格共用）。
 * 缩略图加载失败/加载中时用品牌色渐变占位。
 *
 * :param asset: 资产数据
 * :param onClick: 点击回调
 * :param modifier: 布局参数
 */
@Composable
internal fun ImmichAssetTile(asset: ImmichAsset, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalImmichPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(p.surface2)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(context)
                .data(immichThumbnailUrl(context, asset.id, preview = false))
                .size(360)
                .build(),
            imageLoader = ImmichImageLoader.get(context),
            contentDescription = asset.fileName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (asset.isVideo) {
            // 左下 ▶ 角标（带阴影保证在浅色占位上可见）
            Text(
                "▶",
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 7.dp, bottom = 5.dp),
            )
            // 右下时长角标（半透明黑底白字）
            Text(
                asset.durationLabel,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 5.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 右侧月份拖动条：悬浮浮层列出各月简称，点击跳转、当月高亮（原型 .scrubber）。
 * 三级分组下「今天」与一周内各天仍属当月，拖动条按月聚合不变。
 *
 * :param modifier: 布局参数（调用方传 align 定位）
 * :param months: 月份标签列表（分组出现顺序）
 * :param currentMonth: 当前滚动位置所属月份标签（高亮）
 * :param onSelect: 点选月份回调（传月份标签）
 */
@Composable
private fun ImmichScrubber(
    modifier: Modifier = Modifier,
    months: List<String>,
    currentMonth: String,
    onSelect: (String) -> Unit,
) {
    val p = LocalImmichPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(end = 3.dp)
            .background(p.surface3, RoundedCornerShape(16.dp))
            .padding(horizontal = 7.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        months.forEach { name ->
            val active = name == currentMonth
            Text(
                name,
                fontSize = if (active) 12.sp else 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) p.accent else p.sub,
                modifier = Modifier.clickable { onSelect(name) },
            )
        }
    }
}

/**
 * 左下角存储指示药丸：「X GB · 已用 N 个资产」（原型 .storage）。
 * 服务端统计未拉到时隐藏（避免占位假数据）。
 *
 * :param modifier: 布局参数（调用方传 align 定位）
 * :param stats: 服务端资产统计
 */
@Composable
private fun ImmichStoragePill(modifier: Modifier = Modifier, stats: ImmichStatistics?) {
    val p = LocalImmichPalette.current
    if (stats == null) return
    Row(
        modifier = modifier
            .padding(start = 12.dp, bottom = 10.dp)
            .background(p.surface2, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            "${stats.total} 个资产",
            fontSize = 11.sp,
            color = p.text,
            fontWeight = FontWeight.SemiBold,
        )
        Text(" · 图 ${stats.images} / 视频 ${stats.videos}", fontSize = 11.sp, color = p.sub)
    }
}
