# Bridge 自动化平台：两阶段路线图（探查工具 + Python RPC）

> 规划日期：2026-11-03
> 状态：**已实施（2026-11-03 完成 A/C 阶段 + B 阶段核心，见下方「实施记录」）**
> 关联文档：
> - `docs/research/accessibility-enrichment.md`（AutoX 无障碍动作层移植，已落地 7 条 ui_* 命令）
> - `docs/research/automation-benchmark.md`（Droidline vs AutoX 对比评估，工程能力借鉴清单）
> - `docs/api/commands/ui.md`（现有 ui_* 命令文档）
> - `docs/api/command-catalog.md` §3.3（命令扩展三步规范）

---

## 实施记录（2026-11-03）

用户拍板全部 5 个决策点并**要求一次性完整实施**（因带手机离开）。已完成：

| 阶段 | 内容 | 状态 |
|------|------|------|
| **A（动作层鲁棒性）** | A1 点击三级降级、A2 选择器 DSL 增强 + timeout 等待、A3 IME 输入法兜底（BridgeKeyboard） | ✅ 完成 |
| **B（探查工具补全）** | B1 截图高亮、B2 可视化手势选坐标、B3 节点「复制选择器/参数 JSON」一键导出 | ✅ 完成（2026-11-03 晚，独立「自动化调试台」页面） |
| **C（Python RPC）** | C1 rpc_start/stop/status + 手写 HTTP server + token 鉴权、C2 执行器 HTTP 包装（10 端点）、C3 统一错误码+retryable、C4 Python SDK（bridge_rpc 包） | ✅ 完成 |

**关键实现决策**：Android 无 `com.sun.net.httpserver`（JDK 类），故 RPC 用手写极简 HTTP/1.1
server（ServerSocket + 线程池），零外部依赖。RPC 复用 CommandModule 的 CommandExecutor 表
（`RpcServerController.bind()` 注入），动作层零重复实现。

---

## 一、背景与用户设想（本文档的出发点）

用户已确认「集成自动化完全可行」（无障碍动作层已真机验收通过）。在此基础上提出**两阶段设想**，把 Bridge 从「命令下发工具」升级为「自动化平台」：

| 阶段 | 用途 | 使用者 | 形态 | 与现有关系 |
|------|------|--------|------|-----------|
| **① 控件探查工具** | 用户写代码前，查看控件、测查找/点击/滑动/截屏/输入 | **人**（后台界面点） | 可视化调试台 | 已有雏形（ui_dump/ui_find + 详情弹窗），需补全成「工具」 |
| **② Python RPC** | 用户拿到控件参数后，写 Python 脚本程序化驱动手机 | **脚本**（程序调用） | 手机开 HTTP/REST 服务，Python 当客户端连入 | 复用动作层执行器，新增 RPC 入口 |

**一句话定位**：① 是「给人用的调试工具」，② 是「给脚本用的编程接口」。两者共享同一套无障碍动作层，是同一平台的两条腿。

---

## 二、两阶段的定位差异（先厘清，避免做重）

### 2.1 阶段①：探查工具 = 可视化「示波器」

用户在写自动化脚本前，需要先「摸清目标界面长什么样」——有哪些控件、每个控件的参数（id/text/desc/className/bounds/clickable…）、点哪里能触发什么。这个阶段**人在环中**，是交互式的、探索式的。

- **已实现的小部分**：`ui_dump`（导出控件树）+ `ui_find`（按选择器查找）+ 后台「查看详情」弹窗（树形可视化 + 节点点击详情抽屉）。
- **远未实现、要补成「工具」的部分**（见 §四）。

### 2.2 阶段②：Python RPC = 程序化「执行器」

用户摸清界面后，把操作固化到 Python 脚本里重复执行。这个阶段**人离线**，是确定性的、可编程的。

- **与 Droidline 的同**：都是「脚本驱动手机」，命令语义相近（find/click/input/swipe/screenshot…）。
- **与 Droidline 的异（关键）**：**通讯方向相反**。
  - Droidline：**手机主动连出**到固定服务器（适合「一台服务器管很多台手机」）。
  - Bridge 设想：**Python 主动连入**手机（手机被动监听，适合「脚本按需驱动某台手机」）。
  - 这一反向带来直接收益：**不需要** Droidline 那套发现/握手/多路由/中继穿透，只要网络可达（同网或端口转发）即可。

---

## 三、核心架构决策（本文档的「拍板」）

### 3.1 阶段② RPC 协议：**HTTP/REST**（非 WebSocket）

**结论**：阶段②用 HTTP/REST，理由与取舍如下。

| 维度 | HTTP/REST（✅ 选） | WebSocket RPC |
|------|------------------|---------------|
| Python 端成本 | 零依赖（`requests`/`httpx`），人人会写 | 需 `websockets`/`aiohttp`，处理握手/心跳/异步 |
| 手机端实现 | 极简内嵌 HTTP server（NanoHTTPD 或自写几十行） | 需维护连接池/心跳/重连，复杂 |
| 断线/重连 | 天然无状态，下次请求自动重连 | 需自管重连 |
| 双向推送 | 需轮询/SSE | 天然支持 |
| 适配场景 | **请求-响应**（脚本每步都要返回值） | 手机主动推（位置/短信/事件流） |

**判断依据**：脚本自动化是「发指令 → 等结果 → 发下一条」的**低频、串行、请求-响应**模型。两种协议的单次延迟差异（几 ms）在此场景可忽略，「WebSocket 更快」只对高频双向流（如投屏直播）成立。真正的差异在**实现成本与断线语义**，HTTP/REST 全面占优。

**关键：不冲突**——Bridge 现有**主 WS 通道**负责「长连接保活 + 主动上报 + 后台下发命令」，这是 WebSocket 该干的活；阶段②的 **RPC** 负责「脚本请求-响应式自动化」，用 HTTP。两者**各司其职、并存**：

```
        现有主 WS 通道（常驻，心跳+上报+后台命令下发）
        ──────────────────────────────────────────────
        新增 HTTP RPC（按需拉起，Python 脚本请求-响应）
```

### 3.2 阶段② RPC 生命周期：**按需拉起，不常驻**（省电优先）

延续 Bridge「省电优先、按需拉起」一贯原则 + VNT/SSH 的「start/stop/status 状态联动 + 幂等守卫」范式：

- **`rpc_start`**：收到命令才在本机拉起 HTTP server（默认监听 `127.0.0.1:0` 随机端口，或指定端口），返回实际端口 + 鉴权 token。
- **`rpc_stop`**：关闭 server，释放资源。
- **`rpc_status`**：查询端口/运行状态/已连接会话数。

> 鉴权：**必须有 token**。HTTP RPC 是「远程操控屏幕」的主动写操作，比锁屏/截屏更危险。token 由 `rpc_start` 生成并下发，脚本每次请求带上（`Authorization` 头或 query），避免「同网任意客户端都能控机」。这一条是安全红线。

### 3.3 阶段② RPC 复用现有动作层执行器（不做重写）

现有 `CommandModule` 的 `executeUiXxx(command)` 已经是**纯逻辑执行器**，与「谁来调用」解耦。RPC 层只需：
1. 把 HTTP 请求解析成参数 → 构造 `Command` → 调对应的 `executeUiXxx` → 把 `CommandResult` 序列化成 JSON 返回。
2. **动作层零改动**，RPC 与后台命令通道**共享同一套实现**，天然保证行为一致。

### 3.4 阶段② RPC 接口形态（初步草案，实施时细化）

| HTTP 端点 | 方法 | 参数 | 返回 | 对应执行器 |
|-----------|------|------|------|-----------|
| `/dump` | GET | `max_nodes`, `max_depth` | 控件树 JSON（含 depth） | `executeUiDump` |
| `/find` | POST | `selector`, `max_results` | 匹配节点列表 | `executeUiFind` |
| `/click` | POST | `selector` 或 `x,y` | 点击结果 | `executeUiClick` |
| `/set_text` | POST | `selector` 或 `x,y`, `text` | 输入结果 | `executeUiSetText` |
| `/gesture` | POST | `points[]`, `duration` | 手势结果 | `executeUiGesture` |
| `/swipe` | POST | `x1,y1,x2,y2`, `duration` | 滑动结果 | `executeUiGesture` 封装 |
| `/screenshot` | GET | — | PNG 图片 | 复用截屏通道 |
| `/back` `/home` `/recents` | POST | — | 全局动作结果 | `executeUiBack/Home` |

> 选择器 DSL 会随「关系选择器」（`row`/`below`/`inside`/`nth` + `timeout`，见 `automation-benchmark.md` §3.1）一起增强，RPC 与后台共用同一套 DSL。

---

## 四、阶段①探查工具：从「雏形」到「工具」的补全清单

现状是「能看控件树 + 能点详情」，但离「用户写代码前可用的调试工具」还差很多。补全清单（按价值/成本排序）：

| # | 能力 | 现状 | 缺口 | 价值 |
|---|------|------|------|------|
| 1 | **控件树浏览** | ✅ ui_dump + 树形详情 | 已够用 | — |
| 2 | **控件查找测试** | ✅ ui_find | 缺「等待出现」语义 + 关系选择器 | 高 |
| 3 | **点击测试** | ✅ ui_click | 缺「三级降级」（节点→祖先→坐标）鲁棒性 | 高 |
| 4 | **滑动/手势测试** | ✅ ui_gesture | 缺「可视化拖拽选坐标」交互 | 中 |
| 5 | **截屏查看** | ✅ screenshot 命令 | 缺「截图后高亮命中控件」的可视化联动 | 高 |
| 6 | **输入测试** | ✅ ui_set_text | 缺 IME 兜底（密码框/顽固输入框） | 高 |
| 7 | **控件参数复制** | ❌ | 点节点后一键复制「selector 表达式/参数 JSON」，直接粘进脚本 | 高 |
| 8 | **操作回放/日志** | ❌ | 探查过程中的每次操作留痕，便于回溯 | 低 |

**关键洞察**：阶段①的「测试」和阶段②的「执行」**底层是同一套执行器**。所以阶段①补全时，凡是「让执行更鲁棒」的改进（点击降级、IME 兜底、关系选择器、等待语义）**同时惠及阶段②**。这也是为什么建议两阶段共享动作层、而不是各做一套。

---

## 五、与「automation-benchmark.md」P0/P1 清单的衔接

上一份对比评估给出的借鉴优先级，正好映射到本路线图：

| benchmark 结论 | 本路线图落点 |
|---------------|-------------|
| P0 输入法(IME)兜底 | 阶段①「输入测试」+ 阶段② `/set_text` 可靠性 |
| P0 关系选择器 + nth + 等待 | 阶段①「查找测试」+ 阶段② `/find` DSL 增强 |
| P0 点击三级降级 | 阶段①「点击测试」+ 阶段② `/click` 鲁棒性 |
| P1 服务器端 OCR/图像 | 后续独立能力（游戏/自绘界面），不在本两阶段主线 |
| P1 统一错误码 + retryable | 阶段② RPC 返回结构的**必备项**（脚本要程序化判断重试） |
| P2 命令幂等缓存 | 阶段② RPC 的可靠投递（可后置） |

**结论**：本路线图的两阶段，**优先消化 benchmark 的 P0 三项**（IME 兜底 / 关系选择器 / 点击降级），它们同时增强「探查工具」和「Python RPC」两条腿，是最划算的投入。

---

## 六、分阶段实施顺序（建议）

```
阶段 A（动作层鲁棒性，惠及两腿）
  ├─ A1 点击三级降级（节点→祖先→坐标）
  ├─ A2 关系选择器 + nth + timeout 等待（ui_find DSL 增强）
  └─ A3 IME 输入法兜底（BridgeKeyboard + ui_set_text 降级）
        ↓
阶段 B（阶段①探查工具补全）
  ├─ B1 截图 + 控件高亮联动
  ├─ B2 可视化滑动/手势选坐标
  ├─ B3 节点「复制 selector/参数」一键导出
        ↓
阶段 C（阶段② Python RPC）
  ├─ C1 rpc_start/stop/status 命令 + 手机内嵌 HTTP server + token 鉴权
  ├─ C2 动作执行器 HTTP 包装（/dump /find /click /set_text /gesture /swipe /screenshot /back /home）
  ├─ C3 统一错误码 + retryable 返回结构
  └─ C4 Python SDK（thin client，requests 封装 + 语法糖）
```

> 阶段 A 是地基，做完后 B/C 都受益；B 和 C 可并行。

---

## 七、待用户确认的决策点

1. **RPC 协议**：已倾向 HTTP/REST（本文档 §3.1 已论证），是否认可？
2. **RPC 生命周期**：按需拉起（`rpc_start`/`rpc_stop`）而非常驻，是否认可？（省电优先，但每次要下发 `rpc_start` 才可用）
3. **RPC 鉴权**：token 鉴权是否必须？（本文档认为安全红线，默认必须）
4. **实施顺序**：先做阶段 A（动作层鲁棒性，惠及两腿）？还是先做阶段 C 的 RPC 骨架（快速打通 Python 驱动）？
5. **Python SDK 形态**：只要一个 thin client（`requests` 封装），还是要更完整的 SDK（含选择器 DSL 的 Python 版）？

---

## 八、参考资料

- `references/Droidline-main/`：通讯方向相反的参照系（手机连出 → 改为 Python 连入）
- `references/Autoxjs_v6_ozobi-65823/`：动作层来源（已移植）
- 现有代码：`CommandModule.kt` 的 7 个 `executeUiXxx` 执行器（RPC 复用目标）、`command_catalog.py`（命令注册）
- `docs/api/commands/ui.md`：现有 ui_* 命令语义
