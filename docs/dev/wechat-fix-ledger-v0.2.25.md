# v0.2.25 微信页第四批真机问题（8 项）变更台账

> 用途：任何改动**先逐字记录改前原文 → 再动手 → 回填改后**，确保出错时能 100% 完美恢复。
> 模板见 `docs/dev/heartbeat-inventory.md` §8；相关历史：`wechat-fix-ledger-v0.2.23.md`、`v0.2.24.md`。
> 涉及文件：`ui/WechatPages.kt`、`ui/MainScreen.kt`、`wechat/{WechatState,WechatRuntime,WechatPrefs,WechatNotifier,WechatController,WechatModels}.kt`

---

## §1 问题 1：从其它页（销售）进入微信时弹「没有未读会话」气泡

### 改前（`ui/WechatPages.kt` 309~322，`WechatPage` 内）

```kotlin
    // 双击「聊天」按钮 → 跳到最新一条未读会话（v0.2.23）。令牌为 0 时是首次组合，跳过。
    val jumpToken = ui.jumpUnreadToken
    LaunchedEffect(jumpToken) {
        if (jumpToken == 0) return@LaunchedEffect
        val target = WechatController.latestUnreadSession()
        if (target == null) {
            wechatToast(context, "没有未读会话")
        } else {
            subTab = 0
            openSession = target.userWxid
            WechatController.openSession(context, target.userWxid)
            WechatSound.playJump(context)
        }
    }
```

**根因**：`LaunchedEffect(jumpToken)` 的语义是「**key 变化或首次进入组合时执行一次**」。
从销售页切回微信页 ⇒ `WechatPage` 重新进入组合 ⇒ 该 effect **重新执行**，
而 `jumpUnreadToken` 仍是上次双击留下的非 0 值 ⇒ 立刻走一遍跳转逻辑 ⇒
没有未读就弹「没有未读会话」；**有未读就直接进会话详情**（问题 5 同源）。

**修法**：用「已消费令牌」守卫——`consumedJumpToken` 以当前值初始化，只有真正
`jumpToken != consumedJumpToken`（即用户刚双击过）才动作。

---

## §2 问题 2：双击「聊天」不应进入会话详情

改前同上：`openSession = target.userWxid` + `WechatController.openSession(...)`。
**修法**：只把目标会话**滚动到列表顶部**（`LazyListState.animateScrollToItem(index)`），不打开详情。

## §3 问题 3：未读跳转优先级（先非免打扰，再免打扰，逐次递进）

改前（`wechat/WechatRuntime.kt` 433~439）：

```kotlin
    /**
     * 最新一条有未读的会话（双击「聊天」按钮跳转用，v0.2.23）。
     * 参考 PC 客户端同一交互（快捷键跳未读会话，并播「叮一声」）。
     * [WechatUiState.visibleSessions] 已按「置顶 + 更新时间倒序」排好，取第一个未读即可。
     */
    fun latestUnreadSession(): WechatSession? =
        WechatState.state.visibleSessions.firstOrNull { it.unreadCount > 0 }
```

**修法**：改为**候选队列** `unreadJumpTargets` = 非免打扰未读（按列表顺序）+ 免打扰未读（按列表顺序），
每次双击取 `cursor % size` 并递增 cursor（到尾回绕到 0）。

---

## §4 问题 4：每条消息一条通知 → 只保留一条，后到顶替前到

改前（`wechat/WechatNotifier.kt` 32~36 / 84~98）：

```kotlin
    /** 通知 id 起始值（2001 已被保活/远程通知占用）。 */
    private const val BASE_ID = 2101

    /** 同一会话复用同一 id：连续消息只更新不刷屏。 */
    fun idOf(sessionWxid: String): Int = BASE_ID + (sessionWxid.hashCode() and 0x0FFF) % 500
```
```kotlin
        if (unreadCount > 1) builder.setNumber(unreadCount.coerceIn(1, 99))

        runCatching {
            NotificationManagerCompat.from(app).notify(idOf(sessionWxid), builder.build())
        }.onFailure {
            DiagLog.e(TAG, "通知发送失败", it)
        }
    }

    /** 会话已读后撤掉该会话通知（角标随之归零）。 */
    fun cancel(context: Context, sessionWxid: String) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(idOf(sessionWxid))
        }.onFailure { DiagLog.w(TAG, "撤销通知失败：${it.message}") }
    }
```

**根因**：id 按会话分散 ⇒ 10 个会话来消息就 10 条通知。
**修法**：**全局单一 id** `NOTIFICATION_ID = 2101`（后到的 `notify()` 同 id 直接顶替前一条），
`setNumber(unreadTotal)` 显示累计未读；`cancel(context)` 撤这一条。

---

## §5 问题 5：退出微信后再进入仍然落到会话详情

改前：`MainScreen.kt` 100~111 已清 `openWechatSession`，但真正把页面塞进详情的是 §1 那个
重跑的 `LaunchedEffect(jumpToken)`（退出→进主页→再点微信，`WechatPage` 重新组合，令牌未变却重跑）。
**修法**：同 §1 令牌守卫；另 `MainScreen` 的 `exitToHome` 分支保持清 `openWechatSession`（v0.2.24 已有）。

---

## §6 问题 6：发一张图，本端会话里出现两条（缩略图尺寸不同）

改前（`ui/WechatPages.kt` 699~750，`WechatChatPanel` 内）：

```kotlin
    suspend fun afterSend(placeholder: String) {
        repeat(8) {
            delay(500)
            val latest = WechatController.loadMessages(context, sessionWxid).asReversed()
            val known = messages.map { it.msgId.ifBlank { it.id.toString() } }.toSet()
            val add = latest.filter { (it.msgId.ifBlank { it.id.toString() }) !in known }
            if (add.any { it.isSelf }) {
                messages.addAll(add)
                listState.animateScrollToItem(messages.lastIndex)
                return
            }
        }
        // 兜底：服务端迟迟没落库（如 hook 不回写自己发的消息）时才用占位气泡，
        // 保证用户至少能看到自己发的内容
        messages.add(selfMessage(sessionWxid, placeholder))
        listState.animateScrollToItem(messages.lastIndex)
    }
```
```kotlin
    var lastVersion by remember(sessionWxid) { mutableIntStateOf(WechatState.state.messageVersion) }
    val curVersion = WechatState.state.messageVersion
    LaunchedEffect(curVersion) {
        if (curVersion == lastVersion) return@LaunchedEffect
        lastVersion = curVersion
        val latest = WechatController.loadMessages(context, sessionWxid).asReversed()
        val known = messages.map { it.msgId.ifBlank { it.id.toString() } }.toSet()
        val add = latest.filter { (it.msgId.ifBlank { it.id.toString() }) !in known }
        if (add.isNotEmpty()) {
            messages.addAll(add)
            listState.scrollToItem(messages.lastIndex)
        }
    }
```
```kotlin
    LaunchedEffect(sessionWxid) {
        noMore = false
        messages.clear()
        messages.addAll(WechatController.loadMessages(context, sessionWxid).asReversed())
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
```

**根因**：客户端只发一次（单次 `POST /api/wechat/upload_and_send`，证据：`WechatApiClient.uploadAndSend`
只有一处调用，`WechatRuntime.sendFile` 直连 `sendImage`）。重复来自**服务端/agent 侧对同一次发送写了两条
消息记录**（微信 hook 回写 + 发送侧记录），两条 `msg_id`/`id` 不同 ⇒ 现有「按 msgId/id 去重」判成两条新消息；
缩略图尺寸不同 ⇒ 两条记录的 attachment 不是同一份（一张压缩图、一张原图）。
参考项目里消息表的写入代码不在本仓库（`center-server` 里没有 messages 的 INSERT），无法从服务端根治。

**修法（客户端兜底，可一键关）**：新增 `WechatModels.wechatDedupe()`——
① 按 `msgId ?: id` 去重（原有逻辑）；
② 折叠「同一次发送的双写记录」：同会话 + **双方都是自己发的** + 同 `msgType` +
`|Δtimestamp| ≤ 2s` ⇒ 只保留后到的（id 更大，通常是 hook 回写的完整记录），并打日志。
限定 `isSelf` 与 2 秒窗：人工连发两张不同图必然 > 2s（每次都要走选择器 + 上传），不会误伤。
应用于：首次加载、增量合并、`afterSend`。

---

## §7 问题 7：公众号消息默认静默，加开关

改前（`wechat/WechatRuntime.kt` 313~333）：

```kotlin
    /** 提示：通知 + 提示音（提醒方式可关，连接不可关）。 */
    private fun remind(ctx: Context, session: WechatSession, summary: String) {
        if (WechatPrefs.notifyEnabled(ctx)) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(WECHAT_EXTRA_OPEN_SESSION, session.userWxid)
            } ?: Intent()
            WechatNotifier.post(
                context = ctx,
                sessionWxid = session.userWxid,
                title = session.userNickname,
                content = summary,
                sound = WechatPrefs.soundEnabled(ctx) && !WechatPrefs.muteAll(ctx),
                unreadCount = WechatState.state.unreadTotal,
                openIntent = intent,
            )
        }
        if (WechatPrefs.soundEnabled(ctx) && !WechatPrefs.muteAll(ctx)) {
            WechatSound.play(ctx)
        }
    }
```

**修法**：新增 `WechatPrefs.officialNotify`（**默认 false**，与参考项目 web/PC 端口径一致：
公众号静默）。`onIncomingMessage` 在 `remind()` 之前拦截：会话 `isOfficial`（wxid 前缀 `gh_`）
且开关关闭 ⇒ **只更新列表/未读，不通知、不响铃**。设置页加开关「公众号消息提醒」。

---

## §8 问题 8：去掉左下角返回箭头；主界面按返回键「再按一次返回主页」

改前 ①（`ui/WechatPages.kt` 401~412，`WechatSubBar`）：

```kotlin
@Composable
private fun WechatSubBar(
    current: Int,
    unread: Int,
    onSelect: (Int) -> Unit,
    onShowBottomBar: () -> Unit,
) {
    Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
        Row(modifier = Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShowBottomBar, modifier = Modifier.width(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "显示主导航", tint = WechatSub, modifier = Modifier.size(18.dp))
            }
```

改前 ②（`ui/MainScreen.kt` 126~136，全局 BackHandler 首段）：

```kotlin
    BackHandler {
        // 销售页（沉浸，无二级 tab）：按返回键 = 点击左上角「返回导航」箭头。
        // 第 1 次按 → 显示底部导航栏；再次按 → 隐藏导航栏（重新沉浸），如此循环。
        if (selectedBottom == 1) {
            wechatNavVisible = !wechatNavVisible
            return@BackHandler
        }
```

改前 ③（`ui/WechatPages.kt` 283~299，页内 BackHandler）：

```kotlin
    BackHandler(enabled = ui.configured && (openSession != null || subTab != 0)) {
        when {
            emojiPanel -> emojiPanel = false
            openSession != null -> { ... }
            else -> subTab = 0
        }
    }
```

**修法**：
- 删掉 §8① 的 `IconButton`（连带 `onShowBottomBar` 参数与 MainScreen 传参）；
- `WechatPage` **先注册**一个「主界面返回」BackHandler（注册靠前 = 优先级低）：
  第 1 次按 → Toast「再按一次返回主页」；2 秒内第 2 次按 → `WechatState.requestExitToHome()`
  （MainScreen 已有处理：切到主页 Tab + 清会话 + 显示底栏）。
  原有的「表情/会话/子 Tab」BackHandler **保持注册在后**（Compose 的 BackHandler 是后进先出，
  靠后注册 = 优先级高），所以进详情时仍然是「先退详情」。

> 用户问「这叫什么工具栏」：**底部导航栏 / Bottom Navigation Bar**（APP 主导航，
> 微信页沉浸时隐藏，就是那个箭头点出来的东西）。

---

## §9 改后回填与回滚配方

见文末（代码改完后回填）。
