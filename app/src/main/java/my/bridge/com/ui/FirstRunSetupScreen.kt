package my.bridge.com.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.ws.WsConfig

/**
 * 首次配置引导页（v0.2.51）。
 *
 * 解决「默认无界面模式 + 不填服务器就连不上 + 连不上就进不了界面填服务器」的死循环：
 * 首次安装（`DeviceStore.serverAddress()` 为 null，从未配置）时强制进入本页，
 * **测试连接成功才放行**进入正式界面；失败留在本页，绝不进入死锁。
 *
 * 本页**不受无界面模式拦截**（属「初始化」而非「无界面」）。
 */
@Composable
fun FirstRunSetupScreen(
    onDone: () -> Unit,
) {
    val viewModel: ConnectionViewModel = hiltViewModel()
    val deviceId by viewModel.deviceId.collectAsStateWithLifecycle()

    // v0.2.130：单框完整 URL（必须带协议），预填生产默认值。
    // 测试环境（本机 npm run dev）改为 http://039039.xyz:15160 或 http://192.168.31.226:15160。
    var address by remember { mutableStateOf(WsConfig.BASE_URL_DEFAULT) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔗", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "首次配置中心服务器",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "首次使用需连接中心服务器（管理后台）后才能使用本应用。\n请填写服务器地址并测试连接，成功后进入主界面。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                error = null
                ok = false
            },
            label = { Text("服务器地址（完整 URL）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            placeholder = { Text("https://appclient.039039.xyz:15443") },
            supportingText = {
                Text("必须带协议。生产：https://appclient.039039.xyz:15443；测试（本机 npm run dev）：http://039039.xyz:15160")
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "设备标识：${deviceId.ifBlank { "读取中…" }}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        error?.let {
            Text(
                it,
                color = androidx.compose.ui.graphics.Color(0xFFE5484D),
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Button(
            onClick = {
                error = null
                testing = true
                ok = false
                // 测试连接：先校验格式，再调 REST registerDevice 探测可达性
                viewModel.testServerConnection(address) { success, msg ->
                    testing = false
                    if (success) {
                        ok = true
                        DiagLog.i("首次配置", "服务器连接测试成功：$address")
                    } else {
                        error = msg
                    }
                }
            },
            enabled = !testing && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).fillMaxWidth().padding(0.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(if (ok) "✅ 连接成功，进入主界面" else "测试连接")
            }
        }
        Spacer(Modifier.height(8.dp))
        // 测试成功后出现「进入主界面」按钮（明确放行动作）
        if (ok) {
            Button(
                onClick = { onDone() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("进入主界面")
            }
        }
    }
}
