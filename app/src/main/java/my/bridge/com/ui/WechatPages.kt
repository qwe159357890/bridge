package my.bridge.com.ui

import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import my.bridge.com.wechat.WechatAttachment
import my.bridge.com.wechat.WechatDownloader
import my.bridge.com.wechat.WechatPrefs
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import my.bridge.com.wechat.WechatConnState
import my.bridge.com.wechat.WechatContact
import my.bridge.com.wechat.WechatController
import my.bridge.com.wechat.WechatEmoji
import my.bridge.com.wechat.WechatMessage
import my.bridge.com.wechat.WechatMsgType
import my.bridge.com.wechat.WechatSession
import my.bridge.com.wechat.WechatSound
import my.bridge.com.wechat.WechatState
import my.bridge.com.wechat.wechatDedupe

// ============ 主题色（微信绿，不套 Bridge 蓝） ============
private val WechatGreen = Color(0xFF07C160)
private val WechatBubble = Color(0xFF95EC69)
private val WechatSub = Color(0xFF6B7280)
private val WechatRed = Color(0xFFE5484D)
private val WechatOrange = Color(0xFFE6A23C)
private val WechatPageBg = Color(0xFFF5F6F8)
private val WechatBorder = Color(0xFFDCDFE6)

/**
 * 搜索框统一高度（同时也是同行按钮的高度）——微信页所有搜索输入与右侧按钮都用它，保证严格对齐。
 *
 * 原实现用 `OutlinedTextField` + `heightIn(min = 34.dp)`：M3 的 OutlinedTextField 自带
 * **minHeight 56dp** 且 contentPadding 上下各 16dp，`heightIn(min)` 压不下去（最小尺寸约束优先生效），
 * 于是搜索框比右侧按钮高一大截。销售页 v0.2.19 已踩过同一个坑并验证解法：
 * **改用 BasicTextField 自绘边框**，高度与内边距完全可控。
 */
private val WechatFieldHeight = 32.dp

@Composable
private fun WechatSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(WechatGreen),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .height(WechatFieldHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, WechatBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = WechatSub)
                    inner()
                }
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = WechatSub,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}

/**
 * 聊天输入框（v0.2.23）——与 [WechatSearchField] 同理。
 *
 * 原实现是 `OutlinedTextField` + `heightIn(min = 36.dp)`：M3 自带 **minHeight 56dp**，
 * `heightIn(min)` 压不下去（最小尺寸约束优先），所以 v0.2.22 改了搜索框却漏了这里，
 * 真机上「发送消息的输入框高度没调小」。改用 BasicTextField 自绘后高度完全可控。
 */
@Composable
private fun WechatChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(WechatGreen),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) MaterialTheme.colorScheme.surface else WechatPageBg)
            .border(1.dp, WechatBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = WechatSub, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/**
 * 分页列表底部状态条（v0.2.23）：用户要求「加载完了就显示『已经加载全部』」，
 * 以此区分「到顶了」与「卡住了」。
 */
@Composable
private fun WechatListFooter(loading: Boolean, ended: Boolean, count: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WechatSub)
                    Spacer(Modifier.width(6.dp))
                    Text("加载中...", fontSize = 11.sp, color = WechatSub)
                }
            }
            ended -> Text("已经加载全部 · 共 $count 条", fontSize = 11.sp, color = WechatSub)
            else -> Text("滑动到底部加载更多", fontSize = 11.sp, color = WechatSub)
        }
    }
}

/**
 * Tab 点击（含**双击**判定，v0.2.23）：双击页内「聊天」/ 底部「微信」按钮 →
 * 跳到最新一条未读会话（照参考 PC 客户端的「跳未读会话」交互，并播「叮一声」）。
 */
@Composable
internal fun Modifier.tabClickable(
    index: Int,
    onSelect: (Int) -> Unit,
    onDoubleTap: (Int) -> Unit,
): Modifier {
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapIndex by remember { mutableIntStateOf(-1) }
    return clickable {
        val now = System.currentTimeMillis()
        if (lastTapIndex == index && now - lastTapAt < 400) {
            lastTapAt = 0L
            lastTapIndex = -1
            onDoubleTap(index)
        } else {
            lastTapAt = now
            lastTapIndex = index
            onSelect(index)
        }
    }
}

private const val WECHAT_DOWNLOAD_ROOT = "/sdcard/bridge/wechat/"

/** 双击「聊天」后等会话列表重组的时间（LazyListState 未挂载时滚动是空操作）。 */
private const val JUMP_SCROLL_DELAY_MS = 120L

/**
 * 微信页（center-server 集成）——**沉浸页**，照销售页：
 * 进入即隐藏 Bridge 底部导航，页内用**自己的底部 Tab** 做二级导航。
 *
 * 生命周期：进入只**订阅** Runtime（[WechatController.onEnter]），离开只**退订**
 * （[WechatController.onLeave]）——**连接由进程级 WechatRuntime 常驻维护，页面退出不断连**，
 * 这样离开本页仍能实时收消息并通知（实时性是硬需求）。
 *
 * @param onShowBottomBar 显示 Bridge 主导航（页内「⇤」，语义同销售页「返回导航」）
 * @param initialSessionWxid 通知点击带入的会话 wxid（打开后自动进入该会话）
 */
@Composable
fun WechatPage(
    initialSessionWxid: String? = null,
    /**
     * 「打开会话」令牌（v0.2.29）：MainScreen 每次消费一次通知 deep link 就自增。
     *
     * 用它而不是 `initialSessionWxid` 做 `LaunchedEffect` 的 key —— 同一会话连续两条通知时
     * wxid 值不变，key 不变 ⇒ effect 不重启 ⇒ 第二条通知点了没反应（台账 §3 条件 ③）。
     */
    openToken: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = WechatState.state

    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    // 表情面板状态提升到页面级：返回键要能先收起表情面板（优先级高于「关闭会话」）
    var emojiPanel by remember { mutableStateOf(false) }
    // 会话列表状态提升到页面级（v0.2.25）：双击「聊天」要把目标会话**滚到顶部**，
    // 就必须持有列表自己的 LazyListState（页面级才不会因子 Tab 切换而丢失）。
    val sessionListState = rememberLazyListState()
    var sessionKeyword by remember { mutableStateOf("") }
    val sessionList = ui.visibleSessions.filter {
        sessionKeyword.isBlank() ||
            it.userNickname.contains(sessionKeyword, true) ||
            it.lastMsg.contains(sessionKeyword, true)
    }

    // 返回键链（Compose BackHandler 是**后进先出**：注册越靠后优先级越高）：
    // ① 主界面（会话列表）→ 第 1 次冒泡提示「再按一次返回主页」，2 秒内第 2 次 → 回主页；
    // ② 表情面板 → 收起面板；
    // ③ 聊天详情 → 返回会话列表（不必非得点左上角箭头）；
    // ④ 页内 Tab 非「聊天」→ 切回聊天 Tab（通讯录/实例/工具箱/设置）。
    // ① 优先级最低所以注册在最前；②③④ 注册在后，命中时优先接管。
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = openSession == null && subTab == 0) {
        val now = System.currentTimeMillis()
        // v0.2.26：窗口改为设置可调（默认 2 秒）
        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {
            // v0.2.25：不再「显示/隐藏底部导航栏」，直接回 APP 主页（MainScreen 观察该令牌）
            WechatState.requestExitToHome()
        } else {
            lastBackAt = now
            wechatToast(context, "再按一次返回主页")
        }
    }

    // ②③④（注册在 ① 之后 = 优先级更高）：表情面板 / 聊天详情 / 子 Tab
    BackHandler(enabled = ui.configured && (openSession != null || subTab != 0)) {
        when {
            emojiPanel -> emojiPanel = false
            openSession != null -> {
                emojiPanel = false
                openSession = null
                WechatController.closeSession()
            }
            else -> subTab = 0
        }
    }

    // 订阅 Runtime（进入页面一次即可；与下面的 DisposableEffect/onLeave 配对）。
    // ⚠️ 不能和「打开会话」合在同一个 LaunchedEffect 里——否则每次通知都会重复订阅/刷新会话。
    LaunchedEffect(Unit) {
        WechatController.onEnter(context)
    }

    /**
     * v0.2.29：key 用 **openToken**（自增）而不是 wxid —— 见参数注释。
     * token == 0 表示「不是通知点进来的」（手动点底部微信 Tab / Activity 重建后恢复），不自动开会话。
     */
    LaunchedEffect(openToken) {
        if (openToken > 0 && initialSessionWxid != null) {
            DiagLog.i("通知跳转", "打开会话：$initialSessionWxid token=$openToken")
            // 兜底：若上次停在微信页的其它子 Tab（通讯录/实例/设置），先回到「聊天」
            subTab = 0
            openSession = initialSessionWxid
            WechatController.openSession(context, initialSessionWxid)
        }
    }

    /**
     * 双击「聊天」/底部「微信」→ **把目标未读会话滚到列表顶部**（v0.2.25 改为不进详情）。
     *
     * ⚠️ 令牌守卫（修真机问题 #1/#5）：`LaunchedEffect(token)` 在**首次进入组合时也会执行**，
     * 而从销售页切回微信页 = 重新进入组合，令牌还是上次双击留下的非 0 值 ⇒
     * 会莫名其妙弹「没有未读会话」（没未读时）或直接跳进会话详情（有未读时）。
     * 所以必须记一个「已消费令牌」：**只有令牌真的变了**才动作。
     */
    val jumpToken = ui.jumpUnreadToken
    var consumedJumpToken by remember { mutableIntStateOf(ui.jumpUnreadToken) }
    var jumpCursor by remember { mutableIntStateOf(0) }
    LaunchedEffect(jumpToken) {
        if (jumpToken == consumedJumpToken) return@LaunchedEffect
        consumedJumpToken = jumpToken
        val targets = WechatState.state.unreadJumpTargets
        if (targets.isEmpty()) {
            wechatToast(context, "没有未读会话")
            return@LaunchedEffect
        }
        // 每次双击递进到下一条（先非免打扰、后免打扰，见 unreadJumpTargets），到尾回绕
        val pick = targets[jumpCursor % targets.size]
        jumpCursor = (jumpCursor + 1) % targets.size
        sessionKeyword = ""
        subTab = 0
        // 等会话列表重组完成再滚（LazyListState 还没挂到 LazyColumn 时滚动是空操作）
        delay(JUMP_SCROLL_DELAY_MS)
        val pos = WechatState.state.visibleSessions.indexOfFirst { it.userWxid == pick.userWxid }
        if (pos >= 0) sessionListState.animateScrollToItem(pos)
        WechatSound.playJump(context)
    }
    DisposableEffect(Unit) {
        onDispose { WechatController.onLeave() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ① 状态条：只在断线时出现，正常时零占位（高度全部让给内容）
        if (ui.connState == WechatConnState.DISCONNECTED && ui.configured) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(WechatOrange.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("实时连接已断开，正在尝试重连...", fontSize = 12.sp, color = WechatOrange, modifier = Modifier.weight(1f))
                Text("去设置", fontSize = 12.sp, color = WechatOrange, modifier = Modifier.clickable { subTab = 4 })
            }
        }

        // ② 内容区
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (!ui.configured) {
                WechatConnectCard()
            } else {
                when (subTab) {
                    0 -> WechatSessionList(
                        list = sessionList,
                        keyword = sessionKeyword,
                        onKeywordChange = { sessionKeyword = it },
                        listState = sessionListState,
                        onOpen = { wxid ->
                            openSession = wxid
                            WechatController.openSession(context, wxid)
                        },
                    )
                    1 -> WechatContactsPage(onStartChat = { wxid ->
                        subTab = 0
                        openSession = wxid
                        WechatController.openSession(context, wxid)
                    })
                    2 -> WechatInstancesPage()
                    3 -> WechatToolboxPlaceholder()
                    4 -> WechatSettingsPage()
                }
                // 聊天面板：全屏覆盖（含页内底部 Tab，与微信原生一致）
                if (openSession != null) {
                    WechatChatPanel(
                        sessionWxid = openSession!!,
                        emojiPanel = emojiPanel,
                        onEmojiPanelChange = { emojiPanel = it },
                        onBack = {
                            emojiPanel = false
                            openSession = null
                            WechatController.closeSession()
                        },
                    )
                }
            }
        }

        // ③ 页内底部二级 Tab（沉浸时替代 Bridge 底栏）
        if (ui.configured) {
            WechatSubBar(
                current = subTab,
                unread = ui.unreadTotal,
                onSelect = { subTab = it },
            )
        }
    }
}

// ============ 页内底部二级 Tab ============
private data class WechatSubTab(val label: String, val icon: ImageVector)

private val wechatSubTabs = listOf(
    WechatSubTab("聊天", Icons.AutoMirrored.Filled.Chat),
    WechatSubTab("通讯录", Icons.Filled.Contacts),
    WechatSubTab("实例", Icons.Filled.Devices),
    WechatSubTab("工具箱", Icons.Filled.Build),
    WechatSubTab("设置", Icons.Filled.Settings),
)

@Composable
private fun WechatSubBar(
    current: Int,
    unread: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
        Row(modifier = Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            // v0.2.25：去掉最左下角的「⇤ 显示主导航」箭头（用户要求）。
            // 回主页改为：主界面按返回键 → 第 1 次冒泡提示、2 秒内第 2 次回主页。
            wechatSubTabs.forEachIndexed { index, tab ->
                val selected = current == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        // v0.2.23：双击「聊天」→ 跳到最新一条未读会话（令牌由页面消费）
                        .tabClickable(
                            index = index,
                            onSelect = onSelect,
                            onDoubleTap = { WechatState.requestJumpUnread() },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (index == 0 && unread > 0) {
                        BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                            Icon(tab.icon, contentDescription = tab.label, tint = if (selected) WechatGreen else WechatSub, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Icon(tab.icon, contentDescription = tab.label, tint = if (selected) WechatGreen else WechatSub, modifier = Modifier.size(20.dp))
                    }
                    Text(tab.label, fontSize = 10.sp, color = if (selected) WechatGreen else WechatSub)
                }
            }
        }
    }
}

// ============ 连接设置（未配置时） ============
@Composable
private fun WechatConnectCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(my.bridge.com.wechat.WechatPrefs.serverUrl(context).ifBlank { "http://192.168.1.100:15151" }) }
    var key by remember { mutableStateOf(my.bridge.com.wechat.WechatPrefs.apiKey(context)) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("连接中心服务器", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        testing = true
                        error = ""
                        scope.launch {
                            val ok = WechatController.saveConfig(context, url.trim(), key.trim())
                            testing = false
                            error = if (ok) "" else "连接失败，请检查地址与 API Key"
                        }
                    },
                    enabled = !testing,
                    colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (testing) "测试中..." else "测试连接并保存")
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = WechatRed, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("首次使用需填写中心服务器地址与 API Key（参考设置页「连接」语义）。", fontSize = 11.sp, color = WechatSub)
            }
        }
    }
}

// ============ 会话列表 ============
@Composable
private fun WechatSessionList(
    list: List<WechatSession>,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    listState: LazyListState,
    onOpen: (String) -> Unit,
) {
    val ui = WechatState.state
    Column(modifier = Modifier.fillMaxSize().background(WechatPageBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WechatSearchField(
                value = keyword,
                onValueChange = onKeywordChange,
                placeholder = "搜索",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val mode = ui.sessionMode
            Box(
                modifier = Modifier
                    .height(WechatFieldHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WechatGreen.copy(alpha = 0.12f))
                    .clickable { WechatState.setSessionMode(if (mode == "subscription") "friend" else "subscription") }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (mode == "subscription") "公众号" else "好友", fontSize = 11.sp, color = WechatGreen)
            }
        }
        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无会话", fontSize = 13.sp, color = WechatSub)
            }
        } else {
            // listState 由 WechatPage 持有（双击「聊天」要把未读会话滚到顶部）
            // v0.2.24：触底检测改用 snapshotFlow 订阅 layoutInfo，不再用
            // 「derivedStateOf 布尔量 + LaunchedEffect(布尔量)」——后者只在 false→true
            // 的**边沿**触发一次，一旦加载完成后用户仍处于底部（或本页全是重复项、
            // 列表长度没变），布尔量不会再产生新的跳变，就永远不再加载。
            // snapshotFlow 每次布局/滚动都会发射，天然可重复触发。
            val keywordNow by rememberUpdatedState(keyword)
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo }
                    .distinctUntilChanged()
                    .collectLatest { info ->
                        if (keywordNow.isNotBlank()) return@collectLatest
                        if (WechatState.state.loadingMoreSessions || WechatState.state.sessionsEnded) return@collectLatest
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@collectLatest
                        // 列表末尾还挂了一个 footer item（index == list.size），故 -3 提前触发
                        if (last.index >= info.totalItemsCount - 3) {
                            WechatController.loadMoreSessions()
                        }
                    }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.userWxid }) { s ->
                    WechatSessionRow(s, onOpen)
                }
                item {
                    WechatListFooter(
                        loading = ui.loadingMoreSessions,
                        ended = ui.sessionsEnded,
                        count = list.size,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WechatSessionRow(session: WechatSession, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { onOpen(session.userWxid) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v0.2.23：未读除了行尾计数，还要在**头像右上角挂角标**（用户反馈「没有角标」）。
            // 免打扰用灰点（与行尾计数同色），普通用红点。
            if (session.unreadCount > 0) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = if (session.isMuted) Color(0xFFC0C4CC) else WechatRed) {
                            Text(if (session.unreadCount > 99) "99+" else session.unreadCount.toString())
                        }
                    },
                ) {
                    WechatAvatar(wxid = session.userWxid, name = session.userNickname, size = 40.dp)
                }
            } else {
                WechatAvatar(wxid = session.userWxid, name = session.userNickname, size = 40.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session.userNickname, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (session.isPinned) Text(" 置顶", fontSize = 10.sp, color = WechatOrange)
                    if (session.isMuted) Text(" 免打扰", fontSize = 10.sp, color = WechatSub)
                }
                // v0.2.23：`[微笑]` 这类文本表情代码渲染成 emoji
                Text(
                    WechatEmoji.replaceCodes(session.lastMsg),
                    fontSize = 12.sp,
                    color = WechatSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(session.lastTime, fontSize = 11.sp, color = WechatSub)
                if (session.unreadCount > 0) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (session.isMuted) Color(0xFFC0C4CC) else WechatRed)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(if (session.unreadCount > 99) "99+" else session.unreadCount.toString(), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
        // 长按菜单：置顶 / 免打扰 / 清空聊天记录
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (session.isPinned) "取消置顶" else "置顶该会话") },
                onClick = { menu = false; WechatController.togglePin(context, session.userWxid, !session.isPinned) },
            )
            DropdownMenuItem(
                text = { Text(if (session.isMuted) "取消免打扰" else "设为免打扰") },
                onClick = { menu = false; WechatController.toggleMute(context, session.userWxid, !session.isMuted) },
            )
            DropdownMenuItem(
                text = { Text("清空聊天记录", color = WechatRed) },
                onClick = { menu = false; WechatController.clearSession(context, session.userWxid) },
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF2F3F5)))
}

// ============ 聊天面板 ============
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WechatChatPanel(
    sessionWxid: String,
    emojiPanel: Boolean,
    onEmojiPanelChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = WechatState.state.sessions.firstOrNull { it.userWxid == sessionWxid }
    val messages = remember(sessionWxid) { mutableStateListOf<WechatMessage>() }
    var input by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msgMenu by remember { mutableStateOf<WechatMessage?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = rememberLazyListState()
    val canSend = WechatState.state.currentWxid.isNotBlank()
    // v0.2.26：折叠「同一次发送的双写记录」的兜底开关（默认关 = 已撤回，见 wechatDedupe）
    val foldSentDup = my.bridge.com.wechat.WechatPrefs.dedupeSent(context)

    // 分页状态：loadingMore 防重入，noMore 标记已到最早一条
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember(sessionWxid) { mutableStateOf(false) }

    /**
     * 拉取更早的消息并**插入到列表顶部**，同时保持视口不跳动。
     *
     * 关键：服务端 SQL 是 `ORDER BY timestamp DESC, id DESC`（最新在前），
     * 所以①首次加载必须 `asReversed()` 转成「最早在前、最新在后」的微信习惯顺序，
     * ②翻页时 offset 越大越早，取回后同样反转再插到顶部。
     */
    suspend fun loadEarlier() {
        if (loadingMore || noMore) return
        loadingMore = true
        val anchorIndex = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val more = WechatController.loadMoreMessages(context, sessionWxid, messages.size)
        if (more.isEmpty()) {
            noMore = true
            wechatToast(context, "没有更多了")
        } else {
            // v0.2.26：翻页也走同一去重口径（折叠开关见设置项「自家发送去重」）
            val before = messages.toList()
            val merged = (more.asReversed() + before).wechatDedupe(foldSent = foldSentDup)
            val inserted = merged.size - before.size
            messages.clear()
            messages.addAll(merged)
            // 顶部插入 N 条后，原锚点下移 N，回滚过去避免内容跳走
            listState.scrollToItem(anchorIndex + inserted, anchorOffset)
        }
        loadingMore = false
    }

    /**
     * 发送成功后**等服务端落库再合并真实消息**，不要立刻插本地占位气泡。
     *
     * v0.2.24 修真机问题 #5「发一张图，对方/本端会话里出现两条」：
     * 占位气泡的 key 是 `msgId=""` + `id=0`，服务端真实消息带真实 `msg_id`/`id`，
     * 于是 [LaunchedEffect(curVersion)] 的合并逻辑把它判成「新消息」又追加一条 ⇒
     * 一条「[文件] xxx」占位 + 一条真实图片。真机微信只有一条。
     *
     * 做法：先轮询拉最新消息（微信 hook 回写有几百毫秒延迟），
     * 拿到自己发的真实消息就直接展示它；超过 ~4s 仍拿不到才退回占位气泡。
     */
    suspend fun afterSend(placeholder: String) {
        repeat(8) {
            delay(500)
            val latest = WechatController.loadMessages(context, sessionWxid).asReversed()
            val before = messages.toList()
            // v0.2.26：折叠「同一次发送的双写记录」改为**可选**（默认关，见 wechatDedupe 注释）
            val merged = (before + latest).wechatDedupe(foldSent = foldSentDup)
            // 只认「服务端新落库的自己发的消息」（占位气泡 msgId 为空，不算）
            if (merged.any { it.isSelf && it.msgId.isNotBlank() && it !in before }) {
                messages.clear()
                messages.addAll(merged)
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                return
            }
        }
        // 兜底：服务端迟迟没落库（如 hook 不回写自己发的消息）时才用占位气泡，
        // 保证用户至少能看到自己发的内容
        messages.add(selfMessage(sessionWxid, placeholder))
        listState.animateScrollToItem(messages.lastIndex)
    }

    // 会话内收到新消息：把服务端最新一批合并进列表（去重后追加到底部并滚到最新）。
    // 原先只更新会话列表摘要、详情列表纹丝不动，导致「列表显示了新消息，点进去却看不到」。
    var lastVersion by remember(sessionWxid) { mutableIntStateOf(WechatState.state.messageVersion) }
    val curVersion = WechatState.state.messageVersion
    LaunchedEffect(curVersion) {
        if (curVersion == lastVersion) return@LaunchedEffect
        lastVersion = curVersion
        val latest = WechatController.loadMessages(context, sessionWxid).asReversed()
        val before = messages.toList()
        val merged = (before + latest).wechatDedupe(foldSent = foldSentDup)
        if (merged.any { it !in before }) {
            messages.clear()
            messages.addAll(merged)
            listState.scrollToItem(messages.lastIndex)
        }
    }

    // 进入（或切换到）会话时重新拉取：服务端最新消息一定在最后一批，反转后位于列表底部
    LaunchedEffect(sessionWxid) {
        noMore = false
        messages.clear()
        messages.addAll(WechatController.loadMessages(context, sessionWxid).asReversed().wechatDedupe(foldSent = foldSentDup))
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    // 滚到顶部自动加载更早的消息（原先只有手动按钮，下滑到底没有更多就是这个原因）
    val atTop by remember {
        derivedStateOf { messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1 }
    }
    LaunchedEffect(atTop) {
        if (atTop) loadEarlier()
    }

    DisposableEffect(sessionWxid) {
        // 页面级资源：离开会话停掉语音播放（连接不动，那是进程级的事）
        onDispose { WechatController.stopVoice() }
    }

    /** 系统选择器选中的附件 → multipart 上传发送（手机端唯一可用路径）。 */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readUriBytes(context, uri) }
            val name = uriDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            if (bytes == null) {
                wechatToast(context, "读取文件失败")
                sending = false
                return@launch
            }
            val r = WechatController.sendFile(context, sessionWxid, name, mimeOfName(name), bytes)
            wechatToast(context, if (r.isSuccess) "已发送" else "发送失败：${r.exceptionOrNull()?.message}")
            if (r.isSuccess) {
                // v0.2.24：等服务端真实消息回来再展示（见 afterSend 注释）——
                // 直接插占位气泡会让同一张图显示两条
                afterSend("[文件] $name")
                WechatController.refreshSessions()
            }
            sending = false
        }
    }

    /** 视频/文件：先保存到 `/sdcard/bridge/wechat/...`，再用系统应用打开。 */
    val openAttachment: (WechatMessage, WechatAttachment) -> Unit = { m, att ->
        scope.launch {
            val isVideo = m.msgType == WechatMsgType.VIDEO
            val obj = if (isVideo) att.moviePath.ifBlank { att.movieCover } else att.filePath.ifBlank { att.imagePath }
            if (obj.isBlank()) {
                wechatToast(context, "附件不可用")
                return@launch
            }
            val kind = if (isVideo) WechatDownloader.Kind.VIDEO else WechatDownloader.Kind.FILE
            val name = att.fileName.ifBlank {
                "wechat_${m.msgId.ifBlank { System.currentTimeMillis().toString() }}" + if (isVideo) ".mp4" else ""
            }
            val r = WechatController.saveAttachment(context, obj, name, kind)
            if (r.isFailure) {
                wechatToast(context, "下载失败：${r.exceptionOrNull()?.message}")
                return@launch
            }
            val uri = r.getOrNull() ?: return@launch
            wechatToast(context, "已保存到 ${WechatDownloader.logicalPath(kind, name)}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isVideo) "video/mp4" else "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { wechatToast(context, "没有可打开该文件的应用") }
        }
    }

    val playVoice: (WechatMessage, WechatAttachment) -> Unit = { m, att ->
        if (att.voicePath.isBlank()) {
            wechatToast(context, "语音不可用")
        } else {
            scope.launch {
                WechatController.toggleVoice(context, m.msgId.ifBlank { System.currentTimeMillis().toString() }, att.voicePath)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(WechatPageBg)) {
        // 标题栏
        Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    Text(session?.userNickname ?: sessionWxid, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    if (session?.isMuted == true) Text("免打扰", fontSize = 11.sp, color = WechatSub)
                    IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                }
                DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (session?.isPinned == true) "取消置顶" else "置顶该会话") },
                        onClick = { topMenu = false; session?.let { WechatController.togglePin(context, it.userWxid, !it.isPinned) } },
                    )
                    DropdownMenuItem(
                        text = { Text(if (session?.isMuted == true) "取消免打扰" else "设为免打扰") },
                        onClick = { topMenu = false; session?.let { WechatController.toggleMute(context, it.userWxid, !it.isMuted) } },
                    )
                    DropdownMenuItem(
                        text = { Text("清空聊天记录", color = WechatRed) },
                        onClick = { topMenu = false; WechatController.clearSession(context, sessionWxid); messages.clear() },
                    )
                }
            }
        }

        // 消息区
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (noMore) "没有更早的消息了" else if (loadingMore) "加载中..." else "加载更早的消息",
                        fontSize = 11.sp,
                        color = WechatSub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE9EBEF))
                            .clickable { scope.launch { loadEarlier() } }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            items(messages) { m ->
                if (m.msgType == WechatMsgType.SYSTEM || m.msgType == WechatMsgType.SYSTEM_NOTICE) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(m.content, fontSize = 11.sp, color = WechatSub, modifier = Modifier.padding(vertical = 4.dp))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (m.isSelf) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (!m.isSelf) {
                            WechatAvatar(wxid = m.senderWxid.ifBlank { m.accountWxid }, name = m.senderNickname.ifBlank { sessionWxid }, size = 34.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Column(
                            horizontalAlignment = if (m.isSelf) Alignment.End else Alignment.Start,
                            modifier = Modifier.widthIn(max = 250.dp),
                        ) {
                            if (!m.isSelf && m.senderNickname.isNotBlank()) {
                                Text(m.senderNickname, fontSize = 10.sp, color = WechatSub, modifier = Modifier.padding(bottom = 2.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (m.isSelf) WechatBubble else Color.White)
                                    .combinedClickable(onClick = {}, onLongClick = { msgMenu = m })
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                WechatMessageContent(
                                    msg = m,
                                    onOpenImage = { obj, name -> viewer = obj to name },
                                    onPlayVoice = playVoice,
                                    onOpenFile = openAttachment,
                                )
                            }
                        }
                        if (m.isSelf) {
                            Spacer(Modifier.width(6.dp))
                            WechatAvatar(wxid = m.accountWxid, name = "我", size = 34.dp)
                        }
                    }
                }
            }
        }

        // 表情面板（v0.2.23）：表情全集照参考项目 WechatEmoji（148 条），可滚动网格。
        // 点击插入的是 **emoji 字符**（参考前端 handleEmojiSelect 取 emoji 而非 code）。
        if (emojiPanel) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(Color(0xFFF7F8FA)),
            ) {
                Text(
                    "微信表情 · 共 ${WechatEmoji.list.size} 个",
                    fontSize = 11.sp,
                    color = WechatSub,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
                val rows = remember { WechatEmoji.list.chunked(WechatEmoji.COLUMNS) }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    items(rows) { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { (_, e) ->
                                Text(
                                    e,
                                    fontSize = 22.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { input += e }
                                        .padding(vertical = 6.dp),
                                )
                            }
                            // 末行不满 8 个时补空位，保持网格对齐
                            repeat(WechatEmoji.COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // 输入栏：☺ ＋ [输入框] [账号] [发送]
        Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onEmojiPanelChange(!emojiPanel) }) {
                    Text("☺", fontSize = 20.sp, color = WechatSub)
                }
                Box {
                    IconButton(onClick = { attachMenu = true }, enabled = canSend && !sending) {
                        Icon(Icons.Filled.Add, contentDescription = "附件", tint = if (canSend) WechatSub else Color(0xFFC0C4CC))
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("发送图片") }, onClick = { attachMenu = false; picker.launch("image/*") })
                        DropdownMenuItem(text = { Text("发送视频") }, onClick = { attachMenu = false; picker.launch("video/*") })
                        DropdownMenuItem(text = { Text("发送文件") }, onClick = { attachMenu = false; picker.launch("*/*") })
                    }
                }
                // v0.2.23：换自绘 BasicTextField（M3 OutlinedTextField 的 56dp 压不下来）
                WechatChatInputField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = if (canSend) "请输入消息..." else "请先选择微信账号",
                    enabled = canSend,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                WechatAccountChip()
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isBlank()) return@IconButton
                        input = ""
                        scope.launch {
                            val r = WechatController.sendText(context, sessionWxid, text)
                            if (r.isSuccess) {
                                // v0.2.24：等服务端真实消息回来再展示（见 afterSend 注释）
                                afterSend(text)
                                WechatController.refreshSessions()
                            } else {
                                wechatToast(context, r.exceptionOrNull()?.message ?: "发送失败")
                            }
                        }
                    },
                    enabled = input.isNotBlank() && canSend,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = if (input.isNotBlank() && canSend) WechatGreen else Color(0xFFC0C4CC))
                }
            }
            if (sending) {
                Text("正在发送...", fontSize = 11.sp, color = WechatSub, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            }
        }
    }

    // 消息长按操作：复制文本 / 删除消息
    msgMenu?.let { mm ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { msgMenu = null },
            title = { Text("消息操作") },
            text = {
                Column {
                    if (mm.content.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(mm.content))
                                    msgMenu = null
                                    wechatToast(context, "已复制")
                                }
                                .padding(vertical = 12.dp),
                        ) { Text("复制文本", fontSize = 14.sp) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                msgMenu = null
                                if (mm.msgId.isBlank()) {
                                    messages.remove(mm)
                                    return@clickable
                                }
                                scope.launch {
                                    val r = WechatController.deleteMessage(context, sessionWxid, mm.msgId)
                                    if (r.isSuccess) {
                                        messages.remove(mm)
                                        WechatController.refreshSessions()
                                    } else {
                                        wechatToast(context, "删除失败：${r.exceptionOrNull()?.message}")
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                    ) { Text("删除消息", fontSize = 14.sp, color = WechatRed) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { msgMenu = null }) { Text("关闭") }
            },
        )
    }

    // 大图查看器（保存用）
    viewer?.let { (obj, name) ->
        WechatImageViewer(
            objectName = obj,
            fileName = name,
            onDismiss = { viewer = null },
            onSave = { o, n -> WechatController.saveAttachment(context, o, n, WechatDownloader.Kind.IMAGE) },
        )
    }
}

/** 本地回显一条「我发出的」消息（服务端回执前先上屏，避免用户以为没发出去）。 */
private fun selfMessage(sessionWxid: String, content: String): WechatMessage = WechatMessage(
    id = System.currentTimeMillis(),
    sessionId = sessionWxid,
    accountWxid = WechatState.state.currentWxid,
    msgId = "",
    msgType = WechatMsgType.TEXT,
    content = content,
    attachmentJson = "",
    createTime = "",
    timestamp = System.currentTimeMillis() / 1000,
    isSelf = true,
    senderWxid = WechatState.state.currentWxid,
    senderNickname = "我",
)

// v0.2.23：表情全集移到 wechat/WechatEmoji.kt（照参考项目 148 条），此处不再自带。

/** 账号选择（原顶部账号行移到这里）：显示当前账号序号，点击弹菜单切换。 */
@Composable
private fun WechatAccountChip() {
    val context = LocalContext.current
    val ui = WechatState.state
    val inst = ui.currentInstance
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (inst != null) WechatGreen else WechatRed.copy(alpha = 0.08f))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (inst != null) inst.accountNumber.toString() else "?",
                color = if (inst != null) Color.White else WechatRed,
                fontSize = 12.sp,
            )
        }
        if (expanded) {
            // 账号菜单：照参考「未选择账号时禁用输入」的语义（no-instance 红虚线）
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text("选择微信账号") },
                text = {
                    Column {
                        if (ui.agents.isEmpty()) {
                            Text("暂无可用实例（agent 离线？）", fontSize = 12.sp, color = WechatSub)
                        }
                        ui.agents.forEach { a ->
                            a.instances.forEach { i ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = false
                                            WechatController.selectAccount(context, i.wxid)
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("[${i.accountNumber}] ${i.nickname}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    if (i.wxid == ui.currentWxid) Text("✓", color = WechatGreen)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { expanded = false }) { Text("关闭") }
                },
            )
        }
    }
}

private fun summaryOf(m: WechatMessage): String = when (m.msgType) {
    WechatMsgType.IMAGE -> "[图片]"
    WechatMsgType.VIDEO -> "[视频]"
    WechatMsgType.VOICE -> "[语音]"
    WechatMsgType.EMOJI -> "[表情]"
    WechatMsgType.LOCATION -> "[位置]"
    WechatMsgType.XML -> "[应用消息]"
    else -> m.content.ifBlank { "[消息]" }
}

// ============ 通讯录 ============
@Composable
private fun WechatContactsPage(onStartChat: (String) -> Unit) {
    val context = LocalContext.current
    val ui = WechatState.state
    val filters = listOf("all" to "全部", "has_session" to "会话", "person" to "个人", "group" to "群聊", "subscription" to "公众号")
    LaunchedEffect(ui.contactsFilter) { WechatController.refreshContacts() }
    Column(modifier = Modifier.fillMaxSize().background(WechatPageBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filters.forEach { (k, label) ->
                val on = ui.contactsFilter == k
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) WechatGreen.copy(alpha = 0.12f) else WechatPageBg)
                        .clickable { WechatController.setContactsFilter(context, k) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(label, fontSize = 12.sp, color = if (on) WechatGreen else WechatSub)
                }
            }
        }
        val keyword = remember { mutableStateOf("") }
        WechatSearchField(
            value = keyword.value,
            onValueChange = { keyword.value = it },
            placeholder = "搜索联系人",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        )
        val list = ui.contacts.filter {
            keyword.value.isBlank() || it.nickname.contains(keyword.value, true) || it.wxid.contains(keyword.value, true)
        }
        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无联系人", fontSize = 13.sp, color = WechatSub) }
        } else {
            val listState = rememberLazyListState()
            // v0.2.24：同会话列表——触底检测改 snapshotFlow（布尔量边沿触发会漏）
            val keywordNow by rememberUpdatedState(keyword.value)
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo }
                    .distinctUntilChanged()
                    .collectLatest { info ->
                        if (keywordNow.isNotBlank()) return@collectLatest
                        if (WechatState.state.loadingMoreContacts || WechatState.state.contactsEnded) return@collectLatest
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@collectLatest
                        if (last.index >= info.totalItemsCount - 3) {
                            WechatController.loadMoreContacts()
                        }
                    }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.wxid }) { c -> WechatContactRow(c, onStartChat) }
                item {
                    WechatListFooter(
                        loading = ui.loadingMoreContacts,
                        ended = ui.contactsEnded,
                        count = list.size,
                    )
                }
            }
        }
    }
}

@Composable
private fun WechatContactRow(contact: WechatContact, onStartChat: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .clickable { onStartChat(contact.wxid) }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WechatAvatar(wxid = contact.wxid, name = contact.nickname, size = 34.dp, radius = 4.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(contact.nickname, fontSize = 14.sp)
            Text("${contact.typeLabel} · ${contact.wxid}", fontSize = 11.sp, color = WechatSub)
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF2F3F5)))
}

// ============ 实例（账号上下文） ============
@Composable
private fun WechatInstancesPage() {
    val context = LocalContext.current
    val ui = WechatState.state
    LazyColumn(modifier = Modifier.fillMaxSize().background(WechatPageBg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ui.agents, key = { it.agentId }) { a ->
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Column {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.agentId, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (a.isConnected) "在线" else "离线", fontSize = 10.sp, color = if (a.isConnected) WechatGreen else WechatSub)
                    }
                    a.instances.forEach { i ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (i.wxid == ui.currentWxid) WechatGreen.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable { WechatController.selectAccount(context, i.wxid) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)).background(WechatGreen), contentAlignment = Alignment.Center) {
                                Text(i.accountNumber.toString(), color = Color.White, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("[${i.accountNumber}] ${i.nickname}", fontSize = 14.sp)
                                Text("PID: ${i.pid} | 端口: ${i.port}", fontSize = 11.sp, color = WechatSub)
                            }
                            if (i.wxid == ui.currentWxid) Text("✓", color = WechatGreen, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ============ 工具箱（本期占位） ============
@Composable
private fun WechatToolboxPlaceholder() {
    val planned = listOf(
        "远程打印" to "文件工具", "二维码" to "文件工具", "剪切板同步" to "文件工具",
        "抖一抖" to "交互工具", "重启代理" to "交互工具",
        "拍照" to "查询工具", "流式录音" to "查询工具", "流式录屏" to "查询工具", "GPS 位置" to "查询工具", "短信查询" to "查询工具",
        "JSON" to "编程工具", "Base64" to "编程工具", "时间戳" to "编程工具", "JWT" to "编程工具",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().background(WechatPageBg).padding(12.dp)) {
        item {
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("工具箱（本期仅占位）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "参考项目共 30+ 工具；其中拍照/流式录音录像/短信/GPS 与 Bridge 现有命令重复，" +
                            "编程/生活类与定位无关——本期不实现，仅保留入口，后续按需再开。",
                        fontSize = 11.sp, color = WechatSub,
                    )
                    Spacer(Modifier.height(10.dp))
                    planned.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (name, _) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFAFBFC))
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(name, fontSize = 11.sp, color = Color(0xFFB0B6C0))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 页内设置 ============
@Composable
private fun WechatSettingsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = WechatState.state
    // Android 13+ 通知权限运行时申请
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        wechatToast(context, if (granted) "通知权限已授予" else "通知权限被拒绝")
    }
    // 提示音电平（v0.2.24，照快递扫码 ScanPrefs.volume）：本地状态 + 落盘
    val volumeState = remember { mutableStateOf(my.bridge.com.wechat.WechatPrefs.soundVolume(context)) }
    // v0.2.26：「自家发送去重」兜底开关（默认关——v0.2.25 的无条件折叠已撤回）
    var dedupeSentState by remember { mutableStateOf(my.bridge.com.wechat.WechatPrefs.dedupeSent(context)) }
    LazyColumn(modifier = Modifier.fillMaxSize().background(WechatPageBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsCard("服务器连接") {
                var editing by remember { mutableStateOf(false) }
                var urlEdit by remember { mutableStateOf(ui.serverUrl) }
                var keyEdit by remember { mutableStateOf(WechatPrefs.apiKey(context)) }
                var testing by remember { mutableStateOf(false) }
                var err by remember { mutableStateOf("") }
                if (!editing) {
                    // 状态细分（v0.2.22）：原先只判 CONNECTED，其余一律显示「未连接」，
                    // 于是 REST 已能拉到聊天内容、但 WS 还在 CONNECTING/未连上时，
                    // 用户看到「未连接」会误判为显示错误。现在把三种情况分开讲清楚。
                    val dataOk = ui.sessions.isNotEmpty() || ui.contacts.isNotEmpty()
                    val (stateText, stateColor) = when (ui.connState) {
                        WechatConnState.CONNECTED -> "实时推送已连接" to WechatGreen
                        WechatConnState.CONNECTING ->
                            if (dataOk) "数据已连通 · 实时推送连接中" to WechatOrange
                            else "连接中..." to WechatOrange
                        else ->
                            if (dataOk) "数据已连通 · 实时推送未连接" to WechatOrange
                            else "未连接" to WechatRed
                    }
                    Text("地址：" + ui.serverUrl, fontSize = 12.sp)
                    Text("实时通道：" + WechatController.wsEndpoint(context), fontSize = 11.sp, color = WechatSub)
                    Text("状态：" + stateText, fontSize = 12.sp, color = stateColor)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { editing = true; urlEdit = ui.serverUrl; keyEdit = WechatPrefs.apiKey(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                        ) { Text("修改服务器", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                WechatController.reconnectNow(context)
                                wechatToast(context, "正在重新连接...")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatGreen.copy(alpha = 0.75f)),
                        ) { Text("重新连接", fontSize = 12.sp) }
                    }
                } else {
                    OutlinedTextField(value = urlEdit, onValueChange = { urlEdit = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = keyEdit, onValueChange = { keyEdit = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                testing = true
                                err = ""
                                scope.launch {
                                    val ok = WechatController.testConfig(context, urlEdit.trim(), keyEdit.trim())
                                    testing = false
                                    if (ok) {
                                        editing = false
                                        wechatToast(context, "已保存并重新连接")
                                    } else {
                                        err = "连接失败，请检查地址与 API Key"
                                    }
                                }
                            },
                            enabled = !testing,
                            colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                        ) { Text(if (testing) "测试中..." else "测试并保存", fontSize = 12.sp) }
                        Button(
                            onClick = { editing = false; err = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0C4CC)),
                        ) { Text("取消", fontSize = 12.sp) }
                    }
                    if (err.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(err, fontSize = 11.sp, color = WechatRed)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("连接由进程级 Runtime 常驻维护，离开本页不中断（实时性是硬需求）。", fontSize = 11.sp, color = WechatSub)
            }
        }
        item {
            // 退出微信（v0.2.22）：默认常驻不释放，用户可主动释放全部微信资源。
            // 与销售/智能控制/快递扫码的 stop() 彻底释放模式一致：断 WS、关连接池与线程池、
            // 停语音、清内存数据。退出期间收不到消息，再次进入微信页自动重连。
            SettingsCard("退出微信") {
                var confirm by remember { mutableStateOf(false) }
                Text(
                    "默认常驻后台以保证实时收消息。点「退出微信」将释放微信占用的全部资源" +
                        "（连接、HTTP 连接池与线程池、语音、缓存数据），退出期间收不到消息；" +
                        "再次进入微信页会自动重连。",
                    fontSize = 11.sp,
                    color = WechatSub,
                )
                if (ui.released) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前：已退出，后台不再接收消息",
                            fontSize = 12.sp,
                            color = WechatOrange,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                WechatController.onEnter(context)
                                wechatToast(context, "正在重新连接...")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                        ) { Text("重新连接", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!confirm) {
                    Button(
                        onClick = { confirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = WechatRed),
                    ) { Text("退出微信", fontSize = 12.sp) }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("确认退出？", fontSize = 12.sp, color = WechatRed)
                        Button(
                            onClick = {
                                WechatController.logout(context)
                                confirm = false
                                wechatToast(context, "已退出微信，再次进入将自动重连")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatRed),
                        ) { Text("确认退出", fontSize = 12.sp) }
                        Button(
                            onClick = { confirm = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0C4CC)),
                        ) { Text("取消", fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            SettingsCard("通知权限") {
                val granted = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                Text(
                    if (granted) "系统通知已开启" else "系统通知未开启：后台收到消息时不会弹通知（连接与未读照常）",
                    fontSize = 12.sp,
                    color = if (granted) WechatGreen else WechatRed,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + context.packageName))
                            runCatching { context.startActivity(i) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                    ) { Text("去系统设置", fontSize = 12.sp) }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !granted) {
                        Button(
                            onClick = { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatGreen.copy(alpha = 0.75f)),
                        ) { Text("申请权限", fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            SettingsCard("本地缓存") {
                Text("图片/语音缓存在内存与 App 缓存目录，不占用户可见空间。", fontSize = 11.sp, color = WechatSub)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { WechatController.clearCache(); wechatToast(context, "已清空缓存") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0C4CC)),
                ) { Text("清空缓存", fontSize = 12.sp) }
            }
        }
        item {
            SettingsCard("后台接收与提醒") {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("后台实时接收", fontSize = 13.sp)
                        Text("硬需求：常开，不提供关闭入口", fontSize = 11.sp, color = WechatSub)
                    }
                    Text("常开", fontSize = 12.sp, color = WechatGreen, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("断线自动重连", fontSize = 13.sp)
                        Text("固定 2s · 网络恢复即刻重连", fontSize = 11.sp, color = WechatSub)
                    }
                    Text("常开", fontSize = 12.sp, color = WechatGreen, fontWeight = FontWeight.SemiBold)
                }
                SwitchRow("新消息通知", ui.notifyEnabled) { WechatController.setNotifyEnabled(context, it) }
                SwitchRow("消息提示音", ui.soundEnabled) { WechatController.setSoundEnabled(context, it) }
                // v0.2.25：公众号默认**静默**（照参考项目 web/PC/外部端）——入库、更新列表与未读
                // 照常，只是不弹通知、不响提示音；打开后就是「与好友消息一致」的提醒行为。
                SwitchRow("公众号消息提醒", ui.officialNotify) { WechatController.setOfficialNotify(context, it) }
                Text(
                    "关闭时：公众号新消息照常进列表、照常计未读，但不弹通知、不响提示音（参考项目默认口径）。",
                    fontSize = 11.sp,
                    color = WechatSub,
                )
                // v0.2.24：照快递扫码 ScanFeedback 的「提示音音量」滑杆——
                // 音源已是 0 dBFS 满幅、SoundPool 播放系数默认 1.0（App 侧上限），
                // 这里是与系统媒体音量相乘的唯一可调项（默认 100% = 拉满）。
                val volume = volumeState.value
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("提示音音量", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${(volume * 100).toInt()}%", fontSize = 12.sp, color = WechatSub)
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        volumeState.value = it
                        my.bridge.com.wechat.WechatPrefs.setSoundVolume(context, it)
                    },
                    enabled = ui.soundEnabled,
                    valueRange = 0f..1f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { WechatSound.play(context) },
                        enabled = ui.soundEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = WechatGreen),
                    ) { Text("试听", fontSize = 12.sp) }
                    Text(
                        "实际音量 = 本系数 × 系统媒体音量（系统音量 App 无法越过）",
                        fontSize = 11.sp,
                        color = WechatSub,
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow("全局静音（只关声音）", ui.muteAll) { WechatController.setMuteAll(context, it) }
                // v0.2.26：默认关闭——发图出现两条是 **Agent 端 upload_and_send 缺 EXIF 去重标志**
                // 造成的双写（PC 客户端走 send_image 会写标志，故只有一条），根治应改 Agent。
                // 这里留作未修 Agent 的机器上的应急开关。
                SwitchRow("自家发送去重（服务端双写兜底）", dedupeSentState) {
                    dedupeSentState = it
                    my.bridge.com.wechat.WechatPrefs.setDedupeSent(context, it)
                }
                Text(
                    "重复记录来自 Agent 端发送接口的双写（PC 客户端不会）。默认关闭；若发一张图出现两条，可临时打开。",
                    fontSize = 11.sp,
                    color = WechatSub,
                )
            }
        }
        item {
            SettingsCard("附件保存位置") {
                listOf("images", "videos", "voice", "files").forEach {
                    Text(WECHAT_DOWNLOAD_ROOT + it, fontSize = 11.sp, color = Color(0xFF374151))
                }
                Spacer(Modifier.height(6.dp))
                Text("Android 10+ 分区存储下用 MediaStore / 公共 Download 实现同一逻辑路径；用户主动下载的附件默认不自动清理。", fontSize = 11.sp, color = WechatSub)
            }
        }
        item {
            SettingsCard("WebSocket 参数（v0.2.21 起已放宽，照心跳台账 §8）") {
                ParamRow("心跳间隔", "60000 ms")
                ParamRow("Pong 超时", "30000 ms")
                ParamRow("重连间隔", "固定 2000 ms")
            }
        }
        item {
            SettingsCard("说明") {
                Text("参考项目的 H5+ 保活（通知栏/CPU·WiFi 锁/蜂鸣/隐藏最近任务）全部不移植，Bridge 已有 core-keepalive。", fontSize = 11.sp, color = WechatSub)
                Spacer(Modifier.height(4.dp))
                Text("后台收消息不新增前台服务：复用既有保活进程，避免触碰崩溃台账 §5 的高危面。", fontSize = 11.sp, color = WechatSub)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ParamRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = WechatSub, textAlign = TextAlign.End)
    }
}

// ============ 工具 ============

private fun wechatToast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/** 读取选择器返回的 Uri 字节（图片/视频/文件发送用）。 */
private suspend fun readUriBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

/** 取 Uri 的显示名（文件名）。 */
private fun uriDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

/** 按扩展名猜 MIME（上传 multipart 的 part type）。 */
private fun mimeOfName(name: String): String = when {
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".gif", true) -> "image/gif"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".mp4", true) -> "video/mp4"
    name.endsWith(".amr", true) -> "audio/amr"
    name.endsWith(".mp3", true) -> "audio/mpeg"
    else -> "application/octet-stream"
}
