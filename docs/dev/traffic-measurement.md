# Bridge 后台流量实测与构成拆解

> 权威文档 · 2026-11-04 实测 · 采样方式与口径已反复校验（纠正多轮误判后最终锁定）

## 一、结论速览

Bridge 后台运行态（WiFi、VNT 组网开启、主 WS 在线、无微信/SSH）真实流量：

| 组成部分 | 下行 | 上行 | 合计 | 24h 推算 | 占比 |
|---------|------|------|------|---------|------|
| **VNT 组网** | 679 B/s | 668 B/s | 1346 B/s | ~110.9 MB/天 | **98.8%** |
| 主 WS 心跳 + 位置上报 | 7 B/s | 10 B/s | 17 B/s | ~1.4 MB/天 | 1.2% |
| **总计** | **686 B/s** | **677 B/s** | **1363 B/s（1.33 KB/s）** | **~112 MB/天** | 100% |

**核心结论**：Bridge 后台流量几乎全部（≈98.8%）由 **VNT 组网的 P2P 打洞 + NAT 保活**产生；Bridge 自身的业务流量（主 WS 心跳 + 位置周期上报）仅 ~17 B/s，约 1.4 MB/天，几乎可忽略。

> 上表数值来自 **开/关组网各 5 次 × 120s 对照采样**（见 §三，2026-11-04 20:23–20:45 实测，比首轮单次对照更严谨）。

## 二、采样口径（★ 权威，纠正多轮误判后锁定）

### 唯一可靠采样源

`dumpsys netstats` 的 **`mAppUidStatsMap` 单调累计行**，列序 `uid rxBytes rxPackets txBytes txPackets`：

```
10351 <rxBytes> <rxPackets> <txBytes> <txPackets>
```

- **跨重启单调递增、绝不回退**，是最可靠的采样源。
- Bridge 包名 `my.bridge.com` 的 **appId = 10351**（`dumpsys package` 的 `appId=10351`；多用户 uid = 999*100000+10351 = 99910351，但 netstats 里显示 appId 10351）。
- 采样方法：间隔采样两次，`rxBytes` 差值 ÷ 秒数 = 下行速率。

### 必须避开的错误口径（踩坑记录）

1. **`dumpsys netstats detail` 的 wlan0 会话行会滚动清零**（实测几分钟内数值回退），**不能用于长间隔采样**。
2. **不要用 `ps -A | grep vnt` 判断 VNT 是否运行**——VNT 是 `my.bridge.com` 进程内的 VPN 服务（VntVpnService），进程名就是 `my.bridge.com`，查不到独立进程名。
3. **不要凭端口号臆测服务身份**——须查代码常量 + `dumpsys activity services` + `logcat` 三方印证（曾把 48540 误判成 SSH，实为 VNT 打洞端口）。

## 三、对照实验设计（量化 VNT 占比）

- **方法**：停 VNT 组网测「纯业务基线」，与「开 VNT」总流量相减。
- **采样时长**：各 180 秒（稳态，socket 数恒为 4，无偶发重连干扰）。
- **停 VNT**：管理后台下发 `vnt_stop`（`POST /api/commands`，admin token 登录 `POST /api/admin/login`）。
- **恢复 VNT**：`vnt_start`。

### 实测数据

| 状态 | 采样 | rx 增量 | tx 增量 | rx 速率 | tx 速率 |
|------|------|---------|---------|---------|---------|
| 开 VNT | 180s | 121,435 B | 119,793 B | 674.6 B/s | 665.5 B/s |
| 关 VNT | 180s | 1,486 B | 1,856 B | 8.26 B/s | 10.31 B/s |
| **VNT 贡献** | — | — | — | **666.4 B/s** | **655.2 B/s** |

### 严谨复测：开/关组网各 5 次 × 120s（2026-11-04 20:23–20:45）

**开组网 5 次采样**（每区间 120s）：

| 区间 | rx 增量 | tx 增量 | rx 速率 | tx 速率 |
|------|---------|---------|---------|---------|
| 1 | 79,691 B | 80,536 B | 664.1 B/s | 671.1 B/s |
| 2 | 80,632 B | 81,040 B | 671.9 B/s | 675.3 B/s |
| 3 | 82,280 B | 82,699 B | 685.7 B/s | 689.2 B/s |
| 4 | 82,275 B | 80,976 B | 685.6 B/s | 674.8 B/s |
| 5 | 86,732 B | 81,086 B | 722.8 B/s | 675.7 B/s |
| **均值** | — | — | **686.0 B/s（σ22.5）** | **677.2 B/s（σ6.9）** |

**关组网 5 次采样**（每区间 120s）：

| 区间 | rx 增量 | tx 增量 | rx 速率 | tx 速率 |
|------|---------|---------|---------|---------|
| 1 | 788 B | 1,034 B | 6.57 B/s | 8.62 B/s |
| 2 | 958 B | 1,323 B | 7.98 B/s | 11.03 B/s |
| 3 | 968 B | 1,226 B | 8.07 B/s | 10.22 B/s |
| 4 | 788 B | 1,034 B | 6.57 B/s | 8.62 B/s |
| 5 | 896 B | 1,148 B | 7.47 B/s | 9.57 B/s |
| **均值** | — | — | **7.33 B/s（σ0.73）** | **9.61 B/s（σ1.04）** |

**相减得 VNT 净贡献**：

| 指标 | 下行 | 上行 | 合计 |
|------|------|------|------|
| 开组网总流量 | 686.0 B/s | 677.2 B/s | 1363.2 B/s |
| 关组网业务基线 | 7.33 B/s | 9.61 B/s | 16.94 B/s |
| **VNT 贡献** | **678.7 B/s** | **667.6 B/s** | **1346.3 B/s** |
| **VNT 占比** | **98.9%** | **98.6%** | **98.8%** |

> 两组各 5 次样本标准差极小（开组网下行 σ22.5、上行 σ6.9；关组网下行 σ0.73、上行 σ1.04），
> 说明稳态流量高度稳定，采样可靠，结论可复现。

## 四、流量构成证据链

### Bridge 的完整网络拓扑（实测 socket）

| 连接 | 身份 |
|------|------|
| `[::]:48540` + `[::]:38981`（TCP/UDP 监听）| **VNT 组网 NAT 打洞端口**（vnt_jni）|
| `:48540 → 192.168.31.30:44971`（局域网）| VNT 节点间 P2P 连接 |
| `IPv6:48540 → IPv6:16796` | VNT 节点间 P2P 连接（IPv6）|
| `:49246 → 122.190.6.122:15160`（ESTAB）| 主 WS 到 Bridge 服务端 |
| VNTS 组网服务器 `039039.xyz = 122.6.190.161:31007` | VNT 组网控制通道 |

### VNT 端口铁证（logcat）

```
vnt_jni: NatInfo { public_ports: [48540, 38981], udp_ports: [48540, 38981],
                  tcp_port: 48540, nat_type: Cone, local_ipv4: 192.168.31.109 }
```

### 各参数（代码常量）

- **主 WS 心跳**：60s 一次（`WsConfig.HEARTBEAT_INTERVAL_MS = 60_000L`），对齐栅格。
- **位置上报**：周期上报（服务定时 + WorkManager 兜底），实测约 16 分钟一次，位置不变时被服务端「距上条 0m < 阈值 30m」合并。
- **VNT 保活**：Rust native 内部黑盒（1.x 未暴露 `getTrafficInfo`），UDP 打洞 + NAT 保活持续探测，是流量主体。

## 五、含义与后续

1. **流量健康**：总量 ~112 MB/天，量级健康，无泄漏/异常大流量。
2. **省流量空间主要在 VNT**：若需压缩后台流量，优先考虑 VNT 的保活/打洞策略（但受「虚拟网必可达」硬约束限制，见 `docs/dev/heartbeat-inventory.md` §9.10 相关拍板）。
3. **Bridge 自身几乎零流量**：主 WS + 位置上报仅 1.4 MB/天，说明心跳/上报设计已很精简。

---

## 六、微信页流量（独立于 Bridge 后台，2026-11-05 决定性实测）

> 微信功能是**第五套连接体系**，连中心服务器 `039039.xyz:15151`（独立于 Bridge 服务端 15160），激活时流量高达 ~200 KB/s，是后台流量的 ~150 倍。本节记录其来源的**决定性实测结论**（多轮纠错后最终锁定）。

### 6.1 网络拓扑（用户澄清，纠正多轮误判）

- 手机 WiFi IP = `192.168.31.109`，VNT 虚拟网 IP = `86.74.128.88`。
- 手机是 **client 身份**（连 `/ws/client`），**不是 agent**。
- `192.168.31.226` = 写代码设备（跑 Bridge 服务器 15160）；`192.168.31.30` = 域名 `039039.xyz` 对应的公网服务器（nftables 转发到 15151 等）。
- 微信服务器地址原本填域名（走公网 nftables 转发），后改为 **VNT 内网直连**（`86.74.128.24:15151`）。

### 6.2 决定性对照实验（铁证）

| 状态 | wlan0 下行 | wlan0 上行 | tun0 下行 | tun0 上行 |
|------|-----------|-----------|----------|----------|
| **微信页激活** | 133.6 KB/s | 65.0 KB/s | 59.4 KB/s | 32.1 KB/s |
| **退出微信页** | 2.3 KB/s | 1.06 KB/s | 0 KB/s | 0 KB/s |

**结论**：微信页激活 ≈ 200 KB/s（wlan0 总），退出后归零（只剩 Bridge 主 WS 的 3.3 KB/s 心跳）。**微信页 100% 贡献了这 200 KB/s**，且是稳定持续值，非一次性洪峰。

### 6.3 ★ 最终根因（推翻「头像下载」「13 个 Bot 消息」两个错误判断）

早期两个判断均**方向错误**：
- ❌ 「头像/图片缩略图无磁盘缓存」——磁盘缓存缺失是独立问题，但不是这 200 KB/s 的来源。
- ❌ 「手机作为 agent 管理 13 个营销 Bot 收群消息」——手机是 client 不是 agent，`86.74.128.30/.226/.67/.99` 是其它 VNT 节点，与手机无关。

**真正的根因 = center-server 的 uvicorn Ping 风暴 bug，经 VNT 封装放大 15 倍。**

#### 源头 bug（源码铁证）

`center-server/main.py` 里为了「关闭 uvicorn 层 ping」写了：
```python
ws_ping_interval=0.0,   # 想关 ping，但写法错误
ws_ping_timeout=None,
```

uvicorn 0.46 走 `websockets_impl.py`（legacy 实现），把这两个参数直接传给 **websockets 库 16.0** 的 `Connection`。而 websockets 16.0 `connection.py:861` 的启动判断是：

```python
if self.ping_interval is not None:   # 0.0 is not None → True！启动了 keepalive
    ...
```

`keepalive()` 协程内部：
```python
await asyncio.sleep(self.ping_interval - latency)   # 0.0 - latency = 负数 = sleep(0)
self.protocol.send_ping(data)                        # 立即发 ping
```

`sleep(负数)` 等价 `sleep(0)` → `while True` 死循环**零延迟疯狂 send_ping**，形成 Ping 风暴。

**关键教训**：websockets 16.0 判断用 `is not None` 而非 `> 0`。要「关闭 ping」必须传 `None`，传 `0.0` 反而触发 keepalive 死循环。

#### 铁证（tcpdump + 进程观测）

1. tcpdump 抓到服务器 → 手机每秒几百个 Ping 帧（opcode=9，`89 04` + 4 字节随机 payload = `random.getrandbits(32)`），payload 每次变化，密度 ~500-1000 帧/秒。
2. center-server 进程 CPU **99.7%** 满载（事件循环被 Ping 死循环打满）。
3. 手机单条连接 `bytes_sent`/`bytes_received` 持续增长，`data_segs_out` 是 `data_segs_in` 的 33 倍（服务器疯狂发，手机只回 ACK/pong）。

#### VNT 封装放大（15 倍）

6 字节的 WS Ping 帧 → VNT 组网封装成 ~90 字节 UDP 隧道包（IP 头 20 + UDP 头 8 + VNT 头 + payload）。服务器 `eth0` 5 秒抓 **42,513 个 UDP 包**（~8500 包/秒），大小集中在 `length 84-102`（占 90%+）。

```
微信 center-server 疯狂发 Ping 帧（6 字节 × 每秒几百个）
  → VNT 虚拟网（tun0）
  → VNT 封装成 ~90 字节 UDP 隧道包（15 倍放大）
  → 物理网卡（wlan0）发出
  → 手机看到 ~200 KB/s
```

### 6.4 修复方案

**一行修复**（`center-server/main.py`）：
```python
ws_ping_interval=None,   # 从 0.0 改成 None → websockets 16.0 不启动 keepalive，彻底无 ping
ws_ping_timeout=None,
```

修复后需重新实测确认流量下降（预期微信页流量应降至「REST API 拉取 + 消息推送」的正常量级，而非 Ping 风暴）。

### 6.5 遗留待办

- **磁盘缓存（头像/缩略图/视频封面）已实现**（v0.2.34 落地三级缓存，见 `docs/dev/` 台账与 2026-11-05 工作日志）——独立价值是「单次加载省流量 + 秒开」，与本次 200 KB/s 无关。
- 修复 center-server Ping 风暴后，重新采样量化微信页的「正常」流量基线 → **已完成，见 §七**。

---

## 七、微信后台流量行为澄清 + Ping 风暴修复后稳态基线（2026-11-05）

### 7.1 Ping 风暴修复后后台稳态基线（决定性质证）

用户手动将 `center-server/main.py` 的 `ws_ping_interval=0.0` 改为 `None` 并重启，随后实测「主 WS + VNT + 微信（App 后台，离开后立即结束=关闭）」稳态流量。

采样口径：`dumpsys netstats` 总累计行（appId=10351），3 个区间各 120s：

| 区间 | rx 速率 | tx 速率 |
|------|---------|---------|
| 区间1 | 689.4 B/s | 664.0 B/s |
| 区间2 | 695.3 B/s | 656.2 B/s |
| 区间3 | 723.5 B/s | 664.0 B/s |
| **均值** | **702.7 B/s** | **661.4 B/s** |

**合计 = 1364 B/s ≈ 1.33 KB/s ≈ 112.4 MB/天**。

**关键结论**：与「仅主 WS + VNT（无微信）」的基线 1363 B/s 几乎完全一致（差 <1 B/s）——**微信后台加入后未引入任何异常流量**，之前的 ~200 KB/s Ping 风暴已彻底消失；后台流量仍 98.8% 由 VNT 组网贡献。

### 7.2 微信后台到底发生了什么（代码级穷举，纠正「只维持 WS 心跳」的粗浅说法）

**服务端→手机推送链路是原样透传，不截断**：

- `agent.py:204`：`broadcast_to_clients({"type":"message","data": msg.get("data")})` —— 把 agent 上报的完整 `data`（含 `content` 文字 + `attachment` 元数据）原样转发，不做任何加工。

**手机后台收到新消息时的实际网络行为（穷举）**：

1. WS 被动接收那一条 `type=message` JSON（含**完整文字** + attachment 元数据）——唯一网络数据。
2. 更新内存 session/unread（`upsertSession`/`recalcUnread`）——纯内存。
3. 弹通知（`WechatNotifier.post`）+ 可选提示音（`WechatSound.play` 播本地 wav）——纯本地。

**后台绝不下载任何媒体**（关键结论）：

- 头像/图片缩略图/视频封面加载**全部**在 `WechatMedia.kt`（Compose UI 层 `WechatAvatar` 等组件的 `LaunchedEffect`/`WechatImageLoader.load`）；App 切后台时 UI 不渲染、`LaunchedEffect` 不执行 ⇒ **不下载**。
- 通知栏用 `setSmallIcon(app.applicationInfo.icon)`（App 图标），**不用微信头像** ⇒ 后台收消息连头像都不拉。

**文字 vs 图片/视频的消息内容**：

- 文字消息：`summaryOf(TEXT, content)` 返回 `WechatEmoji.replaceCodes(content)`（**完整文字，不截断**），通知栏显示全文。
- 图片/视频/语音：`summaryOf` 返回本地占位符 `[图片]`/`[视频]`/`[语音]`（**客户端本地生成，非服务端下发，零流量**），文件本体不随推送下发。

**修正后的准确说法**：

> 后台稳态 = 一条 WS 长连接（心跳 + 有消息时被动接收推送 JSON：文字含全量、图片/视频只含 attachment 元数据）+ 通知栏弹通知（纯本地，零媒体下载）。图片/视频/头像的下载只在**前台进入微信页 UI 渲染时**才发生。

---

*实测环境：一加 9Plus（Android 14，ColorOS），WiFi，Bridge v0.2.47→v0.2.50；微信中心服务器 `039039.xyz:15151`（SSH `86.74.128.24`，center-server 进程 PID 54491，`ws_ping_interval` 已修 None）。*
