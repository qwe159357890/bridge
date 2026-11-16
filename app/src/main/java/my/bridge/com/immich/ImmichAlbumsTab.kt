package my.bridge.com.immich

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich「相册」Tab：标题栏（排序入口）+ 新建相册按钮 + 2 列相册封面网格。
 *
 * 复刻要点（原型 pg-albums / Immich mobile albums 页）：
 * - 2 列方格封面、封面下相册名 + 资产数；
 * - 「＋ 新建相册」胶囊按钮；右上「⇅ 排序」入口。
 * 数据：GET /albums?withoutAssets=true（封面 = albumThumbnailAssetId 缩略图，
 * 服务端无 /albums/{id}/thumbnail 端点——实测 404，走资产缩略图）。
 *
 * :param vm: 相册视图模型（相册列表状态）
 * :param onOpenAlbum: 点击相册卡片回调（进入相册详情覆盖层）
 */
@Composable
fun ImmichAlbumsTab(vm: ImmichViewModel, onOpenAlbum: (ImmichAlbumInfo) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalImmichPalette.current
    val albums by vm.albums.collectAsState()
    val loading by vm.albumsLoading.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg),
    ) {
        // 标题栏：相册 + 排序入口（原型 .albums-head）
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("相册", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
            // 排序入口：图标 + 文案（列表已按资产数降序；点击重新加载）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    DiagLog.i(NTAG, "相册排序：重新拉取（服务端按资产数降序）")
                    vm.loadMeta(context)
                },
            ) {
                Icon(
                    Icons.Filled.Sort,
                    contentDescription = null,
                    tint = p.sub,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("排序", fontSize = 12.sp, color = p.sub)
            }
        }
        // 新建相册胶囊（原型 .new-album-btn；Bridge 精简口径：只读复刻，暂不开放建相册）
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { DiagLog.i(NTAG, "新建相册：Bridge 精简口径暂不开放（只读复刻）") }
                    .background(p.surface2, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = p.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("新建相册", fontSize = 13.sp, color = p.accent)
            }
        }
        // 相册网格：2 列（原型 .album-grid gap 4；weight 约束保证滚动正常，2026-11-16 修复）
        Box(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                albums.forEachIndexed { i, album ->
                    item(key = album.id) {
                        ImmichAlbumCard(
                            album = album,
                            onClick = {
                                DiagLog.i(NTAG, "进入相册详情：${album.name}（${album.assetCount} 个资产）")
                                onOpenAlbum(album)
                            },
                        )
                    }
                }
                if (loading && albums.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        Text(
                            "加载相册中…",
                            fontSize = 13.sp,
                            color = p.sub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 相册卡片：封面缩略图（Coil）+ 相册名 + 资产数（原型 .album-card）。
 * 无封面资产的相册显示二级表面色空态。
 *
 * :param album: 相册数据（服务端真实条目）
 * :param onClick: 点击回调
 */
@Composable
private fun ImmichAlbumCard(album: ImmichAlbumInfo, onClick: () -> Unit) {
    val p = LocalImmichPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.clickable { onClick() },
    ) {
        // 封面：albumThumbnailAssetId 缩略图；空相册显示 surface2 空态
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .background(p.surface2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (album.thumbnailAssetId.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(immichThumbnailUrl(context, album.thumbnailAssetId, preview = false))
                        .size(720)
                        .build(),
                    imageLoader = ImmichImageLoader.get(context),
                    contentDescription = album.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Text("📁", fontSize = 44.sp)
            }
        }
        Text(
            album.name,
            fontSize = 14.sp,
            color = p.text,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            "${album.assetCount} 个资产",
            fontSize = 12.sp,
            color = p.sub,
            modifier = Modifier
                .padding(top = 2.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}
