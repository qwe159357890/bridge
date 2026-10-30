# 保活方案设计

> 本文档归纳、总结两个参考项目的保活方式，作为 Bridge 保活模块（`core/core-keepalive`）的设计依据。

## 一、两个参考项目

| 项目 | 路线 | 核心手段 |
|---|---|---|
| Aurelay（声音中继） | 自研多层（12 层） | 常驻前台服务解耦 + 9 类资源锚点 + 一像素 + 无声播放 + 双兜底 |
| SmsForwarder（短信转发器） | 轻量框架 | 前台服务 + 电池白名单引导 + Cactus 框架 |

## 二、借鉴结论

- 吸收 Aurelay：前台服务与业务**解耦**、资源锚点、一像素、无声播放、Alarm+Job 双兜底、`START_STICKY`。
- 吸收 SmsForwarder：**电池优化白名单主动引导**（最有效）、开机自启、**在最近任务列表中隐藏**（防误杀）。
- 取舍：去掉 Aurelay 的「全量权限申请」激进做法；不引入第三方 Cactus 依赖，改自研。

## 三、最终方案（自研轻量模块）

| 层 | 组件 | 默认开关 |
|---|---|---|
| 常驻前台服务 | `KeepAliveService`（START_STICKY + HIGH 通知） | 开 |
| 资源锚点 | `ResourceHolder`（WakeLock/WifiLock/位置/传感器/蓝牙/相机） | 开 |
| 一像素锚点 | `OnePixelOverlay`（alpha 0.01 悬浮窗） | **关** |
| 无声播放 | `SilentPlayer`（±1 LSB 近静音） | 开 |
| 开机自启 | `BootReceiver` | 开 |
| 双兜底 | `KeepAliveReceiver`（Alarm）+ `KeepAliveJobService`（Job） | 开 |
| 电池白名单 | `KeepAliveUtils` 引导 | 用户手动 |
| 最近任务隐藏 | `KeepAliveUtils.setExcludeFromRecents`（MainActivity onCreate 按偏好应用 + 设置页开关即时生效） | 开 |

**最近任务隐藏细节**（v0.1.39，借鉴 SmsForwarder `enableExcludeFromRecents`）：把本应用任务
标记为 `excludeFromRecents`，最近任务列表不再出现 Bridge 卡片，上滑移除 / 一键清理都碰不到。
与原版差异：SmsForwarder 关开关只存偏好、需重启才恢复显示，Bridge 在开关切换时同步调
`setExcludeFromRecents(false)` 即时恢复。副作用：开启后用户只能从桌面图标进入 Bridge、
无法在最近任务里上滑杀进程（既是目的也是代价），关闭开关即恢复。

## 四、关键踩坑（务必遵守）

1. 前台通知必须 `startForeground` 而非 `notify`。
2. 通知渠道 `IMPORTANCE_HIGH`。
3. 一像素 alpha 必须 0.01 不能 0。
4. 无声播放只 pause+flush，绝不 stop；独立 AudioTrack；帧最低位 ±1 抖动。
5. Android 15+ 开机拉起前台服务受限需 try-catch。
6. 电池白名单引导是最有效手段。

## 五、代码位置

`core/core-keepalive/src/main/java/my/bridge/com/keepalive/`，共 9 个类。

---

## 附录：蓝牙耳机按键监听（已剥离，2026-10-28）

> 本节记录「蓝牙耳机媒体按键监听」功能的完整调研结论与经验教训。该功能已按用户决策
> **彻底剥离**（删除客户端 `BluetoothHeadsetObserver.kt` + 服务端 `media_button` 处理），
> 保留本节纯作经验沉淀，避免后人重踩。**注意：本节讲的是「耳机按键监听」，与保活的
> 「无声播放 `SilentPlayer`」「蓝牙 BLE 扫描 `ResourceHolder`」无关，二者功能独立、勿混淆。**

### 1. 目标与结论

需求：客户端 APP 接收蓝牙耳机「播放/暂停/上一首/下一首」等按键，先 Toast 展示动作。

**最终结论（不可达成的部分）**：Android 的蓝牙媒体按键（AVRCP）是**一对一的音频焦点
竞争**——按键永远只路由给「当前持有音频焦点的那个 MediaSession」。当同机其它媒体 App
（如 Aurelay）**正在出声播放**时，它持续持有焦点，系统会拒绝/延迟 Bridge 的焦点请求
（`requestAudioFocus` 返回 `FAILED`/`DELAYED`），**任何 App 都无法强行打断另一个正在
出声的 App 的焦点**。因此「无论其它 App 是否在出声，Bridge 都稳定拿到按键」这个目标，
在 Android 机制下**物理上无法达成**，只能「其它 App 不出声时 Bridge 能拿到」。这是
功能被剥离的根本原因，不是代码缺陷。

### 2. 能拿到按键的必要条件（四条件缺一不可）

实测逐步定位，每一条都是「收不到按键」的一个真实根因：

1. **`MediaSession.isActive = true`**——只 `setFlags(FLAG_HANDLES_MEDIA_BUTTONS)` 不
   active，媒体按键永不路由过来。
2. **`PlaybackState.setState(非 STATE_NONE)`**——必须 `setState(STATE_PLAYING, 0L, 1f)`
   （position 传 0 而非 UNKNOWN）。只 `setActions` 不 `setState` 会停在 STATE_NONE，
   系统媒体中心认为「没在播放」根本不路由按键。
3. **`requestAudioFocus(AUDIOFOCUS_GAIN)` 持久焦点**——`AUDIOFOCUS_GAIN_TRANSIENT`
   （瞬时焦点）在 Bridge 不实际出声时会被系统几百毫秒内静默收回，按耳机键那一刻焦点
   已不在 Bridge。
4. **焦点回调里遇 LOSS 立即重抢、绝不 abandonFocus**——早期实现 LOSS 时 `abandonFocus()`
   等于主动退出竞争，是抢不过 Aurelay 的直接原因。

### 3. 关键认知与坑

- **媒体按键只跟「音频焦点」走，跟蓝牙连接状态无关**。耳机连上不等于能收按键。
- **排查神器**：`adb shell dumpsys media_session` 看 `Media button session is com.xxx`
  指向谁（按键就给谁）；`adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` 模拟按键。
  蓝牙耳机按键的 keyCode：126 播放 / 127 暂停 / 87 下一首 / 88 上一首。
- **「启动即上报」必须等 WS 握手**：`onCreate` 里直接 `sendBusinessMessage` 会因
  `handshakeReady=false` 被静默丢弃，须监听 `handshakeReady.collect` 握手后补报。
- **静音播放 ≠ 抢焦点**：`SilentPlayer`（保活「无声播放」开关）只占 AudioTrack 输出、
  **不申请 AudioFocus**，它帮不上抢焦点；反之，抢焦点本身也不能让系统认为「正在出声」。
  二者是独立机制，不能互相替代（这曾导致两轮误判）。
- **结论性边界**：焦点竞争偏向「正在实际出声」的 App。Bridge 即便「持久焦点 + 静音锚点
  + 500ms 巡检刷新会话活跃」，也只能在其它 App **不出声**时稳定拿到按键；对方出声时
  系统焦点判给它，无法打破。

### 4. 剥离清单（供将来若需恢复时对照）

- 客户端：`app/src/main/java/my/bridge/com/media/BluetoothHeadsetObserver.kt`（已删）；
  `BridgeApplication.kt` 中的注入 + `start()`（已移除）。
- 服务端：`server/app/api/ws.py` 中 `_handle_media_button()` 与 `event == "media_button"`
  分支（已删除）。
- **保留**（勿误删）：保活 `SilentPlayer` / `ResourceHolder` BLE 扫描 / `app_version` 版本上报。


## 附录 3：前台化超时崩溃修复（v0.1.99，2026-10-30）

**现象**：偶发「Bridge 已停止运行」，难复现。adb dropbox 实锤堆栈：
`ForegroundServiceDidNotStartInTimeException`（KeepAliveService，触发源
KeepAliveJobService.onStartJob / KeepAliveReceiver.onReceive；v0.1.95~98 共 8 次；
更早 v0.1.74 时代 AudioRelayService 同款）。

**根因**：`startForegroundService()` 后系统给 5 秒期限调用 `startForeground()`。
旧 `promoteToForeground()` 用 catch **吞掉异常**——`startForeground` 实际失败时
（最可能：后台免疫窗口在 startForeground() 时刻二次校验的竞态，Android 12+ 后台
启动限制），期限一到系统直接崩溃进程。扫码/截屏与此无关（v0.1.98 的 safeExecutor
修复保留，属独立加固）。

**修复**：①promote 失败不再吞——DiagLog.e 记录完整异常 + 350ms×3 次重试（覆盖
竞态类瞬时失败，总窗口 ~1.05s ≪ 5s）；②重试耗尽 → `stopSelf()` 撤销本次前台启动
（系统取消倒计时，唯一防崩出口），等下次兜底/用户进 App 再拉起；③REFRESH 路径
同样防护；④AudioRelayService 降级分支裸 startForeground 再抛会穿透崩溃 → 补
runCatching + stopSelf。根因异常下次出现会带完整堆栈进服务端日志，可精确定位。

## 附录 4：冻结竞态真根因与修复（v0.2.00，2026-10-30）

**v0.1.99 修复后仍崩溃 2 次**（19:15/19:32，dropbox 实锤 v0.1.99）。服务器 DiagLog
里无「startForeground 失败」日志 + logcat 中崩溃进程零输出 → **Service.onCreate
在 30 秒窗口内从未执行**——v0.1.99 修在服务内部，修错了层。

**真根因（events log 时间线实锤）**：用户退后台 → MainActivity 销毁（19:21:34）→
进程缓存化被厂商冻结 → 15 分钟保活闹钟解冻投递 Job → onStartJob 调
startForegroundService 后**立即返回 false** → 任务结束、进程瞬间再冻结 →
排队的 Service.onCreate 饿死 → ~30s 超时崩溃。佐证：崩溃重启的新进程（未冻结）
里同一服务 1 秒内挂前台成功（19:32:28 通知入列）。

**修复（v0.2.00）**：兜底组件保持「处理中」撑住解冻窗口——
①KeepAliveJobService.onStartJob 返回 true + 4s 后 jobFinished；
②KeepAliveReceiver.goAsync() + 4s 后 finish。广播/任务组件存活期间系统不冻结
进程 → onCreate 在窗口内正常派发 → v0.1.99 的重试+stopSelf 防线照常生效。
