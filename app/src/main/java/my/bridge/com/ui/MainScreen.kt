package my.bridge.com.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
 * 应用主界面：底部导航（主页 / 设置）+ 各页二级 Tab。
 *
 * 返回键全局策略（v0.1.36）：
 * - 非首 tab（通讯录/发现/连接/保活/关于）→ 切回当前页首 tab；
 * - 首 tab 第 1 次按 → 底部冒泡提醒「再按一次返回退出」；2 秒内再按 → 退出应用；
 * - 二级页面（短信会话详情）由页内 BackHandler 处理（注册更晚，优先级更高）；
 * - 页内弹窗（material3 AlertDialog）系统默认返回键关闭，无需处理。
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 底部导航当前选中项
    var selectedBottom by rememberSaveable { mutableIntStateOf(0) }
    // 各页二级 Tab 当前选中项
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    // 退出提醒冒泡 + 二次退出时间窗
    var showExitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableLongStateOf(0L) }

    // 注意：本 BackHandler 必须先于内容区注册（组合顺序靠前 = 优先级最低），
    // 这样页内 BackHandler（短信详情）在其启用时优先接管
    BackHandler {
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
                0 -> HomePage(currentTab = homeTab, onTabChange = { homeTab = it })
                1 -> SettingsPage(currentTab = settingsTab, onTabChange = { settingsTab = it })
            }
            // 退出提醒冒泡（内容区底部居中，自动 2 秒消失）
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
                Text(
                    "再按一次返回退出 Bridge",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(Color(0xCC303133), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        // 底部导航
        BridgeBottomBar(
            selected = selectedBottom,
            onSelect = { selectedBottom = it },
        )
    }
}

// ============ 底部导航（自定义紧凑版，对齐原型 60px 高、选中蓝/未选中灰） ============
private data class BottomTab(val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("主页", Icons.Filled.Home),
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

// ============ 主页（三个二级 Tab） ============
@Composable
fun HomePage(currentTab: Int, onTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        BridgeTabRow(
            titles = listOf("短信", "通讯录", "发现"),
            currentTab = currentTab,
            onTabChange = onTabChange,
        )
        when (currentTab) {
            0 -> SmsConversationsPage() // 真实短信页（v0.1.34，原假数据骨架）
            1 -> ContactList()
            2 -> DiscoverList()
        }
    }
}

// ============ 设置页（五个二级 Tab） ============
@Composable
fun SettingsPage(currentTab: Int, onTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        BridgeTabRow(
            titles = listOf("通用", "连接", "保活", "上报", "关于"),
            currentTab = currentTab,
            onTabChange = onTabChange,
        )
        when (currentTab) {
            0 -> GeneralSettings()
            1 -> ConnectionSettings()
            2 -> KeepAliveSettings()
            3 -> ReportSettings()
            4 -> AboutPage()
        }
    }
}

// ============ 二级 Tab 栏（均分占满宽度，选中蓝/未选中灰） ============
@Composable
private fun BridgeTabRow(
    titles: List<String>,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
) {
    TabRow(
        selectedTabIndex = currentTab,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = BrandBlue,
        modifier = Modifier.fillMaxWidth(),
        divider = {},
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = currentTab == index
            Tab(
                selected = isSelected,
                onClick = { onTabChange(index) },
                modifier = Modifier.height(44.dp),
                selectedContentColor = BrandBlue,
                unselectedContentColor = InactiveColor,
                text = {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}
