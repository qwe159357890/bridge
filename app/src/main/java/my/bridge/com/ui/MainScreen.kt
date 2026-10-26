package my.bridge.com.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 品牌色 / 未选中色（与界面原型 ui-mockup.html 对齐）
private val BrandBlue = Color(0xFF1A6EE8)
private val InactiveColor = Color(0xFF8A919F)

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
            titles = listOf("会话", "通讯录", "发现"),
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

// ============ 设置页（四个二级 Tab） ============
@Composable
fun SettingsPage(currentTab: Int, onTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        BridgeTabRow(
            titles = listOf("通用", "连接", "保活", "关于"),
            currentTab = currentTab,
            onTabChange = onTabChange,
        )
        when (currentTab) {
            0 -> GeneralSettings()
            1 -> ConnectionSettings()
            2 -> KeepAliveSettings()
            3 -> AboutPage()
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
