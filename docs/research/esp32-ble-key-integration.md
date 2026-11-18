# ESP32 蓝牙钥匙（BLE iBeacon 广播）集成对接文档

> ⚠️ **本档已被取代（2026-11-18）**：实现以 [`ble-key-broadcast.md`](ble-key-broadcast.md) 为准。
> 本文档由对端项目作者按经验撰写，**不是 Bridge 项目内部设计**，其中 3 处事实错误
> （字节预算 25/27、编号方案的理由、MAC 多行）与 4 处遗漏（`setLegacyMode`、advertiser 槽位争用、
> 与极空射频并发、安全边界）已在新档 §1.2 逐条纠正。**实现时不得照抄本文档代码与参数。**
> 保留本档仅作历史与对端约定的原始记录（§一 的对端硬约定仍然有效）。

> 对接目标：在 Bridge 客户端新增「BLE 钥匙广播」能力——让手机作为 BLE 广播源（peripheral），
> 持续广播一条 iBeacon，供外部 ESP32 设备（espAutoUnlock 项目）扫描识别、把手机当作「靠近即解锁」的钥匙。
> 对端项目：`F:/pythonWork/智能家居/espAutoUnlock`（ESP32 + MicroPython，与 Bridge 完全独立）。
> 文档日期：2026-11-18。

---

## 〇、一句话结论

Bridge 需要新增一个**方向全新的** BLE 能力：**手机从「扫描者」变成「广播者」**。
现有极空（jikong）是手机作为 central 扫描/连接外部 BMS；本次是手机作为 peripheral
**主动广播 iBeacon**，由 ESP32 扫描识别。二者 API 完全不通，不能复用 `JikongBleClient`，
但可**照抄 `JikongProximityService` 的「connectedDevice 前台服务 + START_STICKY + 蓝牙状态守护」范式**来保活。

---

## 一、ESP32 端现状与硬约定（Bridge 必须逐字遵守，不可改动）

espAutoUnlock 的钥匙识别已经完全定稿并上线（ESP32 端代码 `src/key_matcher.py` / `src/ble_scanner.py`）。
以下是对端**已固化**的协议约定，Bridge 广播端必须与之对齐，否则 ESP32 认不出手机。

### 1.1 识别机制

- ESP32 持续做 BLE 主动扫描，解析每帧广播。
- 白名单统一为 `[{name, mac, uuid}]`，**name / mac / uuid 任一命中即算命中（OR）**。
- 手机 iBeacon 广播**没有设备名、地址是「随机静态」（每次重启广播会变）**，因此手机这把钥匙
  **只能靠 UUID 识别**（MAC 不可靠，ESP32 端已明确按 UUID 匹配）。
- 命中后记录显示名 `last_key_name`，用于日志/界面展示「是哪把钥匙」。

### 1.2 iBeacon 广播格式（协议硬约定）

ESP32 端 `parse_ibeacon_uuid()` 按下列字节串识别 iBeacon 并取出 UUID：

```
AD 结构： [length] [0xFF] [4C 00] [02 15] [UUID 16字节] [Major 2字节] [Minor 2字节] [TxPower 1字节]
                            └─ 公司编号 0x004C（Apple）
                                  └─ iBeacon 固定头（02=类型, 15=后续21字节）
```

| 字段 | 值 | 说明 |
|---|---|---|
| 公司编号 Company ID | `0x004C` | Apple Inc.（iBeacon 规范强制） |
| iBeacon 头 | `02 15` | 固定前缀，`15`=后续 21 字节 |
| **UUID** | 16 字节（32 个 hex 字符，**小写无连字符**） | **唯一身份，ESP32 匹配依据** |
| Major | 2 字节（如 `0001`） | 分类号，本项目未用，随便填 |
| Minor | 2 字节（如 `0001`） | 分类号，本项目未用，随便填 |
| Measured Power | 1 字节（`C5` = -59 dBm） | 1 米处参考 RSSI，测距校准用，改它不改变实际信号 |

> 实测样例（ESP32 端抓到的手机广播 hex）：
> `1aff4c00 0215 00112233445566778899aabbccddeeff 0001 0001 c5`

### 1.3 匹配语义（重要，避免误改）

- ESP32 只认 **UUID 精确相等**（字符串 `==`，小写无连字符 32 字符）。
- UUID 前 30 字符 + 后 2 字符这种「固定前缀 + 编号」的拆分**只发生在 App 侧**，ESP32 端
  始终收到并匹配**完整 32 字符 UUID**。Bridge 广播端必须把完整 UUID 编进广播，不能只广播编号。

### 1.4 广播参数要求

| 参数 | 要求 |
|---|---|
| 可连接性 | **不可连接（non-connectable）**——iBeacon 标准就是不可连接广播 |
| 设备名 | **不包含**（iBeacon 广播不带名字） |
| 广播间隔 | 默认 ~250ms 即可（ESP32 端 2 秒扫一轮、连续 3 帧判近，250ms 足够；越短发现越快但越耗电） |
| 发射功率 | 默认 MEDIUM（约 -7dBm）即可；ESP32 判近阈值 -80dBm，实测手机贴脸可达 -67dBm |

---

## 二、与 Bridge 现有 BLE 能力的差异（必读，避免方向搞反）

| 维度 | 极空 jikong（现有） | 本次「BLE 钥匙」（新增） |
|---|---|---|
| 手机角色 | **central**（扫描 / GATT 连接 BMS） | **peripheral**（广播自己） |
| 核心 API | `BluetoothLeScanner` / `BluetoothGatt` | `BluetoothLeAdvertiser` |
| 方向 | 手机 → 连别人 | 手机 → 被别人扫到 |
| 识别依据 | BMS 的 MAC + 服务 UUID | 自己广播的 iBeacon UUID |
| 可复用 | — | **不可复用** `JikongBleClient`；可复用 `JikongProximityService` 的 FGS 范式 |

**结论**：这是全新代码路径，需新建独立包；但前台服务承载、保活、Prefs、Compose 页面均可照项目既有范式。

---

## 三、Android 端适配方案

### 3.1 包结构（建议）

沿用项目「一 feature 一包 + ui 页面」惯例，新建 `app/.../blekey/`（命名避开已被音频中继占用的 `relay/`，
也避开 `jikong/`）：

```
app/src/main/java/my/bridge/com/blekey/
├── BleKeyProtocol.kt        # iBeacon 组包常量 + buildIbeaconPayload()
├── BleKeyBroadcaster.kt     # BluetoothLeAdvertiser 封装（开播/停播/状态/蓝牙开关守护）
├── BleKeyPrefs.kt           # SharedPreferences 持久化（UUID/编号/开关/参数），照 JikongPrefs 范式
└── BleKeyBroadcastService.kt # connectedDevice 前台服务，照 JikongProximityService 范式
app/src/main/java/my/bridge/com/ui/
└── BleKeyPage.kt            # Compose 页面（开关 + 编号输入 + 广播状态 + 当前完整 UUID 展示）
```

### 3.2 核心：iBeacon 组包与广播（关键代码骨架）

**① 组包**（iBeacon 厂商数据，不含 company ID——`addManufacturerData` 会自动补 0x004C）：

```kotlin
// BleKeyProtocol.kt
object BleKeyProtocol {
    const val COMPANY_ID_APPLE = 0x004C
    const val IBEACON_TYPE = 0x02
    const val IBEACON_LEN  = 0x15       // 后续 21 字节
    const val DEFAULT_MAJOR = 1
    const val DEFAULT_MINOR = 1
    const val DEFAULT_MEASURED_POWER = -59  // 0xC5

    /** uuid: 16 字节；返回 23 字节 payload（0215 + UUID16 + Major2 + Minor2 + TxPower1）。 */
    fun buildIbeaconPayload(
        uuid: ByteArray, major: Int = DEFAULT_MAJOR,
        minor: Int = DEFAULT_MINOR, measuredPower: Int = DEFAULT_MEASURED_POWER,
    ): ByteArray {
        require(uuid.size == 16) { "UUID 必须 16 字节" }
        return ByteArray(23).apply {
            this[0] = IBEACON_TYPE.toByte()
            this[1] = IBEACON_LEN.toByte()
            System.arraycopy(uuid, 0, this, 2, 16)
            this[18] = (major shr 8).toByte(); this[19] = (major and 0xFF).toByte()
            this[20] = (minor shr 8).toByte(); this[21] = (minor and 0xFF).toByte()
            this[22] = measuredPower.toByte()
        }
    }

    /** 32 字符小写无连字符 hex → 16 字节。 */
    fun uuidHexToBytes(hex: String): ByteArray {
        val clean = hex.replace("-", "").lowercase()
        require(clean.length == 32) { "UUID 必须 32 个 hex 字符" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
```

**② 广播参数 + 启动**：

```kotlin
// BleKeyBroadcaster.kt（节选）
val advertiser = bluetoothAdapter.bluetoothLeAdvertiser   // 可能为 null（芯片不支持 BLE 广播）
val settings = AdvertiseSettings.Builder()
    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)   // ~250ms
    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)  // 约 -7dBm
    .setConnectable(false)                                          // iBeacon 必须不可连接
    .setTimeout(0)                                                  // 无限期
    .build()
val data = AdvertiseData.Builder()
    .setIncludeDeviceName(false)                                    // 不带名字
    .addManufacturerData(BleKeyProtocol.COMPANY_ID_APPLE, payload)  // payload 从 0215 开始
    .build()
advertiser.startAdvertising(settings, data, advertiseCallback)      // 失败回调 onStartFailure
```

> ⚠️ `addManufacturerData(companyId, data)` 的 `data` 参数**不含 company ID**，Android 会自动
> 把 `0x004C` 编进去。所以 payload 从 `02 15` 开始，与 ESP32 端 `4c00 0215` 的解析对齐。

### 3.3 前台服务承载（保活，照 JikongProximityService 范式）

BLE 广播若要「锁屏/切后台后仍持续」，必须常驻前台服务，否则进程被杀广播即停。**直接照抄**
`JikongProximityService` 的结构，改名为 `BleKeyBroadcastService`：

- `foregroundServiceType="connectedDevice"`（Android 14+ 后台 BLE 操作的合规承载）；
- `START_STICKY`：进程被杀系统重建时按 Prefs 重新评估（自然自愈）；
- 蓝牙开关守护：注册 `BluetoothAdapter.ACTION_STATE_CHANGED`，`STATE_OFF` 摘除广播标记、
  `STATE_ON` 重评估开播（部分 ROM 蓝牙重启后广播丢失）；
- 前台化防崩：照 `KeepAliveService` v0.1.99/v0.2.01 教训——`startForeground` 失败不吞异常、
  带重试、耗尽 `stopSelf` 撤销义务；运行中重复拉起 `onStartCommand` 补挂前台；
- 通知渠道 `IMPORTANCE_LOW`、`setOngoing(true)`，文案如「BLE 钥匙广播中 · 手机可解锁 ESP32」。

**与保活的关系**：`core-keepalive` 的 `KeepAliveService` 负责「进程存活」，本服务负责「广播的合规承载」，
二者解耦（与极空同构，见 `JikongProximityService` 头注释）。

### 3.4 配置存储（照 JikongPrefs 范式）

```kotlin
object BleKeyPrefs {
    private const val SP_NAME = "ble_key_config"
    const val KEY_ENABLED = "ble_key_enabled"          // 广播开关，默认关
    const val KEY_KEY_NUMBER = "ble_key_number"        // 钥匙编号 0~99（两位数字）
    const val KEY_UUID_PREFIX = "ble_key_uuid_prefix"  // UUID 前 30 字符（固定段）
    // 完整 UUID = uuidPrefix + 编号补足两位（"03" 等），32 字符
}
```

> **推荐「固定前缀 + 2 位编号」方案**（与 espAutoUnlock 侧讨论定稿）：固定段 30 字符写死或可配，
> 用户界面只填 2 位编号（00~99 = 100 把钥匙）。App 内部拼成完整 32 字符 UUID 广播出去，
> ESP32 端仍按完整 UUID 匹配，**零改动**。

### 3.5 UI 页面（照 JikongPages / RelaySwitchPages 范式）

`BleKeyPage.kt`，建议放在「设置 → 连接」或主页新增 Tab，包含：

- 广播开关（总开关）；
- 钥匙编号输入（2 位数字，`00~99`）；
- 当前完整 UUID 展示（只读，拼接后显示，供用户在 ESP32 侧核对）；
- 广播状态（广播中 / 已停止 / 蓝牙未开 / 芯片不支持）；
- 权限引导（`BLUETOOTH_ADVERTISE` 运行时权限，Android 12+ 需申请）。

### 3.6 导航接入（照项目现状）

- `MainScreen.kt` 的 `HomePage` 里 `when (tabOrder.getOrElse(currentTab) { 0 })` 新增一个 case，
  如 `9 -> BleKeyPage()`；
- 对应 `TabOrderPrefs` 注册新页面 id 与默认名（如「蓝牙钥匙」）；
- 若选择放进设置页，则在 `SettingsPage` 的连接页新增入口卡片（照 SSH 页 `SshUiState.showPage` 覆盖层模式）。

---

## 四、关键决策点（需作者拍板，缺省给推荐值）

| # | 决策点 | 推荐 |
|---|---|---|
| D1 | 命名 | 包 `blekey/`、Tab「蓝牙钥匙」（避开 `relay`/`jikong`） |
| D2 | UUID 方案 | 固定 30 字符前缀 + 2 位编号（100 把钥匙），App 拼完整 UUID 广播 |
| D3 | 页面位置 | 主页新增 Tab（若主页 Tab 已满，放「设置 → 连接」入口） |
| D4 | 广播参数 | BALANCED(~250ms) + MEDIUM 功率 + 不可连接 + 无限期，**默认即可，不做后台可调** |
| D5 | 广播常驻范围 | 开关打开即常驻（前台服务保活），关闭立即停播释放 |
| D6 | 是否发 iBeacon 之外的广播 | 否，仅 iBeacon 一条 |

---

## 五、坑清单（Android BLE Advertiser 特有，务必遵守）

1. **`BluetoothLeAdvertiser` 可能为 null**：极少数芯片不支持 BLE 广播，`startAdvertising` 前判空并提示。
2. **`BLUETOOTH_ADVERTISE` 运行时权限**：Android 12+ 需运行时申请（Manifest 已声明，无需改），未授权
   `startAdvertising` 抛 `SecurityException`。
3. **广播间隔是「档位」非精确毫秒**：`ADVERTISE_MODE_LOW_LATENCY/BALANCED/LOW_POWER` 对应约
   100ms/250ms/1000ms，实际受系统调度，可能漂到 200~300ms，不要写死「必在 100ms 内发现」。
4. **发射功率厂商可能忽略**：`setTxPowerLevel` 在多数国产 ROM 上不生效或受限，实际功率由射频芯片决定，
   别依赖它精确控距。
5. **息屏 Doze 节流**：即便有前台服务，部分激进 ROM 息屏深睡仍会拉长广播间隔；电池白名单引导
   （`core-keepalive` 已有 `KeepAliveUtils`）是最有效缓解手段，应在页面提供引导入口。
6. **蓝牙开关重启后广播丢失**：部分 ROM 蓝牙 `STATE_OFF→ON` 后 advertiser 需重新 `startAdvertising`，
   必须监听 `ACTION_STATE_CHANGED` 重开（照 `JikongProximityService`）。
7. **重复 startAdvertising 报错**：同一 advertiser 连续启动会 `ADVERTISE_FAILED_ALREADY_STARTED`，
   封装层需幂等（启动前判状态 / 先 stop 再 start）。
8. **广播超时 `setTimeout(0)` 才无限期**：默认非 0 会自动停，务必显式 `setTimeout(0)`。
9. **`AdvertiseData` 大小限制**：单条广播 ≤ 31 字节，iBeacon（company 2 + payload 23）共 25 字节，安全，
   但不要再往同一条广播里塞别的 record。
10. **与极空 BLE 扫描的并存**：本功能（广播）与极空（扫描）可同时进行，二者是独立 API，互不影响；
    但若未来做「BLE 网关」类扫描，注意 advertiser 与 scanner 共用同一射频，极端占空比下会互抢。

---

## 六、端到端验证清单（实现后照此验收）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | App 打开「蓝牙钥匙」开关、填编号 `03` | 出现常驻通知「BLE 钥匙广播中」 |
| 2 | 另一台手机装 nRF Connect → SCANNER | 扫到无名设备，manufacturer data = `4c00 0215 <uuid> 0001 0001 c5` |
| 3 | 核对 UUID | = 固定前缀 + `03`，32 字符小写无连字符 |
| 4 | ESP32 侧扫描（espAutoUnlock 的 SCAN / PyQt5 界面） | 设备列表该手机行 UUID 列 = 完整 UUID |
| 5 | 把该 UUID 在 ESP32 侧添加为钥匙 | ESP32 启动日志「钥匙白名单」含该 UUID |
| 6 | 手机靠近 ESP32 | 状态卡「钥匙=名字」、判近、继电器吸合 |
| 7 | 手机锁屏/切后台 5 分钟 | 广播仍在（前台服务 + 电池白名单生效） |
| 8 | 关闭 App 内开关 | 广播停止、通知消失 |

---

## 七、工作量估算

| 模块 | 内容 | 估量 |
|---|---|---|
| `BleKeyProtocol.kt` | iBeacon 组包 + hex 转换 | 小（~50 行） |
| `BleKeyBroadcaster.kt` | Advertiser 封装 + 幂等 + 蓝牙守护 | 中（~150 行） |
| `BleKeyBroadcastService.kt` | connectedDevice FGS（照 JikongProximityService） | 中（~250 行，大量可抄） |
| `BleKeyPrefs.kt` | SharedPreferences（照 JikongPrefs） | 小（~60 行） |
| `ui/BleKeyPage.kt` | Compose 页面（开关/编号/状态/权限引导） | 中（~200 行） |
| 导航 + Manifest + 权限 | MainScreen 加 case + TabOrderPrefs + service 声明 | 小 |
| 文档 | 本档 + `docs/INDEX.md` 登记 | 小 |

**总计约 700~900 行**，其中 FGS 与 Prefs 有现成范式可大幅复用，核心新增仅 Advertiser 封装与组包。

---

## 八、待与 espAutoUnlock 侧对齐的边界（本文档之外的确认项）

1. **UUID 固定前缀取值**：由 espAutoUnlock 侧给出最终固定段（当前实测样例前缀
   `00112233445566778899aabbccddee`，后两位 `ff` 为编号位），Bridge 侧写入 `KEY_UUID_PREFIX` 默认值。
2. **是否多台手机**：若多台手机各配不同编号，ESP32 白名单需对应登记多把钥匙（ESP32 端已支持
   `[{name,uuid}]` 多钥匙并存）。
3. **判近阈值**：当前 ESP32 端 `rssi_on=-80`，若后续要调判定距离，改 ESP32 端配置，与 Bridge 无关。

---

*本档为对接文档，实现待 D1~D6 决策确认后启动。*
