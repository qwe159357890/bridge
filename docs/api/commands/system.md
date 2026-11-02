# 系统类命令（check_update / vnt_start / vnt_stop / vnt_status）

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
- 已处于 `RUNNING`/`STARTING` 时回执「已在运行」+ 当前状态快照，不重复建网。
- 参数覆盖会写回单套配置 Prefs（与页面「组网配置」共用一份）。
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

**v0.2.31 自动组网记忆（1.x 沿用）**：`vnt_start` 成功下发（或回「已在运行」）会把
`VntMeshPrefs.autoStart` 置 `true`，`vnt_stop` 置 `false`。记忆为 `true` 时，
App 下次冷启动由 `MainActivity.onStart` → `VntAutoStart.tryStart()` 自动开始组网；
三道守卫（记忆未开 / 已 RUNNING·STARTING / VPN 授权失效）会跳过，且**失败不重试**。
与页面「开始组网」/「停止组网」按钮共用同一份记忆；退出应用 / 切后台结束进程**不动记忆**。

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
