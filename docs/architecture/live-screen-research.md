# 投屏实时直播调研（屏幕镜像 → 管理后台实时观看）

> 状态：**已实现（v0.1.50，采用路线 B/FLV）**，实现细节见
> `docs/api/commands/media.md`「screen_live 投屏直播」章节。本文档保留为
> 方案依据与延迟/风险评估。

## 一、需求与定位

在管理后台实时观看设备屏幕（约 1 秒级延迟的「直播」），区别于现有的：

| 能力 | 现有实现 | 与直播的关系 |
|---|---|---|
| 截屏 screenshot | 无障碍静默截屏 / 投屏单帧 | 单帧，非实时 |
| 录屏 record_screen | MediaRecorder 定时录 MP4 后上传 | 录完才有产物 |
| **投屏直播（本文档）** | — | 边采集边看，持续直到手动停止 |

## 二、地基已备（v0.1.41 的关键遗产）

1. **持久镜像幕布**：`MediaCaptureService.obtainMirrorDisplay()`——Android 14+
   投屏令牌一次性问题已解决，幕布（VirtualDisplay）每投影会话只建一次，
   surface 可通过 `setSurface()` 反复挂/摘（截屏挂 ImageReader、录屏挂 MediaRecorder）。
   **直播 = 把 surface 换成 MediaCodec 编码器输入面**，幕布复用逻辑原封不动。
2. **流式分片通道（本次已落地）**：客户端 WS `media_stream` 事件 → 服务端
   `StreamHub` → 管理后台专用流 WS（`/api/admin/stream/{device_id}` 二进制帧）→
   mpegts.js 实时播放。**这套通道是编码无关的**，直播直接复用。

## 三、方案设计（增量部分）

### 客户端

```
mirrorDisplay.setSurface(codec.createInputSurface())
  └─ MediaCodec H.264 编码器（同步模式）
       └─ 输出 H.264 裸流 → MPEG-2 TS 复用（tsmuxer 逻辑自写或引库）
            └─ 每 500ms 一帧分片 → WS media_stream（event 同现有，kind=screen）
```

关键点：

- **编码器**：`MediaCodec.createEncoderByType("video/avc")`，720p@1.5~2.5Mbps
  （直播分辨率不必 1080p，带宽与延迟优先）；
- **TS 复用**：这是唯一有实质工作量的部分。两条路线：
  - A. **引 `mpegts-writer` 类纯 Kotlin/Java TS 复用实现**（自写 PAT/PMT/PES 也约
    300~500 行，H.264 only 时并不复杂）；
  - B. **绕开 TS**：用 FLV 容器（H.264 裸 AVCC 打包更简单），后台 mpegts.js
    原生支持 `type:'flv'` 的 WS 直播——**推荐 B**，FLV 复用约 100 行。
- **声音**：屏幕直播通常不需要声音；如需，加一条 AAC（AudioRecord + MediaCodec）
  轨道，与视频帧按 PTS 交织。
- **授权时机**：与截屏/录屏一致——投影会话未保持时弹一次系统授权
  （「立即开始」），会话保持中免确认直接开播。

### 服务端 / 管理后台

**零新增**。`media_stream(kind=screen)` 走现有 ws.py → StreamHub → 专用流 WS；
管理后台 StreamPlayerCard 增加一种 kind（screen），mpegts 配置 `type:'flv'` 即可。

### 命令面

- 新命令 `screen_live`（开始直播，无参数或 quality/duration）+ 复用 `stream_stop`；
- 或做成后台「设备行」上的常驻「直播」按钮（点击开弹窗播放器，再点停止）——
  交互上比命令卡片更顺手，实现时二选一或都要。

## 四、延迟与性能评估

| 环节 | 预期开销 |
|---|---|
| 屏幕采集（VirtualDisplay→编码器输入面） | GPU 合成，<10ms |
| H.264 编码 720p（硬件编码器） | 每帧 2~8ms |
| TS/FLV 打包 + base64 + WS | ~5ms（CPU，可忽略） |
| 服务端转发 | <1ms |
| 后台 mpegts 解封装 + 解码 | 缓冲主导：`enableStashBuffer=false` 时 <300ms |
| **端到端** | **约 0.6~1.5 秒**（与摄像头流式同级） |

注意：分片走 base64 JSON 有 ~33% 膨胀，720p@2Mbps ≈ 330KB/s 上行；
后续优化可把客户端→服务端这段改成 WS 二进制帧（协议加一种 type），管理侧
到浏览器已是二进制。首版按现有 base64 通道先跑，够用再优化。

## 五、风险与边界

1. **DRM/安全内容**：FLAG_SECURE 的界面（银行类 App）直播出来是黑屏（与截屏一致，系统限制）；
2. **前台服务类型**：直播期间 `mediaProjection` 类型前台服务已在，无需新增；
3. **Android 14+ 投屏隐私提示**：投屏期间系统状态栏有投屏图标（无法去除，同录屏）；
4. **多订阅者**：StreamHub 支持多后台同时看同一设备（每个订阅者独立队列）；
5. **进程被杀**：直播中断 → 客户端命令回执失败 / stream_status 下线兜底事件已处理。

## 六、实现排期建议（若拍板实现）

| 步骤 | 工作量估计 |
|---|---|
| ① MediaCodec 编码 + FLV 复用（客户端） | 主体工作，1 次会话 |
| ② 命令 + 服务/前台类型接线 | 小 |
| ③ 后台 kind=screen 播放分支 | 小（StreamPlayerCard 加分支） |
| ④ 文档 + 真机联调（延迟/码率调优） | 中 |
