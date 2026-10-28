package my.bridge.com.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.data.SmsDefaultHelper
import my.bridge.com.data.SmsReader
import my.bridge.com.data.SmsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 品牌色（与 MainScreen 对齐）
private val BrandBlue = Color(0xFF1A6EE8)
private val BubbleIn = Color(0xFFF1F3F6)
private val UnreadRed = Color(0xFFE5484D)
private val GreenOk = Color(0xFF67C23A)

private val timeFmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
private val timeFmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
private val timeFmtDate = SimpleDateFormat("MM-dd", Locale.getDefault())

/**
 * Bridge 内置短信页（主页「短信」Tab）：短信列表 ↔ 会话详情两级导航。
 *
 * - 读取（READ_SMS）无需默认短信应用身份，随时可浏览；
 * - 顶部状态条支持「设为默认 / 切回原短信应用」快速切换（系统弹窗一次确认，
 *   经 ActivityResultLauncher 拉起并回调结果——裸 startActivity 在部分 ROM 被
 *   静默取消，表现为「点了没反应」）；
 * - Bridge 为默认应用期间，新短信由 SmsDeliverReceiver 写库并通知，本页可见；
 * - 标记已读/未读双向可用：整会话按钮 + 详情页点单条气泡切换
 *   （v0.1.57 起走 Bridge 内部映射 SmsReadStore，无需默认短信应用身份）。
 */
@Composable
fun SmsConversationsPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(SmsReader.hasPermission(context)) }
    var isDefault by remember { mutableStateOf(SmsDefaultHelper.isDefault(context)) }
    var conversations by remember {
        mutableStateOf<List<SmsRepository.Conversation>?>(null)
    }
    var openThread by remember { mutableStateOf<SmsRepository.Conversation?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 当前 launcher 拉起的请求类型：true=设为默认 / false=切回原应用 / null=无
    var pendingRequest by remember { mutableStateOf<Boolean?>(null) }

    // 系统确认框结果回调：按「请求类型 + 实际身份」给反馈，杜绝含糊提示
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val became = SmsDefaultHelper.isDefault(context)
        isDefault = became
        when (pendingRequest) {
            true -> if (became) {
                toast(context, "已设为默认短信应用")
            } else {
                toast(context, "未完成设置（已取消）；标记已读/未读仍可用（内部映射）")
            }
            false -> if (!became) {
                toast(context, "已切回原短信应用")
            } else {
                // 切回未生效（部分 ROM 屏蔽了 ACTION_CHANGE_DEFAULT 确认框，
                // 静默返回）：降级打开系统「默认应用」设置页引导手动切回
                fallbackToDefaultAppsSettings(context)
            }
            null -> Unit
        }
        pendingRequest = null
        refreshKey++
    }

    fun launchRole(intent: android.content.Intent?, becoming: Boolean) {
        if (intent == null) {
            toast(context, if (becoming) "设备不支持设置默认短信应用" else "未记录原短信应用")
            return
        }
        pendingRequest = becoming
        try {
            roleLauncher.launch(intent)
        } catch (e: Exception) {
            pendingRequest = null
            toast(context, "拉起系统设置失败：${e.message}")
        }
    }

    // 加载短信列表（权限就绪时）
    LaunchedEffect(hasPermission, refreshKey) {
        if (!hasPermission) return@LaunchedEffect
        SmsDefaultHelper.recordPreviousDefault(context) // 维护「原短信应用」记忆
        conversations = withContext(Dispatchers.IO) {
            SmsRepository.listConversations(context)
        }
    }

    // 从系统确认弹窗返回时刷新默认身份与列表（切换后立即生效）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = SmsReader.hasPermission(context)
                isDefault = SmsDefaultHelper.isDefault(context)
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统返回键/手势：详情页返回列表，而不是退出应用
    BackHandler(enabled = openThread != null) {
        openThread = null
        refreshKey++ // 返回后刷新未读数
    }

    val thread = openThread
    if (thread != null) {
        ThreadDetailPage(
            thread = thread,
            onBack = {
                openThread = null
                refreshKey++ // 返回后刷新未读数
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {

        // ===== 默认短信应用状态条（快速切换） =====
        DefaultSmsStatusBar(
            isDefault = isDefault,
            hasPermission = hasPermission,
            onSwitch = {
                if (!isDefault) {
                    launchRole(SmsDefaultHelper.becomeDefaultIntent(context), becoming = true)
                } else {
                    val intent = SmsDefaultHelper.restorePreviousIntent(context)
                    if (intent != null) {
                        launchRole(intent, becoming = false)
                    } else {
                        toast(
                            context,
                            "未记录原短信应用，请到 系统设置→应用→默认应用→短信应用 手动切换",
                        )
                    }
                }
            },
        )

        when {
            !hasPermission -> CenterHint("短信权限未授予\n请在 设置→应用→Bridge→权限 中开启「短信」")
            conversations == null -> CenterHint("加载中…")
            conversations!!.isEmpty() -> CenterHint("暂无短信")
            else -> ConversationList(
                conversations = conversations!!,
                onOpen = { openThread = it },
            )
        }
    }
}

private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}

/** 切回原短信应用失败时的降级：打开系统「默认应用」设置页引导手动切换。 */
private fun fallbackToDefaultAppsSettings(context: android.content.Context) {
    try {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        toast(context, "该系统不支持一键切回，请在「默认应用→短信应用」中选回原应用")
    } catch (e: Exception) {
        toast(context, "切回未生效，请到 系统设置→应用→默认应用→短信应用 手动切换")
    }
}

// ============ 状态条 ============
@Composable
private fun DefaultSmsStatusBar(
    isDefault: Boolean,
    hasPermission: Boolean,
    onSwitch: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDefault) Color(0xFFEAF3FF) else Color(0xFFF7F8FA),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📨", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isDefault) "Bridge 是默认短信应用" else "当前默认：系统短信应用",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF303133),
                )
                Text(
                    text = if (isDefault) "新短信由 Bridge 接收"
                    else "标记已读/未读随时可用（Bridge 内部已读映射）",
                    fontSize = 11.sp,
                    color = Color(0xFF8A919F),
                )
            }
            if (hasPermission) {
                Button(
                    onClick = onSwitch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDefault) GreenOk else BrandBlue,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 6.dp,
                    ),
                ) {
                    Text(
                        if (isDefault) "切回原短信" else "设为默认",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ============ 短信列表 ============
@Composable
private fun ConversationList(
    conversations: List<SmsRepository.Conversation>,
    onOpen: (SmsRepository.Conversation) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 4.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(conversations, key = { it.threadId }) { conv ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(conv) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(text = conv.address.take(1).ifBlank { "?" })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                conv.address,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (conv.unreadCount > 0) FontWeight.SemiBold
                                else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatListTime(conv.lastDateMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                conv.lastBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (conv.unreadCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(UnreadRed, RoundedCornerShape(9.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        if (conv.unreadCount > 99) "99+" else "${conv.unreadCount}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 会话详情 ============
@Composable
private fun ThreadDetailPage(
    thread: SmsRepository.Conversation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<SmsRepository.ThreadMessage>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(thread.threadId) {
        messages = withContext(Dispatchers.IO) {
            SmsRepository.listThreadMessages(context, thread.threadId)
        }
    }

    /** 双向标记整会话（read=true 已读 / false 未读），本地乐观更新。
     *  v0.1.57：已读状态由 Bridge 内部映射承载（SmsReadStore），不再要求默认身份。 */
    fun markThread(read: Boolean, current: List<SmsRepository.ThreadMessage>) {
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    SmsRepository.setReadState(
                        context, read, "thread_id = ?", arrayOf(thread.threadId),
                    )
                }
                if (n <= 0) toast(context, "该会话没有可标记的短信")
                messages = current.map {
                    if (it.type == 1) it.copy(read = read) else it
                }
            } catch (e: Exception) {
                toast(context, "标记失败：${e.message}")
            }
        }
    }

    /** 点单条气泡：收到的短信在 已读↔未读 间切换（v0.1.57 起无需默认身份）。 */
    fun toggleMessage(msg: SmsRepository.ThreadMessage, current: List<SmsRepository.ThreadMessage>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SmsRepository.setReadState(context, !msg.read, "_id = ?", arrayOf(msg.id))
                }
                messages = current.map { if (it.id == msg.id) it.copy(read = !msg.read) else it }
            } catch (e: Exception) {
                toast(context, "标记失败：${e.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text(thread.address, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${thread.messageCount} 条消息 · 点消息可切换已读/未读",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val msgList = messages
        when {
            msgList == null -> CenterHint("加载中…")
            msgList.isEmpty() -> CenterHint("该会话暂无消息")
            else -> {
                LaunchedEffect(msgList.size) {
                    if (msgList.isNotEmpty()) listState.scrollToItem(msgList.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(msgList, key = { it.id }) { msg ->
                        MessageBubble(
                            msg = msg,
                            showUnread = msg.type == 1 && !msg.read,
                            onClick = { toggleMessage(msg, msgList) },
                        )
                    }
                }
                // 双向标记按钮（仅默认身份可用；非默认给引导提示）
                MarkActionsBar(
                    canMarkRead = msgList.any { !it.read && it.type == 1 },
                    canMarkUnread = msgList.any { it.read && it.type == 1 },
                    onMarkRead = { markThread(true, msgList) },
                    onMarkUnread = { markThread(false, msgList) },
                )
            }
        }
    }
}

// ============ 双向标记操作条 ============
@Composable
private fun MarkActionsBar(
    canMarkRead: Boolean,
    canMarkUnread: Boolean,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v0.1.57：已读状态走 Bridge 内部映射（SmsReadStore），不再有默认身份门控
        if (canMarkUnread) {
            Button(
                onClick = onMarkUnread,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE6A23C),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp, vertical = 6.dp,
                ),
            ) {
                Text("标记全部未读", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (canMarkRead) {
            Button(
                onClick = onMarkRead,
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp, vertical = 6.dp,
                ),
            ) {
                Text("标记全部已读", fontSize = 12.sp)
            }
        }
    }
}

// ============ 消息气泡 ============
@Composable
private fun MessageBubble(
    msg: SmsRepository.ThreadMessage,
    showUnread: Boolean,
    onClick: () -> Unit,
) {
    val fromMe = msg.type != 1 // 1=收件，其余（已发/失败/队列等）按发出侧展示
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showUnread) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(UnreadRed, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = timeFmtFull.format(Date(msg.dateMs)) +
                    if (showUnread) " · 未读" else "",
                fontSize = 10.sp,
                color = if (showUnread) UnreadRed else Color(0xFFA8ADB8),
                fontWeight = if (showUnread) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (fromMe) BrandBlue else BubbleIn,
                    RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (fromMe) 12.dp else 2.dp,
                        bottomEnd = if (fromMe) 2.dp else 12.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = msg.body,
                color = if (fromMe) Color.White else Color(0xFF303133),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

// ============ 通用 ============
private fun formatListTime(dateMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    val now = java.util.Calendar.getInstance()
    cal.timeInMillis = dateMs
    return if (now.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
        now.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR)
    ) {
        timeFmtTime.format(Date(dateMs))
    } else {
        timeFmtDate.format(Date(dateMs))
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}
