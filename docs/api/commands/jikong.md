# 极空保护板（jikong）命令实现文档

> 分类 key = `jikong`，对应 `command_catalog.py` 的 category。极空（JK BMS）保护板通过
> **BLE 蓝牙透传**通信，Bridge 手机当中转站，管理后台可远程查看状态并下发控制命令。

## 架构与数据流

```
BMS(从机) --BLE(0xDD/NW帧)--> 客户端(jikong模块) --主WS(jikong_state)--> 服务端 --> 管理后台
                                   ▲                                            │
                                   └────────── jikong_* 命令（BLE 写帧）────────┘
```

- **客户端**：`jikong/` 包（BLE 客户端 + 协议编解码 + 控制器 + 状态缓存），页面 `ui/JikongPages.kt`。
- **服务端**：`core/jikong_store.py` 存状态，`api/v1/jikong.py` 提供查询/控制 REST，`ws.py` 处理上报。
- **管理后台**：`views/jikong/index.vue` 复刻 UI + 远程交互。

## 协议要点（无真机，按文档实现，待真机抓包微调）

- **BLE UUID**：运行时自适应探测 `ffe0`（标准透传）/`ff10`/`ff80` 三组 service + 对应 char。
- **0xDD 帧**：读 `DD A5 <cmd> <len> <data> <sum> 77`，写 `DD 5A ...`；命令码 0x01 基本信息 /
  0x02 单体电压 / 0x03 告警 / 0x04 设备信息 / 0x05 均衡 / 0x06 温度 / 0xE1 控制 / 0xE2 写参数。
- **NW 帧**：`4E 57 <LENGTH> ... <标识码+数据> ... <68> <校验>`，标识码 0x79 总压 / 0x7A 电流 /
  0x7B SOC / 0x7C~ 单体 / 0x85~ 温度 / 0x8E~ 保护。
- **串数自动探测**：单体电压帧数据长度 ÷ 2；温度帧数据长度 ÷ 2。

## 命令语义

### jikong_scan 扫描极空设备
- 客户端扫描周边 BLE，返回设备名/地址/信号强度；需蓝牙已开 + 定位/BLE 权限。

### jikong_connect 连接极空设备
- 参数 `address`（必填，BLE MAC）。连接成功后客户端自动开始周期轮询（默认 2s）并上报状态。
- 失败回执如实说明（地址错误/未开蓝牙/设备被占用）。

### jikong_disconnect 断开极空设备
- 断开 BLE 并停止轮询/上报。

### jikong_status 极空状态
- 只读本地状态快照（连接状态 + 总压/电流/SOC/压差/温差/MOS/单体/温度），不触发 BLE 操作。

### jikong_mos 极空 MOS 控制
- 参数 `charge`（含充电 MOS）/`discharge`（含放电 MOS）/`on`（开/关）。写保护板，谨慎操作。

### jikong_balance 极空均衡控制
- 参数 `on`（开/关）。

### jikong_write_param 极空写参数
- 参数 `param_id`（参数编号）+ `value`（原始整数，单位由参数类型决定）。需设备密码，谨慎操作。

## 坑与提醒

1. **无真机最大风险**：BLE UUID 与帧字段偏移因固件版本而异，真机连不上时用 nRF Connect 抓一帧
   真实通信，据实调整 `JikongProtocol.kt` / `JikongBleClient.kt` 的常量。
2. **省电约定**：页面不可见即停止轮询并释放 BLE 连接（`keepConnection` 默认 false），
   重新进入页面需重连；与参考项目（ESP32 继电器）省电策略一致。
3. **上报去重**：`JikongController` 仅在状态 JSON 变化时才上报，避免轮询造成刷屏。
