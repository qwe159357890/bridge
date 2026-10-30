# ESP32 继电器项目集成调研报告

> 调研目标：评估把参考项目「ESP32 继电器控制」集成进 Bridge 客户端（替换「发现」页）的可行性、方案与工作量。
> 参考源码：`references/esp32/`、`references/relay-control/`、`references/server/`。
> 调研日期：2026-10-30。

---

## 〇、落地状态（2026-10-30 已实现，含后续多轮细化）

方案 A（客户端直连参考中继服务器）**已按用户决策落地实现并多轮细化**，编译通过：

| 决策点 | 结论 |
|---|---|
| Tab 命名 | 「发现」→「智能控制」 |
| 链路 | 方案 A：客户端主动直连参考中继服务器（服务端零改动） |
| 视觉 | 统一 Bridge 浅色 Compose 风格，但**还原原项目的 SVG 线框图标 + 电源环光晕 + 粒子特效等细节**（浅色淡色呈现，不搬深色背景/光球） |
| 范围 | 原功能全做（单设备卡片 + 窗格多设备 + 开关 + 状态 + 确认 + 设置 + 自动重连） |
| 硬件 | 无 ESP32 实体，严格照源码移植，测试由用户手动 |
| 连接生命周期 | **省电优先：进入「智能控制」页才连接，离开页面立即 `client.stop()` 释放**（页面少用，不追求重连低延迟） |

**实现产物**（`app/src/main/java/my/bridge/com/`）：

| 文件 | 职责 |
|---|---|
| `switch/RelaySwitchProtocol.kt` | 协议常量 + 消息类型 + 端口→GPIO 映射 |
| `switch/RelaySwitchClient.kt` | OkHttp WS 客户端（连接/订阅/控制/重连/心跳判死/generation 防竞态） |
| `switch/RelaySwitchPrefs.kt` | 持久化（服务器/API Key/设备/端口/窗格模式） |
| `switch/RelaySwitchState.kt` | UI 状态模型（设备列表/状态缓存/连接态） |
| `ui/RelaySwitchIcons.kt` | SVG 线框图标（`PathParser` 解析原项目原始 path 数据，1:1 还原） |
| `ui/RelaySwitchPages.kt` | Compose 页面（单设备卡 + 窗格 + 设置弹窗 + 二次确认） |

**接入改动**：
- `ui/MainScreen.kt`：主页 Tab「发现」→「智能控制」，指向 `RelaySwitchPage()`。
- `ui/SettingsPages.kt`：连接页新增「智能控制」设置分区（服务器/设备/端口/API Key）。
- `ui/HomePages.kt`：删除死代码 `DiscoverList()`（原「发现」假数据占位）。

**原型产出**：`assets/design/ui-mockup-relay-switch.html`（手机壳 SVG 高保真原型，单设备卡 + 窗格每行两列卡片 + 二次确认 + 设置弹窗，图标内联 `<use>` 引用原项目原始 `d` 数据）。

### 落地期间的关键细化决策（多轮讨论定版）

1. **窗格布局**：参考项目 `pane-grid` 移动端 CSS 是 `grid-template-columns: repeat(2, 1fr)`，即**每行两列卡片网格**（非列表）。每张窗格卡结构与单设备卡同构（图标环 + 名称 + 端口 + 状态徽章 + 开关按钮），仅更紧凑。

2. **按钮配色语义（易错点，已纠正）**：按钮颜色表示「**点击后要执行的动作**」，严格照参考项目 `btn-turn-on`/`btn-turn-off`：
   - 已开启 → 按钮「关闭设备」用**绿色** `#2E9E5B`（`btn-turn-off`）
   - 已关闭 → 按钮「开启设备」用**红色** `#E5484D`（`btn-turn-on`）
   - 等待/获取中 → 灰色禁用
   > 这与「涨红跌绿」无关，是参考项目深色科技风的配色逻辑（绿=关闭/安全、红=通电/危险）。

3. **图标还原（易错点，已重做）**：原项目图标是作者**手写内联 SVG path 字符串**（`main.ts` 的 `ICONS` 常量，24×24 viewBox、`fill=none`、`stroke=currentColor`、`stroke-width=2`、`stroke-linecap/linejoin=round`），**不是**第三方图标库、不是 emoji、不是图片。还原时必须用原项目**原始 `d` 数据**：
   - HTML 原型：内联 `<use>` 引用原 `d` 字符串。
   - Compose：`PathParser().parsePathString(d).toPath()` 解析同一份 path，`stroke-width=2`（不是 2.5）。
   - dasharray 区分：`powerOff` 用 `3 3`，等待态圆用 `4 4`。
   - 早期手写 `moveTo/arcTo` 估坐标的方案已废弃（弧线/问号点走样）。

4. **连接生命周期（省电优先）**：`DisposableEffect` 进入页面 `client.start()`，`onDispose` 里 `client.stop()` 释放（`intentionalClose=true` 防幽灵重连、中断重连线程、`httpClient=null`）。参考项目是单页 Web 无「切 Tab」概念，Bridge 按页面级短连接实现，**省电优先、不追求重连低延迟**。

---

## 一、参考项目到底是什么

结论先行：**它不是一个 uniapp 项目，而是「一个 Vue3+Vite 的单页 Web 前端 + 一个 FastAPI WebSocket 中继服务器」的组合**。`references/esp32/` 里没有 uniapp 源码（`.vue`/`pages.json`），只有 HBuilderX 把 `relay-control` 编译后的 H5 产物（`index.html` + `assets/index-*.js` + `css/img/js`），其 `index.html` 引用的 `index-D4EsdMJb.js` 与 `relay-control/dist/` 里的文件同名同源。

### 1.1 三个目录的真实关系

| 目录 | 真实身份 | 内容 |
|---|---|---|
| `references/relay-control/` | **前端源码（可读）** | Vue3 + Vite + TS，单文件 `src/main.ts` 承载全部逻辑（约 1180 行原生 DOM 操作，无框架组件） |
| `references/esp32/` | **前端编译产物（H5/APK 壳）** | HBuilderX 打包的 H5 资源 + 两个 APK；`unpackage/` 是缓存 |
| `references/server/` | **中继服务器源码（可读）** | FastAPI，约 15 个业务 `.py` 文件，`devices.yaml` 配置 |

> 注意：`references/server/.venv/` 是完整虚拟环境（几百个依赖包），只读借鉴时忽略它，业务代码就是根目录那十几个 `.py`。

### 1.2 系统拓扑

```
ESP32 硬件（继电器板） ──WS /ws/device/{device_id}──► 中继服务器(server/)
                                                          ▲
                                                          │ WS /ws/client
Web前端(relay-control) / 手机App ──────────────────────────┘
```

- **设备端**（ESP32，参考项目未提供源码，只有协议约定）：连 `/ws/device/{id}`，上报 `status`、响应 `set`/`get_status`。
- **客户端**（Web/App）：连 `/ws/client`，`subscribe` 订阅设备、`set` 控制继电器。
- **服务器**：负责设备↔客户端消息路由、状态缓存、上下线广播、电平反转。

---

## 二、参考项目核心功能清单

### 2.1 服务器端（FastAPI）

| 能力 | 实现 |
|---|---|
| 设备 WS 端点 | `/ws/device/{device_id}`（ESP32 连接） |
| 客户端 WS 端点 | `/ws/client`（前端连接） |
| 设备列表 | `GET /api/devices` |
| 状态查询 | `GET /api/status`、`GET /api/status/{id}` |
| 继电器控制 | `POST /api/set/{id}`（body: `{pio, value}`） |
| 重连 | `POST /api/reconnect/{id}`、`POST /api/reconnect` |
| 配置查询 | `GET /api/config`、`/api/inverted_pins`、`/api/power_config/{id}` |
| 认证 | API Key（Bearer / X-API-Key / `?token=`），`AuthMiddleware` |
| 配置 | `devices.yaml`：设备中文名、排序、电平反转、省电参数 |

**消息协议**（WS，JSON 文本帧）：

- 客户端→服务器：`subscribe` / `unsubscribe` / `get_status` / `set`
- 服务器→客户端：`devices_list` / `status` / `device_online` / `device_offline` / `error`
- 设备→服务器：`status`（上报状态）/ `heartbeat` / `pong`
- 服务器→设备：`set` / `get_status` / `reconnect`

**关键语义**：继电器 `value`，`0`=低电平(开启)、`1`=高电平(关闭)；「电平反转」配置用来适配「低电平触发 vs 高电平触发」的不同继电器板。

### 2.2 前端功能（relay-control/src/main.ts）

1. **单设备卡片**：电源环动画 + 开/关状态徽章 + 大按钮（开/关设备）。
2. **窗格模式（多设备）**：卡片墙列出所有设备，每个可独立开关。
3. **服务器设置**：服务器地址、选择设备、继电器端口(1-9)、API Key。
4. **连接状态**：连接/未连接圆点、断线自动重连（3 秒间隔）。
5. **确认弹窗**：关闭设备需二次确认。
6. **本地持久化**：localStorage 存服务器地址/设备/端口/API Key/窗格模式。
7. **HBuilderX/plus 适配**：返回键处理（`plus.key`）、退出应用（`plus.runtime.quit`）、`history.pushState` 栈管理。
8. **视觉**：深色背景 + 渐变光斑 + 粒子特效 + 电源环脉冲动画（CSS）。

> 前端是**纯原生 JS + DOM 操作**（约 1180 行），没有用 Vue 组件，逻辑高度集中在 `main.ts`，这反而方便我们对照逻辑用 Compose 重写。

---

## 三、Bridge 项目现状（对照）

### 3.1 「发现」页现状

`app/.../ui/HomePages.kt` 的 `DiscoverList()`：

- 一个 `LazyColumn`，列出硬编码假数据 `朋友圈/小程序/视频号/直播/看一看/搜一搜`。
- 每个 item 是 `Card` + `Avatar`，点击只打日志 `Timber.d("进入发现：$item")`，**无任何真实功能**。
- 完全可被替换，且替换后不损失任何业务能力。

主页结构（`MainScreen.kt`）：底部导航「主页 / 设置」；主页二级 Tab：`短信 / 音频中继 / 发现`。

### 3.2 已有 `relay/` 目录 ≠ 继电器

**关键澄清**：项目里已有 `app/.../relay/` 目录（`RelayClient`、`RelayProtocol`、`AudioRelayService`、`RelayPrefs` 等）和 `ui/RelayPages.kt`，但那是「**音频中继**」（手机作为接收方，主动连 Aurelay 中转服务器接收 PC 推来的音频并放音），**与 ESP32 继电器毫无关系**，只是命名都用了 "relay"。

> 集成继电器时**必须避免命名冲突**：建议新模块用 `switch` / `relayctrl` / `power` 之类命名，或放进 `app/.../switch/` 包，不要复用 `relay/` 包。

### 3.3 Bridge 自身的服务端与 WS 协议

Bridge 客户端连**自己的**服务端（`server/`，FastAPI），WS 端点 `/ws`，消息信封：

```json
{"type":"hello|ping|pong|close|message|command|command_result","ts":...,"data":{...}}
```

- 客户端是「**被动受控设备**」：由管理后台下发 `command` 命令，客户端执行后回 `command_result`。
- 命令体系由 `server/app/core/command_catalog.py` 单一事实来源定义，客户端 `Command.kt` + `CommandModule.kt` 落地执行。

**这与参考项目是两种完全不同的通信模型**：

| 维度 | 参考项目 | Bridge |
|---|---|---|
| 客户端角色 | 主动控制方（发 set/subscribe） | 被动受控方（收 command） |
| 服务端 | 参考 server/（设备↔客户端中继） | 我们 server/（管理后台↔手机） |
| 设备 | ESP32 硬件 | Android 手机 |

---

## 四、集成方案分析（核心决策）

### 4.1 目标

把「发现」页改造成「继电器控制」页，让 Bridge App 能控制 ESP32 继电器，**保留参考项目原有功能**（设备列表/单设备卡片/多设备窗格/开关/状态/设置/自动重连），不要求 UI 百分百还原，可按 Bridge 的 Compose 风格精简。

### 4.2 关键决策点：继电器数据走哪条链路？

这是集成前必须先定的架构问题。有三条路：

#### 方案 A：客户端直连参考项目的中继服务器（推荐，改动最小）

Bridge App 内新建一个「继电器客户端」，**主动出站直连**参考项目的 `server/`（`/ws/client`），照搬 `relay-control/src/main.ts` 的协议逻辑。

- **优点**：
  - 服务端**零改动**，直接复用参考项目的 `references/server/`（甚至现有的部署实例）。
  - 与我们「音频中继」的架构完全同构——`RelayClient.kt` 就是「客户端主动连外部服务器」的现成范式，可照抄其 OkHttp + WebSocket + 退避重连 + 心跳 + generation 防竞态的成熟实现。
  - 不影响 Bridge 自有服务端与管理后台。
- **缺点**：继电器服务器的地址/端口/API Key 需在 App 内单独配置（类似音频中继在设置页配服务器地址）。
- **工作量**：客户端新建一个包（网络层 + 页面）+ 设置项，服务端零改。

#### 方案 B：把继电器能力并入 Bridge 自有服务端

在 Bridge 的 `server/` 里加「ESP32 设备接入 + 继电器控制」能力（设备 WS 端点、继电器命令、设备列表），App 通过「命令」机制被动受控，或 App 直接连。

- **优点**：统一到 Bridge 体系，可被管理后台下发命令统一调度。
- **缺点**：改动大，需把参考 server 的 `ConnectionManager`/`device 端点`/`电平反转`/`devices.yaml` 整套移植进 Bridge server，且 ESP32 硬件要从「连参考 server」改为「连 Bridge server」（涉及硬件固件改动，参考项目未提供 ESP32 源码，改不了固件则此路不可行）。
- **风险**：**ESP32 固件源码缺失**，无法确认硬件端协议是否完全匹配、无法改重连目标地址。

#### 方案 C：内嵌 WebView 加载 relay-control 前端

用 Android WebView 直接加载 `relay-control/dist/` 的 H5。

- **优点**：几乎零逻辑重写，最快。
- **缺点**：与 Bridge 原生 Compose 风格割裂、离线资源管理、返回键/生命周期/JS 桥接都要额外处理，且「保留功能」实际是「套壳」而非「集成」。不推荐作为正式方案。

### 4.3 推荐结论

**采用方案 A**：Bridge 客户端主动直连参考项目中继服务器，把「发现」页改为 Compose 原生继电器控制页。理由：

1. 服务端零改动，可立即用现有参考 server（或部署一份）。
2. 与「音频中继」架构同构，有 `RelayClient.kt` 现成范式可借鉴。
3. ESP32 固件缺失不影响（我们只做客户端控制方，不碰硬件）。
4. 改动集中在客户端一个页面 + 一个新网络模块 + 设置项，风险可控、可独立验证。

---

## 五、Bridge 端改造设计（方案 A 落地）

### 5.1 新建网络模块（借鉴 RelayClient.kt 范式）

建议新包 `app/.../switch/`（或 `relayctrl/`），文件：

| 文件 | 职责 |
|---|---|
| `RelaySwitchClient.kt` | OkHttp WebSocket 客户端：连 `/ws/client`，订阅/控制/重连/心跳/generation 防竞态 |
| `RelaySwitchProtocol.kt` | 消息常量与编解码：subscribe/unsubscribe/set/get_status/status/devices_list 等 |
| `RelaySwitchPrefs.kt` | 持久化：服务器地址、API Key、当前设备、端口、窗格模式 |
| `RelaySwitchState.kt` | UI 状态模型（设备列表、各设备状态、连接态） |

关键点（照抄 RelayClient 的成熟经验）：
- OkHttp WebSocket，`connectTimeout` + `readTimeout(0)`。
- `generation` 会话代次防「停止→启动」后旧回调污染。
- 断线退避重连（1/2/4/8/16/30s 封顶）+ 心跳判死兜底。
- `?token=<apiKey>` 认证（若 server 开启）。

### 5.2 页面改造

- `HomePages.kt` 的 `DiscoverList()` → 替换为 `RelaySwitchPage()`。
- 复用参考前端的交互：
  - 单设备卡片（电源环 + 状态徽章 + 开关按钮）→ Compose `Card` + `Canvas`/`Icon` + `Button`。
  - 窗格模式（多设备卡片墙）→ **每行两列卡片网格**（对齐参考项目 `pane-grid` 移动端 `repeat(2,1fr)`），卡片结构与单设备卡同构、更紧凑。
  - 关闭设备二次确认 → `AlertDialog`。
  - 服务器设置（地址/设备/端口/API Key）→ 复用设置页现有输入组件风格。
- 视觉：按 Bridge 现有 Compose 主题（`BridgeTheme`，品牌蓝 `0xFF1A6EE8`）浅色化，但**还原原项目细节**：SVG 线框图标（`RelaySwitchIcons.kt`，`PathParser` 解析原 `d` 数据）、电源环光晕、粒子特效（`Canvas` + `rememberInfiniteTransition`，淡色呈现）。**不搬**深色背景 + 模糊光球 orb（与浅色 Compose 冲突）。
- **按钮配色语义**：按钮颜色 = 「点击后要执行的动作」。已开启→「关闭设备」**绿色** `#2E9E5B`；已关闭→「开启设备」**红色** `#E5484D`；等待/获取中→灰色禁用。
- **连接生命周期**：页面级短连接，进入 `start()`、离开 `stop()`（省电优先）。

### 5.3 设置项

在「设置 → 连接」页增加「继电器服务器」配置项（地址、API Key），复用 `RelayPrefs` 同款 SharedPreferences 范式。

### 5.4 主页 Tab 取舍

`发现` Tab 改为 `继电器`（或 `开关`），Tab 标题需与用户确认命名。

---

## 六、工作量估算

| 模块 | 内容 | 估量 |
|---|---|---|
| 网络模块 | 新建 `RelaySwitchClient` 等 4 文件 | 中（可大量借鉴 RelayClient，约 300-400 行） |
| 页面 | `RelaySwitchPage`（单设备 + 窗格 + 设置 + 确认弹窗） | 中（约 400-600 行 Compose） |
| 设置项 | 连接页加继电器服务器配置 | 小 |
| 文档 | `docs/api/` 或 `docs/research/` 登记 | 小 |
| 服务端 | **零改动**（方案 A） | 无 |

**风险点**：
1. ESP32 固件源码缺失 → 无法联调硬件，只能先与参考 server + 模拟设备联调。
2. 命名冲突（relay 已被音频中继占用）→ 必须用新包名。
3. 参考 server 若未部署 → 需用户提供可用实例地址，或本地 `uv run` 起一份。

---

## 七、待与用户确认的问题

1. **命名**：「发现」Tab 改叫什么？（继电器 / 开关 / 智能开关 / 电源控制）
2. **链路**：确认走方案 A（客户端直连参考 server）？继电器服务器地址/API Key 现有哪些？
3. **视觉风格**：完全保留参考项目的深色科技风，还是统一成 Bridge 现有浅色 Compose 风格？
4. **是否保留窗格多设备模式**：还是先只做单设备开关？
5. **ESP32 硬件现状**：是否已有实体设备可用于联调？固件源码是否可提供（决定能否走方案 B）？

---

*本报告为调研结论，落地实现待上述问题确认后启动。*
