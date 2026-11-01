# v0.2.26 微信页第五批真机问题（6 项）变更台账

> 用途：任何改动**先逐字记录改前原文 → 再动手 → 回填改后**，确保出错时能 100% 完美恢复。
> 模板见 `docs/dev/heartbeat-inventory.md` §8；相关历史：`wechat-fix-ledger-v0.2.23/24/25.md`。
> 涉及文件：`ui/{MainScreen,SalesPages,WechatPages,WechatMedia,SettingsPages,BackPressPrefs(新)}.kt`、
> `wechat/{WechatNotifier,WechatRuntime,WechatModels,WechatPrefs}.kt`、`core-keepalive/KeepAliveService.kt`

---

## §1 问题 1：微信消息通知与保活常驻通知混在一起

### 改前（`wechat/WechatNotifier.kt`）

```kotlin
    private const val TAG = "微信通知"
    private const val CHANNEL_SOUND = "wechat_message_sound_v2"
    private const val CHANNEL_SILENT = "wechat_message_silent_v2"
    private const val CHANNEL_NAME = "微信消息"

    /** 通知 id 起始值（2001 已被保活/远程通知占用）。 */
    private const val BASE_ID = 2101

    const val NOTIFICATION_ID = BASE_ID

    private fun channelId(context: Context, sound: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (sound) CHANNEL_SOUND else CHANNEL_SILENT
        if (nm.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "中心服务器微信新消息"
            setShowBadge(true)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
        return id
    }
```
```kotlin
        val builder = NotificationCompat.Builder(app, channelId(app, sound))
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(content)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (unreadCount > 1) builder.setNumber(unreadCount.coerceIn(1, 99))
```

### 改前（`core/core-keepalive/.../KeepAliveService.kt`）

```kotlin
        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("Bridge 后台服务")
            .setContentText("服务运行中 · 点此管理")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
```
```kotlin
            val channel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "常驻保活",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "应用运行期间保持后台常驻"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
```

**根因分析**：常驻保活渠道是 `IMPORTANCE_HIGH` + `PRIORITY_MAX` + `setOngoing(true)`，
在通知栏里与 IMPORTANCE_DEFAULT 的微信消息通知**并列且抢在最上方**（多款 ROM 会把同一 App 的
通知按优先级排在同一卡片区/同一分组里），视觉上就是"微信消息和保活通知在一起"。
另外微信通知 id 2101 与 `media/MediaCaptureService.NOTIFICATION_ID = 2101` **撞号**（录屏/投屏
运行时会互相顶替），且没有显式 group key，系统自动归组时也可能被并进去。

**修法**：①微信通知换**独占 id 2301**；②渠道升级到 `_v3` 并归入**独立渠道组**「微信消息」，
「有提示音」渠道重要性提到 **IMPORTANCE_HIGH**（消息类通知应有的醒目度，声音仍由 WechatSound 自播、
渠道自身静音避免双响）；③显式 `setGroup("wechat_messages")`，与「未分组」的保活通知永不互相折叠；
④保活常驻渠道降为 **IMPORTANCE_LOW**（`keep_alive_channel_v2`，渠道属性不可变故换 id），
`setPriority(PRIORITY_LOW)`——前台服务通知只需存在，不需要抢位置（前台服务的存活与通知重要性无关）。

---

## §2 问题 2：确认免打扰会话是否免通知、免提示音

### 改前（`wechat/WechatRuntime.kt` `onIncomingMessage` 尾部）

```kotlin
        // v0.2.25：公众号静默（默认）……
        if (updated.isOfficial && !WechatPrefs.officialNotify(ctx)) {
            DiagLog.i(TAG, "公众号消息静默：$sessionWxid")
            return
        }
        // 自己发的不提醒；免打扰不提醒但仍计未读（与参考前端一致）
        if (isSelf || muted) return
        if (pageActive && WechatController.isViewing(sessionWxid)) {
            // 用户正看着这个会话：不打扰
            return
        }
        remind(ctx, updated, summary)
```

**结论：已实现**。`muted` 取自 `WechatState.state.sessions` 中该会话的 `isMuted`，
在 `remind()`（通知 + 提示音）之前 `return`，所以免打扰会话**不弹通知、不响提示音**，
但仍照常入库、更新列表摘要与未读角标（与参考前端 `stores/session.ts` 口径一致）。
本次只在 `remind()` 入口再加一道 `session.isMuted` 兜底并补日志，防止后续改动绕过该判断。

---

## §3 问题 3：撤回「同会话 + 双发自 + 同类型 + Δt≤2s ⇒ 只留 id 更大者」

### 改前（`wechat/WechatModels.kt`）

```kotlin
private const val SENT_DUP_WINDOW_SEC = 2L

private fun isSentDuplicate(a: WechatMessage, b: WechatMessage): Boolean =
    a.sessionId == b.sessionId &&
        a.isSelf && b.isSelf &&
        a.msgType == b.msgType &&
        kotlin.math.abs(a.timestamp - b.timestamp) <= SENT_DUP_WINDOW_SEC

fun List<WechatMessage>.wechatDedupe(): List<WechatMessage> {
    if (size <= 1) return this
    val out = ArrayList<WechatMessage>(size)
    val seen = HashSet<String>()
    for (m in this) {
        val key = m.msgId.ifBlank { "id:" + m.id }
        if (!seen.add(key)) continue
        val dupIdx = if (out.isEmpty()) null else {
            val from = (out.size - 1).coerceAtLeast(0)
            val to = (out.size - 3).coerceAtLeast(0)
            (from downTo to).firstOrNull { isSentDuplicate(out[it], m) }
        }
        if (dupIdx != null) {
            if (m.id > out[dupIdx].id) out[dupIdx] = m
            continue
        }
        out.add(m)
    }
    return out
}
```

**撤回原因**：经查参考仓库，重复记录**不是**「缩略图/原图」之类的客户端合并问题，
而是 **agent 端 `upload_and_send` 路径少了一道去重**，证据链如下（完整分析见 §3.1）：

1. `agent/core/agent_client.py:1604` —— `send_image` 动作（PC 客户端走的路径）在发送前
   调用 `convert_to_jpeg_and_write_flag()` **写入 EXIF 去重标志**，
   注释原文：「转换为JPEG并写入EXIF去重标志，数据库监控据此识别并跳过重复上报」；
   发送成功后 `_persist_sent_message(..., 3, "[图片消息]", attachment, msg_id)` 写一条记录。
2. `agent/services/message_processor/processor_226.py:79-88` —— 数据库监控 `on_db_rows`
   对自己发的图片（Type=3）**只认 EXIF 标志**：
   `flag = check_exif_flag_from_dat(dat_path)`，命中才 `continue` 跳过。
3. `agent/core/agent_client.py:2387` `_handle_upload_and_send`（手机端唯一可用路径）
   直接 `instance.send_image(target_wxid, save_path)`，**既不写 EXIF 标志也不调
   `record_self_sent_message`**（后者在 `_persist_sent_message` 里只对
   `msg_type == 1`(文本) 与 `43`(视频) 生效，见 `agent_client.py:2785-2789`）。
   ⇒ 同一条消息被写两遍：一次是 `_persist_sent_message`，一次是数据库监控补报。
4. PC 客户端 `pc-client/gui/handlers/message_handler.py:725` 走的是 `send_image_message(image_path=...)`
   ——**有** EXIF 标志 ⇒ 只落一条。这正解释了「PC 端发一条就只有一条」，
   差别在**端点路径**，而不在「服务端/Agent 没问题」。

**结论**：该折叠规则属于「在客户端猜服务端」，既可能误伤（2 秒内连发不同图被并成一条），
也不可靠（数据库监控有轮询延迟，第二条可能晚于 2 秒才到）。按用户要求**撤回**。

**真实修法（Agent 端，本仓库 `references/` 只读不可改）**：把 `_handle_upload_and_send` 的
image 分支改成与 `send_image` 一致（先转 JPEG 写 EXIF 标志再发送）：

```python
# agent/core/agent_client.py: _handle_upload_and_send 内 actual_type == "image" 之前
from utils.exif_flag import generate_flag, convert_to_jpeg_and_write_flag
_flag_id = int(time.time() * 1000000) + uuid.uuid4().int % 10000
_flagged = convert_to_jpeg_and_write_flag(save_path, generate_flag(_flag_id))
# 用 _flagged 代替 save_path 发送，并在成功后用 _flagged 上传 MinIO、删临时文件
```

**本次落地**：`wechatDedupe(foldSent: Boolean = false)` —— 折叠逻辑保留但**默认关闭**（即撤回），
并在微信页设置 Tab 提供开关「自家发送去重（服务端双写兜底）」，默认关；
用户在未修 Agent 的机器上可自行打开应急。

---

## §4 问题 4：返回键二次确认窗口 2 秒硬编码 → 设置可调

### 改前（`ui/WechatPages.kt`）

```kotlin
/**
 * 主界面按返回键回主页的**二次确认窗口**（与全局「再按一次退出」同款 2 秒）。
 * 第 1 次按 → 冒泡提示；窗口内第 2 次按 → 回 APP 主页（v0.2.25）。
 */
private const val BACK_CONFIRM_WINDOW_MS = 2000L
```
```kotlin
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = openSession == null && subTab == 0) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BACK_CONFIRM_WINDOW_MS) {
            WechatState.requestExitToHome()
        } else {
            lastBackAt = now
            wechatToast(context, "再按一次返回主页")
        }
    }
```

### 改前（`ui/MainScreen.kt`）

```kotlin
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                (context as? android.app.Activity)?.finishAffinity()
            } else {
                lastBackAt = now
                showExitHint = true
            }
    LaunchedEffect(showExitHint) {
        if (showExitHint) {
            kotlinx.coroutines.delay(2000)
            showExitHint = false
        }
    }
```

**修法**：新增 `ui/BackPressPrefs.kt`（`ui_prefs`），两项默认 2000ms，范围 1000~5000ms：
- `back_exit_window_ms`：主界面「再按一次返回退出 Bridge」窗口；
- `back_home_window_ms`：微信页/销售页「再按一次返回主页」窗口。
设置→通用新增「返回键」区块，两个滑杆（1~5 秒）自由调节。

---

## §5 问题 5：销售页主界面返回键改为「双击回主页」（舍弃显示底部工具栏）

### 改前（`ui/MainScreen.kt`）

```kotlin
        if (selectedBottom == 1) {
            wechatNavVisible = !wechatNavVisible
            return@BackHandler
        }
        if (selectedBottom == 2) {
            salesNavVisible = !salesNavVisible
            return@BackHandler
        }
```

### 改前（`ui/SalesPages.kt`）

```kotlin
fun SalesPage(onShowBottomBar: () -> Unit) {
```
```kotlin
    // 返回键分层：阅读模式/选择模式/筛选面板先退，浮层菜单最后注册优先关闭。
    BackHandler(enabled = ui.readingMode) { SalesState.setReadingMode(false) }
    BackHandler(enabled = ui.selectMode && !ui.readingMode) { SalesState.setSelectMode(false) }
    BackHandler(enabled = showFilter && !ui.readingMode) { showFilter = false }
    BackHandler(enabled = showOnlineUsers) { showOnlineUsers = false }
    BackHandler(enabled = headerMenu != null) { headerMenu = null }
    BackHandler(enabled = rowMenu != null) { rowMenu = null }
```

**根因**：销售页第 1 次按返回是「显/隐底部导航」（`salesNavVisible` 取反），
与微信页「第 1 次提示、第 2 次回主页」的行为不一致。

**修法**：`SalesPage` 增加 `onExitToHome` 参数，并**在最前面**注册一条
`BackHandler`（Compose 后进先出：注册越靠前优先级越低，前面那些具体状态的 handler 仍优先接管）：
第 1 次 Toast「再按一次返回主页」，窗口内第 2 次 → `onExitToHome()`。
`MainScreen` 的销售分支改为「回主页 + 复位沉浸标记」，不再切换底部工具栏。

---

## §6 问题 6：会话列表头像上叠着昵称首字

### 改前（`ui/WechatMedia.kt`）

```kotlin
@Composable
fun WechatAvatar(wxid: String, name: String, size: Dp = 40.dp, radius: Dp = 5.dp) {
    val context = LocalContext.current
    val url = remember(wxid) { WechatController.avatarUrl(context, wxid) }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(radius)).background(wechatAvatarColor(wxid)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            WechatRemoteImage(
                url = url,
                bearer = WechatController.apiKey(context),
                maxEdge = 160,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 头像加载中/失败时显示首字母（图片加载完成后会盖在上面）
        val initial = name.take(1).ifBlank { "微" }
        if (WechatImageLoader.cached(url) == null) {
            Text(initial, color = Color.White, fontSize = (size.value / 2.6).sp)
        }
    }
}
```

**根因**：`WechatImageLoader.cached(url)` 只在**组合时**求值一次，而 `WechatRemoteImage`
是异步加载（内部 `bmp` 状态变化不会让外层 Box 重组）⇒ 图片下载完成并显示后，
`cached(url) == null` 这个**过期判断**依然成立 ⇒ 首字母一直画在图片**上面**（Box 后绘制的在上层）。
注释里写的「图片加载完成后会盖在上面」是错的：同层 Box 里后绘制的是 Text，反而压在图片上。

**修法**：`WechatAvatar` 自己持有一个 `bmp` 状态（与 `WechatRemoteImage` 同款异步加载），
`bmp == null` 才画首字母 —— 加载成功触发重组，首字母自动消失；加载失败则保留首字色块。

---

## §7 回滚配方

```bash
git checkout cd132be -- \
  app/src/main/java/my/bridge/com/ui/MainScreen.kt \
  app/src/main/java/my/bridge/com/ui/SalesPages.kt \
  app/src/main/java/my/bridge/com/ui/WechatPages.kt \
  app/src/main/java/my/bridge/com/ui/WechatMedia.kt \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
  app/src/main/java/my/bridge/com/wechat/WechatNotifier.kt \
  app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt \
  app/src/main/java/my/bridge/com/wechat/WechatModels.kt \
  app/src/main/java/my/bridge/com/wechat/WechatPrefs.kt \
  core/core-keepalive/src/main/java/my/bridge/com/keepalive/KeepAliveService.kt
rm -f app/src/main/java/my/bridge/com/ui/BackPressPrefs.kt
```

**触发回滚的信号**：①微信消息通知消失或变成折叠的一条（说明 `setGroup` 在该 ROM 上被折叠，
去掉 `WechatNotifier` 的 `setGroup` 即可）；②保活常驻通知被系统彻底隐藏（把
`IMPORTANCE_LOW` 改回 `IMPORTANCE_HIGH`）；③销售页返回键与阅读模式/浮层冲突。

---

## §8 改后回填

> 编译校验：`JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1+1" ./gradlew :app:compileDebugKotlin` 通过。

### §1 改后（`wechat/WechatNotifier.kt`）——通知 id / 渠道 / 分组

```kotlin
    private const val CHANNEL_SOUND = "wechat_message_sound_v3"
    private const val CHANNEL_SILENT = "wechat_message_silent_v3"
    private const val CHANNEL_NAME = "微信消息"
    /** 渠道组：与「常驻保活」等渠道分开，设置页与部分 ROM 按组分开展示。 */
    private const val CHANNEL_GROUP_ID = "wechat_message_channel_group"
    private const val CHANNEL_GROUP_NAME = "微信消息"
    /** 通知分组 key：只有本条（及其后续顶替）属于该组，不会与未分组的保活通知合并。 */
    private const val GROUP_KEY = "wechat_messages"

    /** 通知 id：2301（2001=保活/远程，2101=媒体采集，2201=更新，2202=管理端，均避开）。 */
    private const val BASE_ID = 2301
```
```kotlin
        runCatching {
            nm.createNotificationChannelGroup(
                NotificationChannelGroup(CHANNEL_GROUP_ID, CHANNEL_GROUP_NAME)
            )
        }.onFailure { DiagLog.w(TAG, "创建渠道组失败：${it.message}") }
        val channel = NotificationChannel(
            id,
            CHANNEL_NAME,
            if (sound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "中心服务器微信新消息"
            group = CHANNEL_GROUP_ID
            setShowBadge(true)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
```
```kotlin
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // v0.2.26：显式分组 ⇒ 与「未分组」的保活常驻通知永不折叠成一组。
            .setGroup(GROUP_KEY)
            .setGroupSummary(false)
```

### §1 改后（`core/core-keepalive/.../KeepAliveService.kt`）

```kotlin
        const val CHANNEL_KEEP_ALIVE = "keep_alive_channel_v2"
```
```kotlin
            val channel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "常驻保活",
                NotificationManager.IMPORTANCE_LOW     // 改前 IMPORTANCE_HIGH
            ).apply { ... }
```
```kotlin
            .setPriority(NotificationCompat.PRIORITY_LOW)   // 改前 PRIORITY_MAX
```

### §2 改后（`wechat/WechatRuntime.kt`）

```kotlin
    private fun remind(ctx: Context, session: WechatSession, summary: String) {
        if (session.isMuted) {
            DiagLog.i(TAG, "免打扰会话不提醒：${session.userWxid}")
            return
        }
        ...（原逻辑不变）
```

### §3 改后（`wechat/WechatModels.kt` + `WechatPrefs.kt` + `ui/WechatPages.kt`）

```kotlin
fun List<WechatMessage>.wechatDedupe(foldSent: Boolean = false): List<WechatMessage> {
    ...
        val dupIdx = if (!foldSent || out.isEmpty()) null else {
            val from = (out.size - 1).coerceAtLeast(0)
            val to = (out.size - 3).coerceAtLeast(0)
            (from downTo to).firstOrNull { isSentDuplicate(out[it], m) }
        }
```
```kotlin
    /** 见 [KEY_DEDUPE_SENT]：默认 false（已撤回客户端折叠；Agent 未修时可手动打开应急）。 */
    fun dedupeSent(context: Context): Boolean = sp(context).getBoolean(KEY_DEDUPE_SENT, false)

    fun setDedupeSent(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_DEDUPE_SENT, on).apply()
    }
```
四处调用改为 `wechatDedupe(foldSent = foldSentDup)`，`WechatChatPanel` 内：
```kotlin
    val foldSentDup = my.bridge.com.wechat.WechatPrefs.dedupeSent(context)
```
设置页新增开关「自家发送去重（服务端双写兜底）」。

### §4 改后（`ui/BackPressPrefs.kt` 新增 + `ui/MainScreen.kt` + `ui/WechatPages.kt` + `ui/SettingsPages.kt`）

```kotlin
object BackPressPrefs {
    const val DEFAULT_WINDOW_MS = 2000L
    private const val MIN_MS = 1000L
    private const val MAX_MS = 5000L
    private const val SP_NAME = "ui_prefs"
    private const val KEY_EXIT_MS = "back_exit_window_ms"
    private const val KEY_HOME_MS = "back_home_window_ms"
    fun exitWindowMs(context: Context): Long = read(context, KEY_EXIT_MS)
    fun homeWindowMs(context: Context): Long = read(context, KEY_HOME_MS)
    ...（setXXX 与钳制读写）
}
```
```kotlin
            // v0.2.26：改前是硬编码 2000ms，现为设置 → 通用 → 返回键可调（默认 2 秒）。
            val window = BackPressPrefs.exitWindowMs(context)
            val now = System.currentTimeMillis()
            if (now - lastBackAt < window) {
                (context as? android.app.Activity)?.finishAffinity()
            } else {
                lastBackAt = now
                showExitHint = true
            }
```
```kotlin
        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {   // 微信页
```
设置 → 通用新增「返回键」区块：`BackWindowRow` 两个滑杆（1~5 秒，步长 1）。

### §5 改后（`ui/SalesPages.kt` + `ui/MainScreen.kt`）

```kotlin
fun SalesPage(
    onShowBottomBar: () -> Unit,
    onExitToHome: () -> Unit = {},
) {
```
```kotlin
    // 返回键链（Compose BackHandler **后进先出**：注册越靠后优先级越高）：
    // ① 主界面（表格页/登录页）→ 第 1 次 Toast「再按一次返回主页」，窗口内第 2 次 → 回 APP 主页；
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
    // ② 阅读模式/选择模式/筛选面板先退，浮层菜单最后注册优先关闭。
    BackHandler(enabled = ui.readingMode) { SalesState.setReadingMode(false) }
```
```kotlin
                2 -> SalesPage(
                    onShowBottomBar = { salesNavVisible = true },
                    onExitToHome = {
                        selectedBottom = 0
                        salesNavVisible = true
                    },
                )
```
`MainScreen` 全局 BackHandler 的销售/微信兜底分支改为「直接回主页」（不再切换底部工具栏）。

### §6 改后（`ui/WechatMedia.kt`）

```kotlin
    var bmp by remember(wxid) { mutableStateOf<Bitmap?>(WechatImageLoader.cached(url)) }
    if (bmp == null && url.isNotBlank()) {
        LaunchedEffect(url) {
            withContext(Dispatchers.IO) {
                val loaded = WechatImageLoader.load(url, bearer = bearer, maxEdge = 160)
                if (loaded != null) bmp = loaded
            }
        }
    }
    Box(...) {
        if (bmp != null) {
            Image(bitmap = bmp!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val initial = name.take(1).ifBlank { "微" }
            Text(initial, color = Color.White, fontSize = (size.value / 2.6).sp)
        }
    }
```
