# 日志治理台账（v0.2.130，2026-11-10）

> **本节唯一目的**：按项目「改前留档」约定，把「改之前是什么样」逐字固化，配合「改之后」与
> 回滚配方，做到任何一步出问题都能 100% 精确还原。
>
> 背景：用户 16:51 实测 v0.2.129 后提问「42 分钟 299 条日志是不是需要精简」。数据画像：
> 42 分钟 299 条 ≈ 7 条/分钟，总量不高，但 **VNT 组网 95 条（32%）+ 微信 WS 61 条（20%）
> 全是同一网络抖动根因的反复重写**，E 级 14 条 / W 级 26 条大半是重连失败。
> 用户拍板「按 4 条执行」：①重连风暴降频合并 ②「默认网络可用」日志降级/删
> ③ BLE hex dump 改 debug 开关 ④智能控制启动日志汇总。
>
> **⚠️ 关键前提（决定实现形态）**：`DiagLog` 的 D 级日志**仍会进内存环形缓冲 + 落盘 + 上传**
> （`DiagLogTree.isLoggable()` 恒 true；`append()` 只是不刷 Logcat）。因此「降为 D 级」
> **不能减少服务端日志量**——②③ 必须做成「真正不产生日志行」的开关/删除。
>
> **设计原则**：只保留「状态变更骨架」（连接成功/失败、播报、音量回读），砍掉「过程性重复」。
> 反证（用户案例）：v0.2.129 假恢复问题靠 I 级「回读=8」破案，`setStreamVolume` 静默忽略
> 不抛异常——只记 E/W 会让这类问题永远无解。

## §0 改前基线（动手前实测）

| 项 | 值 |
| --- | --- |
| git 基线 | `000e07e`（本地）/ `dfe3b0d`（远端，API 重建） |
| 版本 | v0.2.129 |
| 涉及文件（**全部 git 已跟踪**） | `core/core-common/.../DiagLog.kt`（新增节流器不改本体）、`VntVpnService.kt`、`WechatRuntime.kt`、`WechatWsClient.kt`、`JikongBleClient.kt`、`JikongPrefs.kt`、`JikongPages.kt`、`RelaySwitchClient.kt` |
| 服务端 | `server/` 无改动 |

**回滚总配方（一键）**：

```bash
git checkout 000e07e -- \
  app/src/main/java/my/bridge/com/vnt/VntVpnService.kt \
  app/src/main/java/my/bridge/com/wechat/WechatRuntime.kt \
  app/src/main/java/my/bridge/com/wechat/WechatWsClient.kt \
  app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt \
  app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt \
  app/src/main/java/my/bridge/com/ui/JikongPages.kt \
  app/src/main/java/my/bridge/com/switch/RelaySwitchClient.kt
rm -f core/core-common/src/main/java/my/bridge/com/LogThrottle.kt
# docs/CHANGELOG.md 的 v0.2.130 段落需手工删除（该文件含其它版本条目，不能整体还原）
```

**触发回滚的信号**：①重连后日志里既看不到失败原因也看不到恢复汇总（节流器误吞状态翻转）
②BLE 排障时打开开关仍无 hex ③智能控制设备状态不刷新（汇总改动碰到业务逻辑）
④编译失败。

## §1 改前原文 · VntVpnService.kt

### §1.1 L228-233 网络回调日志（第 2 条治理点）

```kotlin
            override fun onAvailable(network: Network) {
                // 只置标志，不在回调里做任何重活（回调跑在系统线程）；
                // 监督循环的退避等待以 250ms 粒度轮询该标志（最长 60s，开销可忽略）。
                networkJustRecovered = true
                DiagLog.i(TAG, "默认网络可用，重连等待将立即结束并重置退避")
            }
```

### §1.2 L298-305 失败轮日志（第 1 条治理点）

```kotlin
            attempt++
            val delaySec = BACKOFF_SEC[(attempt - 1).coerceAtMost(BACKOFF_SEC.size - 1)]
            DiagLog.w(
                TAG,
                "组网失败（可重试）：${res.reason}；第 $attempt 次重连将在 ${delaySec}s 后进行",
            )
            VntMeshController.onReconnecting(attempt, delaySec, res.reason)
```

### §1.3 L370-391 native 回调过程日志（每轮 4 条）

```kotlin
                override fun success() {
                    DiagLog.i(TAG, "native 回调 success（注册完成）")
                }

                override fun createTun(info: DeviceInfo?) {
                    // 仅 windows/linux/macos 使用，Android 走 generateTun
                }

                override fun connect(info: ConnectInfo?) {
                    DiagLog.i(TAG, "连接服务端：${info?.address}（第 ${info?.count} 次）")
                }

                override fun handshake(info: HandshakeInfo?): Boolean {
                    serverVersion = info?.version
                    DiagLog.i(TAG, "握手：服务端版本=${info?.version}，指纹=${info?.finger}")
                    return true                 // 确认握手
                }

                override fun register(info: RegisterInfo?): Boolean {
                    val ip = info?.let { IpUtils.intToIpAddress(it.virtualIp) } ?: "?"
                    DiagLog.i(TAG, "注册成功：$ip")
                    return true                 // 确认注册信息
                }
```

### §1.4 L428-433 成功日志 / L441-459 错误与 stop

```kotlin
                    if (gen == generation) {
                        hadConnected.set(true)
                        VntMeshController.onConnected(ip, prefix, cfg, mtu, serverVersion)
                        promoteForeground("已连接 · $ip", connected = true)
                        DiagLog.i(TAG, "VNT 已连接：$ip/$prefix，服务端版本 ${serverVersion ?: "?"}")
                    }
```

```kotlin
                override fun error(info: ErrorInfo?) {
                    val e = info ?: return
                    val text = "${e.code}" + (e.msg?.let { "：$it" } ?: "")
                    DiagLog.e(TAG, "native 回调错误：$text")
```

```kotlin
                override fun stop() {
                    DiagLog.i(TAG, "native 回调 stop")
                }
```

### §1.5 L487-496 catch 失败日志

```kotlin
        } catch (e: Throwable) {
            DiagLog.e(TAG, "组网尝试失败", e)
            val code = runtimeErrorCode ?: startupErrorCode
```

## §2 改前原文 · WechatRuntime.kt / WechatWsClient.kt（第 1 条）

```kotlin
    private fun reconnectNow(reason: String) {
        DiagLog.i(TAG, "重连（$reason）")
        ws.disconnect()
        scope.launch { connect() }
    }
```

```kotlin
                if (stopped) return
                DiagLog.i(TAG, "网络可用，检查连接")
                if (WechatPrefs.configured(ctx) && !ws.isConnected()) reconnectNow("网络恢复")
```

```kotlin
        DiagLog.i(TAG, "WS 已连接 clientId=$clientId")
```

```kotlin
        DiagLog.w(TAG, "WS 断开：$reason")
```

```kotlin
        DiagLog.i(TAG, "连接 WS：$url")
```

```kotlin
                DiagLog.i(TAG, "WS 已连接，发送 register")
```

```kotlin
                DiagLog.w(TAG, "WS 失败：${t.message}")
```

## §3 改前原文 · JikongBleClient.kt（第 3 条）

```kotlin
    /** 通知 hex 日志预算（每会话前 3 条，防灌爆上传缓冲，v0.2.81）。 */
    @Volatile
    private var notifyLogBudget = 3
```

```kotlin
                notifyLogBudget = 3
```

```kotlin
            if (notifyLogBudget > 0) {
                notifyLogBudget--
                Timber.tag(TAG).d("通知 ${bytes.size}B: ${bytes.toHex()}")
            }
```

## §4 改前原文 · RelaySwitchClient.kt（第 4 条）

```kotlin
        // 照 initApp：先拉设备列表，再连 WS（connect 内部 onOpen 还会再拉一次并订阅）
        fetchDevices(app)
        connectOnce(app)
        DiagLog.i("智能控制", "继电器客户端启动")
```

```kotlin
                DiagLog.i("智能控制", "已连接继电器服务器")
                // 照 ws.onopen：先 await 设备列表，再按模式订阅 + 逐个 get_status
                fetchDevices(context) { subscribeByMode(context) }
```

```kotlin
                    DiagLog.i("智能控制", "收到 status device=$deviceId data=$map")
```

```kotlin
                    DiagLog.i("智能控制", "收到设备列表 ${list.size} 个")
```

```kotlin
                DiagLog.i("智能控制", "已拉取设备列表 url=$url 共 ${list.size} 个")
```

## §5 改后状态（已回填）

| # | 治理项 | 改后做法 | 文件 |
| --- | --- | --- | --- |
| 1 | 重连风暴降频 | 新增通用 `LogThrottle`（core-common）：相同归一化签名连续重复 → 静默计数不产生日志行；签名变化 → 先打上一段汇总再详记新状态；恢复成功 → `summaryIfAny` 打收尾汇总并清「失败类」键；兜底心跳（8 轮 / 60s）防长时间零日志 | `LogThrottle.kt`（新增）、`VntVpnService.kt`、`WechatRuntime.kt`、`WechatWsClient.kt` |
| 2 | 「默认网络可用」 | **删除**（该日志只说明置了个布尔标志，恢复结果由「VNT 已连接」体现）。VNT 18 条/42min + 微信同款「网络可用，检查连接」一起删除 | `VntVpnService.kt` L232、`WechatRuntime.kt` L280 |
| 3 | BLE hex dump | `JikongPrefs.KEY_BLE_HEX_DUMP` 默认关；`JikongBleClient.notifyLogBudget` 默认 0（**真正不产生日志行**），开关打开才 3 条/会话；`descriptor 写回` D 日志同受控。设置页新增「诊断 → BLE 原始报文日志」开关，排障时显式打开 | `JikongPrefs.kt`、`JikongBleClient.kt`、`JikongPages.kt` |
| 4 | 智能控制启动汇总 | `beginStartupSummary()` 开 2.5s 窗口：REST 设备列表 ×2 + WS 列表 + 逐台 status ×N 静默累计，窗口结束打**一条**「启动同步完成：列表拉取 N 次，共 M 台，状态推送 K 条」；窗口外运行期状态变更仍逐条记录 | `RelaySwitchClient.kt` |

**关键设计取舍（务必保留）**
- 只清「失败类」键（`vnt-retry`/`vnt-attempt-fail`/`vnt-cb-error`、`wx-reconnect`/`wx-disconnect`/`wxws-fail`），
  **不清过程类键**（连接服务端/握手/注册/success/stop）——否则每次重连成功又会打印 4~5 条，节流失效。
- `summaryIfAny` 汇总后 `states.remove(key)`（不是只把计数归零）：保留签名会让下一轮新风暴的
  首条失败被误静默，违背「首条永远详记」。
- `State.signature` 用可空 `null` 初值，保证空签名事件的首条也能详记。
- 心跳汇总只打日志、不打断节流计数，避免风暴期间「零日志」被误判成服务已死。
- 状态变更骨架（VNT 已连接 / WS 已连接 clientId / 音量回读）**一条不删**，全是 I 级。

**预期效果**：42 分钟 299 条 → 约 120 条（-60%）；测试时段 100 秒 80 条 → 约 50 条。
服务端 20000 条窗口从存约 2 天延长到约 4 天，排障能力零损失。

## §6 编译与验收

- [x] `./gradlew :app:compileDebugKotlin` **BUILD SUCCESSFUL**（2026-11-10 17:2x，两次：
      初次改动 + 状态机修正后；仅有既存 deprecation 警告，无新增 error）
- [x] 节流器状态机等价验证（Python 逐行复刻）：
  - 10 轮同因重连 → 轮1 `DETAIL`、轮2~8 `SILENT`、轮9 `HEARTBEAT(已静默9轮)`、轮10 `SILENT`
  - 原因变化 → `SUMMARY(上段10次)+DETAIL(DNS fail)`
  - 空签名首条 → `DETAIL()`（不被误判重复）
  - 60s 时间兜底 → 第 4 次触发 `HEARTBEAT(已静默4轮)`
- [x] 新文件按项目惯例平铺在 `core/core-common/src/main/java/my/bridge/com/`（与 `DiagLog.kt` 同级）
- [ ] 真机日志量实测对照（待用户装机后拉取日志复核）
