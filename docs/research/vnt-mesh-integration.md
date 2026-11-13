# VNT 组网集成调研报告（虚拟局域网 / SD-WAN）

> 目标：把开源组网项目 **VNT** 集成进 Bridge，**在「快递扫码」之后新增一个「组网」页面**，让手机直接加入 VNT 虚拟网，与远端电脑 / 服务器 / NAT 后设备像同一局域网一样互通。
> 调研方式：`references/vnt/` 三个仓库逐文件实读（Rust 核心 + Android JNI + 官方安卓客户端 + 服务端）+ 对照本机 `app/`、`core/` 现状与既有集成范式（微信 / 销售 / ESP32 / 扫码）。
> 状态：**已实现 v0.2.30（P0 + P1 + P2 全部落地）**：官方 `libvnt_jni.so` 已入库校验 PASS；`com/vnt/*.java` + `my/bridge/com/vnt/`（VntVpnService / VntMeshController / VntMeshState / VntMeshPrefs / VntConfigBuilder）+ `ui/VntPages.kt` + 主页第 5 Tab（含 4→5 迁移）+ 退出流程接入 + 三条命令 + 全量文档登记均已完成，`:app:compileDebugKotlin` 通过。**唯一剩余：真机组网联调与省电实测**（无 VNTS 实体/多设备环境）。原型见 `assets/design/ui-mockup-vnt-mesh.html`；改前留档与回滚配方见 `docs/dev/vnt-integration-change-ledger.md`。
> **决策已定**：9 项拍板见 **§九**（只 arm64 / 单配置 / 服务端可输入默认公共服 / 常驻至手动停止 / 退出流程最高优先级 / 不做子网转发 / 三条命令全做）。
> **工程约定**：本地不安装任何程序；`libvnt_jni.so` 走官方预编译产物（§2.4），任何需安装的动作必须先经用户同意。
> **★ v0.2.31 修订（2026-11-01 晚）**：真机反馈驱动的 4 项修订（对齐官方 universal 三套 ABI / 关于页滚动 / 自动组网记忆 / v2.0.11 地址规则与报错修复）见 **§十二**。§2.4、§5.1、§九 中关于「只 arm64」「裸地址默认 UDP」的旧表述**以 §十二 为准**（原文保留不删，避免破坏改前留档）。
> **★★ v0.2.32 重大修订（2026-11-02，当前实现以此为准）**：用户确认**自建 VNTS 与全部存量客户端都是 1.x 且必须互通**，而 VNT 2.0 官方明写与 1.0 **不兼容**（`vnt-main/README.md:167`），服务端又不能升级（会打断存量客户端）。因此 **Bridge 核心整体回退替换为官方 VntApp `v1.2.9`（最后一个 Java JNI 版）**：`top.wherewego.vnt.jni.*` 14 个绑定类 + 2 套 `libvnt_jni.so`（arm64-v8a / x86_64）。本报告 §2.4 / §5.1 / §九 / §十二 中所有 **2.0.11 相关表述（`udp://` 非法、`network_code`、`tcp(tls)`/`wss`/`quic`、certMode、getClientList、NAT/RTT/速率、三套 ABI）现仅作历史记录**，**当前代码以 §十三 为准**。2.x 成果由改前留档台账 §六 + git 历史完整保留，将来服务端升级可按配方还原。

---

## 〇、结论速览

| 项 | 结论 |
|---|---|
| 技术可行性 | **可行**。VNT 官方自带 **Android JNI 绑定**（`com.vnt.*` Java 类 + `AndroidVpnExample` 完整示例）**和预编译好的 `.so`**，无需我们重写协议、无需 root |
| 推荐方案 | **方案 A：JNI 内嵌** —— 直接用官方 `vnt-dev/vnt` v2.0.11 发布的 `libvnt_jni.so` 打进 APK，Bridge 自建 `VpnService` 持有连接；**服务端零改动、本地零安装**（沿用 VNT/VNTS 生态） |
| 许可证 | Apache-2.0（`vnt-main`/`vnt-jni`/`VntApp` 均为 Apache-2.0），可商用，需保留声明 |
| 主要代价 | ① APK 体积增加约 5 MB（**只 arm64，一个 `.so`**）；② 占用系统唯一 VPN 通道；③ 新增一个 `specialUse` 前台服务（走崩溃台账 §5 检查清单）；④ **升级 VNT 版本时需重新核对产物版本与符号**（见 §2.4） |
| 工作量 | 约 **5.5~6.5 人日**（~~构建链路~~ 产物下载+校验 0.3 + 服务与 JNI 接入 2 + UI 2~3 + Tab/退出 0.8 + 命令/文档 1.5），不含真机组网验证与省电实测 |
| 最大风险 | **Android 同时只允许一个 VPN** 的排他性；其次是 `nextEvent` 阻塞线程与 native 崩溃不可观测 |
| P0 状态 | ✅ **已完成**（官方 so 下载 + SHA256/ELF/符号三项校验 PASS + 入库），见 **§2.4** |
| ★ 本轮新发现 | ① **官方 v2.0.11 Release 直接提供编译好的 `.so`，与本地源码版本一致，根本不用装 Rust/NDK**（§2.4）；② `getClientList()` **是主动 RPC 且最多阻塞 3 秒**，必须在后台线程调；③ **没有设备上下线事件**，UI 只能轮询；④ 速率字段底层没有，需自行差分；⑤ 中继 RTT 是 `server_rtt×2` 的估算值（详见 **§2.5**） |

---

## 一、参考项目全景（`references/vnt/` 是什么）

三个**互相独立**的仓库，不是一个 monorepo：

| 目录 | 身份 | 关键内容 |
|---|---|---|
| `vnt-main/` | **核心 + 多形态客户端**（workspace 根 crate `vnt2`，v2.0.11） | `vnt-core`（组网内核，Rust）、`vnt-jni`（**Android JNI 绑定**）、`vnt-ipc`、`vnt-web`、`vnt-desktop`（Tauri 桌面端）；二进制 `vnt2_cli` / `vnt2_ctrl` / `vnt2_web` |
| `VntApp-master/` | **官方原生安卓客户端** | Java 单 Activity 手写 UI（1712 行）；`VntVpnService`（665 行）+ `com/vnt/*` 绑定 + Gradle 引 `jniLibs`；**本项目最该照抄的样板** |
| `vnts-master/` | **自建服务端** | Rust 服务端 + Vue3 管理前端（rust-embed 内嵌）；可选内置 IKEv2 / WireGuard 接入 |

### 1.1 版本与技术栈（实锤）

- `vnt-main/Cargo.toml`：`[workspace] members = ["vnt-web","vnt-ipc","vnt-core","vnt-jni","vnt-desktop/src-tauri"]`，edition **2024**，license **Apache-2.0**。
- `vnt-jni/Cargo.toml`：`crate-type = ["cdylib"]` → 产物就是 **`libvnt_jni.so`**；依赖 `jni 0.21` + `vnt-core`。
- `vnt-core` 关键依赖：`tun-rs`(TUN/TAP)、`rustp2p-core`（**git 依赖，构建需联网**）、`quinn`+`rustls`(QUIC/TLS)、`lz4_flex`(压缩)、`reed-solomon-erasure`(FEC)、`ring`、`prost`+`protoc-bin-vendored`。
- release profile 已做小体积优化：`opt-level='z', lto=true, strip=true, codegen-units=1`。
- VntApp：`minSdk 24 / targetSdk 37 / Java 17`，`packaging { jniLibs.useLegacyPackaging = false }`，ABI 由 gradle 属性 `vntAbi` 控制。
- 端口：数据面（TCP-TLS / WSS / QUIC）默认 **29872**；VNTS 管理端 **29871**；`vnt2_web` 本地 **19099**。公共体验服务端 `vnt2.rustvnt.com:29872`。

### 1.2 组网三要素与能力边界

| 能力 | 说明 |
|---|---|
| 组网三要素 | **服务端地址 + 组网编号(network_code) + 组网密码**；三者一致的设备自动组成虚拟局域网 |
| 加密 | 设置密码后节点间 **ChaCha20-Poly1305** 端到端加密，服务端只见密文；`cert_mode = finger:<64位hex>` 绑证书防伪造服务端 |
| 传输 | `tcp://` `wss://` `quic://` 可配多个服务端做容灾；P2P 打洞失败自动回落服务端中继 |
| 设备模式 | `tun`(三层) / `tap`(二层，桌面) / `no`(无网卡，仅出口与端口映射)；**Android 仅支持 tun 与 no** |
| 虚拟 IPv6 | 默认派生 `fd76:6e74::/96`（如 `10.26.0.2` → `fd76:6e74::a1a:2`），Android 宿主需自行加地址与路由 |
| 子网转发 | `input` / `output` / `subnet_mapping` / `auto_sync_subnet` / `port_mapping` —— 远程访问家中 NAS、摄像头、内网 Web 的关键 |
| 无服务端组网 | 固定虚拟 IP + `peer_address` 种子，可完全不要服务端 |
| 订阅配置 | VNTS 可签发订阅链接，客户端每次启动拉取并热更新（策略原地生效，IP/MTU/路由变更需重建接口） |

---

## 二、Android 集成面（实读 `vnt-jni`，这是本方案的立足点）

### 2.1 可直接搬进项目的 Java 绑定

`vnt-main/vnt-jni/java_example/` 提供完整源码（约 2000 行）：

```
com/vnt/VntManager.java        init / destroy / fetchSubscriptionConfig / createNetwork(config)
com/vnt/VntNetwork.java        getNetwork / getLogs / startTun / nextEvent / applyRuntimeChange(+fd) / getApi / isNoTun / stop
com/vnt/VntApi.java            getClientList / getNetwork / getNatInfo / getTunnelListenAddresses / getServerList / getRouteTable / isDirect / getPeerNatInfo / getPacketLoss / getTrafficInfo
com/vnt/VntConfig.java         Builder：addServer / setNetworkCode / setPassword / setDeviceId / setDeviceName / setIp / setMtu / setCertMode / setCompress / setFec / setRtx / setNoPunch / …（30 个）
com/vnt/{NetworkResult,RuntimeEvent,RuntimeChange,ChangeApplyResult,LogEntry,VntException,VirtualIpv6}.java
AndroidVpnExample.java         ★ 267 行完整 VpnService 示例，就是我们的骨架
```

> JNI 符号是 `Java_com_vnt_VntManager_nativeInit` 这类长名 —— **Java 侧包名必须保持 `com.vnt`**，改包名需同步改 Rust 的 `#[no_mangle]` 符号（不建议）。

### 2.2 官方调用时序（`AndroidVpnExample` + `VntVpnService` 实读）

```
VntManager.init()                      // 全局一次
  ↓
VntNetwork net = VntManager.createNetwork(config)   // 内部后台连服务器并注册
  ↓
NetworkResult r = net.getNetwork()     // 阻塞到注册完成，拿到虚拟 IP / 掩码 / 网关 / 路由
  ↓
VpnService.Builder  →  setMtu / addAddress(ip,prefix) / addAddress(virtualIpv6)
                       addRoute(虚拟网段) / addRoute(fd76:6e74::/96) / addRoute(ff00::/8)
                       establish() → detachFd()
  ↓
net.startTun(tunFd)                    // Android 必须传 fd（-1 = 无网卡模式）
  ↓
VntApi api = net.getApi()              // 后续所有查询走它
  ↓
独立线程循环：  event = net.nextEvent()          // 阻塞
   ├ instance_stopped → 结束循环
   └ changed 快照 → needsVpnRebuild / needsInstanceRebuild ? 重建接口并 applyRuntimeChange(fd) : applyRuntimeChange()
  ↓
net.stop()  →  VntManager.destroy()
```

生产级 `VntVpnService` 的关键做法（值得照抄）：
- `ACTION_START` / `ACTION_STOP` 两个 action + `ContextCompat.startForegroundService`；`onStartCommand` 收到 **null intent（被系统重启）时从 `vnt_active_connection` 恢复**，返回 `START_STICKY`。
- 前台通知渠道 `vnt_connection`、id **1207**；`uiVisible` 时才 3 秒轮询刷新状态（省电）。
- 运行期变更循环跑在 **daemon 线程**（`vnt-runtime-listener`），用 `generation` + `expectedNetwork` 双重校验作废旧实例回调。
- `MIN_MTU=1280 / DEFAULT_MTU=1380`；Android 端**强制内置 NAT**（移除 `no_nat`）。
- 状态经**包内广播** `ACTION_STATE` 发给 UI（我们可直接换成本项目的 `StateFlow`/`VntMeshState`）。

### 2.3 Android 侧硬约束（踩坑高发区）

1. **必须用户授权**：`VpnService.prepare()` 返回非空 Intent 时需 `startActivityForResult`，首次弹系统确认框。
2. **Android 同时只允许一个 VPN**：启动会挤掉用户其它 VPN 应用（VntApp README 明示），UI 需提示。
3. **绝不能加 `0.0.0.0/0` 路由**：会劫持整机流量（Bridge 自身的 WS 心跳 / 微信 / 销售连接会全被卷进隧道）。只加虚拟网段 + 服务端下发的出口网段。
4. **Android `addAddress` 不自动生成路由**，虚拟网段必须显式 `addRoute`；IPv6 需加 `fd76:6e74::/96` 与 `ff00::/8`。
5. **Android 14+（我们 targetSdk 36）**：VPN 前台服务须声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，并带 `android:permission="android.permission.BIND_VPN_SERVICE"` 与 `android.net.VpnService` 的 intent-filter（缺任一即启动崩）。
6. **16KB 页对齐**：Android 15+ 要求。**官方 v2.0.11 预编译产物已实测符合**（4 个 PT_LOAD 段 `p_align=0x4000`，见 §2.4）。若将来自编，才需要 `-C link-arg=-Wl,-z,max-page-size=16384`。

### 2.4 产物方案（★ 已定：直接用官方预编译 so，本地零安装）

> **纠错说明（2026-11-01）**：本节初稿写过「`references/vnt/` 无预编译 so，必须自己用 Rust + NDK 编」。
> 前半句只对**本地 `references/` 目录**成立，后半句是**错误的全局结论**——当时漏查了官方 GitHub Release。
> 现更正为：**官方已按 ABI 发布编译好的 `.so` + `.sha256`，版本与本地源码 v2.0.11 完全一致，直接下载即可，无需安装任何工具链。**

#### 为什么有源码还不够，但也不必本地编译

`VntManager.java` 开头就是 `System.loadLibrary("vnt_jni")`，四个绑定类共 **23 个 `native` 声明且无一行实现**；
真正的协议栈在 `vnt-jni/src/lib.rs`（1763 行 Rust）+ `vnt-core`。源码必须编译成 `libvnt_jni.so` 才能被 JVM 加载
（类比：Kotlin 源码 → Gradle/JDK → APK）。**但编译不必发生在本地** —— 官方已经编好了。

#### 官方产物实况（`vnt-dev/vnt` tag `v2.0.11`）

| 项 | 值 |
|---|---|
| 产物 | `vnt-jni-arm64-v8a-v2.0.11.so`（另有 armeabi-v7a / x86_64，**我们只取 arm64**） |
| 大小 | 5,042,792 B |
| SHA256 | `c33535b17b860ae3f09d64dba286d81d51387732db2e6bf9115ba2aed0038f48`（官方 `.sha256` 文件同名提供） |
| 版本一致性 | **v2.0.11 == `references/vnt/vnt-main/Cargo.toml` 的 `version = "2.0.11"`** ⇒ JNI 符号与 `com.vnt.*` 绑定严格对应 |
| 许可证 | Apache-2.0（需保留声明） |
| 下载 | `https://github.com/vnt-dev/vnt/releases/download/v2.0.11/vnt-jni-arm64-v8a-v2.0.11.so`（**走 gh-proxy 前缀**，直连 release 会超时） |

#### 已完成的入库与校验（2026-11-01，P0 已完成）

```
tools/vnt/artifacts/vnt-jni-arm64-v8a-v2.0.11.so(.sha256)   原始产物存档
        ↓ 校验通过后复制（文件名必须改成 libvnt_jni.so，对应 loadLibrary("vnt_jni")）
app/src/main/jniLibs/arm64-v8a/libvnt_jni.so                 实际打包用
```

校验用 **`tools/vnt/verify_so.py`**（纯 Python 标准库解析 ELF，**不依赖 NDK 的 llvm-nm/readelf**），实测结果：

| 校验项 | 结果 |
|---|---|
| SHA256 与官方比对 | **PASS** |
| ELF64 / AArch64 / 小端 | **PASS**（`e_machine=183`） |
| PT_LOAD 16KB 对齐 | **PASS**，4 个段全部 `p_align=0x4000` |
| 三个关键 JNI 符号 | **PASS**（`nativeInit` / `nativeNextEvent` / `nativeApplyRuntimeChangeFd`） |
| 导出符号完整性 | **23 个 `Java_com_vnt_*` 导出 == Java 层 23 个 native 声明，逐个对应、零缺失**（脚本自动比对，非人工目测） |

> 版本一致性最后这一条最关键：**23 对 23 完全一一对应**，证明这份 so 就是本地这套 Java 绑定的实现，
> 不会出现「改包名 / 找不到符号 / 参数签名对不上」的问题。

#### 升级 VNT 版本时的流程（将来）

1. 核对新 tag 与 `references/vnt/` 源码版本一致；确认 `vnt-jni-arm64-v8a-<ver>.so` 存在。
2. `curl` 下载 so + `.sha256` 到 `tools/vnt/artifacts/`。
3. `python tools/vnt/verify_so.py <so> <sha256>` —— **必须全 PASS 才继续**。
4. 覆盖 `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so`，并在本文档更新版本 / SHA256 / 校验日期。
5. 若新版本改了 `com.vnt.*` 的 native 声明，须同步更新 `com/vnt/*.java` 后再编译 Kotlin。

#### 兜底：真要自编时才需要的链路（本次不用）

`VntApp-master/scripts/build-android-jni.sh`：NDK r27d + cargo，三 ABI 交叉编译 `-p vnt-jni`
（`aarch64-linux-android`→arm64-v8a / `armv7-linux-androideabi`→armeabi-v7a / `x86_64-linux-android`→x86_64），
产物拷进 `jniLibs/<abi>/`，`llvm-nm` 校验符号、`llvm-readelf` 校验 LOAD 对齐，
编译参数带 `-C link-arg=-Wl,-z,max-page-size=16384`。
**该链路需要本地安装 Rust + NDK，未经用户同意不得执行**；本项目已有官方产物，正常流程无需走到这一步。

### 2.5 运行期数据的刷新机制（源码实锤，回答「P2P/中继、速率、18ms 怎么来的」）

先给结论：**设备列表是「UI 可见时 3 秒主动 RPC 拉取」，不是被动推送；速率是本机两次采样差分算出来的，不是服务端给的。**

#### (1) 三项信息的真实出处

| UI 显示 | 字段 | 真实来源（源码位置） |
|---|---|---|
| `P2P` | `direct` | `vnt-jni/src/lib.rs:763` `route.metric() == 1`。`metric==1` 即一跳直达 = P2P 打洞成功；否则走服务端中继 |
| `↑12K ↓84K` | `txSpeed / rxSpeed` | **VntApp 自己算的**：`VntVpnService.java:525 withTrafficSpeeds()` 用本次 `traffic.txBytes()` 减上次采样值再除以间隔（`bytesPerSecond()`）。底层只提供**累计字节数** `VntApi.Traffic`，**没有速率字段** |
| `18ms` | `rtt` | `vnt-core/src/api/mod.rs:57 get_rtt()`：有 P2P 路由 → `route.rtt()`（真实往返）；**无路由（中继）→ `server_node_rtt(ip) * 2`**（本机到服务端 RTT 的两倍，是**估算**）。服务端 RTT 由 `MsgType::PongTurn` 回填（`server/inbound.rs:199`） |

> 所以：**P2P 那行的 18ms 是实测往返；中继行的延时是估算值**，UI 上最好区分标注（原型里建议中继行加"≈"）。

#### (2) 刷新 = 主动请求，且是**会发网络包的 RPC**

这一点最容易踩：`VntApi.getClientList()` 看着像读本地状态，**实际会向服务端发一次 RPC 请求**：

```
vnt-jni/src/lib.rs:731   runtime.block_on(api.server_rpc().client_list())
                                    ↓
tunnel_core/server/rpc.rs:229  RpcMessageRequest{ ClientListReq }
                         :241   send_to_gateway_expired(server_id, packet, timeout=1s)
                         :243   waiter.wait_for_response(Duration::from_secs(3))
```

即：**复用已建立的 UDP 隧道发一个 `MsgType::RpcReq`（`ttl=1`，只到服务端）**，等服务端回 `RpcRes`，**超时 3 秒**。

两个必知后果：
- **会阻塞调用线程最多 3 秒**（`block_on`）。**绝对不能在主线程调**——VntApp 是放在 `worker` 线程池里跑的（`scheduleWithFixedDelay(this::refresh, 3, 3, SECONDS)`）。
- 它**不是本地内存读取**，频繁调用会产生真实上行包。

#### (3) 刷新频率与省电（VntApp 的做法）

```java
// VntVpnService.java:499-512
private void startRefreshing() {
    if (!uiVisible || api == null || state.status != RUNNING) return;
    refreshTask = worker.scheduleWithFixedDelay(this::refresh, 3, 3, TimeUnit.SECONDS);
}
private void refresh() {
    if (!uiVisible || state.status != RUNNING || api == null) return;   // 双重守卫
    publish(readState(...));   // getClientList + getServerList + getRouteTable + getNatInfo + getNetwork + getTunnelListenAddresses
}
```

- **频率：固定间隔 3 秒**（`scheduleWithFixedDelay(this::refresh, 3, 3, SECONDS)`，上一次结束到下一次开始之间 3 秒，不会堆积）。
- **省电设计就一个：`uiVisible` 开关**。页面不可见 / 服务非 RUNNING → 直接 return 且**取消定时任务**（`stopRefreshing()` 同时 `resetTrafficSamples()`）。也就是说**退后台后一次都不拉**。
- 一次 `refresh()` 实际发 **1 次 RPC**（`getClientList`，`getServerList`/`getRouteTable`/`getNatInfo`/`getNetwork` 都是纯本地内存读取）。

#### ★ Bridge 采用的刷新节奏（已拍板）

**「App 在前台 + 停在组网页」才刷；进页面立刻刷一次，之后每 5 秒一次；离开页面立即停。**

```
进入组网页（且 status == RUNNING）
   → refreshNow()                      ★ 立即拉一次，不等第一个周期
   → scheduleWithFixedDelay(refresh, 5, 5, SECONDS)
离开组网页 / 退后台 / 锁屏 / 服务非 RUNNING
   → cancel 定时任务 + resetTrafficSamples()   ★ 一次都不再拉
回到页面
   → 重新走「立即一次 + 5 秒周期」
```

三处与 VntApp 的差异（都是刻意的改进）：

| 项 | VntApp | Bridge | 理由 |
|---|---|---|---|
| 首次刷新 | `scheduleWithFixedDelay(3, 3)` ⇒ **进入页面后要空等 3 秒才出数据** | **先 `refreshNow()` 再排周期** | 否则进页面看到 3 秒空白/骨架屏，观感差 |
| 周期 | 3 秒 | **5 秒** | 设备列表不需要 3 秒级精度；5 秒足够观察，请求量降 40% |
| 可见性判定 | `uiVisible` 由 Activity 生命周期置位 | 用 **Compose `DisposableEffect`** 进入/离开组合来置位更精确 | 组网页是主页二级 Tab，切到别的 Tab 就会移出组合，天然就是"不在组网界面" |

**省电结论**：这条策略已经是最省的形态了——**不在组网界面时零请求、零定时器**。
补充一点量级：组网激活时射频本来就醒着，单次 RPC 只有几十字节，**边际耗电极小**；
真正该防的是「App 在前台但停在别的 Tab / 屏幕亮着没人看」时的空转，这正是本策略挡掉的部分。

> 实现提醒：`refreshNow()` 与周期任务**都要走后台线程**（`getClientList` 最多阻塞 3 秒），
> 且要用同一个单线程池 + **generation 校验**，避免快速进出页面时旧的慢请求回来覆盖新状态。

#### (4) 其它设备掉线：底层是**被动广播**，但 Java 层**没有事件**，只能靠轮询看到

这是本轮最重要的发现，分两层看：

**底层：服务端会主动推**（`vnt-core/src/tunnel_core/server/inbound.rs:210`）
```rust
MsgType::PushClientIps => {
    let list = ClientSimpleInfoList::from_slice(net_packet.payload())?;
    let changed = self.server_info.update_client_simple_list(server_id, self_ip, list, now);
    for ip in changed { self.punch_backoff.reset(ip); }   // 上下线变化的 IP
}
```
服务端在有设备上线/下线时推 `PushClientIps`，本地 `context/mod.rs:665` 会**比对前后 online 位**算出 `changed` 列表（含"原本在线、这次名单里没了"= 掉线）。**所以"谁掉线了"服务端是实时知道的，也实时推给了客户端。**

**但 Java/JNI 层拿不到这个事件**——`RuntimeEvent` 枚举只有两个变体（`vnt-core/src/network_info.rs:100`）：
```rust
pub enum RuntimeEvent {
    InstanceStopped,            // 组网实例已停止
    Changed(Box<RuntimeChange>),// 配置快照变更
}
```
**没有 `PeerOnline` / `PeerOffline`。** `nextEvent()` 只吐这两类。⇒ **UI 想知道设备上下线，只能自己定时调 `getClientList()`**。

**延迟量级**：服务端推送是即时的，但 UI 最长会有 **一个轮询周期（3 秒，建议 5 秒）** 的滞后；且**页面不可见时完全不刷新**，回到前台的第一次 `refresh()` 才补齐。

**要不要加"掉线通知"？** 可以在 `VntMeshState` 里做**前后两次快照的 online 差分**（照 `context/mod.rs:665` 的思路），变化时发一条本地通知。代价：只在页面可见时才有意义（后台不轮询就无从差分）；且设备闪断会误报，需加"连续 N 个周期都离线才判定"的防抖。**建议 P2 再做，先只做列表变灰。**

---

## 三、Bridge 现状对照

| 维度 | 现状（实读） | 对集成的影响 |
|---|---|---|
| 主页二级 Tab | `MainScreen.HomePage` 的 `when(tabOrder[cur])`：`0 短信 / 1 音频中继 / 2 智能控制 / 3 快递扫码` | 新增 `4 -> VntPage()`；`TabOrderPrefs` 的 `PAGE_TITLES`/`PAGE_ICONS` 加第 5 项 |
| Tab 顺序持久化 | `TabOrderPrefs.isValid()` **硬编码** `size==4 && toSet()==setOf(0,1,2,3)`，默认 `0,1,2,3` | **必须改 5 并做迁移**：老用户已存的 `0,1,2,3` 会判非法静默回退默认，自定义顺序/改名会丢 |
| 页面级连接范式 | `RelaySwitchPages.kt:104`、`ScanPages.kt:672`（扫码上传客户端）、`SalesPages.kt:196` 都是 `DisposableEffect{ start(); onDispose{ stop() } }` | **组网不能照抄**：它是基础设施，需服务常驻 + 显式开关；页面只订阅状态 |
| 前台服务 | 已有 KeepAlive / MediaCapture / AudioRelay 三个 | 新增第 4 个（VPN），须逐条对照 `docs/dev/crash-ledger.md` §5 清单 |
| 设备标识 | `DeviceStore` 的 ANDROID_ID | 直接作 VNT `deviceId`（VNT 要求同服务端+同网络编号内唯一；Android 上它不取机器码，建议显式设置） |
| 日志 | `DiagLog`（core-common） | 统一用它打「组网」TAG，走既有日志上传 |
| 退出流程 | `AppExit.kill`（stopService×3 → finishAffinity → 延迟 300ms → kill） | 组网开启时需并入：先停 VNT 服务再自杀，否则 VPN 会在系统里残留 |
| 心跳台账 | `docs/dev/heartbeat-inventory.md` | VNT 自身保活在 Rust 内部（Java 侧未暴露周期）→ 作为**黑盒常驻连接**登记进 §1，避免日后"改一处忘四处" |

---

## 四、集成方案对比与拍板

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. JNI 内嵌（推荐）** | 编译 `libvnt_jni.so` 进 APK，自建 `VntVpnService` | 全功能自持、统一 UI、可被服务端命令远程启停、不依赖额外 App | 需 Rust/NDK 构建链；APK 增大；占用系统唯一 VPN | ★**推荐** |
| B. 与官方 VntApp 共存 | 只做跳转/不集成 | 零成本 | 无法统一 UI、无法远程控制、用户要装两个 App | ✗ |
| C. 服务端侧组网 | 在 `server/` 机器上跑 `vnt2_cli` 做网关 | 手机不占 VPN、不动客户端 | 手机自身不入网，做不到"手机访问远端内网" | 可选补充（做出口网关） |
| D. 命令化壳子 | 服务端下发命令调起外部 App | 轻量 | 官方无稳定对外 Intent 契约，不可控 | ✗ |

**拍板：方案 A**，「组网」作为主页第 5 个 Tab；VNTS 自建与否可后置（先用公共服务端验证，再决定是否自建）。

---

## 五、Bridge 端落地设计（方案 A）

### 5.1 目录与文件（照既有范式）

```
app/src/main/java/my/bridge/com/vnt/        新包（注意与 relay/audio、switch/继电器 区分）
  ├ VntVpnService.kt     继承 android.net.VpnService；前台服务 specialUse；持有 VntNetwork 句柄与 fd
  ├ VntMeshController.kt 启停编排：init → createNetwork → establish → startTun → 变更循环线程
  ├ VntMeshState.kt      状态对象（status / ip / nat / servers / routes / clients）供 Compose 订阅
  ├ VntMeshPrefs.kt      SharedPreferences 持久化（照 RelaySwitchPrefs 写法）
  └ VntConfigBuilder.kt  把 Prefs → VntConfig.Builder 的映射
com/vnt/*.java           官方绑定原样拷入（保持包名 com.vnt）
app/src/main/jniLibs/arm64-v8a/libvnt_jni.so   ★ 已入库（官方 v2.0.11 预编译产物，见 §2.4）
tools/vnt/artifacts/     原始产物存档（含官方 .sha256）
tools/vnt/verify_so.py   ELF/SHA256/符号校验脚本（纯 Python，无需 NDK）
app/src/main/java/my/bridge/com/ui/VntPages.kt  Compose 页面
```

> 命名铁律：本项目已有 `relay/`（音频中继）、`switch/`（ESP32 继电器）；VNT 用 **`vnt/`**，勿混。
> ABI 铁律：**只出 `arm64-v8a`**。`app/build.gradle.kts` 建议显式加 `ndk { abiFilters += "arm64-v8a" }`，
> 在构建期就限定，避免其它 ABI 设备装上后加载 so 失败；**这一步属既有文件修改，P1 动手前须按「改前留档」约定先固化原文**。

### 5.2 生命周期策略（重要决策）

- **服务常驻 + 显式开关**，而非页面级 `DisposableEffect`：用户点「开始组网」→ `startForegroundService(VntVpnService)`；切 Tab / 退后台不断连；点「停止组网」或走 `AppExit.kill` 才断。
- 页面 `DisposableEffect` **只订阅状态、不启停连接**（与 `AudioRelayPage` 一致：切页面只解注册监听）。
- 前台化义务按崩溃台账 §5：`onCreate` 挂前台 + `onStartCommand` 每次拉起都补挂（幂等），`startForeground` 包 try/catch，失败 `stopSelf`。
- **★ 实现调整（v0.2.30 落地）**：`onStartCommand` 返回 **`START_NOT_STICKY`**（而非官方 VntApp 的 `START_STICKY` + null intent 自动恢复）。
  理由：进程被杀后系统不自动复活服务，天然满足「退出即整体结束」的最高优先级；也省掉「从 SharedPreferences 恢复连接」的复杂度与 VPN 授权失效风险。
  切 Tab / 退后台 / 锁屏这些**正常场景**下服务本就在前台运行，不受影响。
- **★ 退出优先级（已拍板）**：设置页「退出应用」按钮与「切到后台立即结束进程」开关是**最高优先级**，
  一旦触发即整体结束进程，**组网作为其中一部分自然一并终止**。
  实现上仍在 `AppExit.kill` 流程里 **先显式 `stopService(VntVpnService)`**（虽已是 `START_NOT_STICKY`，
  但它持有系统唯一 VPN 通道与 native 句柄，不先释放会残留到进程终止才断开），
  **再**走既有 `finishAffinity()` → 300ms → `killProcess`。
  ⇒ **不需要**为组网单独设计"退出时是否保留"的开关，也不要让它豁免于退出流程。

### 5.3 UI 组成（对应原型）

1. 状态卡：未连接 / 连接中 / 已连接（虚拟 IP、NAT 类型、服务端 RTT）。
2. 本机信息卡：虚拟 IP（含 IPv6）、网络编号、设备名/设备 ID、MTU。
3. 设备列表：在线状态、直连(P2P)/中继、协议与 metric、RTT（中继行标 `≈`）、上下行速率、丢包率。
4. 服务端列表：地址、协议、连接状态、RTT。
5. 路由表：目标 → 下一跳 / 协议 / metric / RTT。
6. 配置弹窗（**单套配置，不做多配置切换**）：
   - **服务端地址：可输入**（自建 VNTS 地址，形如 `vnt.example.com:29872` 或 `tcp://…`/`wss://…`）；
     **默认值 = 公共服务端 `vnt2.rustvnt.com:29872`**（仅作开箱即用的兜底，UI 上标注「公共测试服，生产请填自建」）。
   - 网络编号、组网密码、设备名、设备 ID（默认 `ANDROID_ID`）、固定虚拟 IP（可留空由服务端分配）、MTU。
   - 开关：压缩 / FEC / RTX / 禁用打洞。
   - **「高级（子网 / 端口映射）」入口保留但置灰**，标注「后续版本开放」（见 §九 决策 8 + §9.3 预留接口清单）：
     本期不实现，但接口已调研完毕，代码里留 TODO 与 Prefs 空字段，将来启用**不需要重编 `.so`**。
7. 主按钮：开始组网（蓝）/ 停止组网（红）。

### 5.4 命令与上报（已拍板：三条都做，P2）

按 `docs/api/command-catalog.md` 三步走新增 `vnt_start` / `vnt_stop` / `vnt_status`（类别 `system`）：catalog 加 `_cmd()` → `Command.kt` + `CommandModule` 执行器 → `docs/api/commands/system.md` 章节 + README 索引。

- 执行器里若走 `startForegroundService`，须满足 §5.2 的前台化义务（崩溃台账 §5：一次调用=一次前台义务，try/catch + 失败 `stopSelf`）。
- `vnt_start` 后台受限（Android 12+ `ForegroundServiceStartNotAllowedException`、VPN 授权需 Activity），**回执必须如实说明**。
- `vnt_status` **不触发 RPC**，只回本地缓存快照（详见 §9.2 表格）。

---

## 六、主要流程

### 6.1 首次连接

```
用户点「开始组网」 → 检查 VpnService.prepare()：
  ├ 返回 Intent  → startActivityForResult 系统授权框（首次）
  └ 返回 null    → 已授权，直接继续
   ↓
startForegroundService(VntVpnService)  → 挂前台通知（必做，否则 ANR/崩）
   ↓ 工作线程
VntManager.init() → createNetwork(config) → getNetwork()（拿虚拟 IP）
   ↓
Builder.establish() → detachFd() → startTun(fd) → getApi()
   ↓
启动 daemon 变更循环线程 → 状态置 RUNNING → 通知「已连接 · 10.26.0.x」
```

### 6.2 运行期变更（订阅/服务端策略下发）

```
nextEvent() 阻塞返回
  ├ instance_stopped            → 收尾：置 STOPPED、stopSelf
  └ changed 快照
      ├ needsVpnRebuild / needsInstanceRebuild = true → 用快照的 ip/mtu/routes 重新 establish()，拿新 fd，applyRuntimeChange(fd)
      └ 否则（纯策略/服务器类）                → applyRuntimeChange() 原地生效
  每次应用后刷新 api 与已建立的 CIDR
```

### 6.3 停止 / 退出

```
点「停止组网」或 AppExit.kill
  → cancellationRequested=true（让阻塞的 nextEvent 循环退出）
  → VntNetwork.stop() → 关 fd → VntManager.destroy()
  → stopForeground(REMOVE) + stopSelf()
  → AppExit 流程中：先停 VNT 服务，再走既有的 stopService×3 → finishAffinity → kill
```

---

## 七、注意点与坑（集成时逐条对照）

1. **`com.vnt` 包名不可改**：JNI 符号硬绑定包名/类名；想改就得同步改 Rust `#[no_mangle]`。
2. **VPN 排他**：启动即接管系统 VPN 通道，会挤掉用户其它 VPN；须在 UI 与说明中明确告知。
3. **路由别加 `0.0.0.0/0`**：否则 Bridge 自身全部长连接（主 WS / 微信 / 销售）都被卷进隧道，出现"组网一开什么都连不上"的假故障。
4. **`nextEvent()` 是阻塞调用**：必须在独立线程（官方用 daemon 线程）；停止时用「取消标志 + generation 校验」双保险，否则线程卡死导致服务无法退出。
5. **`TabOrderPrefs` 硬编码 4**：新增第 5 页要把 `isValid`/`DEFAULT`/两个列表全改，并做旧值迁移（读到的 `0,1,2,3` 追加 `4` 后写回），否则老用户自定义排序被静默重置。
6. **前台服务 Android 14+ 特殊类型**：`specialUse` 权限 + property 声明 + `BIND_VPN_SERVICE` + intent-filter，四件套缺一必崩（对照 `crash-ledger.md` §5）。
7. **native 崩溃不可被 `CrashReporter` 捕获**：Rust panic / tombstone 走 `data_app_native_crash`，需在崩溃台账补一层"native 观测"说明与排查命令。
8. **构建产物维护**：升级 VNT 版本要重编三 ABI 并校验符号与 16KB 对齐；建议把版本与编译命令写进 `tools/vnt/` 脚本并在文档登记。
9. **APK 体积与 ABI 选择**：建议先只出 `arm64-v8a`（覆盖绝大多数真机），必要时再加 `armeabi-v7a`。
10. **R8/Proguard**：release 已开 minify+shrink，需 `-keep class com.vnt.** { *; }`，避免绑定类被裁掉。
11. **证书模式**：自签服务端默认 `skip` 不安全，建议 `finger:<sha256>` 绑定；不设组网密码则为明文转发。
12. **网段冲突**：虚拟网段与用户真实 LAN 冲突时用 `subnet_mapping` 做等掩码映射。
13. **耗电与保活叠加**：组网常驻会与现有保活（WakeLock/SilentPlayer/心跳）叠加，先在 WiFi 与移动数据下各做一轮 `dumpsys batterystats` 对照，再决定是否默认开启后台保持。
14. **心跳台账登记**：把「VNT 组网连接（Rust 内部保活，周期不透明）」补进 `docs/dev/heartbeat-inventory.md` §1，并注明"周期不可从 Java 侧调"。
15. **合规与授权**：仅用于自有/获授权设备的内网互联；跨组织内网访问须取得授权，UI 与文档加一句用途声明。
16. **★ `getClientList()` 会发网络 RPC 且最多阻塞 3 秒**（`lib.rs:731` + `rpc.rs:243`）：**必须在后台线程调用**，
    主线程调会直接卡 UI/ANR；同时它不是内存读取，别当成可以随便高频调的 getter。
17. **★ 没有"设备上下线"事件回调**：`RuntimeEvent` 只有 `InstanceStopped` / `Changed(config)` 两个变体。
    服务端确实会推 `PushClientIps`（`inbound.rs:210`），但推不到 Java 层 ⇒ UI 只能轮询 `getClientList()`。
    想做掉线通知就得自己在 `VntMeshState` 里做前后快照差分 + 防抖（P2）。
18. **★ 速率字段底层没有**：`VntApi.Traffic` 只有累计 `txBytes/rxBytes`，`↑/↓` 速率必须自己按采样间隔差分算
    （照 `VntVpnService.java:525 withTrafficSpeeds()`）；页面隐藏再回来时**必须重置采样基准**
    （`resetTrafficSamples()`），否则会算出一个虚假的巨峰速率。
19. **中继行的 RTT 是估算值**（`api/mod.rs:57`：`server_rtt × 2`），不是真实往返，UI 别和 P2P 行同等展示。

---

## 八、工作量拆解与分期

| 阶段 | 内容 | 人日 |
|---|---|---|
| P0 ✅ | ~~Rust 工具链 + NDK 自编~~ → **改为下载官方 `vnt-jni-arm64-v8a-v2.0.11.so` + SHA256/ELF/符号校验 + 入库**（本地零安装，已完成） | 0.3 |
| P0 | 拷入 `com/vnt/*`，实现 `VntVpnService` + 启停编排 + 前台通知 + 变更循环线程 | 2 |
| P1 | `VntPages.kt` Compose 页面（状态卡/设备列表/服务端/路由/主按钮）+ 配置弹窗与 `VntMeshPrefs` | 2~3 |
| P1 | 主页 Tab 接入（TabOrderPrefs 扩 5 + 迁移 + 设置页排序自动跟随） | 0.5 |
| P1 | `AppExit.kill` 流程接入（先 stopService VNT，再 finishAffinity → kill） | 0.3 |
| P1 | 刷新节奏实现：`DisposableEffect` 进页面立即刷一次 + 5 秒周期 + 离开取消（后台单线程 + generation 校验） | 0.3 |
| P2 | 命令 `vnt_start/vnt_stop/vnt_status` 三条 + 文档（commands/system.md、README 索引） | 1 |
| P2 | 台账/心跳登记/INDEX 登记/原型核对 | 0.5 |
| — | ~~子网转发 / 端口映射~~（本期不做，只留扩展位：TODO 注释 + Prefs 空字段 + 置灰入口 + `subnetRoutes` 参数位） | 0.2 |

**合计约 5.5~6.5 人日**（P0 已完成的 0.3 人日已从剩余工作量中扣除；不含真机联调与省电实测）。

---

## 九、决策记录（2026-11-01 用户拍板，实现以此为准）

| # | 议题 | 结论 | 对设计的影响 |
|---|---|---|---|
| 1 | 刷新机制 | **只在「App 前台 + 停在组网页」时刷；进入页面立即刷一次，之后每 5 秒；离开立即停** | §2.5；用 `DisposableEffect` 置位，不抄 VntApp 的 Activity 生命周期 |
| 2 | 生命周期 | **只要用户不点「停止组网」就一直保持**（服务常驻，切 Tab/退后台不断） | §5.2；页面只订阅，照 `AudioRelayPage` |
| 3 | 包名 | 澄清：**不改 Bridge 包名**，另建 `com/vnt/` 源目录放官方绑定 | §5.1；`.so` **直接用官方预编译产物**（本地 `references/` 无现成 so，但官方 Release 有，版本 v2.0.11 一致） |
| 4 | 服务端 | **服务端地址可输入**，默认填公共服 `vnt2.rustvnt.com:29872`（自建 VNTS 已具备，用户手填） | §5.3；Prefs 存字符串，不再写死 |
| 5 | ABI | **只做 `arm64-v8a`** | §5.1；`abiFilters` 显式限定 |
| 6 | 与退出流程的关系 | **「退出应用」按钮 / 「切后台即结束」是最高优先级**，触发即整体结束，组网一并终止 | §5.2；`AppExit.kill` 里先 `stopService(VntVpnService)` |
| 7 | 配置套数 | **单套配置** | §5.3；不做多配置切换 UI 与 `VntConfigStore` |
| 8 | 子网转发 / 端口映射 | **本期不做，但保留扩展位**（接口已调研清楚，代码里留 TODO 注释，配置弹窗保留入口但置灰） | §5.3 + **§9.3 预留接口清单** |
| 9 | 命令体系 | **`vnt_start` / `vnt_stop` / `vnt_status` 三条都做**（`vnt_start` 回执注明"需 App 在前台或已授权"） | §5.4 + §9.2 |

### 9.1 关于第 8 项：子网转发 / 端口映射的代价评估（复杂度 vs 耗电）

**结论先行：几乎不增加耗电，主要成本是复杂度，且复杂度集中在一个很麻烦的点上——VPN 路由变更会触发重建。**

| 维度 | 评估 |
|---|---|
| **耗电** | **基本为 0 增量**。子网转发是**纯数据面的包转发**：命中路由表就把包塞进隧道，不命中就不管，**没有任何新增定时器/轮询**。端口映射侧只有一个 60 秒的 NAT 表 GC（`port_mapping/udp_port_mapping.rs:166`），量级和现有心跳比可以忽略。真正耗电的是隧道本身的保活（QUIC 通道 `keep_alive_interval=5s`、P2P 动态邻居 60s 刷新），**那部分开不开子网转发都一样**。 |
| **代码复杂度** | **中高**。需要：①`input`（允许对端访问我的 LAN）/ `output`（我要访问对端的 LAN）/ `subnet_mapping`（网段冲突时做等掩码映射）三组配置及校验；②`auto_sync_subnet` 开启后要处理 `MsgType::SubnetSyncRes` 并维护子网快照；③**Android 侧最麻烦**：对端 LAN 网段必须 `addRoute` 进 `VpnService.Builder`，而对端子网是**运行期才知道的** ⇒ 一旦变化就会通过 `nextEvent()` 吐出 `needsVpnRebuild=true` 的快照，**必须重建 VPN 接口**（重新 `establish()` 拿新 fd），期间隧道会短暂中断；④网段冲突排查（对端 LAN 是 `192.168.1.0/24`、本机也是，就必须配 `subnet_mapping`）。 |
| **稳定性风险** | VPN 重建是本项目最容易出事故的动作之一（fd 切换、`applyRuntimeChange(fd)` 时序、前台通知不能跟着重建）。为了一个"访问对端局域网"的需求引入它，性价比不高。 |
| **建议** | **本期不做，但保留扩展位**（见 §9.3）。等①手机直连 NAS/电脑这类点对点需求跑顺、②确实出现"要访问对端 LAN 内其它设备"的硬需求时，再单独立项——届时**只加 `output`（单向：手机访问对端 LAN）**，不上 `input`，可砍掉一半复杂度和大部分网段冲突问题。 |

### 9.2 关于第 9 项：命令三步法指的是什么

是的，**就是在管理后台能下发这三条命令**（`vnt_start` / `vnt_stop` / `vnt_status`），但"三步法"指的是它的**实现路径**，不只是加个按钮：

1. **服务端**：`server/app/core/command_catalog.py` 里加 `_cmd("vnt_start", "开始组网", "system", "🕸️", …)`（含参数定义，如服务端地址/网络编号/密码）。
2. **客户端**：`Command.kt` 加常量 + `CommandModule` 里加执行器（调 `VntMeshController.start()` / `stop()` / 读 `VntMeshState`）。
3. **文档**：`docs/api/commands/system.md` 加章节 + 该文件顶部/README 索引补一行。

**代价与注意**：
- `vnt_start` 若从后台发起 `startForegroundService`，Android 12+ **后台启动前台服务会被限制**（`ForegroundServiceStartNotAllowedException`），且**VPN 授权弹窗必须有 Activity 才能弹** ⇒ **命令实际只能在 App 在前台/已授权时可靠生效**。
- 严格按崩溃台账 §5：`startForegroundService` 每调一次就欠一次前台义务，必须 try/catch + 失败 `stopSelf`。
- 因此建议：`vnt_stop` / `vnt_status` **一定要做**（运维价值高、无限制），`vnt_start` 做但要**在回执里如实说明"需 App 在前台或已授权"**。

**已拍板：三条都做。** 三条命令的要点：

| 命令 | 类别 | 参数 | 回执 | 备注 |
|---|---|---|---|---|
| `vnt_start` | `system` | 可选覆盖 `server` / `network_code` / `password` | 成功：虚拟 IP + 状态；失败：原因 | **回执须注明"需 App 在前台或已授权 VPN"**；后台调用大概率失败，要如实返回而不是假装成功 |
| `vnt_stop` | `system` | 无 | 停止前状态 + 已停止 | 无平台限制，最可靠 |
| `vnt_status` | `system` | 无 | status / 虚拟 IP / NAT / 服务端 RTT / 在线设备数 | 只需读 `VntMeshState`，**不触发 RPC**（避免命令通道被打爆） |

> ⚠️ `vnt_status` 刻意**不调 `getClientList()`**（那是 RPC + 最多阻塞 3 秒），只回本地已缓存的快照。
> 需要详细设备列表时让运维去管理后台看，或另加一个 `vnt_clients` 并显式标注"有网络开销"。

### 9.3 预留接口清单（子网转发 / 端口映射，本期只留位不实现）

**JNI/Java 侧接口已全部具备**（`VntConfig.Builder`，实读 `VntConfig.java`），将来启用**不需要重编 `.so`**，纯 Kotlin 侧加代码：

| 能力 | Builder 方法 | 参数格式（见 `client-usage.md:130-135`） | 将来启用还需做什么 |
|---|---|---|---|
| 入站子网（对端访问我的 LAN） | `addInputRoute(String)` | `"源网段CIDR,目标虚拟IP"` | ①UI 加输入项 ②Android 侧**无需** addRoute（是别人访问我） |
| 出站子网（我访问对端 LAN） | `addOutputRoute(String)` | `"真实CIDR"` | ①UI 加输入项 ②**必须把该网段 `addRoute` 进 VpnService.Builder** |
| 网段冲突映射 | `addSubnetMapping(String)` | `"映射CIDR,真实CIDR"`，真实范围必须被 `output` 覆盖 | UI 加输入项 |
| 自动同步对端子网 | `setAutoSyncSubnet(boolean)` | — | ⚠️ 开启后服务端推 `SubnetSyncRes`，**子网变化会触发 `needsVpnRebuild` → 必须重建 VPN 接口**（`applyRuntimeChange(fd)`），这是最大风险点 |
| 端口映射开关 | `setAllowMapping(boolean)` | — | 仅无网卡模式(`no`)下才有意义，Android 用 tun 模式通常不需要 |
| 端口映射规则 | `addPortMapping(String)` | `"tcp://0.0.0.0:81-10.26.0.2-192.168.1.10:80"` = 本地监听 - 目标虚拟节点 - 最终目标 | UI 加规则列表 |

**代码里怎么留位（本期就做，成本 ≈0）**：
1. `VntConfigBuilder.kt` 里加一段注释块，列出上表方法名与参数格式，标注 `// TODO(vnt-subnet): 需求明确后启用，注意 needsVpnRebuild 会导致 VPN 重建`。
2. `VntMeshPrefs.kt` 预留字段（`inputRoutes` / `outputRoutes` / `subnetMappings` / `autoSyncSubnet` / `portMappings`），**默认值全部为空/关闭**，读取处注释说明。
3. 配置弹窗保留「高级（子网 / 端口映射）」入口但**置灰并标注「后续版本开放」**，不隐藏——避免将来用户以为没这功能。
4. **`VntVpnService` 的 `establishVpn()` 里留一个 `subnetRoutes` 参数位**（当前恒为空列表），将来只需往里塞网段即可，不用改结构。

---

## 十、源码索引（供后续实现查阅）

| 用途 | 路径 |
|---|---|
| JNI 绑定源码 | `references/vnt/vnt-main/vnt-jni/java_example/com/vnt/*.java` |
| Android 最小示例 | `references/vnt/vnt-main/vnt-jni/java_example/AndroidVpnExample.java` |
| 生产级服务实现 | `references/vnt/VntApp-master/.../VntVpnService.java`（665 行） |
| 配置存储/状态 | `VntApp-master/.../VntConfigStore.java`、`VntState.java`、`VpnRouteSet.java` |
| 清单声明样板 | `VntApp-master/app/src/main/AndroidManifest.xml` |
| 官方预编译 so（★ 本项目采用） | `vnt-dev/vnt` Release tag `v2.0.11` → `vnt-jni-arm64-v8a-v2.0.11.so`；本地存档 `tools/vnt/artifacts/`，入库 `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so` |
| 产物校验脚本 | `tools/vnt/verify_so.py`（纯 Python 解析 ELF，无需 NDK） |
| 交叉编译脚本（兜底，本次不用） | `VntApp-master/scripts/build-android-jni.sh` |
| 参数权威说明 | `vnt-main/.codex/skills/vnt-operations/references/client-usage.md`、`server-deployment.md` |
| 服务端（自建用） | `references/vnt/vnts-master/`（README.md + `frontend/`） |

---

## 十一、开工前核对清单（实现时逐条打勾 —— **v0.2.30 已回填**）

### A. 决策是否已全部落到设计里

- [x] ① 刷新：**仅「前台 + 组网页」刷；进页面立即一次 + 5 秒周期；离开即取消**（`VntMeshController.startRefresh/stopRefresh` + 组网页 `DisposableEffect`/`LifecycleEventObserver`）
- [x] ② 生命周期：**不点停止就一直保持**；页面只订阅不启停（`VntVpnService` 常驻，`START_NOT_STICKY`）
- [x] ③ 包名：**不改 `my.bridge.com`**，另建 `app/src/main/java/com/vnt/` 放官方绑定（11 个类）
- [x] ④ 服务端：**可输入**，默认 `vnt2.rustvnt.com:29872`（`VntMeshPrefs` 存字符串 + 配置弹窗）
- [x] ⑤ ABI：**只 `arm64-v8a`**，`abiFilters` 显式限定；APK 实测仅 `lib/arm64-v8a/`
- [x] ⑥ 退出：最高优先级，`AppExit.kill` 里先 `stopService(VntVpnService)`
- [x] ⑦ 配置：**单套**，不做多配置切换
- [x] ⑧ 子网转发 / 端口映射：**不做但留位**——`VntConfigBuilder` TODO 注释 + `VntMeshConfig` 空字段 + 配置弹窗置灰说明 + `establishVpn()` 的 `subnetRoutes` 参数位
- [x] ⑨ 命令：三条全做，`vnt_status` 不触发 RPC（只读本地快照）

### B. 项目约定（不可跳过）

- [x] **改前留档**：`docs/dev/vnt-integration-change-ledger.md`（18 个既有文件改前原文 + 回滚配方 + 新增文件删除清单）
- [x] **崩溃台账 §5**：逐条过，§3.1 登记新调用点、§6 记预防性接入
- [x] **心跳台账 §1.2**：登记 VNT 黑盒常驻连接 + UI 侧 5 秒轮询的说明
- [x] **命令三步法**：catalog `_cmd()` → `Command.kt` + `CommandModule` → `system.md` + README 索引
- [x] **文档登记**：`docs/INDEX.md`（报告状态 + 台账 + 崩溃台账）、`docs/CHANGELOG.md` v0.2.30 里程碑
- [x] **本地编译校验**：`compileDebugKotlin` PASS；**额外跑了 `assembleDebug` 全量打包** PASS（见下）

### C. 技术红线（错了就是线上事故）

- [x] **绝不 `addRoute("0.0.0.0/0", 0)`**——只加虚拟网段 + 服务端下发的出口网段
- [x] `getClientList()` **不在主线程调**：后台单线程 `ScheduledExecutorService` + generation 校验
- [x] 页面隐藏→显示时 `resetTrafficSamples()`（`startRefresh`/`stopRefresh` 内部）
- [x] `nextEvent()` 在 **daemon 线程**跑，用「取消标志 + generation」退出
- [x] `startForegroundService` try/catch；`onStartCommand` 每次幂等补挂前台；`startForeground` try/catch + 失败 `stopSelf`
- [x] Android 14+ 四件套齐全（合并后 Manifest 已核对）+ `BIND_VPN_SERVICE` + `android.net.VpnService` intent-filter
- [x] release 加 `-keep class com.vnt.** { *; }`
- [x] `.so` 入库前校验（`verify_so.py`：SHA256 / ELF64-AArch64 / 4 段 16KB 对齐 / 23↔23 符号零缺失，全 PASS）
- [ ] 升级 VNT 版本时重跑 `verify_so.py`，并同步更新 §2.4 的版本与 SHA256 记录（**未来动作**）

> ⚠️ **实现期新增的一个适配（不在原清单里）**：官方 `java_example/com/vnt/VntConfig.java`
> 是按标准 JVM org.json 写的，Android 框架自带的 `JSONObject.put()` 声明**受检异常
> `JSONException`**，直接拷入会编译失败（30 处「未报告的异常错误」）。
> 已在 `VntConfig.java` 加静态包装方法 `put(JSONObject,String,Object)` 统一转成非受检异常，
> **不改包名/类名/native 声明**，JNI 导出符号与 config JSON 内容均不受影响。
> 这是 `compileDebugKotlin` 发现不了、只有 `compileDebugJavaWithJavac`/`assembleDebug` 才暴露的问题。

### D. 验收（真机）—— **本期未做，需 VNTS 实体与多设备环境**

- [ ] 首次连接：系统 VPN 授权 → 拿到虚拟 IP → 通知显示
- [ ] 与远端设备互相 ping 通；设备列表 P2P / 中继标记正确
- [ ] 切到别的 Tab / 退后台：数字停止刷新且不再发请求（`adb shell dumpsys netstats` 或日志计数佐证）
- [ ] 点「退出应用」：进程结束且**不被复活**（服务已取 `START_NOT_STICKY`）
- [ ] 三条命令在管理后台可下发，回执正确
- [ ] `dumpsys batterystats` 一轮开/关对照（重点看常驻基线叠加）

### 11.1 本地验证结果（2026-11-01）

| 项 | 命令 | 结果 |
|---|---|---|
| Kotlin 编译 | `JAVA_HOME=… ./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL**（3m25s） |
| Java 绑定编译 | `compileDebugJavaWithJavac`（经上述适配后） | **PASS** |
| 全量打包 | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**（3m48s），APK 34,029,588 B |
| so 入库 | APK 内 `lib/arm64-v8a/libvnt_jni.so` | 5,042,792 B，sha256 `c33535b1…`，与官方一致 |
| ABI 限定 | APK `lib/` 目录 | 仅 `arm64-v8a`（`abiFilters` 生效） |
| Manifest 合并 | 合并后 `AndroidManifest.xml` | `VntVpnService` / `specialUse` 四件套 / `BIND_VPN_SERVICE` 全部在位 |
| 服务端目录 | `py_compile` + 导入 `COMMANDS` | 38 条命令、白名单 38、VNT 三条参数正确 |
| so 复验 | `tools/vnt/verify_so.py` | SHA256 / 对齐 / 23↔23 声明-符号，全 PASS |

---

## 十二、v0.2.31 修订（2026-11-01 晚 · 真机反馈驱动）

> 用户真机跑 v0.2.30 后提出 4 项，并补了一张 **VNT 1.2.13 原生 APK** 的配置截图
> （「协议 UDP」单选 + 服务器地址 `039039.xyz:31007`），明确拍板：
> **「既然集成最新的 so，就按照最新规则来实现」**。
> 本节是 v0.2.31 的权威记录；更早的 §2.4 / §5.1 / §九 中「只 arm64」「裸地址默认 UDP」
> 的表述**以本节为准**（原文保留不删，避免破坏改前留档台账）。

### 12.1 诊断：「开始组网报错」的完整证据链

| 证据 | 结论 |
|---|---|
| MySQL `device_logs` 21:29:25~29 共 **11 条** `E/组网`：`invalid server address 'udp://039039.xyz:31007': Unknown protocol in server address` | 用户按 v0.2.30 UI 提示（错误地写着「裸 host:port 默认 UDP」）填了 `udp://` 前缀 |
| `vnt-core/.../transport/config.rs:80-101` `parse_server()`（**v2.0.2 ~ v2.0.11 全量核对过**） | 合法前缀仅 `quic://` / `tcp://`(TlsTcp) / `wss://` / `dynamic://`；**裸 `host:port` 默认 TlsTcp**；任何其它 `://` 直接 `Err(Unknown protocol...)`。`ProtocolType` 枚举无 `Udp` |
| `vnt-jni/src/lib.rs:1400-1407` | JNI 层 `server` 数组直接 `s.parse::<ProtocolAddress>()`，用的正是上面那个 `parse_server` |
| `vnts-master/README.md:9` | 2.x 服务端支持 `quic` / `tcp(tls)` / `wss`，自动签发自签证书；**没有纯 UDP 注册通道** |
| 本机实测 `039039.xyz:31007` | DNS→`122.6.190.161`，TCP 可连，但 **TLS 握手 0.01s 即 `UNEXPECTED_EOF`** ⇒ 该端口不是 vnts 2.x 的 TLS TCP 监听 |
| 官方 VntApp v2.0.10 / v2.0.9 APK 内嵌 so | 与本项目 v2.0.11 so sha 不同，但各 tag 的 `parse_server` 一致（都不认 `udp://`）；用户能连的 **1.2.13 是旧版核心**，`udp://` 属于 1.2.x 协议 |

**结论**：不是打包漏东西，也不是 so 版本错，而是**协议代际不匹配**——
1.2.13 客户端 + 老 VNTS（UDP 31007）能连；v2.0.11 核心已改为 TLS TCP/WSS/QUIC，
同一个 `039039.xyz:31007`（UDP/非 TLS）连不上是必然。用户已拍板按最新规则实现，
**不降级 so、不加 `udp://` 兼容层**。

### 12.2 用户 4 项诉求的落地

| # | 诉求 | 落地 |
|---|---|---|
| 1 | 对齐官方 universal | `abiFilters` 改为 `arm64-v8a / armeabi-v7a / x86_64` 三套；下载官方 v2.0.11 另两套 so 并入库；**显式排除 x86（32 位）**（官方无 so，避免「装上了但 VNT 加载不到」的假成功）。见 §12.3 |
| 2 | 设置→关于页不能上下滑动 | `AboutPage()` 根 `Column` 补 `.verticalScroll(rememberScrollState())` + 两个 import |
| 3 | 开始组网记忆 / 停止清除 / 冷启动自动开始 | `VntMeshPrefs.autoStart` 布尔持久化；`MainActivity.onStart` 一次性调 `VntAutoStart.tryStart()`；`vnt_start`/`vnt_stop` 命令同步写记忆。见 §12.4 |
| 4 | 开始组网后报错 | 见 §12.1 诊断 + §12.5 的 5 项修复 |

### 12.3 三套 ABI（对齐官方 universal）

| ABI | 官方产物 | 大小 | SHA256 | ELF |
|---|---|---|---|---|
| arm64-v8a | `vnt-jni-arm64-v8a-v2.0.11.so` | 5,042,792 | `c33535b1…` | ELF64 / AArch64(183) |
| armeabi-v7a | `vnt-jni-armeabi-v7a-v2.0.11.so` | 3,500,728 | `454908df…` | ELF32 / ARM(40) |
| x86_64 | `vnt-jni-x86_64-v2.0.11.so` | 5,756,440 | `0ee3865b…` | ELF64 / x86-64(62) |

三套均经 `tools/vnt/verify_so.py`（已升级支持 ELF32/ELF64 多 ABI）校验：
SHA256 与官方 `.sha256` 一致、PT_LOAD 页对齐（64 位 16KB / 32 位 4KB）、
**23 个 `Java_com_vnt_*` 导出符号 ↔ 23 个 Java native 声明零缺失**。
官方 `VntApp` 每个 Release 出 4 个 APK（3 个分包 + `universal.apk`）；
我们等价于 universal，但**只收官方有 so 的 3 套**。

### 12.4 自动组网记忆语义（用户拍板）

- 「开始组网」→ 记忆 `true`；「停止组网」→ 记忆 `false`；下次冷启动按记忆自动开始 / 需手动点。
- 只有这两个按钮（以及 `vnt_start`/`vnt_stop` 命令）改变记忆；**退出应用 / 切后台结束进程不动记忆**，
  进程重启按记忆恢复（用户未要求退出时清除，按字面实现，如需改可调整）。
- `restart()`（配置保存重连）**刻意不写记忆**，避免「未连接时保存配置」误清标记。
- 三道守卫：记忆未开 / 已 RUNNING·STARTING / `VpnService.prepare()` 非 null（授权失效）→ 跳过。
- 放在 `MainActivity.onStart`（Activity 已可见，避开 Android 12+ 后台 FGS 限制），
  每实例只试一次，**失败不重试**（配置错误落到 ERROR 状态显示原因，不刷屏、不耗电）。

### 12.5 v2.0.11 地址规则与错误可见性（5 项修复）

1. **文案纠错**：`VntPages.kt` 配置弹窗提示与 `VntMeshPrefs.kt` 字段注释，
   从「裸 host:port 默认 UDP」改为「裸 host:port 默认 **tcp://（TLS TCP）**；
   可显式 `tcp:// / wss:// / quic:// / dynamic://`；**不支持 `udp://`（1.2.x 旧协议）**」。
2. **保存前校验**：`VntServerAddress.error()` 白名单校验，非法前缀在配置弹窗就 Toast 拦下，
   不写进 Prefs，从源头杜绝「反复点开始 → 11 条 native 异常」。
3. **native 前防线**：`VntConfigBuilder.build()` 再校验一次并抛可读 `IllegalArgumentException`，
   由 `VntVpnService.runConnect` 的 catch 转成页面文案。
4. **ERROR 状态不再被吞**：`runConnect` 的 `finally` 原先无条件 `onStopped()` 覆盖 `onError()`，
   用户永远看不到失败原因；改为仅成功路径才 `onStopped()`，失败保留 ERROR 红卡 + 原因。
5. **状态卡如实显示**：RUNNING 只代表本地 TUN 已建立并拿到虚拟 IP；
   若刷新后 `serverList` 无任何 `connected=true`，改显橙色「未连上服务端」警示卡，
   不再显示绿色「已连接 · 虚拟IP」误导（v0.2.30 真机截图正是这个矛盾状态）。

### 12.6 给用户自助排查结论

用 v2.0.11 连自建服，二选一：

- **升级 VNTS 到 2.x** 并开启 `tcp(tls)` / `wss` / `quic` 监听，把配置改成
  `039039.xyz:<新端口>`（裸地址默认 `tcp://`，或显式 `tcp://...`）；
- 或先用公共服 `vnt2.rustvnt.com:29872` 验证功能，确认链路通再迁自建。

> ⚠️ 旧版 1.2.13 的「协议 UDP」单选在 2.0.x 核心里**没有对应项**；
> 不要再把 `udp://` 前缀填进服务端地址。

---

## 十三、v0.2.32：整体替换为 VNT 1.x（历史记录，**已被 §十四 的 2.x 升级取代**）

> **★ 本节已是历史记录。** 当前实现（v0.2.131 起）已升级回 **2.x（vnt-dev v2.0.12）**，
> 权威说明见 **§十四**。本节描述 v0.2.32~v0.2.130 的 1.x 实现，保留原文仅为「改前留档」与决策溯源。
> 前十二节（尤其 §2.4、§5.1、§九、§十二）描述的 2.0.x 集成方案在 v0.2.32 被替换为 1.x、
> 又在 v0.2.131 恢复为 2.x。改前原文、文件指纹、删除清单与回滚配方见
> `docs/dev/vnt-integration-change-ledger.md` §六（1.x）与 §八（2.x）。

### 13.1 决策链（不可省略的背景）

1. v0.2.31 真机点「开始组网」报错，日志为 `invalid server address 'udp://039039.xyz:31007': Unknown protocol`。
2. 核实 `vnt-core/.../config.rs::parse_server()`（v2.0.2~v2.0.11 逐 tag 一致）：合法前缀只有
   `tcp:// / wss:// / quic:// / dynamic://`，裸 `host:port` 默认 `TlsTcp`，`ProtocolType` 无 `Udp`。
3. `vnt-main/README.md:167`：**「vnt2.0 整体重构了一遍，和 1.0 不兼容」**。本机实测
   `039039.xyz:31007` TLS 握手 0.01s 即 `UNEXPECTED_EOF` —— 该端口后面是 1.x UDP 服务。
4. 用户确认：真机原生 APK 是 **1.2.13**，**自建 VNTS 与全部存量客户端都是 1.x**，且
   **必须互通**；服务端**不能升级**（会打断存量客户端）。
5. ⇒ 唯一出路：**Bridge 客户端核心也换成 1.x**。A1 方案（纯替换，非双栈）由用户拍板
   「a1 开工」，版本号 `v0.2.32`。

### 13.2 为什么不是「只换一个 .so」

官方 1.x 的 Android 客户端与 2.x 是**两套完全不同的架构**（已下载官方 APK 只读解析）：

| | 早期 1.x（1.1.0~1.2.8 / **v1.2.9**） | 后期 1.x（1.2.10.1+，含 1.2.13） | 2.x（v0.2.30/31 曾用） |
|---|---|---|---|
| 架构 | 原生 Android + **Java JNI** | **Flutter** + flutter_rust_bridge | Java JNI |
| native 库 | `libvnt_jni.so`（`app/libs/`，2 套 ABI） | `librust_lib_vnt_app.so`（4 套 ABI） | `libvnt_jni.so`（3 套 ABI） |
| Java 绑定 | `top.wherewego.vnt.jni.*`（14 类） | 无（Dart FFI） | `com.vnt.*`（11 类） |
| JNI 符号 | 6 个 `Java_top_wherewego_vnt_jni_Vnt_*` | **`Java_*` 符号 = 0**（全是 `frb_*/wire_*`） | 23 个 `Java_com_vnt_*` |

实测 1.2.13 的 so 里 `Java_*` 符号为 0 ⇒ 直接拿来给 Bridge 用必然 `UnsatisfiedLinkError`。
**唯一能复用的是 Java JNI 版**，定位到官方**最后一个** JNI 版 `v1.2.9`（1.2.10.1 起转 Flutter），
比 1.2.8 更新、更贴近用户 1.x 服务端。

### 13.3 版本 / ABI 核实结论

- `vnt-dev/VntApp` 15 个 tag + 3 个维护分支逐个枚举 `.so` 路径：**所有 Java JNI 版都只有
  `arm64-v8a` / `x86_64`，没有任何版本提供 `armeabi-v7a` 的 JNI so**。
- 有 v7a 的只有 Flutter 版（接口不对）与 2.x（协议不对），**没有交集**。
- 结论：**v0.2.32 只支持 2 套 ABI**，纯 32 位 ARM 设备装不上 1.x 核心（用户已登记机型推测均为 arm64）。
- 官方 1.x Rust 仓库（含最后一版 `v1.2.17`）**始终没有 `vnt-jni` 模块**，也不发布 1.x JNI 预编译产物；
  自行补 v7a 需 NDK 交叉编译 + backport JNI 层，违反「不擅自本地安装程序」硬约束，不做。

### 13.4 2.x → 1.x 全量差异对照（重写依据）

| 维度 | 2.0.11（旧） | 1.2.9（当前） |
|---|---|---|
| 启动时序 | Java 先 `Builder.establish()` 拿 fd → `net.startTun(fd)` | `new Vnt(Config, CallBack)` → native 回调 **`generateTun(DeviceConfig)` 里由 Java 建 TUN 并 `return fd`** → `awaitTimeout` 循环 |
| 服务端地址 | `tcp:// / wss:// / quic:// / dynamic://`，裸地址默认 TLS TCP | **裸 `host:port`**，任何 `://` 前缀都非法 |
| UDP/TCP | 由协议前缀决定 | **布尔 `Config.tcp`**（`false`=UDP，对应 1.2.13 的「协议 UDP」） |
| 组网编号 | `networkCode` | **`token`**（Prefs 已做 `network_code`→`token` 升级迁移） |
| 加密 | ChaCha20-Poly1305 / `certMode` | `cipherModel`（aes_gcm / aes_cbc / aes_ecb）+ `serverEncrypt` + `finger` + `password` |
| 设备/状态 | `getClientList/getServerList/getNatInfo/getTrafficInfo/RuntimeEvent`，速率差分、RTT 估算、NAT 类型 | **只有 `Vnt.list() -> PeerRouteInfo[]`**（native 缓存表，本地内存读非 RPC），`Route.metric==1` 判 P2P；**无速率/NAT/真实 RTT** |
| 错误通道 | `nextEvent()` 事件循环 | `CallBack.error(ErrorInfo)`；构造期错误记 `startupError`，构造返回后立刻转 ERROR |
| native 释放 | `net.stop()` | `Vnt.stop()` 让 awaitTimeout 返回 → worker `finally` 里 `close()`（避免并发 drop 的 use-after-free） |
| JNI 符号 / ABI | 23 个 / 3 套（16KB 页对齐） | 6 个 / 2 套（**4KB 页对齐**） |

### 13.5 落地实现清单（v0.2.32）

| 层 | 文件 | 变更 |
|---|---|---|
| native | `jniLibs/{arm64-v8a,x86_64}/libvnt_jni.so` | 换官方 v1.2.9；删除 `armeabi-v7a` 与 2.x 三套；`tools/vnt/artifacts/v1.2.9/` 存档 + SHA256SUMS |
| 绑定 | `java/top/wherewego/vnt/jni/*`（14 类） | 原样拷入；`Vnt.java` 补 `static { System.loadLibrary("vnt_jni"); }`（官方绑定未带） |
| Gradle | `app/build.gradle.kts` | `versionName=0.2.32`；`abiFilters` 收为 2 套 |
| 混淆 | `app/proguard-rules.pro` | keep 包名 `com.vnt.**` → `top.wherewego.vnt.jni.**` |
| 核心 | `my/bridge/com/vnt/` 6 文件 | 全量重写：`VntMeshPrefs`（1.x 字段 + 2.x 迁移 + `VntServerAddress` 反转为裸地址校验）、`VntConfigBuilder`（装配 1.x `Config`）、`VntMeshState`（`VntPeer`）、`VntMeshController`（`PeerListProvider` + `list()` 解析）、`VntVpnService`（generateTun 时序）、`VntAutoStart`（沿用） |
| UI | `ui/VntPages.kt` | 配置弹窗改 1.x 字段（Token / 裸地址 / UDP-TCP 开关 / cipherModel / MTU / STUN），移除 2.x 速率·RTT·NAT 卡片 |
| 命令 | `CommandModule.kt` / `command_catalog.py` / `system.md` | `token`（兼容 `network_code`）、裸 `host:port`、`tcp` 布尔参数；回执改 1.x 文案 |
| 工具 | `tools/vnt/verify_so.py` | 适配 1.x：2 套 ABI、6 个 JNI 符号、4KB 页对齐、6↔6 声明/导出对应；两套 so 全 PASS |

### 13.6 已知限制与风险（如实登记）

1. **ABI 只有 2 套**（无 armeabi-v7a），来自官方产物固有限制。
2. **4KB 页对齐**：官方 1.x so 的 PT_LOAD `p_align=0x1000`，**Android 15+ 16KB 页设备可能加载失败**；
   2.x 产物是 16KB。这是 1.x 官方二进制的固有限制，无法在不重编的前提下消除。
3. **协议互通只能真机实测**：1.2.9 与 1.2.13 同属 1.x，理论上可连同一服务端，但
   `token / password / cipherModel / serverEncrypt / finger` 必须**逐项一致**，否则会
   「注册成功但互 ping 不通」。本地没有用户 1.x 服务端实体，配置 UI 已按官方
   `AddActivity` 默认值预填，用户照 1.2.13 里填的抄入即可。
4. **前台服务四件套与崩溃台账 §5 契约不变**：`VntVpnService` 类名/权限/Manifest 未改，
   `AppExit.kill` 的 `stopService` 仍生效；新增的 `generateTun` 回调在主流程内，不新增 FGS 义务点。

### 13.7 回滚与验证

- **回滚配方、改前原文/哈希、删除清单、触发回滚信号**：`docs/dev/vnt-integration-change-ledger.md` §6.5~6.7。
- **本地验证**：`compileDebugKotlin` / `assembleDebug`、APK 两套 ABI 与官方产物逐字节比对、
  Manifest `versionName` 与 specialUse 四件套、无残留 2.x 类/so —— 结果回填 §6.8。
- **仍待真机验证**：两套 ABI 安装与 native 加载、冷启动自动开始 / 停止后不自动开始、
  与存量 1.x 客户端互 ping、`finger`/加密组合一致性、开关省电对照。

### 13.8 v0.2.38：断线自动重连（真机反馈驱动）

**用户反馈**：切网（WiFi↔蜂窝）/ 断网恢复后 VNT 不自动重连，界面「组网失败 Disconnect」，
按钮恢复「开始组网」，手动点即成功；官方 VntApp 任意切网/断网都能恢复。

**根因（代码级，台账 §七有完整证据链）**：
1. native 1.x 断线后回调 `CallBack.error`，错误码 **2 = Disconnect** → 运行期 `runtimeError` 置位；
2. `runConnect` 的 awaitTimeout 循环一检测到错误就 throw → `onError()` → finally 释放 + `stopSelf()`
   → 服务死亡、ERROR 终态、无任何重试；
3. 全链路无补偿：`vnt/` 包无 NetworkCallback（同项目 AudioRelayService / WechatRuntime 都有），
   `VntAutoStart` 仅冷启动触发一次，START_NOT_STICKY 也不复活；
4. 历史决策盲区：官方 VntApp 靠 **START_STICKY + null intent 自动恢复**（§5.2 记录过）；
   v0.2.30 为「退出即整体结束」有意删掉 STICKY 时，只论证了切 Tab/退后台/锁屏不受影响，
   **漏了断线自恢复这条官方靠 STICKY 实现的路径**，且未在应用层补替代。

**修复（用户拍板「进程内监督重连」，不恢复 STICKY）**：
- `VntVpnService` worker 改为监督循环：`tryConnectOnce()`（原 runConnect 主体）+ 退避重试；
  可重试失败**不停服务、不 stopSelf** ⇒ 天然绕开 Android 12+ 后台 FGS 限制，不新增 FGS 义务点。
- 错误分类：`TokenError` / `InvalidIp` / `establish()` 返回 null（VPN 授权被抢）→ 致命，ERROR 等人工；
  其余（Disconnect / AddressExhausted / IpAlreadyExists / Unknown / 普通异常）→ 可重试。
- 退避 2/4/8/16/30/60s 封顶，连接成功过即重置；`registerDefaultNetworkCallback` onAvailable
  立即结束退避等待并重置退避计数。
- 新增 `VntStatus.RECONNECTING`：UI 橙色「断线，正在自动重连…」+ 第 N 次 / Ns 后重试，
  主按钮仍显示「停止组网」；`vnt_start` 幂等守卫同样拦截该态；admin `VNT_STATE_RULES`
  加 `reconnecting`（vnt_stop 允许）、`parseVntStatus` / `VntResultDetailDialog.stateTagOf` 同步登记。
- **接管竞态防护**：stop→快速 start（restart 流程）时旧 worker 仍可能卡在退避/构造里；
  以 generation 校验 + `running` 重入判定，旧 attempt 只释放自己的实例、
  不碰新 attempt 的共享字段、不 stopSelf、不发布 STOPPED（详见台账 §7.4）。

**回滚**：`docs/dev/vnt-integration-change-ledger.md` §7.3（`git checkout 6f12c26 -- <8 files>`）。

---

## 十四、v0.2.131：升级为 VNT 2.x（vnt-dev v2.0.12，2026-11-13 · 当前实现的权威记录）

> **本节是 v0.2.131 及之后当前代码的唯一权威说明。** 1.x 服务端（31007）已永久下线，
> 服务端开发者已将 VNTS 升级到 VNT 2.0（`quic://039039.xyz:31008` + `tcp://039039.xyz:31008`）。
> Bridge 客户端同步升级为 2.x。改前基线/指纹/改动清单/回滚配方见
> `docs/dev/vnt-integration-change-ledger.md` §八。

### 14.1 决策链

1. 1.x 服务端（`039039.xyz:31007`）永久下线，客户端 v0.2.130 及以前已无法组网。
2. 服务端开发者把 VNTS 升到 VNT 2.0，并给了一份《VNT1 升 VNT2 改造指南》（按用户口述估写，
   服务端/其它客户端信息正确，但**不了解本项目真实代码**，仅作借鉴）。
3. 用户拍板：**先拉官方 v2.0.12 再动手、现在开工**。
4. 关键洞察：本项目 v0.2.30/31 时代**本来就是 2.x 实现**（§十三 之前），v0.2.32 才因
   「存量客户端全 1.x 必须互通」回退到 1.x。2.x 代码完整保存在 git 历史 `0f7f5098`（tag v0.2.31），
   ⇒ 本次是「反向恢复 + 叠加 v0.2.38 断线重连」，非从零新写。

### 14.2 so 版本核实（v2.0.12）

- 官方 `vnt-dev/vnt` 最新 release = **v2.0.12**（`/releases/latest` 指针滞后返回 v2.0.11，
  但 `/releases/tags/v2.0.12` 确有其物）。
- 三套 so（arm64-v8a / armeabi-v7a / x86_64）下载 + 三重校验 PASS：
  - sha256 与官方 `.sha256` 逐字节一致（arm64 `09049316…` / v7a `4cc3b22e…` / x86_64 `48504af5…`）；
  - ELF machine 183/40/62 正确；PT_LOAD 全 `0x4000`（16KB 对齐，Android 15+ 要求）；
  - arm64 导出 23 个 `Java_com_vnt_*` 符号，与 v2.0.11 完全一致 ⇒ `com.vnt.*` 封装层无需改。

### 14.3 2.x 调用时序（与 1.x 相反）

`VntManager.init()` → `createNetwork(config)` → `getNetwork()`（拿 `NetworkResult` 网段）→
**Java 先建 `VpnService.Builder`**（含 IPv6 派生地址 `fd76:6e74::xxxx/96` + 路由 `fd76:6e74::/96`、
`ff00::/8`、虚拟网段，**绝不加 0.0.0.0/0**）**并 `detachFd()` 拿 int fd** → `startTun(fd)` →
`getApi()`（`VntApi`）→ `nextEvent()` 变更循环（`instance_stopped` / `changed`）。

对比 1.x：`new Vnt(Config, CallBack)` → native 回调 `generateTun` 让 Java 建 TUN 并 return fd，
时序相反，不可混用。

### 14.4 配置字段对照（1.x → 2.x）

| 概念 | 1.x（已删） | 2.x（v0.2.131） |
|---|---|---|
| 组网编号 | `token` | `networkCode`（命令参数 `network_code`，兼容旧 `token`） |
| 服务端 | 裸 `host:port`（31007） | `server` 数组（`quic://`+`tcp://` 双地址，31008） |
| 协议 | `tcp: Boolean` 开关 | 删除，地址前缀决定（`quic://`/`tcp://`/`wss://`） |
| 加密 | `cipherModel`/`serverEncrypt`/`finger` | `password`（ChaCha20）+ `certMode`（联调 `skip`） |
| 新增 | — | `compress`/`fec`/`rtx`/`noPunch` |

`VntServerAddress.error()` 校验反转：1.x 拒绝任何 `://` 前缀 → 2.x 只接受 `tcp:// / wss:// /
quic:// / dynamic://` 或裸地址（默认 TLS TCP），拒绝 `udp://`（1.2.x 旧协议）。

### 14.5 断线重连错误分类重映射（唯一「新写」点）

1.x 错误通道 `CallBack.error(ErrorInfo)` → 2.x `nextEvent()` 事件循环：

| 阶段 | 2.x 现象 | 分类 |
|---|---|---|
| `VntManager.init()` false | 初始化失败 | 致命 |
| `createNetwork()` null | 配置/网络不可达 | 致命 |
| `getNetwork()` 抛 VntException | 注册失败（network_code/地址错） | 致命 |
| `establishVpn()` null | VPN 授权被抢 | 致命 |
| `nextEvent()` 抛异常 / `instance_stopped` | 断线 | 可重试 |

（对照 1.x：`TokenError`/`InvalidIp` 枚举 → 2.x `createNetwork null`/`getNetwork 抛异常`。）

### 14.6 已知限制与风险

- **so v2.0.11 vs v2.0.12**：仓库里成套历史为 v2.0.11，本次用 v2.0.12（符号 23↔23 一致已核）。
- **IPv6**：指南说「只做 IPv4 也能通」，但已按官方做全 IPv6 注入，无额外成本。
- **老 APK（≤v0.2.130）**：1.x 服已下线，本就不可用，无需向后兼容。
- **真机互通验证待做**：本节为代码落地记录，验收清单见台账 §8.7。

### 14.7 回滚

`docs/dev/vnt-integration-change-ledger.md` §八.5（`git checkout c7ff577 -- <paths>`）。
