package my.bridge.com.monitor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

/**
 * 监控功能根页面（v0.2.140）。
 *
 * 二级 Tab：实时预览（默认）/ 事件 / 云端 / 延时摄影。
 * 顶栏含节点切换器（「全部节点」聚合视图 + 各节点）。
 * 返回键：第 1 次冒泡提示「再按一次返回主页」，窗口内第 2 次回主页（与销售/管理页同款梯子）。
 */
private val MonitorBlue = Color(0xFF1A6EE8)
private val MonitorInactive = Color(0xFF8A919F)
private val MonitorGreen = Color(0xFF2E9E5B)
private val MonitorGrey = Color(0xFF9AA1AC)

/** 监控页顶层状态：用于返回键逐级退出（由 MainScreen 消费）。 */
object MonitorUiState {
    /** 是否处于全屏播放器（全屏时返回键优先关闭播放器而非退页面）。 */
    var playerOpen by mutableStateOf(false)
}

@Composable
fun MonitorPage(onExitToHome: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MonitorViewModel = hiltViewModel()

    val nodes by viewModel.nodes.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val nodesLoading by viewModel.nodesLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var nodeMenuOpen by remember { mutableStateOf(false) }

    // 首次进入拉取节点清单
    LaunchedEffect(Unit) {
        DiagLog.i(NTAG, "进入监控页，拉取节点清单")
        if (nodes.isEmpty()) viewModel.loadNodes(context)
    }

    // 生命周期：切走监控 Tab（离开组合）时兜底清理全局播放器态，对齐销售页
    // enterPage/exitPage 范式。各子 View 的协程/播放器随 composition 销毁已自动释放，
    // 这里主要防止全屏播放器态残留影响返回键（切 Tab 时若全屏播放器未走 onDispose）。
    DisposableEffect(Unit) {
        onDispose {
            if (MonitorUiState.playerOpen) {
                MonitorUiState.playerOpen = false
                DiagLog.i(NTAG, "离开监控页，清理残留的全屏播放器态")
            }
        }
    }

    // 返回键梯子（对齐销售页范式，Compose BackHandler 后进先出）：
    // 1) 全屏播放器由 MonitorPlayer 内 BackHandler 优先接管关闭；
    // 2) 非「实时预览」二级 Tab 时，先切回实时预览 Tab；
    // 3) 已处实时预览 Tab：第 1 次提示、窗口内第 2 次回主页。
    //    窗口时长取自 BackPressPrefs（设置→通用→返回键，默认 2 秒）。
    var exitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (MonitorUiState.playerOpen) {
            MonitorUiState.playerOpen = false
            return@BackHandler
        }
        // 非实时预览 Tab → 先切回实时预览，不退出页面
        if (currentTab != 0) {
            currentTab = 0
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastBackAt < my.bridge.com.ui.BackPressPrefs.homeWindowMs(context)) {
            onExitToHome()
        } else {
            lastBackAt = now
            exitHint = true
            Toast.makeText(context, "再按一次返回主页", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(exitHint) {
        if (exitHint) {
            kotlinx.coroutines.delay(my.bridge.com.ui.BackPressPrefs.homeWindowMs(context))
            exitHint = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：标题 + 节点切换器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "监控",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box {
                val currentNode = nodes.find { it.id == selectedNode }
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .clickable { nodeMenuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (selectedNode.isBlank() || currentNode?.online == true) MonitorGreen else MonitorGrey,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        currentNode?.nick ?: "全部节点",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = nodeMenuOpen, onDismissRequest = { nodeMenuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(MonitorGreen, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("全部节点", fontSize = 13.sp)
                            }
                        },
                        onClick = {
                            viewModel.selectNode("")
                            nodeMenuOpen = false
                        },
                    )
                    nodes.forEach { n ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp).background(
                                            if (n.online) MonitorGreen else MonitorGrey, CircleShape,
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("${n.nick}${if (!n.online) "（离线）" else ""}", fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                viewModel.selectNode(n.id)
                                nodeMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // 二级 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            listOf("实时预览", "事件", "云端", "延时摄影").forEachIndexed { index, title ->
                val selected = currentTab == index
                Column(
                    modifier = Modifier
                        .clickable {
                            if (currentTab != index) {
                                DiagLog.i(NTAG, "切换二级 Tab → $title")
                                currentTab = index
                            }
                        }
                        .padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MonitorBlue else MonitorInactive,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(3.dp)
                            .background(if (selected) MonitorBlue else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }

        // 内容区：四个子 View 全部保留在组合树中，仅用 zIndex 切换可见性。
        // 之前用 when(currentTab) 只组合当前项，切 Tab 时旧子 View 销毁、新子 View 重建，
        // 导致 remember 状态丢失 + LaunchedEffect 重跑 → 已加载的摄像头/快照/事件/云端目录全部重新拉取。
        // 改为常驻叠加后，切 Tab 仅改变 zIndex，各子 View 的 LaunchedEffect 由数据 key 驱动，
        // 切走再切回不再触发刷新（符合「子页面切换不刷新，仅进入监控页时刷新」的预期）。
        // ★ 关键：每个包裹 Box 必须带不透明背景色，否则上层子 View 内容透明时会透出下层画面
        //   （子 View 根节点多为无背景的 Column/LazyGrid，常驻叠加后出现「延时摄影画面盖住其它页」）。
        Box(modifier = Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(if (currentTab == 0) 1f else 0f)
            ) {
                LiveView(nodes = nodes, selectedNode = selectedNode, nodesLoading = nodesLoading)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(if (currentTab == 1) 1f else 0f)
            ) {
                EventListView(nodes = nodes, selectedNode = selectedNode)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(if (currentTab == 2) 1f else 0f)
            ) {
                CloudBrowserView(nodes = nodes, selectedNode = selectedNode)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(if (currentTab == 3) 1f else 0f)
            ) {
                TimelapseView(nodes = nodes, selectedNode = selectedNode)
            }
        }
    }
}
