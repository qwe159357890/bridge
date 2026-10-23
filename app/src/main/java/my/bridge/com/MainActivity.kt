package my.bridge.com

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import my.bridge.com.core.ui.theme.BridgeTheme
import my.bridge.com.ui.MainScreen

/**
 * 应用主入口。
 * 首次进入时引导用户关闭电池优化（保活最有效手段）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 首次进入引导电池优化白名单（一次性，用户取消后不再打扰）
        // KeepAliveUtils.requestIgnoreBatteryOptimization(this)
        setContent {
            BridgeTheme {
                MainScreen()
            }
        }
    }
}
