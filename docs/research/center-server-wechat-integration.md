# center-server（微信中心服务器）前端集成调研

> 参考项目：`references/center-server/`（微信中心服务器，含 `center-server` 服务端、`agent` 微信代理、`pc-client` PyQt 桌面端、`web-frontend` Vue3 前端）。  
> 目标：把参考项目 **Web 前端** 的能力吞并进 Bridge，在底部导航 **主页 与 销售 之间**新增名为 **「微信」** 的独立页。  
> 两条铁律：① 不影响 Bridge 现有功能；② 严格按源码复刻语义，不凭空想象（本文功能点均给出源码出处）。  
> 交互式原型：`assets/design/ui-mockup-wechat-page.html`（模拟数据 + 可操作，供核验）。

---

## 实现状态

| 项            | 状态                                                                             |
| ------------ | ------------------------------------------------------------------------------ |
| 参考项目源码审阅     | ✅ 已完成（`web-frontend/src` 全量、`center-server/api` 路由契约、`agent`/`pc-client` 角色定位） |
| HTML 交互原型    | ✅ `assets/design/ui-mockup-wechat-page.html`（已按最终拍板重做：沉浸 / 页内底部 Tab / 账号移入输入栏） |
| S1 数据与连接层    | ✅ 已实现并编译通过：`wechat/` 包（Models / Prefs / ApiClient / WsClient / State / Notifier / Sound / Runtime / Controller） |
| S2 通知与提示音    | ✅ 已实现（`WechatNotifier` 独立渠道与 id 2101+ / `WechatSound` 系统通知音 / 免打扰与全局静音规则） |
| S3 UI 与导航接入   | ✅ 已实现并编译通过：`ui/WechatPages.kt`（沉浸页 + 页内底部 5 Tab + 聊天面板 + 账号 chip）+ `MainScreen` 新增「微信」Tab（主页与销售之间） |
| S4 附件下载器     | ✅ 已实现：`WechatDownloader`（Android 10+ 走 MediaStore，9- 走公共目录，失败回退私有目录+FileProvider），逻辑路径 `/sdcard/bridge/wechat/{images,videos,voice,files}` |
| S5 富消息与交互   | ✅ 已实现：图片/视频/语音/文件/表情/位置/支付卡渲染、大图查看与保存、语音下载后播放、图片·视频·文件发送（multipart）、表情面板、消息长按（复制/删除）、会话长按（置顶/免打扰/清空）、分页加载、头像加载（`/api/contacts/avatar`） |
| 工具箱 ✅ | **本期只做占位**（页内底部 Tab 入口 + 规划清单灰显）                                                     | 点击提示「规划中」                                |
| 语音发送   | **不做**：`wechat/upload_and_send_voice` 服务端为空实现（微信 DLL 不支持，直接返回成功），见 §5.8          | —                                       |
| 真机验证         | ⏳ **唯一遗留**：需要可用的中心服务器地址与 API Key；无微信/Agent 实体环境，测试由用户手动                      |
| 参考项目改动       | ⛔ 零改动（`references/` 只读）                                                        |

> 当前版本：`app/build.gradle.kts` `versionName = 0.2.20`（微信功能随该版本首次落地，未单独改版本号）。

参考项目快照：`version.txt` = `2026-10-08 20:23`；中心服务器默认端口 **15151**（`center-server/config/config.yaml` 第 132/137 行），鉴权 `api_key`（同文件第 114 行）。

---

## 0. 已拍板决策（2026-10-31 用户确认，实施以此为准）

> 状态：S1~S5 **已全部落地并编译通过**（`JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1" ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL）。
> 唯一遗留是**真机验证**（需可用的中心服务器地址 + API Key，无实体环境，由用户手动）。



| # | 决策项 | 结论 | 落地要求 |
|---|---|---|---|
| 1 | 聊天气泡配色 | **微信绿**：我方气泡 `#95EC69`，对方白色；品牌强调色 `#07C160` | 与参考项目/微信观感一致；不要套 Bridge 蓝 |
| 2 | 微信页是否沉浸 | **沉浸**（照销售页）：进入微信页即**隐藏 Bridge 底部导航** | `MainScreen` 增加 `wechatNavVisible` 状态（与 `salesNavVisible` 同构）；页内提供「⇤」显示主导航；返回键 toggle |
| 2.1 | 页内二级导航 | 微信页**自己的底部 Tab**：`⇤ · 聊天 · 通讯录 · 实例 · 工具箱 · 设置` | 沉浸时替代 Bridge 底栏显示；显示主导航时隐藏，避免双栏叠加 |
| 2.2 | 连接状态条 | **只在断线时显示**（「实时连接已断开，正在尝试重连...」+ 去设置）；正常时**零占位** | 把高度全部让给内容；不要用常驻绿条 |
| 2.3 | 顶部账号行 | **删除**原「头像+`[1] 张三`+`微信`标题+⚙」整行；⚙ 去掉（底部已有「设置」Tab） | 账号选择移到**聊天输入栏：输入框之后、发送按钮之前**（小方块显示账号序号，点击弹账号菜单） |
| 3 | **离开微信页仍后台实时收消息** | **硬需求：必须实时收到并提示通知**（用户 2026-10-31 明确「不重，必须保证」） | 连接升为**进程级常驻、常开不设关闭开关**；本功能与其它集成（销售/智能控制=页面级连接）**最大差异**，详见 §5.5 |
| 4 | 工具箱 | **暂不实现，保留占位** | 二级 Tab 保留「工具箱」入口，页内展示规划中的能力清单（灰显不可点） |
| 5 | 图片/视频下载位置 | 默认 `/sdcard/bridge/` 下新建资源目录 → `/sdcard/bridge/wechat/{images,videos,voice,files}` | Android 10+ 分区存储下用 MediaStore/公共 Download 目录实现同一逻辑路径，详见 §5.7 |
| 6 | 新消息提示 | **播放提示音 + 显示通知** | 独立通知渠道与 id（**不得复用 2001**），详见 §5.6 |

---

## 1. 参考项目结构：谁负责什么

```
center-server/
├── center-server/   FastAPI 中心服务器（MySQL + 文件存储 + WS 中转），端口 15151
│   └── api/routes/  agent / client / message / contact / wechat / clipboard / print / gps / sms ...
├── agent/           Windows 微信代理（wxhelper/wxhook，采集多开微信消息并上报）
├── pc-client/       PyQt5 桌面客户端（功能最全，含 30+ 工具）
├── web-frontend/    Vue3 + Vite + Element Plus 前端（HBuilderX 可打包成 APK）← 本次借鉴对象
└── nonebot/         NoneBot 微信适配（与本次集成无关）
```

**关键认知**：`web-frontend` 不是独立业务系统，而是 **中心服务器的一个远程控制面板**：微信消息由 `agent` 采集到中心服务器，前端/PC/手机都只是「连中心服务器的客户端」。参考前端用 HBuilderX + HTML5+ 打包成 APK（`web-frontend/README.md` 第三~七章），因此其「保活/通知/状态栏」代码全部是 JS 反射调用 Android（`src/utils/keepAlive.ts`），这与 Bridge 原生实现完全不同。

**集成定位（推荐方案 A，照销售页/智能控制经验）**：

- Bridge **直连中心服务器**（REST + WS），中心服务器 **零改动**；
- Bridge 服务端（15160）不参与、不代理、不复刻微信接口；
- 新增 **第五套独立连接体系** `wechat/`，与既有四套严格区分。

---

## 2. Web 前端真实功能（源码实锤）

路由：`src/router/index.ts`（Hash 路由，5 个页面）；移动端 `MainLayout.vue` + `LeftSidebar.vue` 在 `innerWidth < 768` 时把左侧栏变成 **60px 底部导航**，内容区 `padding-bottom: 60px`。

### 2.1 侧边栏（`components/layout/LeftSidebar.vue`）

| 区域 | 元素                                                               | 源码          |
| -- | ---------------------------------------------------------------- | ----------- |
| 顶部 | 当前微信账号头像（方形 40px + 在线指示点），下拉切换实例；未选择时头像红虚线 + 文案「选择账号」            | 第 4~46 行    |
| 中上 | 聊天（带未读角标，>9/>99 变宽）、通讯录                                          | 第 50~69 行   |
| 中下 | 工具箱（弹窗）、实例管理、设置                                                  | 第 71~92 行   |
| 交互 | 单击聊天进入列表；**双击聊天跳到下一个未读会话**（`jumpToFirstUnread`，先非免打扰后免打扰、跳过上次目标） | 第 171~368 行 |

未读角标：`sessionStore.totalUnreadCount` 只统计**非免打扰**会话未读（`stores/session.ts` 第 70~98 行）。

### 2.2 聊天（`views/chat/ChatView.vue` + `SessionList.vue` + `ChatPanel.vue`）

- 桌面端：左侧会话列表 + 右侧聊天面板；移动端（`innerWidth<768`）选中会话后面板全屏覆盖，标题栏出现返回按钮（ChatView 第 145~168 行）。
- 断线时顶部黄/蓝条提示「实时连接已断开，正在尝试重连...」+「去设置」（ChatView 第 3~10 行）。
- 会话列表（`SessionList.vue`）：**全局不按账号过滤**（服务端 `messages/sessions` 与前端 `SessionItem` 均无 account 字段）、搜索框、「好友/公众号」模式切换（长按或点击 `mode-switch`，第 11~~19、242 行）、置顶/未读/免打扰标记、右键菜单（置顶 / 标记已读 / 免打扰 / 清空消息 / 删除会话 / 查看联系人 / 会话统计 / 回到顶部，第 96~~131 行）。
  - 模式状态持久化：`session_mode` 存 localStorage（`stores/session.ts` 第 34、174~186 行）。
- 聊天面板（`ChatPanel.vue`，2895 行）：
  - 消息类型渲染：`types/index.ts` 的 `MessageType` 枚举——文本 1、图片 3、语音 34、视频 43、表情 47、位置 48、XML 49、系统 10000/10002 等；49 且 `attachment.sub_type == 'wechat_pay'` 渲染微信支付卡片（收款绿/付款橙，第 66 行）。
  - 输入区工具条：表情、文件、图片、位置、语音（第 203~231 行），输入框 placeholder「请输入消息...」，发送按钮。
  - 消息右键菜单：复制 / 下载图片 / 下载视频 / 转发 / 查看发件人 / 删除 / 回到底部（第 278~303 行）；移动端用「⋯」按钮弹菜单（第 173 行）。
  - 发送文本：`wechatApi.sendText(instanceStore.currentWxid, toWxid, content)`（第 2034 行）——**所有发送都依赖当前选中的微信账号**。

### 2.3 通讯录（`views/contacts/ContactsView.vue`）

- 搜索（防抖）+ 筛选：全部 / 会话 / 个人 / 群聊 / 公众号（第 288~~294 行），筛选态持久化 `center_contacts_filter`（第 269 行），映射参数 `has_session=true` 或 `contact_type=0/1/2`（第 324~~336 行）。
- 分页加载（limit 50）、点击查看详情、发起聊天、添加虚拟好友（`wxid` 自动加 `xn_` 前缀 + 昵称 + 所属账号 + 头像上传）、删除联系人。

### 2.4 实例管理（`views/instances/InstancesView.vue`）

- 按 **agent（代理服务器）** 分组卡片：agentId、在线 tag、实例数、连接时间；实例项显示账号序号/昵称、PID、端口；点击设为当前账号（选中蓝色底 + 绿色勾）。
- 账号序号来自 `GET /api/clients/account_mapping`，昵称兜底来自 `GET /api/wechat/self_info`（`stores/instance.ts` 第 100~145 行）。
- **全局语义**：`currentWxid` 是发送消息、拉取会话/联系人的账号上下文，选择状态持久化 `center_selected_account`。

### 2.5 设置（`views/settings/SettingsView.vue`，5 个 Tab）

| Tab  | 关键项                                                                                                                         |
| ---- | --------------------------------------------------------------------------------------------------------------------------- |
| 连接   | 服务器地址（如 `http://192.168.1.100:8080`）+ 测试连接、API Key、连接状态、**WS 参数**（重连 2000 / 心跳 15000 / Pong 60000 ms）、从最近任务隐藏、状态栏模式、状态栏文字颜色 |
| 通用   | 页面标题、消息提示音开关与音量、剪切板同步/接收/推送、接收抖一抖、全局静音、工具箱 Tab 记忆                                                                           |
| 保活   | 唤醒锁、音频保活（蜂鸣/静音、音量/频率/间隔）、通知栏保活（标题/内容）                                                                                       |
| 设备信息 | 客户端列表、上下线记录、自定义设备名、下载路径                                                                                                     |
| 关于   | 版本号、检查更新（Gitee 配置）                                                                                                          |

### 2.6 工具箱（`components/toolbox/ToolboxDialog.vue`，5 个 Tab）

文件工具（远程打印、二维码、Emoji、剪切板同步）、交互工具（抖一抖、重启客户端、重启代理、音频监听）、生活工具（万年历、条形码、世界时间、端口查询）、**编程工具 15 个**（JSON/Base64/URL/时间戳/正则/文本对比/配置转换/代码格式化/Cron/调色板/网络IP/加解密/JWT/cURL/系统信息）、**查询工具 6 个**（拍照、流式录音、流式录像、流式录屏、GPS 位置、短信查询）。

> 注意：`web端与PC客户端功能差异对比.md` 写「Web 缺少编程工具」是**旧稿**；当前 `src/components/toolbox/programming-tools/` 已实装 15 个工具，以源码为准。

---

## 3. 服务端契约（实锤，中心服务器零改动）

### 3.1 REST

- Base：`{配置的服务器地址}/api`（`src/utils/apiUrl.ts` 第 20~26 行）。
- 鉴权：`Authorization: Bearer <api_key>`（`api/http.ts` 第 16~~19 行）；`code !== 0` 视为失败并提示（`api/http.ts` 第 30~~36 行）。
- 文件/附件：`{server}/api/files/{objectName}?token={api_key}`（apiUrl.ts 第 36~45 行）。

| 分组  | 端点（相对 `/api`）                                                                                                                                                                                                                                                 | 用途                       |                                                                                                                         |                      |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ | ----------------------------------------------------------------------------------------------------------------------- | -------------------- |
| 实例  | `GET agents`、`GET agents/{id}`、`GET agents/{id}/instances`                                                                                                                                                                                                    | 代理服务器与微信实例               |                                                                                                                         |                      |
| 账号  | `GET clients/account_mapping`、`GET clients/official_account_config`、`GET clients`、`GET clients/all`、`GET clients/sessions`                                                                                                                                    | 账号序号映射、公众号配置、客户端/上下线记录   |                                                                                                                         |                      |
| 会话  | `GET messages/sessions`、`GET messages/sessions/search`、`GET messages/sessions/find`、`POST messages/sessions/create`、\`POST messages/sessions/pin                                                                                                              | mute                     | unread`、`GET messages/sessions/unread_total`、`DELETE messages/sessions/{id}/messages`、`POST messages/update_nicknames\` | 会话列表/搜索/置顶/免打扰/未读/清空 |
| 消息  | `GET messages`、`GET messages/adjacent-message`、`DELETE messages/{session_id}/{msg_id}`、`POST messages/generate_thumbnail`、`POST messages/voice_text`                                                                                                          | 消息分页、相邻消息、删消息、缩略图兜底      |                                                                                                                         |                      |
| 联系人 | `GET contacts`、`GET contacts/search`、`GET contacts/find`、`GET contacts/exists`、`POST contacts/virtual`（multipart）、`DELETE contacts`                                                                                                                           | 通讯录                      |                                                                                                                         |                      |
| 发送  | `POST wechat/send_text`、`send_image`、`send_file`、`send_video`、`send_location`、`send_room_at`、`send_pat`、`forward_msg`、`POST wechat/upload_and_send`（multipart）、`POST wechat/upload_and_send_voice`（multipart）、`POST wechat/check_login`、`POST wechat/command` | 收发消息（**均带 `wxid` 当前账号**） |                                                                                                                         |                      |
| 工具  | `GET/POST clipboard/*`、`GET print/agents`、`POST print/job`、`GET gps/*`、`sms/*`                                                                                                                                                                                | 剪切板、远程打印、GPS、短信          |                                                                                                                         |                      |

**手机端发送差异（重要）**：`send_image / send_file / send_video` 的参数是**服务器上的文件路径**（`api/wechat.ts` 第 6~~20 行），Web 实际用的是先 multipart 上传的 `upload_and_send` / `upload_and_send_voice`（第 22~~32 行）。手机端没有服务器路径，**必须走 multipart 上传接口**，不能照抄 `image_path`。

### 3.2 WebSocket

- 地址：`ws(s)://{host}/ws/client?token={api_key}`（apiUrl.ts 第 47~64 行）。
- **首帧必须是 `register`，服务端 5 秒内等待**（`center-server/api/routes/client.py` 第 606~617 行）：

```json
{ "type": "register", "device_info": { "client_type": "apk|web", "device_id": "...",
  "device_name": "...", "os_info": "...", "app_version": "1.0.0", "user_agent": "..." } }
```

- 心跳：`{ "type": "ping" }` → `{ "type": "pong" }`；默认心跳 15s / Pong 超时 60s / 重连固定 2s（不做指数退避，`utils/websocket.ts` 第 520~598 行，参数见 `stores/settings.ts`）。连续 3 次发送失败或超时即强制清理重连。
- 客户端可发：`ping`、`mark_read`、`toggle_pin`、`toggle_mute`、`clear_session`、`delete_session`、`update_device_info`。
- 服务端下发（`utils/globalMessageHandler.ts` 第 16~30 行）：`connected`（含 `client_id`）、`pong`、`message`（字段 `from_group/from_wxid/session_id`、`msg_type`、`content`、`is_self`）、`unread_cleared`、`pin_changed`、`mute_changed`、`session_cleared`、`session_deleted`、`session_update`、`contact_deleted`、`instance_change`、`play_sound`、`clipboard_sync` 等。
- **广播回执去自扰**：所有广播带 `from_client`，与本端 `client_id` 相同时忽略（`stores/session.ts` 第 283、299、311、326 行）。

### 3.3 数据模型（`src/types/index.ts`）

- `SessionItem`：`userWxid / userNickname / lastMsg / lastTime / updateTimestamp / unreadCount / isPinned / isMuted`。
- `MessageItem`：`id / sessionId / msgId / accountWxid / msgType / content / attachment / md5 / createTime / timestamp / isSelf / senderWxid / senderNickname`。
- `ContactItem`：`accountWxid / wxid / nickname / updateTime / contactType`（0 个人 / 1 群聊 / 2 公众号，另由 `utils/contactType.ts` 兜底计算）。
- `WechatInstance`：`pid / wxid / port / accountNumber / nickname`；`AgentInfo`：`agentId / instanceCount / instances / connectedAt / lastPing / isConnected`。

---

## 4. 与 Bridge 现状对照与取舍（重点）

| 维度        | 参考项目                                                                        | Bridge 现状                                   | 决策                                                                              |
| --------- | --------------------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------- |
| 服务器       | 中心服务器 15151（MySQL）                                                          | Bridge 服务端 15160，职责不同                       | **两套并存，客户端直连中心服务器**（照销售页/智能控制）                                                  |
| 客户端形态     | Vue3 + HBuilderX H5+ 打包                                                     | Kotlin + Compose 原生                         | UI 全部 Compose 复刻；网络用 OkHttp（项目已有 `libs.okhttp`）                                 |
| 存储        | localStorage                                                                | SharedPreferences / DataStore               | 连接配置、当前账号、会话模式、筛选态用 SharedPreferences（照 `SalesPrefs`）                           |
| 导航        | 侧边栏→移动端变底部 60px                                                             | 底部导航「主页·销售·设置」+ 销售沉浸隐藏                      | **底部导航改为「主页·微信·销售·设置」**；微信页**同样沉浸**（隐藏 Bridge 底栏），页内二级用**微信页自己的底部 Tab**                      |
| 返回键       | `backButton.ts` Handler 栈                                                   | `MainScreen` BackHandler + 页内优先             | 微信页内 BackHandler 注册晚于 MainScreen → 优先接管（照销售页）                                   |
| **保活**    | `keepAlive.ts`：HTML5+ 反射起前台服务通知、CPU/WiFi 锁、AudioContext 蜂鸣、隐藏最近任务、电池白名单、状态栏 | `core-keepalive` 已完善（常驻 FGS、双兜底、电池白名单、开机自启） | **整段舍弃**：不移植任何 H5+ 保活代码；Bridge 已有能力不动。仅保留「连接稳定性」语义（WS 心跳/重连参数照抄）                |
| **连接生命周期** | 前端打开即连、刷新即断                                                                    | 销售页/智能控制：**页面级**（进入连、离开释放）                  | ⚠️ **微信特殊**：用户拍板要后台实时收消息 → 升为**进程级常驻连接**，页面只做观察者（§5.5）。这是与其它集成项目最大的差异 |
| 通知/声音     | 消息提示音、全局静音、通知栏常驻                                                            | `NotificationPoster`（远程通知 id 2001）+ `KeepAliveService` 常驻通知 | **做**：新消息 → 提示音 + 通知；**必须用独立渠道与独立 id**（2001 已与保活通知冲突，见 §5.6）      |
| 桌面角标      | `badge.ts` 更新 App 角标                                                        | 一加/OPPO 无真角标（已拍板接受）                         | 角标只做 **Bridge 底部导航「微信」Tab 红点/数字**，不承诺系统桌面角标                                     |
| 状态栏/最近任务  | 页面内可切沉浸、隐藏最近任务                                                              | Bridge 全局设置已有                               | 不在微信页提供重复开关；如需，跳转 Bridge 设置                                                     |
| 下载路径      | `/sdcard/Pictures/wxhelper`                                                 | Bridge 用自己的媒体/下载目录与清理策略                     | 不照抄；**`/sdcard/bridge/wechat/{images,videos,voice,files}`**（§5.7）                    |
| 工具箱 ✅ | **本期只做占位**（页内底部 Tab 入口 + 规划清单灰显）                                                     | 点击提示「规划中」                                |
| 语音发送   | **不做**：`wechat/upload_and_send_voice` 服务端为空实现（微信 DLL 不支持，直接返回成功），见 §5.8          | —                                       |
| 权限/minSdk | manifest 声明一批权限、minSdk21                                                    | Bridge 独立权限体系                               | 不引入参考项目任何权限与打包配置                                                                |



---

## 5. 推荐集成方案

### 5.1 模块与命名（第五套连接体系）

```
app/src/main/java/my/bridge/com/
├── wechat/
│   ├── WechatModels.kt       会话/消息/联系人/实例模型 + MessageType 枚举 + 连接状态
│   ├── WechatPrefs.kt        服务器地址/API Key/当前账号/会话模式/筛选态/后台接收/通知/提示音（SharedPreferences）
│   ├── WechatApiClient.kt    OkHttp REST：agents/messages/contacts/wechat send/upload
│   ├── WechatWsClient.kt     OkHttp WS：register/ping/mark_read/toggle_*（纯连接，不感知 UI）
│   ├── WechatRuntime.kt      ★进程级单例：后台常驻连接、重连、事件总线、未读聚合（不依赖任何页面）
│   ├── WechatNotifier.kt     ★新消息通知（独立 channel + 独立 id）+ 会话分组 + 点击深链
│   ├── WechatSound.kt        ★提示音播放（前台页内/后台走通知渠道声音；受全局静音与会话免打扰约束）
│   ├── WechatDownloader.kt   ★附件保存到 /sdcard/bridge/wechat/{images,videos,voice,files}
│   ├── WechatState.kt        不可变快照 + 事件通道（照 SalesState/RelaySwitchState）
│   └── WechatController.kt  页面侧：订阅 Runtime、账号切换、发送、分页（不再持有连接）
└── ui/WechatPages.kt         连接设置 / 聊天 / 通讯录 / 实例 / 工具箱占位 / 页内设置
```

**命名铁律**：`wechat/` = 中心服务器微信（本功能）；`sales/` 售后、`switch/` ESP32、`relay/` 音频中继、`scanner/` 扫码互不复用、互不混淆。

★ = 本次因「后台实时收消息」拍板而新增，其它集成项目没有的部件。

### 5.2 导航与页面结构（沉浸 + 页内底部二级 Tab）

```
Bridge 底部导航：主页 | 微信 | 销售 | 设置        （微信=索引 1，销售顺延为 2，设置 3）

微信页（沉浸态，默认）──────────────────────────────
  ┌ 顶部状态条：仅在断线时出现（正常时零占位）──────┐
  │ 内容区（铺满剩余高度）                        │
  │   聊天 / 通讯录 / 实例 / 工具箱 / 设置         │
  └ 页内底部 Tab：⇤ | 聊天 | 通讯录 | 实例 | 工具箱 | 设置 ┘
     ⇤ = 显示 Bridge 主导航（照销售页「返回导航」语义；显示主导航时本行隐藏）

微信页（未配置服务器）→ 连接设置卡：服务器地址 / API Key / 测试连接 / 保存
聊天面板 → 全屏覆盖（含页内底部 Tab，与微信原生一致）
        → 输入栏：☺ ＋ [输入框] [账号▾] [发送]   ← 账号选择固定在输入框后、发送按钮前
```

- **未读红点位置**：沉浸态显示在页内「聊天」Tab 上；显示 Bridge 主导航时显示在底部「微信」Tab 上（两处同一数据源，不同呈现）。
- 未配置/未选择账号时：输入框禁用并提示「请先选择微信账号」，账号小方块显示红虚线「选账号」（照参考 `no-instance` 红虚线语义）。
- **生命周期（与其它集成不同）**：连接由 `WechatRuntime` 在进程级维护；微信页进入只**订阅**状态、退出只**取消订阅**，绝不关闭连接（详见 §5.5）。
### 5.3 桌面交互 → 触屏映射

| 参考（桌面/混合）     | Bridge（Compose）                          |
| ------------- | ---------------------------------------- |
| 会话右键菜单        | **长按会话**弹底部菜单（置顶/标记已读/免打扰/清空/删除/查看联系人）   |
| 消息右键菜单        | **长按消息气泡**弹菜单（复制/下载/转发/删除/回到底部）          |
| 双击聊天图标跳未读     | 聊天 Tab **双击**跳下一个未读（可复用，手势分层同销售页经验）      |
| 模式切换（好友/公众号）  | 会话列表顶部 Segment（点击切换，长按亦可，避免与双击冲突）        |
| 移动端返回按钮       | 微信页内 BackHandler：聊天详情 → 会话列表；再按 → toggle 主导航（照销售页） |
| 侧边栏顶部账号切换      | **聊天输入栏内**的小方块（输入框后、发送按钮前），点击弹账号菜单；顶部不再有账号行 |
| 侧边栏「设置」齿轮      | 删除，改由页内底部 Tab「设置」承载                          |
| H5+ 保活/通知/状态栏 | 不移植（见 §4）                                |

### 5.4 网络与状态规则（照抄语义，不照抄实现）

- WS 参数默认：心跳 15s / Pong 超时 60s / 重连固定 2s；`register` 必须在连上后 5 秒内发出；`client_type` 建议填 `bridge`（服务端仅作字符串记录，未做白名单校验，`client.py` 第 95 行）。
- 心跳与重连参数默认不给用户改（Bridge 既有约定：心跳/重连两端一致），设置页只做只读展示；服务器地址 + API Key 用户自填。
- 未读总数只累计**非免打扰**会话；进入会话立即 `mark_read` 并本地清零；收到广播事件先比对 `client_id` 去自扰。
- 附件 URL 必须拼 `?token={api_key}`；图片/视频/语音下载走 `/api/files/{object}`。

---

### 5.5 后台实时收消息（本功能最大差异点）

**结论：新增进程级常驻连接，但不新增前台服务。**

| 方案 | 做法 | 评价 |
|---|---|---|
| A（推荐） | `WechatRuntime` 进程级单例，随 `BridgeApplication.onCreate`（或首次配置完成后）启动，受「后台实时接收」开关控制；进程存活复用既有 `KeepAliveService` 常驻前台服务 + 电池白名单 + 开机自启 + 双兜底 | ✅ **不新增 FGS**，不触碰 `docs/dev/crash-ledger.md` §5 的高危面；与现有保活体系零冲突 |
| B | 新建 `WechatPushService` 前台服务专门维持 WS | ❌ 必须逐条过 crash-ledger §5 清单（manifest type/权限、每次拉起履行前台义务、早退分支、兜底保持「处理中」≥4s、登记 §3.1 表）；收益与 A 相同，风险显著更高 |

**A 方案要点**：

1. **进程在，连接就在**：`BridgeApplication.onCreate` 已无条件 `KeepAliveService.start(this)`（`BridgeApplication.kt` 第 59 行），进程存活由既有保活负责；WS 断线按参考项目**固定 2s** 重连，不做指数退避。
2. **页面只是观察者**：`WechatController` 订阅 `WechatRuntime` 状态流；离开微信页、切到主页/销售/设置、甚至 App 退到后台，连接与未读聚合持续工作。
3. **常开，不提供关闭开关**：实时性是硬需求，故**不做**「后台接收」开关（原 `KEY_BACKGROUND_RECEIVE` 取消）。可关的只有**提醒方式**（通知 / 提示音 / 静音），收消息本身不可关——关提醒≠断连接。省电通过连接参数（心跳/Pong）调优，而不是靠断连。
4. **冷启动 / 进程被杀恢复**：进程重启后 Runtime 自动重连；未读数以 `GET messages/sessions/unread_total` 校准一次，避免本地计数漂移。
5. **心跳耗电评估**：15s 心跳属高频唤醒，需与现有保活/周期上报共存评估耗电。若要放宽到 30s，**服务端 Pong 超时必须同步调整**（两端一致，项目既有约定），否则会被判死。
6. **会话是全局的，不按账号分**：`GET messages/sessions` 返回字段**没有 account_wxid**（`core/database.py` 第 155~177 行 SQL 只查 sessions 表），参考前端 `SessionItem` 同样没有账号字段 → **会话列表=全部账号混在一起，不按当前账号过滤**（早期原型按账号过滤属我方臆造，已修正）。账号上下文只用于：① 发送（参数 `wxid`）；② 拉消息/联系人时可带 `account_wxid` 过滤。未读总数直接取 `GET messages/sessions/unread_total`，无需本地按账号分组。
7. **网络恢复立即重连**：注册 `ConnectivityManager.NetworkCallback`，网络从断到通**立刻**重连，不等心跳/Pong 超时（否则最长要等 60s 才感知断线）。同时保留 OkHttp `pingInterval(20s)` 传输层兜底，尽早发现半死连接。
8. **实时性保障矩阵（缺一不可）**：

| 环节 | 机制 | 说明 |
|---|---|---|
| 连接载体 | `WechatRuntime` 进程级单例 | 不随页面销毁；App 启动即连 |
| 进程存活 | `KeepAliveService` 常驻 FGS + 电池白名单 + 开机自启 + Job/Alarm 双兜底 | 已有能力，直接复用 |
| 断线感知 | 心跳 15s / Pong 超时 60s + OkHttp ping 20s + 网络回调 | 三重，最快可在网络恢复瞬间重连 |
| 重连 | 固定 2s（照参考项目，不做指数退避） | 保证恢复速度 |
| 注册 | `onOpen` 后 5s 内发 `register` | 漏发则收不到广播 |
| 提醒 | 独立通知渠道 + 提示音（可关提醒，不断连接） | 免打扰会话不提醒但仍入未读 |
| 计数校准 | 每次重连成功后拉一次 `unread_total` | 防止断线期间漏计 |

9. **实时性的既定边界**（不是 bug）：进程被用户强制停止、被系统回收、设备断网/关机期间必然收不到，只能等进程/网络恢复后补收（消息存在中心服务器 MySQL，重连后可拉取补齐）。不承诺「离线也收」——那需要中心服务器侧推送通道（FCM/厂商推送），本次不做。
7. **去自扰照旧**：广播 `from_client == 本端 client_id` 必须忽略（`stores/session.ts` 第 283 行语义），否则本端已读会被自己的广播回弹成未读。

### 5.6 新消息通知与提示音

- **通知渠道**：新建 `wechat_message_sound` / `wechat_message_silent`（声音属性创建后不可变，按是否需要提示音分两个渠道，照 `NotificationPoster` 既有做法），`IMPORTANCE_DEFAULT` + `setShowBadge(true)`。
- **通知 id**：⚠️ **绝对不能用 2001**——`NotificationPoster.NOTIFICATION_ID = 2001` 与 `KeepAliveService.NOTIFICATION_ID = 2001` 相同，微信消息复用会顶掉保活常驻通知（且 `setAutoCancel(true)` 会让用户把前台服务通知划掉）。微信消息通知 **2101 起**（按会话分 id 或固定 id + `MessagingStyle`）。
- **合并与免打扰**：同一会话连续消息用同一 id 更新，不刷屏；免打扰会话**不弹通知、不提示音，但仍计本地未读**（与参考前端一致）；「全局静音」只关声音不关通知。
- **提示音**：App 在前台且正打开该会话 → 只走页内轻提示；否则由通知渠道发声。参考项目有「提示音开关 + 音量 + 蜂鸣」，Bridge 首期只做**开关**，音量跟随系统，不自造音量条。
- **点击跳转**：PendingIntent 带 deep link（打开微信 Tab + 指定会话），点击后自动 `mark_read` 并清掉该会话通知。

### 5.7 附件下载目录

- 逻辑路径（用户拍板）：`/sdcard/bridge/wechat/`，下分 `images/`（3 图片、47 表情存图）、`videos/`（43）、`voice/`（34）、`files/`（其它附件）。
- 实现约束：Android 10+ 分区存储不能直接写 `/sdcard` 裸路径 → 用 MediaStore 插入 `Pictures/Bridge/WeChat`、`Movies/Bridge/WeChat`、`Music/Bridge/WeChat`，`files/` 走公共 `Download/Bridge/WeChat` 或 SAF。**对外展示仍写作 `/sdcard/bridge/wechat/...`**，与用户认知一致。
- 清理策略：纳入现有媒体清理（`config.yaml media.*`，默认 100 条/7 天），但**用户主动下载的附件默认不自动清理**（独立标记或独立目录），避免刚存的图被自动删。

### 5.8 附件（attachment）字段契约与富媒体实现

**字段名全部源码实锤，未臆造**（`agent/core/agent_client.py` 写入 + `web-frontend` 读取）：

| 类型 | msgType | attachment 字段 | Bridge 渲染 |
| --- | --- | --- | --- |
| 图片 | 3 | `thumb_path`（列表/缩略图）、`image_path`（原图） | 气泡内缩略图；点击 → 大图查看器（原图）→ 保存 |
| 视频 | 43 | `movie_cover`（封面）、`movie_path` | 封面 + 播放角标；点击 → 保存到 `videos/` 后 `ACTION_VIEW` |
| 语音 | 34 | `voice_path`、`duration` | 气泡显示时长；点击 → **先下载到缓存**再 MediaPlayer 播放 |
| 文件 | 其它 | `path`、`file_name` | 文件名 + 图标；点击 → 保存到 `files/` 后打开 |
| 表情 | 47 | `emoji_object` | 图片气泡 |
| 位置 | 48 | `map_object`、`poiname`、`label`、`coordinate.{lng,lat}` | 静态地图 + 名称 + 经纬度 |
| 微信支付 | 49 | `sub_type=wechat_pay`、`direction`、`header_title`、`title`、`lines` | 支付卡片（收/付款异色） |
| 系统 | 10000/10002 | — | 居中灰字 |

文件 URL：`{server}/api/files/{object}?token={api_key}`（`utils/apiUrl.ts` 第 36 行）；
头像：`GET {server}/api/contacts/avatar?wxid=`，**Bearer 头鉴权**（`utils/avatarManager.ts` 第 84 行）。

实现要点：

- **不引入 Coil/Glide**：项目无该依赖，且附件要带 token / Bearer；`WechatImageLoader` 用 OkHttp 下载 + `LruCache` 内存缓存 + `inSampleSize` 采样解码。
- **语音播放先落盘**：参考前端是 `new Audio(url)` 直接播；Android MediaPlayer 播网络流不稳，改为下载到 `cacheDir/wechat/` 再本地播，播放完立刻 `release()`（避免与既有蓝牙媒体按键焦点补获逻辑打架）。
- **语音发送不做**：`wechat/upload_and_send_voice` 在服务端是**空实现**——注释原文「微信DLL不支持发送语音，代理服务器收到请求后直接返回成功」（`center-server/api/routes/wechat.py`）。故输入栏不提供录音发送入口，避免做了个假功能。
- **视频/文件打开**：保存后 `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`（Android 10+ 走 MediaStore Uri；9- 走 FileProvider）。

### 5.9 已实现的页面交互清单

| 位置 | 交互 |
| --- | --- |
| 会话列表 | 头像（远程 + 首字母兜底）、搜索、好友/公众号切换、未读（免打扰置灰）、**长按 → 置顶 / 免打扰 / 清空聊天记录** |
| 聊天面板 | 富消息渲染、系统消息居中、**加载更早的消息**（offset 分页）、本地回显已发消息、表情面板、☺ / ＋（图片·视频·文件） |
| 消息 | **长按 → 复制文本 / 删除消息**（`DELETE /api/messages/{session_id}/{msg_id}`，服务端会广播） |
| 会话菜单 | 置顶 / 免打扰 / 清空（本地 + WS 广播 + REST `DELETE /api/messages/sessions/{id}/messages`） |
| 账号 | 输入栏内「账号 chip」→ 弹窗切换；未选账号禁用输入（照参考 `no-instance` 语义） |
| 设置 | 修改服务器（先测后存）、重新连接、通知/提示音/全局静音开关、**通知权限检测与申请**（Android 13+ `POST_NOTIFICATIONS`）、清空缓存、附件路径说明 |
| 通知跳转 | 点击通知 → `MainActivity.handleWechatDeepLink()`（`onCreate` + **`onNewIntent`**）→ `WechatState.pendingOpenSession` → `MainScreen` 切到微信 Tab 并打开会话后清空；**必须走状态而不是直接读 `activity.intent`**（App 已在前台时 intent 不刷新、Compose 也不会重组） |

## 6. 风险与差异清单（防 bug）

1. **账号上下文**：`currentWxid` 缺失时，会话/联系人/发送全部不可用；实例离线或 wxid 变化要清空当前选择（`stores/instance.ts` 第 178~195 行语义）。
2. **手机端发送只能走上传接口**：不要照抄 `send_image` 的服务器路径参数（详见 §3.1）。
3. **消息类型分支多**：49 XML（支付/红包/公众号）、10002 系统通知、47 表情、34 语音、43 视频、48 位置；首期至少保证「未知类型降级为文本 + `[类型]` 占位」，不能白屏。
4. **免打扰语义**：免打扰会话仍可能有新消息（`_hasNewMessage`），但不计入未读总数；跳转未读时最后才考虑。
5. **WS 首帧 5 秒**：OkHttp `onOpen` 后立即发 `register`，否则服务端只记录空设备信息。
6. **两套服务器无关**：绝不在 Bridge 服务端复刻微信/消息接口；也不把中心服务器 api_key 写入 Bridge 服务端。
7. **Compose 布局（沉浸 + 页内底部 Tab）**：微信页结构 = `Column{ 状态条(断线才显示) / 内容 Box weight(1f) / 页内底部 Tab height(54dp) }`；固定高度兄弟节点必须用 `height()` 不用 `heightIn(min)`（项目既有血泪教训）。沉浸时微信页**自己**占满，Bridge `BridgeBottomBar` 在 `selectedBottom==1(微信)` 且 `!wechatNavVisible` 时隐藏——与销售页同构，注意 `navigationBarsPadding()` 只加在最外层，页内 Tab 不要再叠加一次，否则底部会出现双倍留白。
8. **账号小方块不要挤爆输入栏**：输入栏为 `Row{ ☺ / ＋ / 输入框 weight(1f) / 账号方块 / 发送 }`，输入框必须 `weight(1f) + minWidth(0)`，账号方块与发送按钮固定尺寸，否则长文本会把发送按钮顶出屏幕。
8. **参考项目语言差异**：前端是 Vue/TS、PC 端是 PyQt/Python、Agent 是 Python hook；Bridge 只借鉴**业务语义与契约**，实现全部 Kotlin/Compose，不引入任何 JS/Python 运行时。
8.1 **别给会话列表加账号过滤**：服务端会话表没有账号维度，按「当前账号」过滤会漏掉其它账号的会话（原型早期版本犯过，已修正）。
8.2 **语音发送是假功能**：`wechat/upload_and_send_voice` 服务端直接返回成功但不实际发送（微信 DLL 不支持）。不要照前端加录音发送入口。
8.3 **图片加载不要引 Coil/Glide**：项目无该依赖，附件需带 token，已用自实现 `WechatImageLoader`；新增图片库会带来版本与缓存策略的额外风险。
9. **通知 id 冲突（本次排摸发现）**：`NotificationPoster.NOTIFICATION_ID = 2001` 与 `KeepAliveService.NOTIFICATION_ID = 2001` **相同**。微信消息通知必须另起 id（2101+）与独立渠道，否则会顶掉保活前台通知（且 autoCancel 会让用户把前台服务通知划掉）。现有远程通知复用 2001 的问题另行评估，本次不擅自改动。
10. **后台常驻连接的边界**：进程被用户强制停止/系统回收后连接必然断开，只能等下次进程拉起后重连——**这不是 bug，是方案 A 的既定边界**；不要为了「绝对不断线」去新增前台服务（见 §5.5）。心跳 15s 的耗电需实测，与现有周期上报叠加评估。
11. **保活开关与后台接收的关系**：若用户在 Bridge 设置里关掉保活，进程可能被回收，微信后台接收随之失效。设置页需明示依赖，不做隐式承诺。

---

## 7. 分期与完成情况

| 阶段     | 内容                                                                                      | 验收                                       |
| ------ | --------------------------------------------------------------------------------------- | ---------------------------------------- |
| S1 ✅ | `WechatPrefs` 连接设置（地址/Key/测试连接）+ `WechatRuntime` 进程级 WS 常驻（常开）+ 网络恢复重连 + 实例列表与账号切换 + 连接状态条     | 切到主页/销售/设置仍能收到消息；断线可见并 2s 重连；切换账号持久化    |
| S2 ✅ | `WechatNotifier`（独立渠道/id）+ `WechatSound` 提示音 + 未读角标（Tab 红点）+ 免打扰/全局静音规则           | 后台收到消息有通知+提示音；免打扰不计未读；点击通知跳到对应会话并已读  |
| S3 ✅ | 会话列表 + 聊天面板（文本/表情/图片上传/系统消息降级）+ 已读同步 + `WechatDownloader` 保存到 `/sdcard/bridge/wechat/` | 能收发文本与图片；图片可保存到指定目录；跨端已读同步              |
| S4 ✅ | 通讯录（筛选/搜索/发起聊天）+ 置顶/免打扰/长按菜单 + 返回键栈 + **工具箱占位页**                                  | 与原型一致；不影响主页/销售/设置                        |
| S5 ✅  | 富媒体：图片/视频/语音/文件渲染与保存、大图查看器、语音播放、图片·视频·文件发送、表情面板、消息与会话长按菜单、分页、头像        | 已完成，编译通过；真机待验                                   |
| 工具箱 ✅ | **本期只做占位**（页内底部 Tab 入口 + 规划清单灰显）                                                     | 点击提示「规划中」                                |
| 语音发送   | **不做**：`wechat/upload_and_send_voice` 服务端为空实现（微信 DLL 不支持，直接返回成功），见 §5.8          | —                                       |

每阶段编译：`JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1" ./gradlew :app:compileDebugKotlin`。

---

## 8. 决策记录

### 8.1 已拍板（2026-10-31，见 §0）

气泡微信绿 · **微信页沉浸** · 二级导航=**页内底部 Tab** · 状态条仅断线显示 · 顶部账号行删除（账号选择移入输入栏） · 后台常驻收消息 · 工具箱占位 · 下载到 `/sdcard/bridge/wechat/` · 提示音+通知。

### 8.2 实施前仍需确认的小项（不阻塞 S1）

1. 底部「微信」Tab **图标**：沿用原型聊天气泡描边图标，选中态用微信绿 `#07C160`？
2. 页内底部 Tab 的「⇤」是否保留（原型方案：点击显示 Bridge 主导航；也可改为仅用返回键 toggle，把宽度全给 5 个 Tab）。
3. ~~后台接收开关默认开还是关~~ → **已定为常开不设开关**（实时性硬需求）；仅提醒方式（通知/提示音）可关，需确认默认值是否全开（建议：通知开、提示音开）。
4. 心跳 15s 是否维持（照抄参考项目）还是放宽到 30s（放宽需服务端同步调整 Pong 超时）。
5. 提示音素材：用系统通知音，还是内置一段短音效（需新增 raw 资源）。
6. 用户主动下载的附件是否纳入自动清理（建议默认不清理）。

---

## 9. 参考

- 交互式原型：`assets/design/ui-mockup-wechat-page.html`
- 前端源码：`references/center-server/web-frontend/src/`（`router/index.ts`、`views/*`、`components/chat/*`、`components/layout/LeftSidebar.vue`、`components/toolbox/*`、`stores/*`、`utils/{websocket,apiUrl,keepAlive,globalMessageHandler}.ts`、`api/*`）
- 服务端契约：`references/center-server/center-server/api/routes/{client,message,contact,wechat,clipboard,print}.py`、`config/config.yaml`
- 差异旧稿（部分已过时）：`references/center-server/web-frontend/web端与PC客户端功能差异对比.md`
- Bridge 现有集成范式：`docs/research/salesrecord-remote-integration.md`、`docs/research/esp32-relay-integration.md`、`app/.../sales/SalesController.kt`、`app/.../switch/RelaySwitchClient.kt`
