# 极空（JK BMS）功能阶段性总结

> 本文是极空功能「阶段性完成」的开发视角总结：交付了什么、能力全景、真机验证结论、遗留 TODO、下一步。
> 功能怎么用见 `docs/guides/jikong.md`；逐版本改前留档见 `docs/dev/jikong-change-ledger.md`；集成调研见 `docs/research/jikong-bms-integration.md`；靠近自动连接见 `docs/research/jikong-proximity-autoconnect.md`；握手修复专档见 `docs/research/jikong-handshake-fix.md`。

---

## 一、一句话总结

极空（JK BMS）保护板功能已从「按文档无真机实现」推进到「真机验证稳定、用户判定阶段性完成」：三端（客户端 BLE + 服务端透传 + 管理后台双向遥控）全链路打通，握手唤醒收敛到「首连 + 1 次直连重建 ≈2~3s」，音量、数据正确性、体验均已收口；后续只做细小优化。

## 二、交付物总览

| 端 | 交付物 | 说明 |
|---|---|---|
| **客户端** | `app/.../jikong/`（JikongProtocol / JikongBleClient / JikongController / JikongStatus / JikongPrefs / JikongSpeaker / JikongProximityService / JikongStatsStore+Sync / SpeedDash）+ `ui/JikongPages.kt` + `data/SpeakVolumeBoost.kt` + `Command.kt`/`CommandModule.kt` | BLE 连接 + JK02 帧协议（含 CRC）+ 实时数据 + 语音播报 + 里程统计 + 时速仪表盘 + 靠近自动连接 + 连接提示音 + 播报音量三段兜底 |
| **服务端** | `core/jikong_store.py`、`api/ws.py`（jikong_state）、`api/v1/jikong.py`（查询/控制）、`api/v1/data.py`（里程同步）、`core/command_catalog.py`（jikong 命令族） | 状态内存存储 + 透传 + MySQL 里程留档 + 命令白名单 |
| **管理后台** | `api/jikong.ts`、`views/jikong/index.vue`、路由/侧边栏 | 极空页 1:1 复刻 + 双向实时同步遥控（jikong_* 命令族） |

## 三、已实现能力全景

### 3.1 BLE 连接与协议

- 扫描 / 连接 / 设默认设备 / 改昵称；默认设备进页自动直连。
- 协议层兼容 0xDD（读/写）/ NW / JK02 三套帧；JK02 响应帧 300 字节、帧尾 CRC（`d[299]` = 前 299 字节累加和低 8 位，真机 30+ 帧逐字节验证 100% 匹配）。
- 连接竞态治理（陈旧 GATT 回调守卫 + 连接抢占 + 数据帧硬保证）。
- `requestConnectionPriority(HIGH)` + MTU 303。

### 3.2 实时数据与界面

- 首页：中央 SOC 弧仪表盘（电量 + 充电/放电/均衡状态）+ 时速仪表盘卡片（时速 / 剩余续航 / 瞬时·平均电耗 / 本次里程，GPS，可选开启）。
- 实时状态页：电池电压 / 电流 / 功率 / 单体电压 / 温度 / 保护 / MOS。
- 四 Tab：首页 / 实时状态 / 统计 / 参数设置（底部导航）。

### 3.3 语音播报

- 连接自动播报电量（每帧判定：变化即播 / 超间隔重播 / 低电预警重复提醒）。
- 首页双击仪表盘播报（电量 + 时速 + 续航，绕过静音）。
- edge-tts 在线人声 + 分段缓存本地拼接（连读），失败回退本地引擎。
- 播报音量提升：三段兜底（setStreamVolume → FLAG_SHOW_UI → adjustStreamVolume）+ 引用计数（beep 与播报并发不提前恢复音量），后台/锁屏也能拉满。

### 3.4 统计与演示

- 统计页：里程台账（记一笔）+ Canvas 能耗曲线 + 同步按钮；`POST /api/jikong/odometer/sync` → MySQL `jk_mileage_records`（device_id+record_id 幂等 upsert，删除 = deleted=1 墓碑）。
- 演示模式：不连设备灌示例数据核对界面，切离页/后台自动退出。

### 3.5 靠近自动连接

- GATT autoConnect 挂起（控制器级 3.75% 占空比监听、零 CPU、零流量、<0.5%/天），配套 `JikongProximityService`（connectedDevice 四件套前台服务）承载「被杀后也要」。
- 半死链自愈：autoConnect 首连半死链 → 主动断开 → 直连重建唤醒。
- 可配置：首连短戳（0.1~1.0s）、长等待（1~60s）、连接成功提示音（5 选 1）。

## 四、真机验证结论（用户 2026-11-08 多轮真机实证）

- **握手收敛**：每次靠近 =「autoConnect 首连（半死链）+ 1 次直连重建」即成功，**约 2~3s、5/5 复现**。实验①HIGH 半命中（3~4 轮→1 轮）、②a 放宽 20s 更差（97s/5 次）、④反射 7.5ms 失败（hidden API 阻断）——最终结论：**「断开 + 直连重建」1 次就够，无需多轮短戳**；官方「一次交互」仍差一步重建，但已稳定可接受。
- **音量根治**：后台/锁屏裸调 setStreamVolume 被 ROM 静默忽略（回读仍=原值）→ 三段兜底后回读=30，彻底拉满。
- **数据正确性**：CRC 拦到真坏帧，杜绝半死链苏醒首帧错位导致的 SOC 误报（54/58→10）。
- **体验**：连接提示音 beep 与播报电量间隔可听出「连接成功 → 首帧数据」耗时；首连短戳可自设。

## 五、遗留 TODO（后续细小优化，不阻塞）

| 项 | 说明 | 优先级 |
|---|---|---|
| 官方「一次交互成功」的最终握手 | 若日后还想追「1 条链直接成功」，唯一靠谱路径 = HCI/BT Snoop 字节级对比官方 APP（见握手专档 §七 ★★★） | 低（当前已够用） |
| 保护板参数写入 | 已从设置页移除（只读展示防误改），如需恢复需评估风险 | 待定 |
| 更细的省电实测 | 24h 对照实验 + `adb dumpsys batterystats` 归因（当前为文献估算 <0.5%/天） | 低 |
| 提示音音色微调 | 如需更多音色或自定义节奏，可扩展 res/raw 资源 | 低 |

## 六、下一步

功能已判定阶段性完成，后续进入「细小优化」节奏：仅在用户遇到具体问题或提出明确优化点时改动，不再做大范围协议/架构调整。任何协议相关改动务必遵守「纯防御、只增不减、不影响已验证功能」的红线（详见 `docs/dev/jikong-change-ledger.md`）。
