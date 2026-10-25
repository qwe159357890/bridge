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
- **管理后台**：设备管理页每行「位置」按钮 → 弹窗上半高德地图轨迹
  （Key 由 `/api/locations/amap-config` 动态下发，不进前端代码）+
  下半位置历史列表。

### 踩坑

- Android Geocoder（坐标转地址）依赖 Google 后端服务，国产机不可靠——v1 不做逆地理编码。
- `LocationListener` 用 SAM lambda 时内部 `this` 指向外部类而非监听器，
  注销监听须用 `object : LocationListener` 表达式。
