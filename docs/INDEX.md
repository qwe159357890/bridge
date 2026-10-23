# Bridge 文档索引

> 本文档是 Bridge 项目**所有文档的唯一导航入口**。新增文档后必须在此登记。

## 📚 文档清单

### 架构类（architecture/）
| 文档 | 说明 |
|---|---|
| [directory-convention.md](architecture/directory-convention.md) | **项目目录组织规范（宪法）** |
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
| release-pipeline.md | 构建与发布链路（git 推送 + GitHub Actions 打包 + 签名密钥管理） |
| coding-style.md | 代码规范（待补充） |
| git-workflow.md | Git 协作规范（待补充） |
| commit-convention.md | 提交信息规范（待补充） |

### 接口文档（api/）
（待补充）

---

## 📁 目录速查

| 目录 | 用途 |
|---|---|
| `app/` `core/` `feature/` | 代码（应用模块架构） |
| `docs/` | 项目文档 |
| `assets/` | 静态资源源文件 |
| `data/` | 数据类文件 |
| `references/` | 参考源码（只读） |
| `tools/` | 工具脚本 |

---

*目录组织规则详见 [architecture/directory-convention.md](architecture/directory-convention.md)。*
