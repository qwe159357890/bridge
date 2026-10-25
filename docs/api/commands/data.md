# 数据采集类命令实现

数据采集类（`data`）命令的实现语义。参数 schema 的唯一权威是
`server/app/core/command_catalog.py`，本文只写 catalog 说不清的机制、策略与边界。

## get_location 获取位置

让客户端回报当前位置。**真实实现**（非模拟）：Android 原生 `LocationManager`，
无 GMS 依赖，国产 ROM 可用（网络定位由厂商 NLP 服务如高德实现）。

### 定位策略（LocationHelper）

```
coarse 粗略（默认）：
  lastKnown 缓存（network/passive，5 分钟内视为新鲜）→ 命中直接返回「network(缓存)」
  → 未命中：监听一次 network 定位（timeout 内）→ 返回「network」
  → 超时：回退陈旧 lastKnown（标注「缓存 N 分钟前」）→ 都没有则回执失败

fine 精确：
  监听一次 GPS 定位（timeout 内）→ 返回「gps」
  → 超时：自动回退 coarse 结果，来源标注「GPS超时回退」
```

- 冷启动 GPS 搜星可能 30 秒+，精确模式建议 timeout=30；timeout 参数下限 5 上限 120 秒。
- 回执文本示例：`位置：30.123456, 120.654321（±35m，来源 gps，2026-10-25 12:05:30，海拔 12.0m，速度 1.2m/s）`

### 权限

- Manifest 已声明 `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` /
  `ACCESS_BACKGROUND_LOCATION`（保活资源锚点一并声明）。
- 均为**运行时权限**：执行时校验，未授权回执失败
  「缺少定位权限，请打开 App 在系统设置中授予定位权限后重试」（一次性人工授权）。
- fine 模式需 FINE 权限；仅有 COARSE 时**自动降级**为网络定位并在回执注明。

### 持久化预留（后续版本）

v1 纯文本回执（下发历史可见）。`LocationHelper.LocationFix`
（lat/lon/accuracy/provider/timestamp/altitude/speed 结构化数据类）已为入库预留：
后续版本增加上报接口（如 `POST /api/locations`）+ 服务端 SQL/MySQL 存储，
即可支持位置历史与轨迹查询，客户端只需在现有回执旁加一次 `apiClient` 调用。

### 踩坑

- Android Geocoder（坐标转地址）依赖 Google 后端服务，国产机不可靠——v1 不做逆地理编码。
- `LocationListener` 用 SAM lambda 时内部 `this` 指向外部类而非监听器，
  注销监听须用 `object : LocationListener` 表达式。
