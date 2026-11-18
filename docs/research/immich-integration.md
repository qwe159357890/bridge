# Immich 相册集成调研

> 需求：在 Bridge 客户端中像 VNT / 极空 / 监控一样，嵌入一个「相册」页面——外观、交互与 Immich 原版安卓端一致（人眼看一样、用起来一样），其独有功能可适当精简。
> 本文回答三个问题：①Immich 仓库里有没有安卓端源码；②通讯协议是什么；③怎么嵌入。调研日期：2026-11-16。
>
> **进展更新（同日）**：
> - 交互原型已产出：`assets/design/ui-mockup-immich.html`（复刻 v3.4 安卓版：照片/搜索/相册/资料四 Tab + 大图查看器 + 相册详情，数据为本机真实服务端实测）。
> - Immich 全仓库已本地化：`references/immich/`（107MB，gh-proxy 代理下载，含 mobile 源码 621 dart 文件 / docs / design 官方截图）。
> - 本机服务端已部署并验证：`http://039039.xyz:15148`（14250 资产 / 19 相册；thumbnail 端点实测 webp；无 HLS 路由 → 视频统一走 `video/playback`）。
> - ⚠️ 下载铁律补充：**任何 GitHub 资源下载必须优先走 `https://v4.gh-proxy.org/` 前缀代理**（实测直连 0.2MB/min vs 代理 11MB/s），见 `docs/dev/release-pipeline.md` §「gh-proxy 铁律」。

---

## 〇、结论速览

| 问题 | 结论 |
|---|---|
| 有安卓端源码吗 | **有**。仓库 `mobile/` 目录即安卓+iOS 移动端全部源码，技术栈 **Flutter（Dart）**，GitHub Releases 直接发 APK [cite:1][cite:2] |
| 通讯协议是什么 | **REST API + OpenAPI 规范**（全部官方客户端由 OpenAPI 自动生成 SDK），默认端口 2283，路径前缀 `/api`；鉴权支持 API Key / 会话 Token / 共享链接三种体系 [cite:1][cite:3] |
| 第三方能不能对接 | **能**。官方 API 文档站 `api.immich.app` 明确面向第三方开发者；相册/资产/搜索/上传/鉴权等核心端点全部 `Stable` 状态，但**时间线（timeline）端点标注 Internal，外部应用不应依赖** [cite:3][cite:4] |
| 推荐嵌入方式 | **方案 A：原生 Compose 复刻 UI + 客户端直连 Immich REST API**（与监控 NVR 集成完全同模式：纯客户端、Bridge 服务端零改动） |
| 最大门槛 | ①服务端部署要求 Docker + **6GB RAM / 2 核**起步（含 Postgres + Redis 两个额外容器）；②许可证 **AGPLv3**（不可复制其代码/素材，自研复刻无碍） |

---

## 一、Immich 是什么

Immich 是一个开源自托管的「Google Photos 替代品」：手机照片视频自动备份到自己的服务器，然后在时间线/相册中浏览、搜索、分享 [cite:2][cite:5]。

- **仓库形态**：monorepo（`immich-app/immich`），官方目录结构如下 [cite:2]：

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `mobile/` | **移动端源码（Android + iOS）** | Flutter / Dart |
| `server/` | 服务端 REST API + 后台任务 | TypeScript · NestJS · Express |
| `web/` | Web 端 | TypeScript · SvelteKit · Tailwind |
| `machine-learning/` | 智能搜索/人脸识别服务 | Python · FastAPI · ONNX |
| `packages/sdk` | OpenAPI 自动生成的 SDK | — |
| `packages/cli` | 命令行批量上传工具 | Node.js |

- **服务端架构**：Docker Compose 编排 4 个容器——`immich-server`（API+任务）、`immich-machine-learning`（可整体禁用）、`postgres`（元数据）、`redis`（任务队列）。后台任务含缩略图生成、元数据提取、视频转码、智能搜索、人脸识别 [cite:1]。
- **官方客户端**：移动 APP（应用商店包名 `app.alextran.immich`、GitHub Releases 发 APK）、Web 端、CLI 三端共用同一套 REST API [cite:1][cite:6]。
- **版本状态**：当前大版本 v3（`IMMICH_VERSION=v3`），文档 2026-11 仍在活跃更新 [cite:6]。

---

## 二、安卓端源码情况（问题①答案）

**确认存在且完整**：

1. `mobile/` 目录包含移动端全部源码，Android 与 iOS 双端共用一份 Dart 代码 [cite:2]。
2. 官方架构文档明确：移动端用 Flutter 编写，内部架构为 Riverpod 状态管理 + Isar 本地数据库 + Pages/Widgets/Services/Repositories 分层 + OpenAPI 生成的服务端客户端 [cite:1]。
3. 发布渠道：Google Play、GitHub Releases（APK 直下）、FUTO F-Droid、Obtainium [cite:6]。

**对 Bridge 集成的含义**：

- 安卓端源码是 **Flutter/Dart**，与 Bridge 的 Kotlin/Compose 技术栈不同，**无法直接搬代码**（参照极空、监控先例，均为「视觉复刻 + 自研实现」）。
- 但 Flutter 源码是**最佳的行为参照物**：页面结构、交互细节、API 调用时序、缓存策略都能从源码里 1:1 还原（同 VNT 调研时的反编译实锤路线）。
- ⚠️ 许可证注意：`mobile/` 源码同样受 AGPLv3 约束，**只读借鉴逻辑，不复制代码与素材**（详见 §七）。

---

## 三、通讯协议（问题②答案）

### 3.1 协议总貌

- 全部通信走 **HTTP REST**，官方三客户端均由 OpenAPI 规范自动生成客户端代码；服务端用 DTO 直接映射 OpenAPI schema [cite:1]。
- 官方 API 文档站：`https://api.immich.app`（含全部端点、权限、请求/响应格式，面向第三方集成开发者）[cite:3]。
- 服务端同时提供 WebSocket 事件通道（用于资产变更等实时通知，日志可见 `WebsocketRepository`），2026-06 又新增了 Webhook 推送 [cite:7][cite:8]。**Bridge 集成可不依赖实时通道，轮询足够**（同监控快照轮询模式）。

### 3.2 鉴权体系（三种）[cite:3]

| 方式 | 请求头 / 参数 | 适用 | Bridge 取舍 |
|---|---|---|---|
| **API Key** | `x-api-key: <key>` 或 `?apiKey=<key>` | 第三方应用官方推荐方式，支持细粒度权限 | ✅ 首选候选：设置页填一次长期使用 |
| **会话 Token** | `x-immich-user-token`（移动端同款） | 账号密码 `POST /auth/login` 换取 | ✅ 备选：体验与原版 APP 登录一致（`/auth/login`、`/auth/validateToken` 均 Stable [cite:9]） |
| **共享链接** | `x-immich-share-key` / `slug` | 匿名访问指定相册/资产子集 | 备选场景 |

### 3.3 Bridge 集成所需的核心端点（状态已逐一核实）[cite:4][cite:10][cite:11]

| 端点 | 用途 | 状态 |
|---|---|---|
| `POST /auth/login` | 账号密码换 accessToken | Stable [cite:9] |
| `POST /auth/validateToken` | 校验 token 有效性 | Stable [cite:9] |
| `GET /albums` | 相册列表 | Stable [cite:10] |
| `GET /albums/{id}` | 相册详情（含 assets 数组） | Stable [cite:10] |
| `POST /search/metadata` | **资产检索主力**：按拍摄时间区间（takenAfter/takenBefore）、类型（IMAGE/VIDEO）、收藏、分页（size ≤1000）等过滤 | 存档版已验证 [cite:11]；当前站点状态页抓取为空，实施时以部署版本 OpenAPI 为准 |
| `GET /assets/{id}` | 资产详情（含 EXIF、人、堆叠） | Stable [cite:4] |
| `GET /assets/{id}/thumbnail` | **缩略图**（网格用） | Stable [cite:4] |
| `GET /assets/{id}/original` | 原图/原文件（大图查看、下载） | Stable [cite:4] |
| `GET /assets/{id}/video/playback` | 视频播放（Media3 直连） | Stable [cite:4] |
| `GET /assets/statistics` | 资产统计（总数/大小） | Stable [cite:4] |
| `POST /assets` | 上传资产 | Stable [cite:4] |
| `DELETE /assets`、`POST /assets/bulk-upload-check` | 删除、去重检查 | Stable [cite:4] |
| `GET /timeline/bucket`、`GET /timeline/buckets` | **Web 端时间线专用** | ⚠️ **Internal，官方声明外部应用不应依赖** [cite:12] |
| `GET /assets/{id}/video/stream/*`（HLS m3u8） | HLS 视频流 | Alpha，不采用 [cite:4] |

### 3.4 关键协议细节

- **缩略图鉴权**：图片走 Coil/Glide 加载时需带请求头 `x-api-key`（或用 `?apiKey=` 查询参数，但有泄漏进日志的风险， Coil 配 headers 更稳）。
- **时间线自实现**：因 timeline 端点 Internal，客户端时间线用 `POST /search/metadata`（按月/按时间窗分页拉取）后在**本地按 `localDateTime` 分组渲染**「日期头 + 网格」，效果与原版一致。
- **响应结构**：资产对象含 `id / type / localDateTime / duration / thumbhash / exifInfo(宽高/拍摄时间/GPS/相机型号) / isFavorite / isArchived / isTrashed` 等完整字段，网格占位可用 `thumbhash` 模糊预览 [cite:11]。

---

## 四、嵌入方案对比（问题③）

| 方案 | 做法 | 外观一致性 | 体验一致性 | 工程量 | 结论 |
|---|---|---|---|---|---|
| **A. 原生 Compose 复刻**（推荐） | 新建 `immich/` 包 + 主页入口，自研 Compose 页面对齐 Immich 安卓版 UI，直连 Immich REST | ★★★★★（照原版逐页复刻） | ★★★★★（原生手势/滚动/缓存） | 大（同监控量级） | ✅ 与既有集成模式完全一致 |
| B. WebView 装载 Immich Web | App 内嵌 WebView 打开 Immich 服务器的 Web 页 | ★★★（那是 Web 版外观，非安卓 APP 版） | ★★（触屏体验一般、手势受限） | 极小 | ❌ 不满足「使用也一样」 |
| C. 直接编译 Flutter 原版 APP | 用 `mobile/` 源码出独立 APK 安装 | ★★★★★ | ★★★★★ | 小 | ❌ 是独立 APP，**无法嵌入** Bridge，只能并行安装，不符合需求 |

**推荐方案 A 的既有基础**（Bridge 已沉淀的可复用件）：

- **直连模式先例**：监控功能 = 纯客户端经 VNT 直连 NVR hub REST API，服务端零改动——Immich 完全同构（手机经 VNT 直连 `http://<immich-host>:2283/api`）。
- **ZoomableImageView**（v0.2.149）：大图双指缩放/平移/双击复位，直接复用于照片查看器。
- **MonitorPlayer**（Media3 ExoPlayer）：视频播放已有完整手势经验，接 `video/playback` 即可。
- **设置页分区范式**：监控分区（服务地址/令牌/开关）照搬为「相册」分区。
- **DiagLog 日志链路**：新功能首版必须带日志（v0.2.141 教训）。

---

## 五、推荐方案落地设计（草案）

### 5.1 页面拆解（对齐 Immich 安卓版，标注精简）

| Immich 原版页面/能力 | Bridge 取舍 | 说明 |
|---|---|---|
| 主时间线（照片网格 + 日期分组 + 底部 photos/albums/search 切换） | ✅ P0 | searchAssets 分页 + 本地按日分组 + 网格 + thumbhash 占位 |
| 相册列表/相册详情 | ✅ P0 | GET /albums + /albums/{id} |
| 单张大图查看（缩放/平移/信息面板） | ✅ P0 | 复用 ZoomableImageView；EXIF 信息卡展示 |
| 视频播放 | ✅ P0 | video/playback + MonitorPlayer 改造 |
| 收藏（❤）/ 归档 | ✅ P1 | 资产 update 端点，操作轻量 |
| 回收站 | ✅ P1（可选） | isTrashed 字段已有，端点需核对部署版本 |
| **手机备份上传**（Immich 核心卖点） | ✅ 已拍板（D5，2026-11-16） | 手动单张上传 P1 + 自动备份纳入（仅 Wi-Fi / 勾选相册制 / 大文件前台服务），实现方案见 §5.4 |
| 智能搜索 / 地图 | ❌ 砍 | 依赖 ML 语义索引（大内存）与额外底图服务 |
| **人物识别（查看器详情顶部人物区 + 人物照片页）** | ✅ 升级为复刻（D8，2026-11-16） | 实测本机服务端 ML 已启用：GET /api/people 返回真实人物（师宏宇等）、assets/{id} 含 people+faces、people/{id}/thumbnail 200 jpeg、search/metadata 支持 personIds → 纯只读展示，无需端侧 AI。人物改名/合并等管理功能仍砍 |
| 合作伙伴共享 / 多用户管理 | ❌ 砍 | 多用户由服务端管理后台承担 |
| Memories / 360° / RAW / 标签 / 评论活动 / 投屏 / 内置编辑器 / 锁定文件夹 | ❌ 砍 | 低频或重实现 |

### 5.2 集成链路

```
Bridge 客户端(immich/ 包)
   │  VNT 组网内网直连
   ▼
Immich 服务器(:2283)  ← Docker Compose（server + postgres + redis [+ml 可禁用]）
```

- Bridge 服务端（FastAPI）与 Bridge 管理后台**零改动**（同监控模式）。
- 客户端新增：`immich/` 包（Prefs/ApiClient/ViewModel/Pages/Viewer/Player 约 7~9 文件，参照 `monitor/` 9 文件范式）+ 主页入口 + 设置页「相册」分区。

### 5.3 鉴权与配置（默认值草案）

- 设置项：服务器地址（如 `http://192.168.31.x:2283`）、用户名/密码（首选）或 API Key（备选）、缩略图列数。
- 首次登录 `POST /auth/login` 换 accessToken 持久化 → `POST /auth/validateToken` 启动校验 → 失效自动重登。

### 5.4 自动备份设计与能耗纪律（D5 已拍板，2026-11-16）

**原版实现真相**（mobile 源码实锤，非前台服务轮询）：

- 检测：JobScheduler 内容触发——`addContentUriTrigger` 监听 MediaStore 内/外部 图片+视频 4 个 URI（`BackgroundWorkerApiImpl.kt:61-73`），无新图时任务挂在系统进程、零唤醒零 CPU；
- 链路：MediaStore 变更 → `MediaObserver`（WorkManager Worker，跑完自我重新入队保持监听，`MediaObserver.kt`）→ 入队备份 Worker → `onAndroidUpload` 的 `_backgroundLoop`（hash → 同步 → 上传，`background_worker.service.dart:126-135`）；
- 增量：先「资产数量比对」快速路径发现新增（`local_sync.service.dart` checkAddition），异常再全量 diff；
- 去重三重保险：本地 Isar checksum 唯一索引 → 备份候选按 checksum 排除（`backup.repository.dart:82-113`）→ 服务端 `POST /assets/bulk-upload-check` 兜底；
- 上传：multipart `POST /assets`（deviceAssetId/deviceId/fileCreatedAt/fileModifiedAt/fileType/duration/文件本体），>256MB 转前台服务。

**Bridge 移植方案**（基础设施全齐：WorkManager / OkHttp / MediaStore 经验 / DiagLog 均已有）：

| 环节 | Immich 做法 | Bridge 落地 |
|---|---|---|
| 触发 | addContentUriTrigger + MediaObserver 自我入队 | 照抄：`immich/ImmichMediaObserver.kt`（Worker）监听 4 个 MediaStore URI |
| 记账 | Isar 全量本地库 | 简化：DataStore 存已同步 (id,size,modified) 集合 + 增量扫描对账，不建全库 |
| 去重 | 本地 checksum 索引 + bulk-upload-check | SHA-1 + bulk-upload-check（≤1000/批）+ 本地集合双保险 |
| 上传 | multipart POST /assets | OkHttp multipart，与手动单张上传共用 ApiClient |
| 约束 | 充电开关 + 去抖延迟 | 仅 Wi-Fi（UNMETERED，已拍板）+ 去抖 30s/300s |
| 大文件 | >256MB 前台服务 | WorkManager setForeground（已拍板：做） |
| 兜底 | 进前台触发对账 | ImmichPage 进入时 expedited 对账一次 |

**能耗纪律（核心：空闲态 ≈ 0 消耗）**：

1. ❌ 不加 15 分钟周期对账扫描（PeriodicWork 每天至少 96 次唤醒，能耗陷阱）；对账仅两个时机——内容触发 + 进相册页兜底；
2. ❌ 不连服务器长连接、不轮询（Immich 的 WebSocket 属时间线实时刷新，备份链路不需要，砍）；
3. 去抖合并：连拍 N 张只唤醒一次（setTriggerContentUpdateDelay 30s，MaxDelay 300s）；
4. 增量扫描只查 `date_added > 上次同步点`（纯 DB 读），SHA-1 只算新候选文件；
5. Wi-Fi/充电约束由系统把关，不满足时任务挂起零消耗；
6. 前台服务仅在大文件上传**进行中**短暂存在，带进度通知，传完即销毁；
7. 自动备份设置项**出厂默认关闭**，用户明确开启才生效；
8. 空闲时无常驻进程/服务，进程被系统回收不影响检测（下次触发自动拉起）。

**权限新增**：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO（API 33+）、READ_EXTERNAL_STORAGE（旧版）。

---

## 六、服务端部署要求与可行性门槛

| 项 | 要求 | 备注 |
|---|---|---|
| 运行方式 | **必须 Docker Compose** | 官方唯一推荐路径 [cite:6] |
| 硬件 | ≥ 6GB RAM、2 CPU 核 | 官方最低要求 [cite:6] |
| 存储 | `UPLOAD_LOCATION` 独立目录；数据库目录不支持网络共享 | [cite:6] |
| 端口 | 2283（Web+API 同端口） | [cite:6] |
| ML 容器 | 可整体禁用（砍掉智能搜索/人脸，省大量内存） | [cite:1] |
| 首次使用 | 首个注册用户即管理员 | [cite:6] |

**⚠️ 候选机器待核实**：部署机器需满足 Docker + 6GB RAM（候选：盒子2 / mbh / 其它）。**部署前先查通用机器台账确认各机 RAM 与 Docker 可用性**（决策项 D1）。跨网访问依赖 VNT 组网打通（手机 → Immich 服务器 IP 可达）。

---

## 七、许可证风险（AGPLv3）

- Immich 自 v1.95.0（2024-02）起采用 **AGPLv3**，且官方明确承诺持续 AGPL、不设付费墙 [cite:13][cite:14]。
- 对 Bridge 的影响：
  1. **调用 REST API 不触发传染**——API 是公开接口，Bridge 属于独立程序通过网络通信（AGPL 网络交互边界之外的常规解读）。
  2. **不得复制其代码**——`mobile/`、`web/` 的 Dart/TS 代码不能搬进 Bridge（否则 Bridge 需整体遵守 AGPL 开源义务）。
  3. **视觉复刻自研实现**（Kotlin/Compose 重写）不受代码著作权约束；**但不要直接拷贝其图标、设计素材文件**，UI 图标用 Bridge 既有图标体系或自绘。
  4. Bridge 是企业内部应用不分发，风险本已很低，按上述纪律执行即可。

---

## 八、坑清单

1. **timeline 端点 Internal**：官方明示外部应用不应依赖且可能随时变更 [cite:12] → 时间线自实现（searchAssets 分页 + 本地分组），或锁死服务器版本。
2. **API 版本演进快**：大版本 v3，端点有 Deprecated/Beta 标注 → 部署时 `IMMICH_VERSION` pin 版本，客户端按该版本的 OpenAPI 实现，升级服务器前先核对变更。
3. **缩略图加载鉴权**：Coil/Glide 必须配 `x-api-key` 请求头；用 `?apiKey=` 查询参数会泄漏进服务端日志——用 header。
4. **ML 容器内存大户**：不砍功能就别在 8GB 以下机器开 ML；砍了则智能搜索/人脸相关页面全部不可用。
5. **数据库目录不能放网络共享**：Postgres 数据必须本地盘 [cite:6]。
6. **分页上限 1000**：searchAssets `size ≤ 1000`，大库需按时间窗切片分页 [cite:11]。
7. **视频转码**：video/playback 依赖服务端转码任务；老格式/大视频首次播放可能等待转码（后台任务体系）[cite:1]。
8. **Bridge 侧常规**：新页面首版必须带 DiagLog；切 Tab 协程取消单独 rethrow；图片网格注意内存/缓存策略（LRU）。

---

## 九、决策清单（待拍板）

| 项 | 决策点 | 建议默认 |
|---|---|---|
| D1 | Immich 服务器部署在哪台机器（需先查台账 RAM/Docker） | 待核实 |
| D2 | 鉴权方式：账号密码登录换 token（原版体验）vs API Key（简单固定） | 账号密码 + token 持久化 |
| D3 | Bridge 内入口位置：主页顶部 Tab 新增「相册」vs 工具页占位激活 | 主页顶部 Tab（同监控） |
| D4 | 功能裁剪范围确认（§5.1 的 P0/P1/砍） | 按建议默认 |
| D5 | 手机照片上传/备份是否纳入 | ✅ **已拍板（2026-11-16）**：自动备份纳入 + 手动单张上传 P1。三项子决策：①仅 Wi-Fi（UNMETERED）；②勾选相册制（默认勾 Camera/Screenshots）；③>256MB 大文件做前台服务。能耗纪律见 §5.4 |
| D6 | 主题跟随：Immich 原版深浅色切换 vs 固定跟随 Bridge 主题 | 跟随 Immich 原版（可切） |
| D7 | 时间线分组单位：原版仅单选 日/月/无（默认日，`GroupAssetsBy.day` 实锤） | ✅ **已拍板（2026-11-16）**：Bridge 自定义三级精细分组——今天 → 最近一周逐天（昨天起 7 天，「M月D日 星期X」）→ 更早一律按月。相册详情/收藏等所有资产网格同套分组；右侧拖动条仍按月聚合跳转 |
| D8 | 人物识别（原版核心功能）：查看器详情顶部人物区 / 人物照片页 | ✅ **已拍板（2026-11-16）**：从「砍」升级为「只读复刻」。依据：实测部署服务端 ML 已启用且人脸数据齐全（§5.1 人物行），客户端零 AI 成本。范围：EXIF 面板人物区 + 人物照片页（personIds 过滤）+ 搜索页人物入口；人物改名/合并/隐藏管理砍 |

---

## 十、来源

- [cite:1] Immich 官方架构文档（移动端 Flutter 架构 / 服务端容器拆分 / 后台任务）：https://docs.immich.app/developer/architecture/
- [cite:2] Immich 仓库目录结构（monorepo，mobile/ 即安卓+iOS 源码）：https://docs.immich.app/developer/directories/
- [cite:3] Immich API 鉴权文档（API Key / 会话 Token / 共享链接）：https://api.immich.app/authentication
- [cite:4] Immich API 资产端点（thumbnail/original/playback/statistics/upload 均 Stable；HLS Alpha）：https://api.immich.app/endpoints/assets
- [cite:5] Immich 功能全景（时间线/相册/共享/人脸/地图/记忆等）：https://immich.app/features
- [cite:6] Immich 快速开始（Docker 部署 / 6GB RAM / 2283 端口 / 移动端发布渠道 / v3）：https://immich.app/docs/overview/quick-start
- [cite:7] Immich 监控文档（WebsocketRepository 证据）：https://docs.v3.0.3.archive.immich.app/features/monitoring/
- [cite:8] Immich 2026-06 月报（新增 Webhooks）：https://immich.app/blog/2026-june-recap
- [cite:9] Immich API 认证端点（/auth/login、/auth/validateToken Stable）：https://api.immich.app/endpoints/authentication
- [cite:10] Immich API 相册端点（全 Stable）：https://api.immich.app/endpoints/albums
- [cite:11] searchAssets 端点（POST /search/metadata 请求/响应结构，v1.130 存档）：https://v1.130.0.archive.immich.app/docs/api/search-assets/
- [cite:12] timeline 端点（Internal 警示原文）：https://api.immich.app/endpoints/timeline
- [cite:13] Immich Roadmap（v1.95.0 切换 AGPLv3，2024-02-20）：https://immich.app/roadmap
- [cite:14] FUTO 两周年博客（承诺持续 AGPL）：https://immich.app/blog/futo-two-years-later
- 另：GitHub 仓库主页抓取为空（反爬），仓库结构以官方 directories 文档为准；api.immich.app 部分页面 JS 渲染抓取为空，相关状态以抓取成功页 + 存档版交叉验证。
