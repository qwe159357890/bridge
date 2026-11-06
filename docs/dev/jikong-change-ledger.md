# 极空（JK BMS）集成 · 改前留档台账

> **约定来源**：`MEMORY.md` ★ 改前留档约定（2026-11-01 用户确立，优先级最高）——
> 任何修改前**先逐字固化「改之前」的状态**，改完再回填「改之后」，目标是出错时能 **100% 完美恢复**。
> 极空相关改动一律先在本台账登记（改前快照 / 回滚配方 / 触发信号），再动手。
>
> 权威调研文档：`docs/research/jikong-bms-integration.md`；
> 官方 APK 逆向时序记录：`.workbuddy/memory/2026-11-06.md`（2026-11-06 17:30 段）。

---

## §一 当前生效的回退锚点（★ 最高优先查看）

### v0.2.82 对齐官方握手序列（2026-11-06 17:40 实施，用户已确认，待真机验证）

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
| 2026-11-06 | v0.2.82 | 对齐官方握手序列（见 §一） | 回退到 `0b03027` = v0.2.81 |

> 登记规则：今后极空相关每次改版，先在 §一 更新「当前生效的回退锚点」，把旧锚点移入 §二。
