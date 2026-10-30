# 崩溃台账（Crash Ledger）

> 宗旨：崩溃是致命问题，必须 100% 杜绝。本文档是崩溃问题的**唯一权威汇总**：
> 每次崩溃的根因、修复、以及「同类向量全量审计」的结果都记录在此。
> **下次崩溃先查本文档 §4 排障工具链，再对照 §1 时间线定性**；
> 新服务/新前台组件接入前，必须对照 §5 检查清单逐条核对。
> 修复状态总览见 §6。保活专题细节见 `docs/keepalive/design.md`（附录 3/4/5）。

---

## 1. 崩溃事件时间线（v0.1.95 ~ v0.2.01）

「Bridge 已停止运行」，dropbox 签名全部为
`ForegroundServiceDidNotStartInTimeException`（前台服务未按时挂前台）。
同签名，**三个不同根因**——同一签名 ≠ 同一根因，必须逐次实锤。

| # | 根因 | 命中版本 | 触发链 | 修复 |
|---|---|---|---|---|
| 1 | **promote 吞异常**：`startForeground()` 实际失败但异常被 catch 吞掉，5s 倒计时无人理会 | v0.1.95~98（8 次） | 兜底 Job/Receiver 拉起服务 → 挂前台抛异常（后台免疫窗口竞态）被吞 → 超时崩 | v0.1.99：不再吞，DiagLog.e 全栈记录 + 350ms×3 重试 + 耗尽 stopSelf |
| 2 | **冻结饿死**：进程被 ColorOS 冻结，排队的 `Service.onCreate` 在 30s 窗口内永远派发不下来 | v0.1.99（2 次） | onStartJob 返回 false → 任务瞬间结束 → 进程再冻结 → onCreate 饿死 | v0.2.00：JobService 返回 true + 4s 后 jobFinished；Receiver goAsync + 4s 后 finish，撑住解冻窗口 |
| 3 | **重复拉起义务悬空**：服务**已在运行**时再次 `startForegroundService()`，系统只派发 `onStartCommand`（onCreate 跳过），旧代码不补挂前台 → 义务无人履行 | v0.2.00（**8 次**：20:06~20:07 共 2 次 + 20:28~20:31 共 6 次；Frozen=false、进程存活 15.6min/39s 等） | 周期兜底 Job（~15min）到点重复拉起运行中的服务 | v0.2.01：onStartCommand 中 promoted=true 时无条件补挂前台（幂等） |

**崩溃循环机理（v0.2.00 期间 20:28~20:31 实测，每 ~44 秒一崩）**：
根因 3 崩溃后，兜底 Job 标记了「处理中」（返回 true）但进程随即崩溃，`jobFinished()`
没来得及调用 → JobScheduler 判定任务未完成 → 进程一重启**立刻重投递** → 再次义务悬空
→ 再次崩溃 → 无限循环。**止血手段**：`adb shell am force-stop my.bridge.com`
（force-stop 会清掉 JobScheduler 的待投递任务）。「崩溃循环」不是新根因的信号，
看到 40~50 秒间隔的连续同签名崩溃即按此机理理解。

放大因素：**蓝牙焦点拉锯**（v0.1.76~v0.2.00 的旧版监听器永久持有 GAIN 焦点且丢失即重抢），
每次与其它媒体 App 争焦点都把（可能冻结的）进程唤醒一次，显著放大根因 2 的触发概率。
v0.2.01 改「正常补获」：其它媒体播放即让位、停播自动补获（事件驱动零轮询），
该放大因素消除。

## 2. 铁律（每条都有线上崩溃背书）

1. **`startForegroundService()` ≠ 「启动服务」，是「预约一次前台化义务」**：
   - 义务**按次计**——每次调用都要求一次 `startForeground()`，即使服务已在前台；
   - 服务运行中重复拉起**不走 onCreate**，`onStartCommand` 必须履行义务；
   - 义务 ~30s（实测，非文档说的 5s）内无人履行 → 系统直接崩进程。
2. **`startForeground()` 抛异常必须接住**：FGS 类型/权限校验、后台免疫窗口竞态都可能抛；
   接住后 `stopSelf()` 是唯一防崩出口（等下次拉起）。绝不允许裸调用。
3. **兜底组件必须保持「处理中」**：JobService 返回 true + 延迟 jobFinished；
   Receiver goAsync + 延迟 finish。立即返回 = 进程瞬间可被冻结，排队的 onCreate 饿死。
4. **崩溃修复必须先确认「代码到底有没有执行」**：
   - 服务端 DiagLog 缺失本身就是证据（v0.1.99 修错层的直接原因）；
   - dropbox 的 `Frozen:` / `Process-Runtime:` 字段区分「冻结饿死」（Frozen=true）与
     「运行中义务悬空」（Frozen=false、Runtime 长）。
5. **修完一个点 ≠ 修完一类**：必须按 §5 检查清单全量审计同向量调用点。
6. **崩溃循环 = 40~50 秒间隔连续同签名崩溃**：兜底组件「处理中」状态未被正常结束
   （进程在 jobFinished/finish 前崩溃）导致任务重投递。先 force-stop 止血，再修根因。

## 3. 全量审计结果（2026-10-30，v0.2.01）

### 3.1 `startForegroundService` 全部调用点 → 服务映射

| 调用点 | 目标服务 | 后台启动异常防护 | 义务履行 | 状态 |
|---|---|---|---|---|
| `BridgeApplication.onCreate`（启动） | KeepAliveService | try/catch（start 内） | onCreate promoteWithRetry + onStartCommand 补挂（v0.2.01） | ✅ |
| `KeepAliveJobService.onStartJob`（15min 兜底） | KeepAliveService | try/catch | 同上 + 保持处理中 4s（v0.2.00） | ✅ |
| `KeepAliveReceiver.onReceive`（15min 闹钟兜底） | KeepAliveService | try/catch | 同上 + goAsync 4s（v0.2.00） | ✅ |
| `KeepAliveService.refresh`（设置页开关） | KeepAliveService | try/catch | 同上 | ✅ |
| `AudioRelayService.start`（UI「连接」按钮） | AudioRelayService | try/catch（v0.2.01 补） | startRelay 挂前台；**运行中重复拉起补挂（v0.2.01 补）** | ✅ |
| `AudioRelayService.stop/setVolume` | AudioRelayService | 走普通 `startService`，无义务 | 有运行守卫，不误拉起 | ✅ |
| 通知「停止接收」按钮（PendingIntent.getService） | AudioRelayService | 普通 startService | — | ✅ |
| `CommandModule`×4（screenshot/photo/video/audio 命令） | MediaCaptureService | 全部 try/catch | onStartCommand 主路径挂前台；**并发拒绝路径补挂（v0.2.01 补）**；挂前台异常 catch+stopSelf（v0.2.01 补） | ✅ |

### 3.2 其它已消除/已验证的崩溃向量

| 向量 | 结论 |
|---|---|
| `RejectedExecutionException`（executor shutdown 后迟到回调） | `util/SafeExecutors.kt` 统一包装，静默丢弃（v0.1.98，独立加固） |
| WS 发送异常穿透 | `sendBusinessMessage` 全 try/catch 返回 Boolean；未连接静默丢弃 |
| 协程未捕获异常 | CrashReporter 兜底上报；长生命周期 scope 均为 SupervisorJob |
| FGS 类型/权限声明缺失（Android 14 硬校验） | manifest 已声明齐全：`FOREGROUND_SERVICE` + mediaPlayback/mediaProjection/camera/microphone 全套权限与 `foregroundServiceType` |
| HeadlessSmsSendService / BridgeAccessibilityService | 非 FGS，无此向量 |
| 崩溃上报链路 | CrashReporter v0.1.98 重写后已通：同步落盘 `files/crash/last_crash.txt` + runBlocking 3.5s 上报 + 启动补传；服务端 MySQL 日志库可查 |

## 4. 排障工具链（下次崩溃按此顺序）

0. **正在崩溃循环时先止血**：`adb shell am force-stop my.bridge.com`
   （同时清掉 JobScheduler 待投递任务，打破循环）。
1. **服务端日志库**（首选，已打通）：
   `server/.venv/Scripts/python.exe` +
   `from app.config import get_settings; from app.core.log_store import build_log_store;
   store = build_log_store(get_settings().locations.mysql); store.list('<device_id>', limit=60)`
   设备列表：`store.devices()`。Crash 记录含时间/版本/完整堆栈。
2. **adb dropbox 历史堆栈**（设备在手时）：
   - 列表：`adb shell dumpsys dropbox | grep data_app_crash`
   - 详情：`adb shell dumpsys dropbox --print <YYYY-mm-dd> <HH:MM:SS> data_app_crash`
   - **先看 `Frozen:` 和 `Process-Runtime:` 字段**定根因方向（§2 铁律 4）。
3. **events buffer 时间线**：`am_crash` / `wm_*` / `notification_*` 还原前后事件。
4. adb 走无线（单命令内 `connect 192.168.31.109:5555` + 操作，server 会被回收）。

## 5. 新前台服务接入检查清单（逐条打勾后才准合入）

- [ ] manifest 声明 `foregroundServiceType` + 对应 `FOREGROUND_SERVICE_*` 权限；
- [ ] 所有 `startForegroundService` 调用点 try/catch（后台启动限制会抛异常）；
- [ ] `onCreate` 挂前台，且 `startForeground` 有 try/catch + 失败 stopSelf 防崩；
- [ ] **`onStartCommand` 对每次拉起都履行前台化义务**（promoted 后补挂幂等），
      不依赖「onCreate 一定会执行」；
- [ ] 任何早退分支（拒绝/守卫/STOP）返回前都确认义务已履行；
- [ ] 有后台兜底（Job/Alarm）拉起时：返回 true / goAsync 保持「处理中」≥4s；
- [ ] 新调用点登记到本文档 §3.1 表格。

## 6. 修复状态总览（随版本更新维护）

| 根因 | 修复版本 | 落地验证 |
|---|---|---|
| 1 promote 吞异常 | v0.1.99 | ✅ 该形态未再复现 |
| 2 冻结饿死 | v0.2.00 | ✅ 该形态未再复现 |
| 3 重复拉起义务悬空（含 3 个服务的同款修复） | v0.2.01 | ✅ 已验证（2026-10-30 21:12 装机，观察 27min 跨 2 次周期 Job：dropbox 零新增崩溃（最后一次=20:57:37 v0.2.00），服务端日志库 0 条 Crash/ERROR） |
| 放大因素：蓝牙焦点拉锯 | v0.2.01（改「正常补获」） | ✅ 崩溃维度已随观察期验证；按键行为待日常使用中确认（其它媒体播放不抢停、停播后按键回归） |

**v0.2.01 一揽子内容**：三服务义务悬空修复（KeepAlive/AudioRelay/MediaCapture）+
蓝牙按键正常补获 + CrashReporter 链路确认可用（服务端 MySQL 已见 Crash 记录）。

## 7. 关联运维事件备忘（非崩溃，但排查时易混淆）

| 事件 | 现象 | 根因与修复 | 教训 |
|---|---|---|---|
| 更新失败误报（2026-10-30 21:05） | 客户端提示版本校验失败，拒绝安装 v0.2.01 | 服务端 `_scan_latest` 用整数元组回拼版本串，"0.2.01"→"0.2.1" 吃掉前导零，触发客户端「改名冒充」防伪校验。修复：服务端保留原始版本串 + 客户端比对数值归一化 | 防伪校验比对的是**字符串相等**，任何格式归一化差异都会误伤；版本语义比较一律走数值归一化 |
| 排查期误杀 8000 端口进程 | 本机另一 main.py 实例（非 Bridge）被停止 | 重启服务器前未先核对端口归属 | 杀进程前必须 `Get-NetTCPConnection` 核对端口→进程映射；Bridge 服务器 = server/.venv python + 端口 **15160**（config.yaml） |
