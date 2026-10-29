# Bridge 后端服务设计

> Bridge 的 Python 服务端。承载客户端（Android App）的 WebSocket 长连接，负责连接管理。
>
> 本文档是后端服务的**权威设计文档**，所有后端实现必须遵循。

---

## 一、定位与边界

- **角色**：服务端（Server），对应客户端（Android App，即「用户端 / 前端」）。
- **通信**：WebSocket 长连接（`/ws`）+ REST 管理接口（`/api/*`）。
- **已实现范围**：
  1. **连接管理**（建立、心跳、断线、重连、唯一连接）——核心
  2. **消息推送**（服务端主动向设备推送）
  3. **设备鉴权 + 注册**（token 验证，可开关）
  4. **REST 管理接口**（连接列表、踢下线、推送、设备注册、统计）
- **后续扩展**：用户系统、业务数据、持久化存储、水平扩展（本文档预留，不展开）。

## 二、技术选型

| 项 | 选型 | 理由 |
|---|---|---|
| 语言 | Python 3.11+ | 用户指定 |
| Web 框架 | **FastAPI** | 异步原生、WebSocket 支持好、自动文档、后续扩展 REST/鉴权方便 |
| ASGI 服务器 | Uvicorn | FastAPI 标准搭配 |
| 依赖管理 | **uv**（`pyproject.toml` + `uv.lock`） | 快速、可复现、锁版本 |
| 配置管理 | **YAML** 配置文件（`config.yaml`）+ 环境变量覆盖 | 结构化、可读、可版本化；敏感项走环境变量 |
| 异步模型 | `asyncio` 单进程 + 多 worker（进程级扩展） | 连接管理是 IO 密集，异步最优 |

## 三、连接管理的核心难题（设计依据）

> 这一节是本文档的**理论基础**，所有机制设计都围绕下面 5 个难题展开。

### 3.1 五个核心难题

1. **半开连接（Half-Open）**：TCP 连接的一端已经"死掉"（进程崩溃、网线拔了、App 被强杀），但对端完全不知情——因为它没收到 FIN/RST。对端会一直以为连接还活着。**这是长连接最大的坑。**
2. **静默断网**：App 切后台、手机息屏、网络切换（WiFi↔4G），连接可能"假死"但不报错。
3. **重连风暴（Reconnect Storm）**：大量客户端同时断线重连，服务端瞬间被压垮；或单个客户端疯狂重连形成抖动。
4. **重复连接**：同一设备因重连不及时，产生新旧两个连接并存（旧连接还没被服务端判定死亡，新连接又进来了）。
5. **连接泄漏**：服务端若不能及时回收死连接，内存/文件描述符被耗尽，最终拖垮整个服务。

### 3.2 解决思路总纲

| 难题 | 手段 |
|---|---|
| 半开连接 | 心跳 + 读超时：定期互探活性，超时未响应即判死 |
| 静默断网 | 心跳周期内未收到任何消息 → 判定断线并主动回收 |
| 重连风暴 | 客户端指数退避 + 随机抖动；服务端限流/去重 |
| 重复连接 | **设备唯一标识 + 服务端顶旧连接**（新连接建立时主动关闭旧连接） |
| 连接泄漏 | 读超时兜底回收 + 显式关闭清理 + 连接数监控 |

---

## 四、心跳机制（核心设计）

### 4.1 角色分工（已定：客户端主导 + 服务端读超时兜底）

- **客户端主导**：客户端周期性发送 `ping`，服务端收到后回 `pong`。
  - 客户端在「发送 ping 后的一段时间内」未收到 `pong`，判定服务端不可达，触发重连。
- **服务端读超时兜底**：服务端记录「最后一次收到该连接任意消息的时间」，若超过阈值（如 2 个心跳周期）未收到任何数据，判定连接死亡，主动关闭并回收。
  - 这一步弥补「客户端主导」的短板：当客户端静默崩溃时，服务端也能主动发现半开连接。

### 4.2 参数设计（心跳硬编码，两端一致）

> **关键决策**：心跳参数必须客户端 ≤ 服务端读超时，否则服务端必判客户端离线。
> 若两端都可自由改，极易配置不一致导致误判。故**心跳/重连参数客户端与服务端都硬编码，不提供配置入口**。

| 参数 | 值（硬编码，两端一致） | 说明 |
|---|---|---|
| `heartbeat.interval` | 20s | 客户端心跳发送周期 |
| `heartbeat.client_pong_timeout` | 10s | 客户端发出 ping 后等待 pong 的超时 |
| `heartbeat.server_read_timeout` | 45s | 服务端读超时：超过此时间未收到任何消息判死（> 2 倍心跳周期） |
| `reconnect.base_delay` | 1s | 重连基础退避时间 |
| `reconnect.max_delay` | 60s | 重连最大退避时间（封顶） |
| `reconnect.jitter` | 0.2（±20%） | 重连随机抖动，避免风暴 |

> 服务端 `config.yaml` 中这些值作为「约定值」写入（实际代码仍读 config，但客户端与之硬编码一致，不暴露给终端用户改）。
> 协议约定以本文档为准，两端写死，避免误配。

### 4.3 心跳消息协议

心跳消息用 JSON 文本帧，统一信封结构（见「消息协议」一节）：

```json
// 客户端 → 服务端
{"type": "ping", "ts": 1730000000000}

// 服务端 → 客户端
{"type": "pong", "ts": 1730000000000}
```

> 约定：心跳 `ping`/`pong` 是**纯应用层消息**（走 WebSocket 数据帧），
> 不依赖 WebSocket 协议层的 Ping/Pong 帧。
> 理由：应用层心跳跨语言/跨框架语义一致，且能顺带携带业务数据；协议层帧由底层库维护，作为补充。

### 4.4 心跳时序图

```
客户端                          服务端
   |──── ping ───────────────────>|   (t=0)
   |<────────────── pong ─────────|   (t<10s 正常)
   |                              |
   |        ... 20s 后 ...        |
   |──── ping ───────────────────>|   (t=20)
   |        (pong 超时未回)        |
   |  [判定服务端不可达 → 重连]     |
   |                              |
   |  (服务端侧：45s 未收任何消息)   |
   |  [判定连接死亡 → 主动关闭回收]  |
```

---

## 五、断线检测

### 5.1 客户端侧检测

客户端在以下任一情况判定「连接已断」，触发重连：

1. 发出 `ping` 后 `CLIENT_PONG_TIMEOUT` 内未收到 `pong`；
2. WebSocket 收到 `close` 帧；
3. 底层连接报错（网络异常、异常关闭）。

### 5.2 服务端侧检测

服务端在以下情况判定「连接已死」，主动清理：

1. **读超时**：`SERVER_READ_TIMEOUT` 内未收到该连接任何消息（含心跳）；
2. 收到客户端 `close` 帧；
3. 底层发送/接收抛异常。

> 服务端检测的触发点：用 asyncio 的 `asyncio.wait_for(ws.receive_text(), timeout=...)` 实现读超时。

---

## 六、自动重连

### 6.1 重连流程（客户端）

1. 检测到断线 → 进入「重连状态」；
2. 按**指数退避 + 随机抖动**计算等待时间：
   `delay = min(BASE * 2^attempt, MAX) * (1 ± jitter)`；
3. 等待后重新发起 WebSocket 握手（携带设备标识）；
4. 成功 → 重置 attempt=0；失败 → attempt+1 继续退避；
5. 达最大重试次数/长时间仍失败 → 降级（可选：提示用户、改用轮询）。

### 6.2 为什么指数退避 + 抖动

- **指数退避**：避免在服务端故障时所有客户端以固定频率疯狂重连，把服务端打死。
- **随机抖动**：打散重连时间点，避免"同时断线 → 同时重连"的雪崩。

### 6.3 重连与「顶旧连接」的配合

- 客户端重连时，**必须携带同一个设备标识**。
- 服务端收到新连接，发现该标识已有旧连接 → **主动关闭旧连接，接纳新连接**（见「唯一连接」）。
- 这样即使旧连接因半开未及时回收，新连接也能保证"全局只有一条活跃连接"。

---

## 七、设备唯一标识与「唯一连接」

### 7.1 设备标识（已定：系统 ANDROID_ID）

- 客户端用系统 **`Settings.Secure.ANDROID_ID`** 作为 `device_id`，**重装 APK 不变**（区别于自生成 UUID），无需权限。
- 特性：同一台设备 + 同一个 App 安装，`device_id` 恒定；恢复出厂设置会变（可接受）。
- 昵称 `device_name` 由用户自定义，随握手/注册上报，服务端仅作展示名（`GET /api/devices` 返回），不参与唯一性判断。

### 7.2 唯一连接约束（核心业务规则）

> **同一个 `device_id`，服务端全局只保留一条活跃连接。新连接顶掉旧连接。**

实现要点：

1. 服务端维护 `device_id -> Connection` 的映射表（内存字典）。
2. 新连接建立（完成握手、拿到 `device_id`）时：
   - 若映射表中已有该 `device_id` 的活跃连接 → **先关闭旧连接**（发 close 帧并清理），再登记新连接；
   - 否则直接登记。
3. 连接关闭时，从映射表移除（仅当映射表里该 `device_id` 指向的正是这条连接时才移除，避免误删新连接）。

### 7.3 并发安全（顶旧连接的竞态）

> 企业级必须处理并发：两个连接几乎同时握手同一个 `device_id`。

- 用 `asyncio.Lock` 保护映射表的「顶旧 + 登记」操作，保证原子性。
- 或：映射表存 `Connection` 对象，顶旧时通过「比对对象引用」判断，避免关错。

---

## 八、连接状态机

每个连接在服务端经历以下状态：

```
        ┌──────────────┐
  握手   │  CONNECTING   │  客户端发起 WS 握手
────────>│              │
        └──────┬───────┘
               │ 握手完成，拿到 device_id（顶旧 + 登记）
               ▼
        ┌──────────────┐
        │   ACTIVE      │  正常通信（心跳周期交互）
        │              │
        └──┬────────┬──┘
   超时/close│        │ 顶旧（被新连接顶掉）
             ▼        ▼
        ┌──────────────────┐
        │    CLOSED         │  清理资源、从映射表移除
        └──────────────────┘
```

| 状态 | 说明 |
|---|---|
| CONNECTING | 握手进行中 |
| ACTIVE | 活跃，收发消息 + 心跳 |
| CLOSED | 已关闭（主动关闭 / 读超时 / 被顶旧 / 客户端断开） |

---

## 九、消息协议

统一 JSON 信封，字段预留扩展：

```json
{
  "type": "ping | pong | close | message | ...",
  "ts": 1730000000000,
  "data": { }
}
```

| type | 方向 | 说明 |
|---|---|---|
| `hello` | C→S | 握手（携带 device_id + 可选 token + 可选 device_name 昵称 + 可选 uptime_seconds 进程运行时长） |
| `ping` | C→S | 心跳请求 |
| `pong` | S→C | 心跳响应 |
| `close` | 双向 | 主动通知关闭（顶旧/踢下线时服务端发，`reason`=replaced/server_kick） |
| `message` | 双向 | 业务消息（服务端推送、客户端上报） |
| `command` | S→C | 命令下发（data 含 command/command_id/params） |
| `command_result` | C→S | 命令执行回执（data 含 command_id/success/message） |

> 顶旧连接时，服务端先给旧连接发一条 `{"type":"close","data":{"reason":"replaced"}}`，再关闭，让客户端知道自己被顶掉了（而非误判为网络故障反复重连）。

### 9.1 多客户端对应关系（核心原则）

> 本项目是「一服务端对多客户端」，**所有通信必须区分对应关系**，`device_id` 是全局唯一键。

- 服务端下发通知/命令 → 精确到 `device_id`（命令接口一次一个设备；广播用 push）。
- 客户端上报（日志/回执）→ 请求体携带 `device_id`；命令回执带 `command_id` 关联原命令。
- 设备昵称 `device_name` 仅作展示名（`GET /api/devices` 返回，含在线状态），不参与唯一性判断。

---

## 十、异常与边界场景清单（实现时必须覆盖）

1. 客户端握手后立刻断开（没发任何心跳）→ 服务端读超时回收。
2. 同一设备两条连接并发握手 → 顶旧连接原子性。
3. 顶旧连接时旧连接正在发消息 → 关闭操作容错（捕获异常）。
4. 服务端重启 → 所有连接断开，客户端重连重建映射表。
5. 时钟偏移 → 心跳只做「活性判断」，不依赖对端时间戳精确一致。
6. 消息乱序/粘连 → 每条消息独立 JSON，按帧边界解析（FastAPI 的 `receive_text` 天然按消息边界）。
7. 大消息/慢客户端 → 设置单条消息大小上限，防止内存被打爆。

---

## 十一、目录结构（详见 directory-convention.md）

后端代码位于仓库顶层 `server/`，分层结构：

```
server/
├── main.py                # ★ 唯一启动入口（uv run main.py）
├── README.md
├── pyproject.toml
├── uv.lock
├── .python-version
├── app/
│   ├── main.py            # FastAPI 应用工厂 + 依赖注入（不直接启动）
│   ├── config.py          # 配置加载（读 config.yaml + 环境变量覆盖）
│   ├── api/               # 路由包（专门的路由目录，按功能拆模块 + 子路由包）
│   │   ├── router.py      #   根路由聚合器（统一挂载 ws + v1）
│   │   ├── ws.py          #   WebSocket 路由（握手/鉴权/心跳/读超时）
│   │   └── v1/            #   REST v1 子路由包（按功能拆分）
│   │       ├── router.py  #     v1 版本根路由（/api 前缀）
│   │       ├── devices.py #     设备注册/统计
│   │       ├── push.py    #     消息推送
│   │       └── connections.py # 连接管理（查询/踢下线）
│   ├── core/              # 核心：连接管理器、设备注册表、状态机
│   ├── models/            # 数据模型（Connection、消息信封）
│   ├── schemas/           # Pydantic 校验模型
│   └── services/          # 业务逻辑（未来）
├── examples/              # 客户端示例（本地联调）
├── tests/
├── config.yaml            # 默认配置
└── docs/
```

### 启动方式（唯一入口）

后端**唯一**启动方式是 `server/main.py`（在 `server/` 目录下执行 `uv run main.py`）。
它内部调用 `app.main:create_app()` 构建应用，再用 uvicorn 启动，host/port 由
`config.yaml` 的 `server` 配置组决定。不要在别处直接 `uvicorn` 拉起服务。

### 路由包组织原则

路由层集中在 `app/api/`（专门的路由目录），按「功能」拆分为独立模块，并可进一步
拆成「子功能路由包」：

- 每个路由模块只做「协议适配」（解析请求 → 调核心层 → 返回响应），**不写业务逻辑**。
- 同类接口聚合成子路由包（如 `v1/` 版本包下按 devices/push/connections 拆模块）。
- 新增版本时平行建 `v2/` 包；新增功能时在对应子包加模块，并在 `router.py` 登记。
- `app/main.py` 只需 `app.include_router(api_router)` 一行，新增路由无需改 main.py。

---

## 十三、配置管理（YAML）

### 13.1 选型理由

配置用 **YAML** 而非纯环境变量 / JSON / Python 代码，理由：

1. **结构化**：心跳、重连、连接上限等参数天然分组，比扁平的 `FOO_BAR` 环境变量清晰。
2. **可读可注释**：运维人员可直接读、直接改，YAML 支持注释，便于说明每个参数的语义。
3. **可版本化**：`config.yaml` 进 Git，配置变更可审计、可回滚。
4. **多环境**：`config.yaml`（默认）+ `config.prod.yaml`（生产覆盖）+ 环境变量（最敏感项），分层叠加。

### 13.2 配置加载优先级（从低到高）

1. 代码内置默认值（`config.py` 里的 fallback）
2. `config.yaml`（仓库内默认配置）
3. 环境变量 `BRIDGE_CONFIG` 指定的额外 YAML 文件（如生产配置）
4. 环境变量（`BRIDGE_*` 前缀，覆盖单个参数，用于密钥等敏感项）

> 高优先级覆盖低优先级；环境变量仅用于少数敏感项（如 `BRIDGE_SECRET_KEY`），避免污染 YAML。

### 13.3 配置结构示例（`config.yaml`）

```yaml
# Bridge Server 配置

server:
  host: "0.0.0.0"
  port: 15160
  # 单条消息最大字节数，防止慢客户端/大消息打爆内存
  max_message_size: 65536

heartbeat:
  interval: 20               # 客户端心跳发送周期（秒）
  client_pong_timeout: 10    # 客户端 ping 后等 pong 超时（秒）
  server_read_timeout: 45    # 服务端读超时（秒），须 > 2 倍 interval

reconnect:
  base_delay: 1              # 重连基础退避（秒）
  max_delay: 60              # 重连最大退避（秒）
  jitter: 0.2                # 随机抖动比例（±20%）

connection:
  # 同一 device_id 全局只保留一条活跃连接（新连接顶旧连接）
  unique_per_device: true
  # 全局最大并发连接数（0 = 不限）
  max_connections: 10000

auth:
  enabled: false             # 是否启用设备鉴权（false = 接受任意连接，便于联调）
  token_ttl: 0               # token 有效期（秒），0 = 永久

logging:
  level: "INFO"              # DEBUG / INFO / WARNING / ERROR / CRITICAL
```

### 13.4 加载实现要点

- 用 `pyyaml` 解析 YAML + `pydantic`（`BaseModel`）做类型与取值校验，未引入 `pydantic-settings`。
- 所有配置项经 **Pydantic 校验**：类型、取值范围（如 `server_read_timeout > 2 * interval` 的约束）。
- 校验失败即启动报错（fail-fast），不静默用错误配置运行。
- 配置对象在进程启动时加载一次（`lru_cache` 单例），全局只读共享，避免运行期热更新的复杂度（热更新列为后续扩展）。

---

## 十四、消息推送（服务端主动下发）

### 14.1 目标

业务侧（管理后台 / 其他服务）通过 REST 接口，向一个或多个在线的设备主动推送消息。

### 14.2 实现机制

- `Connection` 模型注入 ``send`` 回调（端点层绑定到 ``ws.send_text``），核心层与 WebSocket 对象解耦。
- `ConnectionManager.send_to_device(device_id, raw_json)` 向单设备推送；`broadcast(raw_json)` 向全量广播。
- 推送消息类型为 ``message``，载荷放在 ``data`` 字段。

### 14.3 推送接口

```
POST /api/push
{"device_ids": ["dev-1", "dev-2"], "payload": {"msg": "..."}}
→ {"total": 2, "delivered": 1, "failed": ["dev-2"]}
```

- ``delivered``：成功送达的连接数；``failed``：无活跃连接或发送失败的 device_id 列表。
- 仅推送当前在线设备，离线设备不排队（排队/离线存储列为后续扩展）。

### 14.4 设备端行为（重要）

``message`` 帧是**预留的业务扩展通道**：客户端 WS 层只记日志，**业务层当前不解析、
不在设备上显示任何内容**（与 send_notification 命令不同——那是完整落地的通知命令）。
管理后台「消息推送」页本质是这条通道的调试/测试入口（自定义 JSON 透传）。
后续如需「服务端 → 设备自定义消息展示」，在客户端 WS 消息分发处挂业务处理器即可，
服务端无需改动。

---

## 十五、设备鉴权与注册

### 15.1 鉴权模型

- 客户端首次启动时调用 ``POST /api/devices/register`` 注册，获得 ``device_id + token``。
- 之后 WebSocket 握手时，``hello`` 消息携带 ``token``，服务端校验通过才允许建立连接。
- token 用 ``secrets.token_hex(32)`` 生成（64 位十六进制），不可猜测。

### 15.2 配置开关

```yaml
auth:
  enabled: false   # false = 接受任意连接（便于联调）；true = 强制鉴权
  token_ttl: 0     # token 有效期（秒），0 = 永久
```

### 15.3 握手消息（含 token）

```json
{"type": "hello", "ts": ..., "data": {"device_id": "dev-1", "token": "xxx"}}
```

- 鉴权失败：服务端关闭连接，close code ``4004``（reason=unauthorized）。

### 15.4 存储

设备 token 持久化到 MySQL（`core/device_token_store.py` 的 ``DeviceTokenStore``，
表 ``device_tokens``），``DeviceRegistry`` 以内存 dict 作一级缓存、DB 为持久层：

- **写穿透**：注册（`register`）/ 昵称更新（`update_name`）/ 删除（`remove`）/
  活跃时间刷新（`authenticate` 成功）同步落库。
- **读穿透**：`authenticate` 内存未命中时查 DB 并回填缓存。
- **启动预热**：`registry.load_persisted()` 全量回填内存。
- **降级容错**：MySQL 不可达时自动降级为纯内存模式（注册/鉴权照常，仅重启后设备需重新注册），
  绝不拖垮鉴权主链路。

这样服务重启后已注册设备 token 依然有效，杜绝「服务重启→内存注册表清空→客户端旧 token
全部 4004 重连循环」的故障。客户端侧另有「4004 自动重注册」兜底（见 `docs/api/ws-client.md`
第九节），覆盖 MySQL 故障等异常场景的双保险。

---

## 十五·补充：管理后台鉴权（Admin Auth）

设备鉴权只管「设备连接」，**管理接口**需要独立的「管理员鉴权」，二者解耦。

### 鉴权模型

- `config.yaml` 新增 `admin` 组：`username` / `password` / `token_ttl`（静态配置，不引入数据库）。
- `POST /api/admin/login`：校验账号密码，返回 admin token（`secrets.token_hex` 生成）。
- 管理接口统一通过 `require_admin` 依赖校验 `Authorization: Bearer <token>`，失败返回 401。
- 密码用 `secrets.compare_digest` 常量时间比较，避免时序攻击。

```yaml
admin:
  username: "admin"        # 登录账号
  password: "admin123"     # 登录密码（生产环境用环境变量覆盖）
  token_ttl: 86400         # token 有效期（秒），0 = 永久
```

### 鉴权边界（关键）

| 接口 | 是否要求管理鉴权 |
|---|---|
| `POST /api/devices/register`（设备注册） | ❌ 否（客户端调用） |
| `POST /api/logs`（日志上传） | ❌ 否（客户端调用） |
| `GET /api/music/{filename}`（音乐下载） | ❌ 否（客户端调用；要求设备头鉴权，与 WS hello 同一套注册表校验） |
| 其余 `/api/devices` `/api/commands` `/api/logs`(读/删) `/api/push` `/api/connections` | ✅ 是 |

### 管理侧实时推送（WebSocket）

命令回执除写入 CommandStore 外，还通过 **WebSocket** 实时推送给管理后台：

- `core/admin_event_bus.py`：内存事件总线（连接登记/广播/断开）。
- `WS /api/admin/ws`：管理侧实时事件通道，token 优先取 `Authorization: Bearer`，也兼容 `?token=` query 参数。
- `ws.py` 收到 `command_result` 时同步 `publish` 事件。

选用 WebSocket 而非 SSE，是为后续复杂功能与双向交互预留能力。

---

## 十六、REST 管理接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices/register` | 注册设备，返回 device_id + token（含昵称） |
| GET | `/api/devices` | 列出所有已注册设备（含昵称 + 在线状态，供管理侧挑设备） |
| POST | `/api/push` | 向设备推送消息 |
| POST | `/api/commands` | 向指定设备下发命令（如 upload_log） |
| GET | `/api/commands` | 列出全部命令记录（含回执状态） |
| GET | `/api/commands/{command_id}` | 查询单条命令状态（pending/success/failed） |
| GET | `/api/connections` | 活跃连接列表 + 数量（含 uptime_seconds 进程运行时长） |
| GET | `/api/connections/log` | 设备上下线记录（时间倒序，可按 device_id 过滤） |
| POST | `/api/devices/{id}/disconnect` | 强制断开指定设备（服务端踢下线） |
| GET | `/api/devices/count` | 已注册设备总数 |
| GET | `/api/devices/{device_id}/music-state` | 查询设备音乐播放状态（playing/paused/idle/unknown，见 api/commands/control.md 状态上报节） |
| POST | `/api/logs` | 上传日志（客户端上报诊断日志） |
| GET | `/api/logs/{device_id}` | 读取某设备日志列表 |
| GET | `/api/logs` | 列出有日志的设备摘要（昵称 + 日志数 + 最新时间 + 在线状态） |
| DELETE | `/api/logs/{device_id}` | 清除某设备日志 |
| GET | `/api/music/{filename}` | 下载共享音乐文件（play_music 裸文件名音源，详见 api/commands/control.md） |
| GET | `/health` | 健康检查 + 运行统计（连接数/注册数/运行时长） |

### 16.1 设备上下线记录

为支持管理后台展示「设备上下线时间轴」（如 1 点上线 → 跑半小时断网掉线 → 2 点再上线 → 3 点下线），服务端新增 `core/connection_event_store.py`：

- 内存环形存储（默认保留 500 条，超限丢最旧），与日志/命令存储一致，重启清空（当前阶段可接受）。
- 记录时机：**上线**（`register` 成功）记 `online`；**下线**（读超时/主动关闭/被顶替/服务端踢/客户端断开/异常）记 `offline` + 原因。
- 下线原因枚举：`closed`（主动）、`read_timeout`（读超时）、`replaced`（被顶替）、`server_kick`（服务端踢）、`disconnected`（客户端断开）、`error`（异常）。
- 查询接口：`GET /api/connections/log?device_id=&limit=`，时间倒序返回。

---

## 十七、命令下发（服务端主动下发 → 客户端执行）

### 17.1 目标

管理侧通过 REST 接口向**指定设备**下发一条命令，客户端收到后执行并回执，实现「服务端 → 客户端」的主动控制（如远程触发日志上传）。

### 17.2 接口

```
POST /api/commands
{"device_id": "dev-1", "command": "upload_log", "params": {}}
→ {"command_id": "<uuid>", "device_id": "dev-1", "command": "upload_log", "delivered": true}
```

- `command` 必须在白名单 `ALLOWED_COMMANDS = {"upload_log"}` 内，否则 400。
- 设备必须在线（有活跃连接），否则 404。
- 下发成功后返回 `command_id`，供管理侧关联客户端回执（`command_result` 消息）。

### 17.3 命令白名单

| 命令 | 说明 |
|---|---|
| `upload_log` | 客户端采集诊断日志并上传到后端 |

### 17.4 消息流

```
管理侧 ──POST /api/commands──▶ 服务端 ──command 帧──▶ 客户端（执行 upload_log）
                                      ◀──command_result 帧── 客户端（回执）
```

- 命令精确下发到**一个** device_id（不在此接口做广播，广播用 push）。
- 每条命令带唯一 `command_id`，客户端回执原样带回，便于关联。

### 17.5 命令回执持久化

> 服务端收到 `command_result` 回执后**持久化**（而非仅记日志），供管理侧查询命令执行结果。

- 存储：`CommandStore`（内存实现，接口可替换），`command_id → CommandRecord`。
- 生命周期：下发即登记 `pending` → 回执到达更新为 `success` / `failed`（含 result_message / result_at）。
- **限制最大数量**：默认 `max_records=200`，超限丢弃最旧记录（防内存无限堆积）。
- 查询接口：
  - `GET /api/commands` 列出全部命令记录（由旧到新）。
  - `GET /api/commands/{command_id}` 查询单条命令状态（pending/success/failed + 结果说明），不存在 404。

---

## 十八、日志上传与读取（借鉴 aurelay）

### 18.1 定位

借鉴 `references/aurelay-main` 的「诊断日志上传/读取」能力：客户端把诊断日志文本
上报到服务端，管理侧可读取/列出/清除，用于远程排查问题（无需依赖微信/邮件等外部通道）。

> 注意：aurelay 的 `DiagLog`/`DiagUploader` 是**客户端侧**实现（采集 + 上传），其服务端
> 仅约定协议（`X-API-Key` + `Authorization` 双写、HTTP 恒 200 + body code）。本后端按自身
> 架构风格重新设计服务端接口（路径用 `/api/logs`，鉴权沿用现有设备 token 机制）。

### 18.2 接口语义

- **上传** `POST /api/logs`：客户端提交 `device_id` + `content`（日志文本）+ 可选
  `device_name` / `app_version`。服务端追加一条日志，返回 `code=0`。
- **读取** `GET /api/logs/{device_id}`：返回该设备日志列表（由旧到新）。
- **列出** `GET /api/logs`：返回有日志的设备 id 列表 + 日志总数（管理侧排查入口）。
- **清除** `DELETE /api/logs/{device_id}`：清除该设备全部日志。

### 18.3 存储

- 核心层 `LogStore`（内存实现，接口可替换），`device_id -> 日志条目列表`。
- 单设备保留最近 `max_entries_per_device`（默认 50）条，防止无限堆积。
- 单条内容超长截断尾部（默认 200_000 字符），防止请求体过大。
- 后续可替换为文件落盘 / 数据库 / 对象存储。

---

## 十九、交付清单

1. 连接管理器（唯一连接 + 顶旧连接 + 状态机）
2. 心跳（客户端 ping/pong 服务端响应 + 读超时兜底）
3. 消息协议信封 + 校验
4. 配置（**YAML 加载 + Pydantic 校验**，心跳/重连/连接上限/鉴权可配）
5. **消息推送**（单播 + 广播，REST 推送接口）
6. **设备鉴权 + 注册**（token 验证，可开关，含昵称）
7. **REST 管理接口**（连接列表、设备列表、踢下线、统计、健康检查）
8. **命令下发**（REST 命令接口 + command 帧 + command_result 回执）
9. **日志上传与读取**（借鉴 aurelay，REST 日志接口 + LogStore 存储）
10. 单元测试（顶旧、超时回收、并发握手、鉴权、推送、日志、命令、设备列表）
11. 客户端示例（Python 脚本，含鉴权/心跳/重连/接收推送）

> 客户端的真实 WebSocket 实现（Android 端）属客户端工程，另立文档；本文档定义的是**协议与约束**，客户端必须遵循。

---

## 二十、ADB 一键开启无障碍（v0.1.40）

**目的**：让「Bridge 辅助服务」（模拟电源键锁屏 + 静默截屏的前置开关）不需要用户到
系统设置手动开启——管理后台设备行点「ADB开无障碍」，服务端代跑 adb 一次搞定，
**设备端零操作，授权永久有效**（重启不掉，仅卸载重装才需重做）。

**原理**（借鉴 autoxClient 的 WRITE_SECURE_SETTINGS 方案，见
`references/autoxClient/autoxTcpServer/modules/screen_recorder.js` 的
`_ensureAccessibility`）：

1. 一次性授权：`adb shell pm grant my.bridge.com android.permission.WRITE_SECURE_SETTINGS`
   —— 该签名级权限可经 adb 授予普通应用，重启不掉；
2. 直写系统设置：`settings put secure enabled_accessibility_services <追加
   my.bridge.com/my.bridge.com.access.BridgeAccessibilityService>`（保留已有服务，
   不清除其他应用）+ `settings put secure accessibility_enabled 1`
   —— 系统随即**自动绑定**无障碍服务。

**为什么浏览器做不到、服务端可以**：浏览器沙箱无法访问 USB/adb；
但管理后台背后是运行在用户电脑上的本服务端，由 FastAPI（`core/adb_helper.py`）
subprocess 代跑 adb——网页按钮 → 服务端 adb → 手机，效果等同「网页一键执行」。

**设备定位**（v0.1.41 修正，多 IP 上报）：WS 握手记录的客户端 IP 可能是**公网 IP**
（手机走流量/跨网连服务端），与手机局域网 IP 不一致，不能只靠它匹配。策略：
① **单台 adb 设备直接采用**（用户插/连的显然是目标机）——主路径；
② 多台时用设备 hello 上报的**全部 IP**（`ips` 字段，收集 NetworkInterface 全部
IPv4、wlan 优先）逐一比对，**任一命中**即确认，弹窗可手填 IP 兜底；
③ 多台且无匹配 → 报错列出设备请用户只连目标机。
弹窗顶部展示「设备确认」对照区（`GET /{device_id}/net-info`）：设备上报 IP vs
adb 已连设备及其 IP，有交集打绿标「可放心执行」，让用户**二次确认**。

**adb 版本要求**：`adb pair` 需 platform-tools ≥ 30（Windows PATH 里可能是
`C:\Windows\adb.exe` 的 1.0.32 古董版且优先级更高）。项目自带最新版
`tools/adb/platform-tools/`，`config.yaml adb.path` 显式指向；相对路径按服务端
根目录解析（`resolve_adb_path`）。新版 adb 还会经 mDNS 自动发现已配对的无线
调试设备（`adb-b<serial>-…_adb-tls-connect._tcp`），WiFi 连接常可免手动 connect。

**接口**：
- `GET /api/devices/{device_id}/net-info`（管理员鉴权）——设备网络信息：
  `{device_ips, adb_devices:[{serial, ip?}]}`，供弹窗交叉确认；
- `POST /api/devices/{device_id}/adb-enable-accessibility`（管理员鉴权）——直接模式，
  前提是该手机已被 adb 识别（USB 线 / 已 connect 过）；
- `POST /api/devices/{device_id}/adb-wifi-enable-accessibility`（管理员鉴权）——
  **WiFi 一条龙**：请求体 `{connect_port, pair_port?, pair_code?, ip?}`（连接端口必填 =
  手机「无线调试」主界面端口；首次使用再带配对端口+配对码），IP 缺省自动获取——
  对设备上报的**每个候选 IP 逐个尝试** pair/connect，任一成功即确认，无需手填。

两接口均返回 `{success, serial, device_ips, steps[]}`；失败时 `success=false` +
可读 `error` + 逐步骤 `steps` + `adb_devices`（已连设备及 IP 供对照）
（HTTP 仍 200，便于后台弹窗）。adb 路径可配
`config.yaml adb.path`（默认 PATH 里的 `adb`）。后台入口：设备列表
「ADB开无障碍」按钮 → 弹窗二选一模式（自动发现（推荐）：adb 已连设备——数据线或已配对的无线均算，无需填写；WiFi 无线调试：手填 IP/端口/配对码，发现失败时兜底）。

**前提与边界**：手机需连到运行服务端的电脑——数据线（手机允许一次「USB 调试」）
或 WiFi 无线调试（Android 11+ 首次需配对码；成功一次后授权永久有效，不必再连）；
adb 未安装时错误信息给出安装指引。

## 二十一、媒体流转发与上报配置（v0.1.49）

### 媒体流转发（流式录像/录音实时通道）

- 客户端流式分片（WS `message` 通道，event=`media_stream`）→ `app/core/stream_hub.py`
  （StreamHub）→ **管理后台专用流 WS** `WS /api/admin/stream/{device_id}?token=`
  （v1/admin.py，二进制帧转发，服务端完成 base64 解码）。
- 语义：**实时转发不缓存**——无订阅者时分片直接丢弃；历史完整文件由命令回执后
  的正常媒体上传兜底。多订阅者各持独立队列（多后台同看一路）。
- 起始帧（带 info）/ 结束帧（is_end）额外经 AdminEventBus 广播 `stream_status`
  轻量事件；设备断连时补发结束事件（播放器收尾）。
- **订阅时机双保险（2026-10-27）**：① `stream_status` 事件驱动开卡片（原有）；
  ② 命令页下发流式命令（record_video/record_audio 开 stream、screen_live）成功后
  **立即预连**流 WS（`StreamPlayerCard.startManual`）——实测管理 WS 短暂断线/
  事件丢失会导致整段录制无实时画面（设备日志订阅者=0），预连不再依赖事件通道
  （分片走专用流 WS，与事件通道解耦）。事件通道正常时 `onStreamStatus` 与
  预连幂等互不冲突。
- 流式产物（flv/ts/aac）流结束后照常走 `POST /api/media` 上传，媒体扩展名
  白名单已含（`media_store.MEDIA_EXTENSIONS`）；MIME 映射见 media.py `_media_type_of`。
- 消费方：视频 mpegts.js（WS 直播源）/ 音频 Web Audio 逐组解码——详见
  `docs/api/commands/media.md`「流式通道」。

### 设备上报配置（主动周期上报开关）

- `app/core/report_config_store.py`：按设备 JSON 落盘 `data/report_config.json`
  （location_enabled / location_interval_min / sms_enabled，默认全开、10 分钟）。
- REST：`GET/PUT /api/devices/{device_id}/report-config`（管理鉴权）；
  PUT 成功后经 WS `message` 下发 `event=config_update` 实时生效。
- 客户端握手后服务端主动推一次当前配置（离线期间错过的推送兜底）。
