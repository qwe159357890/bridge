package my.bridge.com.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「工具」页（v0.2.15x，替换原底部「管理」位置）。
 *
 * 当前为占位页：4 列 × 4 行 = 16 个占位工具入口（图标 + 名称），
 * 点击统一 Toast「功能开发中」，具体功能以后再逐个实现。
 *
 * 返回键：沿用管理后台页范式——第 1 次 Toast「再按一次返回主页」，窗口内第 2 次回主页。
 */
@Composable
fun ToolsPage(onExitToHome: () -> Unit = {}) {
    val context = LocalContext.current

    // 返回键：第 1 次提示、窗口内第 2 次回主页（与销售页/管理后台页一致）
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {
            onExitToHome()
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次返回主页", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部标题栏（与其它页一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "工具",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // 4 列 × 4 行占位宫格
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(ToolPlaceholderItems) { _, item ->
                ToolGridItem(
                    emoji = item.emoji,
                    name = item.name,
                    onClick = {
                        Toast.makeText(context, "「${item.name}」功能开发中", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

/** 占位工具项（图标 emoji + 名称）。 */
private data class ToolPlaceholder(val emoji: String, val name: String)

/** 16 个占位工具入口（具体功能后续逐个实现）。 */
private val ToolPlaceholderItems = listOf(
    ToolPlaceholder("🧮", "计算器"),
    ToolPlaceholder("📝", "记事本"),
    ToolPlaceholder("⏱️", "秒表"),
    ToolPlaceholder("⏰", "闹钟"),
    ToolPlaceholder("📷", "相机"),
    ToolPlaceholder("🎙️", "录音"),
    ToolPlaceholder("🖼️", "相册"),
    ToolPlaceholder("📄", "文件"),
    ToolPlaceholder("🧭", "指南针"),
    ToolPlaceholder("📏", "尺子"),
    ToolPlaceholder("🔦", "手电筒"),
    ToolPlaceholder("🧾", "二维码"),
    ToolPlaceholder("🌐", "翻译"),
    ToolPlaceholder("📡", "网络"),
    ToolPlaceholder("🛠️", "设置"),
    ToolPlaceholder("❓", "更多"),
)

/** 单个占位宫格：圆形图标底 + 名称。 */
@Composable
private fun ToolGridItem(emoji: String, name: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFFEAF1FB), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 26.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            fontSize = 12.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
