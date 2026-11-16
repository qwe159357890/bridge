package my.bridge.com.immich

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich 相册详情覆盖层：返回 + 相册名/资产数 + 多选/更多按钮 + 资产网格。
 *
 * 复刻要点（原型 pg-album-detail / Immich mobile album_detail.page.dart）：
 * - 子顶栏：‹ 返回、双行标题（相册名 + 资产数 · 时间范围）、☑ 多选、⋮ 更多；
 * - 网格与主时间线同规格（3 列 / 2px 间距），三级精细分组（ImmichTimeline.group）。
 * 数据：ImmichViewModel.loadAlbumDetail（GET /albums/{id} 全量资产 → 本地分组）。
 *
 * :param detail: 相册详情上下文（信息 + 分组）
 * :param onOpenViewer: 点击网格块回调（资产列表 + 起始索引）
 * :param onClose: 关闭覆盖层回调（返回相册页）
 */
@Composable
fun ImmichAlbumDetail(
    detail: ImmichAlbumDetailContext,
    onOpenViewer: (List<ImmichAsset>, Int) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalImmichPalette.current
    val album = detail.album
    val groups = detail.groups
    // 返回键：先关相册详情（注册晚于 ImmichPage 主梯子 → 优先接管）
    BackHandler { onClose() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg),
    ) {
        // 子顶栏（原型 .sub-appbar）
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回相册列表",
                tint = p.text,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onClose() }
                    .padding(2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(album.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                Text(
                    "${album.assetCount} 个资产 · ${album.startDate} ~ ${album.endDate}",
                    fontSize = 12.sp,
                    color = p.sub,
                )
            }
            // 多选模式入口（Bridge 精简口径：只读复刻，暂不开放多选）
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { DiagLog.i(NTAG, "相册详情：多选模式（Bridge 精简口径暂不开放）") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "多选",
                    tint = p.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            // 更多菜单入口（排序/改名/删除相册等，只读复刻暂占位）
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { DiagLog.i(NTAG, "相册详情：更多菜单（只读复刻暂占位）") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = p.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // 资产网格（三级精细分组，与主时间线同规格 3 列 2px）
        if (groups.isEmpty()) {
            Text(
                "相册为空或加载中…",
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
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEachIndexed { gi, group ->
                    item(key = "adh_${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                        ImmichGroupHeader(group = group)
                    }
                    items(group.assets.size, key = { group.assets[it].id }) { ti ->
                        val asset = group.assets[ti]
                        ImmichAssetTile(
                            asset = asset,
                            onClick = {
                                DiagLog.i(NTAG, "相册详情点击缩略图 → 查看器（${asset.fileName}）")
                                onOpenViewer(group.assets, ti)
                            },
                        )
                    }
                }
            }
        }
    }
}
