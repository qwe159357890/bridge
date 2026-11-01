# 心跳与周期任务台账

> **权威文档**：本项目所有「心跳 / 保活 / 周期任务」的唯一台账。  
> 调整任何周期前先查本文档；调完必须回来更新本表。  
> 背景：v0.2.20 微信集成引入第 5 套长连接后，连接数已达 5 条，周期参数散落各处，  
> 本文档用于避免「改一处、忘四处」。

---

## 一、全景清单

### 1.1 进程级常驻（App 启动即连，永不主动断）

| 连接          | 源码                                                                      | 应用层心跳   | 传输层 ping             | Pong/判死 | 重连                                             |
| ----------- | ----------------------------------------------------------------------- | ------- | -------------------- | ------- | ---------------------------------------------- |
| Bridge 主 WS | `core/core-network/.../BridgeWebSocketClient.kt`<br />参数在 `WsConfig.kt` | **60s**（对齐栅格） | 无（未设 `pingInterval`） | 10s     | 1s→60s 指数退避 ±20%                               |
| 微信 WS       | `app/.../wechat/WechatWsClient.kt`                                      | **60s**（对齐栅格） | **已移除**（原 20s）        | 30s     | 固定 2s ＋ 网络恢复即刻重连                               |
| 周期位置上报      | `app/.../report/ReportManager.kt`                                       | —       | —                    | —       | 10 分钟（服务端可配 1–120）+ WorkManager 兜底（系统下限 15 分钟） |

### 1.2 业务期常驻（服务运行期间）

| 连接   | 源码                                                        | 应用层心跳           | 传输层 ping | 判死                       | 重连                |
| ---- | --------------------------------------------------------- | --------------- | -------- | ------------------------ | ----------------- |
| 音频中继 | `app/.../relay/RelayClient.kt`<br />常量 `RelayProtocol.kt` | **30s** 文本 ping | 无        | 60s 无数据判死 + ready 超时 10s | 1/2/4/8/16/30s 退避 |

### 1.3 页面级（进页面才连，退出即断）

| 连接          | 源码                                    | 应用层心跳   | 传输层 ping | 重连        |
| ----------- | ------------------------------------- | ------- | -------- | --------- |
| 销售 WS       | `app/.../sales/SalesWsClient.kt`      | **30s** | **20s**  | 固定 3s     |
| ESP32 开关 WS | `app/.../switch/RelaySwitchClient.kt` | 无       | **20s**  | 固定 3s     |
| 扫码上传 WS     | `app/.../scanner/ScanUploadClient.kt` | 连上即探测一次 | **15s**  | 5s→60s 退避 |

> **生命周期已源码核实（2026-11-01）**：`MainScreen.kt:158` 用 `when (selectedBottom)` 切 Tab，
> 切走即把旧页面移出组合树 → `DisposableEffect.onDispose` 触发 → 连接**彻底关闭**，
> **应用层心跳与 OkHttp `pingInterval` 同时停止**（`pingInterval` 只对活跃 WS 连接调度，close 后不再发）。
> 断连证据：销售 `SalesPages.kt:186`；ESP32 `RelaySwitchPages.kt:104`；扫码 `ScanPages.kt:672`。
> ⇒ 这三条在「一天只开几分钟」的实际使用习惯下**耗电可忽略**。
>
> ⚠️ **顺带发现的小不一致（未改）**：扫码页（661 行）用 `remember(uploadOn, uploadHostText)` 会判断上传开关；
> 但**会话列表页 `ScanSessionListPage`（1388 行）无条件创建并 start 上传客户端**，未判断 `uploadOn`。
> 仅在该页可见期间生效，影响小，但两处口径不一致。

### 1.4 常驻基线（不是心跳，但 24h 持续耗电）

| 项                 | 源码                                          | 默认    | 周期                       |
| ----------------- | ------------------------------------------- | ----- | ------------------------ |
| PARTIAL_WAKE_LOCK | `core-keepalive/.../ResourceHolder.kt:99` | **开** | 持有即 CPU 永不休眠             |
| WifiLock          | 同上 `:120`（`WIFI_MODE_FULL_HIGH_PERF`） | **开** | 持有即 WiFi 不进省电（**Android 14+ 已失效，见下**） |
| SilentPlayer 无声播放 | `core-keepalive/.../SilentPlayer.kt`        | **开** | 每 **20ms** 写一次 48kHz 音频帧 |
| JobService 存活窗口   | `core-keepalive/.../KeepAliveJobService.kt` | —     | `ALIVE_WINDOW_MS = 4s`   |

### 1.5 前端（不耗手机电，仅备案）

管理后台 `admin/src/views/connection/index.vue` 自动刷新轮询，默认 **3s**，可配。

---

## 二、各连接的周期硬约束（改之前先看这里）

| 连接                | 服务端超时                                                                                          | 客户端 Pong 超时           | 放宽的实际上限                                            |
| ----------------- | ---------------------------------------------------------------------------------------------- | --------------------- | -------------------------------------------------- |
| Bridge 主 WS       | `server_read_timeout=150s`（`config.yaml`，v0.2.20+ 由 45s 放宽）                                   | 10s（`WsConfig`）       | 间隔须 < 150s；当前 60s（2.5 倍余量）                         |
| 微信 WS             | **`CLIENT_HEARTBEAT_TIMEOUT=300s`**（center-server `core/connection_manager.py:16`，检测循环 60s 一轮） | 30s（`WechatWsClient`） | **间隔 < 300s 即可，服务端零改动**；当前 60s（5 倍余量）              |
| 销售 / 中继 / 开关 / 扫码 | 参考项目各有约定                                                                                       | —                     | 页面级连接，退出即断，耗电不敏感                                   |



> **重要纠正（v0.2.20 期间的错误结论）**：早前记录「微信心跳放宽到 30s 需服务端同步调 Pong 超时」  
> 是错的。中心服务器 WS 主循环是裸 `while True: await websocket.receive_text()`（`api/routes/client.py:630`），  
> **没有读超时**；唯一约束是 `CLIENT_HEARTBEAT_TIMEOUT = 300`。  
> 因此 15s → 30s / 60s / 120s 全部合法且**服务端零改动**。
>
> 真正卡住「心跳上限」的不是服务端，是**运营商/路由器 NAT 空闲超时**（移动网络常见 30s–5 分钟，  
> 家用/企业 NAT 常见 5–30 分钟）。间隔超过 NAT 超时 → 连接变「半死」，双向都收不到包，  
> 只能等客户端 Pong 超时才能发现。

---

## 三、耗电模型（量级估算，非实测）

### 3.1 单次唤醒的无线增量

| 网络                | 单次孤立唤醒增量                | 说明                                          |
| ----------------- | ----------------------- | ------------------------------------------- |
| LTE 移动数据          | **约 1.5–4 J，取中值 2.5 J** | RRC 重建约 0.5 J ＋ 约 10s tail 维持约 2.0 J；弱信号可翻倍 |
| WiFi            | **约 0.05–0.3 J**        | 无 RRC 重建那种焦耳级开销；即便进 power save，单次唤醒仍比 LTE 低一个数量级 |

换算：1 mAh ≈ 13.9 J（3.85 V 锂电）。

> **关于「WiFi 已持 WifiLock ⇒ 射频常开」这个前提**：在 **Android 14（API 34）及以上已经不成立**。
> `WIFI_MODE_FULL_HIGH_PERF` 于 API 34 被废弃，平台**自动替换成 `WIFI_MODE_FULL_LOW_LATENCY`**，
> 而后者仅在「已连 AP ＋ **屏幕亮着** ＋ **App 在前台**」三条同时满足时才生效
> （见 `WifiManager` 官方文档）。⇒ 在 Bridge 最关心的「后台 ＋ 灭屏」场景里这把锁**根本不生效**。
> 分类：**Android 13 及以下**设备这把锁真生效、真耗电（关掉可省电）；
> **Android 14 及以上**它是 no-op（关掉省不了电，因为本来就没开）。它只对 WiFi 有效，也不防 Doze。

### 3.2 单条长连接的日耗电（按 5000 mAh 电池）

| 间隔   | 次/小时 | mAh/天（LTE） | 占电池   | mAh/天（WiFi） |
| ---- | ---- | ---------- | ----- | ----------- |
| 15s  | 240  | ~1036      | ~21%  | ~21         |
| 20s  | 180  | ~777       | ~16%  | ~16         |
| 30s  | 120  | ~518       | ~10%  | ~10         |
| 60s  | 60   | ~259       | ~5%   | ~5          |
| 120s | 30   | ~130       | ~2.6% | ~3          |
| 300s | 12   | ~52        | ~1%   | ~1          |

参考值：微信 Mars STN 自适应心跳下限约 **4.5 分钟**；FCM 长连接心跳建议 ~15 分钟（移动网络）。

### 3.3 三个必须知道的结论

1. **WiFi 场景下，调心跳间隔的收益很小。** 15s→30s 大约只省 **10–100 mAh/天**（0.2%–2%，
   取决于 Wi-Fi 是否真的在 power save）。**只有当设备跑在移动数据下，间隔才是关键变量。**
2. **常驻基线比心跳更值得优化。** `PARTIAL_WAKE_LOCK` + `WifiLock` + `SilentPlayer`（20ms/tick）  
   三者叠加，量级约 **400–1500 mAh/天**，与 15s 心跳同数量级甚至更大。  
   其中 SilentPlayer 每 20ms 唤醒一次写音频帧，是三者里最激进的一项。
3. **合并心跳（coalescing）的收益 > 放宽间隔。** 多条连接的心跳若错相，各自独立触发一次无线唤醒；  
   对齐到同一时刻后共用一次唤醒。当前进程级就有 3 个计时器（15s + 20s + 20s），  
   错相约 600 次唤醒/小时，对齐到 20s 栅格后约 180 次/小时，**省约 70% 且零可靠性损失**。

---

## 四、已识别的优化点（按性价比排序）

> **状态更新（2026-11-01）**：第 1、2、3 项**已实施**，实际取值改为「微信 60s / Bridge 60s / 双端对齐 60s 栅格」
> （比原计划的 30s 更激进，因用户确认不需考虑版本兼容）。完整改前/改后与回滚配方见 **§8**。
> 第 4、5 项**未实施**，见 §8.12。

| # | 优化                                                                                                                                    | 预期收益                        | 风险                                                                   |
| - | ------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- | -------------------------------------------------------------------- |
| 1 | **去掉双层心跳冗余**：保留应用层 ping（能测 RTT、能判应用存活），把 `pingInterval` 设为 0 或 ≥ 2×间隔作为兜底。**实际只对微信 WS 有意义**——销售/开关/扫码是页面级，退出即断（见 §1.3），冗余开销≈0 | 微信这一条：唤醒次数减半 | 低。需确认对端会自动回 WS ping 帧（OkHttp 会，center-server 走 starlette/uvicorn 也会） |
| 2 | **心跳对齐**：把进程级连接的心跳对齐到同一相位（如整 20s 栅格）                                                                                                  | 省约 70% 无线唤醒                 | 低                                                                    |
| 3 | **微信间隔 15s → 30s**（服务端零改动，Pong 超时 60s 已够）                                                                                             | LTE 下省约 518 mAh/天；WiFi 下可忽略 | 低。30s 仍远小于服务端 300s 与常见 NAT 超时                                        |
| 4 | **SilentPlayer 降频 / 默认关**：20ms tick → 100ms+，或仅在检测到进程被压制时启用                                                                           | 常驻基线里最大的单项                  | 中。它就是为抗杀而设计的，降频可能削弱保活效果，需真机对照                                        |
| 5 | 微信间隔放宽到 60s/120s                                                                                                                      | LTE 下再省约 260–390 mAh/天      | 中。需把客户端 `PONG_TIMEOUT_MS` 同步调到 ≥ 2×间隔；且要实测 NAT 是否撑得住                 |

---

## 五、怎么实测（不要靠模型拍板）

```bash
# 1. 重置统计（选移动数据网络，关 WiFi，拔掉 USB 或用无线调试）
adb shell dumpsys batterystats --reset
# 2. 亮屏一次确认 App 已启动 + 保活服务在跑，然后熄屏静置 N 小时
# 3. 取报告
adb shell dumpsys batterystats > batterystats.txt
# 4. 关键看这几段：
#    - Estimated power use (mAh)  里的 my.bridge.com 汇总
#    - Per-app mobile ms / radio active ms
#    - Wake lock 持有时长（应接近 100%，因为默认开）
```

更精确的做法：

- **对照法**：同一台设备、同一网络、熄屏静置 8 小时，分别在「心跳 15s」「心跳 30s」「心跳 60s」下跑，  
  比较 `batterystats` 里 `radio active` 与总 mAh 的差值。这是唯一可信的数字。
- **区分基线与心跳**：把 `WechatRuntime` 整体停掉（保留保活服务）跑一轮，  
  再只停心跳（保持连接）跑一轮，两次差值即为心跳的真实净成本。
- 用 Battery Historian 看 `wake_lock_in` / `mobile_radio` 的条形密度，能直观看到对齐效果。

---

## 六、保活三锁的语义与取舍

设置页文案（`SettingsPages.kt:205/206/212`）：「⚡ CPU 唤醒锁」「📡 WiFi 高性能锁」「🔇 无声播放」，
对应 `ResourceHolder.acquireWakeLock` / `acquireWifiLock` 与 `SilentPlayer`，由
`KeepAliveService.onCreate → applyKeepAlive → ResourceHolder.acquireAll` 触发，三者默认全开。

### 6.1 PARTIAL_WAKE_LOCK（CPU 唤醒锁）

只做一件事：**阻止 CPU 进入深睡**。它不是"能不能联网"的开关。

**取消后的三个独立后果，必须分开看：**

| 维度 | 结论 |
| --- | --- |
| 收推送（下行） | **仍能收到。** 入站数据包由射频固件唤醒 AP，这是 Wi-Fi/蜂窝的固有能力，与 WakeLock 无关 |
| 发心跳（上行） | **发不出去。** CPU suspend 后用户态定时器全部冻结（`Handler.postDelayed` / `coroutine delay` / OkHttp ping 调度器都不再按点触发），只有 AlarmManager `RTC_WAKEUP`、JobScheduler、FCM 能唤醒系统 |
| 后台网络限制 | 取决于 **Doze**，不取决于 WakeLock。而 Doze 的进入条件之一就是"无 App 持 wakelock"——所以现在持锁实际上**顺带挡住了 Doze**；取消锁后 App 暴露在 Doze 之下，此时能否收包**完全看是否在电池优化白名单里**（白名单豁免 Doze，网络不受限） |

**最阴险的失效模式**：取消锁 → 心跳发不出 → NAT 映射过期（移动网 30s–5 分钟）→ 连接变"半死"。
此时客户端 `socket != null` 自认在线，服务端推的包被 NAT 丢弃，**收不到且不知道自己收不到**，
要等客户端 Pong 看门狗（微信 60s）或 TCP 超时才发现。

**还能收到 WS 消息吗（分档）**：
- 白名单在 + 心跳发得出去 → 能收
- 白名单在 + 无心跳 → 几十秒~几分钟后开始收不到，且静默失效
- 无白名单 + Doze → 维护窗口前收不到，延迟可达数分钟~十几分钟
- 进程被杀 → 完全收不到（这是保活要解决的另一回事）

**真正能断网的另有其人**：省流量模式（Android 7+，只限移动数据）、App Standby（限 Job/Alarm 频率，不断网）、
国产 ROM 的"后台联网"独立开关（MIUI/ColorOS 有，关掉则后台 socket 直接被切）。

> 设置页自己的说明（`SettingsPages.kt:374`）已写明：「CPU 唤醒锁为核心手段，关闭会明显降低保活效果」。

### 6.2 WifiLock（WiFi 高性能锁）

`WIFI_MODE_FULL_HIGH_PERF`：告诉 Wi-Fi 固件"别进省电"。关掉后**不是连不上、也不是明显变慢**——
能正常连、正常收发，只是射频可以在空闲时短暂打盹，唤醒时多几十毫秒级延迟，对长连接保活几乎无感。

**但 Android 14（API 34）起它已被平台自动替换成 `WIFI_MODE_FULL_LOW_LATENCY`**，
后者仅在「已连 AP ＋ 屏幕亮着 ＋ App 在前台」时生效 ⇒ **后台灭屏场景下这把锁不生效**（详见 §3.1 注）。

### 6.3 SilentPlayer（无声播放）

`AudioTrack` 循环播放 ±1 LSB 近静音 PCM，48 kHz，每 **20ms** 写一次帧（`TICK_MS = 20L`）。
不申请 AudioFocus、不改系统音量、只 pause+flush 绝不 stop。目的：抬高进程优先级抗杀。
三者里**最激进**的一项——每 20ms 唤醒一次写音频帧，直接阻止任何深度空闲。
代价是明确的持续耗电；收益是抗杀（正是本项目保活的核心诉求）。降频需真机对照，不可拍脑袋。

---

## 七、更新纪律

改动任何一处周期，必须同步：

1. 更新本文档 §1 对应表格；
2. 检查 §2 的约束（服务端超时、客户端 Pong 超时、NAT）；
3. 若涉及 Bridge 主 WS，同时核对 `docs/api/ws-client.md` 与 `server/config.yaml` 的  
   `heartbeat.interval` / `client_pong_timeout` / `server_read_timeout` 三者一致性  
   （**该组参数按约定双端硬编码、无配置入口，见 `WsConfig` 注释**）。

---

## 八、变更台账 v1：心跳放宽 + 去双层冗余 + 相位对齐（2026-11-01）

> **本节的唯一目的**：把「改之前是什么样」逐字固化，配合「改之后是什么样」与回滚配方，
> 做到任何一步出问题都能 **100% 精确还原**。改前状态在动手前记录，改后状态在改完核对。
>
> 背景决策：用户确认**不需要考虑版本兼容**（每次大更新强制用户升到最新版、服务端同步更新），
> 因此采用 60s 方案，服务端 `server_read_timeout` 同步放宽。

### 8.0 回滚总配方（最优先看这里）

> **2026-11-01 09:2x 更新**：微信集成已提交（commit `1036b54`），**本次涉及的文件现已全部入库**，
> git 均可一键还原，不再有「兜不住」的文件。下方配方已同步指向新基线 `1036b54`。

**全部文件（git 可一键还原）**：`WsConfig.kt`、`BridgeWebSocketClient.kt`、`SilentPlayer.kt`、
`WechatWsClient.kt`、`server/config.yaml`、`server/app/config.py`、`server/tests/test_config.py`、
`docs/api/ws-client.md`、`docs/server/design.md`、`docs/CHANGELOG.md`、`docs/INDEX.md`。

**方式一：整提交回滚（推荐，心跳改动是独立 commit）**

```bash
git revert 555d0f6             # 心跳改动的独立 commit；反向提交可保留历史
                               # 若需彻底抹掉（连同工作区回到改前）：git reset --hard 1036b54
```

**方式二：按文件精确还原（保留微信集成等其他改动）**

```bash
git checkout 1036b54 -- core/core-network/src/main/java/my/bridge/com/core/network/ws/WsConfig.kt
git checkout 1036b54 -- core/core-network/src/main/java/my/bridge/com/core/network/ws/BridgeWebSocketClient.kt
git checkout 1036b54 -- core/core-keepalive/src/main/java/my/bridge/com/keepalive/SilentPlayer.kt
git checkout 1036b54 -- server/config.yaml server/app/config.py server/tests/test_config.py
git checkout 1036b54 -- docs/api/ws-client.md docs/server/design.md docs/INDEX.md
# docs/CHANGELOG.md 的心跳里程碑段落需手工删除（该文件还含微信集成条目，不能整体还原）
# 新增的台账文档本身不受影响；如需删除：rm docs/dev/heartbeat-inventory.md
```

**⚠️ 例外（git 还原不了，必须手工）：`app/src/main/java/my/bridge/com/wechat/WechatWsClient.kt`**

原因：`wechat/` 整个目录此前一直是 untracked，它**第一次入库就是在 commit `1036b54`、
且入库时已带上本次心跳改动**（15s→60s、去 pingInterval、修 sendPing）。
也就是说 **git 历史里从来不存在该文件的改前版本**——`git checkout 1036b54 -- <该文件>`
拿到的是**改后**代码，不能用于回滚。

还原该文件只能按 **§8.4 的改前原文逐字抄回**（改回 `HEARTBEAT_MS = 15_000L`、
`PONG_TIMEOUT_MS = 60_000L`、恢复 `.pingInterval(20, TimeUnit.SECONDS)`、恢复原 `sendPing()`）。
这是本项目当前**唯一**一个 git 兜不住的心跳改动点，务必留意。

**方式三：手工还原（git 不可用时的兜底）**：按 §8.2~8.8 的改前原文逐字抄回。

### 8.1 改前基线总览（2026-11-01 09:15 实测）

| 位置 | 改前值 |
| --- | --- |
| `WsConfig.HEARTBEAT_INTERVAL_MS` | `20_000L`（20s） |
| `WsConfig.PONG_TIMEOUT_MS` | `10_000L`（10s） |
| `WechatWsClient.HEARTBEAT_MS` | `15_000L`（15s） |
| `WechatWsClient.PONG_TIMEOUT_MS` | `60_000L`（60s） |
| `WechatWsClient` OkHttp `pingInterval` | `20, TimeUnit.SECONDS`（**已开启**） |
| `server/config.yaml` heartbeat.interval | `20` |
| `server/config.yaml` server_read_timeout | `45` |
| `server/app/config.py` 同上默认值 | `20` / `45` |
| `server/tests/test_config.py:26-27` 断言 | `== 20` / `== 45` |
| 心跳相位 | **无对齐**，各连接按各自连上时刻起算 |
| 进程级无线唤醒（估） | 微信应用 240/h ＋ 微信传输 180/h ＋ Bridge 180/h ≈ **600 次/小时** |

### 8.2 改前原文 · WsConfig.kt（L11 / L30-34）

```kotlin
// 文件头注释 L11：
 *   heartbeat.interval=20s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=20s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** 发送 ping 后等待 pong 的超时（毫秒）。与服务端 client_pong_timeout=10s 一致。 */
    const val PONG_TIMEOUT_MS = 10_000L
```

### 8.3 改前原文 · BridgeWebSocketClient.kt（L292）

```kotlin
                delay(WsConfig.HEARTBEAT_INTERVAL_MS)
```

### 8.4 改前原文 · WechatWsClient.kt（未跟踪文件，逐字备份）

```kotlin
    companion object {
        private const val TAG = "微信WS"
        private const val HEARTBEAT_MS = 15_000L
        private const val PONG_TIMEOUT_MS = 60_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 传输层 ping 兜底：尽早发现半死连接（照 SalesWsClient 的做法）
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
```

```kotlin
    private fun sendPing() {
        handler.removeCallbacks(heartbeat)
        if (!send(JSONObject().put("type", "ping"))) return
        // Pong 超时：60s 内没收到 pong 判定连接已死
        val watchdog = Runnable {
            DiagLog.w(TAG, "Pong 超时，判定连接已死")
            teardown()
            listener?.onDisconnected("Pong 超时")
        }
        pongWatchdog = watchdog
        handler.postDelayed(watchdog, PONG_TIMEOUT_MS)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }
```

```kotlin
    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }
```

> ⚠️ **改前已存在的缺陷（必须修，且与去传输层 ping 强相关）**：
> `sendPing()` 里 `if (!send(...)) return` —— 发送失败时 `heartbeat` 已被 `removeCallbacks` 移除
> 且**不再重排**，心跳永久静默停止。改前有传输层 20s ping 兜底尚可掩盖；
> 去掉传输层 ping 后必须改掉，否则心跳一停就只剩服务端 300s 才踢才发现。

### 8.5 改前原文 · SilentPlayer.kt（L160-170）

```kotlin
    /** 生成近静音帧：最低位 ±1 抖动，避免全 0 帧被判空流回收 */
    private fun nextFrame(): ByteArray {
        val buffer = ByteArray(FRAME_BYTES)
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
```

> 改前每个 tick 都 `new ByteArray(1920)`：20ms tick ⇒ 50 次/秒 ⇒ **约 96 KB/s ≈ 8.3 GB/天** 的对象分配。

### 8.6 改前原文 · server/config.yaml（L12-15）

```yaml
heartbeat:
  interval: 20                   # 客户端心跳发送周期（秒）
  client_pong_timeout: 10        # 客户端 ping 后等 pong 超时（秒）
  server_read_timeout: 45        # 服务端读超时（秒），须 > interval（建议 > 2 倍）
```

### 8.7 改前原文 · server/app/config.py（L42-44）与 tests/test_config.py（L26-27）

```python
    interval: float = Field(default=20, gt=0, description="客户端心跳发送周期（秒）")
    client_pong_timeout: float = Field(default=10, gt=0, description="客户端 ping 后等 pong 超时（秒）")
    server_read_timeout: float = Field(default=45, gt=0, description="服务端读超时（秒）")
```

```python
    assert s.heartbeat.interval == 20
    assert s.heartbeat.server_read_timeout == 45
```

> 注意：`get_settings()` 会**先加载 `server/config.yaml`**（`config.py:272`），
> 所以 `test_default_settings` 实际断言的是 config.yaml 的值 —— 改 config.yaml 必然要同步改这两行断言。

### 8.8 改前原文 · 文档

| 文件 | 改前 |
| --- | --- |
| `docs/api/ws-client.md:88` | 「每 `20s` 发送 `ping`；`ping` 后 `10s` 内未收到 `pong` …」 |
| `docs/api/ws-client.md:164` | 表格「心跳周期 \| 20s \| 客户端发 ping 周期；服务端读超时 45s > 2×20s」 |
| `docs/server/design.md:71-73` | 表格 `interval` 20s / `client_pong_timeout` 10s / `server_read_timeout` 45s |
| `docs/server/design.md:109` | 「（服务端侧：45s 未收任何消息）」 |
| `docs/server/design.md:349-350` | 示例 `client_pong_timeout: 10` / `server_read_timeout: 45` |

---

### 8.9 改后状态（2026-11-01 09:2x，改动完成后实测核对）

| 位置 | 改前 | **改后** |
| --- | --- | --- |
| `WsConfig.HEARTBEAT_INTERVAL_MS` | 20_000L | **60_000L** |
| `WsConfig.PONG_TIMEOUT_MS` | 10_000L | **10_000L（未动）** |
| `WsConfig.delayToNextGrid()` | 不存在 | **新增**（对齐栅格） |
| `BridgeWebSocketClient` 心跳 delay | `delay(HEARTBEAT_INTERVAL_MS)` | **`delay(WsConfig.delayToNextGrid())`** |
| `WechatWsClient.HEARTBEAT_MS` | 15_000L | **60_000L** |
| `WechatWsClient.PONG_TIMEOUT_MS` | 60_000L | **30_000L** |
| `WechatWsClient` OkHttp `pingInterval` | 20s（开启） | **已移除**（协议层不再单独 ping） |
| `WechatWsClient.sendPing()` | 发送失败即停跳（缺陷） | **无论成败都重排下一栅格** |
| `WechatWsClient.scheduleHeartbeat()` | 固定 `HEARTBEAT_MS` | **`delayToNextGrid()`** |
| `SilentPlayer.nextFrame()` | 每 tick `new ByteArray(1920)` | **复用 `frameBuffer`** |
| `server/config.yaml` interval / server_read_timeout | 20 / 45 | **60 / 150** |
| `server/app/config.py` 同默认值 | 20 / 45 | **60 / 150** |
| `server/tests/test_config.py:26-27` | == 20 / == 45 | **== 60 / == 150** |
| 心跳相位 | 无对齐 | **Bridge 与微信共用 Unix 毫秒 60s 栅格** |
| 进程级无线唤醒（估） | ~600 次/小时 | **~60 次/小时（−90%）** |
| 死连接最长发现窗口（微信） | 15+60 = 75s | **60+30 = 90s** |
| 死连接最长发现窗口（Bridge） | 20+10 = 30s | **60+10 = 70s** |

### 8.10 改后原文（逐字，供核对 / 反向还原）

**WsConfig.kt**
```kotlin
 *   heartbeat.interval=60s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。
 *   （v0.2.20+ 心跳由 20s 放宽到 60s，服务端 server_read_timeout 同步 45s → 150s；
 *    详见 docs/dev/heartbeat-inventory.md §8）

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=60s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /**
     * 距下一个心跳栅格的毫秒数（取值区间 (0, HEARTBEAT_INTERVAL_MS]）。
     * ...（对齐说明见源文件）
     */
    fun delayToNextGrid(nowMs: Long = System.currentTimeMillis()): Long =
        HEARTBEAT_INTERVAL_MS - (nowMs % HEARTBEAT_INTERVAL_MS)
```

**BridgeWebSocketClient.kt（原 L292）**
```kotlin
                // 对齐到心跳栅格：与微信 WS 共用一次无线唤醒（见 WsConfig.delayToNextGrid）
                delay(WsConfig.delayToNextGrid())
```

**WechatWsClient.kt（未跟踪文件，改后关键片段）**
```kotlin
        private const val TAG = "微信WS"
        // 心跳 60s：服务端 CLIENT_HEARTBEAT_TIMEOUT=300s，留 5 倍余量，服务端零改动即可。
        private const val HEARTBEAT_MS = 60_000L
        // Pong 超时 30s（原 60s）：随间隔放宽同步收紧，把「连接静默死亡」的最长发现窗口
        // 从 60+60=120s 压回 60+30=90s。30s 对任何合理的 WS 往返仍是极大余量。
        private const val PONG_TIMEOUT_MS = 30_000L
```

```kotlin
    private fun sendPing() {
        handler.removeCallbacks(heartbeat)
        // 【修复】无论本次发送成功与否都必须重排下一次心跳。
        val sent = send(JSONObject().put("type", "ping"))
        if (sent) {
            val watchdog = Runnable {
                DiagLog.w(TAG, "Pong 超时，判定连接已死")
                teardown()
                listener?.onDisconnected("Pong 超时")
            }
            pongWatchdog = watchdog
            handler.postDelayed(watchdog, PONG_TIMEOUT_MS)
        } else {
            DiagLog.w(TAG, "心跳发送失败，等待下一栅格重试（不再依赖传输层 ping）")
        }
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    private fun delayToNextGrid(): Long =
        HEARTBEAT_MS - (System.currentTimeMillis() % HEARTBEAT_MS)
```

```kotlin
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 传输层 ping（OkHttp pingInterval）已于 v0.2.20+ 移除：① 对保活 NAT 与应用层 ping
        // 完全重复；② WS ping 帧由 uvicorn 协议层自动回 pong，传不到 ASGI 应用层，
        // 不会刷新 center-server 的 last_ping（client.py:635）。
        .build()
```

**SilentPlayer.kt**
```kotlin
    private val frameBuffer = ByteArray(FRAME_BYTES)

    private fun nextFrame(): ByteArray {
        val buffer = frameBuffer
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
```

### 8.11 验证结果

| 验证项 | 结果 |
| --- | --- |
| `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL**（JDK 17，2026-11-01） |
| `uv run pytest tests/test_config.py`（server/） | **5 passed** |
| 旧值残留扫描（代码侧） | 无。`SalesWsClient` 30s 为**页面级**保持原样；`CommandModule.CAPTURE_TIMEOUT_MS=20_000` 与心跳无关 |

### 8.12 仍未做 / 待真机验证

1. **真机实测**：按 §5 对照法测「改前 vs 改后」的日耗差；重点确认 60s 心跳在实际运营商 NAT 下是否稳定
   （若出现连接频繁静默死亡，说明该运营商 NAT 超时 < 60s，应回退到 30s）。
2. **相位对齐效果验证**：`adb shell dumpsys batterystats` 观察 `mobile_radio` 条形是否收敛成 1 分钟一次的规律脉冲。
3. SilentPlayer **本次未动 tick**（仍是 20ms），只做了缓冲区复用。见 §6.3 与 §3.3：调 tick 收益仅占其自身成本 10–25%，
   建议先做「开 / 关」真机对照再决定是否动。

### 8.13 触发回滚的信号

出现任一情况，按 §8.0 回滚并回到 20s / 15s：

- 管理后台设备频繁上下线（服务端读超时误判）
- 微信消息延迟明显变长或长时间收不到（NAT 超时 < 60s）
- 日志出现 `Pong 超时` 频率显著上升
- 设备待机耗电不降反升（说明重连次数增加抵消了心跳收益）
