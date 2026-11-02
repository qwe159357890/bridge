# 系统类命令（check_update / vnt_start / vnt_stop / vnt_status / ssh_start / ssh_stop / ssh_status）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `system` 分类。
> `upload_log` 见 [../ws-client.md](../ws-client.md) §五/§七。

## check_update 检查更新（服务器目录版，不依赖 GitHub）

**设计动机**：GitHub 在国内不稳定，更新分发完全走服务器自身。

### 开发者操作（发布新版时）

1. 云端打包产出 APK 后，把 APK 拷进服务器更新目录 `server/data/updates/`
   （`updates.dir` 可配），**文件名需含版本号**，如 `bridge-v0.1.32.apk`；
2. 可选放一个 `update.json` 写更新说明：
   ```json
   {"version": "0.1.32", "notes": "新增截屏、弹窗、检查更新等命令"}
   ```
3. 目录里可以留多个 APK，服务端取**版本最高**的（版本号从文件名解析，
   按 `.` 分段数值比较，`0.1.10 > 0.1.9` 正确）。

### 运行逻辑（v0.1.37 起为完整自动更新闭环）

```
下发 check_update（或设备「关于」页点「检查更新」，同一入口 UpdateManager）
  → 客户端带当前 versionName 请求 GET /api/updates/latest
  → 服务端扫描目录取最新 APK，与客户端版本比对
  → 无 APK        回执「服务器暂无更新包」
    已是最新      回执「已是最新版本」
    有新版本      mode=auto_install（默认）：自动下载 APK（通知栏进度）→ 拉起系统安装页 → 用户点「安装」完成
                  mode=check_only：只回执「发现新版本 vX（当前 vY，z MB）」，不下载不安装
```

### 参数

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mode` | select | `auto_install` | `auto_install`=自动安装（发现新版直接下载并弹安装页）；`check_only`=仅检查（只回执是否需要更新，不下载，适合巡检场景） |
| `force_download` | switch | `false` | 强制重新下载：忽略本地缓存删掉重下（仅自动安装模式有效；check_only 下拒绝执行） |

注：设备「关于」页的「检查更新」按钮固定走自动安装模式，不受该参数影响。

### 缓存健壮性（v0.1.45，防「假 44 实为 43」死循环）

历史上缓存命中只看「文件名+大小」，而 42/43/44 三个 debug 包大小恰好一致——
0.1.43 改名冒充 0.1.44 会被错误命中，装完仍是 43，**永远无法更新**。三层防护：

1. **缓存命中以 md5 为准**：服务端 `latest` 接口现算 APK md5 随响应返回，客户端
   缓存命中判定改为 md5 比对（老服务端不返回 md5 时退回大小比对）；
2. **下载完整性校验**：下载完成后重算 md5 与服务端不符 → 删缓存回执报错；
3. **安装前版本号识破**（最根本）：读 APK 清单实际 versionName，与最新版本号
   不符（源头就错）→ 拒绝安装、删缓存、回执明示「服务器上的 v44 实际是 v43」。

配套：下载/校验成功后**自动清理**其他旧版本缓存；设备「关于」页「管理更新缓存」
可列出缓存（文件名/大小/日期/实际版本号）并**有选择地手动删除**；命令参数
`force_download` 提供远程自救入口。

- 鉴权：设备头（`X-Device-Id` + `X-Device-Token`，与音乐下载同一套）。
- 下载：`GET /api/updates/{filename}` 服务器直链（仅放行单级 `.apk` 文件名）；
  先写 `.tmp` 再原子重命名，半截文件不会进缓存；同版本已下载过则跳过下载。
- **防重复触发**：下载/安装中再次下发命令或连点按钮，直接拒绝并提示
  （UpdateManager 原子锁贯穿全流程）。
- **安装**：Android 不允许静默安装，最终在系统安装页由用户点一次「安装」；
  Android 8+ 需「允许安装未知应用」——未开启时自动跳设置页引导，APK 已保留，
  开启后再触发一次即可直接进安装页（Manifest 已声明 REQUEST_INSTALL_PACKAGES，
  APK 经 FileProvider 共享给系统安装器）。
- 会话保持提示：更新成功安装后 App 会被系统重启，WebSocket 自动重连。

---

## VNT 组网命令（v0.2.30 引入，v0.2.32 改为 1.x / VntApp v1.2.9 语义）

> 三条命令走「三步法」：`server/app/core/command_catalog.py` 的 `_cmd()` →
> `core/core-network/.../ws/Command.kt` 常量 + `CommandModule.kt` 执行器 → 本文档 + README 索引。
> 页面与生命周期设计见 [../../research/vnt-mesh-integration.md](../../research/vnt-mesh-integration.md)，
> 交互原型见 `assets/design/ui-mockup-vnt-mesh.html`。

> **★ v0.2.32 重大变更（决策链见 `docs/dev/vnt-integration-change-ledger.md` §六）**：
> 用户的自建 VNTS 与所有存量客户端**全是 1.x，且必须互通**，而 VNT 2.0 与 1.0
> 官方明写「不兼容」（`vnt-main/README.md:167`）。因此 Bridge 核心整体替换为官方
> **VntApp `v1.2.9`（最后一个 Java JNI 版）** 的 `libvnt_jni.so` + `top.wherewego.vnt.jni.*` 绑定。
> v0.2.30/31 的 2.0.11 表述（`udp://` 非法、`network_code`、`tcp(tls)`/`wss`/`quic`、
> ChaCha20、certMode、getClientList、NAT/RTT）**全部作废，以本节为准**。
> 2.x 成果已由「改前留档」台账 §六 + git 历史完整保留，将来服务端升级可按配方还原。

**架构**：连接由 `VntVpnService`（`specialUse` 前台服务）常驻，页面只订阅状态。
点「开始组网」/ 下发 `vnt_start` 后，**切 Tab、退后台、锁屏都不断连**；只有
`vnt_stop`、页面「停止组网」、或 `AppExit.kill`（退出应用 / 切后台立即结束）才终止。

**v0.2.38 断线自动重连**：切网（WiFi↔蜂窝）/ 断网恢复后**自动重建组网**，无需人工点击。
机制 = worker 内**监督重连循环**（可重试失败不停服务，天然绕开 Android 12+ 后台 FGS 限制），
错误分类（`TokenError` / `InvalidIp` / VPN 授权被抢为致命错误，转 ERROR 等人工处理；
`Disconnect` / `AddressExhausted` / `IpAlreadyExists` / `Unknown` 可重试）+
指数退避 2/4/8/16/30/60s 封顶（连接成功即重置）+
`registerDefaultNetworkCallback` 网络恢复立即重试并重置退避。
重连期间状态为 **`RECONNECTING`**（非终态），UI 主按钮仍显示「停止组网」可随时终止。
改前留档与实现细节见 `docs/dev/vnt-integration-change-ledger.md` §七。

**1.x 启动时序（与 2.x 相反）**：`new Vnt(Config, CallBack)` → native 依次回调
`connect / handshake / register / generateTun(DeviceConfig)` → **Java 在 `generateTun`
回调里建 `VpnService.Builder` 并 `return fd`** → `success()` → `awaitTimeout` 保活循环。
2.x 是「Java 先建 TUN 再 `startTun(fd)` 传进去」，两者不可混用。

### vnt_start 开始组网

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `server` | text | 否 | 可选覆盖 APP 内保存的服务端地址（默认 `039039.xyz:31007`，用户自建 1.x VNTS）。**1.x 只接受裸 `host:port`，不接受任何 `://` 前缀**（`udp://` / `tcp://` 都会被 `VntServerAddress.error()` 与 `VntConfigBuilder` 双处拦下）；UDP/TCP 由下方 `tcp` 参数选择 |
| `token` | text | 否 | 组网编号（1.x 叫 **Token**，2.x 叫 network_code）。**兼容旧参数名 `network_code`**；为空且 APP 内也未保存 → 回执失败 |
| `password` | text | 否 | 客户端间加密密码；**须与存量 1.x 客户端逐项一致**（连同 `cipherModel` / `serverEncrypt` / `finger`），不同加密状态不能通信 |
| `tcp` | text | 否 | 可选覆盖连接协议：`tcp` / `true` / `1` → TCP，`udp` / `false` / `0` → **UDP（默认，与用户 1.2.13 原生 APK 一致）** |

**回执与限制**（重要）：

- **需 App 在前台或已授权过 VPN**。`VpnService.prepare()` 授权框必须由 Activity 弹出；
  Android 12+ 后台 `startForegroundService` 还会抛 `ForegroundServiceStartNotAllowedException`。
  执行器先查 `VntMeshController.isPrepared()`，未授权直接回执失败，**不假装成功**。
- **v0.2.33**：`RUNNING`/`STARTING`/`STOPPING` 一律 `success=false`「重复下发被忽略」，不重复建网（详见下方「状态联动、幂等守卫与强制下发」）；**v0.2.38 起 `RECONNECTING`（断线自动重连中）同样拦截**，与「正在运行」同等对待。
- 参数覆盖**仅在真正启动前**写回单套配置 Prefs（与页面「组网配置」共用一份）；已在运行时参数不生效，回执明确说明，避免配置漂移。
- 按崩溃台账 §5：`startForegroundService` 每调一次欠一次前台义务，
  `VntVpnService.onStartCommand` 每次幂等补挂、`startForeground` try/catch + 失败 `stopSelf`。
- **互通前提**：`token` / `password` / `cipherModel` / `serverEncrypt` / `finger` 必须与
  存量 1.x 客户端一致，否则可能「注册成功但互 ping 不通」。配置弹窗已按官方
  `AddActivity` 默认值预填（aes_gcm / serverEncrypt=true / finger=false）。

### vnt_stop 停止组网

无参数。走普通 `startService(ACTION_STOP)`（不产生前台义务），服务内部
`Vnt.stop()` 让 `awaitTimeout` 返回 → worker 的 `finally` 统一 `close()` native 实例、
关闭 VPN 的 `ParcelFileDescriptor` → `stopSelf()`。回执含停止前状态。
**无平台限制，三条里最可靠**，适合运维远程收口。

**v0.2.33**：未组网（`STOPPED`/`ERROR`）时设备端直接回 `success=false`「重复下发被忽略」，
不执行停止、不清除开机自动组网记忆；`VntMeshController.stop()` 与 `VntVpnService.shutdown()`
双重收口，杜绝「未组网时重复 stop 拉起一个空前台服务」的僵尸通知与假 `STOPPING` 状态。

**v0.2.31 自动组网记忆（1.x 沿用）**：`vnt_start` 成功下发（或回「已在运行」）会把
`VntMeshPrefs.autoStart` 置 `true`，`vnt_stop` 置 `false`。记忆为 `true` 时，
App 下次冷启动由 `MainActivity.onStart` → `VntAutoStart.tryStart()` 自动开始组网；
三道守卫（记忆未开 / 已 RUNNING·STARTING·RECONNECTING / VPN 授权失效）会跳过，且**失败不重试**
（运行期断线恢复由 v0.2.38 的监督重连循环负责，与冷启动自动组网是两条独立路径）。
与页面「开始组网」/「停止组网」按钮共用同一份记忆；退出应用 / 切后台结束进程**不动记忆**。

### 状态联动、幂等守卫与强制下发（v0.2.33）

**起因**：`vnt_start` / `vnt_stop` 原先不判断当前组网状态，重复下发有隐性副作用。

| 重复场景 | 改前行为 | v0.2.33 |
|---|---|---|
| 已组网再 `vnt_start` | 不重复建网，但**在状态守卫之前**把带参覆盖写回 Prefs（运行中的实例仍用启动时的旧配置 ⇒ 配置漂移，后台却以为新参数已生效）；且无条件 `setAutoStart(true)`，一条迟到的 start 会把用户刚点「停止」清掉的记忆写回 | 后台按钮置灰；设备端状态守卫放在**所有写操作之前**，返回 `success=false`「重复下发被忽略：组网已在运行」，不写 Prefs、不动记忆 |
| 未组网再 `vnt_stop` | 无条件 `setAutoStart(false)`（下次冷启动不再自动组网，与「必可达」硬约束冲突）；且 `startService(ACTION_STOP)` 在服务不存在时会**新建空前台服务**——`onCreate` 挂通知、`shutdown()` 里 worker 为 null ⇒ 没人调 `onStopped()/stopSelf()` ⇒ 通知常驻、状态卡 `STOPPING` | 后台按钮置灰；设备端未组网直接返回 `success=false`，不停止、不清记忆；`VntMeshController.stop()` 与 `VntVpnService.shutdown()` 双重收口，任何入口拉起的空服务立即 `onStopped()` + `stopSelf()` |

**后台状态联动（纯前端，零 APK / 零服务端改动）**：

- 状态来源 = `vnt_status` **按需查询命令的回执**（`ws.py:446` 的 `command_result` 广播已带 `command` / `message`），按 `device_id` 缓存快照。
  `VntMeshController.statusText()` 固定前缀解析：`运行中 ·`→running、`正在建立连接（`→starting、
  `断线重连中`→reconnecting（v0.2.38 新增）、`正在停止（`→stopping、`未连接`→stopped、
  `异常：`→error，其余→unknown。
- 自动探测时机：选中 `vnt_start`/`vnt_stop` 卡片（防重复 key + 800ms 防抖）、切换目标设备、顶栏「刷新设备状态」按钮、这两条命令回执后 1.5s。
- 禁用规则：`vnt_start` 仅 `stopped`/`error` 可用；`vnt_stop` 仅 `running`/`starting`/`reconnecting`（v0.2.38 新增）/`stopping` 可用；`vnt_status` 自身不受限；**`unknown`（状态未知 / 从未查询 / 回执解析失败）一律禁用**。
  UI = 卡片置灰 + 「下发命令」按钮禁用 + 参数区状态条（含采集时刻与「刷新」）+ 顶栏组网状态徽标 + `blockHint` 原因文案。
- **unknown 禁用（2026-11-02 用户拍板，推翻 v0.2.33 的「不硬禁用」）**：v0.2.33 曾因「快照可能失真 ⇒ 硬禁用会想开开不了」放行 unknown；用户实测认为「状态未知时仍允许下发」不合理 ⇒ 改为一律禁用，先点「刷新状态」取得确态再操作。**出口仍在**：unknown 时命令被禁用 ⇒ 强制下发开关可勾选（正是「命令可用时置灰」规则的反面），二次确认后可直接下发 ⇒ 不违反「必可达」硬约束。详见下方 ④。

**强制下发（调研结论：需要，且必须二次确认）**：

- 复用音乐命令的 `forceSend` 开关，只对 `vnt_start` / `vnt_stop` 显示；勾选后点「下发命令」弹确认框，明写「后台记录的组网状态为 X，将忽略该状态直接下发」，用户确认才真正发（不是静默放行）。
- 它**不是**「强制重启 VNT」：服务端 `force` 只对采集互斥组生效（`commands.py` 的 `CAPTURE_COMMANDS`），VNT 命令走的仍是普通下发，设备端守卫照常按真实状态执行或忽略 ⇒ 强制下发只是**绕过后台按钮禁用**的兜底出口。
- 存在理由：状态快照失真、设备刚上线没探测、回执丢失/超时、离线刚恢复时，仍要能恢复可达性 —— 对应「可接受延迟，不可永远访问不到」。

**幂等口径**：重复命令回执改为 `success=false`（文案以「重复下发被忽略」开头），后台不再显示「命令执行成功」造成误导；`STARTING`/`STOPPING` 也视为「进行中」，不重复拉起。

### 命令历史、查看详情与强制下发置灰（v0.2.33 后续修订，仅后台 + 服务端）

> 本轮**不改 APK、不发版本号**：改动落在管理后台前端与服务端下发接口各一处。

**① 自动探测静默，不进命令历史**

v0.2.33 的 4 个 `vnt_status` 自动探测（选中开始/停止组网卡片、切换设备、顶栏「刷新设备状态」、开始/停止回执后 1.5s 重查）原先都走普通 `sendCommand()` ⇒ 服务端 `CommandStore.create()` 登记 ⇒ 出现在「命令历史」表格里，把内部状态查询和用户主动下发的命令混在一起。

链路：`admin/src/api/commands.ts` 的 `sendCommand(..., silent=true)` → `CommandRequest.silent` → `commands.py` 在 `silent=True` 时**跳过 `store.create()`**。命令照常送达设备，回执仍由 `ws.py:434-452` 的 `command_result` 分支经 `admin_event_bus` 广播给管理后台 ⇒ **状态联动完全不受影响**；`CommandStore.set_result()` 找不到记录会打一条 warning，属预期。

用户在卡片墙上**手动选中「组网状态」再点「下发命令」**走 `doSend()`，不带 `silent` ⇒ 正常入库、正常显示在历史里（也是「查看详情」的主要入口）。服务端测试 `test_send_command_silent_not_recorded` 覆盖「静默不入历史 + 普通下发仍入历史 + 命令确实送达设备」。

**② 三条 VNT 命令的「查看详情」（按命令单独设计）**

用户确立的约定：**每条命令的详情弹窗要针对自身返回的参数/数据单独设计，不做通用回执文本框，也不做全命令统一入口**。本期只做 `vnt_status` / `vnt_start` / `vnt_stop` 三条，组件 `admin/src/components/VntResultDetailDialog.vue`，数据仅取现有 `CommandRecord`（`params` + `result_message`），**不改客户端回执结构**。

| 命令 | 弹窗主体 | 数据来源 |
|---|---|---|
| `vnt_status` | 大号状态徽标 + KV 网格（虚拟 IP / Token / 服务端地址 / TCP·UDP / 在线设备数 / 服务端版本）；解析不出的字段不显示；提示「详细列表需打开组网页」 | 解析 `statusText()` 固定格式 |
| `vnt_start` | 结果横幅（已下发 / 重复被忽略 / 启动失败 / 未授权 / Token 为空 / 地址非法 / 执行失败）+「本次下发参数」KV + 下一步建议 | `params`（含旧别名 `network_code`）+ `result_message` 前缀分类 |
| `vnt_stop` | 结果横幅 +「停止前状态」徽标与快照 KV +「是否执行停止」+ **开机自动组网记忆：已清除 / 保持不变** 醒目标签 | 从「停止前：…」/「当前未组网（…）」提取后再按 `vnt_status` 解析器拆字段 |

共同：命令 ID / 设备 / 下发与回执时间 / 执行状态条，以及「回执原文」可复制折叠区。**密码脱敏**（`params.password` 只显示「已设置（••••）」）；**`pending` 行不显示按钮**（点开无内容）；现有媒体「查看」按钮与悬停 tooltip 均保留。

**③ 命令本来可用时，强制下发置灰**

v0.2.33 的 `showForceSend` 对 VNT 两条恒定显示。本轮按用户拍板：选中 `vnt_stop` 且当前「运行中」时，下发按钮本来就能点 ⇒ 强制下发**仍显示但置灰不可用**（tooltip：「当前命令可直接下发，无需强制下发」）。

实现上把「纯状态判定」`isStateBlocked()` 与 `isBlocked()`（后者会被 `forceSend` 绕过）拆开，`forceSendDisabled = 选中 VNT 两条 && !isStateBlocked(cmd)`；并 `watch(forceSendDisabled)` 在命令由禁用变可用时把 `forceSend` 复位 `false`，否则开关虽置灰、残留勾选仍会误触发二次确认。**音乐 3 条 / 采集互斥的强制下发保持 v0.2.33 原行为不变。**

**④ 状态未知时禁用下发（2026-11-02 用户拍板，推翻 ① 节的「unknown 不硬禁用」）**

用户实测发现：状态未知时 `vnt_start` / `vnt_stop` 的「下发命令」按钮仍可点，判定不合理 ⇒ 改为 **unknown 一律禁用**。

实现：`isStateBlocked()` 删掉 `if (vntState.value === 'unknown') return false` 特判；因 `VNT_STATE_RULES` 不含 `unknown`，`!vntAllowed.includes('unknown')` 自然为 `true` ⇒ 卡片置灰 + 按钮禁用。`blockHint` 改为「下发按钮已禁用，请先点『刷新状态』」。覆盖三种 unknown 来源：从未查询（`vntSnapshot` 为空、徽标「未查询」）、探测回执超时/下发失败、回执文案解析失败。

**与 ③ 的自洽**：unknown 时命令被禁用 ⇒ `forceSendDisabled = !isStateBlocked = false` ⇒ 强制下发**可勾选**，配合二次确认仍是唯一出口 ⇒ 不违反「必可达（可接受延迟、不可永远访问不到）」硬约束。`watch(forceSendDisabled)` 只在置灰变 true 时复位 `forceSend`，unknown 下恒为 false，不会误清用户勾选。**音乐 3 条 / 采集互斥不受影响。**

**生效方式**：①②③ 的服务端改动需**重启服务器**；①②③④ 的后台改动需**重新构建部署 `admin/dist` + 刷新页面**；④ 仅前端、服务端与 APK 均不涉及；全流程 APK 不涉及、无需重装。

### vnt_status 组网状态

无参数。回执格式（1.x）：

```
运行中 · 虚拟IP 10.26.0.5 · Token xxx · 服务端 039039.xyz:31007（UDP）· 在线设备 3 · 服务端版本 1.2.x
（详细列表需打开组网页，命令不主动拉列表）
```

未连接 / 连接中 / 停止中 / 异常 各有对应文案。

**性能红线**：只读 `VntMeshController` 的本地状态快照，
**刻意不调 `Vnt.list()`** —— 虽是 native 侧缓存表的本地内存读（**不是网络 RPC**），
但仍属 JNI 调用，且按已拍板约定「详细设备列表只在组网页可见时刷新」，
命令通道不该触发。1.x 没有 2.x 的 `getClientList` RPC / NAT 类型 / 真实 RTT / 流量速率，
`vnt_status` 也不再展示这些字段。

**为什么没有「设备上下线推送」**：1.x 底层没有设备上下线事件回调，Java 层只能
轮询 `Vnt.list() -> PeerRouteInfo[]`（native 侧缓存的路由表）。UI 最长滞后一个刷新周期；
刷新仅在「App 在前台 + 处于组网页」时进行（进页面立即刷一次，之后 5 秒一次），
退后台零请求、零定时器。P2P/中继由 `Route.metric == 1` 判定（官方 `ConnectActivity` 同款逻辑）。

---

## SSH 服务器命令（v0.2.34 引入）

> 三条命令走「三步法」：`server/app/core/command_catalog.py` 的 `_cmd()` →
> `core/core-network/.../ws/Command.kt` 常量 + `CommandModule.kt` 执行器 → 本文档 + README 索引。
> 调研报告见 [../../research/sshd-integration.md](../../research/sshd-integration.md)，
> 改前留档台账见 [../ssh-integration-change-ledger.md](../ssh-integration-change-ledger.md)，
> 交互原型见 `assets/design/ui-mockup-ssh-page.html`。

**架构**：内嵌 **Apache MINA SSHD 2.20.0**（纯 Java，无需 root / 不依赖 dropbear 二进制），
由 `SshServerService`（`specialUse` 前台服务，通知 ID 2103）常驻。能力 = **SFTP 文件管理 +
完整 sh 终端 + SCP**，监听 `0.0.0.0:8022`（可改），用户名固定 `bridge`，访问密码首启随机生成
（APP 状态页展示、可改、SharedPreferences 持久化）。文件系统根 = 全盘 `/sdcard`
（需「所有文件访问」授权）或应用外部目录（未授权时自动降级，不崩溃）。

**生命周期（enabled / running 分离）**：`SshPrefs.enabled` 是持久化的「用户意愿」，
`SshServerController.state` 是进程内的「事实状态机」（STOPPED/STARTING/RUNNING/STOPPING/ERROR）。
完整自愈链 = BOOT_COMPLETED + MY_PACKAGE_REPLACED（`SshBootReceiver`）→
`MainActivity.onStart`（每实例一次）→ `START_STICKY` 进程重建 → `ssh_start` 命令兜底，
四条链共用 `SshAutoStart.tryStart()`，三重守卫（意愿为开 / 非 RUNNING·STARTING / 服务未活），
失败不重试。运行期默认持有 partial WakeLock（可关，防止 CPU suspend 后主动心跳发不出、
NAT 过期连接半死）。**页面 onDispose 绝不停服务**（与智能控制页相反，同组网页语义）。

### ssh_start 启动 SSH 服务

无参数（端口 / 密码在 APP「设置 → 连接 → SSH 服务」状态页管理）。

**回执与限制**：

- **幂等守卫放最前（v0.2.33 约定）**：`RUNNING`/`STARTING` → `success=false`
  「重复下发被忽略：SSH 服务已在运行（…）」，**不写任何状态**；`STOPPING` → 提示稍后用 `ssh_status` 确认。
- 守卫通过后先 `SshPrefs.setEnabled(true)` 写意愿（开机 / 冷启动 / STICKY 重建据此自动恢复），
  再 `SshServerController.start(context)` → `startForegroundService`（try/catch，
  Android 12+ 后台受限时回执「启动失败：多为 Android 12+ …」不假装成功）。
- 成功回执含端口 / 用户名 / 存储档 / PC 连接示例 `ssh -p 8022 bridge@<手机IP>`。
- 按崩溃台账 §5：`onCreate` 挂前台、`onStartCommand` 每次幂等补挂、
  `startForeground` try/catch + 失败 `stopSelf`；manifest `specialUse` 声明齐全。

### ssh_stop 停止 SSH 服务

无参数。走普通 `startService(ACTION_STOP)`（无前台义务），服务内部 `sshd.stop(true)`
（断开全部活动会话）→ 释放 WakeLock → `onStopped()` → `stopSelf()`。

**幂等守卫**：未运行（`STOPPED`/`ERROR`）时设备端直接回 `success=false`
「重复下发被忽略：当前 SSH 服务未运行（…）；未执行停止、自恢复意愿保持不变」，
不执行停止、不动意愿。`SshServerController.stop()`（未运行不拉起）+
`SshServerService.shutdown()` 双重收口，与 VNT 同款「startService 陷阱」防护。

**自恢复意愿**：`ssh_start` 置 `enabled=true`、`ssh_stop` 置 `false`（远程停止即用户明确
不想再自动拉起）；守卫忽略的重复命令**不动意愿**。与 APP 状态页开关共用同一份记忆。

### ssh_status SSH 服务状态

无参数。回执格式（`SshServerController.statusText()` 固定前缀，后台解析契约）：

```
运行中 · 端口 8022 · 用户名 bridge · 活动会话 1 · 存储暴露 全盘（/sdcard）（访问密码与 hostkey 指纹见 APP「设置 → 连接 → SSH 服务」状态页）
```

其它状态：`正在启动（…）` / `正在停止（…）` / `未运行` / `异常：…`。
只读本地状态快照，不触发网络操作。**密码与 hostkey 指纹刻意不回显**——只在 APP 状态页
展示，避免留在命令历史与服务器记录里。hostkey（EC P-256）首次生成后持久化，
PC 端 `known_hosts` 指纹变化即可判定重置 / 中间人。

### 状态联动、幂等守卫与强制下发（v0.2.34，完全对照 VNT v0.2.33 范式）

- 状态来源 = `ssh_status` 按需查询命令的回执（`silent=true` 静默探测不入历史），
  按 `device_id` 缓存快照。固定前缀解析：`运行中 ·`→running、`正在启动`→starting、
  `正在停止`→stopping、`未运行`→stopped、`异常：`/`执行失败`→error，其余→**unknown**。
- 自动探测时机：选中 `ssh_start`/`ssh_stop` 卡片、切换目标设备、顶栏「刷新设备状态」、
  这两条命令回执后 1.5s（与 VNT 同款 800ms 防抖 + key 防重复）。
- 禁用规则：`ssh_start` 仅 `stopped`/`error` 可用；`ssh_stop` 仅 `running`/`starting`/`stopping` 可用；
  `ssh_status` 不受限；**unknown 一律禁用**（2026-11-02 用户拍板）；命令本来可用时强制下发置灰。
- **强制下发**：勾选 + 二次确认是 unknown / 状态失真时的唯一出口；服务端 `force` 对 SSH
  不生效，设备端守卫照常按真实状态执行或忽略。
- **查看详情单独设计**（2026-11-02 分治约定）：`admin/src/components/SshResultDetailDialog.vue`——
  `ssh_status` 状态快照 KV（端口 / 用户名 / 活动会话 / 存储暴露）；`ssh_start` 结果分类解读 +
  从回执解析启动信息；`ssh_stop` 停止前状态 + 是否执行停止 + 自恢复意愿（已清除 / 保持不变）。
