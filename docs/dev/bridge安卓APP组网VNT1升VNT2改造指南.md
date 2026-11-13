# Bridge 安卓 APP 组网改造指南：VNT 1.x → VNT 2.0

> 面向对象：Bridge 项目安卓端维护者
> 目标：把 APP 内嵌的 VNT 1.x 组网内核（VntApp v1.2.9 语义）升级为 VNT 2.0，接入部署在 `039039.xyz:31008` 的 vnts2 服务端
> 编写日期：2026-11-13
> 依据：VNT 官方 README 与 VntApp 2.0 开源实现（Apache 2.0），文中所有类名、方法签名、配置字段均摘自官方仓库原文，可直接对照

---

## 0. 为什么要改（红线，先读）

1. **VNT 2.0 是整体重构，与 1.0 协议不兼容**（官方 README 原话）。现有 APP 内嵌的 1.x 内核**无法**通过"改服务器地址"连上新服务端，必须替换内核。
2. 1.x 服务端（31007）已永久下线且不再恢复。不完成本次改造，APP 的组网功能持续不可用（WiFi 局域网功能不受影响）。
3. 管理后台（服务端）下发命令的协议**保持不变**：仍下发 `vnt_start` / `vnt_stop` / `vnt_status` 三条命令，参数结构尽量兼容。APP 端改动集中在**内核与网络层**，命令通道层基本不动。

## 1. 联调环境（改造完成后用这套环境验证）

| 项目 | 值 | 说明 |
|---|---|---|
| v2 服务端地址 | `quic://039039.xyz:31008` 与 `tcp://039039.xyz:31008` | v2 支持多地址容灾，逗号分隔 |
| 组网编号（network_code） | `wzsvnttoken` | 对应服务端白名单 |
| 组网密码（password） | `qwe951753` | v2 语义：节点间端到端加密（ChaCha20-Poly1305），全网内必须一致 |
| 服务端管理后台 | `http://039039.xyz:29873`（账号 qwe159357 / qwe951753） | 联调时核对设备是否注册上线 |
| 已占用虚拟 IP | `.226 / .30 / .68 / .99 / .24 / .67`（86.74.128.0/24 段） | APP 设备建议**留空 ip 让服务端自动分配**；如需固定请避开上述地址 |

> ⚠️ 以上编号/密码仅用于本项目联调，请勿写入公开仓库。

## 2. 官方参考资源（改造前先 clone）

| 资源 | 地址 | 用途 |
|---|---|---|
| VNT 核心（Rust，含 vnt-jni） | `https://github.com/vnt-dev/vnt` | vnt-jni 各架构 `.so` 在 Releases 下载（v2.0.11 / v2.0.12 均带 `vnt-jni-arm64-v8a-v2.0.12.so`） |
| 官方安卓端 2.0（完整可编译工程） | `https://github.com/vnt-dev/VntApp`（master 分支） | **本次改造的标准答案**：JNI 封装层可直接复制，VpnService 适配层可对照移植 |
| 官方 README（集成规范） | vnt 仓库 main 分支 README「虚拟 IPv6」节 | Android 宿主需自行配置的 IPv6 地址与路由规则 |

VntApp 2.0 的关键源码文件（后续步骤会反复引用）：

```
app/src/main/java/com/vnt/          ← JNI API 封装层（建议整包复制）
  VntManager.java                     init / destroy / createNetwork(configJson)
  VntApi.java                         组网后的状态查询（设备列表/NAT/路由/服务器）
  VntNetwork.java                     组网实例句柄封装（启动/停止）
  VntException.java / NetworkResult.java / RuntimeEvent.java / RuntimeChange.java / LogEntry.java
app/src/main/java/com/rustvnt/vntapp/
  VntVpnService.java                  ← VpnService + 前台服务（对照移植）
  VpnRouteSet.java                    ← VPN 接口路由/地址注入（IPv6 派生规则在这里）
  VntConfigStore.java                 ← 配置 JSON 字段定义（第 4 步的字段权威来源）
  VntState.java / MainActivity.java   ← 状态机与界面层（仅参考）
```

## 3. 改造总览（三层）

| 层 | 动作 | 工作量 |
|---|---|---|
| ① 核心库层 | 移除 VNT 1.x SDK（VntApp 1.2.9 语义模块、`Vnt.list()` 等调用），引入 `libvnt_jni.so` v2 + 官方 `com.vnt` API 封装层 | 小（直接复制官方代码） |
| ② VPN 服务层 | VpnService/前台服务的启动、TUN 接口建立、IPv6 派生地址与路由注入，对照官方 `VntVpnService`/`VpnRouteSet` 移植 | 中（主要是 IPv6 路由这块与 1.x 不同） |
| ③ 命令语义层 | `vnt_start` 收到的参数映射为 v2 配置 JSON；`vnt_status` 改用 `VntApi` 查询 | 小（对照表见第 6 步） |

---

## 4. 详细步骤

### 步骤 1：引入 vnt-jni v2 核心库

两种方式二选一：

**方式 A（推荐，构建期自动拉取）**：照抄 VntApp 仓库 `app/build.gradle.kts` 中拉取核心库的逻辑——它构建时从 vnt 核心仓库取与 APP 同名 tag 的 Release 中的 `vnt-jni-<abi>.so`，找不到同名版本时用 latest。

**方式 B（手动）**：从 `https://github.com/vnt-dev/vnt/releases` 下载（v2.0.12 或更高）：

```
vnt-jni-arm64-v8a-v2.0.12.so      →  app/src/main/jniLibs/arm64-v8a/libvnt_jni.so
vnt-jni-armeabi-v7a-v2.0.12.so    →  app/src/main/jniLibs/armeabi-v7a/libvnt_jni.so   （如有 32 位设备）
```

注意**必须重命名为 `libvnt_jni.so`**（`System.loadLibrary("vnt_jni")` 按此名加载，见 VntManager.java 静态块）。

### 步骤 2：复制官方 API 封装层

把 VntApp 仓库 `app/src/main/java/com/vnt/` 目录**整个复制**进自己工程（包名 `com.vnt` 可保持原样）。该层是纯 Java + org.json，无 Android 依赖，零改动可用。

关键入口（摘自官方源码）：

```java
// 一次性初始化/销毁（APP 进程生命周期内）
boolean ok = VntManager.init();
VntManager.destroy();

// 用配置 JSON 创建组网实例（configJson 字段见步骤 4）
VntNetwork network = VntManager.createNetwork(configJson);
```

### 步骤 3：构造 v2 配置 JSON

配置字段权威定义见 `VntConfigStore.Profile.create(...)`（官方原文），Bridge 场景的**最小可用配置**如下：

```json
{
  "server": ["quic://039039.xyz:31008", "tcp://039039.xyz:31008"],
  "network_code": "wzsvnttoken",
  "password": "qwe951753",
  "device_id": "<APP 生成的设备唯一 ID，UUID 即可>",
  "device_name": "<设备名，建议沿用 Bridge 现有设备标识>",
  "mtu": 1280,
  "compress": false,
  "rtx": false,
  "fec": false,
  "no_punch": false,
  "no_broadcast": false,
  "allow_ikev2": false,
  "device_mode": "tun",
  "input": [],
  "output": [],
  "peer_address": [],
  "cert_mode": "skip"
}
```

字段说明（仅列 Bridge 用得到的）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `server` | 数组 | v2 支持多服务器容灾/负载均衡；**同一网络多台服务器时必须配合 `ip` 固定虚拟 IP**（官方校验规则：0 个或 >1 个 server 时强制要求填 ip） |
| `network_code` | ✅ | 组网编号，即 1.x 的 token |
| `password` | 建议 | 节点间端到端加密密码；全网设备必须一致；**v2 中服务端只转发密文**（1.x 的 server_encrypt 概念已不存在） |
| `device_id` | ✅ | 设备唯一标识，决定虚拟 IP 分配的稳定性；必须持久化，卸载重装保持不变 |
| `device_name` | 建议 | 在线设备列表里显示的名字 |
| `ip` | 可选 | 固定虚拟 IP（纯 IP 默认 /24）；留空由服务端分配。**联调期建议留空** |
| `device_mode` | | `"tun"`（默认，三层网卡）或 `"no"`（无网卡，仅做流量出口/端口映射，无需 VPN 权限）；Android 的 VpnService 只支持 TUN |
| `rtx` / `fec` | | v2 新特性：QUIC 优化传输 / 前向纠错，弱网环境可开 |
| `cert_mode` | | 服务端证书校验模式，联调用 `"skip"`（默认） |
| `mtu` | | 合法区间 **1280–9000**（Rust 核心 P2P 栈下限 1280，越界直接被核心拒绝） |

### 步骤 4：VpnService 适配（与 1.x 差异最大的部分）

对照官方 `VntVpnService.java` + `VpnRouteSet.java` 移植，特别注意 1.x 没有的新要求（README「虚拟 IPv6」节原文规范）：

1. v2 的 TUN 会同时跑 IPv4 与虚拟 IPv6（`fd76:6e74::/96`，地址后 32 位即虚拟 IPv4 的 node ID，如 `86.74.128.30` → `fd76:6e74::564a:801e` 形式）。
2. 宿主 APP **必须**在建立/重建 VPN 接口时：
   - 给接口添加派生的 IPv6 地址（从 JNI 网络信息 `virtual_ipv6` / `virtual_ipv6_prefix_len` / `virtual_ipv6_route` 取值，官方 JNI 已返回）；
   - 添加 `fd76:6e74::/96` 与 `ff00::/8` 路由；
   - IPv4 路由按原有业务网段需求添加（Bridge 场景添加 `86.74.128.0/24` 即可）。
3. 官方示例在**首次建立和重建 VPN 时**自动执行上述注入——第三方宿主必须同样处理，否则 IPv6 链路不通（只做 IPv4 也能通，但建议照官方做全）。
4. 前台服务与 VPN 授权流程（`VpnService.prepare()`）与 1.x 时代一致，沿用现有实现即可。
5. v2 同一设备同一组网编号**只允许一个实例**：机器标识相同会被服务端当成"同一设备的新连接"踢掉旧连接，出现互相重连死循环。v2 核心现在会直接拒绝第二个实例并打印提示——APP 侧请保证 `vnt_start` 幂等（已有组网时忽略重复 start，这条 Bridge 现有实现 v0.2.33 已做，保持即可）。

### 步骤 5：移除 1.x 残留

- 删除内嵌的 VNT 1.x SDK 模块与 `Vnt.list()` 等全部调用点（用全局搜索 `Vnt.` / `vnt-cli` / `1.2.9` 清理）
- 删除 1.x 特有概念的处理代码：`server_encrypt`、裸 `udp://`/`tcp://` 协议切换参数（v2 协议由 server 地址前缀决定）
- 开机自动组网记忆、前台服务保活等 Bridge 既有逻辑**保留**，只把底层执行体换成 v2 实例

### 步骤 6：命令语义映射（管理后台契约）

管理后台下发的三条命令与参数**不变**，APP 端按下表映射：

#### `vnt_start`（参数：server / token / password / protocol）

| 后台参数 | v2 处理 |
|---|---|
| `server` | 裸 `host:port` 时自动补全为 `quic://host:port` 与 `tcp://host:port` 两条地址放入 `server` 数组；若参数已带 `quic://`/`tcp://`/`wss://` 前缀则原样使用。联调环境默认值应为 `039039.xyz:31008` |
| `token`（兼容旧名 `network_code`） | → `network_code` |
| `password` | → `password`（端到端加密密码） |
| `protocol`（tcp/udp） | **废弃**。v2 的传输协议由 server 地址前缀决定；收到该参数可忽略（建议在回执中注明"已忽略，v2 由地址前缀决定协议"） |

#### `vnt_status`

改用 `VntApi` 查询后拼装快照，与现有回执字段语义对齐：

| 现有快照字段 | v2 取值来源 |
|---|---|
| 状态（组网中/停止） | `VntNetwork` 实例存活 + `VntApi.getNetwork() != null` |
| 虚拟 IP | `VntApi.getNetwork().ip()` |
| Token | 本地保存的 `network_code` 原样返回 |
| 协议 | `VntApi.getServerList()` 中已连接服务器的地址前缀（quic/tcp） |
| 在线设备数 | `VntApi.getClientList()` 中 `online=true` 的数量 |
| 服务端版本 | `VntApi.getServerList().get(i).version()` |

> 可选增强：`getClientList()` 现在还能拿到每台设备的直连状态（`direct`）、RTT、丢包率、收发流量——如 Bridge 后续想做"组网设备质量页"，v2 的数据比 1.x 丰富得多，**但本次改造不要加**，保持回执结构稳定。

#### `vnt_stop`

停止 v2 实例并释放系统 VPN 通道（`VntManager` 层面销毁 handle + 停掉前台服务），与现有逻辑等价。

### 步骤 7：联调验证清单（逐项打勾）

1. [ ] 安装新 APK，APP 内手动组网（或等后台下发 `vnt_start`），系统弹出 VPN 授权并允许
2. [ ] APP 显示组网成功，拿到 `86.74.128.x` 虚拟 IP（服务端管理后台 `http://039039.xyz:29873` 设备列表中可见该设备）
3. [ ] `vnt_status` 回执字段齐全且语义正确
4. [ ] 从 PC 侧 `ping` APP 的虚拟 IP 通；反向 APP `ping` PC 虚拟 IP（如 `86.74.128.226`）通
5. [ ] 访问 APP 内嵌 SSH 服务（8022）经 VNT 虚拟网可达（`ssh bridge@<APP虚拟IP> -p 8022`）
6. [ ] **断线重连**：APP 开飞行模式 10 秒再恢复，自动重连成功（这是升级 v2 的核心收益，1.x 就坏在这）
7. [ ] 杀 APP 进程重开，开机自动组网记忆生效
8. [ ] 重复下发 `vnt_start` 被幂等忽略，回执如实说明
9. [ ] 后台按 `vnt_status` 快照的按钮禁用逻辑（已组网禁 start、未组网禁 stop）工作正常

### 步骤 8：发布与同步

- APP 版本号建议 **v0.2.34+**（Bridge 语义，当前最新 v0.2.33）
- 向管理后台维护方同步：新版 APP 的 `vnt_status` 回执样例 JSON 一份（用于后台字段核对）、`protocol` 参数已废弃的说明
- 老 APP（≤v0.2.33）因 1.x 服务端已下线，组网功能本就不可用，**无需向后兼容**；后台已计划按 APP 版本区分下发参数

## 5. 常见问题

| 问题 | 处理 |
|---|---|
| 启动报 `UnsatisfiedLinkError` | so 未改名或 ABI 目录放错，必须 `jniLibs/<abi>/libvnt_jni.so` |
| 配置被核心拒绝 | 检查 `mtu` 是否 ≥1280、`network_code` 是否为空、多服务器时是否漏了固定 `ip` |
| 能通 IPv4 不通 IPv6 | VPN 接口未注入派生地址/路由，见步骤 4 第 2 条 |
| 设备反复掉线重连 | 检查是否同设备起了两个实例（`device_id` 相同）；确认 APP 幂等守卫生效 |
| ping 通但业务不通 | 检查 `output`/`input` 网段（Bridge 场景留空数组即可）与对端防火墙 |
| 服务端连接被拒 | 确认地址前缀正确：`quic://`、`tcp://`、`wss://`；裸地址不带前缀的行为以核心文档为准，建议始终显式带前缀 |

## 6. 交付物

1. 新版 APK（arm64-v8a 必须交付；armeabi-v7a / x86_64 按存量设备架构决定）
2. 变更说明（改了哪些模块、`protocol` 参数废弃、回执样例）
3. 联调通过的验证清单（步骤 7 打勾结果）

---

*本文档中的服务端地址、组网编号、密码仅限本项目联调使用。vnts2 服务端（31008）已就绪：systemd 自启、持久化开启，APP 端完成后无需服务端做任何改动即可接入。*
