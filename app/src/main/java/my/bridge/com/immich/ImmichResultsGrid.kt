package my.bridge.com.immich

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

private const val NTAG = "相册"

/**
 * 分类结果网格覆盖层（搜索/资料入口与人物照片页共用，D8 人物照片页即此页）。
 *
 * 结构：子顶栏（‹ 返回 + 标题 + 计数）+ 三级精细分组网格（与主时间线同规格）+ 无限滚动。
 * 数据：ImmichViewModel.openResults 已按检索条件（type/personId/albumId/收藏/回收站/
 * 归档/文件名）分页拉取，本页仅渲染与续页。
 *
 * :param vm: 相册视图模型（结果状态）
 * :param onOpenViewer: 点击网格块回调（资产列表 + 起始索引）
 * :param onClose: 关闭覆盖层回调
 */
@Composable
fun ImmichResultsGrid(
    vm: ImmichViewModel,
    onOpenViewer: (List<ImmichAsset>, Int) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalImmichPalette.current
    val title by vm.resultsTitle.collectAsState()
    val assets by vm.resultsAssets.collectAsState()
    val loading by vm.resultsLoading.collectAsState()
    val loadingMore by vm.resultsLoadingMore.collectAsState()
    val groups = remember(assets) { ImmichTimeline.group(assets) }
    val headerIndices = remember(groups) { ImmichTimeline.headerIndices(groups) }
    val gridState = rememberLazyGridState()
    // 分组折叠状态（与照片 Tab 同款）
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val totalItems = groups.sumOf { 1 + it.assets.size }
    // 无限滚动：snapshotFlow 监听最后可见索引（修复照片 Tab 同源的 stale 捕获问题）
    LaunchedEffect(groups) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { idx ->
            if (totalItems > 0 && idx >= totalItems - 30) vm.loadMoreResults(context)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg),
    ) {
        // 子顶栏
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = p.text,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { onClose() },
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = p.text)
                Text("${assets.size} 个资产", fontSize = 12.sp, color = p.sub)
            }
        }
        // 结果网格（空态：加载中 / 无结果）
        if (groups.isEmpty()) {
            Text(
                if (loading) "加载中…" else "无结果",
                fontSize = 13.sp,
                color = p.sub,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEachIndexed { gi, group ->
                    item(key = "rg_${group.label}", span = { GridItemSpan(maxLineSpan) }) {
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
                    if (group.label !in collapsed) {
                        items(group.assets.size, key = { group.assets[it].id }) { ti ->
                            val asset = group.assets[ti]
                            ImmichAssetTile(
                                asset = asset,
                                onClick = { onOpenViewer(group.assets, ti) },
                            )
                        }
                    }
                }
                if (loadingMore) {
                    item(key = "loading_rg", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "加载更多…",
                            fontSize = 12.sp,
                            color = p.sub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
