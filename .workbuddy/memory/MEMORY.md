# 项目记忆

## ★ 记忆使用原则（最高优先级，写入记忆前必读）

**记忆是「导航地图」，不是「信息仓库」——只存指针与约定，不存内容副本。**

1. **去重优先**：凡 `docs/` 或代码注释等已有记录的内容，**不重复写入记忆**，只在记忆里留「结论 + 权威文档路径」。
2. **只存三类信息**：
   - **决策/约定**：技术选型、命名规范、目录规范、开关默认值等「必须被长期遵守」的规则；
   - **结论/指针**：某件事的结论 + 指向权威文档的路径（如 `docs/keepalive/design.md`）；
   - **未落地的 TODO**：待办事项、待适配项。
3. **不存的内容**：研究过程的细节、组件清单、踩坑清单、代码清单——这些归 `docs/`，记忆只留一句「详见 xxx」。
4. **单一事实来源（SSOT）**：同一事实只在一个地方权威记录（优先 docs），记忆仅索引，避免双写导致的过期与不一致。

## ★ 项目目录组织规范（最高优先级，任何步骤必须遵循）

**Bridge 是企业级大型多功能 Android 项目，区分「应用模块架构」与「项目目录结构」两类概念：**
- 应用模块架构（代码分层）：`app/`（壳）→ `feature/`（业务，未来）→ `core/`（基础层 core-common/model/network/storage/ui/keepalive）。
- 项目目录结构（仓库组织）：
  - `docs/` 项目文档——**总领 `docs/INDEX.md` + 分文档**（architecture/ keepalive/ dev/ api/），新模块/功能写独立文档，**绝不在 README 堆内容**，README 只做入口。
  - `assets/` 静态资源源文件（icons/images/audio/fonts/design）——放原始素材，不放编译产物。
  - `data/` 数据类文件（sqlite/config/samples/schema）——数据库/配置文件/样本数据等。
  - `references/` 参考源码（只读，原 example/）——借鉴用的第三方样例，不参与构建。
  - `tools/` 工具脚本——从源文件生成产物的可重复脚本（如图标生成）。
- 命名：文档/目录 kebab-case，类 PascalCase。
- 权威文档：`docs/architecture/directory-convention.md`（目录组织宪法）。

## 项目定位与架构（2026-10-23）

- 项目名 **Bridge**，包名 **my.bridge.com**，定位：企业级、大型、多功能重量级 Android 应用。
- 技术栈：Kotlin + Jetpack Compose + Hilt；AGP 8.13.1 / Kotlin 2.0.21 / Gradle 8.10.2 / JDK 17 / compileSdk 36 / minSdk 24。
- 多模块架构（Google Now in Android 范式）：app 壳工程 + core/{core-common, core-model, core-network, core-storage, core-ui, core-keepalive}；feature/ 未来按功能添加。
- 版本目录统一管理依赖：gradle/libs.versions.toml。
- 图标源图：`assets/icons/沟通记录.png`（229×200 非正方形），生成脚本 `tools/icon/generate_icon.py`。

## 构建发布链路（2026-10-23）

- **结论**：当前为**内部测试阶段**，用 **debug 签名**打包。全自动闭环：用户提供 1 个 GitHub PAT → AI 自动建仓库（GitHub API）→ push → 打 tag → GitHub Actions 打包 → 代理下载 APK 到本地 `releases/`。**用户除生成 PAT 外无需任何操作。**
- **平台**：GitHub（Gitee 不支持 GitHub Actions 云端打包，故打包走 GitHub）。用户之前有 gitee_updater 经验（GITEE_TOKEN 环境变量），但本项目的打包必须用 GitHub。
- **脚本**：`tools/release/release.py`（自动建仓库 → push → 触发打包 → 轮询 → 下载 APK）；`github.env.example` 配置模板（GITHUB_USER/GITHUB_REPO/GITHUB_PRIVATE/GITHUB_TOKEN）。
- **待办（需用户，一次性）**：仅 ① 生成 GitHub PAT（勾选 repo+workflow）并告诉 AI。仓库由 AI 自动建。
- **权威文档**：完整步骤见 `docs/dev/release-pipeline.md`。
- **约定**：`references/`（32MB 第三方源码）不入库；`github.env`/`releases/` 不入库；gradlew 为 Gradle 8.13（与 AGP 8.13.1 匹配）；未来上架 Play 再启用正式签名（参考 aurelay 的 fastlane + Play App Signing）。

## 保活模块（2026-10-23）

- **结论**：借鉴 `references/` 下 Aurelay（自研多层）与 SmsForwarder（轻量框架）两方案，取「两者结合」——吸收 Aurelay 分层架构 + SmsForwarder 电池白名单引导，去掉第三方 Cactus 依赖与激进权限，产出**自研轻量保活模块**。
- **位置**：`core/core-keepalive`（包名 my.bridge.com.keepalive），共 9 个类，入口 Activity 经 `KeepAliveEntryActivityHolder` 注册解耦。
- **默认开关**：总开关/开机自启/双兜底/资源锚点/无声播放 = 开；一像素 = 关（避免悬浮窗提示）。
- **最有效手段排序**：前台服务 > 电池白名单引导 > 开机自启 > 双兜底 > 一像素/无声播放 > 资源锚点。
- **权威文档**：设计详见 `docs/keepalive/design.md`（含组件清单/踩坑清单），集成详见 `docs/keepalive/integration.md`，Manifest 模板 `core/core-keepalive/AndroidManifest.template.xml`。
- **待适配 TODO**：`KeepAliveService.openMainActivityClass()` 入口类名、通知小图标资源。
