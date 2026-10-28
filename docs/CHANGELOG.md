# Bridge 变更记录（CHANGELOG）

> 记录版本里程碑与关键修复脉络。细节见对应专题文档，工作日志见 `.workbuddy/memory/`。

---

## 2026-10-28 · v0.1.59 ✅ 保活细化弹窗开关实时刷新

- **APK**：「后台资源占用 · 细化」弹窗开关点击后**立即切换**（此前要点开关写盘
  不触发重组、重开弹窗才刷新——组合期直读 SharedPreferences 无重组订阅，改为
  `mutableStateMapOf` 可观察状态；核心项「二次确认关闭」路径经 refreshKey 联动）。

---

## 2026-10-28 · v0.1.58 ✅ APK 短信聚合模式 + 管理后台 UI 六项修复

- **APK**：短信页**聚合模式**（默认聚合）——显示服务端存档里**所有设备**的短信
  （会话键=设备+号码），本机会话标绿「本机」；聚合详情聊天气泡+「加载更早」游标分页；
  顶部「聚合/本机」切换条，本机视图保留原有能力；聚合存档只读。
  服务端配套新增设备认证端点 `GET /api/device/sms_threads`、`/api/device/sms_messages`。
- **APK**：返回键「再按一次退出」Toast 美化（品牌蓝图标圆徽+深蓝渐变胶囊+投影）。
- **admin 六项**：①切命令卡片即销毁实时画面控件（断 WS+移除，自动预连不弹回）
  ②全屏画面铺满（根因 `.stream-video` max-height 420px 未在全屏态解除）
  ③画中画 Document PiP（含音量控件整体搬入，窗口自由调大小）④停止采集按钮
  仅采集中可用 ⑤tooltip 美化 ⑥**侧边栏导航卡死修复**（hide 未进 defineExpose →
  路由守卫 TypeError 导航中止；Playwright 复现实证）。
- **admin**：短信页聚合模式（全部设备+来源设备列+按行设备分组标记/删除）。
- **server**：短信 `list_sms` device_id 改可选；**上下线记录 MySQL 持久化**
  （`connection_events` 表，`retention_days` 默认 90，MySQL 不可用自动降级内存）。

---

## 2026-10-28 · v0.1.56~v0.1.57 ✅ 码率失控闭环 + 短信已读方案落定

- **v0.1.56**：投屏码率失控根治（显式 `KEY_BITRATE_MODE=CBR`，VBR 下 KEY_BIT_RATE 只是
  均值导致实测 22Mbps）；编码器 start 后 request-sync 立即出首帧；二进制 WS 帧
  （省 33% 上行）+ 画质档位 + GOP 1s + 低延迟模式；流式不限时 + 停止采集；回执上传开关。
- **v0.1.57**：短信已读 **Bridge 内部映射**方案落定（ColorOS 拒绝第三方写系统短信库，
  三级回退实测全 0 行 → `SmsReadStore` 本地 JSON 展示层叠加，免默认短信身份）；
  停止采集即断 WS + 手动停止 15min 封锁；音量控件；tooltip 限宽换行。
- v0.1.55 遗留待办「码率失控」「启动黑屏」均已闭环（CBR + request-sync 首帧）。

---

## 2026-10-27 · v0.1.55 ✅ 流式实时链路稳定里程碑（投屏/流式录像不卡顿）

**节点意义**：投屏直播与流式录像的实时播放终于稳定不卡顿。这是自流式功能上线以来
持续多轮排障（TS 转封装 → FLV 对齐器 → 追帧策略 → 静止断流）的收敛节点。

### 本轮关键修复

| 问题 | 根因 | 修复 |
|---|---|---|
| 流式录像只出首帧后停滞 | Android MediaMuxer 的 MPEG-2 TS 时间戳缺陷（PCR/boottime、dts 非单调）→ mpegts.js 解码停滞 | 服务端 `ts_to_flv.py` 统一 TS→FLV 转封装（PTS/DTS 归一化） |
| 卡住→刷新恢复→再卡住死循环 | 512KB 盲缓存任意字节截断/中途加入者字节断层 → `Invalid PrevTagSize` | `flv_aligner.py`：流头+关键帧对齐接入；StreamHub 订阅原子化 |
| 投屏首次无实时画面 | 首次下发时 videoEl 未渲染，attachMediaElement 抛异常被吞 | nextTick + 会话守卫 + 三重预连保险 |
| 追帧跳变（每隔几秒卡一下） | 落后 2.5s 猛 seek = 冻结攒够→瞬跳循环 | 平滑变速追帧 v3：缓冲垫 1.2~3.5s |
| 屏幕静止时周期性卡顿 | ① 上游静止断流十几秒→数据爆发→硬跳；② 解码帧不增被误判卡死→4s 重建循环 | 冻结即时追尾 + 停滞检测区分「上游无帧」+ 重建 12s 冷却 |
| 静止时浏览器旋转 spinner 误导「加载中」 | Chrome 原生 controls 在 waiting 态显示 spinner | video 去掉原生 controls + 卡片头部全屏按钮 |
| **静止期上游断流（治本）** | 设备 Surface 无新帧时编码器不出帧，时间轴断流 | APK：`KEY_REPEAT_PREVIOUS_FRAME_AFTER` 500ms 重复帧（静止期重编上一帧，时间轴连续） |

### 待办（遗留）

- 投屏编码器码率失控（实测 ≈22Mbps vs 配置 2.5Mbps）：静止期重复帧已解决静止断流，
  活动画面的码率爆发（KEY_BIT_RATE 不生效？）仍需单独排查。
- 启动黑屏 ~14s（下发→出画）：设备端编码器启动 + 关键帧对齐等待，属 APK 侧优化项。

---

## 2026-10-27 · v0.1.53

- 投屏直播 FLV sequence header 剥起始码（mpegts FormatError 根因）、首帧不再丢弃、TS 播放追帧自愈。
- 持久镜像幕布稳定复用；宽高清零运算优先级 bug 修复（v0.1.52）。

## 2026-10-27 · v0.1.50~v0.1.51

- 投屏直播（screen_live）上线：MediaCodec + 自研 FlvMuxer，FLV 复用流式通道。
- 音量控制（set_volume 5 流 + get_volume）、流式录像/录音（stream=true + stream_stop）。

## 2026-10-26 · v0.1.41~v0.1.45

- 持久镜像幕布（解决 Android 14+ 投屏令牌一次性）。
- 主动上报默认开（位置周期 + 新短信即推）；媒体文件自动清理。
