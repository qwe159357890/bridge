package my.bridge.com.immich

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich「资料」Tab（Bridge 精简版，接 API 真实化）。
 *
 * 精简口径（调研报告 §5.1 / 原型 pg-library）：砍人物 / 地点 / 锁定文件夹 / 合作伙伴 /
 * 共享相册（ML 依赖或本项目无需求），保留 收藏 / 最近添加 / 回收站 / 归档 +
 * 服务端相册列表（原版「本机相册」语义 → Bridge 取服务端资产数最多的前 7 个相册）。
 *
 * :param vm: 相册视图模型
 * :param onOpenResults: 打开分类结果网格覆盖层回调
 * :param onOpenAlbum: 点击相册条目回调（进入相册详情覆盖层）
 */
@Composable
fun ImmichLibraryTab(
    vm: ImmichViewModel,
    onOpenResults: () -> Unit,
    onOpenAlbum: (ImmichAlbumInfo) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalImmichPalette.current
    val albums by vm.albums.collectAsState()
    val stats by vm.statistics.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // 分区一：收藏与整理（原型 .sec-title + 入口列表）
        Text(
            "收藏与整理",
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = p.text,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        ImmichLibraryEntry(Icons.Filled.Favorite, "收藏", "标记 ♥ 的资产") {
            DiagLog.i(NTAG, "资料页入口：收藏（isFavorite=true）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(favorite = true, title = "收藏"))
            onOpenResults()
        }
        ImmichLibraryEntry(Icons.Filled.Schedule, "最近添加", "最新入库的资产") {
            DiagLog.i(NTAG, "资料页入口：最近添加（desc）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(title = "最近添加"))
            onOpenResults()
        }
        ImmichLibraryEntry(Icons.Filled.Delete, "回收站", "已删除待清理") {
            DiagLog.i(NTAG, "资料页入口：回收站（isTrashed=true）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(trashed = true, title = "回收站"))
            onOpenResults()
        }
        ImmichLibraryEntry(Icons.Filled.Inventory2, "归档", "主时间线隐藏的资产") {
            DiagLog.i(NTAG, "资料页入口：归档（isArchived=true）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(archived = true, title = "归档"))
            onOpenResults()
        }
        // 分区二：相册（原版本机相册语义 → 服务端资产数前 7 相册）
        Text(
            "相册",
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = p.text,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp),
        )
        albums.take(7).forEach { album ->
            ImmichLibraryEntry(
                icon = Icons.Filled.Inventory2,
                name = album.name,
                sub = "相册 · ${album.assetCount} 个资产",
                emojiFallback = "📁",
                onClick = {
                    DiagLog.i(NTAG, "资料页相册：${album.name}")
                    onOpenAlbum(album)
                },
            )
        }
        if (albums.isEmpty()) {
            Text(
                "相册加载中…（${stats?.total ?: 0} 个资产已统计）",
                fontSize = 12.sp,
                color = p.sub,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 资料页单个入口行：图标块 + 名称/说明 + 右箭头（与搜索页 .entry 同构）。
 *
 * :param icon: Material 图标
 * :param name: 入口名称
 * :param sub: 说明文案
 * :param emojiFallback: 可选 emoji（相册行显示 emoji 代替图标）
 * :param onClick: 点击回调
 */
@Composable
private fun ImmichLibraryEntry(
    icon: ImageVector,
    name: String,
    sub: String,
    emojiFallback: String? = null,
    onClick: () -> Unit,
) {
    val p = LocalImmichPalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标块：二级表面色圆角方块（emoji 优先，否则品牌色图标）
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(p.surface2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (emojiFallback != null) {
                Text(emojiFallback, fontSize = 20.sp)
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = p.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = p.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 12.sp, color = p.sub)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = p.sub,
            modifier = Modifier.size(16.dp),
        )
    }
}
