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
| coding-style.md | 代码规范（待补充） |
| git-workflow.md | Git 协作规范（待补充） |
| commit-convention.md | 提交信息规范（待补充） |

### 接口文档（api/）
| 文档 | 说明 |
|---|---|
| [ws-client.md](api/ws-client.md) | **客户端 WebSocket 对接设计**（握手/心跳/重连/唯一连接 + ANDROID_ID 设备标识 + 设备昵称 + 命令下发执行 + 诊断日志上传） |
| [command-catalog.md](api/command-catalog.md) | **命令目录与下发页设计**（命令元数据规范：后端单一事实来源 + 分类树/卡片墙/参数面板交互 + 三端对齐 + 命令实现文档组织规则） |
| [commands/](api/commands/README.md) | **命令实现文档**（按命令分类聚合：control/data/media/interact/system，README 为命令级索引 + 缺文档台账；INDEX 只登记目录级入口） |

### 后端服务（server/）
| 文档 | 说明 |
|---|---|
| [design.md](server/design.md) | **后端服务设计**（WebSocket 连接管理：心跳/断线/重连/唯一连接/顶旧连接 + 消息推送 + 设备鉴权/昵称 + 命令下发 + 日志上传读取 + REST 管理接口 + 路由包组织） |

### 调研报告（research/）
| 文档 | 说明 |
|---|---|
| [esp32-relay-integration.md](research/esp32-relay-integration.md) | **ESP32 继电器项目集成调研**（参考项目结构/功能清单、与 Bridge 现状对照、集成方案 A/B/C 对比、推荐「客户端直连参考中继服务器」落地设计；**已落地实现**：`switch/` 包 + `ui/RelaySwitchPages.kt` + `ui/RelaySwitchIcons.kt`，含连接生命周期/按钮配色/图标还原/窗格网格等细化决策） |

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
