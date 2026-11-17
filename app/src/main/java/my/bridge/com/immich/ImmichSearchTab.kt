package my.bridge.com.immich

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.automirrored.filled.ArrowForward

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich「搜索」Tab（Bridge 精简版，接 API 真实化）。
 *
 * 精简口径（调研报告 §5.1 / 原型 pg-search）：砍智能搜索短语 / 地图（依赖 ML 容器），
 * 保留：文件名搜索 + 人物照片页入口（D8 只读复刻） + 最近添加 / 收藏 / 截图 / 视频分类。
 * - 文件名搜索：POST /search/metadata filename 参数，回车触发结果网格；
 * - 人物区：GET /api/people 真实人物（Coil 头像），点击开人物照片页（personIds 过滤）；
 * - 分类入口：收藏 = isFavorite=true；截图 = Screenshots 相册；视频 = type=VIDEO。
 *
 * :param vm: 相册视图模型
 * :param onOpenResults: 打开分类结果网格覆盖层回调
 * :param onOpenViewer: 查看器回调（结果网格内部使用，此处透传签名）
 */
@Composable
fun ImmichSearchTab(
    vm: ImmichViewModel,
    onOpenResults: () -> Unit,
    onOpenViewer: (List<ImmichAsset>, Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalImmichPalette.current
    val people by vm.people.collectAsState()
    val albums by vm.albums.collectAsState()
    // 文件名搜索关键词
    var searchText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // 文件名搜索框（原型 .search-box：胶囊底 + 放大镜 + 输入）
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 4.dp)
                .fillMaxWidth()
                .background(p.surface2, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = p.sub,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = p.text,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchText.isEmpty()) {
                            Text("按文件名搜索…", fontSize = 14.sp, color = p.sub)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (searchText.isNotEmpty()) {
                Text(
                    "搜索",
                    fontSize = 13.sp,
                    color = p.accent,
                    modifier = Modifier
                        .clickable {
                            if (searchText.isNotBlank()) {
                                DiagLog.i(NTAG, "文件名搜索：${searchText}")
                                vm.openResults(context, ImmichViewModel.ImmichQuery(filename = searchText.trim(), title = "搜索「${searchText.trim()}」"))
                                onOpenResults()
                            }
                        }
                        .padding(6.dp),
                )
            }
        }
        // 人物区（v0.2.182：横向滑动显示全部 + 人名本地过滤，照官方人物集合页范式）
        if (people.isNotEmpty()) {
            Text(
                "人物",
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = p.text,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
            )
            // 人物名过滤输入（本地过滤 person.name，官方 people_collection.page 同款做法）
            var personQuery by remember { mutableStateOf("") }
            BasicTextField(
                value = personQuery,
                onValueChange = { personQuery = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = p.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                decorationBox = { innerTextField ->
                    Box {
                        if (personQuery.isEmpty()) {
                            Text("按人物名过滤…", fontSize = 13.sp, color = p.sub)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(p.surface2, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            val shownPeople = if (personQuery.isBlank()) {
                people
            } else {
                people.filter { it.name.contains(personQuery.trim(), ignoreCase = true) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                shownPeople.forEach { person ->
                    ImmichPersonAvatar(person = person, onClick = {
                        DiagLog.i(NTAG, "人物照片页：${person.name}")
                        vm.openResults(context, ImmichViewModel.ImmichQuery(personId = person.id, title = person.name))
                        onOpenResults()
                    })
                }
                if (shownPeople.isEmpty()) {
                    Text("无匹配人物", fontSize = 13.sp, color = p.sub)
                }
            }
        }
        // 分类入口列表（原型 .entry-list / .entry）
        Text(
            "分类",
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = p.text,
            modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 4.dp),
        )
        ImmichSearchEntry(Icons.Filled.Schedule, "最近添加", "服务端最近入库的资产") {
            DiagLog.i(NTAG, "搜索页入口：最近添加（POST /search/metadata desc）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(title = "最近添加"))
            onOpenResults()
        }
        ImmichSearchEntry(Icons.Filled.Favorite, "收藏", "已标记 ♥ 的照片与视频") {
            DiagLog.i(NTAG, "搜索页入口：收藏（isFavorite=true）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(favorite = true, title = "收藏"))
            onOpenResults()
        }
        ImmichSearchEntry(Icons.Filled.Smartphone, "截图", "Screenshots 相册资产") {
            val album = albums.firstOrNull { it.name.equals("Screenshots", true) }
            DiagLog.i(NTAG, "搜索页入口：截图（Screenshots 相册 id=${album?.id}）")
            vm.openResults(
                context,
                ImmichViewModel.ImmichQuery(albumId = album?.id ?: "", title = "截图"),
            )
            onOpenResults()
        }
        ImmichSearchEntry(Icons.Filled.Movie, "视频", "视频资产") {
            DiagLog.i(NTAG, "搜索页入口：视频（type=VIDEO）")
            vm.openResults(context, ImmichViewModel.ImmichQuery(type = "VIDEO", title = "视频"))
            onOpenResults()
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 人物头像项：Coil 人脸缩略图圆 + 名字，点击进人物照片页。
 *
 * :param person: 人物数据
 * :param onClick: 点击回调
 */
@Composable
private fun ImmichPersonAvatar(person: ImmichPerson, onClick: () -> Unit) {
    val p = LocalImmichPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(p.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(ImmichApiClient(context).personThumbnailUrl(person.id))
                    .size(160)
                    .build(),
                imageLoader = ImmichImageLoader.get(context),
                contentDescription = person.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(person.name, fontSize = 11.sp, color = p.text, maxLines = 1)
    }
}

/**
 * 搜索页单个分类入口行：图标块 + 名称/说明 + 右箭头（原型 .entry）。
 *
 * :param icon: Material 图标（入口图标）
 * :param name: 入口名称
 * :param sub: 说明文案（资产数 / 数据来源）
 * :param onClick: 点击回调
 */
@Composable
private fun ImmichSearchEntry(
    icon: ImageVector,
    name: String,
    sub: String,
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
        // 图标块：二级表面色圆角方块 + 品牌色图标（对齐原型 .e-ico）
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(p.surface2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = p.accent,
                modifier = Modifier.size(20.dp),
            )
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
