# 客户端 WebSocket 对接设计

> Bridge Android 客户端对接后端服务的权威设计文档。
>
> 后端协议与约束见 [../server/design.md](../server/design.md)，本文档定义**客户端侧**如何落地这些协议，
> 客户端所有实现必须遵循本文档。

---

## 一、对接范围（服务端已实现 → 客户端需补齐）

| # | 服务端能力 | 服务端入口 | 客户端需实现 |
|---|---|---|---|
| 1 | WebSocket 长连接管理 | `/ws` | 握手 hello、心跳 ping/pong、断线检测、指数退避重连 |
| 2 | 设备注册与鉴权 | `POST /api/devices/register` | ANDROID_ID 作 device_id、注册拿 token、握手携带 token + 昵称 |
| 3 | 消息推送接收 | `/ws` 的 `message` 帧 | 接收 `message` 类型并回调业务层 |
| 4 | 诊断日志上传 | `POST /api/logs` | 日志采集 + 上传（借鉴 aurelay DiagLog/DiagUploader） |
| 5 | 命令下发与执行 | `/ws` 的 `command` 帧 + `POST /api/commands` | 接收 `command` → 查注册表执行 → `command_result` 回执 |

> 管理侧接口（`GET /api/connections`、`POST /api/devices/{id}/disconnect`、`GET /api/logs`、`GET /api/devices` 等）
> 面向「管理后台」，**不属客户端**，不实现。

---

## 二、多客户端对应关系（核心原则）

> 本项目是「一服务端对多客户端」，**所有通信必须区分对应关系**，device_id 是全局唯一键。

| 场景 | 区分方式 |
|---|---|
| 服务端下发通知/命令 → 客户端 | 精确到 `device_id`（命令接口一次只下发一个设备；广播用 push） |
| 客户端上报（日志/回执）→ 服务端 | 请求体携带 `device_id`；命令回执带 `command_id` 关联原命令 |
| 管理侧「认出」设备 | 服务端维护 `device_id → 昵称` 映射，`GET /api/devices` 返回列表（含昵称 + 在线状态） |

- `device_id`：系统 ANDROID_ID，全局唯一，**不参与展示**，只作键。
- 昵称 `device_name`：用户可自定义的展示名，缺省用 device_id，**仅展示用，不参与唯一性判断**。

---

## 三、模块归属（遵循 directory-convention.md）

| 模块 | 新增内容 |
|---|---|
| `core:core-network` | WebSocket 客户端、消息信封、命令框架、REST 接口（设备注册 / 日志上传） |
| `core:core-storage` | `DeviceStore`（ANDROID_ID + 昵称 + token 持久化） |
| `core:core-common` | `DiagLog`（诊断日志采集，借鉴 aurelay） |
| `app` | Hilt 装配、连接管理器启动、命令执行器注册、设置页 UI |

依赖方向不变：`app → core-*`；`core-network` 依赖 `core-common` + `core-model`；
`core-storage` 独立（DataStore）。

---

## 四、协议对接要点（与后端一一对应）

### 4.1 消息信封

统一 JSON 信封，字段与后端 `Envelope` 完全一致：

```json
{"type": "hello|ping|pong|close|message|command|command_result", "ts": 1730000000000, "data": {…}}
```

- `ts` 为发送方毫秒时间戳（`System.currentTimeMillis()`）。
- 客户端用 Gson 序列化/反序列化；`data` 为可选 `JsonObject`。

### 4.2 设备标识（ANDROID_ID）

- `device_id` 用系统 **`Settings.Secure.ANDROID_ID`**，**重装 APK 不变**（区别于自生成 UUID），无需权限。
- 极端情况 ANDROID_ID 为空（罕见）→ 回退 `fallback-{MANUFACTURER}-{MODEL}-{DEVICE}`（同一设备恒定）。
- 恢复出厂设置会变（可接受）。

### 4.3 握手（hello）

连接建立后首条消息必须是 `hello`：

```json
{"type":"hello","ts":…,"data":{"device_id":"<android_id>","token":"<可选>","device_name":"<可选>","uptime_seconds":3600}}
```

- `device_id` 来自 `DeviceStore.deviceId()`（ANDROID_ID）。
- `token` 来自设备注册接口返回；后端 `auth.enabled=false` 时 token 可为空，但仍先尝试注册拿 token（幂等）。
- `device_name` 昵称（可选，缺省用 device_id），服务端据此更新设备列表展示名。
- `uptime_seconds` 客户端进程运行时长（秒，可选）：默认用 `SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()` 计算，供管理后台展示「APP 已运行 XX」。

### 4.4 心跳（客户端主导 + 硬编码）

- 每 `20s` 发送 `ping`；`ping` 后 `10s` 内未收到 `pong` → 判定服务端不可达，断开重连。
- **心跳/重连参数客户端与服务端都硬编码，不提供配置入口**（见「六、配置」）。

### 4.5 断线检测与重连

客户端判定断线的触发点（与 design.md 第五节一致）：
1. `ping` 后超时未收到 `pong`；
2. 收到 `close` 帧（含服务端顶旧/踢下线的通知）；
3. 底层连接报错。

重连策略（与 design.md 第六节一致）：
- `delay = min(base * 2^attempt, max) * (1 ± jitter)`，默认 base=1s、max=60s、jitter=±20%。
- 连接成功 → attempt 归零。
- 收到 `close` 且 `reason=replaced` → 被同设备新连接顶掉，**停止重连**。
- `reason=server_kick` → 服务端主动踢下线，**不自动重连**。

### 4.6 唯一连接（顶旧）

- 客户端始终携带同一个 `device_id`，天然满足「同设备全局一条连接」。
- 客户端被顶旧（`reason=replaced`）时停止重连，交由新连接接管。

---

## 五、命令框架（服务端下发 → 客户端执行）

### 5.1 协议

服务端经 WS 下发 `command` 消息，客户端执行后回执 `command_result`：

```json
// 服务端 → 客户端
{"type":"command","data":{"command":"upload_log","command_id":"<uuid>","params":{}}}

// 客户端 → 服务端（回执）
{"type":"command_result","data":{"command_id":"<uuid>","command":"upload_log","success":true,"message":"上传成功"}}
```

### 5.2 命令白名单（客户端与服务端一致）

| 命令 | 说明 |
|---|---|
| `upload_log` | 采集诊断日志并上传到后端（`POST /api/logs`） |

- 服务端 `ALLOWED_COMMANDS = {"upload_log"}` 白名单，客户端命令注册表与之保持一致。
- 收到未注册/不支持的命令 → 回执 `success=false`（不回执会阻塞服务端，须兜底）。

### 5.3 客户端命令执行器

- 命令执行器实现 `CommandExecutor`（`suspend fun execute(command): CommandResult`），注册到 `BridgeWebSocketClient.commandExecutors`。
- `upload_log` 执行器在 app 层 `CommandModule` 注册：`DiagLog.dump()` 采集 → `BridgeApiClient.uploadLog()` 上传。
- 执行在协程（IO）中进行，异常统一转 `success=false` 回执，不抛。

---

## 六、配置项（心跳硬编码，服务地址可配）

> **关键决策（用户确认）**：心跳参数必须客户端 ≤ 服务端读超时，否则服务端必判客户端离线。
> 若两端都可自由改，极易配置不一致导致误判。故**心跳/重连参数客户端与服务端都硬编码，不提供任何配置入口**。

| 参数 | 值（硬编码，两端一致） | 说明 |
|---|---|---|
| 心跳周期 | 20s | 客户端发 ping 周期；服务端读超时 45s > 2×20s |
| pong 超时 | 10s | 客户端 ping 后等 pong 超时 |
| 重连退避 | 1s → 60s | 指数退避封顶 |
| 重连抖动 | ±20% | 避免重连风暴 |
| 服务地址 | 默认 `10.0.2.2:15160` | **可配置**（见下） |

### 6.1 服务地址可配置（持久化 + 重连）

- 心跳/重连参数硬编码不变；**仅服务地址（host + port）可配置**。
- 运行时地址由 `ServerAddress` 单例持有（默认 `10.0.2.2:15160` 模拟器回环，真机改为服务器地址）。
- 持久化：`DeviceStore` 保存 host/port 到 DataStore（key：`server_host` / `server_port`），重装/重启后仍生效。
- 启动加载：连接编排器 `start()` 时先从 DataStore 读入 `ServerAddress`，再发起连接。
- 修改生效：设置页「服务地址」编辑后 → 保存 DataStore → 更新 `ServerAddress` → 调 `BridgeWebSocketClient.reconnect()` 用新地址重连。
- REST 与 WS 共用同一 host/port：`ServerAddress.wsUrl()` 与 `restBaseUrl()` 由 host+port 拼出。

#### 服务地址合法性校验

- 校验函数集中在 `ServerAddress.validate(host, port)`（返回 null 合法，否则中文错误提示），UI 与 ViewModel 复用。
- 规则：
  - host 非空；不允许协议前缀（`http://`/`ws://`）、路径、反斜杠、冒号（IPv6 用 `[::1]` 方括号形式）、空白。
  - port 非空、纯数字、范围 `1~65535`。
- 交互：弹窗内点击「保存」时本地校验，非法则红字提示且不关闭弹窗，合法才保存并重连。

---

## 七、日志上传（借鉴 aurelay）

- 借鉴 aurelay `DiagLog`（采集）+ `DiagUploader`（上传）语义，精简实现。
- 上传字段对齐后端 `LogUploadRequest`：`device_id` / `content` / `device_name` / `app_version`。
- 单条内容超长截尾（保留尾部 200_000 字符，与后端 `max_content_chars` 对齐）。
- 三种触发：① 设置页「上传诊断日志」手动；② 收到 `upload_log` 命令自动；③ **崩溃自动上报**。

### 7.1 崩溃自动上报

- `CrashReporter`（app 层）通过 `Thread.setDefaultUncaughtExceptionHandler` 安装全局处理器。
- 崩溃时：记录崩溃信息到 `DiagLog` → 采集（环境信息 + 崩溃堆栈 + 最近日志缓冲）→ 独立线程异步上传 → 交回系统默认处理器。
- 尽力而为：上报失败仅记日志，绝不阻塞/改变崩溃流程。
- **强制开启**，不提供关闭入口（用户确认）。

---

## 八、设置页入口

设置页新增「连接」二级 Tab，提供：
1. **连接状态**（实时，来自 WS 客户端 `StateFlow<WsConnectionState>`）
2. **设备标识**（只读，ANDROID_ID）
3. **设备昵称**（可编辑，缺省 device_id）
4. **服务地址**（可编辑 host+port，改后自动重连）
5. **上传诊断日志**（手动触发）

---

## 九、边界与容错

1. 握手超时/失败 → 进入重连退避，不崩。
2. `hello` 被拒（close code 4002/4004）→ 记录日志，停止重连（参数错误不重试）。
3. 网络切换（WiFi↔移动网络）→ 底层报错触发重连。
4. 进程被保活服务唤醒但网络未就绪 → 重连退避自动等待。
5. 推送回调业务层未实现 → 默认仅记录日志，不抛异常。
6. 收到未注册命令 → 回执 `success=false`，不抛。
7. 服务地址非法（空 host/port）→ 拒绝保存，提示，不触发重连。

---

## 十、TODO（后续）

- 命令回执的「实时通知管理侧」机制（当前持久化后由管理侧主动查询，未做实时推送；需等管理后台确定接收方式后一起定）。
