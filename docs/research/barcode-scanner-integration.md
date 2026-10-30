# 快递扫码功能集成调研（Barcode to PC 参考）

> 调研对象：`references/Barcode to PC/`（barcodetopc.com 的手机扫码枪项目）。
> 目标：评估把「扫码快递单号 → 推送到后端」集成进 Bridge 客户端（主页新增 Tab，
> 放在「智能控制」之后），重点回答三个问题：**协议长什么样、怎么扫得又快又准、
> 需要自研哪些部分**。
> 状态：**C 版本（纯本地闭环）已实现并发布 v0.1.94**（`scanner/` 包 + `ui/ScanPages.kt`，
> 主页第 4 个 Tab「快递扫码」；按日会话/五种去重/CSV 导出/多帧投票/高清单帧救援均已落地；
> 服务端通道 + admin 看板待下一版）。设计原型：`assets/design/ui-mockup-barcode-scanner.html`。
> **v0.1.95 真机反馈迭代**：会话自由增删改（§5.5 修订）、记录级编辑/删除/复制、同码冷却节流、
> 提示音重做（SoundPool 双音效）、相机按需启停、入口设置、首次创建弹窗可编辑命名（§5.5/§6.6）。

## 1. 参考项目概览

| 项 | 内容 |
|---|---|
| 项目 | Barcode to PC（手机变无线扫码枪，扫码结果在 PC 端模拟键盘输入） |
| 组成 | 手机 App（Ionic/Cordova + TypeScript，本地 `barcode-to-pc-app-master/` 完整源码）+ PC 服务端（Electron + TypeScript） |
| 关键限制 | **服务端核心源码不可得**：`barcode-to-pc-server-master/electron/` 是 git submodule，指向私有仓库 `fttx/barcode-to-pc-server-core`（GitHub 404），本地该目录为空。只有官方安装 exe |
| 扫码引擎 | 自维护 fork：`@fttx/barcode-scanner`（基于 `phonegap-plugin-barcodescanner`，Android 原生层为 ZXing） |
| 发现机制 | Zeroconf/mDNS（`_http._tcp`，服务名 `barcode-to-pc-server-*`，端口 57891）+ 子网校验 |
| 传输 | **明文 WebSocket** `ws://host:57891/`，JSON 文本帧，`action` 字段路由 |
| 收费点 | 高级 Output 组件（http/run/csv_lookup/google_sheets 等）走 license server |

**结论先行**：协议层从 App 端源码可 100% 还原（App 是协议的另一半权威），但它
服务的是「PC 模拟键盘输入」场景；Bridge 的场景是「扫码结果进自己的服务端/管理后台」，
**不兼容也不需要兼容参考服务端，协议按 Bridge 体系自定**，参考项目只借鉴交互模型、
去重策略、扫描质量手段。

## 2. 协议层完整还原（重点，源自 App 端 `providers/server.ts` + `models/*.ts`）

### 2.1 传输与帧格式

- WS 明文，默认端口 **57891**；消息为 JSON 对象，统一 `action` 字段路由。
- 连接状态机有「连接队列」防抖：WS 处于 CONNECTING/CLOSING 过渡态时新目标进队列，
  5s 仍在过渡态则强制断开重连；重连固定间隔 7s；选中新服务器用 `skipQueue` 直接踢掉旧连接。
- 关闭码语义：`4000` = 用户主动换服务器，**不再重连**；其余关闭码一律定时重连；
  App 回到前台（resume）时若未连接立即发起重连。

### 2.2 握手（helo）

```
App → 服务端   { action: 'helo', version: '4.x.x', deviceName: '...', deviceId: 'uuid' }
服务端 → App   { action: 'helo', version, outputProfiles: [...], events: [],
                 serverUUID: '...', savedGeoLocations: [...], kicked: false }
```

- **版本对齐检查**：major/minor 不匹配弹「版本不匹配」弹窗（有一次性的
  `skipLastMismatchVersion` 抑制）；旧服务端不回 helo → 5s 超时后 App 发
  `getVersion` 兜底探测。
- `serverUUID` 是会话同步的锚点（见 2.5）。

### 2.3 心跳

- App 每 15s 发 `{action:'ping'}`（iOS 9s），**4s 内未收到 `{action:'pong'}` 即判死**
  → 主动断开 + 定时重连。应用层心跳而非依赖 WS ping frame。

### 2.4 扫码数据上报（核心链路）

```
App → 服务端   { action: 'putScanSessions', scanSessions: [ScanSession...],
                 sendKeystrokes: true|false, deviceId }
服务端 → App   { action: 'putScanAck', scanSessionId, scanId, outputBlocks: [...], serverUUID }
```

- **批量上报**：按「扫描会话（session）」为单位 push，一次会话里含 N 条 scan 记录，
  不是扫一条发一条——这是它省电省流量的关键设计。
- `sendKeystrokes`： true = 让 PC 端模拟键盘打出；false = 仅同步数据（重连恢复用）。
- **ACK 驱动**：服务端回 `putScanAck` 才算送达；ACK 里可回填 outputBlocks 的执行结果
  （服务端侧组件如 http/run 的返回值），App 拿它更新本地记录。
- 服务端可反向推送：`action_popup`（弹窗）、`kick`/`unkick`（顶号/解禁）、
  `update_output_profiles`（输出模板热更新）、`requestScanSessionUpdate`、
  `remoteComponentResponse`（App 发 `remoteComponent` 请求服务端代执行 http/csv 等，
  用 `id` 关联请求-响应）。

### 2.5 离线可靠性（值得照抄的部分）

- App 本地持久化 scan sessions（`ScanSessionsStorage`），每条 session 记
  `syncedWith: serverUUID[]`。
- 已删未同步：App 删了 session 但没送达服务端 → 记入 unsynced deleted 列表，
  重连 helo 后补发 `deleteScanSession`，然后清空。
- 已恢复未同步：同理补发 `putScanSessions(sendKeystrokes=false)`。
- **即：断网期间扫的码不丢，重连后自动补同步，双向（删/增）都有对账。**

### 2.6 数据模型

```
ScanSession { id, name, date, scannings: Scan[], selected, syncedWith: serverUUID[] }
Scan        { id, outputBlocks: OutputBlock[], date, displayValue, ack, repeated, hasImage }
OutputBlock { type: 'barcode'|'number'|'text'|'key'|'beep'|'alert'|'if'|'date_time'|'http'|
              'geolocation'|..., label, value, filter, errorMessage, enabledFormats... }
```

- Output 模板是「一条扫码记录的组装管线」：BARCODE（等扫码）→ NUMBER（数量）→
  STATIC_TEXT → KEY(tab/回车) → BEEP → IF 条件块 → HTTP/CSV 等远程块。
- `displayValue = 各块 value 按模板顺序拼接`（跳过 skipOutput 块），即 CSV 单行的雏形；
  CSV 导出逻辑（`ScanModel.ToCSV`：Papa.unparse、可选表头、引号、分隔符、剔除非输出块）
  值得直接借鉴。

## 3. 扫描链路与准确率手段（参考项目怎么做的）

App 端 `providers/scan.ts` 的采集模式（acquisitionMode）：

| 模式 | 行为 |
|---|---|
| `manual` | 手动输入（自动聚焦输入框，校验失败显示 errorMessage 重输） |
| `single` | 单次扫码，成功即返回 |
| `continue` | 原生插件连续模式，一个 subscription 不停出码（仅 Android 且模板里无阻塞组件时启用） |
| `mixed_continue` | 连续扫但每条完成后弹「继续添加？」对话框（可倒计时自动继续） |

准确率/可靠性手段（逐条来自源码）：

1. **格式白名单**：设置里按格式开关（CODE_128/CODE_39/QR/EAN13…），只开需要的
   格式，`formats` 直接传给原生插件——减少误识和提速。
2. **正则 filter 校验**：每个 BARCODE 块可配 `filter`（正则）+ `errorMessage`，
   不匹配 = 视为没扫到，继续重扫并在取景框提示 `❌ + errorMessage`。**这是防
   「扫到但不想要的东西」的第一道闸**。
3. **重复码四种策略**（`duplicateBarcodeChoice`）：`ask`（弹窗 Accept/Discard，可记住选择）、
   `always_accept`、`discard_adjacent`（仅丢弃相邻重复）、`discard_scan_session`（会话内全去重）。
   连续模式下同一码反复出现是常态，**去重策略直接决定「刷屏」还是「静默精准」**。
   Bridge 集成时新增第 5 种「全局去重」（跨所有历史会话判重，见 §6.6）。
4. **码制修正**：CODE_39 → CODE_32 转换（`convertCode39ToCode32`）；
   「去特殊字符」开关（`[^a-zA-Z0-9]` 清洗）；瑞士 QR 账单（SPC 开头）识别提醒。
5. **原生参数**：`assumeGS1: true`（Android，GS1 解析）、`alsoInverted: true`
   （反色条码）、`preferWideLens`、手电筒常开选项、`resultDisplayDuration: 800`、
   成功提示音/震动开关。
6. **死循环防护**：连续模式下若连续 30 次（`INFINITE_LOOP_DETECT_THRESHOLD`）产出相同
   displayValue，弹窗询问是否继续——防「模板逻辑错误导致无限弹窗」。
7. **取消即提示 PDA**：用户手动取消扫码时弹「你的设备是 PDA？」引导（外接红外扫码
   头的设备不需要相机）。

**参考项目的天花板**：底层是老 ZXing 插件，一帧一拍、无连续变焦、弱光/远距表现一般。
Bridge 自研可以直接上 CameraX + ML Kit，准确率上限显著高于参考实现（见 §6）。

## 4. Bridge 现状对照

| 项 | 参考 Barcode to PC | Bridge 现状 |
|---|---|---|
| 客户端 | Ionic/Cordova，ZXing 插件 | Kotlin + Compose，可上 CameraX + ML Kit |
| 服务端 | Electron 私有核心（PC 键盘模拟） | 自有 FastAPI 服务端 + admin Vue 后台（可加扫码通道） |
| 连接 | 明文 WS + mDNS 发现 | 已有设备 WS 通道（ANDROID_ID 鉴权）+ REST + admin_event_bus |
| 页面接入点 | 独立 App | 主页 `HomePage` tabs 现为 `["短信","音频中继","智能控制"]`，**追加第 4 个 Tab「快递扫码」即可**；连接设置放「设置 → 连接页」新分区（照 RelaySwitchSettings 模式） |
| 生命周期经验 | — | RelaySwitch 的 `start()/stop()` 省电模式、generation 防竞态、不可变快照 UI 全部可复用 |

## 5. 集成方案对比与拍板建议

| 方案 | 内容 | 结论 |
|---|---|---|
| A. 复用参考服务端 | 客户端直连 Barcode to PC 官方服务端（照 ESP32 方案 A） | **不可行**：服务端核心闭源（私有仓库），协议虽可逆向但场景是「PC 键盘模拟」，与 Bridge「扫码进管理后台」不匹配 |
| B. 全自研三端（推荐） | 客户端扫码页（CameraX+ML Kit）→ Bridge 服务端新增扫码会话接口（REST 上报 + `scan_store.py` 持久化 + admin_event_bus 广播）→ admin 后台实时看板 + CSV 导出 | **推荐**：与 Bridge 体系同构（鉴权/存储/广播全是现成模式），参考项目只借鉴交互与扫描策略 |
| C. 纯本地降级 | 扫码页仅本地保存会话 + 导出 CSV，不连服务端 | 作为 B 的第一步实现（服务端通道后补），或弱网场景兜底 |

**建议路线**：先做 C（客户端闭环，风险集中在扫码质量本身），跑通后加 B 的服务端与
admin 部分。协议不需要兼容参考项目，但消息语义直接映射它的
`putScanSessions/putScanAck`（批量 + ACK + 离线补传），该设计的可靠性已被验证。

### 推荐协议（Bridge 版，REST 优先）

```
POST /api/scan/sessions              # 批量上报会话（照 putScanSessions 语义）
      body: { sessions: [{ id, name, date, scannings: [{ id, value, format, date }] }] }
      → 200 { accepted: [sessionId...], serverTime }        # putScanAck 语义
DELETE /api/scan/sessions            # 已删对账（照 unsynced deleted）
GET  /api/scan/sessions?device_id=   # admin 查询
WS   admin_event_bus 广播 scan_received 事件（admin 后台实时刷新）
```

鉴权复用设备 token；服务端新增 `core/scan_store.py`（照 `sms_store.py` 模式：
落盘 + 上限清理）。命令通道（WS）不适合传批量数据，REST 更简单可靠。

## 5.5 会话管理设计（v0.1.95 修订：会话自由增删改）

> **v0.1.94 时拍板「每天一个会话不可多会话」，v0.1.95 按真机反馈放开**：会话支持自由
> 创建/删除/重命名，「按日命名」降级为**默认建议值**（创建弹窗默认文本「10月30日」，可编辑，
> 不带「出库单」后缀）。以下为现行规则：

- 会话一等键 = **id（UUID）**；date 仅作创建日期分组与「今日会话」判定（旧档迁移：id 缺失用 date 补齐）。
- 扫码自动落**今日最新会话**（同日多会话时取 createdAt 最大者）；今日无会话则自动创建。
- 打开扫码页场景（设置项「进入快递扫码页时」：**直接进入扫码页 / 进入会话列表**）：

| 场景 | 行为 |
|---|---|
| A. 今日会话已存在 + 入口=直接扫码 | 直接进入扫码页继续扫 |
| A'. 今日会话已存在 + 入口=会话列表 | 停在会话管理页手动进入 |
| B. 今日会话不存在 | 提示卡「今天是 10月31日，还没有今日会话」→「创建今日会话」弹窗（默认文本「10月31日」**可编辑**，确认后创建进入）+「查看历史」 |

- 会话管理页：全部会话列表（今日标签），点击查看记录；每行菜单**重命名/删除**；顶部「＋新建会话」。
- 会话记录页：头部菜单**重命名会话/清空记录/删除会话/导出CSV**；每条记录菜单**复制单号/编辑/删除**。
- 昨天未完成/未上报的记录保持原样，重连后照常自动补传，**不合并**进今天的会话。
- 存储仍按 date 分组可按天查询与清理（照 media_store 的上限清理思路）。

## 6. 高效、精确扫码方案（本功能成败的核心）

技术底座：**CameraX（ImageAnalysis）+ ML Kit Barcode Scanning（bundled 版）**。
官方实时优化准则 + 快递单场景适配，按「采集 → 识别 → 确认 → 校验 → 去重」五层：

### 6.1 采集层（决定速度上限）

- **分辨率 1280×720（2MP 以内）**：官方明确「原始分辨率（10MP+）只增加延迟不增准确率」；
  快递单号条码（Code 128）在 720p + 合理取景框下足够。目标距离远再升 1080p。
- **`STRATEGY_KEEP_ONLY_LATEST`**（ImageAnalysis 默认反压）：分析器忙时新帧自动丢弃，
  永不积压——照官方建议，不加自定义背压。
- **YUV_420_888** 直通 ML Kit（InputImage.fromMediaImage），CameraX 自动处理旋转。
- **连续自动对焦**（`CameraControl` + `CONTINUOUS_VIDEO`/tap-to-focus），扫码页常亮
  （`FLAG_KEEP_SCREEN_ON`）+ 手电筒手动开关。
- **自动变焦**（ML Kit bundled 17.2+ 的 zoomSuggestion，支持的设备上）：远距小码
  自动推近，显著提升远处识别率；不支持则退化为手动双指变焦。

### 6.2 识别层

- **格式白名单**：这里的「格式」指**码制（symbology）**——条码/二维码的编码制式，
  不是「二维码的格式」。快递面单常见组合：单号条码 = CODE_128（一维码）、面单
  二维码区 = QR_CODE、纸箱外箱 = ITF-14、部分国际件 = CODE_39 / DATA_MATRIX /
  PDF417。在设置里限定白名单（默认 CODE_128 / CODE_39 / ITF / QR_CODE /
  DATA_MATRIX / PDF417）后，识别器只解这些制式：**既提速（排除大量无关候选），
  又防误识**（避免把包装上的商品 EAN 码、货架码误当单号）。
- ML Kit bundled 模型内置旋转/透视鲁棒性，优于老 ZXing；反色条码必要时镜像帧再喂一次
  （参考项目的 `alsoInverted` 思路）。

### 6.3 确认层（准确率的最后一道闸，官方建议）

- **多帧一致性投票**：视频流中识别器逐帧结果可能不同，**同一值连续命中 ≥2 帧
  才确认有效**（阈值可配，1 帧=最快，2 帧=推荐平衡，3 帧=最稳）。这是官方文档
  明确给出的防误识手段，参考项目（一帧一拍）没有这层，是我们能超越它的点。
- 取景框遮罩 + 命中高亮框，成功后震动/提示音（可设置），`resultDisplayDuration`
  式的短暂结果展示。

### 6.4 褶皱/倾斜/污损单据专项优化（针对参考项目痛点）

参考项目识别差的根因：底层是老 ZXing（Cordova 插件一帧一拍、固定算法、
无对焦/变焦策略），对形变、透视、污损的容忍度低。我们的对应手段：

| 单据状况 | 优化手段 | 原理 |
|---|---|---|
| 倾斜/旋转持握 | 无需特殊处理 | ML Kit 内置任意角度旋转识别（老 ZXing 需逐方向重试）；取景框引导正对即可 |
| 褶皱/弯曲（曲面） | 多帧投票天然互补 | 连续流里不同帧看到不同形变相位，「各帧单独解码成功」的概率叠加，正确值连续命中 2 帧即确认 |
| 褶皱严重、流式连续失败 | **高清单帧救援** | 连续 N 帧（如 12 帧 ≈ 1 秒）无结果 → 自动拍一张高分辨率静态图（4:3 1080p~1440p）走 ML Kit `SINGLE_IMAGE_MODE`（单图模式比流式模型更强，可反复细查），成功则入库并恢复流式 |
| 远距/单号太小 | 自动变焦 + 1080p 升档 | ML Kit zoomSuggestion 自动推近；弱设备手动双指变焦 |
| 弱光/阴影 | 手电筒常开选项 + 常亮屏幕 | 对焦与曝光稳定性 |
| 反光覆膜面单 | 微偏角提示 + 曝光调整 | 取景检测到长时间失败时提示「稍倾斜避免反光」（文案级优化） |
| 局部污损/缺角 | 手动输入兜底 | O/0、I/1、B/8 相似字符提示 + 正则校验 |
| 反色条码 | 镜像帧重喂 | 照参考项目 `alsoInverted` 思路 |

- 不引入第二引擎（华为 HMS Scan Kit 等各有强项，但会双倍模型体积与维护面）；
  单一 ML Kit + 「流式 + 高清单帧救援」双通道已覆盖绝大多数恶劣单据。
- 救援路径静默进行：用户无感知，只在成功后正常出结果；连续救援失败才提示手动输入。

### 6.5 校验层（业务级防错）

- **正则 filter**（照参考项目）：按会话配置「单号规则」（如长度 12~15、纯数字、
  指定前缀），不匹配提示错误并继续扫。
- **自带校验位校验**：EAN/UPC 系列校验位自验（ML Kit 已解码出 rawValue，本地可算）；
  注意官方说明 **ITF 和 CODE-39 无校验位**，不能依赖。
- **手动输入兜底**：O/0、I/1、B/8 相似字符提示；手动输入同样过正则校验。

### 6.6 去重与交互（五种策略，含自研「全局去重」）

| # | 策略 | 行为 | 适用 |
|---|---|---|---|
| 1 | 相邻去重（默认） | 只忽略与上一条相同的值 | 连续扫描防手抖重复 |
| 2 | 询问 | 每次重复弹窗 接受/丢弃，可勾选「记住选择」 | 偶尔确实要重复录 |
| 3 | 总是接受 | 重复照单全收 | 清点数量型场景 |
| 4 | 会话内全去重 | 今日会话内出现过即忽略 | 每件包裹只录一次 |
| 5 | **全局去重（Bridge 自研）** | **所有历史会话（含今日）出现过即忽略** | 防跨天重复收发同一单号混入今日列表 |

- 策略 5 的实现：本地持久化一个**全局已见值集合**（value → 首次出现日期），
  判重 O(1)；集合随历史会话清理策略同步裁剪（超 N 天且无重复风险后可归档剔除）；
  上报协议无需改动（服务端只收最终会话内容，去重纯客户端侧）。
- 参考项目只有前四种（`ask / always_accept / discard_adjacent / discard_scan_session`），
  第五种是 Bridge 新增能力。
- **同码冷却窗口（v0.1.95 新增，先于上述策略执行）**：同一单号处理过（入库/提示/弹窗）之后
  N 毫秒内（默认 2500ms，设置可选 1/2/2.5/3/5s）再次识别 → **完全静默忽略**（不弹窗/不响音/
  不提示）。场景：对准一个包裹扫码后手移动需要时间，摄像头会反复识别同一单号——不节流的话
  「询问」连续弹窗骚扰、「总是接受」连续入库同一条号。即工业扫描枪的 same-symbol timeout 惯例；
  手动输入豁免（人为操作无此问题）。实现：页面级 `cooldownMap`（value→lastHandledAt），
  >64 条时清理 60s 前旧项。
- **提示音感知（v0.1.95 重做）**：照成熟扫码软件惯例（ZXing BeepManager / 工业枪）——
  成功音 = 短促高频单音（`res/raw/scan_ok.wav`，2800Hz 150ms，穿透力强）；重复警示音 =
  明显不同的低频「两短响」（`res/raw/scan_dup.wav`，900Hz 80ms×2）；SoundPool 低延迟播放
  音量拉满；震动 60ms→250ms。进页面预热 SoundPool（解码异步，避免首扫无声）。
- **相机按需启停（v0.1.95，省电）**：页面级 `cameraActive` 总开关——单次模式扫完一条立即
  unbind 全部用例（取景框隐藏）；手动输入弹窗打开前停相机、关闭恢复；连续模式常开。
  再次点击「单次/连续」chip 或「继续扫描」才重新绑定。离开页面 onDispose 兜底：手电筒关 +
  分析器 release + 分析线程池 executor.shutdown()。

### 6.7 坑与注意

- **Compose + CameraX**：`PreviewView` 用 `AndroidView` 包装；页面 onDispose 必须
  `unbindAll()` + 释放 ML Kit executor（照 RelaySwitch onDispose stop() 的省电纪律）。
- **单例状态陷阱**：扫码列表 UI 照 v0.1.92 经验直接用「不可变快照 + 参数化」，
  不要重复踩 LazyVerticalGrid 漏刷的坑。
- **相机权限**：动态申请，拒绝时给手动输入入口（功能不因权限死掉）。
- **相机占用冲突**：与投屏/直播/摄像头命令互斥——扫码页激活期间应拒绝或提示
  `screen_live`/camera 类命令（服务端 catalog 加状态检查，或客户端弹提示）。
- minSdk 24 与 CameraX/ML Kit 兼容（ML Kit 要求 21+，无冲突）；ML Kit bundled 增量
  包体积约 2~3MB，可接受。
- 明文 HTTP 上报走服务器已有 token 鉴权；若部署公网建议 HTTPS（现有 Bridge 服务端
  同样问题，不新增风险）。

## 7. 需要实现的清单（工作量拆解）

**客户端（app + core）**
- `scanner/` 新包（区分 `switch/`）：`ScanCaptureAnalyzer.kt`（CameraX Analyzer + ML Kit
  + 多帧投票 + 高清单帧救援）、`ScanPrefs.kt`（服务器/会话/去重策略（含全局已见值
  集合）/格式白名单）、`ScanState.kt`（不可变快照）、`ScanUploader.kt`（REST 批量
  上报 + 离线队列 + 对账）
- `ui/ScanPages.kt`：扫码页（取景框 + 模式选择 + 会话列表 + 结果卡）+ 设置弹窗
- 接入：`MainScreen.kt` HomePage tabs 追加「快递扫码」；设置连接页加分区
- gradle：`camera-camera2/camera-lifecycle/camera-view` + `mlkit-barcode-scanning`

**服务端（server/）**
- `core/scan_store.py`：会话存储 + 上限清理（照 media_store 模式）
- `api/v1/scan.py`：POST/DELETE/GET + 设备 token 鉴权 + admin_event_bus 广播

**管理后台（admin/）**
- 新「扫码记录」页：设备筛选 + 实时刷新（event_bus）+ CSV 导出（照 `ScanModel.ToCSV` 语义）

**不做**：Output 模板管线（number/if/http 等高级组件）、键盘模拟、mDNS 发现、
NFC、license——超出快递单号场景需要。

## 8. 参考文件索引

| 文件 | 内容 |
|---|---|
| `references/Barcode to PC/barcode-to-pc-app-master/src/providers/server.ts` | WS 连接/心跳/重连/helo/会话同步全逻辑 |
| `.../src/providers/scan.ts` | 四种采集模式、去重策略、filter 校验、Output 管线执行 |
| `.../src/models/request.model.ts` `response.model.ts` | 协议 action 与字段定义（权威还原依据） |
| `.../src/models/scan.model.ts` | displayValue 拼接 + CSV 导出算法 |
| `.../src/providers/config.ts` | 端口 57891、zeroconf 服务名等常量 |
| `references/Barcode to PC/简单使用说明.txt` | 中文使用流程（模式语义/重复码行为） |
| `references/Barcode to PC/barcode-to-pc-server-master/electron/` | **空**（submodule 私有，服务端核心不可得） |

ML Kit 实时性能官方准则：developers.google.com/ml-kit/vision/barcode-scanning/android
（分辨率 ≤2MP、KEEP_ONLY_LATEST、多帧一致性确认、格式白名单）。
