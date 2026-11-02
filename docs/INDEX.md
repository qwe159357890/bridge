# Bridge 文档索引

> 本文档是 Bridge 项目**所有文档的唯一导航入口**。新增文档后必须在此登记。

## 📚 文档清单

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
| release-pipeline.md | 构建与发布链路（git 推送 + GitHub Actions 打包 + 签名密钥管理） |
| [heartbeat-inventory.md](dev/heartbeat-inventory.md) | **心跳与周期任务台账**（6 条长连接的心跳/传输 ping/判死/重连全景，含 v0.2.30/v0.2.32 VNT 黑盒保活（v0.2.32 起为 1.x `awaitTimeout` 循环）+ 各连接周期硬约束 + 耗电量级模型 + 优化点与实测方法；调任何周期前先查这里） |
| [crash-ledger.md](dev/crash-ledger.md) | **崩溃台账**（三次根因时间线 + `startForegroundService` 义务按次计铁律 + 全量审计表 + 排障工具链 + **新前台服务接入检查清单 §5**；新服务接入前对照） |
| [vnt-integration-change-ledger.md](dev/vnt-integration-change-ledger.md) | **VNT 集成改前留档台账**（§一~四 = v0.2.30 P1+P2 全部既有文件改前原文逐字固化 + 回滚配方 + 触发回滚信号 + 新增文件删除清单 + 改后回填；**§五 = v0.2.31** 诊断证据链、改前原文/哈希、新增文件、回滚配方；**§六 = v0.2.32** A1 纯替换 1.x 全量改前留档 + 删除清单 + 回滚配方 + 改后回填） |
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

### 调研报告（research/）
| 文档 | 说明 |
|---|---|
| [esp32-relay-integration.md](research/esp32-relay-integration.md) | **ESP32 继电器项目集成调研**（参考项目结构/功能清单、与 Bridge 现状对照、集成方案 A/B/C 对比、推荐「客户端直连参考中继服务器」落地设计；**已落地实现**：`switch/` 包 + `ui/RelaySwitchPages.kt` + `ui/RelaySwitchIcons.kt`，含连接生命周期/按钮配色/图标还原/窗格网格等细化决策） |
| [barcode-scanner-integration.md](research/barcode-scanner-integration.md) | **快递扫码功能集成调研**（Barcode to PC 参考项目：协议层完整还原 helo/ping-pong/putScanSessions-ACK/离线对账、扫描准确率手段、集成方案对比→推荐自研三端（CameraX+ML Kit 多帧投票）、需要实现的清单与坑；**未实现**，原型见 `assets/design/ui-mockup-barcode-scanner.html`） |
| [salesrecord-remote-integration.md](research/salesrecord-remote-integration.md) | **SalesRecordRemote 售后管理系统集成调研**（参考项目三端结构/服务端 REST+WS 契约实锤/客户端功能清单/与 Bridge 现状对照/集成方案：客户端直连参考服务器+底部导航插「销售」Tab+桌面交互→触屏映射表/语言差异防 bug 清单；**已实现 v0.2.09**，`app/.../sales/`+`ui/SalesPages.kt`，交互原型见 `assets/design/ui-mockup-sales-page.html`） |
| [vnt-mesh-integration.md](research/vnt-mesh-integration.md) | **VNT 组网（虚拟局域网 / SD-WAN）集成调研**（三仓库全景 + 2.x `com.vnt` / 当前 1.x `top.wherewego.vnt.jni` 绑定 API 面与调用时序 + Android VPN 硬约束 + **官方预编译 so 产物方案与校验** + 运行期刷新机制源码实锤 + Bridge 现状对照 + 方案对比（推荐 JNI 内嵌）+ 落地设计/坑清单/9 项决策/开工核对清单；**v0.2.30 曾实现 2.x（已作废，见 §十三）**：`com/vnt/*.java` + `my/bridge/com/vnt/` + `ui/VntPages.kt` + 主页第 5 Tab + `vnt_start/vnt_stop/vnt_status` 三条命令；**★ v0.2.32 起核心整体替换为 1.x（官方 VntApp v1.2.9 Java JNI 版），当前实现以 §十三 为准**；§十二 的 v0.2.31 修订（universal 三套 ABI / 关于页滚动 / 自动组网记忆 / v2.0.11 地址规则）因「自建 VNTS + 存量客户端全 1.x、必须互通、服务端不能升级」**仅作历史记录**；**真机互通验证待做**，原型见 `assets/design/ui-mockup-vnt-mesh.html`，改前留档见 `docs/dev/vnt-integration-change-ledger.md`） |
| [center-server-wechat-integration.md](research/center-server-wechat-integration.md) | **微信中心服务器（center-server）前端集成调研**（参考项目结构/前端真实功能与实例账号上下文/REST+WS 契约实锤/与 Bridge 现状对照与保活·工具箱取舍/推荐 `wechat/` 包与「主页·微信·销售·设置」导航方案/风险与分期；**已拍板形态：微信页沉浸 + 页内底部二级 Tab + 状态条仅断线显示 + 账号选择移入输入栏 + 后台常驻收消息（进程级连接、不新增前台服务）+ 工具箱占位 + 附件存 `/sdcard/bridge/wechat/` + 提示音与通知**；**已实现 v0.2.20**：`app/.../wechat/` 全套（进程级常驻连接/通知与提示音/富媒体与下载器）+ `ui/WechatPages.kt` + `ui/WechatMedia.kt` + 底部导航「主页·微信·销售·设置」；仅**真机验证待做**，交互原型见 `assets/design/ui-mockup-wechat-page.html`） |

### 管理后台（admin/）
| 文档 | 说明 |
|---|---|
| [design.md](admin/design.md) | **管理后台设计**（Vue3 + Element Plus Web 前端：设备管理/命令下发/日志查看/消息推送/连接监控/仪表盘 + 简单管理员鉴权 + 对接服务端 REST 接口） |

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
