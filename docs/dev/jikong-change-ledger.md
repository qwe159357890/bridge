# 极空（JK BMS）集成 · 改前留档台账

> **约定来源**：`MEMORY.md` ★ 改前留档约定（2026-11-01 用户确立，优先级最高）——
> 任何修改前**先逐字固化「改之前」的状态**，改完再回填「改之后」，目标是出错时能 **100% 完美恢复**。
> 极空相关改动一律先在本台账登记（改前快照 / 回滚配方 / 触发信号），再动手。
>
> 权威调研文档：`docs/research/jikong-bms-integration.md`；
> 官方 APK 逆向时序记录：`.workbuddy/memory/2026-11-06.md`（2026-11-06 17:30 段）。

---

## §一 当前生效的回退锚点（★ 最高优先查看）

### v0.2.86 后台极空页 1:1 复刻 + 客户端上报扩展（2026-11-06 21:00 实施，已构建分发，待验证）

| 项 | 内容 |
|---|---|
| **实施结果（改后回填，2026-11-06 21:16）** | ①客户端：reportStatus 扩展编译一次通过，assembleDebug 成功，APK 实证 versionName=0.2.86 + buildReportStatus 在 dex，分发 `server/data/updates/bridge-v0.2.87.apk` + Downloads + update.json（0.2.87；0.2.86 同版本号覆盖无法触发手机端升级，已重发）；②服务端：odometer 查询端点 py_compile 通过 + 段电耗公式单测通过（73.55V 均压口径与设备端一致，Δ容量≤0 判无效）；③后台：index.vue 重写 + jikong.ts 扩展，`npm run build`（vue-tsc+vite）通过。**待验证**：真机升级 0.2.87 后后台手机壳四 Tab 数据实时性、统计曲线、MOS 控制面板 |
| **补丁（21:26，用户反馈两点）** | ①后台目标设备接全局 `useTargetDeviceStore`（与命令下发/自动化台一致：localStorage 记忆、切换自动保存、跨页面保持，无记忆才回退第一台）；②`enterDemoMode` 末尾补 `reportStatus()`——演示数据（connected=true）上报服务端，后台显示「已连接 · 演示设备」可用于联调；演示退出路径本就上报复位。同版本未装机，APK 直接覆盖分发（46,123,195 B），提交 `27390e6` |
| **复刻范围说明** | 手机壳内四 Tab 与客户端 1:1（首页含仪表盘/健康横幅/时速卡/双四格/电流功率卡；实时页双列+单体电压/内阻网格；统计页只读台账+曲线；设置页镜像含开关只读）。**管理台专属面板放在手机壳外**：远程控制（MOS/均衡，手机端已按官方 UI 移除控制入口）+ 保护/告警状态 + 设备信息 |

| 项 | 内容 |
|---|---|
| **需求** | 管理后台「极空保护板」页从 Element Plus 通用仪表盘风格改为**手机壳 1:1 复刻**（390px 居中一台，四 Tab 与手机 APP 完全一致）；补齐客户端一直没上报的数据（时速仪表盘快照、本地设置），统计台账走 MySQL 查询 |
| **回退目标版本** | v0.2.85（统计页，未真机验证但功能完整） |
| **回退标记（git 快照）** | 提交 **`6305354`**（`feat: v0.2.85 极空统计页…`，工作区干净即此提交）——本次改动前全部文件原文以此为准 |
| **回滚配方** | ①客户端：`git checkout 6305354 -- app/src/main/java/my/bridge/com/jikong/JikongController.kt app/build.gradle.kts` 重新打包（恢复 0.2.85 上报内容）；②服务端：`git checkout 6305354 -- server/app/api/v1/jikong.py`（去掉台账查询端点）；③后台：`git checkout 6305354 -- admin/src/views/jikong/index.vue admin/src/api/jikong.ts` 重新 build |
| **触发回滚的信号** | ①上报扩展导致 jikong_state 主链路异常（状态不更新/WS 报错）；②后台页构建失败或极空页白屏；③台账查询接口拖垮服务端 |
| **改动内容** | ①客户端 v0.2.86：`JikongController.reportStatus` 的 jikong_state 上报 status 对象内附带 `dash`（speed_kmh/gps_fresh/range_km/inst_wh_km/avg_wh_km/trip_km/trip_wh/trip_seconds/bms_stale/updated_at）与 `app`（theme/dash_enabled）两块（SpeedDash 单例 + JikongPrefs）；②服务端：jikong_state 原样透传（store 存原始 dict，零改动）+ `GET /api/jikong/{device_id}/odometer`（require_admin，odometer_store.list + 服务端算段电耗）；③后台：重写 `admin/src/views/jikong/index.vue`（手机壳四 Tab）+ `admin/src/api/jikong.ts` 补字段 |

### v0.2.85 极空统计页（里程台账+能耗曲线+MySQL 留档）（2026-11-06 20:20 实施，已打包分发，待真机验证）

| 项 | 内容 |
|---|---|
| **实施结果（改后回填，2026-11-06 20:45）** | 编译一次通过（compileDebugKotlin + assembleDebug 增量）；APK 实证 versionName=0.2.85（UTF-16 编码）+ JikongStatsStore/JikongStatsSync/StatsView 均在 dex；服务端 py_compile 通过；分发完成 `server/data/updates/bridge-v0.2.85.apk`（45,956,038 B）+ Downloads + update.json（0.2.85）。**真机未连 adb，待用户更新后验证**：①统计 Tab 位置/图标/亮暗主题；②录入→列表/概要/曲线联动；③同步成功与失败（可停服务端 MySQL 演示）Toast 与标记状态机；④删行联动 |

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.84（已真机确认基线：无握手 + 时速仪表盘） |
| **回退标记（git 快照）** | 提交 **`ae48fa9`**（`chore: 快照 v0.2.84（极空统计页功能实施前的回退锚点）`）——首次把 v0.2.84 全量工作区状态入库（含此前未跟踪的 SpeedDash.kt / 统计.svg / 原型 HTML），**本次改动前全部文件的原文以此提交为准** |
| **回滚配方** | `git checkout ae48fa9 -- app/src/main/java/my/bridge/com/ui/JikongPages.kt app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/build.gradle.kts server/app/api/v1/router.py server/app/main.py server/data/updates/update.json`，另**手动删除**新增文件：`app/src/main/java/my/bridge/com/jikong/JikongStatsStore.kt`、`JikongStatsSync.kt`、`app/src/main/res/drawable/ic_jk_stats.xml`、`server/app/core/odometer_store.py`、`server/app/api/v1/odometer.py`（MySQL 表 jk_mileage_records 可留可删，无副作用） |
| **触发回滚的信号** | ①统计页导致极空主流程异常（连接/状态上报/时速仪表盘被干扰）；②同步逻辑拖垮主 WS 或产生大量重复请求；③服务端 503 频发且 MySQL 正常（新表/SQL 问题）；④编译/打包失败 30 分钟内无法定位 |
| **改动内容** | 客户端：底部导航第 3 位新增「统计」Tab（图标 `assets/icons/统计.svg` → `ic_jk_stats`，顺序 首页/实时状态/**统计**/参数设置）；`JikongStatsStore`（SharedPreferences JSON 数组：id/ts/odoKm/cycleAh/volt/synced + 待删除队列，org.json 范式）；统计页 UI（概要三卡=区间里程/总耗电/平均电耗、录入卡=里程输入+自动读取循环容量/总压、Canvas 能耗曲线、固定高度倒序台账含删除/未同步徽标/同步按钮+Toast、口径说明）。同步：录入成功后**静默自动同步一次** + 手动按钮兜底，失败保「待同步」标记可重试；服务端：`server/app/core/odometer_store.py`（MySQL 表 `jk_mileage_records`，按 device_id+record_id 幂等 upsert，删除=tombstone deleted=1）+ REST `POST /api/v1/odometer/sync`（X-Device-Id/X-Device-Token 鉴权，复用 locations.mysql 配置，无新配置项） |
| **口径** | 段电耗 Wh/km = Δ循环容量(Ah) × 两记录平均电压(V) ÷ Δ里程(km)；循环容量只增不减，充电不影响；Δ容量≤0（BMS 重置/换电池）→ 该段标记无效不进曲线并重设基准；新里程须 > 上一条 |

### v0.2.84 回退握手改动（2026-11-06 18:55 实施；19:47 用户真机确认，**定为当前基线**）

| 项 | 内容 |
|---|---|
| **当前版本（基线）** | **v0.2.84 = v0.2.81 连接时序 + v0.2.83 时速仪表盘（握手改动已完全移除）**。2026-11-06 19:47 用户真机验证通过，明确拍板「暂时将当前版本链路定为当前版本」 |
| **基线验证结论（用户观察）** | ①官方 APP 链路**也时通时不通**（「官方链路有时能通，有时不能」）→ 半死链路是官方同样存在的现象，并非我们实现独有缺陷；②v0.2.84 回退版：偶发退出重连（有时间隔约 1 分钟）**但至少能通** → 可用性达标，作为后续迭代的对照基线 |
| **握手链路处置** | **暂存**（用户拍板 2026-11-06 19:47）：21B 握手帧 / 官方时序对齐 / 半死链路根因（快档 800ms 重连嫌疑等）整体搁置，留待以后具体分析和剖析；逆向成果保留在本文档与 2026-11-06 工作日志，随时可复用 |
| **实施方式** | `git checkout 0b03027 -- app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt JikongController.kt JikongProtocol.kt`（仅回滚握手 3 文件；`SpeedDash.kt`/`JikongPrefs.kt`/`JikongPages.kt` 保留） |
| **验证** | 编译 3m27s 通过（时速仪表盘与 v0.2.81 Controller API 完全兼容）；APK 实证 0.2.84 + `buildOfficialHandshake` 0 残留 + SpeedDash 在 dex；真机可用（见上） |
| **回退背景（历史，已证伪）** | ① v0.2.83 真机实证：官方时序完全生效（21B 握手帧发送成功+CCCD+MTU303）但 BMS 休眠时 0 帧数据；② 二轮逆向定论：官方 APP 无独立激活帧（第 3 声蜂鸣=0x6C 时间同步帧，收到数据后才发；0xC8=OTA 帧；监控平台协议 0x01 激活指令是 4E57 帧，不适用 55AAEB 蓝牙直连）；③ 官方 CONNECT_TIMEOUT 10s 报错不重连 |
| **遗留疑点（暂存，待以后剖析）** | 官方 APP 同场景十几秒连接时长但**链路不断**，我们曾 13s 必 status=8 → 候选原因：①800ms 快档重连循环（BMS 未清完旧链路→新链路质量差→supervision timeout）；②requestConnectionPriority(HIGH) 弱信号副作用。**恢复剖析时一次只测一个变量，先以 0.2.84 基线对照** |
| **若未来要再调链路** | 候选微调：快档重连 800ms→2s（给 BMS 清链时间）；去掉启动 ramp 的 0x96 轰炸（官方只发一次后等待）。**改前先在本台账登记改前快照** |

### v0.2.83 时速仪表盘（2026-11-06 18:35 实施，用户已确认，待真机验证）

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.82（官方握手对齐，功能完整但仪表盘尚未验证） |
| **回退标记（git 快照）** | 提交 **`3342e91`**（`chore: 快照 v0.2.82（时速仪表盘功能实施前的回退锚点）`） |
| **回滚配方** | `git checkout 3342e91 -- app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/build.gradle.kts server/data/updates/update.json`，另**手动删除**新增文件 `app/src/main/java/my/bridge/com/jikong/SpeedDash.kt`（git 未跟踪） |
| **触发回滚的信号** | ①仪表盘开关打开后 GPS 异常耗电/发热；②首页卡片数值明显错误（如续航乱跳、里程虚增）；③仪表盘逻辑干扰既有极空连接/上报 |
| **上一轮锚点** | v0.2.82 官方握手对齐（回退标记 `0b03027` = v0.2.81，见下节） |

### v0.2.82 对齐官方握手序列（2026-11-06 17:40 实施，待真机验证）

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.81（功能正常、偏慢但可用的最后版本） |
| **回退标记（git 快照）** | 提交 **`0b03027`**（`chore: 快照 v0.2.81（极空官方握手帧对齐前的回退锚点）`），含 v0.2.76~81 全部工作区状态 |
| **回滚配方** | 见下方命令块 |
| **当前版本** | v0.2.82（改动已落地、打包、分发，**用户尚未真机测试**） |

**回滚配方**（在仓库根目录执行，一条命令退回 v0.2.81 全部相关文件）：

```bash
git checkout 0b03027 -- \
  app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt \
  app/src/main/java/my/bridge/com/jikong/JikongController.kt \
  app/src/main/java/my/bridge/com/jikong/JikongProtocol.kt \
  app/build.gradle.kts \
  server/data/updates/update.json
```

回滚后重新本地打包即可恢复 0.2.81 行为（注意 `app/build.gradle.kts` 里 versionName 会随文件一起退回 0.2.81，如需保持新版本号再手动改回）。

**触发回滚的信号**（任一出现即考虑回滚）：
1. **健康链路回归失败**：正常近距离连接（非闲置首连）也长时间无数据 / 0x02 不来——说明官方时序反而干扰了本来能用的链路；
2. **BMS 对 21B 握手帧行为异常**：如 ACK 帧循环、0x02 爆发消失、设备信息帧（0x03）不再返回；
3. **连接成功率下降**：自动重连频繁失败、或 12s 自愈反而更频繁触发；
4. 编译/打包失败且 30 分钟内无法定位。

**本次改动内容**（改了什么，便于回滚前判断问题归属）：
- `JikongProtocol.kt`：新增 `buildOfficialHandshake()`——官方 21B 握手帧 `AA 55 90 EB 97 + 15×00 + 11`（CRC=0x311 低 8 位=0x11，与 20B 0x97 帧 CRC 同值，0 不参与累加）。
- `JikongBleClient.kt`：
  - STATE_CONNECTED：`requestConnectionPriority(HIGH)` → **80ms** 后 `discoverServices`（官方时序；移除旧的先 `requestMtu(256)` 后发现服务 + 1.5s 兜底）；
  - `onDescriptorWrite` 成功（CCCD 订阅完成）→ **40ms** 后发官方 21B 握手帧 → `requestMtu(517)`（官方时序：MTU 在 CCCD 之后）；`handshakeSent` 每链路一次，STATE_CONNECTED / disconnect() 复位。
- `JikongController.kt`：`startPolling` 删除首发 0x97（让位给握手帧）；启动 ramp 的 0x97 重发改用 21B 握手帧；`requestRealtimeData()` 同步替换。
- **保留未动**（v0.2.77~80 的保险层）：0x96 每 1.2s 催流、4s 断流自愈、12s 半死链主动重建、180s 自动重连（status=8 快档 800ms）。

**背景**（为什么改）：真机实证「蓝牙闲置 ~10 分钟后首连 → 链路建立但无数据 → 干等 ~1min 至 status=8 断开 → 第 2 条链路立即出数」。官方 APP 无任何重连机制却总能一次连接出数，逆向 `JK_BMS_6.4.04.apk`（dexdump 反汇编 `com.jktech.bms.ble.BleMessage`）找到三处差异：①HIGH 连接优先级 ②CCCD 后 40ms 的 21B 握手帧（推测为 BMS 应用层会话激活信号）③MTU 517 时序。

---

## §二 历史改动登记（按时间倒序）

| 日期 | 版本 | 改动 | 快照/回退方式 |
|---|---|---|---|
| 2026-11-06 | v0.2.76~81 | 0x02 断流自愈、demo 重置、内阻@80/平均重算、自动重连（180s/双档）、GATT 串行化（MTU→发现服务→CCCD）、强跳过冻结修复（基元传参）、12s 半死链自愈、日志降噪 | 均含于快照 `0b03027`；更早回退点见 git 历史（`3778aca` 之前） |
| 2026-11-06 | v0.2.82 | 对齐官方握手序列（见 §一） | 已回退到 `0b03027` = v0.2.81 |
| 2026-11-06 | v0.2.83 | 时速仪表盘（SpeedDash + 首页卡片 + 设置页清空） | 回退到 `3342e91` = v0.2.82（已验证，无需回退） |
| 2026-11-06 | v0.2.84 | 回退握手 3 文件至 v0.2.81 + 保留时速仪表盘；**用户真机确认定为当前基线**，握手链路暂存 | 基线本身即回退产物；再回退可用 `3342e91`（含握手）或 `0b03027`（无握手无仪表盘） |
| 2026-11-06 | v0.2.85 | 统计页：里程台账/能耗曲线/同步按钮（本地为准+MySQL 留档），见 §一 | 回退到 `ae48fa9` = v0.2.84 |

> 登记规则：今后极空相关每次改版，先在 §一 更新「当前生效的回退锚点」，把旧锚点移入 §二。
