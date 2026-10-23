package my.bridge.com.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色：取自应用图标（对话气泡蓝）
val BridgeBlue = Color(0xFF1E88E5)
val BridgeBlueDark = Color(0xFF1565C0)

private val LightColors = lightColorScheme(
    primary = BridgeBlue,
    primaryContainer = Color(0xFFD3E4FF),
    secondary = BridgeBlueDark,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    primaryContainer = BridgeBlueDark,
    secondary = BridgeBlue,
)

/**
 * Bridge 全局主题。
 * 所有 feature 模块统一使用此主题，保证设计系统一致。
 */
@Composable
fun BridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
