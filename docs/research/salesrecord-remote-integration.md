# SalesRecordRemote 售后管理系统集成调研

> 参考项目：`references/SalesRecordRemote/`（售后管理系统 · 远程协同版）。
> 目标：把该项目的**客户端功能**吞并集成到 Bridge，在主页与设置之间新增底部导航「销售」页。
> 两条铁律：① 不影响 Bridge 现有功能；② 严格复刻借鉴项目，不凭空想象（本文所有功能点均有源码出处）。
> 交互式功能原型：`assets/design/ui-mockup-sales-page.html`（模拟数据 + 可操作，供核验）。

---

## 实现状态（v0.2.09，已落地）

客户端功能已按本方案集成到 Bridge，参考项目目录 `references/` 全程零改动：

| 层 | 落地文件 | 说明 |
|---|---|---|
| 数据/模型/权限 | `app/.../sales/SalesModels.kt` | 15 列模型、产品类型、角色权限（admin/manager/operator）、列配置默认 |
| 配置持久化 | `app/.../sales/SalesPrefs.kt` | SharedPreferences：登录态/token/服务器地址/产品类型/列配置/记住账号 |
| REST | `app/.../sales/SalesApiClient.kt` | OkHttp 直连参考服务器，Bearer 鉴权、409 强登、智能填充、物流查询 |
| WebSocket | `app/.../sales/SalesWsClient.kt` | 心跳 30s/判死 60s/重连 3s，编辑锁、他人操作通知、强制下线 |
| 状态容器 | `app/.../sales/SalesState.kt` | 单例可变状态 + Toast 通道 |
| 业务编排 | `app/.../sales/SalesController.kt` | 进入/退出页生命周期、增删改查、排序筛选分页、编辑锁释放 |
| 界面 | `app/.../ui/SalesPages.kt` | 登录/表格/单元格编辑/行菜单/物流/表单/导出/阅读模式/权限差异 |
| 接入 | `app/.../ui/MainScreen.kt` | 底部导航 主页·销售·设置；销售页沉浸导航（进入隐藏、页内返回恢复） |

编译校验：`JAVA_HOME=... ./gradlew :app:compileDebugKotlin` 通过。**未发布 APK**（需用户核验原型/真机后走发布流程）。

---

## 1. 参考项目结构

```
SalesRecordRemote/
├── main.py                    uv 启动入口（0.0.0.0:8001，实际部署 15153）
├── server/                    FastAPI 后端
│   ├── routers/               auth / user / after_sales / ws / config / settings
│   ├── services/              after_sales / audit / auth / express(物流·百度Cookie) / price
│   └── schemas/               Pydantic 模型
├── web/                       Vue3 + Vite + Element Plus + TS + Pinia 前端
│   ├── src/views/afterSales/  售后主页(1971 行) + 新增/编辑表单(281 行)  ← 客户端核心
│   ├── src/views/login/       登录页(433 行)
│   ├── src/composables/       useWebSocket(369) / useBackButton(143) / useIsMobile
│   ├── src/stores/            auth(146) / afterSales(271)
│   ├── app.config.json        serverUrl 配置入口
│   └── manifest.json          uniapp H5Plus 打包 APK 清单（APK=web 前端打包）
└── data/                      config.toml / users.json / db(SQLite)
```

**关键认知**：APK 端就是同一份 Vue 前端经 uniapp H5Plus 打包，靠 `useIsMobile`（innerWidth<768）切换移动端布局；**没有独立的 APK 业务代码**。集成到 Bridge = 用 Kotlin/Compose 复刻 web 移动端布局的全部功能。

### 1.1 安卓打包产物补充调研（SalesRecordRemoteApp）

`references/SalesRecordRemoteApp/` 是 H5Plus/uniapp **打包产物而非源码工程**：`index.html + manifest.json + assets/*.js/css + unpackage/cache`。当前入口为 `assets/index-BTRC5EG9.js`，销售页为 `AfterSalesPage-DuMMvGmi.js` + `AfterSalesPage-DMdtoBPp.css`。它与 Vue 源码功能一致，价值是提供**实际 APK 移动适配证据**：

- 状态栏/导航栏：H5Plus 获取状态栏高度，Android 反射读取 `navigation_bar_height`，配合 `env(safe-area-inset-bottom)`；Compose 对应 WindowInsets，不照搬 Web 技术。
- 返回键：Handler Stack 逐层关闭弹窗/菜单；再处理 select/date 焦点；主页双击 `moveTaskToBack(true)` 回桌面而非杀进程。
- 阅读模式：进入锁 `landscape-primary`，退出/销毁解锁；默认竖屏，移动/阅读各一套独立列配置。
- 移动布局：≤768px 工具栏纵向铺满、筛选列 100% 宽、紧凑 6×8 padding、分页 `◀ + 5页码 + ▶`；这些与当前 HTML 原型一致。
- 存储：localStorage 与 plus.storage 双写并迁移登录态/类型/地址/列配置；Bridge 只保留 SharedPreferences 单写，但配置语义不变。
- 触屏焦点：touchend 后清除非输入控件焦点；Compose 对应点击空白收起键盘/焦点。
- **未解决项**：APK 表格仍是 `onCellDblclick/onRowContextmenu/onHeaderContextmenu`，无 touch 长按菜单实现；Bridge 的长按映射仍是必要新增适配。
- **权限清单不可照搬**：manifest 含短信、联系人、电话、MANAGE_EXTERNAL_STORAGE 等大量无关权限，且 minSdk21/target33/excludeFromRecents 与 Bridge 不同，全部不采用。

结论：**Vue 源码继续作为业务逻辑主依据，SalesRecordRemoteApp 作为移动端交互/安全区/返回键/横屏的校验依据**。它不是另一套需要再次移植的客户端。

## 2. 服务端契约（实锤，服务端零改动）

### 2.1 REST（Bearer token 鉴权，端点出自 routers/）

| 端点 | 用途 |
|---|---|
| `POST /api/auth/login` | 登录：`{username,password,force?,device_info}` → `access_token + user`（含 role/权限）。409=登录冲突→`force:true` 强登踢旧设备 |
| `POST /api/auth/logout` / `GET /api/auth/me` | 退出 / 刷新用户信息（权限变更后重拉） |
| `GET /api/after-sales` | 列表：`product_type, page, size, keyword, sort_column, sort_order, signed, date_start, date_end, has_return_express, shop_serial, column_filters(JSON)` |
| `GET /api/after-sales/{id}` / `POST /` / `PUT /{id}` / `DELETE /{id}` | 单条 / 新增 / 整条编辑 / 删除 |
| `PATCH /api/after-sales/{id}/cell` | 列级编辑：`{product_type,column_name,value}`（双击单元格/签收/取件/颜色标记全走它） |
| `GET /api/after-sales/match/sales` | 新增表单智能自动填充（按订单号/收货人/电话/发货快递/退货快递匹配销售记录） |
| `GET /api/after-sales/shops/list` | 店铺列表（筛选下拉数据源） |
| `GET /api/after-sales/search/all` | 全域搜索：当前类型无结果时搜其他类型 |
| `GET /api/after-sales/express/query` | 物流查询（百度 Cookie，返回轨迹+公司+状态） |
| `GET /api/after-sales/export/excel` | 导出 Excel（Blob；`columns/color_map/record_ids` 可选） |
| `GET/POST /api/settings/bd-cookie` | 百度 Cookie 状态/保存（物流查询依赖） |

### 2.2 WS `/ws/after-sales?token=...&device_info=...`

消息协议（useWebSocket.ts 实锤）：
- 发送：`{type:'auth',token}`（onopen 后）、`{type:'ping'}`（30s 心跳，60s 无 pong 判死重连）、`{type:'online_users'}`、`{type:'edit_lock'/'edit_unlock',product_type,record_id}`
- 接收：`pong` / `client_id` / `online_users` / `user_online` / `user_offline` / `user_device_change` / `edit_locked` / `edit_unlocked` / `edit_lock_conflict` / `edit_lock_result`（锁成功失败回执，5s 超时默认成功）/ `notification`（他人操作通知：create/update/delete/sign_change/picked_up_change/color_mark）/ `force_logout`（被踢→清 token 回登录页）/ 业务 `create/update/delete`（product_type 过滤后本地增量更新列表）
- 断线自动重连：固定 3s 间隔

### 2.3 数据模型（15 列）

`id, serial(序号), shop_serial(店铺序号), date, receiver, phone, goods, order_no, send_express, return_express, return_express_company, return_freight, signed(签收:未签收/已签收), picked_up(取件:0/1), remarks, row_color, created_at`
产品类型：`sponge(海绵) / crank(手摇器) / peach_branch(桃树枝)`。

### 2.4 权限矩阵（登录返回的 user 信息驱动前端 UI）

- 角色：admin > manager > operator；manager 及以上可增/删/改/导出，operator 只读+导出。
- operator 受三个配置限制：`visible_columns`（可见列）、`editable_columns`（可双击编辑列）、`allowed_product_types`（可访问产品类型）——未配置=全禁。
- 编辑锁强制解锁：按角色级别，高级别可解低级别锁。

## 3. 客户端功能清单（移动端 APK 视角，逐条源码实锤）

### 3.1 登录页（LoginPage.vue）
① 账号输入（历史账号下拉+单条删除，lastUsed 排序）② 密码 ③ 记住密码（存 localStorage/plus.storage）④ 自动回填最近账号密码 ⑤ 登录按钮（loading）⑥ 服务器地址入口（卡片底部链接）→ 弹窗设置（http:// 前缀校验、保存后重载、恢复默认）⑦ 登录冲突弹窗（409 →「取消/强制登录」）⑧ 回车提交。

### 3.2 售后主页（AfterSalesPage.vue）
- **产品类型切换**：海绵/手摇器/桃树枝 radio 组（按权限过滤），切换清空关键词+列搜索、恢复该类型横向滚动位置（持久化）。
- **搜索**：关键词+「全域」复选框（无结果→searchAllProductTypes→提示切换到首个有结果的类型）。
- **筛选面板**：签收状态/日期范围/退货快递有无/店铺（数据源 shops/list）+ 应用/重置。
- **表格**：15 列可配（列配置弹窗：勾选显隐+恢复默认）；列宽拖动（防抖 500ms 持久化）；移动端默认精简 4 列（收货人/货物/退货快递/取件）；签收/取件为 tag（绿/黄/灰）；电话列 REF- 前缀剥离；行颜色背景。
- **双击单元格编辑**：弹窗单字段编辑（Enter 保存/Esc 取消/Ctrl+C 复制并关闭）；先 edit_lock（冲突→提示/高级别强制解锁）、保存/关闭时 edit_unlock。
- **签收/取件切换**：双击 tag 或右键菜单 → 确认弹窗 → PATCH cell。
- **右键菜单（行）**：编辑记录/切换签收/切换取件/查询物流(快递列)/标记颜色(红黄绿蓝+清除)/删除。
- **右键菜单（表头）**：升/降序/清除排序（排序列宽 ±15px 容纳箭头）、搜索此列（表头下搜索框，Enter 触发，AND 组合）、隐藏此列；有列搜索的表头蓝点标记。
- **物流查询弹窗**：单号输入+查询（自动识别公司）、timeline 轨迹；智能提示：退货快递已签收但记录未签收→问是否更新；识别到公司而列为空→问是否填充。
- **新增/编辑表单**（AfterSalesForm.vue）：必填=日期/收货人/电话/货物；智能自动填充（5 字段 blur 时 matchSalesRecord 唯一匹配→填空字段）。
- **导出 Excel**：选择模式（勾选行→导出选中）或弹筛选（默认近一个月时间段必选）→ Blob 下载（文件名含类型名）。
- **分页**：20/50/100/200（默认 100）+「全量」模式（9999，确认提示）；移动端精简分页（◀▶+5 页码）。
- **移动端阅读模式**：分页栏「阅读」进入——隐藏导航+工具栏、横屏锁定、底部 24px 毛玻璃控制条（退出阅读/列/◀页码▶）、独立 5 列默认配置、竖屏时顶部 3 秒「建议横屏」提示。
- **移动端工具栏折叠**：导航栏上下箭头切换（持久化）。
- **在线用户**：导航栏徽标 popover（人数/角色 tag/设备列表+上线时间），WS 实时更新。
- **操作通知**：他人操作 ElNotification 3 秒（display_name 优先，notify_enabled 服务端开关）；编辑锁定冲突通知 5 秒。
- **实时同步**：WS create→unshift 列表+total+1；delete→移除；update→changes 合并（数字列转换）。
- **系统设置弹窗**：服务器地址（同登录页）+ 百度 Cookie（状态 tag+粘贴保存+获取步骤说明）。
- **返回键**（APK）：Handler Stack 模式（弹窗/菜单/筛选/选择/阅读模式逐层拦截）→ 清空后主页双击 2s 退桌面（moveTaskToBack）。
- **主题**：亮/暗切换（持久化）；连接状态 tag（已连接/未连接）。

### 3.3 管理功能（admin 专属页面，移动端经导航菜单进入）
用户管理（增删改/重置密码/强制下线/多设备开关/在线状态 15s 刷新）、操作日志（用户/类型多选包含排除/日期筛选/清理/详细模式）、系统设置（数据库路径等）。

## 4. 与 Bridge 现状对照

| 维度 | 借鉴项目 | Bridge 现状 | 集成影响 |
|---|---|---|---|
| 服务器 | 独立 FastAPI（8001/15153）+ SQLite | Bridge 服务端（15160，职责完全不同） | **两套服务器并存，客户端直连参考服务器**（照智能控制方案 A 思路，参考服务端零改动） |
| 客户端 | uniapp H5Plus 打包的 Vue 页面 | Kotlin + Compose 原生 | 全部 UI 用 Compose 复刻；网络层 OkHttp（项目已有成熟经验：ScanUploadClient/RelaySwitchClient） |
| 存储 | localStorage + plus.storage 双写 | SharedPreferences/DataStore | 登录态/列配置/记住密码等用 SharedPreferences（照 TabOrderPrefs/ScanPrefs 模式） |
| 导航 | 单页路由 + 移动端菜单 | 底部导航（主页/设置）+ 二级 Tab | 底部导航**插入第 3 个「销售」Tab**（主页与设置之间）；进入销售页即隐藏底部导航，页内提供恢复入口；销售页自成体系，不进主页 4 页面排序（TabOrder id 恒定 0..3 不动） |
| 返回键 | Handler Stack + 双击退桌面 | BackHandler 全局策略（MainScreen） | 销售页内 BackHandler 注册晚于 MainScreen → 优先接管；与现有「非首 Tab 切回首 Tab / 双击退出」策略同构 |
| 表格 | Element Plus el-table | 无表格组件 | 自研横向滚动表格（见 §6 风险） |

**不影响现有功能的保证**：新增独立包 `app/.../sales/` + `ui/SalesPages.kt`；MainScreen 仅改 4 处——bottomTabs 加一项、when 分支加一页、销售页选中时条件隐藏底部导航、BackHandler 的 landing 判断加销售页自身状态（销售页内部自管）；现有 4 个主页页面、设置页、命令/保活/上报链路零触碰。

## 5. 集成方案（推荐）

### 5.1 模块与命名
- 新包 `app/.../sales/`：`SalesApiClient`（OkHttp REST：登录/列表/单元格编辑/物流/导出）、`SalesWsClient`（WS：auth/心跳/通知/编辑锁/重连）、`SalesPrefs`（token/user_info/记住密码/列配置/服务器地址/滚动位置）、`SalesModels`（15 列记录+产品类型+权限）。
- **命名区分铁律**：`sales/` = 售后管理（本功能）；勿与 `scanner/`（快递扫码，对接 PC 端 SalesRecordSystem 57891）、`relay/`（音频中继）、`switch/`（ESP32）混淆——**这是第四套独立连接体系**（SalesRecordRemote 服务器）。
- UI：`ui/SalesPages.kt`（登录页+售后主页+各弹窗）。

### 5.2 页面结构
```
底部导航：主页 | 销售 | 设置（点击销售后隐藏）
销售页（未登录）→ 返回导航 + 登录卡（账号历史/记住密码/服务器地址/409 冲突弹窗）
销售页（已登录）→ 顶栏（返回导航·标题·连接状态·刷新·在线用户·主题·退出）
                 + 工具栏（产品类型 Tab·搜索+全域·筛选·列配置·系统设置·选择·导出·新增）
                 + 表格（横向滚动·行颜色·tag 列·双击编辑）
                 + 分页栏（阅读入口·◀ 页码 ▶）
                 + 弹窗组（单元格编辑/新增编辑表单/物流/列配置/设置/筛选/导出/颜色菜单）
```

**销售页沉浸导航（用户已拍板，Bridge 适配新增而非参考项目功能）**：点击底部「销售」即隐藏底部导航，让表格使用完整高度；登录页和销售主页都提供「返回导航」，点击只恢复底部导航，不离开销售页、不清空列表/筛选/分页状态；再次点击「销售」重新隐藏。Android 返回键优先关闭销售页弹窗/菜单，无页内浮层时再恢复底部导航，避免直接退出或丢失销售上下文。

### 5.3 桌面交互 → 触屏/Compose 映射（**重点核验项**，源码桌面交互在原 APK 触屏上同样不可用，必须映射）

| 借鉴项目（桌面） | 原型/Compose 方案 | 依据 |
|---|---|---|
| 行右键菜单 | **长按行**弹出底部菜单（编辑/签收/取件/物流/颜色/删除） | Android 惯例；借鉴项目右键菜单项集合照搬 |
| 表头右键菜单 | **长按列头**弹菜单（排序/列搜索/隐藏） | 同上 |
| 双击单元格 | 双击（Compose `detectTapGestures(onDoubleTap)`）；签收/取件双击 tag | 原项目 APK 本就依赖双击事件 |
| Ctrl+F / Ctrl+N | 搜索框聚焦按钮 / 新增按钮（移动端工具栏已有） | 移动端无键盘 |
| 弹窗 Esc 关闭 | 返回键（BackHandler 栈，逐层关闭） | 借鉴项目 useDialogBackHandler 同构 |
| 主页双击返回退桌面 | 复用 Bridge 现有「再按一次返回退出」策略 | MainScreen 既有逻辑 |
| 阅读模式横屏锁定 | `Activity.requestedOrientation(landscape)` 進入恢复 `unspecified` | 借鉴项目 lockScreenOrientation 同义 |
| 导出 Excel Blob 下载 | 下载到 Download 目录（或转发系统分享），文件名规则照抄 | 需要时再细化 |

### 5.4 网络与生命周期
- 页面级生命周期照智能控制：进入销售页 `connect()`、离开 `disconnect()`（登录后才有 WS；REST 按需）。
- 心跳 30s/判死 60s/重连 3s——参数照抄；重连用 Bridge 已验证的「常驻 watchdog + 防溢出退避」经验（借鉴项目固定 3s 更简单，照抄即可）。
- token 过期（REST 401）→ 清登录态回登录页（照抄拦截器行为）。

### 5.5 安卓原生适配落地（依据 SalesRecordRemoteApp 打包产物）

- 销售页根布局使用 `statusBarsPadding/navigationBarsPadding`；阅读模式底栏和分页栏必须计入系统导航栏 Insets，不能写死 24px 后贴住手势区。
- 阅读模式进入时保存当前 `requestedOrientation` 并锁横屏，退出及 `onDispose` 都恢复原值，防止异常路径永久横屏。
- 销售页 BackHandler 顺序：弹窗/菜单/选择模式/筛选/阅读模式 → 收起键盘与焦点 → 恢复 Bridge 底部导航；主页/设置仍走现有返回策略。
- 网络超时参考 manifest 的 30s，但 OkHttp 连接/读写超时分别配置；WS 心跳仍按 30s/60s/3s 契约。
- 不引入参考 APK 的权限、包名、minSdk/targetSdk、最近任务排除等配置；Bridge 原有保活和权限体系不动。

## 6. 风险与差异点（语言不同，防 bug 清单）

1. **表格是最大工程**：el-table 的列宽拖动/固定列/横向滚动在 Compose 需自研（建议：`horizontalScroll` + 每行 Row 按列宽渲染；列宽拖动可用列头分界线手势）。移动端默认 4 列大幅降低复杂度。
2. **双击与长按的冲突**：长按菜单 vs 双击编辑在同一单元格上需手势分层（长按 500ms 优先、双击 300ms 内两击）——Compose pointerInput 需自定义检测顺序，这是易出 bug 点。
3. **409 强制登录语义**：force=true 会踢掉其它设备（含 PC 网页端登录的同账号），弹窗文案必须保留「强制登录将使已登录的设备自动下线」。
4. **REF- 电话前缀**：显示时剥离、编辑时保留原值（照抄源码：仅展示层 `startsWith('REF-') ? slice(4)`）。
5. **编辑锁义务**：双击进入编辑必 edit_lock、弹窗任何关闭路径（保存/取消/返回键/冲突被踢）必 edit_unlock；网络断开时服务端自动释放锁（借鉴项目行为，无需客户端补偿）。
6. **WS 业务消息只处理当前产品类型**；create 是 unshift（新记录进第一页顶部）。
7. **服务器地址是用户自填**（默认空=同源，APK 场景必须填，如 `http://192.168.31.226:15153`）；地址校验 `^https?://`，WS 地址 http→ws 推导。
8. **两套数据库/服务器完全无关**：SalesRecordRemote 的售后库与 Bridge 服务端互不相干，绝不在 Bridge 服务端复刻售后接口（方案已拍板直连）。

## 7. 待用户核验的决策点（见 HTML 原型标注）

- 桌面交互映射表（§5.3）是否符合预期（尤其长按=右键）。
- admin 专属页面（用户管理/操作日志）首期是否集成（建议首期不做，仅 admin/manager/operator 的售后主页）。
- 导出 Excel 在手机端的落地形式（保存到下载目录 vs 分享面板）。
- 暗色主题是否跟随 Bridge 全局（借鉴项目有独立亮暗切换）。

## 8. 参考

- 交互式功能原型：`assets/design/ui-mockup-sales-page.html`
- 原项目 README（功能/权限/部署全解）：`references/SalesRecordRemote/README.md`
- 源码核心：`web/src/views/afterSales/AfterSalesPage.vue`、`web/src/views/login/LoginPage.vue`、`web/src/composables/useWebSocket.ts`、`server/routers/*`
- 安卓移动适配产物：`references/SalesRecordRemoteApp/index.html`、`manifest.json`、`assets/index-BTRC5EG9.js`、`assets/AfterSalesPage-DuMMvGmi.js`、`assets/AfterSalesPage-DMdtoBPp.css`
