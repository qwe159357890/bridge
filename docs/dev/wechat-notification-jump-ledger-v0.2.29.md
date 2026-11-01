# v0.2.29 台账：通知点击「有时不进会话、落到主页」

> 版本：0.2.28 → **0.2.29**
> 日期：2026-11-01
> 触发：用户真机反馈「在 APP 内切到主页/销售/设置 → 切到别的 APP 一段时间 → 收到通知 → 点通知，
> 有时不进微信会话详情，而是进入程序主页；大多数时候能进详情」。
> 铁律：改动前**逐字固化改前原文**（git 未跟踪的文件 git 兜不住），改完回填改后 + 回滚配方。

---

## §1 跳转链路全景（改前）

```
WechatRuntime.remind()                      ← 收到新消息、非免打扰、非正在看
  └─ 构造 openIntent（getLaunchIntentForPackage + extra）
  └─ WechatNotifier.post(openIntent)        ← PendingIntent.getActivity(requestCode=2301, FLAG_UPDATE_CURRENT|IMMUTABLE)
用户点通知
  └─ AMS startActivity
       ├─ Activity 存活  → MainActivity.onNewIntent(intent)
       └─ Activity 已销毁 → MainActivity.onCreate(intent)
  └─ handleWechatDeepLink(intent) → WechatState.setPendingOpenSession(wxid)
  └─ MainScreen.LaunchedEffect(pendingWechat) → selectedBottom=1 / openWechatSession=wxid / clearPending
  └─ WechatPage.LaunchedEffect(initialSessionWxid) → openSession=wxid + WechatController.openSession()
```

---

## §2 改前原文（逐字留档）

### 2.1 `app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt`（remind()，332–346 行）

```kotlin
    private fun remind(ctx: Context, session: WechatSession, summary: String) {
        if (session.isMuted) {
            DiagLog.i(TAG, "免打扰会话不提醒：${session.userWxid}")
            return
        }
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

同文件尾部（719–720 行）：

```kotlin
/** 通知点击时携带的会话 wxid（MainActivity 读取后跳转微信页并打开该会话）。 */
const val WECHAT_EXTRA_OPEN_SESSION = "wechat_open_session"
```

### 2.2 `app/src/main/java/my/bridge/com/MainActivity.kt`（57–75 行）

```kotlin
    /**
     * 处理微信消息通知的 deep link。
     *
     * ⚠️ App 已在前台时，通知 PendingIntent 走 [onNewIntent]，`intent` 属性不会自动更新，
     * 必须 `setIntent()`；且 Compose 不会因此重组，故写入 [WechatState.pendingOpenSession]
     * 由 UI 侧消费（见 `MainScreen` 的 LaunchedEffect）。
     */
    private fun handleWechatDeepLink(intent: Intent?) {
        val wxid = intent?.getStringExtra(my.bridge.com.wechat.WECHAT_EXTRA_OPEN_SESSION)
        if (!wxid.isNullOrBlank()) {
            setIntent(intent)
            my.bridge.com.wechat.WechatState.setPendingOpenSession(wxid)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWechatDeepLink(intent)
    }
```

### 2.3 `app/src/main/java/my/bridge/com/ui/MainScreen.kt`（78–111 行）

```kotlin
    // 通知点击「微信消息」进来时直接落在微信页并打开对应会话（deep link）
    var openWechatSession by rememberSaveable { mutableStateOf<String?>(null) }
    // 底部导航当前选中项：0=主页 1=微信 2=销售 3=设置
    var selectedBottom by rememberSaveable { mutableIntStateOf(0) }
    ...
    // 通知点击（含 App 已在前台的 onNewIntent 场景）：切到微信 Tab 并打开指定会话
    val pendingWechat = my.bridge.com.wechat.WechatState.state.pendingOpenSession
    LaunchedEffect(pendingWechat) {
        if (pendingWechat.isNotBlank()) {
            selectedBottom = 1
            wechatNavVisible = false
            openWechatSession = pendingWechat
            my.bridge.com.wechat.WechatState.clearPendingOpenSession()
        }
    }
    // 「退出微信」后回主页（v0.2.23）：微信页设置里点「确认退出」会自增该令牌，
    // 这里观察到变化就切到「主页」Tab（令牌为 0 是首次组合，跳过）。
    val exitToHome = my.bridge.com.wechat.WechatState.state.exitToHomeToken
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

### 2.4 `app/src/main/java/my/bridge/com/ui/WechatPages.kt`（274–335 行，节选）

```kotlin
@Composable
fun WechatPage(
    initialSessionWxid: String? = null,
) {
    ...
    // 依赖 initialSessionWxid：通知点击带来新会话时（MainScreen 更新该值）能再次打开
    LaunchedEffect(initialSessionWxid) {
        WechatController.onEnter(context)
        if (initialSessionWxid != null) {
            openSession = initialSessionWxid
            WechatController.openSession(context, initialSessionWxid)
        }
    }
```

### 2.5 `MainScreen.kt` 微信分支调用（183–185 行）

```kotlin
                1 -> WechatPage(
                    initialSessionWxid = openWechatSession,
                )
```

---

## §3 根因分析（三个独立条件，任一命中都会「不进会话」）

### 条件 ①（最贴合用户复现路径）：通知 Intent 与 launcher Intent 完全相同 ⇒ AMS 可能不 deliver

`ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)` 返回的就是
`ACTION_MAIN + CATEGORY_LAUNCHER + component=MainActivity`，**与 task 的 base intent 一模一样**。
AMS 的 `Intent.filterEquals()` 比较 component/action/data/type/categories，**不比较 extras**。
在「task 已存在、仅从后台恢复」的路径上（用户切到别的 APP 后点通知，正是这条路径），
AMS 判定「与 task.intent 相同 → 只是把 task 带前台」，于是不走 `onNewIntent`，
`pendingOpenSession` 从未被写入 ⇒ UI 停在原页面（主页/销售/设置）。

对照：冷启动（进程被杀、task 重建）时走 `onCreate(intent)`，extras 一定送达 ⇒ 这就是
「大多数时候能进详情、有时不行」的差异来源——**取决于 task 是重建还是复用**。

### 条件 ②：`LaunchedEffect(exitToHome)` 重新进入组合时会重放，把「刚切到微信页」覆盖回主页

`LaunchedEffect(key)` **在重新进入组合时也执行**（v0.2.25 #1/#5 已栽过一次，jumpUnreadToken 已加守卫，
但 exitToHomeToken **没加**）。当 MainScreen 重新组合（Activity 因后台回收/配置变更而重建）时：

- `LaunchedEffect(pendingWechat)` 先跑：`selectedBottom = 1`、`openWechatSession = A`
- `LaunchedEffect(exitToHome)` 后跑（token > 0，即用户此前用过微信页「双击返回键回主页」）：
  **`selectedBottom = 0`、`openWechatSession = null`** ⇒ 落到主页，deep link 被丢弃。

### 条件 ③：同一个 wxid 连续两次通知 ⇒ `LaunchedEffect` key 不变、不再打开

`openWechatSession` 用 `rememberSaveable` 长期保留上次值。用户点过会话 A 的通知后返回会话列表
（`WechatPage.openSession = null`，但 `MainScreen.openWechatSession` 仍是 `"A"`），
此时会话 A 再来新消息 → 点通知 → `openWechatSession = "A"`（同值，State 不变化、不重组）
→ `WechatPage.LaunchedEffect(initialSessionWxid)` 的 key 仍是 `"A"` ⇒ **不重启** ⇒ 停在会话列表。

---

## §4 改后（实施完成后回填）

见 §7。

---

## §5 回滚配方

```bash
git checkout <本次提交 sha>^ -- \
  app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt \
  app/src/main/java/my/bridge/com/MainActivity.kt \
  app/src/main/java/my/bridge/com/ui/MainScreen.kt \
  app/src/main/java/my/bridge/com/ui/WechatPages.kt
```

触发回滚的信号：点通知后**打开的不是通知里那个会话**（串会话）、或微信页被反复重建/闪烁、
或底部导航状态错乱（切不回主页）。

---

## §6 验证方法（真机）

1. 设置 → 关于 → 版本号确认为 0.2.29。
2. 复现步骤：进 APP → 切到「主页」→ 切到别的 APP → 等另一设备/PC 端发消息 → 点通知。
   期望：直接落在微信页该会话详情；连测 5 次（含**同一会话连续来两条**）。
3. 反向验证：在微信页双击返回键回主页（令 exitToHomeToken > 0）→ 再走一次步骤 2，
   期望仍然进会话详情（不再被覆盖回主页）。
4. 日志关键字（`adb logcat | grep -E "微信通知跳转|MainActivity"`）：
   - `通知跳转 → onNewIntent: <wxid>` / `onCreate: <wxid>` —— 证明 intent 送到了
   - `通知跳转 → UI 消费: <wxid>` —— 证明 MainScreen 已消费
   - `通知跳转 → 打开会话: <wxid> token=N` —— 证明 WechatPage 已打开
   **缺哪一条，就定位到哪一段。**

---

## §7 改后回填

### 7.1 通知 Intent 唯一化（`WechatRuntime.remind()` + 文件尾部常量）

```kotlin
/** 通知点击的专用 action：**不能**再复用 launcher 的 ACTION_MAIN —— 见台账 §3 条件 ①。 */
const val WECHAT_ACTION_OPEN_SESSION = "my.bridge.com.action.OPEN_WECHAT_SESSION"

        if (WechatPrefs.notifyEnabled(ctx)) {
            // v0.2.29：显式构造（component 明确 + 专用 action + 唯一 data），
            // 不再用 getLaunchIntentForPackage()——那个 intent 与 task 的 launcher intent
            // 完全相同（filterEquals 忽略 extras），AMS 在「后台恢复」路径上可能只把 task
            // 带前台而不回调 onNewIntent，导致点了通知只回主页/停在原页面。
            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = WECHAT_ACTION_OPEN_SESSION
                data = android.net.Uri.parse("bridge://wechat/session/${android.net.Uri.encode(session.userWxid)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(WECHAT_EXTRA_OPEN_SESSION, session.userWxid)
            }
```

### 7.2 `MainActivity.handleWechatDeepLink`：extra 优先、data 兜底 + 全链路日志

```kotlin
    private fun handleWechatDeepLink(intent: Intent?, from: String) {
        val wxid = intent?.getStringExtra(my.bridge.com.wechat.WECHAT_EXTRA_OPEN_SESSION)
            ?.takeIf { it.isNotBlank() }
            ?: intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        Timber.i("通知跳转 → $from: ${wxid ?: "(无会话)"}")
        if (!wxid.isNullOrBlank()) {
            setIntent(intent)
            my.bridge.com.wechat.WechatState.setPendingOpenSession(wxid)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWechatDeepLink(intent, "onNewIntent")
    }
    // onCreate 里：handleWechatDeepLink(intent, "onCreate")
```

### 7.3 `MainScreen`：`exitToHome` 加「已消费令牌」守卫；新增 `openWechatToken` 自增令牌

```kotlin
    var openWechatToken by remember { mutableIntStateOf(0) }   // 非 saveable：重建后归 0

    LaunchedEffect(pendingWechat) {
        if (pendingWechat.isNotBlank()) {
            DiagLog.i("通知跳转", "UI 消费：$pendingWechat")
            selectedBottom = 1
            wechatNavVisible = false
            openWechatSession = pendingWechat
            openWechatToken++                                  // 保证同 wxid 也能再次打开
            my.bridge.com.wechat.WechatState.clearPendingOpenSession()
        }
    }

    val exitToHome = my.bridge.com.wechat.WechatState.state.exitToHomeToken
    var consumedExitToHome by remember { mutableIntStateOf(exitToHome) }   // 守卫：首次组合不触发
    LaunchedEffect(exitToHome) {
        if (exitToHome == consumedExitToHome) return@LaunchedEffect
        consumedExitToHome = exitToHome
        selectedBottom = 0
        wechatNavVisible = true
        openWechatSession = null
    }
    ...
    1 -> WechatPage(initialSessionWxid = openWechatSession, openToken = openWechatToken)
```

### 7.4 `WechatPage`：参数新增 `openToken`，`LaunchedEffect(openToken)` 保证每次点击都打开，并强制 `subTab = 0`

```kotlin
fun WechatPage(
    initialSessionWxid: String? = null,
    openToken: Int = 0,
) {
    ...
    // 订阅只在进入页面时做一次（与 DisposableEffect/onLeave 配对），
    // 不能再和「打开会话」共用一个 effect——否则每条通知都会重复订阅/刷新会话。
    LaunchedEffect(Unit) { WechatController.onEnter(context) }

    LaunchedEffect(openToken) {
        if (openToken > 0 && initialSessionWxid != null) {
            DiagLog.i("通知跳转", "打开会话：$initialSessionWxid token=$openToken")
            subTab = 0                                   // 上次停在通讯录/设置也要回到「聊天」
            openSession = initialSessionWxid
            WechatController.openSession(context, initialSessionWxid)
        }
    }
```

编译校验：`JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1+1" ./gradlew :app:compileDebugKotlin` → **exit 0**。
