package my.bridge.com.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 应用主界面：底部导航（主页 / 设置）+ 各页二级 Tab。
 * 当前为界面骨架实现，内容为假数据，仅展示界面结构与交互。
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    // 底部导航当前选中项
    var selectedBottom by rememberSaveable { mutableIntStateOf(0) }
    // 各页二级 Tab 当前选中项
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }

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
        }
        // 底部导航
        BridgeBottomBar(
            selected = selectedBottom,
            onSelect = { selectedBottom = it },
        )
    }
}

// ============ 底部导航 ============
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
    NavigationBar {
        bottomTabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}

// ============ 主页（三个二级 Tab） ============
@Composable
fun HomePage(currentTab: Int, onTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = currentTab,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("会话", "通讯录", "发现").forEachIndexed { index, title ->
                Tab(
                    selected = currentTab == index,
                    onClick = { onTabChange(index) },
                    text = { Text(title) },
                )
            }
        }
        when (currentTab) {
            0 -> ChatList()
            1 -> ContactList()
            2 -> DiscoverList()
        }
    }
}

// ============ 设置页（三个二级 Tab） ============
@Composable
fun SettingsPage(currentTab: Int, onTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = currentTab,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("通用", "通知", "关于").forEachIndexed { index, title ->
                Tab(
                    selected = currentTab == index,
                    onClick = { onTabChange(index) },
                    text = { Text(title) },
                )
            }
        }
        when (currentTab) {
            0 -> GeneralSettings()
            1 -> NotificationSettings()
            2 -> AboutPage()
        }
    }
}
