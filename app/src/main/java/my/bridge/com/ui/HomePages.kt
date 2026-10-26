package my.bridge.com.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import timber.log.Timber

// ============ 会话列表 ============
// 真实短信会话（读取系统短信库 + 默认短信应用快速切换），实现见 SmsPages.kt。
// 旧版假数据骨架已由 v0.1.34 的内置短信页替换。

// ============ 通讯录 ============
private data class ContactItem(val name: String, val role: String, val icon: String)

private val contacts = listOf(
    ContactItem("张三", "产品经理", "张"),
    ContactItem("李四", "Android 开发", "李"),
    ContactItem("王五", "测试工程师", "王"),
    ContactItem("赵六", "UI 设计师", "赵"),
    ContactItem("孙七", "后端开发", "孙"),
)

@Composable
fun ContactList() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("联系人") }
        items(contacts) { contact ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Timber.d("查看联系人：${contact.name}（${contact.role}）") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(text = contact.icon)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            contact.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ============ 发现 ============
private val discoverItems = listOf("朋友圈", "小程序", "视频号", "直播", "看一看", "搜一搜")

@Composable
fun DiscoverList() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("发现") }
        items(discoverItems) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Timber.d("进入发现：$item") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(text = item.take(1), background = Color(0xFFFEF3E8), foreground = Color(0xFFE8862E))
                    Spacer(Modifier.width(12.dp))
                    Text(item, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ============ 通用组件 ============
@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
fun Avatar(
    text: String,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    foreground: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(background, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, style = MaterialTheme.typography.titleMedium)
    }
}
