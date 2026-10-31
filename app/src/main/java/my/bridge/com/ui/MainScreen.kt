package my.bridge.com.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 品牌色 / 未选中色（与界面原型 ui-mockup.html 对齐）
private val BrandBlue = Color(0xFF1A6EE8)
private val InactiveColor = Color(0xFF8A919F)

/**
 * 应用主界面：底部导航（主页 / 销售 / 设置）+ 各页二级 Tab。
 *
 * 返回键全局策略（v0.1.36）：
 * - 非首 tab（通讯录/发现/连接/保活/关于）→ 切回当前页首 tab；
 * - 首 tab 第 1 次按 → 底部冒泡提醒「再按一次返回退出」；2 秒内再按 → 退出应用；
 * - 二级页面（短信会话详情）由页内 BackHandler 处理（注册更晚，优先级更高）；
 * - 页内弹窗（material3 AlertDialog）系统默认返回键关闭，无需处理。
 *
 * 销售页沉浸导航（v0.2.09）：
 * - 点击底部「销售」→ 隐藏底部导航，销售页顶栏/登录页提供「返回导航」恢复；
 * - 恢复导航不退出销售页、不清空产品类型/筛选/分页/表格数据；再点「销售」重新沉浸。
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 底部导航当前选中项：0=主页 1=销售 2=设置
    var selectedBottom by rememberSaveable { mutableIntStateOf(0) }
    // 各页二级 Tab 当前选中项
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    // 销售页沉浸模式：进入销售页即隐藏底部导航；页内「返回导航」置 true 重新显示，
    // 再次点击「销售」则重新沉浸（不退出销售页、不丢表格状态）。
    var salesNavVisible by rememberSaveable { mutableStateOf(false) }
    // 主页页面显示顺序（v0.2.08，设置→通用可调）：homeTabOrder[位置]=页面 id。
    // 持有在顶层使设置页改动即时反映到主页 Tab（HomePage 与设置页共享此状态）。
    var homeTabOrder by remember { mutableStateOf(TabOrderPrefs.load(context)) }
    // 主页页面自定义名称（v0.2.08）：homeTabNames[页面id]，空串=用默认名。
    var homeTabNames by remember { mutableStateOf(TabOrderPrefs.loadNames(context)) }
    // 显示标题 = 顺序表翻译 + 名称表解析（HomePage 只管渲染，计算收敛在此）
    val homeTitles = homeTabOrder.map { TabOrderPrefs.displayName(homeTabNames, it) }
    // 退出提醒冒泡 + 二次退出时间窗
    var showExitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableLongStateOf(0L) }

    // 注意：本 BackHandler 必须先于内容区注册（组合顺序靠前 = 优先级最低），
    // 这样页内 BackHandler（短信详情）在其启用时优先接管
    BackHandler {
        // 销售页（沉浸，无二级 tab）：按返回键 = 点击左上角「返回导航」箭头。
        // 第 1 次按 → 显示底部导航栏；再次按 → 隐藏导航栏（重新沉浸），如此循环。
        if (selectedBottom == 1) {
            salesNavVisible = !salesNavVisible
            return@BackHandler
        }
        val onLanding = if (selectedBottom == 0) homeTab == 0 else settingsTab == 0
        if (!onLanding) {
            // 非首 tab：切回当前页的首 tab
            if (selectedBottom == 0) homeTab = 0 else settingsTab = 0
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                (context as? android.app.Activity)?.finishAffinity()
            } else {
                lastBackAt = now
                showExitHint = true
            }
        }
    }
    LaunchedEffect(showExitHint) {
        if (showExitHint) {
            kotlinx.coroutines.delay(2000)
            showExitHint = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 内容区：根据底部导航显示对应页面（占据剩余全部空间）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (selectedBottom) {
                0 -> HomePage(
                    titles = homeTitles,
                    currentTab = homeTab,
                    tabOrder = homeTabOrder,
                    onTabChange = { homeTab = it },
                )
                1 -> SalesPage(
                    onShowBottomBar = { salesNavVisible = true },
                )
                2 -> SettingsPage(
                    currentTab = settingsTab,
                    homeTabOrder = homeTabOrder,
                    homeTabNames = homeTabNames,
                    onHomeTabOrderChange = { order ->
                        homeTabOrder = order
                        TabOrderPrefs.save(context, order)
                    },
                    onHomeTabNameChange = { names ->
                        homeTabNames = names
                        TabOrderPrefs.saveNames(context, names)
                    },
                    onTabChange = { settingsTab = it },
                )
            }
            // 退出提醒冒泡（内容区底部居中，自动 2 秒消失）。
            // v0.1.57 美化：品牌蓝图标圆徽 + 深色渐变胶囊 + 投影（用户要求美观，
            // 旧版是纯色块白字毫无设计感）
            androidx.compose.animation.AnimatedVisibility(
                visible = showExitHint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.slideInVertically { it / 2 },
                exit = androidx.compose.animation.fadeOut() +
                    androidx.compose.animation.slideOutVertically { it / 2 },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .shadow(10.dp, RoundedCornerShape(24.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xF0243348), Color(0xF01A6EE8)),
                            ),
                            RoundedCornerShape(24.dp),
                        )
                        .padding(start = 7.dp, end = 18.dp, top = 7.dp, bottom = 7.dp),
                ) {
                    // 图标圆徽：品牌蓝底 + 白色退出图标
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Color(0xFF1A6EE8), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "再按一次返回退出 Bridge",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // 底部导航：销售页沉浸时隐藏（selectedBottom==1 且未点「返回导航」）。
        // 点击任意 Tab 时重置 salesNavVisible，保证再次进入销售页回到沉浸态。
        if (selectedBottom != 1 || salesNavVisible) {
            BridgeBottomBar(
                selected = selectedBottom,
                onSelect = { idx ->
                    selectedBottom = idx
                    // 点击「销售」→ 重新沉浸；点其它 Tab → 复位，下次进销售仍是沉浸
                    salesNavVisible = idx != 1
                },
            )
        }
    }
}

// ============ 底部导航（自定义紧凑版，对齐原型 60px 高、选中蓝/未选中灰） ============
private data class BottomTab(val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("主页", Icons.Filled.Home),
    BottomTab("销售", Icons.Filled.Store),
    BottomTab("设置", Icons.Filled.Settings),
)

@Composable
private fun BridgeBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bottomTabs.forEachIndexed { index, tab ->
            val isSelected = selected == index
            val color = if (isSelected) BrandBlue else InactiveColor
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = color,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tab.label,
                    color = color,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ============ 主页（顶部页面 Tab，顺序与名称可由设置→通用调整） ============
@Composable
fun HomePage(
    titles: List<String>,
    currentTab: Int,
    tabOrder: List<Int>,
    onTabChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BridgeTabRow(
            // titles 已按显示顺序解析好（自定义名/默认名）
            titles = titles,
            currentTab = currentTab,
            onTabChange = onTabChange,
        )
        // currentTab 语义=「位置」，经顺序表翻译成页面 id 再分发。
        // coerceIn 兜底：rememberSaveable 恢复的旧索引恒在 0..3，此处防御性钳制。
        when (tabOrder.getOrElse(currentTab) { 0 }) {
            0 -> SmsConversationsPage() // 真实短信页（v0.1.34，原假数据骨架）
            1 -> AudioRelayPage() // 音频中继页（手机放音，原通讯录占位已移除）
            2 -> RelaySwitchPage() // 智能控制页（ESP32 继电器，原「发现」假数据占位已替换）
            3 -> ScanPage() // 快递扫码页（v0.1.94 新增，CameraX + ML Kit 纯本地闭环）
        }
    }
}

// ============ 设置页（五个二级 Tab） ============
@Composable
fun SettingsPage(
    currentTab: Int,
    homeTabOrder: List<Int>,
    homeTabNames: List<String>,
    onHomeTabOrderChange: (List<Int>) -> Unit,
    onHomeTabNameChange: (List<String>) -> Unit,
    onTabChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BridgeTabRow(
            titles = listOf("通用", "连接", "保活", "上报", "关于"),
            currentTab = currentTab,
            onTabChange = onTabChange,
        )
        when (currentTab) {
            0 -> GeneralSettings(homeTabOrder, homeTabNames, onHomeTabOrderChange, onHomeTabNameChange)
            1 -> ConnectionSettings()
            2 -> KeepAliveSettings()
            3 -> ReportSettings()
            4 -> AboutPage()
        }
    }
}

// ============ 二级 Tab 栏（标题不换行；空间不足时可左右滑动） ============
@Composable
private fun BridgeTabRow(
    titles: List<String>,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
) {
    // v0.1.95：4 个 Tab 后均分宽度不够导致文字换行。改为自定义行：
    // 标题 maxLines=1 不换行；Row 横向可滚动（窄屏/超长标题时左右滑动）。
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = currentTab == index
            Column(
                modifier = Modifier
                    .clickable { onTabChange(index) }
                    .padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    softWrap = false,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) BrandBlue else InactiveColor,
                )
                Spacer(Modifier.height(6.dp))
                // 选中下划线
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .background(
                            if (isSelected) BrandBlue else Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
