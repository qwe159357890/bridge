# 数据采集类命令实现

数据采集类（`data`）命令的实现语义。参数 schema 的唯一权威是
`server/app/core/command_catalog.py`，本文只写 catalog 说不清的机制、策略与边界。

## get_location 获取位置

让客户端回报当前位置。**真实实现**（非模拟）：Android 原生 `LocationManager`，
无 GMS 依赖，国产 ROM 可用（网络定位由厂商 NLP 服务如高德实现）。

### 定位策略（LocationHelper）

```
coarse 粗略（默认）：
  （refresh=off）lastKnown 缓存（network/passive，5 分钟内视为新鲜）
    → 命中直接返回「network(缓存)」
  （refresh=on 强制刷新）跳过新鲜缓存短路，直接进入下一级监听
  → 监听一次 network 定位（timeout 内）→ 返回「network」
  → 超时：回退陈旧 lastKnown（标注「缓存 N 分钟前」）→ 都没有则回执失败

fine 精确：
  监听一次 GPS 定位（timeout 内）→ 返回「gps」
  → 超时：自动回退 coarse 结果（遵循同一 refresh 开关），来源标注「GPS超时回退」
```

- 冷启动 GPS 搜星可能 30 秒+，精确模式建议 timeout=30；timeout 参数下限 5 上限 120 秒。
- `refresh` 强制刷新开关：解决「设备静止时缓存一直新鲜、coarse 永远拿不到实时位置」的
  滞后问题；只跳过新鲜缓存短路，**陈旧缓存兜底保留**（兜底语义=宁可旧也不报错，
  与新鲜度要求是两回事）。开启后成功回执追加「（强制刷新，已跳过缓存）」。
- 回执文本示例：`位置：30.123456, 120.654321（±35m，来源 gps，2026-10-25 12:05:30，海拔 12.0m，速度 1.2m/s）`

### 权限

- Manifest 已声明 `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` /
  `ACCESS_BACKGROUND_LOCATION`（保活资源锚点一并声明）。
- 均为**运行时权限**：执行时校验，未授权回执失败
  「缺少定位权限，请打开 App 在系统设置中授予定位权限后重试」（一次性人工授权）。
- fine 模式需 FINE 权限；仅有 COARSE 时**自动降级**为网络定位并在回执注明。

### 持久化（MySQL，已实现）

get_location 成功后客户端在回执之外 fire-and-forget 上报
`POST /api/locations`（设备头鉴权；失败仅记日志，**不影响回执**）。
服务端 MySQL 持久化（`core/location_store.py`）：

- **库/表自动创建**（`locations` 表，启动时预连，连不上服务照常起、端点报 503）。
- **静止合并**：与该设备最新一行算 haversine 距离，`< max(两次精度, 30米)`
  （误差圈重叠=无法确认移动）则只刷新该行坐标/时间并 `report_count+1`，
  不新建行——设备静止时不会堆积重复记录；真实移动必然超出误差圈，不会误合并。
- **双坐标系**：WGS-84（原始）与 GCJ-02（火星坐标，高德/腾讯底图渲染用）
  同存；转换仅中国境内生效。`first_fix_time` / `last_fix_time` / `report_count`
  直接表达「在该位置停留了多久」。
- **无自动保留策略**：删不删全由管理员决定（设备详情页「位置」弹窗删除，
  或 `DELETE /api/locations`，须带 id / device_id / before_ms 之一）。
- **配置**：`config.yaml` 有 `locations.mysql` / `locations.amap` 模板段，
  真实密码/Key 放 `config.local.yaml`（gitignore，不入库）。
- **管理后台**：设备管理页每行「位置」按钮 → 弹窗（自适应宽度 + 右下角
  手柄拖拽调宽；时间两列合并避免横向滚动条）上半高德地图轨迹
  （Key 由 `/api/locations/amap-config` 动态下发，不进前端代码）+
  下半位置历史列表。
- **命令页实时定位结果**：下发 get_location 回执成功后，命令下发页自动
  出现「定位结果」卡片——短轮询 `GET /api/locations/latest` 等本次入库
  （按 server_time 与下发时刻匹配，容忍 1 分钟时钟偏差，最多等 ~8 秒），
  地图展示「标记 + 误差圈 + 信息窗」，信息窗含逆地理编码地址
  （走服务端 `GET /api/locations/address`：Web服务 REST + WGS-84→GCJ-02
  自动转换 + 24h 坐标网格缓存；2021-12 后的新 JS API Key 平台受限，
  浏览器 Geocoder 插件不可控，故解析统一收口服务端）。
  上报与回执解耦，等不到入库则明示超时提示。

## get_contacts 获取通讯录

读取设备联系人并上报服务端（v0.1.25）。借鉴 `references/pc-client` 与
`references/autoxClient`（contacts.js 的两级 Map 查询防 N+1）。

- **参数**：`only_with_phones`（仅含有号码的联系人，默认开）、
  `limit`（数量上限，默认 2000，上限 5000）。
- **链路**：命令 → 客户端 `ContactsReader`（READ_CONTACTS 权限，3 次批量查询：
  联系人主表 + 全部电话 + 全部邮箱，按 contact_id 分组合并）→
  `POST /api/contacts` → MySQL `contacts` 表。
- **存储语义**：按设备**覆盖快照**——每次上报单事务先删后插，以最新采集为准。
- **后台**：「通讯录」页——选设备 → 表格（姓名/电话/邮箱/采集时间）+ 姓名/号码/邮箱模糊搜索 + 分页 + 清空快照。

## get_sms 获取短信

读取设备短信并上报服务端（v0.1.25）。借鉴 autoxClient sms.js。

- **参数**：`sms_type`（all/inbox/sent/draft/outbox/failed，默认 all）、
  `limit`（条数上限，默认 100，上限 1000）。
- **链路**：命令 → 客户端 `SmsReader` → `POST /api/sms` → MySQL `sms_messages` 表。
- **存储语义**：**累积存档 + (device_id, sms_key) 唯一键去重**——sms_key 取设备端
  `_id`（异常时退化为内容摘要 MD5 前 16 位）；重复上报刷新已读标记/正文/类型/
  **卡字段**（sim_slot/sim_number，v0.1.27+，重复上报可回填历史数据的卡信息）。
  去重统计用前后 COUNT 差值精确区分新增/更新（pymysql executemany 对
  ON DUPLICATE 的受影响行数语义模糊：更新计 2）。
- **卡来源**（v0.1.27+）：短信库 `sub_id` 列（Android 5.1+）经
  `SubscriptionManager` 映射为卡槽序号 + 本机号码；ROM 无此列或无
  READ_PHONE_STATE 时按**单卡兜底 → 未知**（-1/''），后台显示「未知」。
- **后台**：「短信」页——类型筛选 + 号码/正文搜索 + **时间游标翻页**
  （before_ms = 已加载最旧一条，对累积数据比 offset 稳）+ 所属卡列 + 单条删除。

## get_call_log 获取通话记录

读取设备通话记录并上报服务端（v0.1.25）。借鉴 autoxClient phone.js。

- **参数**：`call_type`（all/incoming/outgoing/missed，默认 all）、
  `limit`（条数上限，默认 200，上限 1000）。
- **链路**：命令 → 客户端 `CallLogReader`（READ_CALL_LOG，查 `CallLog.Calls`，
  含联系人名缓存与归属地列）→ `POST /api/calllogs` → MySQL `call_logs` 表。
- **存储语义**：按设备**覆盖快照**（与通讯录一致）；含卡字段（sim_slot/sim_number，
  v0.1.27+，重新采集即刷新）。
- **卡来源**（v0.1.27+）：四层解析（v0.1.29 修正）——① 通话库 `sub_id` 列
  （AOSP 隐藏列，多数 ROM 存在）映射激活卡；② `PHONE_ACCOUNT_COMPONENT_NAME`
  + `PHONE_ACCOUNT_ID` 两列拼装「组件名/id」与 `callCapablePhoneAccounts`
  精确比对（ACCOUNT_ID 列只存 handle 的 id 部分，必须与 COMPONENT_NAME 组合；
  v0.1.28 及之前拿完整 toString() 对 id 部分，永远匹配不上——这就是
  「短信有卡、通话未知」的根因）；③ `PHONE_ACCOUNT_ADDRESS`（线路地址 ≈
  本机号码）与 SIM 号码尾号比对（兼容 +86 前缀）；④ 单卡兜底（仅一张激活卡
  时通话必然经它；无权限时不猜测）。都没有按未知（-1/''）。
  **ROM 兼容**（v0.1.28+）：个别 ROM（实测一加 9 Plus / ColorOS）对未知列
  投影直接抛 `IllegalArgumentException`（「Invalid column sub_id」）而非返回
  -1 索引——客户端先带 `sub_id` 查询，失败后去掉该列降级重查（仅丢卡来源，
  记录本身完整上报）。`get_sms` 的 `SmsReader` 同款防御。
- **后台**：「通话记录」页——类型筛选 + 号码/联系人搜索 + 所属卡列 +
  时长/归属地展示 + 清空快照。

## get_sim_info 获取SIM卡信息

读取设备 SIM 卡列表并上报（v0.1.28）。为「发送短信/拨打电话」的选卡提供数据源。

- **参数**：无。
- **链路**：命令 → 客户端 `SimHelper`（READ_PHONE_STATE，查
  `SubscriptionManager.activeSubscriptionInfoList`）→ `POST /api/sims` →
  MySQL `device_sim_info` 表（(device_id, slot_index) 主键，按设备覆盖快照）。
- **上报字段**：卡槽序号（0 基）、subscription_id、本机号码（运营商未写入为空）、
  运营商名称（如「中国移动」，ROM 未提供为空）。
- **回执**：卡片摘要直接可读（如「共 2 张卡：卡1（138xxx · 中国移动）；
  卡2（号码未知 · 中国电信）」）。
- **用途**：管理后台命令下发页 `send_sms` / `dial_phone` 的 sim 选项**按此结果
  动态渲染**（选卡下拉显示实际卡数/号码/运营商，参数区顶部有卡信息提示条）；
  设备从未上报过时退化为通用选项（自动/卡1/卡2/卡3）。
- **权限**：READ_PHONE_STATE（启动批量申请清单内）；未授权回执失败并提示。

## get_current_page 获取当前页面

读取设备**当前前台应用**（应用级，v0.1.31）。核心 `app/.../data/ForegroundAppReader.kt`。

- **参数**：无。**结果去向**：仅命令回执（时点状态，不建表不持久化）。
- **系统限制（设计根因）**：Android 5.1 起 `getRunningTasks` 对第三方应用
  只返回自己的任务；无障碍服务能到 Activity 级但「读屏」权限过重——
  故只能到**应用级**（应用名+包名，如「微信 com.tencent.mm」），拿不到
  具体 Activity 页面，这是系统红线不是实现欠缺。
- **实现**：`UsageStatsManager.queryEvents`（最近 10 分钟）取最后一次
  `ACTIVITY_RESUMED`（API 29 前同值 MOVE_TO_FOREGROUND，常量编译期内联）；
  事件缺失兜底 `queryUsageStats` 取 24h 内 `lastTimeUsed` 最新的应用。
- **授权**：`PACKAGE_USAGE_STATS`（AppOp 级特殊权限，需设备上开一次
  「使用情况访问权」）：未授权时尝试直接弹系统设置页（Android 10+ 后台
  启动可能被拦），回执失败附手动路径；刚开权限统计未生成时回执提示稍后重试。
- **用途扩展**：远端确认设备正在被谁使用/在干什么（如确认锁屏前的界面状态）。

---

## get_network 获取网络状态

读取当前网络状态（v0.1.31）。核心 `app/.../data/NetworkReporter.kt`，
借鉴 autoxClient `network_watcher.getStatus` 的一次性查询路径。

- **参数**：无。**结果去向**：仅命令回执。
- **返回**：联网类型（WiFi/蜂窝/VPN/以太网/无网络，VPN 标注承载网如
  「VPN（over WiFi）」）、**是否真正可上网**（NET_CAPABILITY_VALIDATED，
  区分「连上但上不了网」）、IPv4 地址、蜂窝制式（2G/3G/4G/5G）、
  WiFi 名称（SSID）。
- **权限分级**：ACCESS_NETWORK_STATE 声明即得（主路径永不失败）；
  蜂窝制式需 READ_PHONE_STATE、SSID 需精确定位权限+设备定位服务开启
  （Android 8.1+ 系统限制）——未授权/ROM 隐私化时对应子项显示
  「未知」，**不影响命令成功**。
- **踩坑预防**：`WifiManager.connectionInfo` 在 API 31 标记弃用但仍可用
  （回调式 `NetworkCallback + FLAG_INCLUDE_LOCATION_INFO` 需异步等待，
  一次性查询不值得）。

---

## get_battery 获取电量

读取电池状态（v0.1.31）。核心 `app/.../data/BatteryReporter.kt`，
借鉴 autoxClient `device.js getBatteryInfo`。

- **参数**：无。**结果去向**：仅命令回执。
- **实现**：注册（仅拉取不常驻）`ACTION_BATTERY_CHANGED` 粘性广播，
  **零权限**。
- **返回**：电量百分比、充电状态（充电中/已充满/放电中/未充电）、
  充电方式（仅充电时显示：AC 电源/USB/无线）、温度（℃）、电压（V）、
  健康度（良好/过热/损坏/过冷等）、电池技术（如 Li-poly）。
- 示例回执：`电量：85%（充电中·USB）｜温度：28.5℃｜电压：4.32V｜健康度：良好｜电池：Li-poly`。

---

## 数据采集类通用设计（v0.1.25）

- **架构**：与位置功能同款「命令触发 → 客户端执行 → REST 上报 → MySQL →
  后台页面」，大体量数据不进命令回执文本（回执只报汇总数字）。
- **上报与回执关系**：与 get_location 的 fire-and-forget 不同，这三类命令的
  **数据本身是主产物**，上报失败在回执中明示（「已读取 N 条但上报失败，可重试」）。
- **权限**：READ_CONTACTS / READ_SMS / READ_CALL_LOG 均已入 Manifest 与
  启动批量运行时权限申请清单；未授权时命令回执失败并提示授权。
- **只读红线**：三类采集命令均只读——不删联系人、不改短信库/通话记录
  （参考项目实测：写短信库需「默认短信应用」身份，Android 4.4+ 无法绕过）。
  发短信/拨打电话属**写操作**，由独立的 `send_sms` / `dial_phone` 命令承担
  （见 [control.md](control.md)），不破坏本红线。

### 踩坑

- Android Geocoder（坐标转地址）依赖 Google 后端服务，国产机不可靠——v1 不做逆地理编码。
- `LocationListener` 用 SAM lambda 时内部 `this` 指向外部类而非监听器，
  注销监听须用 `object : LocationListener` 表达式。
