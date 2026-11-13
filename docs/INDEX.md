# Bridge 文档索引

> 本文档是 Bridge 项目**所有文档的唯一导航入口**。新增文档后必须在此登记。

## ★ 模块使用文档约定

每个功能模块在以下两个时机，必须在 `guides/` 目录总结一篇**面向使用者**的功能使用文档（功能概览 / 如何启动 / 连接参数 / 使用说明 / 能做与不能做 / 配置项 / FAQ）：

1. **阶段性**——功能主干可用、经过真机验证时；
2. **完全完成**——功能模块整体收尾时。

使用文档与开发文档（`dev/` 台账、`research/` 调研）分工：guides 写「怎么用」，dev/research 写「怎么做的」。

## 📚 文档清单

### 模块使用文档（guides/）
| 文档 | 说明 |
|---|---|
| [automation.md](guides/automation.md) | **自动化模块使用文档**（面向小白：这是什么、一次性授权配置、连接手机三步、Hello World、能力全景、能做与不能做、配置项、FAQ；功能怎么用见本文，怎么实现见 research/） |
| [ssh-server.md](guides/ssh-server.md) | **SSH 服务器使用文档**（SFTP 文件管理 + 远程终端：启动方式、连接参数、快捷目录/内置 alias、存储档、能做与不能做（含为何装不了 zsh/oh-my-zsh）、配置项、FAQ） |
| [jikong.md](guides/jikong.md) | **极空（JK BMS）保护板使用文档**（扫描连接/实时数据/语音播报/里程统计/靠近自动连接/连接提示音/演示模式：怎么连、四页怎么用、各设置项、能做与不能做、FAQ；功能怎么用见本文，怎么实现见 research/） |

### 架构类（architecture/）
| 文档 | 说明 |
|---|---|
| [directory-convention.md](architecture/directory-convention.md) | **项目目录组织规范（宪法）** |
| [terminology.md](architecture/terminology.md) | **术语约定（三端叫法：客户端/服务端/管理后台）** |
| [live-screen-research.md](architecture/live-screen-research.md) | 投屏实时直播调研（方案/延迟评估/实现排期，未实现） |
| overview.md | 总体架构设计（待补充） |
| module-map.md | 模块依赖关系图（待补充） |

### 保活专题（keepalive/）
| 文档 | 说明 |
|---|---|
| design.md | 保活方案设计（借鉴归纳） |
| integration.md | 保活模块集成说明 |

### 开发规范（dev/）
| 文档 | 说明 |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | **变更记录（版本里程碑与关键修复脉络）** |
| [release-pipeline.md](dev/release-pipeline.md) | **构建与发布链路**（git 推送 + GitHub Actions 打包 + 签名密钥管理 + **严格打包下载铁律**：APK 唯一留存、`update.json` 的 sha/size 与留存包一致、本地=远程） |
| [deploy-defaults-ledger.md](dev/deploy-defaults-ledger.md) | **部署默认值改前留档台账**（三处默认值 / nginx conf 改前原文逐字固化 + 改后回填 + 回滚配方 + 触发信号 + 编译与生产验收；§7 为里程碑收尾修订留档） |
| [log-governance-ledger.md](dev/log-governance-ledger.md) | **全项目日志治理台账**（v0.2.130：`LogThrottle` 重连风暴节流 + 网络回调日志删除 + BLE hex dump 开关 + 智能控制启动汇总；含「为什么不是只记错误」） |
| [heartbeat-inventory.md](dev/heartbeat-inventory.md) | **心跳与周期任务台账**（6 条长连接的心跳/传输 ping/判死/重连全景，含 VNT 黑盒保活与 **v0.2.38 监督重连**（可重试失败不停服务 + 退避 + NetworkCallback 立即重试）+ 各连接周期硬约束 + 耗电量级模型 + 优化点与实测方法；调任何周期前先查这里） |
| [crash-ledger.md](dev/crash-ledger.md) | **崩溃台账**（三次根因时间线 + `startForegroundService` 义务按次计铁律 + 全量审计表 + 排障工具链 + **新前台服务接入检查清单 §5**；新服务接入前对照） |
| [vnt-integration-change-ledger.md](dev/vnt-integration-change-ledger.md) | **VNT 集成改前留档台账**（§一~四 = v0.2.30 P1+P2 全部既有文件改前原文逐字固化 + 回滚配方 + 触发回滚信号 + 新增文件删除清单 + 改后回填；**§五 = v0.2.31** 诊断证据链、改前原文/哈希、新增文件、回滚配方；**§六 = v0.2.32** A1 纯替换 1.x 全量改前留档 + 删除清单 + 回滚配方 + 改后回填；**§七 = v0.2.38** 断线自动重连的改前原文/哈希、监督循环改造、回滚配方与真机验收清单；**§八 = v0.2.131** 1.x→2.x（v2.0.12）反向升级的改前基线/指纹、改动清单、断线重连错误重映射、回滚配方、触发信号与验收清单） |
| [jikong-change-ledger.md](dev/jikong-change-ledger.md) | **极空（JK BMS）改前留档台账**（§一 = 当前生效回退锚点：**v0.2.82 对齐官方握手序列**（HIGH 优先级 + 80ms 发现服务 + CCCD 后 40ms 官方 21B 握手帧 + MTU 517），回退标记 = git 快照 `cacc8f5`（=v0.2.81），一条命令回滚配方 + 触发回滚信号；§二 = 历史改动登记。**极空相关改动前先更新此台账**） |
| coding-style.md | 代码规范（待补充） |
| git-workflow.md | Git 协作规范（待补充） |
| commit-convention.md | 提交信息规范（待补充） |

### 接口文档（api/）
| 文档 | 说明 |
|---|---|
| [ws-client.md](api/ws-client.md) | **客户端 WebSocket 对接设计**（握手/心跳/重连/唯一连接 + ANDROID_ID 设备标识 + 设备昵称 + 命令下发执行 + 诊断日志上传） |
| [command-catalog.md](api/command-catalog.md) | **命令目录与下发页设计**（命令元数据规范：后端单一事实来源 + 分类树/卡片墙/参数面板交互 + 三端对齐 + 命令实现文档组织规则） |
| [commands/](api/commands/README.md) | **命令实现文档**（按命令分类聚合：control/data/media/interact/system，README 为命令级索引 + 缺文档台账；INDEX 只登记目录级入口；`system.md` 含 **v0.2.33 组网命令状态联动 / 幂等守卫 / 强制下发二次确认**） |

### 后端服务（server/）
| 文档 | 说明 |
|---|---|
| [design.md](server/design.md) | **后端服务设计**（WebSocket 连接管理：心跳/断线/重连/唯一连接/顶旧连接 + 消息推送 + 设备鉴权/昵称 + 命令下发 + 日志上传读取 + REST 管理接口 + 路由包组织） |
| [bridge-supervisor.conf](server/bridge-supervisor.conf) | **supervisord 进程配置模板**（后端 `uv run main.py` 守护 + 一次性 TTS 预热任务 + 测试机 Vite dev 可选段；去 `sudo`、进程组杀、日志切割、部署前置与回滚） |
| [aurelay-relay-troubleshooting.md](server/aurelay-relay-troubleshooting.md) · [aurelay-relay-reconnect-troubleshooting.md](server/aurelay-relay-reconnect-troubleshooting.md) | 音频中继排障 / WiFi↔蜂窝切换重连时序证据专档（历史） |

### 调研报告（research/）
| 文档 | 说明 |
|---|---|
| [esp32-relay-integration.md](research/esp32-relay-integration.md) | **ESP32 继电器项目集成调研**（参考项目结构/功能清单、与 Bridge 现状对照、集成方案 A/B/C 对比、推荐「客户端直连参考中继服务器」落地设计；**已落地实现**：`switch/` 包 + `ui/RelaySwitchPages.kt` + `ui/RelaySwitchIcons.kt`，含连接生命周期/按钮配色/图标还原/窗格网格等细化决策） |
| [barcode-scanner-integration.md](research/barcode-scanner-integration.md) | **快递扫码功能集成调研**（Barcode to PC 参考项目：协议层完整还原 helo/ping-pong/putScanSessions-ACK/离线对账、扫描准确率手段、集成方案对比→推荐自研三端（CameraX+ML Kit 多帧投票）、需要实现的清单与坑；**未实现**，原型见 `assets/design/ui-mockup-barcode-scanner.html`） |
| [salesrecord-remote-integration.md](research/salesrecord-remote-integration.md) | **SalesRecordRemote 售后管理系统集成调研**（参考项目三端结构/服务端 REST+WS 契约实锤/客户端功能清单/与 Bridge 现状对照/集成方案：客户端直连参考服务器+底部导航插「销售」Tab+桌面交互→触屏映射表/语言差异防 bug 清单；**已实现 v0.2.09**，`app/.../sales/`+`ui/SalesPages.kt`，交互原型见 `assets/design/ui-mockup-sales-page.html`） |
| [vnt-mesh-integration.md](research/vnt-mesh-integration.md) | **VNT 组网（虚拟局域网 / SD-WAN）集成调研**（三仓库全景 + 2.x `com.vnt` 绑定 API 面与调用时序 + Android VPN 硬约束 + **官方预编译 so 产物方案与校验** + 运行期刷新机制源码实锤 + Bridge 现状对照 + 方案对比（推荐 JNI 内嵌）+ 落地设计/坑清单/9 项决策/开工核对清单；**v0.2.30 曾实现 2.x（§十三 记录，历史）**：`com/vnt/*.java` + `my/bridge/com/vnt/` + `ui/VntPages.kt` + 主页第 5 Tab + `vnt_start/vnt_stop/vnt_status` 三条命令；**★ v0.2.131 起核心升级为 2.x（vnt-dev v2.0.12），接入自建 vnts2（`quic://039039.xyz:31008` + `tcp://039039.xyz:31008`）**；v0.2.32~v0.2.130 的 1.x（官方 VntApp v1.2.9 Java JNI 版）实现**已作废，仅作历史记录**（§十三 的 1.x 差异说明保留为回滚参照）；**真机互通验证待做**，原型见 `assets/design/ui-mockup-vnt-mesh.html`，改前留档见 `docs/dev/vnt-integration-change-ledger.md` §八） |
| [center-server-wechat-integration.md](research/center-server-wechat-integration.md) | **微信中心服务器（center-server）前端集成调研**（参考项目结构/前端真实功能与实例账号上下文/REST+WS 契约实锤/与 Bridge 现状对照与保活·工具箱取舍/推荐 `wechat/` 包与「主页·微信·销售·设置」导航方案/风险与分期；**已拍板形态：微信页沉浸 + 页内底部二级 Tab + 状态条仅断线显示 + 账号选择移入输入栏 + 后台常驻收消息（进程级连接、不新增前台服务）+ 工具箱占位 + 附件存 `/sdcard/bridge/wechat/` + 提示音与通知**；**已实现 v0.2.20**：`app/.../wechat/` 全套（进程级常驻连接/通知与提示音/富媒体与下载器）+ `ui/WechatPages.kt` + `ui/WechatMedia.kt` + 底部导航「主页·微信·销售·设置」；仅**真机验证待做**，交互原型见 `assets/design/ui-mockup-wechat-page.html`） |
| [sshd-integration.md](research/sshd-integration.md) | **客户端 SSH 服务器（sshd）集成调研**（需求=SFTP 文件管理+远程终端、UI 开关+命令体系控制；平台硬约束盘点（minSdk 24 vs NIO2 API 26、BC provider、W^X、scoped storage、Doze）；四方案对比——**推荐方案 A：Apache MINA SSHD 2.20.x 纯 Java 内嵌 + sshd-mina 传输层**（零 NDK 零安装），Dropbear NDK / Termux 搬运 / Go gomobile 作废或备选；替代协议对照（FTP/WebDAV/SMB/ADB over WiFi）SFTP 全优；落地设计：`ssh/` 包 + `specialUse` 前台服务（照 VNT 模式）+ `ssh_start/stop/status` 三命令（v0.2.33 状态联动全套）+ VNT 虚拟网协同 + 双档存储暴露面 + 11 条坑清单 + 分期 P0/P1/P2 + 决策清单；**决策已全部拍板（2026-11-02）**：端口 8022 / 首启随机密码可改可持久化 / P0 直接申请全盘 / P0 完整 sh / `ssh_start,ssh_stop,ssh_status`；交互原型见 `assets/design/ui-mockup-ssh-page.html`；**未实现，可开工**） |
| [accessibility-enrichment.md](research/accessibility-enrichment.md) | **无障碍能力丰富化调研：对标 AutoX.js**（动作层移植 + 耗电评估 + 实施方案；**已落地**：`access/automator/` 24 文件 + `BridgeAccessibilityService` + `BridgeKeyboard` + 7 条 ui_* 命令） |
| [automation-benchmark.md](research/automation-benchmark.md) | **流程自动化对比评估：Droidline vs AutoX.js**（两者分别是什么流程自动化 + 工程能力借鉴优先级 P0/P1/P2） |
| [automation-roadmap.md](research/automation-roadmap.md) | **自动化平台两阶段路线图**（① 控件探查工具 + ② Python RPC；**已实施 A/C 阶段**：点击三级降级/选择器 DSL/IME 兜底 + rpc_start/stop/status + 10 端点 HTTP server + token 鉴权 + Python SDK） |
| [droidline-feature-inventory.md](research/droidline-feature-inventory.md) | **自动化功能全量清单（Droidline + AutoX 逐行梳理）**（逐行读两个项目源码：Droidline 48 主源码文件 + AutoX runtime/api + inputevent + image 全能力；Droidline「脑+神经+眼睛」全收、AutoX「手+感官」补缺（devices/APP/按键模拟/图像找色/Shell/Media/Files/Sensors）；~100 项能力全景 + 明确取舍；自动化模块实施总蓝本。**已按本清单实施 v0.2.40**） |
| [automation-directory-separation.md](research/automation-directory-separation.md) | **自动化模块目录分离方案**（第一步：三端自动化代码收敛到 `com/automation` 包，11 子包 a11y/automator/query/input/device/notification/rpc/commands；服务端/前端拆分 + 迁移步骤 + 命名约定 + ColorFinder 去 OpenCV 坑。**批次 0 已迁移完成**） |
| [automation-implementation-plan.md](research/automation-implementation-plan.md) | **自动化模块实施路线**（第三步：批次 0 目录分离 → 批次 1 关系查询+错误码 → 批次 2 动作层 ~30 命令 → 批次 3 设备+应用 → 批次 4 输入+找色+传感器+文件+媒体 → 批次 5 通知子域+RPC 端点 → 批次 6 SDK 视觉 → 批次 7 P2 后置。**批次 0-6 已实施（v0.2.40），批次 7 P2 暂不做**） |
| [automation-summary.md](research/automation-summary.md) | **自动化模块阶段性总结**（已交付物总览、能力全景、与 Droidline/AutoX 对照结论、待真机验证清单 §五、遗留 TODO、下一步；功能怎么用见 `guides/automation.md`） |
| [jikong-bms-integration.md](research/jikong-bms-integration.md) | **极空（JK BMS）保护板集成调研**（极空是什么、BLE 协议/UUID 反编译实证、官方 APP 界面、复刻+远程中转方案、命令清单、分期落地；**已实现 v0.2.55**：`jikong/` 全套 + `ui/JikongPages.kt` + 服务端/后台极空页） |
| [jikong-proximity-autoconnect.md](research/jikong-proximity-autoconnect.md) | **极空「靠近自动连接」需求与调研**（需求最终版 V2、autoConnect 挂起方案选型、省电量化 <0.5%/天、实现设计 §三、握手剖析 §四；**已实现 v0.2.107→109**：`JikongProximityService` + 半死链直连重建自愈） |
| [jikong-handshake-fix.md](research/jikong-handshake-fix.md) | **极空「半死链唤醒」握手修复专档**（问题冻结、基线锚点、单变量实验纪律、实验①~④矩阵与卡片、兜底方向；结论见 `jikong-summary.md` §四） |
| [jikong-summary.md](research/jikong-summary.md) | **极空功能阶段性总结**（交付物总览、能力全景、真机验证结论、遗留 TODO、下一步；**功能阶段性完成**，功能怎么用见 `guides/jikong.md`） |

### 管理后台（admin/）
| 文档 | 说明 |
|---|---|
| [design.md](admin/design.md) | **管理后台设计**（Vue3 + Element Plus Web 前端：设备管理/命令下发/日志查看/消息推送/连接监控/仪表盘 + 简单管理员鉴权 + 对接服务端 REST 接口） |
| [client-embed-admin.md](admin/client-embed-admin.md) | **客户端内嵌管理后台设计**（形态 A：admin 由 nginx / 开发机托管、`VITE_API_BASE` 留空走同源相对路径、App WebView 加载可配置 URL、返回键与移动端/横屏适配） |
| [environment-matrix.md](admin/environment-matrix.md) | **生产 / 测试环境配置对照（白话版 · SSOT）**（三处地址取值、Mermaid 链路图、端口地图、15080/301 实测结论、改前改后、生产/测试步骤、界面位置纯文字路径、只读验收命令） |
| [bridge-admin.conf](admin/bridge-admin.conf) | **nginx 站点配置模板**（15443 静态 dist + `/api` `/ws` `/d` `/download` `/static` `/health` 反代、WebSocket Upgrade 头、`X-Forwarded-Host $http_host`、SPA fallback、缓存策略；末尾附验证清单，与 `wxhelper.conf` 同 server_name 时二选一） |

### 跨项目通用引用（不在本仓库内）
| 文档 | 说明 |
|---|---|
| **通用机器台账** | **跨项目通用基础设施台账**（机器命名/组网 IP 分配/凭据/SSH 免密/链路优先级），物理文件存于用户级公共目录 `C:\Users\Administrator\.workbuddy\通用机器台账.md`，不在本 git 仓库内。涉及 VNT 组网服务端（mbh/魔百盒）、写代码机（zaomai/早买）、SSH 免密、Tailscale/VNT 组网地址等**跨机器信息时，先查此台账** |

---

## 📁 目录速查

| 目录 | 用途 |
|---|---|
| `app/` `core/` `feature/` | 代码（应用模块架构，Android 客户端） |
| `server/` | Python 后端服务（FastAPI + WebSocket，独立服务） |
| `admin/` | 管理后台（Vue3 + Element Plus Web 前端，独立服务） |
| `docs/` | 项目文档 |
| `assets/` | 静态资源源文件 |
| `data/` | 数据类文件 |
| `references/` | 参考源码（只读） |
| `tools/` | 工具脚本 |

---

*目录组织规则详见 [architecture/directory-convention.md](architecture/directory-convention.md)。*
