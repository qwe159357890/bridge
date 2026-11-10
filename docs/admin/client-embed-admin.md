# 客户端集成管理后台 · 落地设计文档（移动端适配 + WebView 集成）

> **文档状态**：🟡 设计中（2026-11-10 建立，尚未开始执行）
> **权威定位**：本文档是「客户端集成管理后台」这一需求的唯一权威落地依据。改动按本文档执行，进度实时回填，设计经验沉淀在 §六，避免中途改思路后遗忘。
>
> **一句话目标**：让 Android 客户端能像"用浏览器打开管理后台"一样，管理**其他**客户端设备——通过内嵌 WebView 加载管理后台（admin），并针对手机小屏做移动端适配，同时保证 PC 端管理后台体验不变。

---

## 一、背景与结论（调研已定，不再反复）

### 1.1 架构事实（已核实，勿再质疑）

- **管理后台（admin）是纯前端 Vue3 SPA**（Vue3 + Vite + Element Plus + TypeScript + Pinia），只消费 `server/` 的 REST 接口 + WebSocket，**不直接连接其他客户端**。
- **"管理其他客户端"的关键在服务端可达**：客户端 WebView 打开 admin，admin 经服务端 REST 管理所有连到同一 server 的设备。
- **server 有公网地址**（用户确认），客户端 WebView 可直接访问。

### 1.2 已核实的三个关键事实（决定方案，勿再查）

| 事实 | 结论 | 影响 |
|---|---|---|
| admin `.env.production` / `.env.development` 的 `VITE_API_BASE` 均为**空** | admin 走**相对路径（同 origin）**，由谁托管就自动指向谁 | admin **零改动**即可同源部署，无需改构建配置 |
| admin router 是 `createWebHistory()`（history 模式） | 深层路由刷新会 404 | 若 server 托管需 SPA fallback；但**本方案走 nginx/独立托管，规避此坑** |
| `index.html` 已含 `viewport` meta + 引 `/qrcode.js`（绝对路径） | 静态资源用根路径 | 托管时 admin 应挂在**根路径**，而非 `/admin` 子路径 |

### 1.3 部署形态（用户拍板）

| 阶段 | 服务端 | 管理后台 |
|---|---|---|
| 现在 | 本机 `uv run main`（localhost:15160） | `npm run dev`（vite proxy → 15160） |
| 以后 | 长跑服务器，`uv run main` 不变 | **nginx 指定 dist 目录** |

**结论（已定）**：采用**形态 A**——admin 由 nginx 托管 dist（用户既定部署），客户端 WebView 加载 nginx 的 admin 地址。**不采用**"server 自己托管 admin dist"（因资源根路径 + history fallback 麻烦，且用户本就要上 nginx）。

### 1.4 移动端适配策略（用户拍板）

- **PC 端不变**：所有移动端适配一律用 `@media (max-width: 768px)` 窄屏断点覆盖，PC 断点以上样式原样不动。
- **适配层次**：先做 **层次 A（布局级适配）**——侧边栏默认收缩 + 全局 CSS 媒体查询 + 表格横向滚动；**暂不做**层次 B（逐页深度重排，性价比低）。
- **横屏接受**：用户接受横屏使用，客户端 WebView 允许横屏。
- **admin URL 可配置**：客户端设置里可填 admin URL（灵活，适配现在还在本机 dev 的阶段）。

---

## 二、改动总览（三处，各自独立可验证）

| # | 模块 | 改动 | 状态 |
|---|---|---|---|
| 1 | admin（前端） | 移动端适配：侧边栏默认收缩 + 全局 `@media` 响应式 | ✅ 完成（2026-11-10） |
| 2 | 客户端（Android） | 新增「管理后台」WebView 沉浸式页 + 可配置 URL + 明文放行 | ✅ 完成（2026-11-10） |
| 3 | 入口 | 底部导航第 4 个 Tab「管理」（销售后、设置前） | ✅ 完成（2026-11-10） |

> 执行顺序：先做 #1（admin 适配，可独立 `npm run build` 验证 PC 无回归 + 移动端生效），再做 #2、#3（客户端，可独立编译验证）。

---

## 三、改动 1：admin 移动端适配（详细设计）

### 3.1 侧边栏移动端默认收缩

**文件**：`admin/src/layout/index.vue`

**现状**：`isCollapse = ref(false)`，侧栏展开 220px、收缩 36px，`toggleCollapse()` 写 `localStorage('bridge_admin_aside_collapse')`。

**改法**：`isCollapse` 初始值改为「尊重 localStorage 记忆 > 首次按屏宽判定」：

```ts
const isCollapse = ref(initCollapse())

function initCollapse(): boolean {
  // 优先尊重用户手动记忆；无记忆时按屏幕宽度判定（移动端默认收缩）
  try {
    const saved = localStorage.getItem('bridge_admin_aside_collapse')
    if (saved != null) return saved === '1'
  } catch { /* 忽略 */ }
  return window.innerWidth < 768
}
```

> 注意：`initCollapse` 需在 `ref` 初始化时执行，且要在浏览器环境（`window` 可用）下——SPA 客户端渲染，`setup` 时 `window` 已存在，安全。若后续做 SSR 才需守卫。

### 3.2 全局响应式 CSS（移动端覆盖）

**文件**：`admin/src/styles/global.css`

**现状**：全站无 `@media` 响应式。

**改法**：文件末尾追加一段移动端媒体查询（PC 断点以上零影响）：

```css
/* ===== 移动端适配（v0.2.125，层次 A：布局级）===== */
@media (max-width: 768px) {
  /* 内容区：缩小 padding，最大化可用宽度 */
  .el-main {
    padding: 12px;
  }
  /* 表格容器：允许横向滚动，避免窄屏溢出 */
  .el-table__body-wrapper,
  .el-table__header-wrapper {
    overflow-x: auto;
  }
  /* 卡片间距收紧 */
  .el-card {
    margin-bottom: 12px;
  }
  /* 顶部 header：移动端精简（隐藏面包屑次要项，保留标题与用户区） */
  .header .el-breadcrumb {
    display: none;
  }
  /* 表单/工具栏在窄屏下弹性换行 */
  .el-form--inline .el-form-item {
    margin-right: 8px;
  }
}
```

> 说明：以上为**起点覆盖**，实际执行时若发现某个具体页面（如命令下发页的工具栏、极空页的卡片）在 768px 下仍溢出，再按需在该页面 `<style scoped>` 里追加窄屏覆盖，**并回填到本 §3.2 的清单**，保持文档同步。

### 3.3 验证方式

- `cd admin && npm run build` 通过（vue-tsc + vite build）。
- PC 端（>768px）打开确认**零回归**（侧栏/表格/header 与之前完全一致）。
- 移动端（<768px，浏览器 DevTools 手机模拟）确认：侧栏默认收缩、表格可横滑、header 精简。

---

## 四、改动 2：客户端 WebView 页（详细设计）

### 4.1 新增文件

| 文件 | 职责 |
|---|---|
| `app/.../ui/AdminWebPages.kt` | 「管理后台」沉浸式页面，`AndroidView { WebView }` |
| `app/.../jikong/../AdminPrefs.kt`（或 `data/`） | admin URL 配置持久化（可编辑） |

### 4.2 WebView 关键配置（务必齐全）

```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true          // ★ 保 localStorage 登录态，必开
    databaseEnabled = true
    loadWithOverviewMode = true
    useWideViewPort = true            // 让 viewport 生效，配合移动端适配
    builtInZoomControls = true
    displayZoomControls = false
}
webView.webViewClient = WebViewClient()
webView.webChromeClient = WebChromeClient()  // 弹窗/文件上传等
```

### 4.3 明文 HTTP 放行（server 无 HTTPS 时必需）

**现状**：Android 9+ 默认禁明文 HTTP，`ws://` 同样受限。

**改法**：`app/src/main/res/xml/network_security_config.xml` 允许 cleartext 到 server 公网域（或 `usesCleartextTraffic=true` 兜底）。执行时确认 server 是否 HTTPS：
- 若 server 是 HTTPS：无需改，`ws://` 会自动走 `wss://`。
- 若 server 是 HTTP：需放行 cleartext。

### 4.4 返回键处理

WebView 有后退栈时先 `goBack()`，无则退出页面（对齐极空页/销售页的沉浸式返回范式）。

### 4.5 验证方式

- `compileDebugKotlin` 通过。
- 真机：进入「管理后台」页 → 加载 admin URL → 登录 → 看设备列表/下发命令正常。

---

## 五、改动 3：入口（已定：底部导航第 4 个 Tab）

**用户拍板（2026-11-10）**：在底部导航栏（主页/微信/销售/设置）中，**销售之后、设置之前**，新增第 4 个底部 Tab「管理后台」。

### 5.1 涉及文件与改动点

**文件**：`app/src/main/java/my/bridge/com/ui/MainScreen.kt`

| 改动点 | 现状 | 改后 |
|---|---|---|
| `bottomTabs` 列表 | `[主页, 微信, 销售, 设置]`（4 项） | `[主页, 微信, 销售, 管理, 设置]`（5 项，销售后插「管理」） |
| `selectedBottom` 的 `when` | `0=Home, 1=Wechat, 2=Sales, 3=Settings` | `0=Home, 1=Wechat, 2=Sales, 3=AdminWebPage, 4=Settings` |
| 底部导航隐藏判断 | `selectedBottom == 2 && !salesNavVisible` 等 | 需评估 `salesNavVisible` 逻辑是否受 index 位移影响 |
| `onSelect` 的 idx 判断 | `idx != 1` / `idx != 2` 控制微信/销售沉浸复位 | 需确认 index 3（管理）是否要沉浸，4（设置）的复位逻辑 |

> ⚠️ **注意**：`selectedBottom` 的索引从 0/1/2/3 变为 0/1/2/3/4，**所有硬编码 `selectedBottom == 2`、`idx == 1`、`idx != 2` 等判断都要逐个核对**，这是本次改动最容易出错的地方，执行时逐条排查。

### 5.2 图标选择

底部 Tab 图标用 Material Icons，建议 `Icons.Filled.Dashboard`（或 `Icons.Filled.Web`）代表"管理后台"，执行时确定。

### 5.3 管理页沉浸策略

`AdminWebPage` 采用**非沉浸**（保留底部导航栏），因为它本质是"内嵌浏览器"，用户需要随时切换 Tab；返回键处理对齐 WebView 后退栈（§4.4），而非极空页的沉浸梯子。

---

## 六、设计经验沉淀（持续补充，勿删）

### 6.1 已确认的关键经验

1. **admin 天然支持同源部署**：`VITE_API_BASE` 空 = 相对路径，是"零改动集成"的根基，勿改成绝对地址。
2. **admin 静态资源用根路径**（`/qrcode.js`、`/api/*`）：托管时挂根路径，勿挂 `/admin` 子路径（否则路径错位）。
3. **history 模式**：若未来改成 server 托管，必须加 SPA fallback；nginx 托管则靠 `try_files $uri $uri/ /index.html`。
4. **WebView 必开 `domStorageEnabled`**：否则 localStorage 登录态存不住，每次进都要重新登录。
5. **移动端适配用 `@media (max-width: 768px)`**：PC 断点以上零影响，天然满足"PC 端不变"。

### 6.2 执行中新增经验（逐步补充）

6. **明文 HTTP 已放行**：`AndroidManifest.xml` 已含 `usesCleartextTraffic="true"`，改动 2 **无需**再配 `network_security_config`（§4.3 简化）。
7. **底部导航 idx 语义**：新增底部 Tab（管理=index 3）时，微信(1)/销售(2) 的 idx 判断（`idx==1`、`selectedBottom==2` 等）**不受影响**，只有设置从 3 变 4；唯一要补的是 MainScreen 返回梯子里 `selectedBottom==3` 的早退分支（否则会误走 `settingsTab==0` 判断）。
8. **WebView `domStorageEnabled` 是唯一关键项**：`databaseEnabled`（Web SQL）已弃用可移除，`javaScriptEnabled` 默认已开。
9. **`load` lambda 的 early-return 陷阱**：显式类型 `(String) -> Unit` 的 lambda 里 `return@Unit` 会编译报错（`'return' is prohibited here`），改用 `if (valid) { ... }` 包裹。

---

## 七、进度跟踪（实时更新）

| 时间 | 步骤 | 状态 | 备注 |
|---|---|---|---|
| 2026-11-10 08:37 | 建立本文档 | ✅ 完成 | 调研结论已固化 |
| 2026-11-10 08:40 | 改动 1：admin 移动端适配 | ✅ 完成 | layout/index.vue 侧栏默认收缩 + global.css @media；`npm run build` 通过 |
| 2026-11-10 08:45 | 改动 2：客户端 WebView | ✅ 完成 | AdminPrefs.kt + AdminWebPage.kt；compileDebugKotlin 通过 |
| 2026-11-10 08:50 | 改动 3：底部导航入口 | ✅ 完成 | MainScreen 加「管理」Tab（index 3）+ 返回梯子早退分支；compileDebugKotlin 通过 |
| — | 整体验证 + 打包 | ⬜ 待执行 | 真机联调（手机与 dev 机同网，访问 http://192.168.31.226:5173） |

---

## 八、待用户拍板项（未决，不阻塞文档执行）

1. **admin URL 策略**：可配置（推荐）vs 写死默认+可覆盖 —— 倾向可配置，执行时确认。
2. **server 是否 HTTPS**：决定明文放行方案（§4.3）。
