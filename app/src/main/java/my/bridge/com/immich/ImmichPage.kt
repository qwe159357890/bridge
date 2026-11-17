package my.bridge.com.immich

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.MenuBook

import my.bridge.com.ui.BackPressPrefs
import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/** 底部导航 4 Tab 数据（tab_shell.page.dart：photos / search / albums / library）。 */
private data class ImmichBottomTab(
    val label: String,
    val icon: ImageVector,
)

private val immichBottomTabs = listOf(
    ImmichBottomTab("照片", Icons.Filled.Photo),
    ImmichBottomTab("搜索", Icons.Filled.Search),
    ImmichBottomTab("相册", Icons.AutoMirrored.Filled.MenuBook),
    ImmichBottomTab("资料", Icons.Filled.FolderCopy),
)

/** 查看器上下文：资产列表 + 起始索引（左右翻页在列表内循环）。 */
data class ImmichViewerContext(
    val assets: List<ImmichAsset>,
    val index: Int,
)

/**
 * Immich 全局 UI 状态（借鉴极空页 JkUiState.immersive 范式）。
 * - immersive：双击时间线切换（隐藏主页顶部 Tab 与相册内部底部导航）；
 * - banner：自动备份成功横幅文案（MainScreen 顶层展示，3 秒自动清除）；
 * - refreshTick：备份成功递增——相册页若在前台则监听后自动刷新时间线。
 */
object ImmichUiState {
    /** 是否沉浸式（隐藏主页顶部 Tab 与 APP 底部导航栏；相册内部底导航保持显示）。 */
    var immersive by mutableStateOf(false)

    /** 备份成功横幅文案（null = 不显示）。 */
    var banner by mutableStateOf<String?>(null)

    /** 备份成功刷新信号（递增计数）。 */
    var refreshTick by mutableStateOf(0)

    /** 照片页月份浮窗收缩态（返回键可收起；持久化在 ImmichPrefs）。 */
    var scrubberCollapsed by mutableStateOf(false)

    /** 照片页时间线是否停在顶部「今天」（返回键梯子判断）。 */
    var timelineAtTop by mutableStateOf(true)

    /** 返回键「回到顶部」请求（递增计数；照片页监听后 scrollToItem(0)）。 */
    var scrollTopTick by mutableStateOf(0)
}

/** 相册详情上下文：信息 + 本地三级分组结果。 */
data class ImmichAlbumDetailContext(
    val album: ImmichAlbumInfo,
    val groups: List<ImmichTimeline.ImmichGroup>,
)

/**
 * Immich 相册功能根页面（Bridge 主页顶部 Tab 第 9 页，复刻 Immich 安卓版 v3.4）。
 *
 * 结构（tab_shell.page.dart 同构）：
 * - 内部底部导航 4 Tab：照片 / 搜索 / 相册 / 资料（选中态药丸高亮）；
 * - 覆盖层梯子：分类结果网格（搜索/资料入口 + 人物页）→ 相册详情 → 大图查看器；
 * - 未配置服务端账号时显示引导卡片（去设置 → 相册 分区填写）。
 *
 * 返回键梯子（与监控页同款「再按一次返回」范式）：
 * 1) 查看器打开 → 关闭查看器；
 * 2) 相册详情打开 → 关闭详情；
 * 3) 结果网格打开 → 关闭网格；
 * 4) 非「照片」Tab → 切回照片 Tab；
 * 5) 已在照片 Tab：第 1 次提示、窗口内第 2 次回主页（窗口取设置→通用→返回键）。
 *
 * 主题（决策 D6 默认）：跟随系统深浅色，亮 #4150AF / 暗 #ACCBFA。
 *
 * :param onExitToHome: 返回主页首 Tab 回调（由 MainScreen 提供）
 */
@Composable
fun ImmichPage(onExitToHome: () -> Unit) {
    val context = LocalContext.current
    val vm = remember { ImmichViewModel() }
    // 深浅色跟随系统（原型顶部「浅色/深色」按钮为演示工具，非应用内交互）
    val palette = if (isSystemInDarkTheme()) ImmichDarkPalette else ImmichLightPalette
    // 首次进入：自动进入沉浸模式（用户拍板 2026-11-17，销售页阅读模式同款——
    // 隐藏主页顶部 Tab + 相册底部导航，只显示相册界面；双击返回键退出）
    LaunchedEffect(Unit) {
        ImmichUiState.immersive = true
        vm.loadAll(context)
        // 自动备份兜底：进相册页触发一次对账（调研 §5.4；开关关闭时内部为 no-op）
        ImmichBackup.ensureRegistered(context)
        ImmichBackup.triggerSyncNow(context)
    }
    // 相册页每次回到前台都触发对账（v0.2.179）：LaunchedEffect(Unit) 只在首次组合执行，
    // 页面常驻/切走再切回不会重新触发；OnePlus 内容触发随机丢事件时这是最快的兜底路径
    //（节流 30s，内部开关 no-op 保护）。
    DisposableEffect(context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ImmichBackup.triggerSyncNow(context)
            }
        }
        (context.findActivity() as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(observer)
        onDispose {
            (context.findActivity() as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.removeObserver(observer)
        }
    }
    // 备份成功信号：相册页在前台时自动刷新时间线（新照片出现在「今天」组）
    LaunchedEffect(ImmichUiState.refreshTick) {
        if (ImmichUiState.refreshTick > 0) {
            DiagLog.i(NTAG, "备份成功信号 → 刷新时间线（tick=${ImmichUiState.refreshTick}）")
            vm.loadTimelinePage(context)
        }
    }
    // 沉浸式状态（ImmichUiState.immersive 为 snapshot 状态，双击切换自动触发重组；
    // 退出本页时复位，避免下次进入残留）
    DisposableEffect(Unit) {
        onDispose { ImmichUiState.immersive = false }
    }

    // 内部底部导航当前 Tab：0=照片 1=搜索 2=相册 3=资料
    var currentTab by rememberSaveable { mutableStateOf(0) }
    // 分类结果网格覆盖层（null=关闭；搜索/资料入口与人物照片页共用）
    var resultsOpen by remember { mutableStateOf(false) }
    // 相册详情覆盖层（null=关闭）
    var albumDetail by remember { mutableStateOf<ImmichAlbumDetailContext?>(null) }
    // 大图查看器覆盖层（null=关闭）
    var viewerCtx by remember { mutableStateOf<ImmichViewerContext?>(null) }
    // 返回键梯子的「再按一次」状态
    var exitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableStateOf(0L) }
    // 提示文案（按梯子层级变化：退出沉浸 / 回到顶部 / 返回主页）
    var exitHintText by remember { mutableStateOf("") }
    // 冒泡提示 2 秒后自动消失（与监控页同款）
    LaunchedEffect(exitHint) {
        if (exitHint) {
            kotlinx.coroutines.delay(BackPressPrefs.exitWindowMs(context))
            exitHint = false
        }
    }

    // 返回键梯子（覆盖层由各自 BackHandler 优先接管——后组合先出）。
    // 用户拍板 2026-11-17（照片页，从高到低）：
    // 查看器 → 分类结果 → 相册详情 → 非 photos Tab 切回 → 浮窗展开则收起
    // → 已滑动（非顶部）双击回顶部 → 沉浸中双击退出沉浸 → 顶部双击回主页
    BackHandler {
        if (viewerCtx != null) {
            viewerCtx = null
            return@BackHandler
        }
        if (resultsOpen) {
            resultsOpen = false
            return@BackHandler
        }
        if (albumDetail != null) {
            albumDetail = null
            return@BackHandler
        }
        if (currentTab != 0) {
            currentTab = 0
            return@BackHandler
        }
        // 浮窗展开时优先收起浮窗（用户拍板：更高优先级）
        if (!ImmichUiState.scrubberCollapsed) {
            ImmichUiState.scrubberCollapsed = true
            ImmichPrefs.saveScrubberCollapsed(context, true)
            return@BackHandler
        }
        // 通用双击判定：窗口内第 1 次按弹提示，第 2 次执行动作
        fun doubleBack(hint: String, action: () -> Unit) {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {
                action()
                exitHint = false
            } else {
                lastBackAt = now
                exitHintText = hint
                exitHint = true
            }
        }
        // 已滑动（非顶部）优先回顶部——与是否沉浸解耦（用户拍板 2026-11-17 第 2 轮：
        // 滑动会自动重入沉浸，若沉浸分支在前则「回顶部」永远轮不到）
        if (!ImmichUiState.timelineAtTop) {
            doubleBack("再按一次回到顶部") { ImmichUiState.scrollTopTick += 1 }
            return@BackHandler
        }
        if (ImmichUiState.immersive) {
            doubleBack("再按一次退出沉浸模式") { ImmichUiState.immersive = false }
            return@BackHandler
        }
        doubleBack("再按一次返回主页") { onExitToHome() }
    }

    // 调色板组合局部：所有 Immich 子组件经 LocalImmichPalette 读取
    CompositionLocalProvider(LocalImmichPalette provides palette) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg),
        ) {
            // 主内容：4 Tab 页面
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        0 -> ImmichPhotosTab(
                            vm = vm,
                            onOpenViewer = { assets, index ->
                                viewerCtx = ImmichViewerContext(assets, index)
                            },
                        )
                        1 -> ImmichSearchTab(
                            vm = vm,
                            onOpenResults = { resultsOpen = true },
                            onOpenViewer = { assets, index ->
                                viewerCtx = ImmichViewerContext(assets, index)
                            },
                        )
                        2 -> ImmichAlbumsTab(
                            vm = vm,
                            onOpenAlbum = { album ->
                                DiagLog.i(NTAG, "进入相册详情：${album.name}（${album.assetCount} 个资产）")
                                vm.loadAlbumDetail(context, album.id) { info, groups ->
                                    albumDetail = ImmichAlbumDetailContext(info, groups)
                                }
                            },
                        )
                        3 -> ImmichLibraryTab(
                            vm = vm,
                            onOpenResults = { resultsOpen = true },
                            onOpenAlbum = { album ->
                                vm.loadAlbumDetail(context, album.id) { info, groups ->
                                    albumDetail = ImmichAlbumDetailContext(info, groups)
                                }
                            },
                        )
                    }
                    // 返回提醒冒泡（与监控页同款：底部居中胶囊）
                    // 全限定名调用：避免匹配到外层 ColumnScope 的 AnimatedVisibility 扩展
                    androidx.compose.animation.AnimatedVisibility(
                        visible = exitHint,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 90.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            exitHintText.ifBlank { "再按一次返回主页" },
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .background(Color(0xE6333338), RoundedCornerShape(20.dp))
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
                // 内部底部导航（原型 .bottomnav：药丸图标 + 文字标签）
                // v0.2.171：沉浸模式不再隐藏相册自己的底部导航（只隐藏 APP 底部导航栏与主页顶部 Tab）
                ImmichBottomNav(
                    selected = currentTab,
                    onSelect = {
                        DiagLog.i(NTAG, "内部底部导航切换：${immichBottomTabs[it].label}")
                        currentTab = it
                    },
                )
            }
            // 分类结果网格覆盖层（搜索/资料入口 + 人物照片页；盖住主内容与底部导航）
            if (resultsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.bg),
                ) {
                    ImmichResultsGrid(
                        vm = vm,
                        onOpenViewer = { assets, index ->
                            viewerCtx = ImmichViewerContext(assets, index)
                        },
                        onClose = { resultsOpen = false },
                    )
                }
            }
            // 相册详情覆盖层（盖住主内容与底部导航）
            albumDetail?.let { detail ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.bg),
                ) {
                    ImmichAlbumDetail(
                        detail = detail,
                        onOpenViewer = { assets, index ->
                            viewerCtx = ImmichViewerContext(assets, index)
                        },
                        onClose = { albumDetail = null },
                    )
                }
            }
            // 大图查看器覆盖层（最高层，黑底全屏）
            viewerCtx?.let { vctx ->
                ImmichViewer(
                    vm = vm,
                    assets = vctx.assets,
                    startIndex = vctx.index,
                    onClose = { viewerCtx = null },
                    onOpenPerson = { person ->
                        // 点击人物：关查看器 → 开人物照片页（D8 只读复刻）
                        viewerCtx = null
                        vm.openResults(context, ImmichViewModel.ImmichQuery(personId = person.id, title = person.name))
                        resultsOpen = true
                    },
                )
            }
        }
    }
}

/**
 * Immich 内部底部导航：4 Tab 均分，选中态药丸高亮（原型 .bottomnav / .bn.on）。
 *
 * :param selected: 当前选中 Tab 序号
 * :param onSelect: 点选 Tab 回调
 */
@Composable
private fun ImmichBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalImmichPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.navBg)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
    ) {
        immichBottomTabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) },
            ) {
                // 选中药丸：二级表面色圆角底 + 品牌色图标（未选中 = 次要文字色）
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .background(p.surface2, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) p.accent else p.sub,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    tab.label,
                    fontSize = 11.sp,
                    color = if (isSelected) p.text else p.sub,
                )
            }
        }
    }
}

/**
 * 逐层上溯查找宿主 Activity（lifecycle 监听注册用）。
 *
 * :param context: 任意 Context
 * :return: 宿主 Activity；找不到返回 null
 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
