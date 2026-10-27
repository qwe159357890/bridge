# 音视频类命令（screenshot / record_screen / take_photo / record_video / record_audio / clear_media）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `media` 分类。

三命令共同链路：**采集 → 产物落设备 App 私有 `media/` 目录 → multipart 上传
`POST /api/media` → 回执**。服务端文件存 `server/data/media/{device_id}/`，
元数据入 MySQL `media_files` 表，管理后台「媒体文件」页查看（图片预览 / 视频播放 /
下载 / 删除），并按配置自动清理（默认保留最近 100 条且 ≤ 7 天，先到先触发，
`config.yaml` 的 `media.max_count` / `media.max_age_days` 可调）。

## 采集互斥守卫（v0.1.48）

摄像头/麦克风/投屏是**独占硬件**，同一设备并发下发采集命令必然一条成功一条失败。
三层防线（v0.1.48 起）：

1. **服务端守卫**（权威）：`POST /api/commands` 对采集组
   `{screenshot, record_screen, take_photo, record_video, record_audio}` 检查
   同设备 15 分钟窗口内的 pending 记录——命中返回 409 并说明在途命令；
   `force: true`（后台「强制下发」勾选或 409 确认弹窗）可越过，用于设备无响应卡
   pending 时的解锁重发。
2. **后台按钮联动**：目标设备有在途采集命令时，「下发命令」按钮禁用 + 提示在途
   命令名与已等待时长；勾选「强制下发」恢复可用。
3. **客户端兜底**：`MediaCaptureService` 收到第二条采集命令直接回执失败
   （旧版会覆盖共享状态 `commandId/mode`，污染在途任务的回执——顺手修掉）。

pending 超过 15 分钟视为僵尸（客户端崩溃回执丢失）不再拦截，守卫自动解除。

## 缩略图（v0.1.48，管理后台流量优化）

`GET /api/media/{id}/thumb`（鉴权同 `/file`）：仅图片类，首次访问用 Pillow 生成
480px JPEG 缓存到 `server/data/media/thumbs/{id}{后缀}.thumb.jpg`，删原图时同步清理；
Pillow 未安装或解码失败降级返回原图。后台媒体列表图片走缩略图 + `el-image lazy`
（滚动到可视区才请求），点击查看大图仍取原图。视频封面不适用（走浏览器首帧方案）。

## screenshot 截屏

两级路线（v0.1.40，借鉴 autox）：

### 首选：无障碍静默截屏（`BridgeAccessibilityService.takeScreenshot`）

- 前置：开启「设置 → 无障碍 → Bridge 辅助服务」（XML 声明 `canTakeScreenshot`，
  Android 11+，需 minSdk 能力见下）。开启后**下发即拍**：无投屏授权框、无前台服务、
  无通知、无屏幕拉起——**全程无感**，这正是 autox 截屏完全静默的原理。
- 机制：系统 `AccessibilityService.takeScreenshot()` 直接返回屏幕 HardwareBuffer →
  `Bitmap.wrapHardwareBuffer` 拷贝为软件位图 → JPEG 90 保存。不再依赖 MediaProjection
  会话，也就不存在投屏路线的「截屏超时（屏幕可能受 DRM 保护）」问题。
- 失败自动**降级投屏路线**（会弹系统授权框，属兜底而非常态）。

### 兜底：MediaProjection 投屏取单帧（无障碍未开启时）

- 机制：ImageReader RGBA → JPEG 90。
- **授权（v0.1.37 起一次授权长期有效）**：首次下发经 Activity 弹「投屏授权框」
  （设备上点一次「立即开始」）；之后 MediaProjection 由 `MediaCaptureService`
  **常驻持有**（借鉴 autox：进程不重启一直有效），后续截屏/录屏**免确认**直接采集。
  会话失效场景（用户下拉取消投屏、系统回收、App 进程重启）自动重新走授权。
  90 秒未确认回执失败。
  - 后台启动授权页可能被部分 ROM 拦截：已授予「显示在其他应用上层」权限的设备不受限。
- Android 14+ 硬性要求：`getMediaProjection` 后必须先 `registerCallback` 再
  `createVirtualDisplay`，否则抛 SecurityException「Must register a callback
  before starting capture」——v0.1.36 及以前每次采集必报错即此根因，v0.1.37 修复。
- 产物：`screenshot_{时间戳}.jpg`（屏幕原分辨率）。
- 服务端配套：前台服务 `MediaCaptureService`（`foregroundServiceType="mediaProjection"`，
  Android 14+ 必须先 startForeground 再建 VirtualDisplay；采集完成后转
  「会话保持中」常驻）。

## record_screen 录屏

- 参数：`duration` 秒（3~60，默认 10）。
- 机制：MediaProjection + MediaRecorder（H.264/MP4，30fps，6 Mbps，长边压到 1280）。
- **无法走无障碍路线**（无障碍只能取单帧），始终走投屏：首次点「立即开始」，
  之后免确认（会话保持）；回执 = 录制完成并上传成功。
- 产物：`screen_{时间戳}.mp4`（无音频轨——录系统声需系统投屏音频，且麦克风
  录音有隐私风险，暂不支持）。

## take_photo 拍照

- 参数：`camera` = `back`（后置，默认）/ `front`（前置）；指定方向无摄像头时回退第一个。
- 机制：**Camera1 API + SurfaceTexture(0) 虚拟预览**（无需真实取景界面，参考
  autoxClient 验证过的静默拍照路线），最大 supportedPictureSize，方向按
  CameraInfo.orientation 修正。
- **静音**：`enableShutterSound(false)` + 拍照期间临时静音系统/铃声/提示音流
  （拍完恢复）。个别 ROM 强制快门声属机型限制。
- **通知静默化（v0.1.40）**：Android 11+ 后台访问相机**必须**走 CAMERA 类型前台
  服务，前台服务通知是系统硬性要求**无法完全去除**——静默上限为
  IMPORTANCE_MIN 渠道（无声音/无横幅/折叠通知栏底部）+ 中性文案「Bridge 运行中」，
  不再显示「正在拍照…」等动作内容。
- 权限：`CAMERA` 运行时权限（首次需打开 App 授权一次，未授权回执明示）。
- 无需投屏授权，下发即拍（无需设备端确认）。

## record_video 录像（v0.1.42，借鉴 autox video_recorder.js）

- 参数：
  - `camera` = `back`（后置，默认）/ `front`（前置）；指定方向无摄像头时回退第一个；
  - `quality` = `low`（640×480@24，800kbps）/ `medium`（720p@30，2.5Mbps，默认）/ `high`（1080p@30，5Mbps）；
  - `duration` 秒（3~60，默认 10）；
  - `enable_audio`（默认 true）：同时录麦克风伴音（AAC 44.1kHz/96kbps 单声道），
    需 RECORD_AUDIO 权限，关闭则录无声视频。
- 机制：Camera1 + MediaRecorder（MP4/H.264）。**autox 验证过的启动顺序**（务必保持）：
  open → setPreviewTexture 虚拟预览 → startPreview → `unlock()` 交出摄像头 →
  MediaRecorder 配置（`setCamera`）→ prepare/start——unlock 前不起预览会直接 start failed。
- 方向：`setOrientationHint` 按 CameraInfo.orientation + 屏幕旋转补偿（Android 官方配方）。
- 静音：同拍照——临时静音系统/铃声/提示音流，录完恢复（避免提示音串进麦克风）。
- **互斥约束**：摄像头独占——录像/拍照由采集服务单工作线程天然串行，极端情况下
  并发下发会在回执提示忙/失败，重试即可。
- 权限：`CAMERA`（必需）+ `RECORD_AUDIO`（enable_audio=true 时）运行时权限。
- 产物：`video_{时间戳}.mp4`，kind=`video`，后台媒体页可播放，回执可「查看」。

## record_audio 录音（v0.1.42，借鉴 autox audio_recorder.js）

- 参数：
  - `quality` = `low`（16kHz/32kbps 语音档）/ `medium`（44.1kHz/96kbps 单声道，默认）/ `high`（48kHz/192kbps 立体声）；
  - `duration` 秒（5~300，默认 30）。
- 机制：MediaRecorder 纯音频（`AudioSource.MIC` + MPEG_4 容器 + AAC 编码）。
  无相机、无预览、无 unlock，实现成本远低于录像。
- Android 11+ 后台访问麦克风要求 **MICROPHONE 类型前台服务**
  （Manifest `foregroundServiceType` 加 `microphone` + `FOREGROUND_SERVICE_MICROPHONE` 权限）。
- 权限：`RECORD_AUDIO` 运行时权限（Manifest 早已声明，v0.1.42 起真正使用）。
- 产物：`audio_{时间戳}.m4a`，kind=`audio`，后台媒体页可试听，回执可「查看」。

## clear_media 清理设备媒体（v0.1.47）

- 无参数。清空客户端本地 `media/` 目录（`getExternalFilesDir("media")`）的媒体残留，
  回执「已清理 N 个本地媒体文件（共 X MB）」。
- **定位**：正常采集产物上传成功后客户端立即删除本地临时文件，残留基本都是
  **上传失败的孤儿**。本命令给管理员一个主动兜底手段；另有**自动清理**——
  每次采集完成时顺带清理（>7 天全删 + 超过 50 个只留最新 50 个，正在生成的
  产物是最新文件天然受保护）。
- 并发保护：有采集任务进行中（`captureBusy`）时拒绝执行并回执提示稍后再试。
- 服务器侧媒体（media_store）与此命令无关，请在管理后台「媒体文件」页按条删除
  （服务端另有自动清理：默认保留最近 100 条且 ≤7 天，config.yaml `media.*`）。

## 采集命令两阶段进度（v0.1.47，后台「状态」列实时显示）

- 耗时命令（录屏/录像/录音/截屏/拍照）执行期间，客户端经 WS `message` 通道
  （`event=command_progress`）上报进度，服务端只做 SSE 转发（`command_progress`
  事件）**不落库**——命令历史只保留最终回执：
  - `phase=executing`：执行阶段。录制类（record_screen/record_video/record_audio）
    按「参数时长 × 已耗时」每秒报百分比（`MediaCaptureService.sleepWithProgress`）；
    截屏/拍照瞬间完成，不报此阶段；
  - `phase=uploading`：上传阶段。按真实写到网络的字节计（OkHttp 计数 sink 包装，
    每 300ms 或 ≥5% 节流上报，`CommandModule.ProgressRequestBody`）。
- 后台命令页「待回执」状态下方显示「执行中 45%」/「上传中 45%（8.2/18.2MB）」，
  最终回执到达即消失。

## 上传接口（服务端新增）

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/media`（kind 为 **multipart 表单字段**，白名单含 video/audio） | 设备头 | multipart 上传，单文件 ≤ 200 MB |
| `GET /api/media` | 管理员 | 列表（倒序，含 kind_label / exists） |
| `GET /api/media/{id}/file` | 管理员（Bearer 或 `?token=`） | 文件本体（预览/播放/下载） |
| `DELETE /api/media/{id}` | 管理员 | 删记录 + 磁盘文件 |

> v0.1.37 修复：kind 此前误声明为查询参数，而客户端把 kind 放在 multipart 体里
> （OkHttp `@Part("kind")`），FastAPI 校验直接 422——拍照/截屏/录屏上传全部失败。
> 现改为 `Form(...)` 接收，与已发布客户端的 multipart 形态对齐。
