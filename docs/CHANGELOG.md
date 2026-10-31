# Bridge 变更记录（CHANGELOG）

> 记录版本里程碑与关键修复脉络。细节见对应专题文档，工作日志见 `.workbuddy/memory/`。

---

## 📐 里程碑记录规范（新增条目照此执行，源自 v0.1.83→v0.1.93 梳理经验）

1. **先对齐事实源，再动笔**：以 git 提交 + 工作日志（`.workbuddy/memory/` 当日文件）逐版核对；
   本地历史断档时以工作日志为准（其中逐版记录了提交 sha 与改动内容）。
2. **按脉络组织，不按版本堆砌**：一个里程碑 = 一条完整脉络（问题 → 演进 → 收敛），
   版本号只是脉络上的节点；并行多条主线时合并为一个里程碑，开头「节点意义」点明几条脉络，
   各设「脉络N · 名称（版本区间）」小节。
3. **逐版本一条精炼记录**：每版一行「**版本 + 定位短语**：根因 → 修复/决策」；
   是补丁就标「（仍是补丁）」，是根治就标「根治」，让收敛路径一眼可见。
4. **提炼不照搬**：日志是全量细节，CHANGELOG 只留「以后还会用到的」——根因结论、
   方案取舍、验证结果与边界；文件清单、命令、临时坑留给专题文档和日志。
5. **结尾必有「关键经验沉淀」**：每条 = **结论加粗** + 一句根因/证据 + 一句可复用做法；
   禁止写成流水账，只收跨版本、跨场景仍成立的教训。
6. **写完自查**：每个版本区间是否都有对应条目（不许跳版）；每条是否回答了
   「当时为什么坏、现在为什么好、以后怎么避免」。

---

## 2026-10-31 · 🛒 v0.2.09 销售页集成里程碑（售后管理系统吞并 + 沉浸导航）

**节点意义**：把参考项目 `references/SalesRecordRemote`（售后管理系统）的**客户端功能**完整移植进 Bridge，
在主页与设置之间新增底部导航「销售」页，客户端直连参考服务器（Bridge 服务端零改动）；
辅线是移动端沉浸导航适配（销售表格多列需要更多横向空间）。

### 脉络1 · 销售页功能移植（客户端直连参考服务器）

- **调研与原型先行**：通读参考项目三端（FastAPI 后端 + Vue3 前端 + uniapp H5Plus 打包产物），
  产出集成调研 `docs/research/salesrecord-remote-integration.md` 与可交互原型
  `assets/design/ui-mockup-sales-page.html`，经用户核验后落地。
- **数据层自研**：新增独立 `app/.../sales/` 包——`SalesModels`（15 列模型 + 角色权限 admin/manager/operator
  + 列配置）、`SalesPrefs`（登录态/token/服务器地址/产品类型/列配置持久化）、
  `SalesApiClient`（OkHttp 直连，Bearer 鉴权 + 409 强登 + 智能填充 + 物流查询）、
  `SalesWsClient`（心跳 30s/判死 60s/重连 3s + 编辑锁 + 他人操作通知 + 强制下线）、
  `SalesState`/`SalesController`（状态容器 + 业务编排）。
- **UI 复刻**：`ui/SalesPages.kt` 复刻登录/产品切换/搜索筛选/表格/双击单元格编辑/长按行菜单/
  签收取件切换/物流查询与智能提示/新增（智能自动填充）/列配置/导出/阅读模式/权限差异。
- **移动适配**：表格移动端默认 4 列、阅读模式 5 列；双击=编辑、长按=行菜单（参考 APK 未实现长按，
  Bridge 补做）；阅读模式进入锁横屏/退出恢复原方向；REF- 电话前缀仅展示剥离；编辑锁义务按次释放。

### 脉络2 · 沉浸导航（销售页专属，不影响现有页面）

- 底部导航由「主页·设置」扩为「主页·销售·设置」，销售页进入即隐藏底部导航、表格区自动增高；
- 销售页顶栏与登录页提供「返回导航」，点击仅恢复导航、不退出销售页、不清空产品类型/筛选/分页/表格数据；
- 再次点击「销售」重新沉浸；主页 4 页面排序（TabOrder id 0..3）与设置页逻辑零改动；
- 返回键：销售页沉浸时走「再按一次退出」，导航已恢复时按返回重新沉浸。

### 关键经验沉淀

- **参考项目只读，移植自成体系**：`references/` 全程零改动；Vue 源码做业务依据、
  APK 打包产物做移动行为校准；移植用 Kotlin/Compose 复刻语义而非照搬 Web 技术，避免影响 Bridge 保活/权限体系。
- **语言差异是 bug 高发点**：表格横向滚动/双击与长按手势分层/409 强登文案/编辑锁释放路径，
  均需在移植时显式处理而非假设等价。
- **大功能分层推进、逐层编译**：先数据层（编译通过）→ UI 层（编译通过）→ 导航接入（编译通过），
  每步 `compileDebugKotlin` 兜底，避免一次性大改引入难定位问题。

### 脉络3 · 表格空白修复（v0.2.10→v0.2.17，约 10 轮排查）

- **v0.2.10 竞态根因（第一层）**：进入销售页 `enterPage` 并发触发 `refreshMe`（`GET /auth/me`）
  与 `refreshAll`（拉列表）。`refreshMe` 复用 `loginSuccess` 把 `records` 清空为 `emptyList`；
  当 `me` 比列表晚返回时，刚填充的记录被清空 → 表格空白。修复：新增 `SalesState.refreshUser`
  （只更新 user/登录态/产品类型，不清列表），`refreshMe` 改用它。
- **v0.2.11~v0.2.12 数据层定位**：加诊断日志确认「loadList 成功 total=1018 records=100」数据已进 state，
  ADB 截图 + uiautomator dump 铁证表格数据区零节点（表头、分页栏正常）。曾误判为 LazyColumn 在
  horizontalScroll 内测不出有界视口，改普通 `Column+verticalScroll+horizontalScroll`（仍是补丁）。
- **v0.2.13~v0.2.14 视口塌缩**：数据体 `fillMaxHeight`/`matchParentSize` 在 horizontalScroll 容器内
  高度测出≈0，LazyColumn 只 compose 1 个 item（id 固定 1052）且不绘制（仍是补丁）。
- **v0.2.15~v0.2.16 高度链诊断**：四处 `onGloballyPositioned` 打印整条高度链，
  锁定 `数据体 Box(weight) h=0`。
- **v0.2.17 真凶（根治）**：表头 Row 用 `heightIn(min=44.dp)`（只有 min 无 max）+ 内部
  `Row(fillMaxHeight)` + 每列 `Column(fillMaxHeight)`，在 Column 测量时把表头撑满整个剩余空间，
  `weight(1f)` 的数据体分到 0。修复：表头 Row 改固定 `height(44.dp)`，数据体正确分到高度，100 行正常显示。

### 脉络4 · 交互与省电收尾（v0.2.18→v0.2.20）

- **v0.2.18 四项优化**：①返回键适配——销售页沉浸态按返回=点左上角退回箭头（显示底部导航栏），
  再按=隐藏导航栏（重新沉浸），替代原「再按一次退出」；②搜索框高度调低；③分页栏「每页」防换行
  （换行会撑高分页栏挤占表格高度）；④删除设置→通用中的纯占位符（深色模式/消息免打扰/语言/清理缓存）。
- **v0.2.19 对照参考项目修正**：搜索框弃用 OutlinedTextField（默认 minHeight 56dp + contentPadding
  上下 16dp，height(36dp) 硬压导致文字垂直裁剪），改 `BasicTextField` 自绘边框（对照 `.search-row input`
  height 32px/padding 0 10px）；分页栏 TextButton 默认 contentPadding 左右 12dp + minWidth 58dp 太宽，
  把「100/页」挤出屏幕，压缩到左右 4dp、字号 12sp（对照 `.pager` height 32px/gap 6px）。
- **v0.2.20 退出资源释放省电加固**：对照智能控制/快递扫码 `stop()` 彻底释放模式，补齐销售页退出释放——
  `SalesApiClient.close()`（`connectionPool.evictAll` + `dispatcher.executorService.shutdown` 回收空闲连接、
  停线程池），`exitPage()` 调用 `api?.close(); api=null` 并清残留 loading 状态，`ensureInit` 懒重建。

---

### 关键经验沉淀（销售页空白 10 轮排查的最终教训）

- **`weight(1f)` 分到 0 高度，先看排在前面的兄弟节点**：别只盯着 `weight` 那一层——要先查同一 Column 里
  排它前面的兄弟是否用 `heightIn(min=xx)`（无 max）+ 内部 `fillMaxHeight` 把剩余空间全吃掉。固定高度的
  兄弟必须用 `height(固定值)` 而非 `heightIn(min)`。排查顺序：先看「谁吃掉高度」再看「谁没分到高度」。
- **LazyColumn 只 compose 1 个 item = 视口高度塌缩**（不是数据问题）；用 `onGloballyPositioned` 打印
  各层真实尺寸可一次锁定塌缩层。
- **横向/纵向滚动必须分离**：`Row(horizontalScroll)` 包 `Column(verticalScroll)`，绝不能同一 Column 叠加
  双 scroll（maxHeight=0），也不能 horizontalScroll 包 LazyColumn（视口塌缩）。
- **Material3 输入框要紧凑高度须自绘**：OutlinedTextField 有默认 minHeight 56dp + contentPadding 上下 16dp，
  用 `height()` 硬压会垂直裁剪文字，必须改 BasicTextField 自绘边框。
- **省电对照检查法**：新页面接入时对照已有页面的 `stop()/onDispose` 释放清单逐项核对（WS 断开、连接池回收、
  线程池 shutdown、监听器置空、残留状态清理），避免「主体正确但漏一处」的隐性耗电。

---

## 2026-10-31 · 📦 v0.2.03→v0.2.07 扫码 PC 协同里程碑（音效对齐 + 上报通路多样化 + 服务器状态识别）

**节点意义**：一条主脉络贯穿——快递扫码从「本地闭环」升级为「与 PC 端销售记录系统深度协同」：
音效/播报与 PC 一一对齐 → 上报通路从单一扫码扩展到四处 → PC 端新旧状态可视可判；
辅线为扫码模块省电审计收敛。**最新客户端 v0.2.07。**

### 脉络1 · PC 协同深化（v0.2.03→v0.2.07）

- **v0.2.03 音效四路对齐**：本地重复→warning、回执 duplicate→重复录入（改由回执触发重播）、
  miss→取消、hit→新增系统 TTS 播报「receiver town」（文案与 PC 端 text2speech 一致，
  QUEUE_FLUSH 连续扫码只播最新）；TTS 随 SoundPool 进页预热防首播无声。
- **v0.2.04 上报通路多样化**：①扫码页重复提示卡点击补报（不入库不重复自动上报，点卡片才报）；
  ②记录列表单击=上报；③长按多选 + 全选/全取消切换 + 全部发送(N) 批量上报；
  回执分发统一 `dispatchScanReceipt`（四条路径同构），新增 `uploadText()` 免记录补报。
- **v0.2.05 音量治理**：设置新增「提示音音量」滑杆（SoundPool+TTS 同源系数 0~100%）；
  实测揭穿 v0.1.96「音源已满幅」的错误注释——5 个 wav 实为 -2.7~-12.6 dBFS，全部归一化
  到 0dBFS；省电审计：相机/WS/分析器释放链路复核通过，补上唯一漏网 TTS 引擎释放。
- **v0.2.06 旧版识别（回执特征）**：新增 `LEGACY` 状态——上传后 6s 无 scanResult 回执即判
  「PC旧版·无回执」（新版每条上传必回、旧版只回空确认，纯行为特征零版本维护）；
  回执迟到自愈回 ONLINE；LEGACY 下上报仍放行（旧版也能收单）。
- **v0.2.07 旧版识别提速（连接即判）**：v0.2.06 需上传一次才能判定 → PC 端 pong 附加固定
  能力标志 `server:'SalesRecordSystem'`（能力标志非版本号，App 只判字段存在性，一次改动
  永久有效）；Bridge 连接即发应用层 ping，3s 内无标志 pong 即判 LEGACY。

### 脉络2 · 事故复盘驱动的可观测性（贯穿）

用户实测「PC 播音手机不响」→ 排查发现 PC 端跑的是 7 月编译的旧 exe（源码已升级但进程
未更新）。此事故直接催生 v0.2.06/07 的服务器状态识别体系（OFFLINE/LEGACY/ONLINE 三态）。

**关键经验沉淀**
- **音量天花板三段相乘**：最终响度 = 音源电平 × App 音量 × 系统媒体音量；「播放参数拉满」
  ≠「实际响度拉满」，音源电平要用实测（PCM 峰值）验证而非信注释；16bit 硬顶 0dBFS，
  突破即削波失真——调响只能向下（滑杆）或向上（系统音量），App 无法超越物理极限。
- **识别远端版本用「行为特征/能力标志」，不用版本号**：版本号需要两端同步维护必腐化；
  行为差异（回不回 scanResult）零维护但需触发一次，能力标志（pong 带字段）零维护且
  连接即判——两者叠加互为兜底是最优解。
- **打包产物与源码脱节是隐形事故源**：桌面程序「平时 exe、开发时源码」双运行方式下，
  排查先核对进程启动时间 vs 代码修改时间（本例差 3 个半月）。
- **回执分发要一开始就抽公共函数**：四条上报路径共用 `dispatchScanReceipt`，
  v0.2.03→07 五次音效/判定演进每版只改一处。

---

## 2026-10-30 · 📷 v0.1.94→v0.2.02 扫码功能里程碑（快递扫码闭环 + 崩溃清零 + PC 上报）

**节点意义**：三条脉络交汇——①快递扫码功能从零到闭环；②保活崩溃三根因连环排查清零；
③扫码结果上报 PC（扩展 Barcode to PC 协议）。

### 脉络1 · 快递扫码（v0.1.94→v0.1.97，v0.2.02 补完）
- **v0.1.94**：CameraX 720p + ML Kit 连续 2 帧投票 + 1080p 单帧救援；会话按自然日管理、5 种去重策略、码制白名单。
- **v0.1.95**：真机 11 条反馈全落地（同码冷却 2500ms、单次扫完停相机、会话/记录管理、返回键入口逻辑等）。
- **v0.1.96/97**：音效切 USAGE_MEDIA + 用户成品 WAV（扫描咻/重复录入）；返回键统一 goToEntry。
- **v0.2.02**：扫码成功动画（取景框四角闪色外扩 + 中央绿勾描画徽章 overshoot + 结果卡上滑/重复轻摇）；PC 上报。

### 脉络2 · 保活崩溃清零（v0.1.95→v0.2.01，同签名三根因）
- **v0.1.98**（弯路）：CrashReporter 上报时序修复（runBlocking 3.5s + 启动补传）——上报链路自此打通，但根因判断错误。
- **v0.1.99**：根因①promote 吞异常（DiagLog + 350ms×3 重试 + stopSelf）。仍崩。
- **v0.2.00**：根因②ColorOS 冻结饿死（兜底 goAsync/返回 true 保持「处理中」4s 撑解冻窗口）。仍崩。
- **v0.2.01**：根因③运行中重复拉起义务悬空（onStartCommand 无条件补挂前台，幂等）+ 同款修复推广到 AudioRelayService/MediaCaptureService + 蓝牙焦点改「正常补获」消除唤醒放大器。**27 分钟观察期（跨 2 次周期 Job）三路证据零崩溃，闭环**。权威文档 `docs/dev/crash-ledger.md`。

### 脉络3 · PC 上报（v0.2.02）
- 调研 `SalesRecordSystem`（PyQt5 桌面退货系统）：WS 0.0.0.0:57891 收 Barcode to PC 协议，业务=退货签收登记（匹配→已签收+√+xiu+TTS / 重复=chongfuluru / 未匹配=quxiao）。文档 `docs/research/salesrecord-upload-flow.md`。
- **协议扩展（向后兼容，Barcode to PC 旧客户端零影响）**：服务端 `barcode_received` 信号加回执闭包参数 + 业务槽上报 `scanResult{hit|duplicate|miss}`；Bridge 端 `ScanUploadClient`（OkHttp WS + 15s 心跳 + 指数退避重连）逐条实时上报，扫码页 PC 在线徽标，miss 回执播「取消」+卡片提示。离屏冒烟测试全链路通过。

**关键经验沉淀**
- **startForegroundService 是「预约一次前台化义务」，按次计**：运行中服务被重复拉起只走 onStartCommand，onCreate 的挂前台覆盖不了；义务 ~30s 无人履行必崩。修崩溃先看 dropbox `Frozen:`/`Process-Runtime:` 区分「冻结饿死」vs「运行中义务悬空」，同签名≠同根因。
- **崩溃修复必须先确认代码是否执行**：服务端 DiagLog 缺失本身就是证据（v0.1.99 修错层的直接原因）。
- **兜底组件保持「处理中」4s**（JobService 返回 true/Receiver goAsync）：立即返回=进程瞬间可被冻结，onCreate 饿死；jobFinished 未调会引发 ~44s 崩溃循环，force-stop 止血。
- **第三方桌面系统集成先全链路读实现再动手**：协议、信号链、音效映射（Bridge 两个音效与服务端 xiu/chongfuluru 同源）都是读源码实锤的，不是猜的。
- **改共享协议只做「新增」**：信号加参用缺省值兜底、新 action 旧端不读即无感——旧客户端兼容性有测试兜底。

---

## 2026-10-29→10-30 · 🚀 v0.1.83→v0.1.93 双脉络里程碑（音频中继重连自愈 + 智能控制上线）

**节点意义**：两天内两条主线先后收敛——①音频中继「切网必卡死」的历史顽疾经四版（83→86）
根治；②ESP32 继电器控制（智能控制）从调研到命令确认层七版（87→93）从零上线。
**最新客户端 v0.1.93。**

### 脉络一 · 音频中继「切网重连」自愈（v0.1.83→v0.1.86）

问题本质：手机放音走 Aurelay 第三方中转（与 Bridge 主服务是两套独立连接体系），
切网络后出现「正在接收但无声」甚至永久卡死。四版递进，确立「不点停止就永远尝试接收」：

- **v0.1.83 自愈骨架**：网络监听（onLost/onAvailable 秒级触发）+ 心跳 60s 超时判死 +
  重连前重新上报（清服务端旧会话让 PC 重新撮合）。
- **v0.1.84 两个重连 bug**：① Aurelay 重连是「一次性退避循环」，重试用尽后网络恢复也无人
  再触发 → 改**常驻 watchdog**（1/2/4/8/16/30s 退避，绝不因重连次数退出）；② 主连接退避
  指数溢出（2^63 → Long.MIN_VALUE → 负 delay 疯狂空转 1.7 万次）→ 改 `1L shl coerceIn(0,6)`。
- **v0.1.85 切网竞态**：三路网络回调密集并发 forceReconnect，留下「TCP 已连但 hello 未送达」
  的半死连接 → generation 递增作废在途回调 + 500ms 防抖 + interrupt 唤醒 watchdog 立即重连。
- **v0.1.86 半死连接判死（收敛版）**：日志证明 pingLoop 心跳判死从未触发过，判死下沉到
  watchdog：onOpen 后 **10s 未收到 ready 强制重连** + 就绪后 60s 失联判死。实测多次切网
  1 分钟内自愈，剩余延迟来自 PC 端握手超时 + 服务器 90s 空闲期的「慢循环」，可接受。

### 脉络二 · 智能控制（ESP32 继电器）从零上线（v0.1.87→v0.1.93）

方案 A：客户端主动直连参考中继服务器（`references/server/` 零改动）。新增 `switch/` 包
（与 `relay/` 音频中继严格命名隔离），主页「发现」Tab →「智能控制」。七版演进：

- **v0.1.87 初版**：单设备卡 + 窗格模式 + 二次确认 + 设置分区 + 自动重连；
  图标 `PathParser` 1:1 还原参考项目手写 SVG；连接生命周期页面级（进入 start、离开 stop）。
- **v0.1.88 体验补齐**：设备下拉选昵称；onOpen 补 subscribe+get_status（此前不订阅就永远
  收不到状态广播）；窗格补粒子特效；「执行中」置灰转圈中间态；针对 ESP32 休眠（2~10s）
  加 2s×8 次状态轮询。
- **v0.1.89 诊断先行**：revision 版本号补丁 + DiagLog「智能控制」埋点，打通「客户端上报日志
  + API 查询」远程排障链路——为后续定位立功。
- **v0.1.90 日志实证**：数据链路全通（set/轮询/收到 status 全有日志），问题在 UI 重组层；
  LazyVerticalGrid 的 items diff 陷阱 → `key(revision)` 强制重建内容区（仍是补丁）。
- **v0.1.91 根治**：逐行对照参考项目揪出四处硬伤——服务器地址未归一化（用户填 `http://`
  前缀 → `Unable to resolve host "http"`）、窗格持久化后永不订阅、device_online 未补订阅、
  离线闪烁加宽限；状态层重写为单一不可变快照 `SwitchUiState` + 全参数化。
- **v0.1.92 照参考完全重写**：砍掉自研「执行中/轮询/离线宽限」，状态仅由
  `connected + device.online + statusCache` 三量推导；点击只发 set + Toast 纯等广播；
  固定 3s 重连；窗格点卡片=选中切单卡。
- **v0.1.93 命令确认层**：按「设备+端口」登记 pending 禁用按钮（防重复点击），15s 未确认
  自动补 get_status 再等 10s；仍无回应按钮变「重试开启/关闭」，按原目标值重发**绝对 set**
  （幂等，不误反转）。

### 关键经验沉淀

- **半死连接治理三板斧**：① 判死不能只依赖单一心跳线程（pingLoop 判死日志证明从未触发）；
  ② 所有「可能半死」连接的 send/close 必须带超时；③ 网络回调会密集并发，必须 generation
  + 防抖，否则留下「连上但握手未达」的假活连接。
- **重连退避防溢出**：`2.0.pow(attempt)` 在 attempt 大时会溢出 Long 为负 → 负 delay 疯狂
  空转；用 `1L shl coerceIn(0, 6)`。
- **Compose 漏刷的根治法是「不可变快照 + 参数化」**，revision/key() 打补丁都是徒劳；
  LazyVerticalGrid 的 items diff 不受 lambda 捕获值变化影响。
- **远程排障链路是攻坚底座**：DiagLog 埋点 → upload_log → 服务端 API 查询，v0.1.90 正是
  靠它把「猜 UI」变成「日志实证」。
- 参考项目服务器地址默认带协议前缀，客户端拼接前必须归一化；状态更新唯一可靠途径是
  `subscribe` + 服务端广播，`get_status` 只是转发等回报；继电器 `set` 是绝对状态，重发天然幂等。

---

## 2026-10-29 · 🏁 日志系统重构 + 数据库可靠性治理 + 侧边栏 UI 里程碑（v0.1.82）

**节点意义**：自 10-28「阶段性功能完善总结」以来，围绕「日志可追溯」「数据库可靠性」
「管理后台体验」三大主线连续多轮攻坚（v0.1.76 → v0.1.82），全部落地并经用户实测确认。
**最新客户端 v0.1.82，服务端/管理后台持续演进。**

### 一、日志系统彻底重构（Timber 桥接 → 落盘轮转 → 按行存储 → 去重 → 分页）

- **链路打通**：新增 `DiagLogTree` 桥接，让全项目 50+ 模块的 Timber 日志统一进 DiagLog 环形缓冲，
  release 包也不再「无日志可查」；后台下发 `upload_log` 即可拉到网络层/无障碍/媒体/短信等关键日志。
- **落盘 + 轮转**：DiagLog 从「内存 600 条不落盘」升级为「单文件 1MB × 5 份落盘 + 单线程异步写 + 自动清理」，
  重启/崩溃后历史可追溯；崩溃上报前 `flushToFile()` 尽力刷盘。
- **按行存储重构**：日志从「整块 dump 文本一条」重构为「结构化 LogLine 行（ts/level/tag/message）」，
  支持按级别/模块/关键词过滤 + 分页；MySQL `device_logs` 表唯一索引 `(device_id, ts, line_hash)` + `INSERT IGNORE` 去重。
- **多行堆栈防御性收口**：反编译 Timber 确认「Throwable 完整堆栈用 `\n` 拼进 message」根因，
  客户端 `flattenNewlines` 转义 + `parseFileLine` 解析失败即归并（无条件兜底，非针对单一异常）。
- **服务端持久化**：日志 MySQL 落库（`retention_days=30` 按天清理 + `max_entries_per_device=2000` 兜底），
  分页接口 + 昵称优先 + 日志倒序 + 三路并集（日志表∪注册表∪在线连接）设备列表。

### 二、数据库可靠性治理（三类报错根治）

- **每操作独立连接**：12 个 store 中 8 个（location/contacts/calllog/sim/admin_token/connection_event/media/log）
  统一改为「每操作独立连接」contextmanager 模式，根治 pymysql 单例连接被线程池并发复用导致的
  `Packet sequence number wrong` / `Lost connection` 协议错乱。
- **事件循环阻塞根治**：`logs.py` 端点 MySQL 读写改 `run_in_executor` 转线程池；`add()` 逐行 INSERT 改 `executemany` 批量，
  彻底解决「upload_log 后设备 20 秒断连重连」。
- **设备 token 持久化**：新增 `DeviceTokenStore`（仿 `AdminTokenStore`），设备 token 落库 + 启动预热，
  根治「服务端重启后内存注册表清空 → 客户端 401」；客户端配套「4004 自动重注册」兜底（双保险）。

### 三、蓝牙按键监听恢复（零额外功耗版）

- 复用常驻 `KeepAliveService` 语境，创建 MediaSession + 申请一次 AUDIOFOCUS_GAIN，**零定时巡检/额外功耗**；
  目标收窄为「其它 App 不出声时能收到按键」，焦点回调被动重抢；Toast 单实例复用、后台展示改全局弹窗。
- 音频中继「播放几秒就崩」根治：真实根因是 `startForegroundService` 未同步 `startForeground()`
  （前台化断链），非 AudioTrack 竞态。

### 四、管理后台侧边栏 UI 重构

- 收缩态：宽度自适应（图标 24px + 左右对称留白）、图标正方形撑满、菜单项高度/间距收紧、外壁 2px 空隙；
- 展开态：宽度**按最长菜单项文字自动测量**（JS 动态计算，非固定 220px）、内容靠左、右侧贴合；
- 修复多个 Element Plus 菜单高优先级样式覆盖导致的「左侧大空白」「图标盒内偏右」等错位问题。

### 关键工程教训沉淀

- 依赖唯一索引做去重，建索引失败**必须显式告警**，绝不能 `except: pass` 静默吞（否则功能静默失效）。
- `startForegroundService` 必须在 onStartCommand 内尽快 `startForeground()`，绝不能只 notify。
- 任何走 MySQL 的端点：async def 就要 `run_in_executor`，否则改同步 def（反复栽的坑）。
- 有真机必抓 `adb logcat`，别靠猜 native 崩溃。
- Element Plus 菜单收缩态宽度写死 64px、非收缩态 `padding-left` 高优先级，自定义样式需针对性覆盖。

---

## 2026-10-28 · 音频中继（集成 Aurelay 中继模式）✅ 功能落地

**节点意义**：调研 Aurelay 开源源码后，将其「中继模式」（手机放音）合理精简并
集成进 Bridge，作为主页「音频中继」页 + 设置「连接 → 音频中继」分区。**后端零改动**
——复用 Aurelay 中心服务器（`039039.xyz:15151`），Bridge 与 Aurelay 原版可同时接入。

### 集成范围（只保留中继模式）

| 保留 | 砍掉 |
|---|---|
| 中继内核：`RelayProtocol`（ARLY 帧）+ `RelayClient`（OkHttp WS 出站）+ `OpusDecoder`（统一 CSD 83 字节） | 电脑放音（OpusEncoder / AudioCaptureService / DeviceStore） |
| AURL 流解析 + AudioTrack 播放（含 stop 不改 state、会话代次、魔数扫描坑） | 局域网直连 / TLS / auto 链路切换 |
| 24 柱波形 + 中继音量（AudioTrack 软增益，不改系统音量） | 广告内购（AdsManager / BillingManager） |

### 文件落点

| 文件 | 说明 |
|---|---|
| `app/.../relay/RelayProtocol.kt` | ARLY 帧协议（与 Aurelay 逐字节一致） |
| `app/.../relay/RelayClient.kt` | 中转出站 WS（JSON hello 握手 + 二进制音频帧 + 退避重连） |
| `app/.../relay/OpusDecoder.kt` | 系统 Opus 解码（统一 CSD 防永久静音） |
| `app/.../relay/AudioRelayService.kt` | mediaPlayback 前台服务（AURL 解析 + AudioTrack + 24 柱波形） |
| `app/.../relay/RelayPrefs.kt` | 配置持久化（服务器 / 自动重连 / 音量 / token） |
| `app/.../ui/RelayPages.kt` | 主页「音频中继」页（状态卡 + 波形 + 音量 + 启停） |
| `app/.../ui/SettingsPages.kt` | 连接页新增「音频中继」分区（服务器编辑 + 自动重连二次确认） |
| `app/.../ui/MainScreen.kt` | 主页「通讯录」tab →「音频中继」 |

### 关键技术决策

1. **后端零改动**：复用 Aurelay 中转服务器 `039039.xyz:15151` + `/api/aurelay/relay` 路径，
   握手发 JSON `hello`（device_id/token/role），与原版完全兼容。
2. **复用 Bridge 已有能力**：`core-keepalive` 的 SilentPlayer（播放时让位）、OkHttp 4.12.0、
   DiagLog 诊断日志、DataStore device_id（ANDROID_ID）。
3. **交互自服务**：客户端本地自服务（用户手动开始/停止），非管理后台下发。
4. **音量语义**：中继音量 = `AudioTrack.setVolume(0~1)` 软增益，仅调中继播放流，不碰系统音量。
5. **自动重连二次确认**：默认开，关闭时复用核心项确认样式（⚠️ + 红色「确认关闭」）。

---

## 2026-10-28 · v0.1.69 ✅ 蓝牙耳机按键监听功能彻底剥离（里程碑：功能放弃 + 经验沉淀）

**节点意义**：蓝牙耳机按键监听功能历经多轮实现与排障（v0.1.60→v0.1.68）后，
确认目标在 Android 系统机制下**物理上不可达成**，最终彻底剥离。这是一次
「技术方案验证 → 触及系统硬边界 → 果断止损 + 经验沉淀」的完整闭环，值得记录
为工程决策里程碑。

### 剥离原因（核心结论）

> Android 蓝牙媒体按键（AVRCP）是**一对一的音频焦点竞争**：耳机按键永远只发给
> 「当前持有音频焦点」的那个 App。当另一个媒体 App（如 Aurelay）正在**出声播放**
> 时，它持有焦点，`requestAudioFocus(AUDIOFOCUS_GAIN)` 会被系统**拒绝或延迟**
> （返回 `FAILED` / `DELAYED`），系统不允许一个 App 强行打断正在出声的 App。
>
> 因此「无论何时 Bridge 都拿得到按键」这一目标，在系统级焦点仲裁规则下无法达成
> ——这是平台硬限制，不是代码缺陷。

### 剥离清单

| 层 | 改动 |
|---|---|
| 客户端 | 删除 `BluetoothHeadsetObserver.kt`（整文件）；`BridgeApplication.kt` 移除注入 + `start()` |
| 服务端 | `ws.py` 删除 `_handle_media_button()` + `event=="media_button"` 分支 |
| 文档 | `docs/keepalive/design.md` 新增附录沉淀经验；`TodoList.md` 标记已剥离 |

### 明确保留（与蓝牙按键无关，未触碰）

- **`SilentPlayer`**（保活「无声播放」锚点，受「后台资源占用·细化」开关控制，默认开）
- **`ResourceHolder`** 中的 BLE 扫描（保活资源锚点）
- **`app_version`** 版本上报（hello 握手 / 日志 / 崩溃上报通用机制）

### 经验教训（详见 `docs/keepalive/design.md` 附录）

1. 收到媒体按键的三条件缺一不可：`MediaSession.setActive(true)` + 持久音频焦点
   （`AUDIOFOCUS_GAIN`，瞬时焦点会被静默收回）+ `PlaybackState.setState(STATE_PLAYING)`
   （只 `setActions` 不 `setState` 会停在 STATE_NONE，系统不路由按键）。
2. 保活的「无声播放」锚点（`SilentPlayer`）**不申请 AudioFocus**，与「抢焦点」
   是两套独立机制，不能混用；「抢过正在出声的 App」必须让 MediaSession 真正占用
   音频输出，单靠 requestAudioFocus 抢不过。
3. 排查神器：`adb shell dumpsys media_session`（看 `Media button session` 指向）+
   `adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` 模拟按键。

---

## 2026-10-28 · 🏁 阶段性功能完善总结（三端功能清单）

**节点意义**：自 v0.1.55 流式链路稳定里程碑以来，连续多轮 UI/UX 与数据链路打磨
（v0.1.55→v0.1.59 + 多轮未打 tag 改进）全部落地并经用户实测确认，三端功能形态
达到一个小阶段完备状态。**最新客户端 v0.1.59（此后 APK 零改动），服务端/管理后台
持续演进。**

### 客户端 APK（v0.1.59）

- **连接与保活**：WS 长连接 + 自研轻量保活（前台服务 > 电池白名单 > 开机自启 >
  双兜底）；「后台资源占用·细化」弹窗开关实时生效
- **命令执行**：全量命令目录（截屏/录屏/拍照/录像/录音/音量/SIM/网络/位置/应用/
  文件/媒体控制等），见 `docs/api/commands/`
- **实时流**：投屏直播（FLV）+ 流式录像/录音；二进制 WS 帧（省 33% 上行）；
  画质档位 hd/smooth；低延迟模式；CBR 码率（失控根治）；静止时重发上一帧；
  不限时采集（duration=0）
- **短信**：**聚合模式默认开启**（服务端存档所有设备，会话列表+聊天气泡详情+
  游标分页，本机标绿）；「聚合/本机」切换；已读 Bridge 内部映射（免默认身份）；
  默认短信应用一键切换
- **主动上报**：位置周期上报（WorkManager 兜底）+ 新短信即推（ContentObserver）
- **体验**：返回键 Toast 美化；应用内自动更新（走服务器更新目录）

### 服务端 server（Python FastAPI）

- **命令通道**：WS 下发/回执，文本与二进制帧双兼容（向后兼容旧 APK）
- **流转发**：StreamHub 实时转发（不缓存）→ `/api/admin/stream/{device_id}` 二进制
  直播流，mpegts.js 直播源
- **MySQL 持久化**：短信（累积去重+跨设备聚合）、通话记录（覆盖快照）、通讯录
  （覆盖快照）、位置、**上下线记录**（`retention_days` 默认 90 天自动清理，
  MySQL 不可用自动降级内存）
- **设备认证端点**：`/api/device/sms_threads`、`/api/device/sms_messages`（APK 聚合）
- **配套**：更新目录扫描（check_update 不依赖 GitHub）、媒体文件自动清理
  （默认 100 条/7 天）、音乐共享目录、命令目录 catalog 单一权威源

### 管理后台 admin（Vue3 + Element Plus）

- **九大页面**：仪表盘 / 设备管理 / 连接监控 / 命令下发 / 短信 / 通话记录 /
  通讯录 / 媒体文件 / 消息推送（+日志查看）
- **命令下发**：分类+命令卡片按浏览器记忆（localStorage）；参数下发成功后持久化
  记忆并自动回填；目标设备全局 store 跨页面保持；**停止采集四入口同流程**
  （⏹ 按钮 / 切卡 / 切页 / 播放中点 ✕，均 stream_stop+断 WS+15min 封锁）
- **实时画面卡片**：追帧策略（默认/低延迟双档）、画中画 Document PiP（窗口自由
  调大小+内容铺满跟随缩放）、全屏铺满、自绘音量控件、tooltip 美化
- **数据页**：短信聚合模式（全部设备+来源列+按行分组标记/删除）；通话记录/通讯录
  「🔄 从设备刷新」（直发命令+轮询回执+自动重查）；已读/未读双向标记
- **体验**：tab 按浏览器记忆自动跳回；侧边栏导航稳定；深色风格统一

### 关键工程教训沉淀

- 往 PiP/iframe 搬 DOM 后覆盖 scoped 样式必须 `!important`（[data-v] 特异性更高）
- 路由守卫做「首跳恢复」时 redirect 先于守卫解析；默认页记忆恢复必须收窄到根路径首跳
- MySQL 连接禁止跨线程共享（FastAPI 线程池并发 → 协议错乱），高频端点逐操作独立连接
- 功能按钮别用 `v-if` 静默隐藏（置灰+说明）；给用户的说明文案别只放原生 title
- 暴露组件方法前核对 `defineExpose` 清单（路由守卫里调用未暴露方法 = 导航中止）

---

## 2026-10-28 · v0.1.59 ✅ 保活细化弹窗开关实时刷新

- **APK**：「后台资源占用 · 细化」弹窗开关点击后**立即切换**（此前要点开关写盘
  不触发重组、重开弹窗才刷新——组合期直读 SharedPreferences 无重组订阅，改为
  `mutableStateMapOf` 可观察状态；核心项「二次确认关闭」路径经 refreshKey 联动）。

---

## 2026-10-28 · v0.1.58 ✅ APK 短信聚合模式 + 管理后台 UI 六项修复

- **APK**：短信页**聚合模式**（默认聚合）——显示服务端存档里**所有设备**的短信
  （会话键=设备+号码），本机会话标绿「本机」；聚合详情聊天气泡+「加载更早」游标分页；
  顶部「聚合/本机」切换条，本机视图保留原有能力；聚合存档只读。
  服务端配套新增设备认证端点 `GET /api/device/sms_threads`、`/api/device/sms_messages`。
- **APK**：返回键「再按一次退出」Toast 美化（品牌蓝图标圆徽+深蓝渐变胶囊+投影）。
- **admin 六项**：①切命令卡片即销毁实时画面控件（断 WS+移除，自动预连不弹回）
  ②全屏画面铺满（根因 `.stream-video` max-height 420px 未在全屏态解除）
  ③画中画 Document PiP（含音量控件整体搬入，窗口自由调大小）④停止采集按钮
  仅采集中可用 ⑤tooltip 美化 ⑥**侧边栏导航卡死修复**（hide 未进 defineExpose →
  路由守卫 TypeError 导航中止；Playwright 复现实证）。
- **admin**：短信页聚合模式（全部设备+来源设备列+按行设备分组标记/删除）。
- **server**：短信 `list_sms` device_id 改可选；**上下线记录 MySQL 持久化**
  （`connection_events` 表，`retention_days` 默认 90，MySQL 不可用自动降级内存）。

---

## 2026-10-28 · v0.1.56~v0.1.57 ✅ 码率失控闭环 + 短信已读方案落定

- **v0.1.56**：投屏码率失控根治（显式 `KEY_BITRATE_MODE=CBR`，VBR 下 KEY_BIT_RATE 只是
  均值导致实测 22Mbps）；编码器 start 后 request-sync 立即出首帧；二进制 WS 帧
  （省 33% 上行）+ 画质档位 + GOP 1s + 低延迟模式；流式不限时 + 停止采集；回执上传开关。
- **v0.1.57**：短信已读 **Bridge 内部映射**方案落定（ColorOS 拒绝第三方写系统短信库，
  三级回退实测全 0 行 → `SmsReadStore` 本地 JSON 展示层叠加，免默认短信身份）；
  停止采集即断 WS + 手动停止 15min 封锁；音量控件；tooltip 限宽换行。
- v0.1.55 遗留待办「码率失控」「启动黑屏」均已闭环（CBR + request-sync 首帧）。

---

## 2026-10-27 · v0.1.55 ✅ 流式实时链路稳定里程碑（投屏/流式录像不卡顿）

**节点意义**：投屏直播与流式录像的实时播放终于稳定不卡顿。这是自流式功能上线以来
持续多轮排障（TS 转封装 → FLV 对齐器 → 追帧策略 → 静止断流）的收敛节点。

### 本轮关键修复

| 问题 | 根因 | 修复 |
|---|---|---|
| 流式录像只出首帧后停滞 | Android MediaMuxer 的 MPEG-2 TS 时间戳缺陷（PCR/boottime、dts 非单调）→ mpegts.js 解码停滞 | 服务端 `ts_to_flv.py` 统一 TS→FLV 转封装（PTS/DTS 归一化） |
| 卡住→刷新恢复→再卡住死循环 | 512KB 盲缓存任意字节截断/中途加入者字节断层 → `Invalid PrevTagSize` | `flv_aligner.py`：流头+关键帧对齐接入；StreamHub 订阅原子化 |
| 投屏首次无实时画面 | 首次下发时 videoEl 未渲染，attachMediaElement 抛异常被吞 | nextTick + 会话守卫 + 三重预连保险 |
| 追帧跳变（每隔几秒卡一下） | 落后 2.5s 猛 seek = 冻结攒够→瞬跳循环 | 平滑变速追帧 v3：缓冲垫 1.2~3.5s |
| 屏幕静止时周期性卡顿 | ① 上游静止断流十几秒→数据爆发→硬跳；② 解码帧不增被误判卡死→4s 重建循环 | 冻结即时追尾 + 停滞检测区分「上游无帧」+ 重建 12s 冷却 |
| 静止时浏览器旋转 spinner 误导「加载中」 | Chrome 原生 controls 在 waiting 态显示 spinner | video 去掉原生 controls + 卡片头部全屏按钮 |
| **静止期上游断流（治本）** | 设备 Surface 无新帧时编码器不出帧，时间轴断流 | APK：`KEY_REPEAT_PREVIOUS_FRAME_AFTER` 500ms 重复帧（静止期重编上一帧，时间轴连续） |

### 待办（遗留）

- 投屏编码器码率失控（实测 ≈22Mbps vs 配置 2.5Mbps）：静止期重复帧已解决静止断流，
  活动画面的码率爆发（KEY_BIT_RATE 不生效？）仍需单独排查。
- 启动黑屏 ~14s（下发→出画）：设备端编码器启动 + 关键帧对齐等待，属 APK 侧优化项。

---

## 2026-10-27 · v0.1.53

- 投屏直播 FLV sequence header 剥起始码（mpegts FormatError 根因）、首帧不再丢弃、TS 播放追帧自愈。
- 持久镜像幕布稳定复用；宽高清零运算优先级 bug 修复（v0.1.52）。

## 2026-10-27 · v0.1.50~v0.1.51

- 投屏直播（screen_live）上线：MediaCodec + 自研 FlvMuxer，FLV 复用流式通道。
- 音量控制（set_volume 5 流 + get_volume）、流式录像/录音（stream=true + stream_stop）。

## 2026-10-26 · v0.1.41~v0.1.45

- 持久镜像幕布（解决 Android 14+ 投屏令牌一次性）。
- 主动上报默认开（位置周期 + 新短信即推）；媒体文件自动清理。
