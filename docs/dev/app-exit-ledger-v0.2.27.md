# v0.2.27 · 进程退出能力台账（切后台即结束 / 关于页「退出」）

> 日期：2026-11-01　版本：0.2.27　作者：Bridge Dev
> 需求来源：用户逐条反馈（①确认「微信离开后立即结束」语义　②通用设置加「切后台立即结束」　③关于页加「退出」）

---

## §1 需求确认：①「微信离开后立即结束」到底销毁了什么

**结论：是的**——开关（`设置 → 保活 → 微信离开后立即结束`，键 `WechatPrefs.release_on_leave`）打开后，
**只要离开微信页就销毁全部微信侧资源**，包括连接、心跳、重连、HTTP 客户端与内存数据。

链路（改前原文，`app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt`）：

```kotlin
    fun onPageLeave() {
        pageActive = false
        val ctx = app ?: return
        if (WechatPrefs.releaseOnLeave(ctx)) stopAll("离开微信页（已开启离开即释放）")
    }

    @Synchronized
    fun stopAll(reason: String) {
        DiagLog.i(TAG, "释放微信全部资源：$reason")
        reconnectJob?.cancel()
        reconnectJob = null
        ws.disconnect()
        stopVoice()
        api?.close()
        api = null
        WechatState.clearData()
        WechatState.setReleased(true)
        stopped = true
    }
```

逐项核对（改前代码，均未改动）：

| 资源 | 是否被销毁 | 证据 |
|---|---|---|
| WS 长连接 | ✅ | `ws.disconnect()` → `intentionalClose=true` → `teardown()`：`socket?.cancel()`、`socket=null` |
| 60s 心跳 | ✅ | `teardown()` 里 `handler.removeCallbacks(heartbeat)`，Pong 看门狗一并 remove |
| 断线重连 | ✅ | `reconnectJob?.cancel()`；且 `stopped=true` 后不会再自动重连 |
| HTTP 连接池/线程池 | ✅ | `api?.close()`（OkHttp 的 `dispatcher.executorService.shutdown()` + 连接池清理） |
| 语音播放 | ✅ | `stopVoice()` |
| 内存中的会话/消息/联系人 | ✅ | `WechatState.clearData()` |
| 网络状态回调 `NetworkCallback` | ❌ **未注销** | `stopAll` 里没有 `unregisterNetworkCallback`；但 `stopped` 守卫使回调不会触发重连，行为无害，只是回调对象还挂着（本次**不动**，避免扩大改动面） |

恢复：再次进入微信页时 `onPageEnter()` 检测 `stopped` → 重新 `connect()`（`WechatRuntime.kt:113-126`）。

---

## §2 改前原文（逐字留档）

### 2.1 `app/src/main/java/my/bridge/com/BridgeApplication.kt` · `onCreate()` 末尾

```kotlin
    override fun onCreate() {
        super.onCreate()

        // 日志：先初始化落盘目录（files/logs/），再桥接 Timber → DiagLog 内存缓冲
        // （release 也必须 plant，否则后端拉日志看不到网络层/无障碍/媒体等关键模块的日志）；
        // debug 额外 plant DebugTree 刷 Logcat，便于本地开发。
        my.bridge.com.core.common.DiagLog.init(this)
        Timber.plant(DiagLogTree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 安装崩溃自动上报（在连接/保活之前，尽早覆盖）
        CrashReporter(this, deviceStore, apiClient).install(enabled = true)

        // 无条件拉起常驻保活服务
        KeepAliveService.start(this)

        // 启动后端长连接（设备注册 + WebSocket）
        connectionManager.start()

        // 音乐播放状态上报（状态变化 → WS message 通道 → 管理后台实时展示/按钮联动）
        musicStateReporter.start()

        // 蓝牙耳机媒体按键监听（正常补获模式：其它媒体没在播时才持有焦点接按键，
        // 有媒体开播即让位、停播自动补获，事件驱动零轮询）。按键 → Toast + WS 上报后台。
        my.bridge.com.media.BridgeApplicationContextHolder.init(this)
        bluetoothMediaButtonListener.start(this)

        // 提示音预热（v0.2.23）：SoundPool 解码是异步的，启动时 load 好，
        // 避免第一条新消息「无声」（音源照参考 PC 客户端 MSN消息.wav）。
        my.bridge.com.wechat.WechatSound.init(this)

        // 微信（center-server）**进程级**运行时：连接常开，不随页面销毁而断。
        // 离开微信页仍要实时收消息并通知，因此这里就启动；未配置服务器时内部自行跳过。
        // 不新增前台服务——进程存活复用上面的 KeepAliveService 保活体系。
        my.bridge.com.wechat.WechatRuntime.start(this)

        // 主动上报总管：周期位置上报（服务定时+WorkManager 兜底）+ 新短信即推 +
        // 接收服务端 config_update 推送（后台远程开关，默认双开）
        reportManager.start()

        Timber.i("BridgeApplication 初始化完成")
    }
```

### 2.2 `app/src/main/java/my/bridge/com/ui/SettingsPages.kt` · `GeneralSettings()` 结尾（第 162-193 行）

```kotlin
        // v0.2.26：改前两处窗口都是硬编码 2 秒，现在 1~5 秒可调（默认仍 2 秒）
        item { SectionTitle("返回键") }
        item {
            SettingCard {
                BackWindowRow(
                    title = "再按一次返回主页",
                    desc = "微信 / 销售页主界面：第 1 次提示，该时间内第 2 次回主页",
                    seconds = homeWindowSec,
                    onChange = {
                        homeWindowSec = it
                        BackPressPrefs.setHomeWindowMs(context, it * 1000L)
                    },
                )
                Spacer(Modifier.height(4.dp))
                BackWindowRow(
                    title = "再按一次返回退出 Bridge",
                    desc = "主页 / 设置页首 Tab：第 1 次提示，该时间内第 2 次退出应用",
                    seconds = exitWindowSec,
                    onChange = {
                        exitWindowSec = it
                        BackPressPrefs.setExitWindowMs(context, it * 1000L)
                    },
                )
                Text(
                    "两项独立，默认均为 2 秒；改动立即生效，无需重启。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
```

### 2.3 `app/src/main/java/my/bridge/com/ui/SettingsPages.kt` · `AboutPage()` 检查更新卡片结尾（第 954-973 行）

```kotlin
                Text(
                    text = updateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
                // 缓存管理入口：列出已下载的更新包（含实际版本号），有选择地手动删除——
                // 万一下载过「假 44 实为 43」的污染缓存，从这里删掉即可恢复正常更新
                androidx.compose.material3.TextButton(
                    onClick = {
                        cacheList = my.bridge.com.update.UpdateManager.listCache(context)
                        cacheMsg = if (cacheList.isEmpty()) "暂无缓存（检查到新版本并下载后才有）" else ""
                        cacheVisible = true
                    },
                ) {
                    Text("管理更新缓存", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
```

---

## §3 设计要点（为什么这么写）

1. **「切后台」的判定**：用 `Application.ActivityLifecycleCallbacks` 计数（`onActivityStarted` +1 /
   `onActivityStopped` -1），归零即后台。**不引入 `lifecycle-process` 依赖**（现有依赖里没有，
   且 `ProcessLifecycleOwner` 的 ON_STOP 有 700ms 延迟，我们要的是「立刻」）。
2. **不能只 `killProcess`**：`KeepAliveService` / `AudioRelayService` / `MediaCaptureService` 都是
   `START_STICKY` 服务，直接杀进程会被系统重新拉起进程；且**有可见 Activity 时杀进程，AMS 会为恢复
   可见任务而重启进程**。故顺序固定为：`stopService` ×3 → `finishAffinity()` → 延迟 300ms →
   `killProcess` + `exitProcess`。
3. **开关默认关**：与「保活」的既定定位相反，误开的代价（后台再也收不到任何消息）远大于收益。
4. **熄屏也会触发**：Android 在熄屏时会 stop 前台 Activity，因此开关打开后**锁屏/熄屏同样会结束进程**。
   这是该开关的固有语义，已在设置项说明里写清楚。
5. **双兜底/开机自启不受影响**：它们由 JobScheduler/WorkManager/静态广播驱动，杀进程后仍可能按周期把
   APP 拉回来；若要彻底不回来，需同时关闭「双兜底唤醒」「开机自启」（设置项说明里已注明）。

---

## §4 改后（2026-11-01 已实施，`./gradlew :app:compileDebugKotlin` 通过）

### 4.1 新增 `app/src/main/java/my/bridge/com/AppExit.kt`

```kotlin
object AppExit {
    private const val TAG = "退出"
    private const val KILL_DELAY_MS = 300L
    @Volatile private var foregroundCount = 0
    @Volatile private var killing = false
    private var current: WeakReference<Activity>? = null

    fun install(application: Application) { /* ActivityLifecycleCallbacks 计数；归零 → onBackground */ }

    private fun onBackground(context: Context) {
        if (!AppExitPrefs.killOnBackground(context)) return
        DiagLog.i(TAG, "APP 已切到后台（开关已开）→ 立即结束进程")
        kill(context, "切到后台（已开启「切到后台立即结束」）")
    }

    fun kill(context: Context, reason: String) {
        if (killing) return
        killing = true
        // 1) 显式 stopService：KeepAliveService / AudioRelayService / MediaCaptureService
        // 2) WechatRuntime.stopAll(reason)：释放 WS / 心跳 / 重连 / HTTP / 内存数据
        // 3) current?.get()?.finishAffinity()：回桌面
        // 4) Handler.postDelayed(300ms) → Process.killProcess(myPid()) + exitProcess(0)
    }
}
```

### 4.2 新增 `app/src/main/java/my/bridge/com/ui/AppExitPrefs.kt`

`ui_prefs` → `kill_on_background`，**默认 false**；读取失败也回 false（宁可不明杀进程）。

### 4.3 `BridgeApplication.onCreate()`：崩溃上报之后、保活之前挂监听

```kotlin
        // v0.2.27：前台/后台计数监听。开关「设置 → 通用 → 切到后台立即结束」打开时，
        // 一旦所有 Activity 都 stop 就结束整个进程（AppExit）。默认关闭，这里只装监听不杀进程。
        AppExit.install(this)
```

### 4.4 `ui/SettingsPages.kt`

- `GeneralSettings()`：返回键小节之后新增 `SectionTitle("后台行为")` + 开关「切到后台立即结束」，
  说明含两条注意（后台收不到消息 / 双兜底与开机自启仍会拉回）。
- `AboutPage()`：检查更新卡片（含「管理更新缓存」）之后新增「退出应用」卡片，
  红色 Button → `AlertDialog` 二次确认 → `my.bridge.com.AppExit.kill(context, "用户点击退出（关于页）")`。

### 4.5 版本与文档

`app/build.gradle.kts` versionName → `0.2.27`；`server/data/updates/update.json`；
`docs/CHANGELOG.md` 新增 v0.2.27 条目。

---

## §5 回滚配方

```bash
cd /f/pythonWork/appClient
git checkout <本次提交 sha> -- app/src/main/java/my/bridge/com/BridgeApplication.kt \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt
git rm app/src/main/java/my/bridge/com/AppExit.kt app/src/main/java/my/bridge/com/ui/AppExitPrefs.kt
```

触发回滚的信号：①开启「切后台立即结束」后 APP 在前台被无故杀掉（说明前台计数算错）；
②点「退出」后进程被系统立刻重启（说明有 sticky 服务没停干净，需在 `AppExit.kill` 的
`stopService` 列表里补上对应 Service）。
