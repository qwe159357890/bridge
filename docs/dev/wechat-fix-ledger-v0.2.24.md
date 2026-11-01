# 微信页第三批真机问题修复台账（v0.2.24）

> 遵循「改前留档」约定（2026-11-01 用户确立）：**先逐字固化改前原文 → 再动手改 → 回填改后 → 附回滚配方**。
> 目标：任何一条改动出错都能 100% 完美恢复。

- 版本：v0.2.23 → v0.2.24
- 反馈来源：用户真机逐条反馈（6 项）
- 涉及文件：
  - `app/src/main/java/my/bridge/com/ui/WechatPages.kt`
  - `app/src/main/java/my/bridge/com/ui/MainScreen.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatState.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatSound.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatPrefs.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatController.kt`
  - `app/src/main/java/my/bridge/com/wechat/WechatApiClient.kt`

---

## §1 问题 1：会话/通讯录第一次触底加载成功，之后触底再也不加载

### 改前（原文，`WechatPages.kt` 会话列表）

```kotlin
        } else {
            val listState = rememberLazyListState()
            // 滑到接近底部就拉下一页（v0.2.23）。搜索状态下不自动翻页——
            // 过滤后的条数与服务端 offset 对不上，翻页会错位。
            val needMore by remember {
                derivedStateOf {
                    if (keyword.value.isNotBlank()) return@derivedStateOf false
                    if (ui.loadingMoreSessions || ui.sessionsEnded) return@derivedStateOf false
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                    // 列表末尾还挂了一个 footer item（index == list.size），故用 -2 提前触发
                    last.index >= list.size - 2
                }
            }
            LaunchedEffect(needMore) {
                if (needMore) WechatController.loadMoreSessions()
            }
```

通讯录（`WechatPages.kt`）同构：

```kotlin
            val listState = rememberLazyListState()
            val needMore by remember {
                derivedStateOf {
                    if (keyword.value.isNotBlank()) return@derivedStateOf false
                    if (ui.loadingMoreContacts || ui.contactsEnded) return@derivedStateOf false
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                    last.index >= list.size - 2
                }
            }
            LaunchedEffect(needMore) {
                if (needMore) WechatController.loadMoreContacts()
            }
```

### 根因

`LaunchedEffect(needMore)` 只在 **键值发生变化** 时重启协程。
`needMore` 是布尔量，典型序列：

1. 触底 → `needMore=false→true` → 协程启动 → 调一次 `loadMoreSessions()`；
2. 加载中 `loadingMoreSessions=true` → `needMore=true→false`，协程被取消；
3. 加载完成 → 若追加后用户**仍在底部附近**（常见于「新页全被去重/模式过滤吃掉」或「追加条数少、视口没移动」），
   `needMore` 会从 false 又变 true **触发第二次**，但若此时 `sessions.size` 没变（去重后 added==0），
   下一页 `offset` 与本次完全相同 → 服务端返回同一批 → 继续去重 → added 恒为 0 →
   `sessionsEnded` 永远不会被置 true，而 `loadingMoreSessions` 每次都会先 true 再 false。

用户侧看到的就是：**第一次加载有反应，之后 footer 一直显示「滑动到底部加载更多」，再滑也不加载**
（实际是「加载了但没新增 / 或 needMore 布尔量在同一帧内来回跳变导致协程没重启」）。

两个必须一起修的次生缺陷：
- `appendSessions` 去重后 `added == 0` 时 `sessionsEnded` 仍按 `list.size < PAGE` 计算 → 永远 false；
- 布尔量 + `LaunchedEffect` 的边沿触发模型本身不可靠（只在 false→true 边沿响应）。

---

## §2 问题 2：有新消息时会话列表显示的更少了

### 改前（原文，`WechatRuntime.refreshSessions`）

```kotlin
    fun refreshSessions() {
        val ctx = app ?: return
        if (WechatState.state.loadingSessions) return
        WechatState.setLoadingSessions(true)
        scope.launch {
            runCatching {
                val list = api!!.sessions(limit = SESSION_PAGE, offset = 0)
                withContext(Dispatchers.Main.immediate) {
                    WechatState.setSessions(list, list.filter { !it.isMuted }.sumOf { it.unreadCount })
                    // 返回条数不足一页 ⇒ 后面没有了（列表据此显示「已经加载全部」）
                    WechatState.setSessionsEnded(list.size < SESSION_PAGE)
                }
            }.onFailure {
                DiagLog.w(TAG, "拉取会话失败：${it.message}")
                WechatState.setLoadingSessions(false)
            }
        }
    }
```

`SESSION_PAGE = 50`。触发者：
`onSessionUpdate()`（WS `session_update`）、`onConnected()` → `refreshAll()`、
`onPageEnter()` → `refreshAll()`、发送成功后 `WechatController.refreshSessions()`。

### 根因

用户翻到第 3 页（150 条）后，任何一次 `refreshSessions()` 都把列表**整体替换成第 1 页 50 条**
（`setSessions` 是覆盖语义），且 `sessionsEnded` 被重算成 false。
现象即「来了新消息，列表反而变短」。

---

## §3 问题 3：Tab 角标 54，会话行没有角标

### 改前（原文，`WechatRuntime.refreshSessions` 与 `recalcUnread`）

```kotlin
WechatState.setSessions(list, list.filter { !it.isMuted }.sumOf { it.unreadCount })
```

```kotlin
    private fun recalcUnread() {
        // 与服务端口径一致：只累计非免打扰会话
        val total = WechatState.state.sessions.filter { !it.isMuted }.sumOf { it.unreadCount }
        WechatState.setUnreadTotal(total)
    }
```

会话列表只显示**当前模式**的会话（`WechatUiState.visibleSessions`）：

```kotlin
    val visibleSessions: List<WechatSession>
        get() {
            val official = sessionMode == "subscription"
            return sessions
                .filter { it.isOfficial == official }
                .sortedWith(compareByDescending<WechatSession> { it.isPinned }
                    .thenByDescending { it.updateTimestamp })
        }
```

参考前端 `web-frontend/src/stores/session.ts` 的 `totalUnreadCount` **是按模式过滤后**再求和的：

```ts
    for (const s of allSessions.value) {
      localWxids.add(s.userWxid)
      const isGh = isOfficialAccount(s.userWxid)
      if (isSubscriptionMode !== isGh) continue     // ← 模式过滤
      if (!s.isMuted && s.unreadCount > 0) {
        total += s.unreadCount
      }
    }
```

### 根因

我们的 `unreadTotal` 对**全部会话**求和（含公众号），而列表只渲染当前模式的会话。
未读若落在被过滤掉的一类（好友模式下未读是 `gh_` 公众号）⇒ Tab 显示 54、列表里一条角标都没有。

次生风险：`setSessions` 覆盖语义会把本端 WS 累计的未读清零（服务端 `sessions.unread_count`
由客户端写回，**服务端收到新消息时并不自增**——`center-server` 全库 grep 只有
`db.update_session_unread()` 被客户端 PUT 调用）。

---

## §4 问题 4：退出微信后再次进入直接跳进会话聊天界面

### 改前（原文，`MainScreen.kt`）

```kotlin
    var openWechatSession by rememberSaveable { mutableStateOf<String?>(null) }
```

```kotlin
    val exitToHome = my.bridge.com.wechat.WechatState.state.exitToHomeToken
    LaunchedEffect(exitToHome) {
        if (exitToHome > 0) {
            selectedBottom = 0
            wechatNavVisible = true
        }
    }
```

```kotlin
                1 -> WechatPage(
                    onShowBottomBar = { wechatNavVisible = true },
                    initialSessionWxid = openWechatSession,
                )
```

`WechatPages.kt`：

```kotlin
    LaunchedEffect(initialSessionWxid) {
        WechatController.onEnter(context)
        if (initialSessionWxid != null) {
            openSession = initialSessionWxid
            WechatController.openSession(context, initialSessionWxid)
        }
    }
```

### 根因

`openWechatSession` 是 `rememberSaveable`，退出微信只切了 Tab（selectedBottom=0），
**没有清掉上次打开的会话 wxid**。再次点「微信」→ `initialSessionWxid != null` → 直接进聊天详情。

---

## §5 问题 5：发送图片后接收端（自写客户端）显示两条

### 改前（原文，`WechatPages.kt` 附件发送回调）

```kotlin
            val r = WechatController.sendFile(context, sessionWxid, name, mimeOfName(name), bytes)
            wechatToast(context, if (r.isSuccess) "已发送" else "发送失败：${r.exceptionOrNull()?.message}")
            if (r.isSuccess) {
                messages.add(selfMessage(sessionWxid, "[文件] $name"))
                listState.animateScrollToItem(messages.lastIndex)
                WechatController.refreshSessions()
            }
```

`selfMessage()`：

```kotlin
private fun selfMessage(sessionWxid: String, content: String): WechatMessage = WechatMessage(
    id = 0L,
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
    senderNickname = "",
)
```

会话内新消息合并（`messageVersion`）：

```kotlin
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

### 根因

占位气泡的 key 是 `msgId=""` + `id=0` → 去重集合里只有 `"0"`；
服务端真实图片消息有真实 `msg_id`/`id` → **判定为新消息被再次追加**。
于是同一张图出现两条：一条本地占位「[文件] xxx」，一条服务端真实图片。
真机微信只有一条，说明重复纯粹是客户端合并口径造成的。

---

## §6 问题 6：提示音电平

### 改前（原文，`WechatSound.kt`）

```kotlin
    private fun play(id: Int) {
        val p = pool ?: return
        if (id == 0) return
        runCatching { p.play(id, 1f, 1f, 1, 0, 1f) }
            .onFailure { DiagLog.w(TAG, "播放提示音失败：${it.message}") }
    }
```

音源电平实测（`wave` 读取峰值）：

| 文件 | 采样率 | 时长 | 峰值 | RMS |
|---|---|---|---|---|
| `res/raw/wechat_msg.wav` | 11025Hz 立体声 16bit | 1.13s | 32768（**100% FS**） | 23.3% |
| `res/raw/wechat_jump.wav` | 44100Hz 单声道 16bit | 0.20s | 32767（100% FS） | 14.2% |
| `res/raw/scan_ok.wav`（快递扫码对照） | 48000Hz 立体声 16bit | 0.16s | 32767（100% FS） | 29.9% |

结论：音源已是满幅（0 dBFS），SoundPool 播放音量也已 1.0（App 侧上限）。
**缺的是「可调电平」**——快递扫码 `ScanFeedback` 有 `ScanPrefs.volume(context)`（默认 1.0f）与设置页滑杆：

```kotlin
                // 音量滑杆（v0.2.05）：App 播放系数 0~100%，与系统媒体音量相乘；
                // 音源已归一化满幅，这里是唯一需要日常调的地方
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提示音音量", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${(volume * 100).toInt()}%", fontSize = 12.sp, color = ScanSub)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    enabled = sound,
                    valueRange = 0f..1f,
                )
```

`ScanPrefs.volume` → `sp(context).getFloat(KEY_VOLUME, 1.0f)`。

---

## §7 改后（逐条对照）

### §7.1 触底加载（`WechatPages.kt`，会话列表 / 通讯录同构）

```kotlin
            val listState = rememberLazyListState()
            // v0.2.24：触底检测改用 snapshotFlow 订阅 layoutInfo，不再用
            // 「derivedStateOf 布尔量 + LaunchedEffect(布尔量)」——后者只在 false→true
            // 的**边沿**触发一次，一旦加载完成后用户仍处于底部（或本页全是重复项、
            // 列表长度没变），布尔量不会再产生新的跳变，就永远不再加载。
            // snapshotFlow 每次布局/滚动都会发射，天然可重复触发。
            val keywordNow by rememberUpdatedState(keyword.value)
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
```

`WechatState.appendSessions` / `appendContacts` 加 `|| added == 0`：

```kotlin
                // v0.2.24：整页都是已存在的（added == 0）⇒ 判定已到底。
                // 否则 offset 不变（= sessions.size），下次请求拿回同一批，
                // footer 会一直显示「滑动到底部加载更多」却永远加载不出新内容。
                sessionsEnded = ended || added == 0,
```

### §7.2 刷新不打回第一页（`WechatRuntime.kt`）

```kotlin
    private const val SESSION_MAX = 500   // 服务端 query.max_limit = 500
```

```kotlin
        // 已在内存里的条数（可能已翻过几页）：刷新要取回同样多，不能打回第 1 页
        val want = WechatState.state.sessions.size
            .coerceAtLeast(SESSION_PAGE)
            .coerceAtMost(SESSION_MAX)
        ...
                val list = api!!.sessions(limit = want, offset = 0)
                withContext(Dispatchers.Main.immediate) {
                    WechatState.setSessions(list, WechatState.state.unreadOf(list))
                    WechatState.setSessionsEnded(list.size < want)
                }
```

### §7.3 未读口径（`WechatState.kt` + `WechatRuntime.kt`）

```kotlin
    /**
     * 未读总数口径（v0.2.24，照参考前端 `web-frontend/src/stores/session.ts`
     * 的 `totalUnreadCount`）：**只统计当前模式（好友/公众号）下的非免打扰会话**。
     */
    fun unreadOf(list: List<WechatSession>): Int {
        val official = sessionMode == "subscription"
        return list
            .filter { it.isOfficial == official && !it.isMuted }
            .sumOf { it.unreadCount }
    }
```

```kotlin
    fun setSessions(list: List<WechatSession>, unreadTotal: Int) = mutate { st ->
        val known = st.sessions.associateBy { it.userWxid }
        val merged = list.map { s ->
            val old = known[s.userWxid]
            if (old != null && old.unreadCount > s.unreadCount) s.copy(unreadCount = old.unreadCount) else s
        }
        st.copy(sessions = merged, unreadTotal = unreadTotal, loadingSessions = false)
    }
```

```kotlin
    private fun recalcUnread() {
        WechatState.setUnreadTotal(WechatState.state.unreadOf(WechatState.state.sessions))
    }
```

### §7.4 退出微信停在列表（`MainScreen.kt`）

```kotlin
    LaunchedEffect(exitToHome) {
        if (exitToHome > 0) {
            selectedBottom = 0
            wechatNavVisible = true
            // v0.2.24：必须清掉上次打开的会话，否则「退出微信 → 再点微信」
            // 会带着旧 wxid 直接落进聊天详情（用户期望停在微信主页/会话列表）。
            openWechatSession = null
        }
    }
```

### §7.5 发送后不重复（`WechatPages.kt`）

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
        messages.add(selfMessage(sessionWxid, placeholder))
        listState.animateScrollToItem(messages.lastIndex)
    }
```

两处调用点改为 `afterSend(text)` / `afterSend("[文件] $name")`，不再直接 `messages.add(selfMessage(...))`。

### §7.6 提示音电平

`WechatPrefs`：`KEY_SOUND_VOLUME = "sound_volume"`，`soundVolume()` 默认 **1.0f**。
`WechatSound.play/playJump` → `play(id, WechatPrefs.soundVolume(context))`；
`SoundPool.play(id, volume, volume, 1, 0, 1f)`。
设置页加滑杆（0~100%）+「试听」按钮，沿用快递扫码 `ScanFeedback` 的交互与文案口径。

---

## §8 回滚配方

本批改动全部在本机 git 中（commit 见下），未涉及服务端。

```bash
# 整包回滚（回到 v0.2.23 的代码状态）
git checkout 46fdd07 -- \
  app/src/main/java/my/bridge/com/ui/WechatPages.kt \
  app/src/main/java/my/bridge/com/ui/MainScreen.kt \
  app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt \
  app/src/main/java/my/bridge/com/wechat/WechatState.kt \
  app/src/main/java/my/bridge/com/wechat/WechatSound.kt \
  app/src/main/java/my/bridge/com/wechat/WechatPrefs.kt
```

单条回滚要点：

| # | 回退动作 | 触发回滚的信号 |
|---|---|---|
| 1 | 还原 `WechatPages.kt` 的两个 `LaunchedEffect(listState)` 块 + `WechatState` 的 `|| added == 0` | 列表出现「疯狂自动翻页」（snapflow 每帧触发）——此时应给 `collectLatest` 加 `debounce` 或改回阈值 -2 |
| 2 | 还原 `refreshSessions()` 的 `limit = SESSION_PAGE` | 刷新变慢 / 服务端 422（说明 `SESSION_MAX` 超过实际 `query.max_limit`） |
| 3 | 还原 `unreadOf` → 全量 sum、`setSessions` → 直接覆盖 | 角标一直不消失（本地累计值被保留、服务端已清零且没收到 `unread_cleared` 广播） |
| 4 | 删掉 `openWechatSession = null` | 通知点击进会话失效（不应发生，该值由 `pendingOpenSession` 单独写入） |
| 5 | 还原成 `messages.add(selfMessage(...))` | 发送后 ~4s 才出现气泡、且偶尔不出现（说明服务端不回写自己发的消息） |
| 6 | `WechatPrefs.soundVolume` 默认改回 1.0f（已是） | 无声：检查系统媒体音量与「全局静音」开关 |
