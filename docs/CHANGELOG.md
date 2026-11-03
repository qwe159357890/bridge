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

## 2026-11-03 · 🤖 v0.2.39 自动化模块全量落地（无障碍执行器 + Python RPC + 管理后台调试台）

**节点意义**：对标 Droidline + AutoX 的自动化能力一次性铺开——逐行阅读两个借鉴项目源码后，按「目录分离 → 关系查询引擎 → 动作/设备/输入/通知执行器 → RPC + Python SDK → 管理后台可视化调试台」六批次实施。**能力与交互分两层**：底层无障碍执行器（66 条 `ui_*` 命令）供 Python 脚本程序化驱动；上层「自动化调试台」页面（对标 Appium Inspector）供人可视化探查控件、一键复制选择器。两层共用同一套执行器，调试台验证通过的选择器粘进脚本行为完全一致。**最新客户端 v0.2.39。**

### 脉络1 · 自动化能力移植（Droidline 全量 + AutoX 独有，v0.2.39）

- **调研纠偏**：不能只读 `spec/commands.json`，须逐行读 Droidline + AutoX 全部源码；Droidline 能实现的都实现，AutoX 独有的（devices/APP/按键模拟/找色/传感器/文件/通知）一并集成，界面/多线程/Java 调用桥不要。
- **目录分离**：`access/` → `automation/a11y/`，`rpc/` → `automation/rpc/`，automator/query/commands/input/notification 各归其位；Manifest 类名、COMPONENT 常量、IME id 同步改。
- **关系查询引擎**（`automation/query/`）：`Query.kt` 关系查询（row/below/above/left_of/right_of/has/inside/bounds）、`Matcher.kt`（clickableAncestor/editableTarget）、`SelectorParser.kt` 链式 DSL + 对象 JSON 双入口、`AutomationError.kt` 11 种结构化错误码。
- **执行器**（`automation/commands/`）：Ui/Device/Input/FileMedia/Notification 五类，共 66 条命令；纯 Java 找色去 OpenCV（`ColorDetector`/`ColorFinder`）。
- **RPC + SDK**：手写 ServerSocket HTTP server（Android 无 com.sun.net.httpserver），监听 `0.0.0.0`、默认端口 **15161**（支持局域网/VNT 连入，非 127.0.0.1 随机端口）；`/exec` 端点 67 命令白名单 + token 鉴权；Python SDK 补 ~60 语义化方法 + Vision（找图/OCR）。

### 脉络2 · 管理后台「自动化调试台」（阶段①探查工具补全，v0.2.39）

- 此前阶段①只做了 B3「复制选择器」，B1 截图高亮/B2 可视化选坐标留待后续；本次补齐为完整可视化探查台（独立页面，对标 Appium Inspector）。
- 三栏布局：左控件树（`ui_dump` 解析+搜索+折叠）、中截图 canvas（叠加 bounds 高亮框，点树/点框双向联动）、右属性+操作（复制选择器/参数 JSON、点击/长按/输入测试、5s 自动刷新）。
- 复用「命令下发 + WS 回执 + media 截图」现有链路，手机端/服务端零改动，纯后台新增页面。

### 关键经验沉淀

- **底层执行器与上层交互解耦**：能力先备齐（66 命令），再补给人用的交互外壳（调试台），两层共用一套执行器，避免「调试时通过、脚本里行为不一致」。
- **Android 无障碍截屏 + 控件树 bounds 联动完全可行**：`/screenshot` 返回 JPEG、`ui_dump` 节点带 `bounds`，无需改手机端即可做截图高亮。

---

## 2026-11-02 · 🌐 v0.2.38 VNT 断线自动重连 + SSH 终端/SFTP 完整主线

**节点意义**：两条主线同日收敛——① 用户实测 VNT 切网/断网恢复后不自动组网，只能手动点「开始组网」；根因是移植官方 1.x 时删掉 `START_STICKY` 恢复链后未补应用层替代，以**进程内监督重连**根治。② SSH 内嵌 sshd（MINA SSHD）从 v0.2.34 上线后，经五轮真机迭代把「能连」收敛成「终端/SFTP 能用」。**最新客户端 v0.2.38。**

### 脉络1 · VNT 断线自动重连（问题 → 根因 → 收敛）

- **根因链**：网络切换 → native 1.x 回调 `ErrorInfo(Disconnect)` → 旧 worker 一见错误即 `throw` → `onError("组网失败：Disconnect")` → 释放 native/TUN 并 `stopSelf()` → 全链路无补偿（`vnt/` 包无 NetworkCallback，冷启动自动组网也不会触发）。
- **为什么官方能恢复**：官方 VntApp = `START_STICKY + null intent` 自动拉回；v0.2.30 为「退出即整体结束」删掉 STICKY 时，漏了这条断线自恢复路径。
- **修复（进程内监督重连，不恢复 STICKY）**：`VntVpnService` worker 改为监督重连循环——可重试失败**不 stopSelf**、释放本轮实例后退避重建（2/4/8/16/30/60s 封顶，成功过一次即重置）+ `registerDefaultNetworkCallback` 网络恢复立即重试；`TokenError`/`InvalidIp`/VPN 授权被抢（establish 返回 null）仍判致命转 ERROR，不无限重试。进程内重建天然绕开 Android 12+ 后台启动前台服务限制（不新增 FGS 义务点）。
- **新增 `RECONNECTING` 状态**：UI 橙色「断线，正在自动重连…（第 N 次）」、主按钮仍显示「停止组网」；`vnt_start` 幂等守卫、`VntAutoStart` 守卫、admin `VNT_STATE_RULES`/`parseVntStatus`/详情弹窗全链路登记（避免「unknown 一律禁用」误禁）。
- **竞态防护**：generation 校验 + `running` 重入判定 + AtomicReference 跨线程可见性，防 stop→快速 start 时旧 worker 误关新连接。

### 脉络2 · SSH 内嵌 sshd 终端/SFTP（v0.2.34 → v0.2.38）

- **v0.2.34 集成**：MINA SSHD 内嵌，SFTP 文件管理 + 远程终端；`ssh/` 包（Service/Controller/Prefs/HostKeyStore/AutoStart/BootReceiver）+ `SshPages` + `ssh_start/ssh_stop/ssh_status` 三条命令 + admin `SshResultDetailDialog`。
- **v0.2.35 启动修复**：真机首验 sshd 静态初始化失败——`PathUtils user.home` 为 null（Android 无 user.home 属性）→ 显式传 home 目录 + `setAndroid` 签名。
- **v0.2.36 五层真机迭代（当日）**：① exec 通道卡死（sshd 2.20 `ProcessShellFactory.resolveEffectiveCommand` 无视传入 elements、非 Windows 硬编码 `/bin/sh -c`）→ 绕开 OS 推断直接 `InvertedShellWrapper(ProcessShell(...))`；② 终端无回显（管道无 PTY、行规程缺失）→ 自研 `SshLineDiscipline`；③ 行规程与泵死锁（`available()` 整行缓冲时恒 0）→ 改推送式泵 + OS 层合并 stderr；④ mksh `-i` 拿到整行不执行 → 放弃 `-i`、`sh -c` 读取-执行循环；⑤ 输出阶梯缩进 + SFTP/终端落点 `/`（无 PTY 缺 ONLCR；`defaultDir=进程工作目录` 在 Android 恒 `/`）→ 补 `\r` + `HomeSftpSubsystemFactory` 钉到 `/sdcard`。
- **v0.2.37 快捷目录语义（用户拍板）**：补设 HOME 环境变量（cd ~ / 裸 cd 原先失效），裸 cd → App 私有目录、cd ~ → /sdcard、cd - → 上一目录。
- **v0.2.38 内置美化**：彩色路径提示符、常用 alias、h 帮助；不引入 zsh/oh-my-zsh 等不可行依赖。

**关键经验沉淀**：
- **删掉系统的自动恢复机制时，必须同步补上应用层替代**：STICKY 不是唯一方案，但「错误 → 自停 → 无人拉起」的半成品移植会在网络抖动场景必现。
- **重连循环放服务 worker 内比反复 startService 更稳**：进程内重建天然绕开 Android 12+ 后台启动前台服务限制，也更容易做退避、错误分类和竞态防护。
- **无 PTY 的伪终端要自己补行规程与换行转换**：管道拿到的字节缺内核 PTY 的回显/`\r`→`\n`/ONLCR/退格处理，`available()` 判断会因整行缓冲而饿死泵；`/bin/sh -c` 读取-执行循环比 mksh `-i` 从管道取整行更可靠。
- **Android 上 Java 标准库的 `user.home` 可能为 null、`File("").toAbsolutePath()` 恒为 `/`**：依赖这两者推导「家目录 / 初始目录」的第三方库（sshd、SftpSubsystem）都要显式覆写，否则 SFTP/终端落点会钉死在根目录。

---

## 2026-11-02 · 🚦 组网状态「未知」时禁用下发（v0.2.33 后续，**无 APK 发版**）

**节点意义**：用户实测发现，管理后台在组网状态为「未知」时，`vnt_start` / `vnt_stop` 的「下发命令」按钮仍可点。v0.2.33 当初为照顾「快照失真」而放行 unknown，用户判定该取舍不合理 ⇒ 改为 **unknown 一律禁用**。仅改管理后台前端一处判定 + 提示文案，服务端、APK 均不涉及。

**脉络（问题 → 收敛）**：
- 根因：`isStateBlocked()` 末尾有 `if (vntState.value === 'unknown') return false` 特判，直接跳过 `VNT_STATE_RULES` 判定。
- 改动：删掉该特判（`VNT_STATE_RULES` 本就不含 `unknown` ⇒ `!includes()` 自然返回 `true` ⇒ 禁用）；同步改块注释、`parseVntStatus` 注释、`showForceSend` 注释与 `blockHint` unknown 分支文案（「下发按钮已禁用，请先点刷新状态」）。
- 覆盖三种 unknown 来源：从未查询（徽标「未查询」）、探测回执超时/下发失败、回执文案解析失败。
- **关键自洽**：unknown 时命令被禁用 ⇒ `forceSendDisabled = !isStateBlocked = false` ⇒ 强制下发**可勾选**，配合二次确认仍是唯一出口；`watch(forceSendDisabled)` 只在置灰变 true 时复位 `forceSend`，unknown 下恒为 false，不会误清勾选 ⇒ 不违反「必可达（可接受延迟、不可永远访问不到）」硬约束。音乐 3 条 / 采集互斥不受影响。
- 校验：`vue-tsc -b && vite build` 通过。

**关键经验沉淀**：
- **「状态未知」不等于「允许下发」**：快照失真时正确做法是先逼用户确认（刷新状态），而不是放行；只要保留一个需二次确认的强制出口，就不会把设备锁死。
- **改动状态联动规则时先核对「禁用 ⇄ 强制置灰」这对互反逻辑**：把 unknown 从放行改为禁用，恰好让上一轮「命令可用时强制下发置灰」自动变成「unknown 时强制下发可用」，无需额外改开关代码。

## 2026-11-02 · 🧾 命令下发页三项修订（v0.2.33 后续，**无 APK 发版**）

**节点意义**：v0.2.33 状态联动上线后，用户提出三个体验问题——自动探测污染命令历史、回执信息看不全且需按命令分治、命令本来可用时强制下发多余。本轮**只改管理后台前端 + 服务端一处下发参数**，不动 APK、不升版本、不打包。

**脉络（问题 → 拍板 → 收敛）**：
- 经「对时」两轮确认后开工：① 4 个自动探测全静默；② 服务端 `silent` 跳过入库（方案①）；③ 仅 VNT 三条加「查看详情」且三套弹窗各自设计；④ 命令可用时强制下发**置灰**（不隐藏）；⑤ 只改 VNT 两条，音乐/采集保持原状。
- **自动探测静默**：`sendCommand(..., silent=true)` → `CommandRequest.silent` → `commands.py` 跳过 `store.create()`；回执仍经 `admin_event_bus` 广播 ⇒ 状态联动不受影响。手动选中「组网状态」点下发仍正常入库。
- **三套详情弹窗**：新增 `VntResultDetailDialog.vue`，`vnt_status` 做状态快照 KV、`vnt_start` 做下发参数 + 结果解读、`vnt_stop` 做停止前状态 + 自动组网记忆影响；密码脱敏、pending 不显示、媒体「查看」保留。
- **强制下发置灰**：拆出不受 `forceSend` 影响的 `isStateBlocked()`，`forceSendDisabled` 只在 VNT 两条且命令可用时生效；watch 复位残留勾选，避免误触发二次确认。
- 校验：`vue-tsc -b && vite build` 通过；`pytest tests/test_commands.py` **7 passed**（新增 `test_send_command_silent_not_recorded`）。

**关键经验沉淀**：
- **「状态查询」不等于「用户命令」**：内部自动探测复用下发通道时，必须在协议层加静默标记，而不是靠前端过滤显示——否则刷新页面/换浏览器就会复现，且清空历史会把它算进去。
- **静默只跳过入库、不跳过送达与回执广播**：服务端 `set_result` 找不到记录打 warning 属预期，前端状态联动依赖的是 SSE 事件而非 store 记录，二者解耦才能安全加 `silent`。
- **「命令可用时禁用强制」要拆开纯状态判定与含 force 的判定**：直接用 `commandBlocked` 会因 `forceSend` 已勾选而自相矛盾，必须单独 `isStateBlocked()`，并复位残留勾选。
- **每个命令的详情需要单独设计**（用户确立的约定）：不同命令返回的参数与数据结构不同，通用回执文本框会丢失语义，本期按三条 VNT 命令分治。

## 2026-11-02 · 🛡️ v0.2.33 组网命令状态联动与重复下发幂等收口

**节点意义**：v0.2.32 的三条 VNT 命令已能正常启停，但「开始/停止」都**不判断当前状态**，重复下发不是无害空操作。本版把状态判断同时落在**管理后台 UI**（按钮禁用 + 强制下发二次确认）和**客户端执行器**（幂等守卫），并修掉两个实测会伤到「虚拟网必可达」硬约束的副作用。

**脉络（问题 → 决策 → 收敛）**：
- 起因：用户提出「已组网再点开始 / 未组网再点停止会发生什么」，并要求先判断状态、重复时按钮不可用；另问是否要加「强制下发」及是否需二次确认。
- 调研：`vnt_status` 本就是现成的状态入口（按需查询命令，回执经 `command_result` 广播带 `command`/`message`），缺的只是后台 UI 没消费回执；项目已有「选中音量命令自动下发 `get_volume` 拉快照」的同款范式可复用。
- 结论：不新建上报通道、不改服务端、不动 APK 的协议面 —— 纯前端联动即可；**强制下发需要**，因为状态是快照不是事实（设备端手动操作 / 冷启动自动组网 / VPN 授权被撤 / 回执丢失都会让缓存失真），硬禁用会变成「想开开不了」，与「可接受延迟、不可永远访问不到」冲突，且必须二次确认。
- 客户端两个副作用（用户拍板「1 2 一起」一并修）：① `executeVntStart` 在状态守卫**之前**写 Prefs ⇒ 配置漂移 + 迟到 start 重写 `autoStart`；② `executeVntStop` 无守卫 ⇒ 未组网时 `startService(ACTION_STOP)` 会新建一个空前台服务（`worker` 为 null ⇒ 无人 `onStopped()/stopSelf()`，通知常驻、状态卡 `STOPPING`），且无条件清 `autoStart` 会让下次冷启动不再自动组网。

**落地**：
- 后台 `admin/src/views/command/index.vue`：`vnt_status` 回执按设备缓存快照 + 固定前缀解析（running/starting/stopping/stopped/error/unknown）；选中命令 / 换设备 / 手动刷新 / 回执后 1.5s 自动探测；`VNT_STATE_RULES` 驱动卡片置灰 + 按钮禁用 + 状态条 + 顶栏徽标 + `blockHint`；**unknown 不硬禁用**；`forceSend` 对两条命令显示并在 `handleSend` 里弹二次确认。
- 客户端 `CommandModule.kt`：守卫提到最前（含 `STOPPING`），重复命令回 `success=false`「重复下发被忽略」，参数覆盖只在真正启动前写；`vnt_stop` 未组网直接返回、不清记忆。`VntMeshController.stop()` 与 `VntVpnService.shutdown()` 双重收口空服务。
- 命令目录描述、`docs/api/commands/system.md` 新增「状态联动、幂等守卫与强制下发」小节、工作日志「改前留档」段全部同步。
- 校验：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`admin` 的 `vue-tsc -b && vite build` 通过。

**经验沉淀**：
- **「重复下发不崩」不等于「重复下发无害」**：状态守卫必须放在**所有写操作之前**，否则只读的「已在运行」分支照样会改配置 / 改记忆；回执也应明确「未执行」而非 `success=true`，否则后台会误判。
- **按需查询命令可以当状态通道用**，不必新建上报链路：复用回执广播 + 现成的自动探测范式即可，零协议改动、零发版依赖；但必须承认它是**快照**，留 unknown 出口与强制通道。
- **`startService` 不是纯信号**：服务不存在时它会**创建**服务，`onCreate` 的前台化义务与 `worker.finally` 的 `stopSelf()` 一旦对不上就会留下僵尸前台服务；任何「停止」入口都要能在无连接时自收尾。
- **状态联动放 UI 层、幂等守卫放设备端**：后台缓存可能失真（故要强制下发），设备端本地状态才可信（故能兜底），两层不能互相替代。

---

## 2026-11-02 · 🔁 v0.2.32 VNT 核心整体替换为 1.x（协议互通 · A1 纯替换）

**节点意义**：v0.2.31 落地后确认了一条此前被忽略的硬约束——**用户自建 VNTS 与全部存量客户端都是 1.x，且服务端不能升级**（升级会打断存量设备）。VNT 2.0 官方明写与 1.0 不兼容（`vnt-main/README.md:167`），2.x 服务端也只支持 `tcp(tls)/wss/quic`、没有普通 UDP 监听 ⇒ **不存在「兼容 1.x 客户端的 2.x 服务端」这个两全版本**。因此 v0.2.30/31 的 2.0.11 集成方向整体推翻，Bridge 客户端核心改为官方 VntApp `v1.2.9`（最后一个 Java JNI 版），与全 1.x 生态互通。

**脉络**：
- 起因：真机点「开始组网」报 `Unknown protocol in server address: udp://039039.xyz:31007`（MySQL `device_logs` 拉到 11 条）。
- 定位：2.0.11 `parse_server()` 无 `Udp`；本机 TLS 握手 0.01s EOF ⇒ 端口后面是 1.x UDP 服务；用户真机 1.2.13 原生 APK 填「协议 UDP」可正常连。
- 决策：先拍板「按最新规则实现」→ 补充「服务端与存量客户端全 1.x、必须互通」→ 改为「集成 1.x so」→ 核实架构差异后拍板 **A1 纯替换**（放弃双栈），版本号 `v0.2.32`。
- 版本选型：官方 15 个 tag + 3 个维护分支逐个枚举 `.so`，锁定 `v1.2.9`（1.2.10.1 起转 Flutter，用户真机 1.2.13 是 Dart FFI、`Java_*` 符号为 0 不可用）；**所有 Java JNI 版都无 armeabi-v7a**，接受 2 套 ABI。

**落地**：删 `com/vnt/*` 11 类 + 3 套 2.x so → 拷入 `top/wherewego/vnt/jni/*` 14 类（`Vnt.java` 补 `System.loadLibrary`）+ 2 套官方 so（存档 `tools/vnt/artifacts/v1.2.9/`）→ 重写 6 个核心文件（`generateTun` 时序反转、`token`/裸地址/`tcp` 布尔配置、`Vnt.list()→PeerRouteInfo[]`）→ 重写 `VntPages.kt` → 命令三步法与全量文档登记 → `verify_so.py` 适配 1.x（6 符号 / 2 ABI / 4KB 页对齐）。

**经验沉淀**：
- **「官方原生 APK 能连」不能反推「同仓库最新 so 也能连」**——必须先锁定用户实际跑的二进制版本，再核对协议解析源码与官方兼容性声明。
- **架构代际 ≠ 协议代际**：1.x 早期是 Java JNI、后期是 Flutter Dart FFI、2.x 又回到 Java JNI，但包名 / JNI 符号 / 配置模型全不同；「换个 .so」几乎从不成立，要连绑定层一起换。
- **ABI 交集要按「接口 × 协议」二维核对**：有 v7a 的版本要么接口不对（Flutter）、要么协议不对（2.x），没有交集。
- **推翻式重写必须靠改前留档兜底**：台账 §六 逐字固化改前原文 + 未跟踪文件删除清单 + 回滚配方；git 只兜得住已跟踪文件。
- 官方 1.x so 是 **4KB 页对齐**，Android 15+ 16KB 页设备可能加载失败（固有限制，如实登记）。

**仍待真机**：与存量 1.x 客户端互 ping（`token/password/cipherModel/serverEncrypt/finger` 逐项一致）、两套 ABI 加载、冷启动自动组网 / 停止后不自动开始、开关省电对照。详见 `docs/research/vnt-mesh-integration.md` §十三、`docs/dev/vnt-integration-change-ledger.md` §六。

---

## 2026-11-01 · 🔧 v0.2.31 VNT 真机反馈修订里程碑（universal ABI · 协议代际 · 自动组网记忆）

**节点意义**：v0.2.30 上真机后用户提出 4 项修订，并补了一张 **VNT 1.2.13 原生 APK**
（「协议 UDP」单选 + `039039.xyz:31007`）的配置截图，拍板「既然集成最新的 so，就按最新规则实现」。
一条主脉络：**客户端按 v2.0.11 规则收敛**，不降级 so、不加旧协议兼容层。

**v0.2.31 · 根治**：真机点击「开始组网」报错 → 从 MySQL 客户端日志拉到 11 条
`Unknown protocol in server address: udp://039039.xyz:31007`，回源码逐 tag 核对
`parse_server()`（v2.0.2~v2.0.11 一致）确认合法前缀只有 `tcp:// / wss:// / quic:// / dynamic://`、
裸 `host:port` 默认 **TlsTcp**、`ProtocolType` 枚举无 `Udp`；本机实测 31007 端口
TLS 握手 0.01s 即 EOF，判定是 **1.2.x 旧 UDP 服务端 × 2.0.11 新核心的协议代际不匹配**，
而非打包或 so 版本错误 → 纠正误导文案（原写「裸 host:port 默认 UDP」）、保存前白名单校验、
native 前防线、ERROR 状态不再被 `finally.onStopped()` 吞掉、状态卡如实显示
「未连上服务端」橙色警示卡。

**同版 3 项**：① 对齐官方 universal，收录 `arm64-v8a / armeabi-v7a / x86_64` 三套官方 so
（`tools/vnt/verify_so.py` 升级为多 ABI 校验），显式排除官方无 so 的 x86（32 位）；
② 「设置→关于」页根 `Column` 补 `verticalScroll`，修复小屏够不到底部「退出」；
③ 自动组网记忆：开始/停止（及 `vnt_start`/`vnt_stop`）写 `VntMeshPrefs.autoStart`，
`MainActivity.onStart` 一次性自动开始，三道守卫 + 失败不重试。

**关键经验沉淀**：
- **协议代际 ≠ 版本兼容**：官方「原生 APK 能连」不等于「同仓库最新 so 也能连」——
  用户跑的是 1.2.13（旧 UDP 核心），我们集成的是 v2.0.11（`ProtocolType` 已无 `Udp`）。
  排查这类问题必须**先锁定用户实际运行的二进制版本**，再逐 tag 核对解析函数，
  别用「官方能连」反推我们的实现有误。
- **错误提示文案是配置契约的一部分**：一句错误的「裸 host:port 默认 UDP」直接把用户导向
  `udp://` 非法前缀，引发 11 次 native 异常。凡暴露给用户填的协议/格式，必须**以底层解析源码为准**，
  并在保存入口做白名单校验，把晦涩的 native 异常挡在 UI 之外。
- **`catch` 里发的 ERROR 会被 `finally` 覆盖**：服务启动失败时 `finally` 无条件 `onStopped()`
  会冲掉 `onError()` 的文案，用户只看到按钮变回去、永远不知道为什么失败。
  状态机的终态写入必须区分成功/失败路径。
- **ABI universal 要「按官方有 so 的集合」对齐**：官方 4 个 APK = 3 套 ABI 分包 + universal；
  x86（32 位）官方无 so，若不限定会打出「装上了但 VNT 加载不到」的假成功。

---

## 2026-11-01 · 🕸️ v0.2.30 VNT 组网集成里程碑（主页第 5 Tab · JNI 内嵌 · 官方预编译产物 · 三条命令）

**节点意义**：把开源 VNT（虚拟局域网 / SD-WAN）集成进 Bridge，在「快递扫码」之后新增「组网」Tab。
四条脉络：**调研与决策收敛**（三仓库源码实读 + 9 项拍板 + 两轮追问）、**产物路线纠偏**
（原判「必须自编 Rust+NDK」→ 查官方 Release 改为下载预编译 so，本地零安装）、**P1 落地**
（JNI 绑定 + 前台服务 + Compose 页面 + Tab 迁移 + 退出流程）、**P2 命令与全量文档登记**。

**逐版记录**：

- **v0.2.30（本次）**：**根治**「组网能力缺失」——新增 `com/vnt/*.java` 官方绑定（11 个类，包名不可改）、
  `my/bridge/com/vnt/` 五件套（`VntVpnService` / `VntMeshController` / `VntMeshState` /
  `VntMeshPrefs` / `VntConfigBuilder`）、`ui/VntPages.kt`、主页第 5 Tab（含 `TabOrderPrefs` 4→5
  **旧值迁移**）、`AppExit.kill` 显式停服务、`vnt_start/vnt_stop/vnt_status` 三条命令；
  `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so` 直接取官方 v2.0.11 预编译产物并校验 PASS。

**关键经验沉淀**：

1. **「有源码」≠「有产物」**：Java 层 23 个 `native` 声明全是空壳，必须配 `.so`；但**编译不必在本地做**——
   先查上游 Release（官方按 ABI 分发 + sha256），能省掉整套 Rust/NDK 工具链。**本地安装任何程序前必须先经用户同意**，
   下载/复制文件不算安装。
2. **Tab 数量变化必须做迁移**：`isValid()` 硬编码长度时，老用户存的值会被静默回退默认，
   丢掉自定义排序与改名；识别旧值 → 保留原顺序 → 末尾追加新页 id → 回写，是最低成本的兼容。
3. **基础设施不能照抄页面级生命周期**：ESP32/销售/扫码用 `DisposableEffect{start; onDispose{stop}}`，
   组网是常驻连接，必须「前台服务 + 显式开关」，页面只订阅（照 `AudioRelayPage`）。
4. **`getClientList()` 是主动 RPC 且最多阻塞 3 秒**：绝不上主线程；设备上下线底层实时推但
   `RuntimeEvent` 没有对应变体，UI 只能轮询；速率底层只有累计字节数，需自行差分并在
   页面隐藏→显示时**重置采样基准**，否则出现虚假尖峰。
5. **路由红线**：Android `VpnService.Builder.addAddress()` 不自动生成路由，必须显式加虚拟网段，
   且**绝不加 `0.0.0.0/0`**（会劫持整机流量，把 Bridge 自己的长连接卷进隧道）。

> 真机组网联调与省电实测**仍待做**（需 VNTS 实体与多设备环境）。改前留档与回滚配方见
> `docs/dev/vnt-integration-change-ledger.md`；完整调研见 `docs/research/vnt-mesh-integration.md`。

---

## 2026-11-01 · 🧭 v0.2.22→v0.2.29 微信页真机收敛里程碑（五批真机问题 · 进程退出 · 通知跳转根治）

**节点意义**：v0.2.21 微信页上线后，用户在真机上连续五轮反馈（每批 6~9 项），把「能跑」收敛成「能用」。
四条主脉络：**实时链路真正打通**（WS 从未连上的根因）、**真机体验逐批收敛**（分页/未读/通知/返回键/头像）、
**新增进程退出能力**、**根治通知跳转**。方法论上最值钱的三条：**客户端不猜服务端**（发图两条真因在 Agent 端）、
**一次性事件令牌必须配守卫**（两次栽在 `LaunchedEffect`）、**自杀前先停 sticky 服务并回桌面**。
逐版细节见下方各版条目；改前原文/改后/回滚配方见 `docs/dev/` 下同名台账。

### 脉络1 · 实时链路（先修「从未连上」，再谈体验）

- **v0.2.22**：首批 8 项（搜索框高度、通讯录、返回键四级优先级、连接状态误报、分页、进入会话不刷新、
  微信资源释放「退出微信 / 离开即释放」）。
- **v0.2.23**：**WS 从未连上的根因**——OkHttp `HttpUrl` 只支持 http/https，`toHttpUrlOrNull("ws://…")` 恒 null，
  连接卡在 CONNECTING 且无回调；改返回 String 交 `Request.Builder.url(String)`，并补失败回退 DISCONNECTED。
  同批修分页/角标/退出/输入框/滚动/表情/提示音共 9 项。

### 脉络2 · 真机体验五批收敛（v0.2.24 → v0.2.26）

- **v0.2.24**：触底只加载一次（`snapshotFlow` 替代布尔量 `derivedStateOf`）、刷新后列表变少（按已加载条数回拉）、
  未读口径（按模式过滤 + `max(本地累计, 服务端)`）、发送重复、提示音音量滑杆。
- **v0.2.25**：`LaunchedEffect(token)` **重新进入组合时也执行** → 销售页误弹未读 / 退出后误跳，加「已消费守卫」；
  双击「聊天」只滚顶部不进详情；未读跳转非免打扰优先；全局单一通知 id；公众号默认静默；
  去返回箭头 + 返回键双击退出。
- **v0.2.26**：通知与保活常驻通知分离（**不是 id 撞号**，是保活渠道 `IMPORTANCE_HIGH+PRIORITY_MAX` 抢位 → 降为 LOW，
  微信侧独立渠道组 + id 2301）；确认免打扰不提醒（早已实现，补入口兜底）；**撤回**客户端 2s 折叠去重；
  返回键 2 秒改为设置可调（1~5s）；销售页返回键对齐微信；头像首字母压图（`cached(url)` 只在组合时求值一次）。
- **发图两条（跨仓库根治）**：真因在 **Agent 端** `_handle_upload_and_send` 未写 EXIF 去重标志
  （PC 端走的 `send_image` 会写 ⇒ 只有它不重复）。已在真实仓库 `F:\pythonWork\wxhelper\center-server` 打补丁
  并本地提交（`agent/core/agent_client.py`）；客户端应急开关保留但默认关，待 Agent 验证后可删。

### 脉络3 · 进程退出（v0.2.27 → v0.2.28）

- **v0.2.27**：新增 `AppExit.kill` + 通用「切到后台立即结束」（默认关）+ 关于页「退出」。
  顺序是关键：`stopService ×3`（三个 START_STICKY）→ `WechatRuntime.stopAll()` → `finishAffinity()` →
  延迟 300ms → `killProcess + exitProcess`（**有可见 Activity 时直接 kill 会被 AMS 为恢复可见任务而重启**）。
  同时确认「微信离开后立即结束」= 销毁 WS / 60s 心跳 / 重连 Job / OkHttp 池 / 内存数据。
- 边界更正：杀进程前 `stopService` → 服务 `onDestroy` → `ResourceHolder.releaseAll()` 已取消双兜底 Alarm/Job，
  **不需要用户手动关双兜底**；真正还会拉起进程的只剩开机静态广播与无障碍服务绑定。
- **v0.2.28**：补 `cancelUniqueWork("bridge_location_report")`（否则十几分钟后 WorkManager 会新建进程复活）；
  退出只清**运行时登记**、不写任何持久化开关 ⇒ 手动重启后保活照旧全部重新布防。

### 脉络4 · 通知跳转根治（v0.2.29）

- 复现路径：APP 切到主页/销售/设置 → 切到别的 APP → 来通知 → 点通知 → **有时进主页**。三处根因：
  ① 通知 Intent 用 `getLaunchIntentForPackage()`，与 task 的 launcher intent **完全相同**
  （`Intent.filterEquals()` 不比较 extras）⇒ 后台恢复时 AMS 只把 task 带前台、**不回调 `onNewIntent`**，
  deep link 根本没送达；② `exitToHomeToken` 缺「已消费守卫」，Activity 重建时把微信页打回主页；
  ③ 同一会话连续两条通知，`openWechatSession` 同值写入、key 不变 ⇒ 第二次点了没反应。
- 修法：专用 action + `bridge://wechat/session/<wxid>` 唯一 data + `NEW_TASK|CLEAR_TOP|SINGLE_TOP`；
  `exitToHome` 补守卫；改用自增 `openToken` 作 `LaunchedEffect` key；全链路加日志（关键字 `通知跳转`）。

### 脉络5 · 工程与发布链路（贯穿）

- 每版固定动作：台账（**改前逐字留档**）→ 改 → 回填改后/回滚 → `compileDebugKotlin` → 提交 → 打 tag → CI →
  APK 同步 `server/data/updates/`（走 check_update）。
- `chore` 提交（7fc93b2 / e7eef04 / 33ec1dd / 1b6a954）：每版同步 `update.json` 包清单。
- `de28c8a`：发布链路文档补三个致命坑（含 `release.py` 重跑会重复 bump）。
- **v0.2.28 踩坑**：`tools/release/api_push.py` 的 `PUSH_BASE=HEAD~1` **只推最后一个提交的 diff** ⇒
  连续多次本地提交必须用 `HEAD~N`，且推完**必须校验远端文件内容**（不能只看 CI 绿）。

### 关键经验沉淀

1. **通知 PendingIntent 不要复用 launcher intent**：`getLaunchIntentForPackage()` 的
   `ACTION_MAIN+CATEGORY_LAUNCHER` 与 task base intent 相同（extras 不参与比较），从后台恢复时 AMS 不回调
   `onNewIntent` ⇒ 用专用 action + 唯一 data，并保留 `SINGLE_TOP`。
2. **`LaunchedEffect(key)` 重新进入组合时也执行**：凡「一次性事件令牌」都要配「已消费守卫」
   （本项目两栽：`jumpUnreadToken`、`exitToHomeToken`）；且 key 用**值**会漏掉同值重复事件 ⇒ 用**自增令牌**。
3. **客户端不猜服务端**：「某端表现正常」只证明**那条路径**正常（PC `send_image` vs 手机 `upload_and_send`），
   定位必先比端点；在客户端折叠「疑似双写」是补丁不是根治。
4. **自杀会被复活**：START_STICKY 服务要显式 `stopService`；有可见 Activity 时 `killProcess` 会被 AMS 重启
   ⇒ 先 `finishAffinity()` 再延迟杀；登记在系统里的 WorkManager 任务要一并取消。
5. **通知展示位置由渠道 importance 决定，与前台服务存活无关**：改 importance 只影响展示，不影响保活。
6. **OkHttp 不支持 `ws://`**：`toHttpUrlOrNull("ws://…")` 恒 null 且静默失败，WS 地址必须以 String 交给 `url(String)`。
7. **推送后必须校验远端文件内容**：CI 绿 ≠ 代码上去了（diff 基线取错会漏提交）。

## 2026-11-01 · 🔧 v0.2.28 进程退出补强（取消 WorkManager 兜底 + 说明更正）

- **补强**：`AppExit.kill` 增加 `cancelUniqueWork(ReportManager.WORK_NAME)`。该周期任务登记在系统里，
  不取消的话会在 ≥15 分钟后新建进程执行 Worker，APP 会自己「复活」；下次冷启动 `applyConfig` 自动重排，功能不丢。
- **更正说明**：双兜底无需手动关（`stopService` → `onDestroy` → `releaseAll` 已取消 Alarm/Job）。
- **边界澄清**：退出只清**运行时登记**，不写任何持久化开关 ⇒ 手动重新启动后保活照旧全部重新布防。

## 2026-11-01 · 🐞 v0.2.29 通知点击「有时落到主页」（根治）

**节点意义**：用户复现路径明确（APP 切到主页/销售/设置 → 切到别的 APP → 来通知 → 点通知 → 有时进主页）。
排查出一个**平台级坑**和两个**Compose effect 坑**。
台账（改前/改后/回滚配方、真机验证方法）：`docs/dev/wechat-notification-jump-ledger-v0.2.29.md`。

- **① 通知 Intent 不能复用 launcher Intent（根治，平台级）**：`getLaunchIntentForPackage()` 返回的
  `ACTION_MAIN + CATEGORY_LAUNCHER` 与 task 的 base intent **完全相同**（`Intent.filterEquals()`
  不比较 extras），APP 从后台恢复时 AMS 只把 task 带前台、**不回调 `onNewIntent`** ⇒ deep link
  根本没送达、UI 停在原页面；冷启动走 `onCreate` 则必然送达 ⇒ 这正是「有时行有时不行」。
  改为显式 `Intent(ctx, MainActivity::class)` + 专用 action + `bridge://wechat/session/<wxid>` 唯一 data。
- **② `exitToHomeToken` 缺「已消费守卫」**：`LaunchedEffect(key)` **重新进入组合时也执行**，
  Activity 重建时会重放一次，把 pending 分支刚切好的 `selectedBottom=1` 打回 `0` 并清空会话。
  补守卫（与 v0.2.25 的 `jumpUnreadToken` 同款）。
- **③ 同一会话连续两条通知 ⇒ 第二次点了没反应**：`openWechatSession` 同值写入（State 不变、
  不重组），`LaunchedEffect` 的 key 也不变 ⇒ 不重启。改用自增 `openToken` 作 key，并强制 `subTab=0`。
- **可观测性**：全链路加日志（`通知跳转 → onCreate / onNewIntent / UI 消费 / 打开会话`），
  缺哪一条就定位到哪一段。

## 2026-11-01 · ✨ v0.2.27 进程退出能力（切后台即结束 / 关于页「退出」）

**节点意义**：用户要求「能主动把 APP 整个杀掉」。核心坑是**自杀会被系统复活**——三个服务是
START_STICKY、且**有可见 Activity 时 killProcess，AMS 会为恢复可见任务而重启进程**，
故固定顺序 `stopService ×3 → finishAffinity → 延迟 300ms → killProcess + exitProcess`。
台账（改前/改后/回滚配方）：`docs/dev/app-exit-ledger-v0.2.27.md`。

- **① 确认「微信离开后立即结束」**：开关打开后离开微信页即销毁 WS 连接、60s 心跳、重连 Job、
  OkHttp 连接池/线程池、语音、内存数据（唯一未注销的是 NetworkCallback，有 `stopped` 守卫兜住，本次不动）。
- **② 通用 →「切到后台立即结束」**（默认关）：`AppExit` + `AppExitPrefs`，用
  `ActivityLifecycleCallbacks` 计数判定后台（不引 `lifecycle-process`，且它 ON_STOP 有 700ms 延迟）。
  **熄屏/锁屏也会触发**（Android 会 stop 前台 Activity），已在设置项说明写明。
- **③ 关于 →「退出」**：检查更新下方红色按钮 + 二次确认，走同一个 `AppExit.kill`。
- **边界（改后更正）**：原以为杀进程后双兜底会把 APP 拉回来，**核对代码后不成立**——
  `stopService` → 服务 `onDestroy` → `ResourceHolder.releaseAll()` 会 `cancelAlarm` + `cancelJob`，
  两个 15 分钟兜底随服务一起取消。真正还会复活进程的只有三条：开机静态广播（关「开机自启」）、
  WorkManager 位置上报兜底（v0.2.27 起 kill 时 `cancelUniqueWork` 取消，下次冷启动自动重排）、
  已开启的无障碍服务被系统绑定（只能去系统设置关）。

## 2026-11-01 · 🐞 v0.2.26 微信页第五批真机问题修复（6 项）

**节点意义**：v0.2.25 装上真机后用户再反馈 6 项。两条最值得记：**①「发图两条」的真凶定位到了 Agent 端**
（`upload_and_send` 路径没写 EXIF 去重标志，而 PC 客户端走的 `send_image` 会写 ⇒ 只有它不重复），
v0.2.25 的客户端折叠规则因此**撤回**（属「在客户端猜服务端」，可能误伤且不可靠）；
**②通知混在一起**不是 id 撞号，而是保活常驻渠道 `IMPORTANCE_HIGH + PRIORITY_MAX` 抢位，
降级为 LOW 后消息通知自然独立（前台服务的存活与通知重要性无关）。
台账（改前/改后/回滚配方）：`docs/dev/wechat-fix-ledger-v0.2.26.md`。

- **① 微信消息通知与保活常驻通知分离**：微信通知 id 2101→**2301**（2101 与 `MediaCaptureService` 撞号）、
  渠道升 `_v3` 并归入独立渠道组、开提示音时重要性提到 **IMPORTANCE_HIGH**、显式 `setGroup` 防折叠；
  对侧 `KeepAliveService` 渠道降为 **IMPORTANCE_LOW**（`keep_alive_channel_v2`）。**根治**。
- **② 免打扰会话**：结论是**早已实现**（`onIncomingMessage` 里 `if (isSelf || muted) return` 在
  `remind()` 之前），本次只在 `remind()` 入口补一道 `session.isMuted` 兜底 + 日志，防后续改动绕过。
- **③ 撤回 v0.2.25 的客户端折叠**：`wechatDedupe(foldSent = false)`，折叠改为设置项
  「自家发送去重（服务端双写兜底）」**默认关**；真实修法在 Agent 端（台账 §3 附补丁）。
- **④ 返回键窗口不再硬编码**：新增 `ui/BackPressPrefs`（`ui_prefs`），设置 → 通用 → 返回键
  两个滑杆（1~5 秒，默认 2 秒），分别管「再按一次返回主页」与「再按一次返回退出 Bridge」。
- **⑤ 销售页返回键与微信对齐**：`SalesPage` 新增 `onExitToHome`，在最前注册 `BackHandler`
  （Compose 后进先出 ⇒ 优先级最低，阅读模式/浮层仍优先接管），舍弃「第 1 次显示底部工具栏」。
- **⑥ 头像上叠着昵称首字**：`WechatAvatar` 用 `WechatImageLoader.cached(url)` 判断是否画首字，
  而该判断只在组合时求值一次、图片异步加载完成不会触发重组 ⇒ 首字一直压在图上。
  改为组件自持 `bmp` 状态，`bmp == null` 才画首字。**根治**。

**关键经验沉淀**：
- **「某端表现正常」不等于「服务端没问题」**：PC 客户端发图不重复，是因为它走 `send_image`
  （写 EXIF 标志）；手机端被迫走 `upload_and_send`（无标志）——**同一现象的两条不同代码路径**，
  定位时必须先比端点，再下结论。
- **客户端猜服务端的兜底要有退出条件**：时间窗折叠这类启发式既可能误伤（2 秒内连发不同图），
  也不可靠（数据库监控有轮询延迟），应做成可关闭，并在找到根因后删除。
- **常驻前台服务通知不需要高优先级**：`startForeground` 之后 importance 只影响展示位置；
  把它降到 LOW 是让业务通知「独立显示」成本最低的办法。

---

## 2026-11-01 · 🐞 v0.2.25 微信页第四批真机问题修复（8 项）

**节点意义**：v0.2.24 装上真机后用户再反馈 8 项。最要害的一条是 **`LaunchedEffect(令牌)` 在首次进入
组合时也会执行**——从别的页切回微信页 = 重新组合，令牌还是上次双击留下的非 0 值 ⇒ 平白弹
「没有未读会话」（没未读时）或直接跳进会话详情（有未读时）。它同时解释了问题 1 与 5。
台账（改前/改后/回滚配方）：`docs/dev/wechat-fix-ledger-v0.2.25.md`。

- **① 从其它页（销售）进微信会弹「没有未读会话」**：跳转 effect 改为**已消费令牌守卫**
  （`consumedJumpToken` 以当前值初始化，只有令牌真变了才动作）。
- **② 双击「聊天」不再进详情**：只把目标会话**滚到列表顶部**（`LazyListState` 提升到页面级）。
- **③ 未读跳转优先级**：新增 `WechatUiState.unreadJumpTargets` = **非免打扰未读（按列表顺序）
  + 免打扰未读（按列表顺序）**，每次双击递进一条、到尾回绕（例：第 2 → 第 4 → 第 3 条）。
- **④ 通知只保留一条**：`WechatNotifier` 改**全局单一 id**（`NOTIFICATION_ID=2101`），
  后到的消息直接顶替前一条，`setNumber` 显示累计未读、`setOnlyAlertOnce` 防连环响动。
- **⑤ 退出微信后再进入落到会话详情**：同 ① 的令牌守卫（真凶不是 `openWechatSession` 残留）。
- **⑥ 发一张图本端显示两条**：客户端确认只发一次，是服务端/agent 对同一次发送写了两条记录。
  参考仓库不含消息表 INSERT，无法从服务端根治 ⇒ 客户端兜底 `wechatDedupe()`：
  同会话 + **双方都是自己发的** + 同 `msgType` + `|Δts| ≤ 2s` ⇒ 保留后到的（id 更大）。
  应用于首次加载 / 增量合并 / afterSend / 翻页四处。
- **⑦ 公众号默认静默**：新增开关「公众号消息提醒」（**默认关**，照参考项目 web/PC/外部端口径）——
  关时公众号消息照常入库、更新列表与未读，但**不通知、不响铃**。
- **⑧ 去掉左下角返回箭头**（那叫**底部导航栏 / Bottom Navigation Bar**，APP 主导航）：
  主界面按返回键 → 第 1 次冒泡「再按一次返回主页」，2 秒内第 2 次 → 回主页。
  返回键链改为**先注册①主界面回主页（最低优先级），后注册②表情③详情④子 Tab**（Compose BackHandler 后进先出）。

---

## 2026-11-01 · 🐞 v0.2.24 微信页第三批真机问题修复（6 项，分页/未读口径/发送重复）

**节点意义**：v0.2.23 装上真机后用户再反馈 6 项。三类根因：①**布尔量边沿触发**导致触底加载只生效一次；
②**未读总数未按模式过滤 + 刷新覆盖未读**（照参考前端 `stores/session.ts` 口径修正）；
③**发送后本地占位气泡与服务端真实消息各算一条**。
台账（改前/改后/回滚配方）：`docs/dev/wechat-fix-ledger-v0.2.24.md`。

- **① 触底加载只生效一次**：`derivedStateOf` 布尔量 + `LaunchedEffect(布尔量)` 只在 false→true **边沿**触发，
  加载完成后若用户仍在底部（或该页全是重复项、列表长度没变），布尔量不再跳变 ⇒ 再也不加载。
  改为 `snapshotFlow { listState.layoutInfo }` 订阅（每次滚动/布局都发射，可重复触发）；
  并补上「整页 added==0 ⇒ 判定已到底」，避免同一 offset 反复请求却永远不出新内容。
- **② 有新消息时列表变少**：`refreshSessions()` 每次都拉 `limit=50, offset=0` 并**整体覆盖**，
  用户翻到第 3 页后来一条消息就缩回 50 条。改为按已加载条数取回（`max(50, 当前条数)`，上限 500）。
- **③ Tab 角标 54、列表无角标**：未读总数对**全部会话**求和，而列表只渲染当前模式（好友/公众号）的会话。
  照参考前端 `totalUnreadCount` 改为**只统计当前模式的非免打扰会话**；
  且 `setSessions` 未读取 `max(本地累计, 服务端)`——服务端 `unread_count` 是**客户端写回**的
  （服务端收到新消息不自增），直接覆盖会把进页面时看到的角标抹成 0。
- **④ 退出微信后再进入直接进聊天详情**：`openWechatSession` 是 `rememberSaveable`，
  退出只切了 Tab 没清旧 wxid。观察 `exitToHomeToken` 时一并置 null。
- **⑤ 发一张图出现两条**：占位气泡 key 是 `msgId="" + id=0`，服务端真实消息带真实 `msg_id` ⇒
  被判成新消息再追加一条（一条「[文件] xxx」+ 一条真实图片）。改为发送成功后**轮询等服务端落库**
  （8 × 500ms），拿到真实消息就展示它；超时才退回占位气泡。
- **⑥ 提示音电平**：音源实测已是 0 dBFS 满幅、SoundPool 系数 1.0（App 侧上限）；
  缺的是可调项——照快递扫码 `ScanFeedback`/`ScanPrefs.volume` 加「提示音音量」滑杆 + 试听按钮，
  默认 100%（拉满），实际音量 = 本系数 × 系统媒体音量。

---

## 2026-11-01 · 🐞 v0.2.23 微信页第二批真机问题修复（9 项，含一个「从未连上」的根因）

**节点意义**：v0.2.22 装上真机后用户又反馈 9 项。其中 **WS 从未连接过**是根因级 bug——
一次修复同时解决了「实时消息不刷新 / 不弹通知 / 不响提示音」三个现象（问题 1、3、9 同源）。
台账（改前/改后/回滚配方）：`docs/dev/wechat-fix-ledger-v0.2.23.md`。

- **① WS 地址永远推导失败（根治，问题 1/3/9 同源）**：`WechatWsClient.toWsUrl()` 拼出 `ws://…` 后
  直接调 OkHttp 的 `toHttpUrlOrNull()`，而 **OkHttp 的 `HttpUrl` 只支持 http/https**
  （jar 里 `HttpUrl.class` 搜不到 `wss`，只有 `Request$Builder` 有 `ws→http` 静默转换）→ **恒为 null**
  → `connect()` 首行 return false，**从未发起过握手**；且无任何回调，状态永远卡在 CONNECTING
  （连 2s 重连循环都进不去）。改为**返回字符串**交给 `Request.Builder.url(String)` 由 OkHttp 内部转换；
  并发起失败时把状态打回 DISCONNECTED（不再假装在连接）。
- **② 会话/通讯录分页 + 「已经加载全部」**：原先一次性拉 200 条会话 / 100 条联系人，**之后滑动不再请求**，
  也没有终点提示。改为 50 条一页、滑到接近底部追加（`loadMoreSessions` / `loadMoreContacts`），
  底部状态条区分「加载中 / 滑动加载更多 / **已经加载全部 · 共 N 条**」。
- **③ 未读角标 + 双击跳未读**：会话行头像**右上角**补 `BadgedBox` 未读角标（免打扰灰点）；
  新增 `tabClickable`（400ms 内连击判定），双击页内「聊天」Tab 或底部「微信」Tab →
  跳到最新一条未读会话并播「叮一声」（照参考 PC 客户端的跳未读交互）。
- **④ 退出微信回主页**：原来只释放资源、仍停在微信页。`logout()` 现在自增 `exitToHomeToken`，
  MainScreen 观察后切到「主页」Tab。
- **⑤ 聊天输入框高度（补漏）**：v0.2.22 只改了两处搜索框，漏了聊天输入框（仍是
  `OutlinedTextField` + `heightIn(min=36.dp)`，被 M3 的 56dp 最小高度顶住）。新增
  `WechatChatInputField`（BasicTextField 自绘，36dp）替换。
- **⑥ 发送后自动滚到底部**：发送文本/文件成功后 `listState.animateScrollToItem(messages.lastIndex)`。
- **⑦ 表情全集（148 个）**：新增 `wechat/WechatEmoji.kt`，从参考项目
  `pc-client/gui/utils/emoji_data.py`（web 端 `emoji.ts` 同源）1:1 生成；面板改为可滚动 8 列网格；
  并照前端 `replaceEmojiCodes` 在消息/会话摘要渲染时把 `[微笑]` 这类代码**渲染成 emoji**。
- **⑧ 提示音（照 PC 客户端）**：原先播系统默认通知铃声（多数 ROM 上是「无」⇒ 完全无声）。
  改为 SoundPool 播放自带音源 `res/raw/wechat_msg.wav`（= PC 端 `MSN消息.wav`），
  跳未读播 `wechat_jump.wav`（= `叮一声.wav`）；**通知渠道改为静音**（`_v2` 换 id 规避渠道属性不可变），
  避免「通知声 + 自播声」双响。
- **⑨ 通知**：随 ① 修复后 `onIncomingMessage` 正常回调，通知与提示音链路才真正被触发。

### 关键经验沉淀

- **「连接状态显示异常」要优先怀疑地址推导，而不是服务端**：本次设置页已经打印了
  「地址无效，无法推导 WS 地址」，但被当成显示瑕疵；真相是 WS **一次都没连过**。
  **结论：状态文案里的「无效/失败」是硬信号，不是文案问题。**
- **OkHttp 用 ws 只认 `Request.Builder.url(String)`**：`HttpUrl` 与 `toHttpUrlOrNull()` 不支持 ws/wss，
  任何「先拼 ws 再 toHttpUrl」的写法都会静默返回 null。
- **连接发起失败必须显式回退状态**：没有回调 ⇒ 状态机卡死 ⇒ 连重连都不会触发；
  凡是「发起即失败」的路径都要自己把状态置回 DISCONNECTED。
- **分页要给终点提示**：没有「已经加载全部」时，用户无法区分「到顶了」和「卡住了」，
  会把正常行为报成 bug。

---

## 2026-11-01 · 🐞 v0.2.22 微信页首批真机问题修复（8 项，用户逐条反馈）

**节点意义**：v0.2.21 首次装上真机后，用户逐条反馈了 8 类问题。本里程碑按「一个一个解决」全部修复，
其中**消息顺序反向**是根因级 bug（一次修复同时解决「点进会话看不到最新消息」和「下滑不加载更多」两个现象）。

- **① 搜索框高度（全项目统一）**：微信页两个搜索框原本用 `OutlinedTextField` + `heightIn(min=34.dp)`，
  M3 的 OutlinedTextField 自带 **minHeight 56dp** 且 contentPadding 上下各 16dp，`heightIn(min)` 压不下去
  （最小尺寸约束优先），于是比右侧「好友」按钮高一大截。**复用销售页 v0.2.19 已验证的解法**：
  改用 `BasicTextField` 自绘边框，新增 `WechatSearchField` 统一组件 + `WechatFieldHeight = 32.dp`，
  搜索框与同行按钮高度严格一致。
- **② 通讯录「暂无联系人」**：`refreshContacts()` 带 `account_wxid` 过滤，而 contacts 表里的
  account_wxid 未必回填完整 → 永远空列表。改为**空结果时回退不带账号再拉一次全局联系人**。
- **③ 返回键四级优先级链（新增 BackHandler）**：微信页此前**完全没有** BackHandler，返回键只能靠左上角箭头。
  现按 ①收起表情面板 → ②聊天详情返回会话列表 → ③页内 Tab（通讯录/实例/工具箱/设置）切回聊天
  → ④交给外层切换底部主导航栏显隐。**④ 是最低优先级**（用户拍板：「显示/隐藏底部工具栏最高级应该是最低级的」）。
  表情面板状态为此提升到 `WechatPage` 页面级。
- **④ 聊天详情返回键**：同上 ②，不必非得点左上角返回箭头。
- **⑤ 设置页连接状态误报**：原先只判 `CONNECTED`，其余一律显示「未连接」，于是 REST 已能拉到聊天内容、
  但 WS 仍在 CONNECTING 时用户看到「未连接」以为是 bug。现细分三态，并**新增显示实际 WS 端点**
  （`WechatRuntime.wsEndpoint()`），便于排查「REST 通但 WS 连不上」。
- **⑥ ⑦ 消息顺序反向（根因）**：服务端 SQL 是 `ORDER BY timestamp DESC, id DESC`（**最新在前**），
  而聊天面板按微信习惯「最新在底部」渲染，两者相反 → 进入会话滚到底部看到的其实是最早的消息。
  修复：加载后 `asReversed()` 转升序；分页 `offset` 越大约早，取回后同样反转插入顶部并保持视口不跳；
  **新增滚到顶部自动加载更早**（原先只有手动按钮）；新增 `messageVersion` 让会话内收到新消息时
  增量合并（原先「正看着该会话就 return」，只更新会话摘要、详情列表不动）。
- **⑧ 退出微信 + 离开即释放**：
  · 微信页设置新增「退出微信」（二次确认）：释放 WS 连接、HTTP 连接池与线程池、语音、内存数据，
    与销售/智能控制/快递扫码的 `stop()` 彻底释放模式一致；再次进入自动重连。
  · 主页 设置→保活 新增开关「微信离开后立即结束」：打开后微信连接从**进程级常驻**降级为**页面级**，
    离开微信页立即释放（代价：离开期间收不到消息）；关闭（默认）保持常驻以保证实时收消息。
  · 内部：`WechatRuntime.stopAll()` + `stopped` 标志，并确保**已释放时不被重连机制/网络回调撤销**。

---

## 2026-11-01 · ⚡ 心跳放宽与去冗余（Bridge 主 WS 20s→60s、微信 15s→60s、双端相位对齐）

**节点意义**：v0.2.21 微信集成后进程级长连接增至 2 条、且各自开了「应用层 + 传输层」双层心跳，
无线唤醒错相叠加约 600 次/小时。本里程碑把 heartbeat 全项目盘了一遍并把进程级唤醒压到约 60 次/小时。

- **全项目心跳台账化（新 SSOT）**：新增 `docs/dev/heartbeat-inventory.md`，把 5 条长连接的心跳/传输 ping/
  判死/重连、生命周期证据、周期硬约束、耗电量级模型、实测方法、保活三锁语义全部落到一处，并在 `docs/INDEX.md` 登记。
- **纠正一条错误结论**：此前记的「微信心跳放宽需服务端同步调 Pong 超时」是错的——center-server 的 WS 主循环
  `while True: await websocket.receive_text()` **没有读超时**，唯一约束是 `CLIENT_HEARTBEAT_TIMEOUT=300`，
  故 15s/30s/60s/120s 全部合法且服务端零改动。真正卡上限的是运营商 NAT 空闲超时。
- **去双层冗余（根治）**：微信 WS 移除 OkHttp `pingInterval(20s)`。关键证据是 WS 协议 ping 帧由 uvicorn
  在协议层自动回 pong、**传不到 ASGI 应用层**，不刷新 center-server 的 `last_ping`（`api/routes/client.py:635`）——
  所以该保留的是应用层 ping，而非传输层那个。
- **修掉一个会静默停跳的缺陷**：`WechatWsClient.sendPing()` 原写法 `if (!send(...)) return`，
  发送失败时 `heartbeat` 已被 `removeCallbacks` 且不再重排 → 心跳永久停止。改前有传输层 ping 掩盖，
  去冗余后必须修（改为无论成败都重排下一栅格）。
- **相位对齐（新增能力）**：新增 `WsConfig.delayToNextGrid()`，Bridge 主 WS 与微信 WS 的 ping 统一落在
  Unix 毫秒时间的 60s 整数倍，共用一次无线唤醒；每次按当前时间重算，可自我纠正漂移。
- **服务端同步放宽**：`server/config.yaml` 与 `config.py` 的 `heartbeat.interval` 20→60、
  `server_read_timeout` 45→150（保留 2.5 倍余量）；`tests/test_config.py` 断言同步更新（5 passed）。
  用户已确认**不需考虑版本兼容**（大版本强制升级 + 服务端同步）。
- **顺带零风险优化**：`SilentPlayer.nextFrame()` 由每 tick `new ByteArray(1920)` 改为复用缓冲区，
  消除约 96 KB/s（≈8.3 GB/天）的短命对象分配。**tick 间隔未动**（仍 20ms）——见下条经验。

**关键经验沉淀**：
- **耗电结论完全取决于网络类型**：单次孤立无线唤醒 LTE ≈ 2.5 J、WiFi ≈ 0.05–0.3 J。
  **WiFi 常驻下把 15s 放宽到 60s 只省约 10–100 mAh/天（0.2%–2%），基本无意义**；只有移动数据下间隔才是关键变量。
- **合并/对齐心跳的收益大于放宽间隔**：3 个计时器错相 600 次/小时 → 对齐后 60 次/小时（−90%），且零可靠性损失。
- **WifiLock 在 Android 14+ 已经失效**：`WIFI_MODE_FULL_HIGH_PERF` 于 API 34 废弃并被平台自动替换为
  `WIFI_MODE_FULL_LOW_LATENCY`，后者仅在「已连 AP + 屏幕亮 + App 前台」生效 ⇒ 后台灭屏场景下这把锁是 no-op。
- **WakeLock 只管"CPU 睡不睡"，不管"能不能联网"**：入站包由射频固件唤醒 AP，下行推送仍可达；
  但取消后 CPU suspend 会让主动心跳发不出 → NAT 过期 → **连接静默半死（自认在线、收不到、且不知道）**；
  能否收包取决于 Doze 与电池白名单，而非 WakeLock（持锁反而顺带挡住了 Doze）。
- **SilentPlayer 调 tick 收益有限**：AudioFlinger mixer 按 HAL 周期自己跑、与写入 cadence 无关，
  音频通路常开（15–50 mA）占其成本 75–90% 且调 tick 省不掉；调 tick 最多省 10–25%。
  且**单独改 TICK_MS 会 underrun**（缓冲区 80ms、每 tick 仅写 20ms 音频），必须联动放大 FRAME_SAMPLES 与 bufferBytes。
- **改前状态必须逐字留档**：本次按新约定在 `heartbeat-inventory.md` §8 先固化改前原文（含 git 兜不住的
  未跟踪文件 `WechatWsClient.kt`），再动手，改后回填，附回滚配方与回滚触发信号。

---

## 2026-11-01 · 💬 v0.2.21 微信页集成里程碑（center-server 前端吞并 + 进程级常驻连接）

**节点意义**：把参考项目 `references/center-server/web-frontend`（Vue3 远程控制面板）的能力搬进 Bridge，
在**主页与销售之间**新增底部导航「微信」页（沉浸 + 页内底部二级 Tab）；客户端直连中心服务器（参考服务端零改动）。
辅线是**实时性架构**：与销售页/智能控制的「页面级连接」不同，微信要求离开页面也能收到消息并通知，
因此连接抬到进程级常驻。

### 脉络1 · 实时收消息（本功能与其它集成最大的不同）

- **v0.2.21**：`WechatRuntime` 进程级单例持连接（App 启动即连、**常开不设关闭开关**）；页面只订阅/退订，
  退出不断连。进程存活复用既有 `KeepAliveService` 常驻 FGS + 电池白名单 + 开机自启 + 双兜底，
  **不新增前台服务**（避免触碰 `docs/dev/crash-ledger.md` §5 崩溃高危面）→ 根治「离开页面就收不到」的根因。
- 断线感知三重：心跳 15s / Pong 60s / OkHttp ping 20s + **网络恢复即刻重连**（不等超时）；重连固定 2s（照参考项目，不做指数退避）；
  重连成功后拉 `unread_total` 校准，防断线期间漏计。

### 脉络2 · 契约实锤（避免凭空想象）

- 会话列表**没有账号维度**（服务端 SQL 与前端 `SessionItem` 均无 `account_wxid`）→ 全局不过滤；账号上下文只用于发送。
- 手机端发送只能走 multipart `wechat/upload_and_send`：`send_image/send_file/send_video` 的参数是**服务器文件路径**。
- 附件字段：图片 `thumb_path`/`image_path`、视频 `movie_cover`/`movie_path`、语音 `voice_path`+`duration`、文件 `path`、
  表情 `emoji_object`、位置 `map_object`/`poiname`/`coordinate`。
- **语音发送不做**：`wechat/upload_and_send_voice` 服务端是空实现（注释「微信DLL不支持发送语音…直接返回成功」），
  加了就是假功能。

### 脉络3 · 取舍（照 Bridge 现状）

- 参考 `utils/keepAlive.ts`（H5+ 通知栏/CPU·WiFi 锁/蜂鸣/隐藏最近任务）**整段不移植**，Bridge 已有 `core-keepalive`。
- 工具箱**只占位**（与 Bridge 现有命令重复的拍照/流式录音录像/短信/GPS 不做）。
- 不引 Coil/Glide：自实现 `WechatImageLoader`（OkHttp + LruCache + 采样解码），附件需带 token/Bearer。
- 通知 id **2101+**（`NotificationPoster` 与 `KeepAliveService` 都占用 2001，复用会顶掉保活前台通知）。

### 待办（遗留）

- **真机验证**：需可用的中心服务器地址与 API Key；无微信/Agent 实体环境，测试由用户手动。
- 心跳 15s 的耗电需实测评估（若要放宽到 30s，服务端 Pong 超时必须同步调整）。

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
