# 微信页 v0.2.23 真机第二批问题修复 —— 变更台账

> 遵循项目「改前留档约定」（`docs/dev/heartbeat-inventory.md` §8）：
> **先逐字固化改前原文 → 动手改 → 回填改后原文 → 附回滚配方**。
> 本次共 9 项真机反馈（用户 2026-11-01 语音转文字），根因可归为 3 类：
> ① WS 从未连上（问题 1/3/9 同源）；② 列表无分页与角标缺失（问题 2/4）；
> ③ 交互细节（问题 5/6/7/8）。

版本基线：v0.2.22（远端 77c8b7e）

---

## 0. 根因：WS 地址永远推导失败（问题 1 / 3 / 9 同源）

### 证据（字节级）

```
$ unzip okhttp-4.12.0.jar "okhttp3/HttpUrl*" "okhttp3/Request*"
$ grep -a -o "wss" okhttp3/HttpUrl.class          → 无匹配
$ grep -a -o "wss" "okhttp3/Request\$Builder.class" → wss
```

`HttpUrl` **只支持 http/https**，`toHttpUrlOrNull()` 遇到 `ws://`/`wss://` 直接返回 null；
okhttp 官方支持 ws 的入口是 `Request.Builder.url(String)`，它内部有
「Silently replace web socket URLs with HTTP URLs」的 `ws→http` / `wss→https` 转换。

旧代码先拼 `ws://…` 再 `toHttpUrlOrNull()` ⇒ **恒为 null** ⇒ `ws.connect()` 第一行就
`return false`，从未真正发起过 WebSocket 握手。

### 改前原文（`wechat/WechatWsClient.kt` companion object）

```kotlin
        /**
         * REST 地址 → WS 地址。服务端 WS 路由是 `/ws/client`（`api/app.py:133`），
         * 鉴权走 query `token`（`api/auth.py:83`）。
         * 公开给 [WechatRuntime]：设置页要展示实际端点，便于排查「REST 通但 WS 连不上」。
         */
        fun toWsUrl(serverUrl: String): okhttp3.HttpUrl? {
            if (serverUrl.isBlank()) return null
            val s = serverUrl.trim().trimEnd('/')
            val ws = when {
                s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
                s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
                s.startsWith("ws://") || s.startsWith("wss://") -> s
                else -> "ws://$s"
            }
            return (ws + "/ws/client").toHttpUrlOrNull()
        }
```

### 改前原文（`wechat/WechatWsClient.kt` connect）

```kotlin
    fun connect(serverUrl: String, apiKey: String, deviceId: String, deviceName: String): Boolean {
        if (socket != null) return false
        val wsUrl = toWsUrl(serverUrl) ?: run {
            DiagLog.w(TAG, "服务器地址无法转换为 WS：$serverUrl")
            return false
        }
        intentionalClose = false
        val gen = ++generation
        val url = wsUrl.newBuilder().addQueryParameter("token", apiKey).build()
        val request = Request.Builder().url(url).build()
```

### 改前原文（`wechat/WechatRuntime.kt` wsEndpoint + connect）

```kotlin
    /** 设置页展示用：实际 WS 端点（排查「REST 通但 WS 连不上」时第一手信息）。 */
    fun wsEndpoint(context: Context): String =
        WechatWsClient.toWsUrl(WechatPrefs.serverUrl(context))?.toString()
            ?: "（地址无效，无法推导 WS 地址）"
```

```kotlin
        if (ws.isConnected()) return
        WechatState.setConnState(WechatConnState.CONNECTING)
        ws.connect(
            serverUrl = WechatPrefs.serverUrl(ctx),
            apiKey = WechatPrefs.apiKey(ctx),
            deviceId = WechatPrefs.deviceId(ctx),
            deviceName = android.os.Build.MODEL,
        )
```

### 副作用链（问题的因果链，留档备查）

`toWsUrl()==null` → `connect()` 返回 false → **没有任何回调** →
`WechatState.connState` 永远停在 CONNECTING，既不 DISCONNECTED（不触发 `onDisconnected`，
因而**不会进入 2s 重连循环**）也不 CONNECTED ⇒ 设置页显示「实时推送连接中」，
`onIncomingMessage` 永不回调 ⇒ 新消息不实时显示、不弹通知、不响提示音。
唯一的消息更新路径只剩「切走页面再切回来 → `onPageEnter` → `refreshAll()`」，
与用户描述「只有切换到其它页再切回才刷新」完全吻合。

---

## 1. 会话列表 / 通讯录没有加载更多（问题 2）

### 改前原文（`WechatRuntime.refreshSessions` / `refreshContacts`）

```kotlin
    fun refreshSessions() {
        val ctx = app ?: return
        if (WechatState.state.loadingSessions) return
        WechatState.setLoadingSessions(true)
        scope.launch {
            runCatching {
                val list = api!!.sessions(limit = 200)
                withContext(Dispatchers.Main.immediate) {
                    WechatState.setSessions(list, list.filter { !it.isMuted }.sumOf { it.unreadCount })
                }
            }.onFailure {
                DiagLog.w(TAG, "拉取会话失败：${it.message}")
                WechatState.setLoadingSessions(false)
            }
        }
    }
```

```kotlin
    fun refreshContacts() {
        val ctx = app ?: return
        scope.launch {
            runCatching {
                val account = WechatPrefs.currentWxid(ctx)
                val filter = WechatPrefs.contactsFilter(ctx)
                var list = api!!.contacts(accountWxid = account, filter = filter)
                // 兜底（v0.2.22 修复「通讯录空空如也」）：contacts 表里的 account_wxid 未必回填完整，
                // 此时带账号过滤会永远返回空列表。空结果时再不带账号拉一次全局联系人。
                if (list.isEmpty() && account.isNotBlank()) {
                    DiagLog.i(TAG, "按账号过滤无联系人，回退拉取全部联系人")
                    list = api!!.contacts(accountWxid = "", filter = filter)
                }
                withContext(Dispatchers.Main.immediate) { WechatState.setContacts(list) }
            }.onFailure { DiagLog.w(TAG, "拉取联系人失败：${it.message}") }
        }
    }
```

即：**一次性拉 200 条会话 / 默认 100 条联系人，之后无论怎么滑都不会再请求**，
也没有「已加载全部」的终点提示——用户无法区分「到顶了」还是「卡住了」。

---

## 2. 未读角标缺失 + 双击聊天按钮不跳转（问题 4）

### 改前原文（`ui/WechatPages.kt` WechatSessionRow 头像部分）

```kotlin
            WechatAvatar(wxid = session.userWxid, name = session.userNickname, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                ...
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
```

未读只在**行尾**显示，**头像右上角无红点角标**。

### 改前原文（`ui/WechatPages.kt` WechatSubBar 点击）

```kotlin
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clickable { onSelect(index) },
```

### 改前原文（`ui/MainScreen.kt` BridgeBottomBar 点击）

```kotlin
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable { onSelect(index) },
```

均无「双击 / 连击」概念，也无「跳到最新未读会话」的事件通道。

---

## 3. 退出微信不跳转主页（问题 5）

### 改前原文（`ui/WechatPages.kt` 退出微信按钮）

```kotlin
                        Button(
                            onClick = {
                                WechatController.logout(context)
                                confirm = false
                                wechatToast(context, "已退出微信，再次进入将自动重连")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WechatRed),
                        ) { Text("确认退出", fontSize = 12.sp) }
```

只释放资源，**停在微信页**（且停在「设置」子 Tab），没有任何回主页的动作。

---

## 4. 聊天输入框高度没压下来（问题 6）

### 改前原文（`ui/WechatPages.kt` 输入栏）

```kotlin
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (canSend) "请输入消息..." else "请先选择微信账号", fontSize = 13.sp) },
                    enabled = canSend,
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                )
```

v0.2.22 只把**两处搜索框**换成了 `WechatSearchField`（32.dp），
这里仍是 `OutlinedTextField` + `heightIn(min=36.dp)`——M3 的 minHeight 56dp 最小尺寸约束
优先于 `heightIn`，压不下去。

同文件其余 `OutlinedTextField`（连接卡片 341/343 行、设置页「修改服务器」1148/1150 行）
是**带 label 的整宽表单**，M3 默认高度属正常版式，本轮不动。

---

## 5. 发送后不滚动（问题 7）

### 改前原文（`ui/WechatPages.kt` 发送按钮）

```kotlin
                        scope.launch {
                            val r = WechatController.sendText(context, sessionWxid, text)
                            if (r.isSuccess) {
                                messages.add(selfMessage(sessionWxid, text))
                                WechatController.refreshSessions()
                            } else {
                                wechatToast(context, r.exceptionOrNull()?.message ?: "发送失败")
                            }
                        }
```

### 改前原文（附件发送回调）

```kotlin
            if (r.isSuccess) {
                messages.add(selfMessage(sessionWxid, "[文件] $name"))
                WechatController.refreshSessions()
            }
```

两处都只 add，**没有滚动**（`messages` 追加在底部，列表视口停在原处）。

---

## 6. 表情面板只有 24 个（问题 8）

### 改前原文（`ui/WechatPages.kt` WECHAT_EMOJIS）

```kotlin
private val WECHAT_EMOJIS = listOf(
    "\uD83D\uDE00", "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDE0A", "\uD83D\uDE0D", "\uD83D\uDE18", "\uD83D\uDE14", "\uD83D\uDE2D",
    "\uD83D\uDC4D", "\uD83D\uDE4F", "\uD83D\uDCAA", "\uD83C\uDF89", "❤️", "\uD83D\uDD25", "\uD83D\uDE05", "\uD83E\uDD14",
    "\uD83D\uDC4F", "\uD83C\uDF39", "\uD83C\uDF7A", "☕", "\uD83D\uDCB0", "\uD83D\uDE80", "⭐", "✅",
)
```

参考项目实际有 **118 个微信表情**（`web-frontend/src/utils/emoji.ts` 与
`pc-client/gui/utils/emoji_data.py` 同源），且**渲染时把 `[微笑]` 这类代码替换成 emoji**
（`replaceEmojiCodes`），Bridge 两样都没做——所以既「漏表情」，收到的 `[微笑]` 也显示成纯文本。

---

## 7. 提示音不响（问题 9）

### 改前原文（`wechat/WechatSound.kt` 全文核心）

```kotlin
    fun play(context: Context) {
        runCatching {
            val uri = Settings.System.DEFAULT_NOTIFICATION_URI
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
        }.onFailure {
            DiagLog.w(TAG, "播放提示音失败：${it.message}")
        }
    }
```

走系统默认通知铃声（很多 ROM 上被设为「无」⇒ 完全无声）。
参考 PC 客户端 `gui/utils/sound_manager.py` 用的是**自带 wav**：
`gui/resources/sounds/MSN消息.wav`（receive_message）、`叮一声.wav`（jump_unread）。

通知渠道侧（`WechatNotifier.channelId`）对 sound 渠道**没有静音**，
而 `remind()` 又同时调 `WechatSound.play()` ⇒ 修复后会「通知声 + 自播声」双响。

---

## 改后回填与回滚配方

### §8.1 改后：WS 地址推导（`wechat/WechatWsClient.kt`）

```kotlin
        fun toWsUrl(serverUrl: String): String? {
            if (serverUrl.isBlank()) return null
            val s = serverUrl.trim().trimEnd('/')
            val ws = when {
                s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
                s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
                s.startsWith("ws://") || s.startsWith("wss://") -> s
                else -> "ws://$s"
            }
            val raw = ws + "/ws/client"
            val probe = when {
                raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
                raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
                else -> raw
            }
            return if (probe.toHttpUrlOrNull() != null) raw else null
        }

        /** 带鉴权 token 的 WS 地址（连接用）。 */
        fun toWsUrl(serverUrl: String, apiKey: String): String? {
            val base = toWsUrl(serverUrl) ?: return null
            val sep = if (base.contains("?")) "&" else "?"
            return base + sep + "token=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
        }
```

```kotlin
        val url = toWsUrl(serverUrl, apiKey) ?: run {
            DiagLog.w(TAG, "服务器地址无法转换为 WS：$serverUrl")
            return false
        }
        intentionalClose = false
        val gen = ++generation
        DiagLog.i(TAG, "连接 WS：$url")
        val request = Request.Builder().url(url).build()
```

配套：`WechatRuntime.connect()` 改为返回 `Boolean`，发起失败时
`WechatState.setConnState(DISCONNECTED)`；`scheduleReconnect()` 在 `connect()` 返回 false 时
**`break` 退出循环**（⚠️ 绝不能 `cancel()`——那会取消共享的 Runtime scope）。

### §8.2 改后：分页

`WechatRuntime` 新增 `SESSION_PAGE = 50` / `CONTACT_PAGE = 50`、
`loadMoreSessions()` / `loadMoreContacts()`；`WechatState` 新增
`sessionsEnded / loadingMoreSessions / contactsEnded / loadingMoreContacts` 与
`appendSessions() / appendContacts()`。UI 侧新增 `WechatListFooter`：

- 加载中 → 转圈 + 「加载中...」
- 已到底 → **「已经加载全部 · 共 N 条」**
- 否则 → 「滑动到底部加载更多」

### §8.3 改后：角标 / 双击 / 退出跳主页

- 会话行头像：`if (unreadCount > 0) BadgedBox { Badge(containerColor = 免打扰?灰:红) }`
- 新增 `@Composable internal fun Modifier.tabClickable(index, onSelect, onDoubleTap)`（400ms 连击），
  用于 `WechatSubBar` 与 `BridgeBottomBar`
- `WechatState.requestJumpUnread()` / `requestExitToHome()` 自增令牌；
  微信页 `LaunchedEffect(jumpToken)` 跳未读并播「叮一声」；
  MainScreen `LaunchedEffect(exitToHome)` 切到主页 Tab

### §8.4 改后：输入框 / 滚动 / 表情 / 提示音

- `WechatChatInputField`（BasicTextField 自绘，36dp）替换聊天输入
- 发送文本与发送文件成功后 `listState.animateScrollToItem(messages.lastIndex)`
- 新增 `wechat/WechatEmoji.kt`（148 条，脚本从 `pc-client/gui/utils/emoji_data.py` 生成）；
  面板可滚动 8 列网格；`WechatEmoji.replaceCodes()` 用在消息文本、表情兜底、
  else 兜底、会话摘要、通知摘要
- 新增 `res/raw/wechat_msg.wav`（= 参考 `MSN消息.wav`）、`res/raw/wechat_jump.wav`（= `叮一声.wav`）；
  `WechatSound` 改为 SoundPool（`USAGE_MEDIA`，同扫码页 ScanFeedback 的验证结论），
  `BridgeApplication.onCreate` 里 `WechatSound.init(this)` 预热
- `WechatNotifier` 渠道 id 后缀 `_v2` 且**一律 `setSound(null, null)`**（单一发声源）

### §8.5 回滚配方

本次改动全部集中在 1 个提交内（v0.2.23），整包回滚：

```bash
git log --oneline -3                    # 找到 v0.2.23 的 sha
git revert --no-commit <v0.2.23-sha>    # 或 git checkout <sha>~1 -- <path>
git status                              # 确认只动微信相关文件
```

单文件回滚（只想退回某个改动时）：

```bash
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/wechat/WechatWsClient.kt
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/wechat/WechatState.kt
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/wechat/WechatSound.kt
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/ui/WechatPages.kt
git checkout <sha>~1 -- app/src/main/java/my/bridge/com/ui/MainScreen.kt
git checkout <sha>~1 -- app/build.gradle.kts
git clean -f app/src/main/java/my/bridge/com/wechat/WechatEmoji.kt app/src/main/res/raw/wechat_msg.wav app/src/main/res/raw/wechat_jump.wav
```

### §8.6 触发回滚的信号（真机上出现任一即回滚排查）

| 信号 | 说明 |
|---|---|
| 设置页仍显示「地址无效，无法推导 WS 地址」 | §8.1 没生效；用 `adb logcat -s 微信WS` 看「连接 WS：」打印 |
| WS 连上后**频繁断开重连** | 60s 心跳下 NAT 问题或服务端 300s 踢除；见 `docs/dev/heartbeat-inventory.md` §8.13 |
| 列表无限重复同一批数据 | offset 与 state 不同步；查 `appendSessions` 去重是否生效 |
| 提示音双响 | 老安装上旧通知渠道仍带声音；确认渠道 id 已是 `_v2` |
| 表情显示成豆腐块 | 部分 emoji（如 `🀅`）系统字体缺字形，属 ROM 差异，非 bug |
