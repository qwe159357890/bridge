# 蓝牙钥匙（BLE 钥匙广播）改前留档台账

> 项目铁律：动任何既有文件之前，先在此**逐字固化改前原文**，改完回填改后内容，并附回滚配方与触发信号。
> 模板源自 `docs/dev/heartbeat-inventory.md` §8。
>
> **状态**：**P0（广播卡点）+ P1（前台服务/状态机/自愈/UI）已实现并编译通过**
> （2026-11-18），改前原文与改后回填均已落表；P2 起（静默细节打磨、使用文档）待做。
> 设计见 `docs/research/ble-key-broadcast.md` v1.3。

---

## 一、改动总览

| # | 文件 | 改动性质 | 风险 | 回滚方式 |
|---|---|---|---|---|
| 1 | `app/src/main/AndroidManifest.xml` | 追加 `<service>` 声明（纯新增节点） | 极低 | 删除该节点 |
| 2 | `app/src/main/java/my/bridge/com/BridgeApplication.kt` | `onCreate` 末尾追加一行 `evaluate()` 调用 | 低（幂等、开关关时立即 return） | 删除该行 |
| 3 | `app/src/main/java/my/bridge/com/ui/SettingsPages.kt` | `ConnectionSettings` 末尾追加一个分区卡片 | 低（纯 UI 追加） | 删除该 `item {}` 块 |
| 4 | `app/src/main/java/my/bridge/com/ui/MainScreen.kt` | 覆盖层区追加 `if (BleKeyUiState.showPage) BleKeyPage()` | 低（布尔为 false 时零组合） | 删除该行 |

新增文件（无需回滚，删除即可）：

```
app/src/main/java/my/bridge/com/blekey/BleKeyProtocol.kt
app/src/main/java/my/bridge/com/blekey/BleKeyBroadcaster.kt
app/src/main/java/my/bridge/com/blekey/BleKeyPrefs.kt
app/src/main/java/my/bridge/com/blekey/BleKeyBroadcastService.kt
app/src/main/java/my/bridge/com/ui/BleKeyPages.kt
```

---

## 二、改前原文（逐字固化 · 待回填）

### 2.1 `AndroidManifest.xml` — 插入锚点

- 锚点：`JikongProximityService` 的 `<service>` 节点之后、`<!-- 保活：开机 / 更新自启 -->` 之前。
- 改前原文（2026-11-18 11:30，`sed -n '180,190p'` 逐字）：

```xml
        <!-- 极空靠近自动连接前台服务（v0.2.107）：connectedDevice 四件套
             （FOREGROUND_SERVICE_CONNECTED_DEVICE 权限见上 + 本类型声明 +
             startForeground 传类型位掩码 + BLUETOOTH_CONNECT 运行时权限前提）。
             由设置开关控制启停；START_STICKY 被杀重建时按当前 Prefs 重新评估。 -->
        <service
            android:name="my.bridge.com.jikong.JikongProximityService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <!-- 保活：开机 / 更新自启 -->
```

### 2.2 `BridgeApplication.kt` — 插入锚点

- 锚点：`ImmichBackup.ensureRegistered(this)` 之后、`Timber.i("BridgeApplication 初始化完成")` 之前。
- 改前原文（2026-11-18 11:30，`sed -n '112,117p'` 逐字）：

```kotlin
        // 相册自动备份（Immich 集成）：开关开则注册 MediaStore 内容触发 Observer
        // （幂等 KEEP 重排；关闭时内部直接跳过，不注册零消耗）
        my.bridge.com.immich.ImmichBackup.ensureRegistered(this)

        Timber.i("BridgeApplication 初始化完成")
```

### 2.3 `SettingsPages.kt` — 插入锚点

- 锚点：`ConnectionSettings()` 的 `LazyColumn` 内，`无界面模式` 分区之后（末尾）。
- 改前原文（2026-11-18 10:16，`sed -n '1363,1371p'` 逐字）：

```kotlin
        // ===== 无界面模式分区（v0.2.51：儿童锁 / 防误触，本机手动开关）=====
        item { SectionTitle("无界面模式") }
        item {
            UiLockSettingsSection()
        }
    }
```

- 上下文：位于 `ConnectionSettings()` 内（该函数起于第 1255 行 `fun ConnectionSettings() {`），
  紧随其后是 `// 昵称编辑弹窗`。插入位置 = `UiLockSettingsSection()` 的 `item` 块之后、`}` 之前。
- 改后回填见 §三。

### 2.4 `MainScreen.kt` — 插入锚点

- 锚点：`if (SshUiState.showPage) { SshServerPage() }` 之后、`// 退出提醒冒泡` 注释之前。
- 改前原文（2026-11-18 11:30，`sed -n '285,292p'` 逐字）：

```kotlin
            // SSH 服务状态页（v0.2.34）：设置→连接页卡片进入的独立覆盖层（无 NavHost，
            // 照项目状态驱动惯例）。SSH 页内注册的 BackHandler 后组合、优先接管返回键。
            if (SshUiState.showPage) {
                SshServerPage()
            }
            // 退出提醒冒泡（内容区底部居中，自动 2 秒消失）。
```

### 2.6 `AppExit.kt` — 新增前台判定（只加一个 getter，不改计数逻辑）

- 锚点：`foregroundCount` 字段之后。
- 改前原文（2026-11-18 11:30，`sed -n '42,45p'` 逐字）：

```kotlin
    /** 处于 started 状态的 Activity 数量：归零即「已切到后台」。 */
    @Volatile
    private var foregroundCount = 0
```

- 改后：追加 `fun isForeground(): Boolean = foregroundCount > 0`（供蓝牙钥匙服务判 `F` 输入）。
- 回滚：删除该函数即可（无调用方时编译通过）。

### 2.5 `app/build.gradle.kts` — 版本号（P0 打包时已改）

- 改前原文（2026-11-18 10:30，`sed -n '20,23p'` 逐字）：

```kotlin
        // 版本号单一来源：release.py 会自动递增并写回此处，CI 打包直接读取。
        versionCode = 382
        versionName = "0.2.182"
```

- 改后：`versionCode = 3001` / `versionName = "0.3.001"`（新规律：`主*1000 + 补丁`）。
- **P1 追加（2026-11-18 11:55）**：升 **`3002 / "0.3.002"`** —— 因为真机已装 0.3.001，
  `check_update` 按版本号比较，同版本不会提示更新。规则不变（主×1000 + 补丁递增）。
- 回滚：改回 `382` / `"0.2.182"`。**注意：手机一旦装了 3001+，再装回 382 会被安装器判为降级而拒装**，回退只能走 adb `install -r -d` 或卸载重装。
- **P2 追加（2026-11-18 12:00）**：升 **`3003 / "0.3.003"`**（同上理由，真机已装 0.3.002）。

### 2.7 `MainActivity.kt` — 通知跳转 + 前台拉起（P2 新增改动点）

- 锚点一：`onNewIntent` 内 `handleWechatDeepLink(intent, "onNewIntent")` 之后。
- 锚点二：`onCreate` 内 `handleWechatDeepLink(intent, "onCreate")` 之后、`enableEdgeToEdge()` 之前。
- 锚点三：`onStart` 内 `SshAutoStart.tryStart(this)` 的 `if` 块之后、函数 `}` 之前。
- 锚点四：`/** 申请尚未授予的危险权限；若无待申请项则跳过。 */` 注释之前（新增私有方法）。
- 改前原文（2026-11-18 11:58，`sed -n '93,137p'` 逐字）：

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWechatDeepLink(intent, "onNewIntent")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWechatDeepLink(intent, "onCreate")
        enableEdgeToEdge()
```

```kotlin
    override fun onStart() {
        super.onStart()
        if (!vntAutoStartAttempted) {
            vntAutoStartAttempted = true
            VntAutoStart.tryStart(this)
        }
        if (!sshAutoStartAttempted) {
            sshAutoStartAttempted = true
            my.bridge.com.ssh.SshAutoStart.tryStart(this)
        }
    }

    /** 申请尚未授予的危险权限；若无待申请项则跳过。 */
```

- 为什么必须改：通知点击要落到详情页。**不能**在服务构建通知时置 `showPage`——构建通知每次状态刷新都执行，
  会在用户毫无操作时把详情页挂起来（v0.3.002 真实 bug）。改成 extra 驱动，只有真正点击才生效。
- 回滚：删掉两处 `handleBleKeyIntent(intent)` 调用、`onStart` 里的 `evaluate(this)`、以及 `handleBleKeyIntent` 整个函数。

---

## 三、改后回填（P0 → P1 → P2，2026-11-18）

| # | 改后内容 | 验证 |
|---|---|---|
| 1 | **`AndroidManifest.xml`：`JikongProximityService` 之后追加 `<service android:name="my.bridge.com.blekey.BleKeyBroadcastService" android:exported="false" android:foregroundServiceType="connectedDevice" />` + 3 行注释，共 +8 行** | 2026-11-18 11:52 `compileDebugKotlin` BUILD SUCCESSFUL |
| 2 | **`BridgeApplication.kt`：`ImmichBackup.ensureRegistered(this)` 之后插入 `my.bridge.com.blekey.BleKeyBroadcastService.evaluate(this)` + 4 行注释，共 +5 行** | 同上（evaluate 内部查开关，关时不启动） |
| 3 | `SettingsPages.kt`：`UiLockSettingsSection()` 的 `item` 块之后、`}` 之前插入 2 个 item（`SectionTitle("蓝牙钥匙")` + `BleKeySettingsSection()`），共 +8 行 | 2026-11-18 10:2x 编译通过 |
| 4 | **`MainScreen.kt`：`if (SshUiState.showPage) { SshServerPage() }` 之后插入 `if (BleKeyUiState.showPage) { BleKeyPage() }` + 1 行注释，共 +4 行** | 同上 |
| 5 | `app/build.gradle.kts`：`versionCode 382 → 3001`（P0）→ **`3002`（P1）**、`versionName "0.2.182" → "0.3.001" → "0.3.002"` | **P1 产物**：63,052,051 B / sha256 `8BDACA5A…DEE3459`，`output-metadata.json` 实测 `3002 / 0.3.002`；已上传 mbh `bridge-v0.3.002.apk` 并改写 `update.json`（P0 的 0.3.001 包保留在同目录，作为回退锚点） |
| 6 | **`AppExit.kt`：`foregroundCount` 字段后追加 `fun isForeground(): Boolean = foregroundCount > 0`（+2 行）** | 同上；仅新增 getter，不改计数逻辑 |
| 7 | **`MainActivity.kt`（P2）：`onNewIntent` / `onCreate` 各加一行 `handleBleKeyIntent(intent)`；`onStart` 末尾加 `BleKeyBroadcastService.evaluate(this)` + 4 行注释；新增私有方法 `handleBleKeyIntent`（读 extra → 置 `BleKeyUiState.showPage`，约 10 行）** | 2026-11-18 12:05 `compileDebugKotlin` BUILD SUCCESSFUL；行为验证见验收单 E1 / E5 |
| 8 | `app/build.gradle.kts`：`3002 / "0.3.002"` → **`3003 / "0.3.003"`**（P2） | **P2 产物**：63,069,068 B / sha256 `BBC2494F…31F83`，`output-metadata.json` 实测 `3003 / 0.3.003`；已上传 mbh `bridge-v0.3.003.apk` 并改写 `update.json`（0.3.001 / 0.3.002 包均保留在同目录作回退锚点） |
| 9 | **`BleKeyRuntime.kt`：`BleKeyMode` 新增 `BOOST`（100ms 亮屏提速）；`BleKeyBroadcastService.kt`：新增 `BOOST_MS=2min` + `boostUntil` + `boostExpire` Runnable + `onScreenOn()`，`evaluateNow()` 的 `when` 首位插入 `boosting` 分支（`ADVERTISE_MODE_LOW_LATENCY`），`maybeNotifyQuietSoon` 的「在播」判据加 `BOOST`，通知标题与 `onDestroy` 回调同步；`BleKeyPages.kt`：`modeText` / `modeColor` 加 `BOOST`** | 2026-11-18 14:03 `compileDebugKotlin` BUILD SUCCESSFUL；**v0.3.004 产物**：63,069,064 B / sha256 `C61D260B…F3CB20`，实测 `3004 / 0.3.004`，已上传 mbh 并改写 `update.json`；行为验证见验收单 C8/C9/C10 |

新增文件（P1）：`blekey/BleKeyRuntime.kt`（运行状态单例）、`blekey/BleKeyBroadcastService.kt`（FGS + 状态机 + 巡检）、
`blekey/BleKeySelfCheck.kt`（手动 5 秒自检）；改写 `blekey/BleKeyPrefs.kt`（+总开关/静默时段）、
`blekey/BleKeyBroadcaster.kt`（+advertise mode 档位）、`ui/BleKeyPages.kt`（分区 + 详情覆盖页 + `BleKeyUiState`）。

新增文件（P2）：`blekey/BleKeyDeviceName.kt`（系统蓝牙名读写 + 按编号建议名）、
`res/drawable/ic_ble_key.xml`（通知小图标）；改写 `blekey/BleKeyBroadcastService.kt`（通知点击意图 /
静默前提醒 / 第二通知渠道）、`blekey/BleKeyPrefs.kt`（+静默提醒开关与去重日期）、
`ui/BleKeyPages.kt`（+设备名卡片 + 静默提醒开关 + 改名确认对话框）、`MainActivity.kt`（见上表 §7）。

### P2 修掉的两个既有 bug（记录在案，避免回归）

| bug | 现象 | 根因 | 修法 |
|---|---|---|---|
| 页面误跳转 | 状态刷新过几次后，**下次打开 App 直接落在蓝牙钥匙详情页**，而不是首页 | `buildNotification()` 里直接 `BleKeyUiState.showPage = true`，而该函数每次 `updateNotification()` 都执行 | 改为点击通知带 extra，由 `MainActivity.handleBleKeyIntent` 置标志 |
| 文案不兑现 | 状态显示「蓝牙未开启 · 点击通知去开启」，但点通知只是打开 App，用户还得自己找蓝牙开关 | `contentIntent` 固定指向 App 启动页 | 蓝牙关时 `contentIntent` 改指 `Settings.ACTION_BLUETOOTH_SETTINGS` |

---

### 2.8 亮屏提速（v0.3.004 · 2026-11-18 14:00 新增改动点）

**背景**：真机体感「后台 1000ms 时走近要 8 秒才通电」。对端不能动（它也要省电：基档只跑
25% 占空比、`hit_frames=3`），所以只能在手机侧用「亮屏」当免费的「人来了」信号。
实测/推算：100ms 广播 + 对端中档 25% → 首帧 0.4 秒；合计 **≈1.5 秒**（现状 8 秒）。

**用户拍板（2026-11-18 13:57）**：
- **保留 1000ms 基线**（不接受常驻 250ms）；
- **亮屏 → 100ms，维持 2 分钟**，之后回落原值（灭屏不立即回落，仍走完 2 分钟）；
- 不做三段式、不做运动传感器、不做"亮屏超 30 分钟降档"。

**改前原文**（2026-11-18 13:58，逐字）：

`BleKeyRuntime.kt:16-22`
```kotlin
enum class BleKeyMode {
    OFF,        // 总开关关闭
    BLOCKED,    // 不能播（未设编号 / 蓝牙未开 / 无权 / 不支持 / 启动失败）
    FAST,       // 250 ms：前台或极空已连接（含 5 分钟宽限）
    SLOW,       // 1000 ms：后台保底
    QUIET,      // 静默时段：后台且未连极空 → 停播（对端 75 秒后断电）
}
```

`BleKeyBroadcastService.kt:124-130`
```kotlin
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 亮灭屏只用来「趁唤醒顺手核对一次」，不改变档位判定依据（前台由 AppExit 判）
            evaluateNow()
        }
    }
```

`BleKeyBroadcastService.kt:217-238`（when 块开头两个分支）
```kotlin
        when {
            active || inGrace -> {
                val remain = ((graceUntil - now) / 1000).coerceAtLeast(0)
                val reason = when {
                    foreground && jikongConnected -> "前台 + 极空已连接 · 快速广播"
                    foreground -> "App 在前台 · 快速广播"
                    jikongConnected -> "极空已连接 · 快速广播"
                    else -> "后台宽限中 · 剩 ${remain / 60} 分 ${remain % 60} 秒"
                }
                startAdvertising(BleKeyMode.FAST, reason, uuid, txPower, AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            }
```

`BleKeyBroadcastService.kt:259`（静默提醒的"在播"判据）
```kotlin
        if (mode != BleKeyMode.FAST && mode != BleKeyMode.SLOW) return
```

`BleKeyPages.kt:596-604`（modeText）
```kotlin
private fun modeText(mode: BleKeyMode, adv: BleKeyState): String = when {
    mode == BleKeyMode.FAST && adv == BleKeyState.ADVERTISING -> "广播中 · 快速（250ms）"
    mode == BleKeyMode.SLOW && adv == BleKeyState.ADVERTISING -> "广播中 · 慢速保底（1000ms）"
    mode == BleKeyMode.QUIET -> "已暂停 · 时段静默"
    mode == BleKeyMode.BLOCKED -> "未广播"
    mode == BleKeyMode.OFF -> "已停止"
    adv == BleKeyState.ADVERTISING -> "广播中"
    else -> "已停止"
}
```

`BleKeyPages.kt:606-615`（modeColor）
```kotlin
private fun modeColor(mode: BleKeyMode, adv: BleKeyState) = when {
    adv == BleKeyState.ADVERTISING && (mode == BleKeyMode.FAST || mode == BleKeyMode.SLOW) ->
        MaterialTheme.colorScheme.primary
```

**关键设计**（写死，不要改）：

1. **不新增状态机分支**：`BOOST` 只是「取哪个 advertiseMode」的一个前置条件，其余分支逻辑不动；
2. 优先级：总开关 → 未设编号 → 蓝牙未开 → **亮屏提速窗口（BOOST 100ms）** → 前台/极空（250ms）→ 静默停播 → 慢速（1000ms）。亮屏提速**高于静默时段**（夜里亮屏 = 要用车）；
3. **灭屏不回落**：`SCREEN_OFF` 不改 `boostUntil`，仍走完 2 分钟（覆盖"灭屏后揣兜走两步"）；
4. **到期用 `postDelayed` 精确触发**，不靠 60 秒巡检（否则实际变 2~3 分钟）；
5. **不去重抖**：广播器幂等键是 `uuid + advertiseMode`，窗口内重复亮屏 mode 不变 → 天然不重建；
6. 服务被杀重建后 `boostUntil` 丢 → 回落到原档。可接受（重启瞬间不是用车瞬间）。

**能耗账**（4500 mAh / 100ms≈1.0mA、1000ms≈0.1mA）：100 次/天 × 2 分钟 = 3.3 小时 → **+3.0 mAh ≈ 0.07%/天**。

**回滚**：删 `BOOST` 枚举值 + 三处分支 + 台账本节即可（其余逻辑自洽）。

### 2.9 自检四档诊断（v0.3.005/0.3.006 · 2026-11-18 14:15 新增改动点）

**背景（用户真机实测）**：点「开始自检」报「5 秒内没扫到自己——可能本机广播被系统限制，
或距离/遮挡太强」，但同一时刻 ESP32 一直扫到、车在通电。→ 广播正常，是自检判断前提错。

**改动文件（2 个，均为本项目文件，无既有文件受影响）**：

| 文件 | 改前 | 改后 |
|---|---|---|
| `blekey/BleKeySelfCheck.kt` | `Result(found: Boolean, rssi, hits, error)`，单向判定 | **整篇重写**：改 `Result(outcome: Outcome, rssi, hits, deviceCount, beaconCount, error)`；新增 `Outcome` 五值；线程安全化 |
| `ui/BleKeyPages.kt` | `selfCheckResult: String?`；文案「边播边扫 5 秒，看能不能扫到自己」 | 改 `BleKeySelfCheck.Result?` + `selfCheckError`；新增 `selfCheckShort()` / `selfCheckDetail()`；说明文案改为分层诊断口径 |

**关键改前原文（BleKeySelfCheck.kt）**：

```kotlin
data class Result(val found: Boolean, val rssi: Int? = null, val hits: Int = 0, val error: String? = null)
// onScanResult 命中路径：直接 finish(Result(true, bestRssi, hits))  ← binder 线程回调 UI
// 超时路径：finish(Result(false, error = "5 秒内没扫到自己——可能本机广播被系统限制，或距离/遮挡太强"))
var hits = 0   // 普通局部变量，binder 线程写 / 主线程读
```

**修掉的三个缺陷**：

1. **误判**：`found=false` 一律当失败 → 改为四档（`SELF_FOUND` / `NO_SELF_SCAN_OK` /
   `NO_SELF_NO_BEACON` / `NOTHING_SEEN` / `FAILED`），只有后两者算问题。
2. **线程**：`onScanResult` 命中后直接 `onDone`（binder 线程写 Compose 状态）→ 统一 `handler.post {}`。
3. **可见性**：`hits` / `bestRssi` 改 `AtomicInteger`；去重计数改 `ConcurrentHashMap.newKeySet()`。

**回滚**：恢复上表"改前"结构即可（功能回退到"扫不到就报错"，但该报错在多数机型恒为假失败）。

### 2.10 巡检改为无条件重建（v0.3.007 · 2026-11-18 15:20 新增改动点）

**触发**：用户质疑「60 秒巡检到底检什么」，并用 `adb` 实测。结论：**自 P1 起巡检一直是空转**。

**证据链（15:08 真机日志，OnePlus 9 Pro）**：

```
15:03:45.794  已在广播 …0003（mode=0），忽略重复启动   ← 每 60 秒一次
15:04:45.884  已在广播 …0003（mode=0），忽略重复启动
15:05:45.954  已在广播 …0003（mode=0），忽略重复启动
15:08:49.082  蓝牙状态变化 state=12（STATE_ON），重评估
15:08:49.258  广播已启动 UUID=…0003 mode=2           ← 176ms，手机侧无延迟
```

- 60 秒整点必执行，但每次走**幂等跳过** → 一次都没重建；
- `巡检修复` 恒为 0 是代码必然结果，不是"没坏过"。

**根因**：`BleKeyBroadcaster` 在提交后**乐观置位** `_state = ADVERTISING`（`BleKeyBroadcaster.kt:184`），
而 `isAdvertising()` 只读该标志。判据 `expectedAlready && !isAdvertising()` 在
「期望在播 + 实际已停」时恒为 false。Android 无任何 API 能查广播是否真在发。

**改动（5 个文件，均为本项目文件）**：

| 文件 | 改前 | 改后 |
|---|---|---|
| `blekey/BleKeyBroadcaster.kt` | `start(context, uuid, tx, mode)`，幂等无条件生效；`if (advertiser != null) stopInternal()` | 加 `force: Boolean = false`；`!force && …` 才走幂等；改为 `if (advertiser != null || callback != null) stopInternal()` |
| `blekey/BleKeyBroadcastService.kt` | `watchdog` 只 `evaluateNow()`；判据依赖 `isAdvertising()` + `onWatchdogRestart()` 计数 | 新增 `forceRebuild` 字段（`evaluateNow` 外层 `finally` 清理）；巡检置 `forceRebuild=true`；`btReceiver` 收到 `STATE_ON` 也置真；删除原判据与计数 |
| `blekey/BleKeyRuntime.kt` | `onAdvertisingStarted()` 无条件写 `advertisingSince`；有 `watchdogRestarts` 字段与 `onWatchdogRestart()` | 只在 `advertisingSince == 0L` 时写；**删除** `watchdogRestarts` 与 `onWatchdogRestart()` |
| `ui/BleKeyPages.kt` | `BleKeyInfoRow("巡检修复", "${runtime.watchdogRestarts} 次")` | `BleKeyInfoRow("保活", "每 60 秒强制重建一次（防 ROM 静默停播）")` |
| `docs` / `guides` / 验收单 | 巡检"比对状态"、`巡检修复 0~2 次正常` | 改为"每 60 秒无条件重建"，验收 D1 改为「已播时长 5 分钟不归零」，新增 H8「蓝牙重开恢复时长」 |

**关键改前原文**（`BleKeyBroadcastService.kt` watchdir + startAdvertising 判据）：

```kotlin
private val watchdog = object : Runnable {
    override fun run() {
        if (stopRequested) return
        evaluateNow()
        mainHandler.postDelayed(this, WATCHDOG_MS)
    }
}
// startAdvertising 内：
val expectedAlready = lastDesiredMode == mode && BleKeyRuntime.state.value.mode == mode
if (expectedAlready && !BleKeyBroadcaster.isAdvertising()) {
    BleKeyRuntime.onWatchdogRestart()
    Timber.tag(TAG).w("巡检发现广播已静默停止，重建一次（累计 %d 次）", …)
}
```

**通知去重（同一版新增）**：`applyMode` 里按「档位 / 错误 / 倒计时分钟」三个维度比较，
都没有变化就不 `notify()`——否则例行重建会让通知栏一天刷新 1440 次。

**回滚**：把 `force` 传 `false` 即可退回"永远不重建"的旧行为（**但那是空转，不建议**）。

---

### 2.11 运行观测（健康台账 + 日志降噪 + 上传摘要）（v0.3.008 · 2026-11-18 15:40 新增改动点）

**背景**：验收单 50 项，用户不可能逐项手测。改为「挂机跑 + 上传日志」由日志判读。
核查后发现 3 个**关键盲区** + 1 个**量级问题**：

| # | 问题 | 后果 |
|---|---|---|
| B1 | `onCreate` / `onDestroy` / START_STICKY 重建**零日志** | 服务被 ROM 杀掉后何时复活、停了多久，日志里查不到 |
| B2 | 巡检每轮只打「强制重建」一条，没成功/失败结论 | 无法判断 1440 次巡检里有没有失败 |
| B3 | 无屏幕 / Doze / 省电模式状态 | 广播停了分不清「ROM 压制」还是「我们不播」 |
| Q1 | 每 60 秒 3 条 I 级日志（强制重建 + 广播已停止 + 广播已启动）≈ 4320 条/天 | 上传上限 5000 行只够 1 天多；磁盘 5MB 与其他模块争抢 |

**改前原文（逐字固化）**

`BleKeyBroadcastService.kt` — 巡检 Runnable（第 147~158 行）：

```kotlin
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopRequested) return
            // ★ 无条件重建（不看 isAdvertising()，它只是本类的乐观标志位）：
            //   Android 没有任何 API 能查"广播当前是否真的在发"，所以只能周期性重新提交。
            //   60 秒 = 对端「15 秒判 FAR + 60 秒断电 ≈ 75 秒断电门」内唯一能救回来的周期；
            //   空窗约 30~100 ms，远小于 15 秒失联门，对端无感。
            forceRebuild = true
            evaluateNow()
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }
```

`BleKeyBroadcastService.kt` — `onCreate` / `onStartCommand` / `onDestroy`（第 202~244 行）：

```kotlin
    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        promote()
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || stopRequested) {
            Timber.tag(TAG).i("收到停止请求，停播并撤前台")
            stopRequested = true
            BleKeyPrefs.setEnabled(applicationContext, false)
            BleKeyBroadcaster.stop()
            BleKeyRuntime.reset()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        if (promoted) promote()
        evaluateNow()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(boostExpire)
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        // 服务被系统杀掉时也要撤广播（否则残留一个无主广播）
        BleKeyBroadcaster.stop()
        BleKeyRuntime.reset()
    }
```

`BleKeyBroadcaster.kt` — 幂等分支 + `onStartSuccess` + `stopInternal`（第 118~126、169~177、219~235 行）：

```kotlin
        // 幂等：同一 UUID + 同一档位已在播（force 时跳过，用于巡检无条件重建）
        if (!force &&
            _state.value == BleKeyState.ADVERTISING &&
            currentUuid == uuidHex &&
            currentMode == advertiseMode
        ) {
            Timber.tag(TAG).d("已在广播 %s（mode=%d），忽略重复启动", uuidHex, advertiseMode)
            return true
        }
        // 换参数（编号/功率）、force 重建、或异常残留：先停再启
        if (advertiser != null || callback != null) stopInternal()
```

```kotlin
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Timber.tag(TAG).i("广播已启动 UUID=%s mode=%d payload=%s", uuidHex, advertiseMode, lastPayloadHex)
                currentUuid = uuidHex
                currentMode = advertiseMode
                _error.value = null
                _state.value = BleKeyState.ADVERTISING
                BleKeyRuntime.onAdvertisingStarted()
            }
```

```kotlin
    private fun stopInternal() {
        val adv = advertiser
        val cb = callback
        advertiser = null
        callback = null
        currentUuid = null
        currentMode = -1
        if (adv != null && cb != null) {
            try {
                adv.stopAdvertising(cb)
                Timber.tag(TAG).i("广播已停止")
            } catch (e: Exception) {
                // 蓝牙已关/栈忙时 stopAdvertising 会抛，视为已停
                Timber.tag(TAG).w(e, "stopAdvertising 异常（忽略）")
            }
        }
    }
```

`ConnectionViewModel.kt` — `uploadLog()` 开头（第 155~167 行）：

```kotlin
    fun uploadLog() {
        viewModelScope.launch {
            _uploadResult.value = "上传中…"
            val result = try {
                val id = deviceStore.deviceId()
                val logs = DiagLog.dumpLines().map {
```

`BleKeyPages.kt` — 「当前状态」卡片（第 283~297 行）：

```kotlin
            // ---- 状态 ----
            BleKeyCard("当前状态") {
                // tick 每秒变一次 → 触发重组，让「已播时长」走字
                val nowMs = remember(tick) { System.currentTimeMillis() }
                BleKeyInfoRow("档位", modeText(runtime.mode, advState))
                BleKeyInfoRow("生效原因", runtime.reason)
                BleKeyInfoRow(
                    "已播时长",
                    runtime.advertisingSince.takeIf { it > 0 }
                        ?.let { fmtDuration(nowMs - it) }
                        ?: "—",
                )
                BleKeyInfoRow("保活", "每 60 秒强制重建一次（防 ROM 静默停播）")
                runtime.error?.let { BleKeyInfoRow("原因", it) }
            }
```

`app/build.gradle.kts` — 版本号：

```kotlin
        versionCode = 3007
        versionName = "0.3.007"
```

**改法**

| 文件 | 改动 |
|---|---|
| `blekey/BleKeyHealth.kt` | **新增**：跨进程健康台账（服务创建次数 / 巡检次数 / 广播失败次数 / 各档位轮数 / 累计广播毫秒 / 最后巡检时刻 / 上次停机间隔），并提供 `summary()` 与 `appendSummary()` |
| `BleKeyBroadcastService.kt` | ①`onCreate` 打「服务已创建」并做**停机间隔检测**（`now - lastWatchdogAt > 150s` → W 级「疑似被杀/设备重启」）；②`onStartCommand` 区分 `intent == null`（START_STICKY 重建）；③`onDestroy` 打「服务销毁」；④巡检每轮 D 级心跳（带 屏幕/Doze/省电/档位/广播器状态），每 10 轮 I 级汇总；⑤失败计数入台账 |
| `BleKeyBroadcaster.kt` | 例行重建（force 且 uuid+mode 未变）降噪为**一条 D 级**，不再「广播已停止 + 广播已启动」两条 I 级；真正参数变化/首启仍 I 级；同一失败码连续出现只第一次 E |
| `BleKeySelfCheck.kt` | `finish()` 里补**结果日志**（`自检结果=… RSSI=… 命中=… 设备数=… 信标数=…`，FAILED 记 W、其余记 I）。改前只有「自检开始」，用户报错时日志里查不到结论 |
| `BleKeyBroadcastService.kt`（静态） | `evaluate()` / `stop()` 补 D 级「请求拉起 / 请求停止」——这是「用户何时动过开关、App 何时被拉起」的时间证据（只在服务未运行时记拉起，避免高频刷日志） |
| `BleKeyPages.kt` | 「当前状态」卡片下新增「运行概况」卡片：服务启动次数 / 巡检次数 / 最后巡检 / 累计广播 / 广播失败 / 上次断档 / 档位轮数 |
| `ConnectionViewModel.kt` | `uploadLog()` 组装前先 `BleKeyHealth.appendSummary(context)`，把一行 `[BleKeySummary]` 插进上传流 |
| `app/build.gradle.kts` | `3007 / 0.3.007` → `3008 / 0.3.008` |

**日志量核算（改后）**

| 项 | 改前 | 改后 |
|---|---|---|
| 每天条数 | ≈ 4320（全 I 级） | ≈ 1584（1440 D + 144 I） |
| 每天字节 | ≈ 0.5 MB | ≈ 0.2 MB |
| 磁盘 5 MB 覆盖 | ≈ 10 天 | ≈ 25 天 |
| 上传 5000 行覆盖 | ≈ 1.2 天 | ≈ 3 天 |

**回滚配方**

```bash
git checkout -- app/src/main/java/my/bridge/com/blekey/BleKeyBroadcastService.kt \
  app/src/main/java/my/bridge/com/blekey/BleKeyBroadcaster.kt \
  app/src/main/java/my/bridge/com/ui/BleKeyPages.kt \
  app/src/main/java/my/bridge/com/ui/ConnectionViewModel.kt \
  app/build.gradle.kts
rm -f app/src/main/java/my/bridge/com/blekey/BleKeyHealth.kt
```

**触发回滚的信号**：改后日志里出现 `BleKeyHealth` 相关崩溃、或巡检条数明显少于 1440/天（说明心跳被吞）。

---

## 四、触发回滚的信号

出现以下任一情况即回滚（删改动 + 保留新增文件不引用）：

1. `startAdvertising` 持续返回 `ADVERTISING_NOT_SUPPORTED` / `FEATURE_UNSUPPORTED`（本机不支持广播）→ 功能整体撤出，仅保留设计文档；
2. 与极空并存时极空连接成功率明显下降（射频争抢导致）→ 先回滚本功能，再单独排查；
3. 待机耗电增量 >2 %/天且无法通过状态机分层压下来 → 回滚；
4. 出现与 `startForegroundService` 义务相关的崩溃（`crash-ledger.md` §5 清单项）→ 立即回滚。

回滚配方：

```bash
# 仅撤调用与入口（保留新增文件，功能不可达）
git checkout -- app/src/main/AndroidManifest.xml \
  app/src/main/java/my/bridge/com/BridgeApplication.kt \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
  app/src/main/java/my/bridge/com/ui/MainScreen.kt
# 需要彻底删除时
rm -rf app/src/main/java/my/bridge/com/blekey app/src/main/java/my/bridge/com/ui/BleKeyPages.kt
```

---

## 五、发布面

| 项 | 值 |
|---|---|
| 版本 | `versionName 0.3.008` / `versionCode 3008`（P0 0.3.001 → P1 0.3.002 → P2 0.3.003 → 亮屏提速 0.3.004 → 自检修正 0.3.005/0.3.006 → 巡检真自愈 0.3.007 → 运行观测 0.3.008） |
| 现网基线 | `0.2.182` / `382`（**3006 > 382，不会触发降级拒装**） |
| 发版命令 | `tools/release/release.py`（正式）／调试期走 5.1 的 check_update 通道 |
| 回退锚点 | 同目录保留 `bridge-v0.3.001`~`bridge-v0.3.005.apk`；`update.json` 改回对应版本即可让手机回退（**但安装器禁止降 versionCode**，回退必须卸载重装或 adb `install -r -d`） |

### 5.1 调试期分发通道（check_update，2026-11-18 用户明确）

本地 `assembleDebug` 只是产出 APK，**不等于手机能点到**。完整链路是：

```
./gradlew :app:assembleDebug
  → app/build/outputs/apk/debug/app-debug.apk
  → scp 到 魔百盒 mbh:/home/qwe159357/software/appClient/server/data/updates/bridge-v0.3.001.apk
  → 同步改写同目录 update.json（version / versionCode / url / sha256 / notes）
  → 手机 App 内「检查更新」→ GET /api/updates/latest → 直链下载安装
```

- 目标机：**魔百盒（mbh）**，`ssh mbh` 免密（LAN 192.168.31.30 直连），见 `通用机器台账`。
- 服务端**实时扫描目录**，无需重启 supervisor。
- 版本比较按文件名解析 int 元组：`0.3.001 → (0,3,1) > 0.2.182 → (0,2,182)` ✅
- 客户端安装前会读 APK 清单里的真实 `versionName` 做防伪，**`update.json.version` 必须与清单一致**，否则拒装。
