package my.bridge.com.immich

import androidx.compose.runtime.snapshotFlow
import coil.compose.AsyncImage
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich「照片」Tab：三级精细分组时间线网格（无顶栏——装饰性 logo/头像已按反馈移除）。
 *
 * 复刻要点（timeline/constants.dart 实锤）：
 * - 3 列方格、2px 间距；
 * - 分组头：标签 + 资产计数胶囊 + 折叠/展开按钮；
 * - 右侧月份拖动条（scrubber）：点月份跳转；
 * - 左下角存储指示药丸。
 * 分组采用 Bridge 自定义三级精细度（用户拍板 2026-11-16）：今天 → 最近一周逐天
 * （昨天起 7 天，头文案「M月D日 星期X」）→ 更早一律按月。
 * 缩略图经 Coil（ImmichImageLoader 带 token 头）加载 size=thumbnail webp。
 * 网格块角标（用户反馈第 5 条）：☁ 已备份（云端资产）/ ⬆ 未上传（本地独有资产）。
 * 双击时间线进入/退出沉浸式（隐藏底部导航 + 主页顶部 Tab，借鉴极空页；用户反馈第 6 条）。
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
    val error by vm.error.collectAsState()
    val stats by vm.statistics.collectAsState()
    val configured by vm.configured.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalImmichPalette.current.bg)
            .statusBarsPadding(),
    ) {
        if (!configured) {
            ImmichConfigGuide()
            return@Column
        }
        // 时间线区域：网格 + 悬浮的拖动条 / 存储指示（双击切换沉浸式）
        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            ImmichUiState.immersive = !ImmichUiState.immersive
                            DiagLog.i(NTAG, "双击切换沉浸式 → ${ImmichUiState.immersive}")
                        },
                    )
                },
        ) {
            val gridState = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            // 每次进页刷新（refreshSeq 自增）后回到顶部「今天」——修复网格按索引恢复
            // 滚动位置导致落在旧位置（11月12）的问题
            val refreshSeq by vm.refreshSeq.collectAsState()
            LaunchedEffect(refreshSeq) {
                if (refreshSeq > 0) gridState.scrollToItem(0)
            }
            // 分组头全局索引表（三级分组：今天/最近一周逐天/按月）
            val headerIndices = remember(groups) { ImmichTimeline.headerIndices(groups) }
            // 分组折叠状态（label → 收起）
            var collapsed by remember { mutableStateOf(setOf<String>()) }
            // 无限滚动：derivedStateOf 读网格自带的 totalItemsCount（权威计数，无 stale 捕获），
            // 接近底部（剩 60 条内）触发下一页。带日志：触发/跳过原因全部上报 DiagLog。
            val nearBottom by remember {
                derivedStateOf {
                    val li = gridState.layoutInfo
                    val last = li.visibleItemsInfo.lastOrNull()?.index ?: -1
                    li.totalItemsCount > 0 && last >= li.totalItemsCount - 60
                }
            }
            LaunchedEffect(nearBottom) {
                if (nearBottom) {
                    DiagLog.i(NTAG, "无限滚动：接近底部（last=${gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index} total=${gridState.layoutInfo.totalItemsCount}）")
                    vm.loadMoreTimeline(context)
                }
            }
            // 右侧月份拖动条：固定全量列表（今天 / 一周逐天 MM-dd / 已加载月 + 未加载月）
            // 点已加载项跳转；点未加载月触发按月加载（拉全该月插入）
            val buckets by vm.monthBuckets.collectAsState()
            val monthLoadingKey by vm.monthLoadingKey.collectAsState()
            val monthJumpKey by vm.monthJumpKey.collectAsState()
            val scrubItems = remember(groups, buckets) { ImmichTimeline.scrubItems(groups, buckets) }
            // 当前滚动位置对应的分组（浮窗高亮用）
            val currentLabel by remember {
                derivedStateOf {
                    var gi = 0
                    headerIndices.forEachIndexed { i, idx ->
                        if (gridState.firstVisibleItemIndex >= idx) gi = i
                    }
                    groups.getOrNull(gi)?.label ?: ""
                }
            }
            // 按月加载完成 → 跳转到该月分组头（等分组重算出现后执行）
            LaunchedEffect(monthJumpKey, groups) {
                val key = monthJumpKey ?: return@LaunchedEffect
                val gi = groups.indexOfFirst { it.monthKey == key && Regex("""\d+年\d+月""").matches(it.label) }
                if (gi >= 0) {
                    DiagLog.i(NTAG, "按月加载完成 → 跳转 ${groups[gi].label}")
                    scope.launch { gridState.scrollToItem(headerIndices[gi]) }
                    vm.clearMonthJump()
                }
            }
            ImmichScrubber(
                modifier = Modifier.align(Alignment.CenterEnd),
                items = scrubItems,
                currentLabel = currentLabel,
                loadingKey = monthLoadingKey,
                onSelect = { item ->
                    val gi = item.groupIndex
                    if (gi != null) {
                        DiagLog.i(NTAG, "浮窗跳转：${item.label}")
                        scope.launch { gridState.scrollToItem(headerIndices[gi]) }
                    } else {
                        DiagLog.i(NTAG, "浮窗按月加载：${item.monthKey}（${item.count} 个）")
                        vm.loadMonth(context, item.monthKey)
                    }
                },
            )
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
                                    DiagLog.i(NTAG, "点击缩略图 → 查看器（${asset.fileName}${if (asset.isLocal) " 本地" else ""}）")
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
            // 左下角存储指示药丸（原型 .storage）
            ImmichStoragePill(
                modifier = Modifier.align(Alignment.BottomStart),
                stats = stats,
            )
        }
        // 错误提示条（加载失败可见可点消失；否则失败会被静默吞掉）
        error?.let { msg ->
            Text(
                msg,
                fontSize = 12.sp,
                color = Color(0xFFFF6B6B),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.clearError() }
                    .background(Color(0x22FF6B6B), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // 沉浸式提示条（底部小字，提示双击退出）
        if (ImmichUiState.immersive) {
            Text(
                "沉浸模式 · 双击屏幕退出",
                fontSize = 11.sp,
                color = LocalImmichPalette.current.sub,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
        Text("相册服务未配置", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.text)
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
        Text(group.label, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.text)
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
 * 资产网格块：Coil 缩略图 + 视频 ▶ / 时长角标 + 同步状态角标（全部网格共用）。
 * 云端资产（☁ 蓝底已备份）；本地独有资产（⬆ 琥珀底未上传，直接读 contentUri）。
 * 缩略图加载失败/加载中时用品牌色渐变占位。
 *
 * :param asset: 资产数据（云端或本地）
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
        if (asset.isLocal) {
            // 本地资产：直接加载 MediaStore content URI（默认 Coil 实例，无需 token）
            AsyncImage(
                model = ImmichLocalMedia.localUri(asset),
                contentDescription = asset.fileName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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
        }
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
        // 同步状态角标（用户反馈第 5 条）：☁ 已备份 / ⬆ 未上传
        if (asset.isLocal) {
            Text(
                "⬆",
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 4.dp)
                    .background(Color(0xE6B8860B), CircleShape)
                    .padding(3.dp),
            )
        } else {
            Text(
                "☁",
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 4.dp)
                    .background(Color(0xCC4150AF), CircleShape)
                    .padding(3.dp),
            )
        }
    }
}

/**
 * 右侧浮窗：固定全量列表（用户拍板 2026-11-16）——今天 / 一周内逐天（MM-dd）/
 * 已加载月份 + 未加载月份（附资产数），不再随加载动态增减。
 * 点击已加载项 → 跳转该分组头；点击未加载月 → 触发按月加载（拉全该月插入时间线）。
 * 内部可滚动（服务端月份多时列表长，高度限制约屏高 40%）。
 *
 * :param modifier: 布局参数（调用方传 align 定位）
 * :param items: 浮窗条目（ImmichTimeline.scrubItems 构造）
 * :param currentLabel: 当前滚动位置分组标签（高亮匹配）
 * :param loadingKey: 正在按月加载的月份键（null = 空闲）
 * :param onSelect: 点选条目回调
 */
@Composable
private fun ImmichScrubber(
    modifier: Modifier = Modifier,
    items: List<ImmichTimeline.ImmichScrubItem>,
    currentLabel: String,
    loadingKey: String?,
    onSelect: (ImmichTimeline.ImmichScrubItem) -> Unit,
) {
    val p = LocalImmichPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(end = 3.dp)
            .background(p.surface3, RoundedCornerShape(16.dp))
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 7.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            val isCurrent = item.label == currentLabel
            val isLoading = item.monthKey == loadingKey
            val loaded = item.groupIndex != null
            Text(
                text = when {
                    isLoading -> "${item.label}…"
                    loaded -> item.label
                    else -> "${item.label} ${item.count}"
                },
                fontSize = if (isCurrent) 12.sp else 10.sp,
                fontWeight = if (isCurrent) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Normal
                },
                color = when {
                    isCurrent -> p.accent
                    isLoading -> p.accent
                    loaded -> p.sub
                    else -> p.sub.copy(alpha = 0.55f)
                },
                modifier = Modifier.clickable { onSelect(item) },
            )
        }
    }
}

/**
 * 左下角存储指示药丸：「已统计 N 个资产（图 X / 视频 Y）」（原型 .storage）。
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Text(" · 图 ${stats.images} / 视频 ${stats.videos}", fontSize = 11.sp, color = p.sub)
    }
}
