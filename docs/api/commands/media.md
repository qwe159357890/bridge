# 音视频类命令（screenshot / record_screen / take_photo）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `media` 分类。

三命令共同链路：**采集 → 产物落设备 App 私有 `media/` 目录 → multipart 上传
`POST /api/media` → 回执**。服务端文件存 `server/data/media/{device_id}/`，
元数据入 MySQL `media_files` 表，管理后台「媒体文件」页查看（图片预览 / 视频播放 /
下载 / 删除），并按配置自动清理（默认保留最近 100 条且 ≤ 7 天，先到先触发，
`config.yaml` 的 `media.max_count` / `media.max_age_days` 可调）。

## screenshot 截屏

- 机制：MediaProjection 投屏取单帧（ImageReader RGBA → JPEG 90）。
- **授权**：系统强制要求经 Activity 弹「投屏授权框」，**每次下发需在设备上点一次
  「立即开始」**（Android 14 起授权结果一次性有效，无法复用）。90 秒未确认回执失败。
  - 后台启动授权页可能被部分 ROM 拦截：已授予「显示在其他应用上层」权限的设备不受限。
- 产物：`screenshot_{时间戳}.jpg`（屏幕原分辨率）。
- 服务端配套：前台服务 `MediaCaptureService`（`foregroundServiceType="mediaProjection"`，
  Android 14+ 必须先 startForeground 再建 VirtualDisplay）。

## record_screen 录屏

- 参数：`duration` 秒（3~60，默认 10）。
- 机制：MediaProjection + MediaRecorder（H.264/MP4，30fps，6 Mbps，长边压到 1280）。
- 授权方式同截屏（每次点「立即开始」）；回执 = 录制完成并上传成功。
- 产物：`screen_{时间戳}.mp4`（无音频轨——录系统声需系统投屏音频，且麦克风
  录音有隐私风险，暂不支持）。

## take_photo 拍照

- 参数：`camera` = `back`（后置，默认）/ `front`（前置）；指定方向无摄像头时回退第一个。
- 机制：**Camera1 API + SurfaceTexture(0) 虚拟预览**（无需真实取景界面，参考
  autoxClient 验证过的静默拍照路线），最大 supportedPictureSize，方向按
  CameraInfo.orientation 修正。
- **静音**：`enableShutterSound(false)` + 拍照期间临时静音系统/铃声/提示音流
  （拍完恢复）。个别 ROM 强制快门声属机型限制。
- 权限：`CAMERA` 运行时权限（首次需打开 App 授权一次，未授权回执明示）。
  后台访问相机要求 CAMERA 类型前台服务（Android 11+ while-in-use 规则）——
  拍照走 `MediaCaptureService`（camera 类型）承载。
- 无需投屏授权，下发即拍（无需设备端确认）。

## 上传接口（服务端新增）

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/media?kind={screenshot\|photo\|screen_record}` | 设备头 | multipart 上传，单文件 ≤ 200 MB |
| `GET /api/media` | 管理员 | 列表（倒序，含 kind_label / exists） |
| `GET /api/media/{id}/file` | 管理员（Bearer 或 `?token=`） | 文件本体（预览/播放/下载） |
| `DELETE /api/media/{id}` | 管理员 | 删记录 + 磁盘文件 |
