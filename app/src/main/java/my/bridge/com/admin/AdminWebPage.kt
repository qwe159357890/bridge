package my.bridge.com.admin

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import my.bridge.com.ui.BackPressPrefs

/**
 * 「管理后台」内嵌页（v0.2.125，见 docs/admin/client-embed-admin.md）。
 *
 * 客户端内嵌 WebView 打开管理后台（admin），让用户在手机上就能管理**其他**客户端设备。
 * admin 由 nginx/开发机托管（相对路径自动指向服务端），客户端只存「admin 访问地址」。
 *
 * 交互：
 * - 未配置 URL → 显示地址输入框，填好保存后加载；
 * - 已配置 → 直接加载，顶部常驻「刷新 + 改地址」工具条；
 * - 返回键 → WebView 有后退栈则 goBack，否则退出到上一级（非沉浸，保留底部导航）。
 */
@Composable
fun AdminWebPage(onExitToHome: () -> Unit = {}) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(AdminPrefs.url(context)) }
    var input by remember { mutableStateOf(url) }
    var editing by remember { mutableStateOf(url.isBlank()) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val load: (String) -> Unit = { target ->
        if (AdminPrefs.isValid(target)) {
            AdminPrefs.setUrl(context, target)
            url = target
            input = target
            editing = false
            webView?.loadUrl(target)
        }
    }

    // 返回键链（对齐销售页范式，Compose BackHandler 后进先出）：
    // ① 主界面兜底：第 1 次 Toast「再按一次返回主页」，窗口内第 2 次 → onExitToHome()；
    //    注册在最前 = 优先级最低。
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
    // ② 编辑态 / WebView 后退栈优先接管（注册靠后，优先级更高）。
    BackHandler(enabled = editing || webView?.canGoBack() == true) {
        val wv = webView
        if (editing) {
            editing = false
        } else if (wv != null && wv.canGoBack()) {
            wv.goBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部工具条（已配置 URL 时显示）：标题 + 刷新 + 改地址
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "管理后台",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            if (!editing) {
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Color(0xFF1A6EE8))
                }
                Text(
                    "改地址",
                    fontSize = 13.sp,
                    color = Color(0xFF1A6EE8),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { editing = true },
                )
            }
        }

        // 地址编辑区（未配置或点「改地址」时显示）
        if (editing) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("管理后台地址") },
                    placeholder = { Text("http://192.168.31.226:5173") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { load(input) },
                    enabled = AdminPrefs.isValid(input),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开")
                }
            }
        }

        // WebView 内容区
        if (!editing && url.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            configureSettings(settings)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = false // 站内跳转仍在 WebView 内
                            }
                            webChromeClient = WebChromeClient()
                            webView = this
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (!editing && url.isBlank()) {
            // 未配置 URL 的兜底（正常会先进入 editing 态）
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("请先配置管理后台地址", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }

    // 页面销毁时释放 WebView
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    // ★ 关键：DOM storage 必开，否则 admin 的 localStorage 登录态（bridge_admin_token）
    //   存不住，每次进都要重新登录。
    settings.domStorageEnabled = true
    // 让 viewport 生效，配合 admin 移动端适配（@media max-width:768px）
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    // 支持缩放（移动端看密集表格可能需要）
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}
