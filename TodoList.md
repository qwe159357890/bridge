# TodoList — 未了结事项累积清单

> - 每轮会话把用户提出的「需要修改 / 新增 / 优化」逐项归纳**追加**到本文件；
> - 后续轮次用户确认某项「已修复 / 完成 / 结束」→ **删除**对应条目；
> - 用户未确认完成的事项**一直累积保留**，作为跨轮次跟踪清单；
> - 每轮回复格式：先「本轮改动总结」（逐项归纳），后「生效提示」表格。

---

## 未了结事项（2026-10-28 10:41 新批次；旧 10 项已全部由用户确认生效，按指令清空）

### 1. 管理后台选「全部设备（聚合）」后下拉框不显示该选项（admin）——已修复待确认

**根因**：`withAll` 选项 `value=''`，el-select 把空串 modelValue 视为「未选中」
只显示 placeholder。**已修**：DevicePicker 内部用哨兵值 `__all__` 映射显示，
对外 v-model 契约不变（''=全部设备）。刷新后台即可验证。

### 2. 画中画窗口不能完全自由缩放（admin）——已定位为浏览器硬限制，已优化待确认

**查证 Chromium 源码（picture_in_picture_window_manager.cc）**：Document PiP
窗口有引擎硬编码限制——最小 300×300（kMinWindowSize）、最大=屏幕工作区 80%
（kMaxWindowSizeRatio=0.8），任何网页代码都无法突破。
**已做**：①初始尺寸自适应给到接近上限（≤1024×16:9，夹在 80% 上限内）；
②按钮 tooltip 与画中画提示条写明限制，超过 80% 需求引导用「全屏」按钮。

### 3. APK 客户端聚合短信列表拉取失败（503）——已修复待确认（需重启服务器）

**两个根因均已修复**（server/app/core/sms_store.py）：
- `list_threads` 的 `SELECT COUNT(DISTINCT device_id, address)` **漏了 `AS n`
  别名** → DictCursor 取 `row["n"]` 抛 KeyError 'n' → 503；
- 所有操作**共享单个 MySQL 连接**，FastAPI 线程池并发访问（APK 每 3~5s 轮询 +
  管理后台请求同时到达）→ 协议错乱（`Packet sequence number wrong`、
  `utf-8 codec can't decode`）。
  **已改**：补 `AS n` 别名；连接管理改为**每次操作独立连接**（用完即关，
  线程安全），schema 建库建表仍启动时一次。**需重启服务器生效**。

### 4. APK 保活设置弹窗开关状态不实时刷新（APK）——已修复待确认（需装新 APK）

**根因**：ResourceSheet 组合期直接读 SharedPreferences 是普通值（无重组订阅），
点开关只写盘不触发重组 → UI 不动，重开弹窗才刷新。
**已改**：开关状态提升为 `mutableStateMapOf` 可观察状态；核心项「二次确认关闭」
写盘路径经 `refreshKey` 联动同步。**已打包 v0.1.59**（run #116 success，sha256
`35dabbe3…accb` 校验一致），待安装实测确认。

---

## 已了结（确认后从上方移入本区留痕一轮，下一轮清理）

（空）
