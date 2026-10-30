# SalesRecordSystem 扫码上传链路梳理

> 调研日期：2026-10-30。目标：梳理 `D:\sync_program\python\ProgramTransferStation\SalesRecordSystem`
> 中「接收扫码上传的服务器」的实现与上报流程，为 Bridge 扫码页后续对接做准备。
> 关联文档：`docs/research/barcode-scanner-integration.md`（Barcode to PC 协议 App 端还原）。

## 1. 系统定位

SalesRecordSystem 是一个 **PyQt5 桌面销售/退货管理系统**（本地 SQLite），内嵌一个
**WebSocket 服务器（端口 57891）**，充当「Barcode to PC」协议的 PC 接收端：
手机扫码 App（Barcode to PC / 未来 Bridge）扫到条码后经 WS 上报，桌面端按业务规则处理。

相邻目录辨析（同属 ProgramTransferStation，勿混淆）：
- `SalesRecordRemoteApp`：uniapp/H5 移动端应用（有 unpackage 编译产物）；
- `upload_consumer`：通用上传消费器（独立项目，与扫码链路无关）。

## 2. WS 服务器实现（`controllers/websocket_server.py`，196 行）

| 项 | 内容 |
|---|---|
| 框架 | PyQt5 `QWebSocketServer`（NonSecureMode），跑在主窗口进程内（非独立进程） |
| 监听 | `0.0.0.0:57891`（`WebSocketServer(host, port)`，main_window.py:125 启动） |
| 消息格式 | JSON 文本帧，统一 `{"action": "...", ...}` 信封 |
| 并发 | 每连接一个 `WebSocketHandler`，条码经 Qt 信号 `barcode_received(dict)` 逐层上抛 |

### 2.1 协议 action 集（与 Barcode to PC App 端还原一致，实锤）

| action | 方向 | 载荷 | 服务端行为 |
|---|---|---|---|
| `helo` | C→S→C | `version, deviceName, deviceId` | debug 日志；回 `{"action":"helo","version":"0.9"}` |
| `ping` | C→S→C | — | 回 `{"action":"pong"}`（心跳） |
| `getVersion` | C→S | — | 回 `{}`（空对象，版本协商形同虚设） |
| **`putScanSessions`** | C→S | `sendKeystrokes, deviceId, scanSessions[]` | **核心上报**；遍历每个 session 的 `scannings[]`，逐条 emit `barcode_received`；回 `{}` |
| `deleteScan` | C→S | `scanSessionId, scan` | 仅日志，回 `{}` |
| `deleteScanSessions` | C→S | `scanSessionIds[]` | 仅日志，回 `{}` |
| `updateScanSession` | C→S | session 对象 | 仅日志，回 `{}` |
| `clearScanSessions` | C→S | — | 仅日志，回 `{}` |
| 未知 action | C→S | — | **直接 `websocket.close()` 踢下线** |

### 2.2 `putScanSessions` 载荷结构（Bridge 对接时的出参契约）

```json
{
  "action": "putScanSessions",
  "sendKeystrokes": false,
  "deviceId": "...",
  "scanSessions": [
    {
      "id": "...", "name": "...", "date": "...", "selected": true,
      "scannings": [
        {"id": "...", "repeated": false, "date": "...",
         "text": "快递单号", "displayValue": "显示值"}
      ]
    }
  ]
}
```

**服务端只消费 `scannings[].text`**（其余字段仅 debug 日志）；
回执统一是空对象 `{}`——**没有逐条成功/失败回执**，上抛后全靠桌面端业务自反馈。

## 3. 上报全流程（信号链）

```
手机扫码 App
   │ WS ws://<PC_IP>:57891（helo 握手 → ping/pong 保活 → putScanSessions 批量上报）
   ▼
WebSocketHandler.handle_text_message          controllers/websocket_server.py
   │ 解析 scanSessions[].scannings[]，逐条 emit
   ▼
WebSocketServer.barcode_received               （信号转发层）
   │ main_window.py:127 connect
   ▼
MainWindow.update_return_express_status        ui/main_window.py:858
   │ ①取 text → ②find_return_by_express 匹配 → ③更新状态 → ④多模反馈
   ▼
database_manager.find_return_by_express        database_manager.py:1595
   遍历所有 product_type 的 returns_{type} 表：
   WHERE return_express = ? OR send_express = ? OR remarks LIKE '%单号%'
   命中后连带查 sales_{type}（order_no + serial）取销售记录
```

## 4. 业务处理规则（`update_return_express_status`，核心状态机）

以扫到的快递单号匹配退货记录，本质是「**退货包裹签收登记**」：

| 情形 | 动作 |
|---|---|
| 空结果 / 无 text / 未匹配到退货记录 | 播「quxiao」音效（失败提示），静默返回 |
| 匹配到 且 尚未签收（`signed != '已签收'`） | 状态置「已签收」；备注非 `√` 开头则加 `√` 前缀 |
| 匹配到 且 备注已有 `√`（**重复扫码**） | 播「chongfuluru」音效（= Bridge 的「重复录入.wav」同源），不重复写库 |
| 首次有效签收 | 播「xiu」音效（= Bridge 的「扫描咻.wav」同源）+ **TTS 播报「收货人 地址四段」**（QTimer 100ms 延迟） |
| 写库 | `update_return_signed_status(product_type, id, 已签收, √备注, 单号)`，仅在「状态或备注有变化」时写 |

**UI 联动**（主窗口可见时）：自动切到「退货记录管理」Tab、必要时切换商品类型、
把单号填进搜索框实现表格过滤——扫一下即「定位+签收」一步完成。

**剪贴板**：`auto_switch` 开启时把单号写入系统剪贴板（供其它软件粘贴）。

> 有趣的实锤：Bridge 扫码页 v0.1.96 的两个音效（扫描咻 / 重复录入）与
> 本系统播放的「xiu / chongfuluru」同源——音效文件本就出自这套系统。

## 5. 对 Bridge 扫码页对接的启示

1. **协议已完全兼容**：Bridge 的 ScanStore 数据模型（session + records）与
   `scanSessions/scannings` 一一对应，`text/displayValue/repeated/date` 都有现成映射；
   `repeated` 即 Bridge 的去重判定结果。
2. **上报时机建议**：仿 Barcode to PC——会话内逐条或退出/手动「上传」时整包
   `putScanSessions`；本服务端无逐条回执，整包上报即可。
3. **失败反馈缺口**：服务端匹配失败只在本机播「quxiao」，**不回传手机**——
   手机端无法感知「单号是否被业务接纳」。若 Bridge 需要「已入库/未匹配」区分，
   需改造服务端在 putScanSessions 回执中带结果（现回 `{}` 是空实现，有扩展位）。
4. **心跳**：客户端需实现 `ping`→`pong`（服务端不主动发）；未知 action 会被踢线，
   Bridge 发送的 action 必须严格在白名单内。
5. **重复扫码语义**：服务端以「备注 √ 前缀」判重，Bridge 侧 `repeated=true` 的记录
   上报后不会引起重复写库，语义安全。

## 6. 对接设计方案（扩展 Barcode to PC 协议，待拍板）

设计原则：**向后兼容**——所有扩展都是「新增」，Barcode to PC 原客户端行为不变；
Bridge 侧本地闭环（去重/入库/导出）不受上报影响，上报是叠加层。

### 6.1 扩展一：服务器在线检测（Bridge 端为主，服务端零改动/可选增强）

**现有协议已够用**：WS TCP 连接成功 + `helo` 得到回执 = 在线实锤；
每 15s 发 `ping`，收到 `pong` = 存活；连接断开或连续 2 次超时 = 离线。

- Bridge 状态机：`Disconnected → Connecting → Online`，断线指数退避重连
  （5s/10s/20s…上限 60s）；
- UI：扫码页会话头行加状态徽标（●绿=在线 / ○灰=离线 / ⚠红=地址不可达），
  设置页显示「已连接 SalesRecordSystem @ 192.168.31.x:57891」；
- 服务端可选增强（一行）：`helo` 回执加 `"serverName": "销售记录系统"`，
  Bridge 显示更有辨识度。不加也能工作。

### 6.2 扩展二：扫码结果回执（核心扩展，需服务端小改）

**现状缺口**：`WebSocketHandler` 收到 `putScanSessions` 后 emit 信号即回 `{}`，
业务在 `MainWindow.update_return_express_status` 异步处理，**业务结果没有回流通道**。

**协议新增** `scanResult`（S→C，每条 scannings 一项）：

```json
{
  "action": "scanResult",
  "results": [
    {"id": "scanning.id", "text": "单号",
     "result": "hit | duplicate | miss",
     "receiver": "收货人", "town": "省, 市, 区, 详细"}
  ]
}
```

**服务端改动点（3 处，均在 SalesRecordSystem）**：

1. `websocket_server.py`：`barcode_received = pyqtSignal(dict, object)` ——
   第二参数传 `self.send_message`（回执发送闭包），`WebSocketServer` 信号同步加参转发；
2. `main_window.py`：`update_return_express_status(express_dict, reply=None)` ——
   三条既有分支顺手上报：命中首扫（播 xiu 那支）→ `hit`；
   重复（备注已有 √，播 chongfuluru 那支）→ `duplicate`；
   未匹配/空（播 quxiao 那支）→ `miss`；处理完 `reply({"action": "scanResult", ...})`；
3. 兼容性：旧客户端不读 `scanResult`，行为不变；无回执通道时（reply=None）跳过。

### 6.3 Bridge 客户端设计

| 项 | 方案 |
|---|---|
| 新代码 | `app/.../scanner/ScanUploadClient.kt`（WS 客户端+状态机）+ ScanPages 接线 + 设置页分区 |
| 连接生命周期 | 跟随扫码页可见（进入 start / onDispose stop，与智能控制页惯例一致），省电优先 |
| 上报时机 | **逐条实时**：本地 Accept 入库后立即发单条 `putScanSessions`（包一个单条 session）→ 等回执 |
| 音效映射 | 回执 `hit` → 不重播（本地 scan_ok=「扫描咻」=服务端 xiu 同音，已播过）；`duplicate` → 同理（scan_dup=chongfuluru）；**`miss` → 播 `quxiao`（净新增）**+ 结果卡显示「服务器未匹配该单号」 |
| 新资源 | `res/raw/scan_miss.wav` ← 拷贝 SalesRecordSystem `data/static/sounds/取消.wav` |
| 回执超时 | 2s 无回执静默容错（不阻塞本地闭环，本地音效早已播完） |
| 设置项 | 开关 + 地址（IP:57891，自动补 ws://）+ 状态显示 + 测试连接按钮 |
| 本地闭环 | 不变：去重/入库/导出与上报解耦，服务器离线时扫码功能完全可用 |

### 6.4 交互时序（一次完整扫码）

```
Bridge 扫码确认 → 本地入库 + 播扫描咻 + 徽章动画
   └→ WS putScanSessions(单条) → 服务端匹配退货表 → 播 xiu + TTS（PC 侧）
        └→ scanResult{hit} → Bridge 不重播（同音）
   未匹配分支：PC 播 quxiao ──→ scanResult{miss} → Bridge 播 quxiao + 卡片提示
   重复分支：  PC 播 chongfuluru → scanResult{duplicate} → Bridge 不重播（同音）
```
