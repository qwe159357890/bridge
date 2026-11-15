# Bridge 集成 Tailscale 组网方案（调研稿）

> 状态：**已完全回退（未采用方案，本档仅存档）**。档 1 PoC 曾完成（AAR 编译通过、Kotlin 侧封装 + 组网页切换式卡片已落地），v0.2.156 接入后真机验证暴露「与 VNT 互斥 + 单向互通（tsnet userspace 出站需走本地 SOCKS5、TUN 模式占唯一 VPN 槽位且 UDP 转发不完整）」，遂在 v0.2.157 完全回退。**当前仓库无任何 Tailscale 代码/依赖**，回退脉络见 `CHANGELOG.md` 监控里程碑「脉络六」。
> 日期：2026-11-14
> 前置输入：`references/ssh-with-tailscale`（Matt-wzy/ssh-with-tailscale，GPL-3.0-or-later）源码精读 + 公开资料检索
> 关联文档：`docs/research/vnt-mesh-integration.md`（现有 VNT 组网权威）、`docs/dev/vnt-integration-change-ledger.md`

---

## 0. 一句话结论

Tailscale 完全可以像 VNT 一样被「内嵌进 Bridge 进程」，做法成熟、有官方与社区双路线，与现有 VNT 组网**并存互补、不冲突**。但它的定位和 VNT 重叠度高，是否值得引入取决于「是否需要官方生态（MagicDNS/ACL/Subnet Router/跨端 GUI）」这条核心决策线。**已拍板：控制平面用 Tailscale SaaS，技术路线走 A（tsnet + gomobile）**；下一步待用户确认「是否引入」及「与 VNT 并存还是互斥」，确认后即可进档 1 PoC。

---

## 1. 背景：这篇文章讲的是什么

用户给的文章描述的是 **SSHWithTailscale**（GitHub 仓库 `Matt-wzy/ssh-with-tailscale`）——一个「把 Tailscale 直接嵌进 App 进程」的 Android SSH 客户端。核心卖点：

- **无需单独打开官方 Tailscale App**：Tailscale 节点以 userspace（netstack）模式跑在 App 自己的进程里。
- **不占系统 VpnService 槽位、无需 root**：因为它不起 TUN，而是在 `127.0.0.1:1055` 起一个本地 SOCKS5 代理，SSH 流量走这个代理进 WireGuard 隧道到 tailnet（`100.x.x.x`）。
- 在手机上「一个 App 同时搞定 Tailscale + SSH」，适合运维/开发/家庭 NAS 用户。

文章里提到的关键技术名词——**userspace / netstack 模式、tsnet、SOCKS5 本地代理、WireGuard 隧道、authkey 无头鉴权**——正是本方案要评估的核心，也是和本项目现有 VNT 组网直接可比的地方。

---

## 2. ssh-with-tailscale 项目精读（已吃透）

仓库已克隆到 `references/ssh-with-tailscale/`，源码结构：

```
ssh-with-tailscale/
├── go/                      # gomobile 绑定层（核心）
│   ├── tailscale/tailscale.go   # tsnet 内嵌节点 + SOCKS5 代理 + peers 列表
│   ├── tailscale/publish.go     # 反向导出（中继/HTTP 代理）
│   └── go.mod                   # tailscale.com v1.102.4 + go-socks5
├── app/src/main/java/org/dpdns/mattsgateway/sshwithtailscale/
│   ├── TailscaleManager.kt       # 封装 gomobile 绑定（start/stop/status/peers）
│   ├── TailscaleService.kt       # specialUse 前台服务保活节点
│   ├── NetworkInterfaceProvider.kt # 解决 Android SELinux 网卡枚举
│   ├── SshConnector.kt / SshSession.kt  # JSch + ProxySOCKS5
│   ├── Socks5SocketFactory.kt    # 手写 SOCKS5 握手（远程 -R 用）
│   └── terminal/ 等              # 自绘终端 / SFTP / 端口转发 / TOFU
└── generate_aar.sh          # gomobile bind 生成 tailscale.aar
```

### 2.1 最关键的架构：tsnet 内嵌 + SOCKS5

`go/tailscale/tailscale.go` 是灵魂，做四件事：

1. **`tsnet.Server`** 起一个 userspace 节点：`Dir` 指定状态目录（持久化机器密钥），`Hostname` 定节点名，`AuthKey` 无头鉴权。
2. **本地 SOCKS5 代理**（`things-go/go-socks5`）监听 `127.0.0.1:1055`，所有 `Dial` 都走 `srv.Dial()` —— 即经 WireGuard 隧道直达 `100.x.x.x`。
3. **`captureLogf`** 抓取 tsnet 的交互式登录 URL（authkey 失效/过期时 Up() 会卡住等浏览器登录，不抓就永远「启动中」）。
4. **`Status()/Peers()/IsUp()/LastError()`** 暴露状态与在线设备列表给 UI。

`publish.go` 是「反向导出」：`srv.Listen()` 在 tailnet 上开监听端口，把连接转发到手机自身网络（中继）或做 HTTP 代理（CONNECT / 绝对 URI）。

### 2.2 两个 Android 专属的「坑」（本项目照搬时必须处理的）

这两个问题在纯 Go 环境下不存在，是 tsnet 跑进 Android 才炸出来的，`tailscale.go` 里的注释写得很清楚：

**坑 1：Go 的 `net.Interfaces()` 在 Android 上被 SELinux 拒。**
- 报错：`route ip+net: netlinkrib: permission denied` → Tailscale 起不来。
- 解法：Kotlin 侧用 `java.net.NetworkInterface` 枚举网卡（Java API 不受 SELinux 限制），打包成 JSON 传给 `Tailscale.setInterfaces()`，Go 侧 `netmon.RegisterInterfaceGetter` 注册成替代 getter。`NetworkInterfaceProvider.kt` 里还做了「去重缓存」，避免连接能力回调频繁触发导致 Tailscale link monitor 抖动断连。

**坑 2：Tailscale 的 logpolicy 找不到可写目录会 panic。**
- 报错：`no safe place found to store log state`（`/var/lib/tailscale` 不可写、`$HOME` 未设、cwd=`/`）→ panic 直接杀进程。
- 解法：`Tailscale.setStorageDirs()` 把 `XDG_CACHE_HOME` / `TMPDIR` 指到 App 私有目录。

### 2.3 保活：specialUse 前台服务

`TailscaleService.kt` 用 `FOREGROUND_SERVICE_SPECIAL_USE`（`foregroundServiceType="specialUse"`）保活进程内节点，`START_STICKY` + 持久化「node_desired」标记，进程被杀后重启能自动复活节点并重新下发反向导出规则。Manifest 里配了 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途（Android 14+ 对该类型有审核要求）。

### 2.4 其它可借鉴的实现（本项目若做 SSH 会直接复用思路）

- `SshConnector.kt`：JSch 的 `ProxySOCKS5(127.0.0.1, 1055)` 直接指向本地代理，密码或 PEM 私钥认证，TOFU 主机指纹校验。
- `Socks5SocketFactory.kt`：手写 SOCKS5 握手（ATYP=0x03 域名模式），供远程 -R 时 JSch 反向拨号走隧道。
- 端口转发 / SFTP / 自绘终端都是「锦上添花」，与本项目的「组网」核心无关，不在本方案评估范围。

---

## 3. 三条技术路线（把 Tailscale 内嵌进 Android 的可行方式）

精读源码 + 检索后，Tailscale 在 Android 上「内嵌」有三条成熟路线，本项目可选其一：

### 路线 A：tsnet + gomobile（ssh-with-tailscale 的做法）

- 用 `gomobile bind` 把 `tailscale.com/tsnet` 编译成 AAR（内含 `libgojni.so`）。
- **优点**：与 ssh-with-tailscale 完全同款，源码可直接参考；tsnet 是 Tailscale 官方维护的库，接口稳定；自带 SOCKS5 / Listen / Dial / LocalClient。
- **缺点**：产物体积大（`libgojni.so` ~30MB，压缩后 APK 增 ~30MB）；Go 工具链要求高（本项目用 go 1.26+）；`tsnet` 的鉴权模型偏「单节点登录」。

### 路线 B：官方 `tailscale/libtailscale` C 库（BSD-3-Clause）

- Tailscale 官方仓库，把 tsnet 编译成 C 库（`c-archive` / `c-shared`），输出 `libtailscale.a/.so` + `tailscale.h`。
- 这就是 `libtailscale` 这个 pub.dev 包（Dart/Flutter 用）和官方 `tailscale-android` 客户端底层用的东西。
- **优点**：**BSD-3-Clause 许可**（比 ssh-with-tailscale 的 GPL 更友好，商业集成无传染性）；官方维护；Swift/Dart/Go 等多语言绑定齐全。
- **缺点（致命）**：C API 比 gomobile 绑定薄，需要自己写 JNI 或 C 桥；`Android 15+ (API 35)` 起步（libtailscale 的 Flutter 包装声明），对本项目 minSdk 24 不友好。**更关键的是「Go runtime 冲突」**——官方博客明确承认：libtailscale 本质是「tsnet 外面套一层 C 胶水」，第一次调用时会在进程里**再起一个完整 Go runtime** 并接管进程生命周期；若进程里已有别的 runtime（尤其是另一个 Go runtime，比如本项目 VNT 的 `libvnt_jni.so`），两个 runtime 会互相踩踏最终崩溃。官方因此已转向 **tailscale-rs（Rust 版）** 取代 libtailscale，libtailscale 是条正在被官方边缘化的路线。

### 路线 C：直接依赖官方 tailscale-android 的 libtailscale（重、不推荐）

- 官方 Android 客户端本身就是「Kotlin UI + gomobile 绑定的 Go libtailscale」。
- **不推荐**：那是完整客户端（TUN + VpnService + UI），不是「内嵌库」，与本项目「组网只是能力之一」的诉求不符，且会抢占系统 VPN 槽位。

> **结论**：本项目要走内嵌，**路线 A（tsnet + gomobile）是唯一合理选择**，三条硬理由：
> 1. **工程同构**：VNT 已是 gomobile 产物（`libvnt_jni.so`，三套 ABI），路线 A 同样走 gomobile → AAR，构建链、ABI 目录、混淆、打包方式零新增；路线 B 要新开 NDK + JNI + 手写 C 胶水这条全新主线，本项目目前没有 NDK 构建链。
> 2. **Go runtime 冲突（最硬）**：路线 B 的 libtailscale 会在进程里再起一个 Go runtime 接管生命周期，与 VNT 的 Go runtime 打架崩溃；路线 A 在同一个 gomobile 绑定里跑，可与 VNT 协调同一 runtime，且未来若「Tailscale 替代 VNT」也是平滑换库。
> 3. **成熟度 + 可抄范本**：tsnet 是官方主推稳定库，ssh-with-tailscale 是现成 Android 集成范本（两个 Android 坑的解法可直接复用）；libtailscale 官方已转向 Rust 版，正在被边缘化，长期投入价值低。

---

## 4. Tailscale vs 现有 VNT：核心对比

| 维度 | 本项目 VNT（v2.0.12） | Tailscale（tsnet 内嵌） |
|---|---|---|
| 控制平面 | **自建** VNTS 2.0（`quic/tcp://039039.xyz:31008`） | Tailscale SaaS 或**自建 Headscale** |
| 数据平面 | WireGuard 变体（VNT 自研） | 标准 WireGuard + gVisor netstack |
| 内嵌方式 | gomobile 产物 `libvnt_jni.so`（3 ABI） | gomobile 产物 `libgojni.so`（3 ABI） |
| 系统 VPN | **需要 VpnService（起 TUN）** | **不需要**（userspace + SOCKS5） |
| 虚拟网段 | 86.74.128.0/24（服务端下发） | 100.64.0.0/10（CGNAT） |
| 生态 | 无（VNT 社区小众） | **极大**：MagicDNS/ACL/Subnet Router/Exit Node/Taildrop/官方全端 GUI/Headscale |
| 鉴权 | network_code + password | authkey / OAuth / 交互登录 |
| NAT 打洞 | 自研 | STUN + DERP 中继（全球中继网） |
| 许可 | VNT 项目自身许可 | tsnet 官方；ssh-with-tailscale 为 GPL |
| 定位 | 轻量组网 | 企业级 Zero Trust 组网平台 |

**关键结论**：

1. **两者是「同赛道」的组网能力，功能高度重叠**（都是把分散设备组进一个虚拟内网）。引入 Tailscale 不是「新增能力」，而是「换/并一个更强的组网引擎」。
2. **Tailscale 的差异优势全在「控制平面 + 生态」**：MagicDNS（域名而非 IP）、ACL 零信任、Subnet Router（把整个局域网导进 tailnet）、跨端官方客户端、Taildrop 传文件、Headscale 自托管——这些是 VNT 完全没有的。
3. **VNT 的现有优势是「轻 + 已落地 + 可控」**：本项目已经完整实现 VNT 组网并跑通生产，Tailscale 是「从零再叠一套组网」。

---

## 5. 给 Bridge 的集成方案（分三档，按投入递增）

### 档 0：明确「要不要引入」——两条决策线

在动任何代码前，先回答两个问题（这是本方案的核心价值，不是技术而是决策）：

1. **控制平面归谁？** ✅ **已拍板：Tailscale SaaS**（2026-11-14 用户确认）。零运维、开箱即用，authkey 无头登录即可让设备入网，不需要自建 Headscale / DERP。注意：免费档有节点数限制（Personal 3 台 / Starter 100 台等），企业多设备需选付费档；若后续对「数据主权」敏感再评估 Headscale。
2. **要不要官方生态？** 如果只是「手机连内网设备」，VNT 已经够用、且已落地；只有明确需要 **MagicDNS / ACL / Subnet Router / 跨端官方客户端 / 传文件** 时才值得引入 Tailscale。

> 我的建议：**如果只是想让「手机能连到 100.x.x.x 的内网设备」，VNT 已经覆盖了这个需求，不必引入 Tailscale。** Tailscale 的真正价值在「生态 + 标准化」，只有当项目有「跨平台多端（Windows/Mac/Linux/手机）统一组网」「需要 ACL 权限控制」「需要把整段局域网导进虚拟网」这类诉求时才值得做。

### 档 1（PoC，1~2 天）：验证「tsnet 内嵌 + Tailscale SaaS」链路

目标：证明 Bridge 进程内能拉起一个 Tailscale userspace 节点、拿到 `100.x.x.x`、经本地 SOCKS5 连到 tailnet 里的另一台设备。

- 在 Tailscale 控制台（SaaS）生成一个 authkey（`tskey-auth-...`）。
- 参照 `references/ssh-with-tailscale/go/tailscale/tailscale.go` 写一个最小 `tailscale.go`，只留 `Start/Stop/Status/Peers` + SOCKS5，去掉 SSH 相关。
- 用 `generate_aar.sh` 生成 `libgojni.so`（3 ABI），塞进 `app/src/main/jniLibs/`（与 `libvnt_jni.so` 并列，工程结构零新增）。
- 处理两个 Android 坑（网卡枚举 + 存储目录），加一个 PoC 按钮「启动 Tailscale → 显示 IP → ping 一个 peer」。

**验收标准**：能拿到 100.x.x.x、能连到 tailnet 里的设备、不抢系统 VPN 槽位（可与 VNT 同时存在）。**★ 必做：按 §6 方法学实测「流量」与「耗电」两个硬指标（WiFi + 移动数据各一轮），与 VNT 基线对照——这是用户拍板「是否最终引入」的决定性依据，PoC 不测这两个数则结论悬空。**

### 档 2（产品化，1~2 周）：Tailscale 作为「第二组网引擎」并入 Bridge

若档 1 通过且拍板要引入，按本项目既有工程规范落地：

- **新增 `core/tailscale` 模块**（对标 `core/keepalive`），内含：Go 绑定 AAR、`TailscaleManager`（状态机）、`TailscalePrefs`（SharedPreferences）、`TailscaleService`（specialUse 前台服务，复用 `core/keepalive` 的保活思路而非新造轮子）。
- **主页顶部 Tab 或「工具」页加「Tailscale」入口**（复用 VntPages 的 UI 范式），状态枚举对标 `VntStatus`（STOPPED/STARTING/RUNNING/RECONNECTING/ERROR）。
- **与 VNT 的关系**：两者都是组网，**建议做成互斥切换**（同一时间只启用一个），避免两张虚拟网卡并存带来的路由混乱；但底层允许「并存」（tsnet 不占 VpnService，天然不冲突）。
- **配置项**：control URL（Tailscale SaaS 留空即可，已拍板走 SaaS；Headscale 可选填自建地址）、authkey、hostname、ephemeral 开关。
- **服务端零改动**（与监控功能同理）：Tailscale 是纯客户端能力，Headscale 是独立部署的协调服务，不在 Bridge 后端里。
- **合规**：若走路线 A，注意 ssh-with-tailscale 是 **GPL-3.0**，直接抄其 Go 代码会传染 GPL；**建议直接基于 `tailscale.com/tsnet`（BSD 许可）自写绑定**，只「参考」ssh-with-tailscale 的两个 Android 坑的解法（那是事实/思路，不是可版权的实现）。路线 B 的官方 libtailscale 更是 BSD，无此顾虑。

---

## 6. 流量与耗电评估（决定「是否最终引入」的硬指标）

> 用户明确要求：当年加 VNT 时做过「流量 + 耗电」调研，这次必须对 Tailscale 再做一次，两个指标决定最终要不要加。本节给出：① VNT 当年的实测基线（对照基准）；② Tailscale 的理论预判（公开资料）；③ 实测方法学（照搬 VNT 口径）。**关键前提：Tailscale 尚未集成，两个指标现在物理上测不到，必须靠档 1 PoC 真机实测落地。**

### 6.1 VNT 实测基线（对照基准，权威）

见 `docs/dev/traffic-measurement.md`（2026-11-04 实测）：

| 指标 | VNT 组网后台稳态 | 说明 |
|---|---|---|
| **流量** | 1346 B/s ≈ **112 MB/天** | 占 Bridge 后台总流量 98.8%；开/关组网各 5×120s 对照，`dumpsys netstats` 单调累计行采样 |
| **耗电** | **未真正实测**（仅定性判断） | `vnt-mesh-integration.md` §7.13 记录为**遗留 TODO**「先在 WiFi 与移动数据下各做一轮 `dumpsys batterystats` 对照」；§二「边际耗电极小」只是理论判断，无真实电量数据 |

> 诚实说明：**当年 VNT 的「耗电」其实没测**，只有流量有权威实测。这次对 Tailscale 若要做「耗电」硬决策，等于两个方案都需要补一次真实 batterystats 对照，才能公平比较。

### 6.2 Tailscale 后台流量/耗电机制（公开资料 + 官方源码）

Tailscale 的后台活跃度**高于 VNT**，来源有三条独立链路：

| 机制 | 周期 | 来源 |
|---|---|---|
| 控制平面长轮询 | 服务器→客户端 ~50s 心跳，客户端→服务器 60s 上报 endpoint | 官方 HTTP/2 控制流 |
| **STUN 查询** | **每 23~27s 一次**，维持入站 NAT 映射 | 官方 issue #604 |
| WireGuard keepalive | 活跃 peer 每 **25s** 一个空包；magicsock 每 **2s** 心跳 | 官方设计（`PeerConfig.persistent_keepalive_interval`） |
| DERP 心跳 | 持续保活中继连接（对称 NAT 场景） | magicsock |

**耗电公开实测（外部数据，非本项目实测）**：
- iPhone 16 Pro 8 小时睡眠：Tailscale 掉 **6.4%** 电（WireGuard 仅 1.2%），基带每 25~60s 被唤醒（ecn-apps 实测）。
- Android 用户报告：Tailscale 占每日 **~30%** 电量（WireGuard ~3%）（sko.ai）。
- 官方 issue #604（2020）承认「idle 做太多工作」，Tailscale 1.4 起「aggressively goes idle」已改善，但 STUN 周期查询仍存在。

**预判结论**：Tailscale 后台流量与耗电**大概率高于 VNT**（多了 STUN 周期查询 + DERP 心跳 + 25s keepalive），尤其在**移动数据下**基带被频繁唤醒，耗电更明显。但量级需要真机实测确认——公开数据差异大（有说 6.4%/8h，也有说「静默」），不能直接套用。

### 6.3 实测方法学（照搬 VNT 口径，PoC 必做）

**流量**（有 VNT 现成权威口径，直接复用）：
- 采样源：`dumpsys netstats` 的 `mAppUidStatsMap` 单调累计行（appId 10351）。
- 方法：开/关 Tailscale 各 5 次 × 120s 对照，rxBytes/txBytes 差值 ÷ 秒数 = 速率；与 VNT 基线 1346 B/s 对比。
- 注意：Tailscale 是 userspace（不起 TUN），流量走 `srv.Dial()` 的 SOCKS5，netstats 归属仍在 `my.bridge.com` appId 下，采样口径与 VNT 一致。

**耗电**（VNT 当年遗漏，这次必须补）：
- 采样源：`dumpsys batterystats`（先 `--reset` 重置）。
- 方法：WiFi 与移动数据**各一轮**对照（关 Tailscale 基线 vs 开 Tailscale），各 ≥ 8h 睡眠/熄屏，读 `Estimated power use`（mAh）差值 + `batterystats --wakeups` 的射频唤醒次数。
- 关键指标：**射频唤醒频率**（Tailscale 的 STUN/keepalive 会周期性拉醒基带，这是耗电主因，比单纯电量百分比更本质）。

### 6.4 决策门槛（建议）

PoC 实测后，对照 VNT 基线判「是否最终引入」：
- 流量、耗电与 VNT **同量级** → 引入风险低，按计划产品化。
- 明显高于 VNT（尤其移动数据下耗电）→ 需权衡「冗余兜底价值」vs「日常多耗的电」，可能改成「**默认关、需时手动开**」的兜底形态，而非常驻并行。

### 6.5 ★ 按需开启形态：不开启 = 零耗电、零流量（已确认，2026-11-14）

用户提出关键形态问题：「只集成、默认不开启、按需开启」时，不开启会不会增加耗电/流量？结论：**不会，零开销**。三层依据：

1. **只集成不开启 = 纯静态**：tsnet 打包成 `.so` 进 APK 后，只要不调 `Start()`，一行代码不执行——无进程/线程/连接/定时器。唯一代价是 APK 体积增 ~30MB（libgojni.so），是存储体积非耗电/流量。
2. **Stop() 是真停、关闭后归零**：参考 `tailscale.go` 的 `Stop()` = `cancel()` 停 Up + `ln.Close()` 关 SOCKS5 + `srv.Close()` 关 tsnet.Server。`tsnet.Server.Close()` 会断开控制平面长轮询、停 STUN 周期查询、停 DERP 心跳、释放 WireGuard 会话，无任何残留后台线程/定时器。
3. **产品化注意点**（非「不开启」本身开销）：若用前台服务保活节点，「关闭」时必须同步 `stopSelf`（否则前台服务空转）；「开启态」本身的 STUN/DERP/keepalive 开销仍在，但只在「用户主动开启兜底」的那段时间发生。

**推论**：走「按需开启」，Tailscale 日常零成本，只有 VNT 打洞失败、用户手动开启兜底的那几分钟才有开销。这使「流量/耗电」硬指标**对按需开启形态几乎不构成障碍**，它真正约束的是「要不要常驻并行」。

---

## 7. 集成位置与 UI 形态（待设计，2026-11-14 调研中）

> 现状：VNT 组网页面 = 主页顶部 Tab 第 5 位（id=4，标题「组网」🕸️），卡片式垂直布局（状态卡→本机信息/配置摘要→设备列表→服务端→主按钮+配置）。VNT 走 VpnService **排他占 VPN 槽位**；Tailscale 走 userspace **不占 VPN 槽位**，两者可真正并存。

### 7.1 集成位置：继承现有组网页（推荐），而非新开 Tab

- 主页顶部 Tab 已达 **8 个**（短信/音频/智能控制/快递/组网/监控/极空/管理），再新增第 9 个 Tab 会过度拥挤。
- 两个引擎功能高度重叠（都是组网），语义统一为「组网」一个入口管两个引擎最合理，用户无需在两个 Tab 间切换。

### 7.2 UI 形态：切换式卡片（顶部引擎选择器 + 双状态概览）

三种候选布局对比：

| 布局 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 上下两卡 | 两引擎同屏可见 | 页面过长、信息重复堆叠 | ❌ |
| 左右两卡 | 紧凑 | 手机宽度不足、信息密度过高、操作区太窄 | ❌ |
| **切换式** | 一次聚焦一个引擎、复用同一套卡片布局、清晰 | 需顶部双状态概览补足「同时看两状态」 | ✅ **推荐** |

**切换式方案**：组网页顶部加一个「引擎选择器」（VNT | Tailscale 分段控件），下方只渲染当前引擎的完整卡片（复用现有 VntStatusCard 等布局范式，Tailscale 侧新增 T2StatusCard）。顶部常驻一条「双引擎状态概览」小条（VNT ●在线 / Tailscale ○未开），保证切换式不丢失「同时看两个引擎状态」的能力。详见下方交互设计稿。

### 7.3 已拍板细节（2026-11-14）

- ✅ **UI 形态**：切换式卡片（VNT | Tailscale 分段选择器 + 顶部双状态概览 + 下方当前引擎完整卡片）。
- ✅ **引擎选择器默认选中**：默认停在 **VNT**（老用户习惯，不记忆上次选择）。
- ✅ **authURL 交互登录态要暴露**：Tailscale 特有状态——authkey 失效/被吊销时 `Up()` 会阻塞等待浏览器交互登录，此时必须把 `AuthURL()` 返回的登录链接展示在状态卡上（否则用户卡在「启动中」不明所以）。状态枚举需在 VNT 的 STOPPED/STARTING/RUNNING/RECONNECTING/STOPPING/ERROR 基础上，为 Tailscale 增加「NEEDS_LOGIN（待交互登录，附 authURL）」态。
- **双引擎并存文案**：VNT 走 VpnService（排他占 VPN 槽位）、Tailscale 走 userspace（不占槽位），两者**可同时开启、无互斥逻辑**，双状态概览条如实显示各自状态即可。

---

## 8. 关键技术要点速查（照搬前必看）

| 要点 | 说明 |
|---|---|
| userspace 模式 | tsnet 不起 TUN、不请求 VpnService、无需 root；流量经 `srv.Dial()` 走 gVisor netstack |
| SOCKS5 本地代理 | 默认 `127.0.0.1:1055`，App 内流量显式指向它才走隧道，其余走系统网络 |
| authkey 无头鉴权 | `tskey-auth-...`；首次成功后节点密钥持久化在 `stateDir`，之后可空 authkey 重启 |
| 网卡枚举坑 | Android SELinux 拒 `net.Interfaces()` → Kotlin 枚举 + `netmon.RegisterInterfaceGetter` 绕过 |
| 存储目录坑 | 不设会 panic `no safe place found` → `SetStorageDirs` 指到 App 私有目录 |
| MagicDNS | SOCKS5 下建议用 `100.x.x.x` 原始 IP，域名解析更稳 |
| 保活 | specialUse 前台服务 + START_STICKY，Android 14+ 有审核要求 |
| 自建控制平面 | Headscale（BSD），`preauthkeys create` 生成 key；控制服务器地址由 `tsnet.Server` 登录流程决定 |
| 许可 | `tailscale.com/tsnet` 官方 BSD；`ssh-with-tailscale` 仓库 GPL-3.0 |

---

## 9. 结论与下一步

1. **技术上完全可行**：Tailscale 内嵌进 Bridge 与 VNT 是「同一类工程」（gomobile 产物 + 3 ABI + 前台服务保活），有 ssh-with-tailscale 现成参考 + 官方 tsnet 库。
2. **但它是「换引擎」不是「加能力」**：与 VNT 功能重叠，定位是「VNT 的冗余兜底」（用户拍板：VNT 有缺陷或打洞失败时的并行备用）。
3. **已拍板（2026-11-14 全部确认）**：
   - ① 引入 Tailscale；② 与 VNT **允许并存**；③ 控制平面 = **Tailscale SaaS**；④ 技术路线 = **A（tsnet + gomobile）**；
   - ⑤ 形态 = **按需开启**（默认关、需时手动开，已确认零开销）；⑥ 集成位置 = **继承现有组网页**；⑦ UI = **切换式卡片**（VNT 默认选中 + authURL 登录态暴露）。
4. **决定性指标 = 流量 + 耗电**：按需开启形态下，日常（不开）零开销，指标真正约束的是「开启兜底期间」的成本；仍须档 1 PoC 真机实测（照 VNT 口径 `dumpsys netstats` + `dumpsys batterystats` 对照）拿到硬数据。
5. **下一步 = 档 1 PoC**（tsnet 内嵌 + Tailscale SaaS + 流量/耗电实测）。前置：Go 工具链（go 1.26+）+ gomobile + Android NDK 环境检查；**任何工具链安装须先经用户同意（项目铁律）**。

---

## 附：待用户拍板的问题

1. ✅ 是否引入 Tailscale：引入。
2. ✅ 与 VNT 的关系：允许并存。
3. ✅ 控制平面：Tailscale SaaS。
4. ✅ 技术路线：A（tsnet + gomobile）。
5. ✅ 形态：按需开启（默认关、需时手动开）。
6. ✅ 集成位置与 UI：继承组网页 + 切换式卡片（VNT 默认选中 + authURL 登录态暴露）。
7. **生态能力取舍**：MagicDNS / ACL / Subnet Router / Taildrop 是否产品化时做进去——可后置，不阻塞 PoC。
8. ✅ **启动档 1 PoC**：已执行（编译 + 接入完成，见 §10）。

---

## 10. 档 1 PoC 执行记录（2026-11-14）

### 10.1 编译产物

- **工具链**（免安装绿色版，已 .gitignore）：`tools/tailscale-toolchain/`（Go 1.26 + NDK r25b + gomobile，~1.8GB）。
- **PoC 工作区**（已 .gitignore，含复用 GPL 代码）：`tools/tailscale-poc/`。
- **编译脚本**：`tools/tailscale-poc/build_aar.cmd`（Windows 原生 cmd，纯 ASCII + CRLF）。
- **产物**：`tools/tailscale-poc/build/Tailscale.aar`（60.5MB，含 4 ABI `libgojni.so`：arm64-v8a 43MB / armeabi-v7a 40.6MB / x86 41.2MB / x86_64 44.6MB）。
- **Java API 包名**：`my.bridge.com.tailscale.Tailscale`（重编译修正，原误为 `my.bridge.com.tailscale.tailscale.Tailscale` 多一层）。
- **暴露的静态方法**：`Start(stateDir, socksAddr, authKey)` / `Stop()` / `Status()` / `IsRunning()` / `IsUp()` / `LastError()` / `AuthURL()` / `Peers()` / `SetInterfaces(json)` / `SetStorageDirs(cache, tmp)` / `InterfaceCount()`。

### 10.2 编译踩坑（本轮新增，与既有 §8 呼应）

| 坑 | 根因 | 修法 |
|---|---|---|
| `go mod tidy failed: sumdb ... Access is denied` | gomobile bind 内部 go mod tidy 触发 sum.golang.org 校验，本机不通 | build_aar.cmd 加 `GOSUMDB=off` + `GONOSUMDB=*` |
| `'go' 不是内部或外部命令`（GOROOT 空） | **Edit 工具把 .cmd 换行符 CRLF 改成了 LF**，cmd 解析 set 语句错乱 | `sed` 转回 CRLF；.cmd 文件禁用 Edit 工具，用 sed 改 |
| 中文注释乱码 | .cmd 是 GBK 代码页，UTF-8 中文注释乱码且可能破坏解析 | .cmd 保持纯 ASCII，注释用英文 |

### 10.3 app 模块接入（本次已完成代码，待编译验证）

- **AAR 入库**：`app/libs/Tailscale.aar`（60.5MB，用户拍板入库），`app/build.gradle.kts` 加 `implementation(files("libs/Tailscale.aar"))`。
- **新增 Kotlin 包** `my.bridge.com.tailscale`：
  - `TailscaleManager.kt` — 封装 gomobile 绑定（start/stop/status/peers/authURL），处理两个 Android 坑（存储目录 + 网卡枚举）。
  - `NetworkInterfaceProvider.kt` — `java.net.NetworkInterface` 枚举网卡 → JSON → `setInterfaces()`，绕过 SELinux。
  - `TailscaleController.kt` — 状态机 + 轮询（gomobile 无回调，5s 轮询驱动 STARTING/RUNNING/NEEDS_LOGIN 迁移）。
  - `TailscaleState.kt` — 状态枚举 + peer 数据模型（对标 VntMeshState，含 NEEDS_LOGIN 态）。
  - `TailscalePrefs.kt` — authkey + hostname 配置持久化（SharedPreferences）。
- **组网页改造** `VntPages.kt`：
  - 顶部引擎选择器（VNT | Tailscale 分段控件，默认 VNT）+ 常驻双状态概览条。
  - 按选中引擎分支渲染完整卡片（VNT 现有 / Tailscale 新增）。
- **新增 UI** `TailscalePages.kt`：Tailscale 状态卡 / 本机信息 / 设备列表 / 登录链接卡（authURL 复制到剪贴板）/ 配置弹窗。

### 10.4 待办（真机验证前置）

1. **真机验证**：需用户在 Tailscale 控制台生成 authkey（`tskey-auth-...`），填入配置后点「开始 Tailscale」验证能拿到 `100.x.x.x`。
2. **流量/耗电实测**：按 §6.3 方法学（`dumpsys netstats` + `dumpsys batterystats`）WiFi + 移动数据各一轮，对照 VNT 基线。
3. **hostname 参数化**：当前 Go 侧 `tailscale.go` 的 `Hostname` 写死 `"android-ssh"`，`TailscaleConfig.hostname` 尚未生效，需改 Go 的 Start 签名加 hostname 参数并重编译（PoC 可后置）。
4. **GPL 合规**：`tools/tailscale-poc/tailscale/` 复用了 ssh-with-tailscale 的 GPL 代码，仅内部验证不入库；产品化需基于 `tailscale.com/tsnet`（BSD）自写绑定。
