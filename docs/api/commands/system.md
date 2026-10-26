# 系统类命令（check_update）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `system` 分类。
> `upload_log` 见 [../ws-client.md](../ws-client.md) §五/§七。

## check_update 检查更新（服务器目录版，不依赖 GitHub）

**设计动机**：GitHub 在国内不稳定，更新分发完全走服务器自身。

### 开发者操作（发布新版时）

1. 云端打包产出 APK 后，把 APK 拷进服务器更新目录 `server/data/updates/`
   （`updates.dir` 可配），**文件名需含版本号**，如 `bridge-v0.1.32.apk`；
2. 可选放一个 `update.json` 写更新说明：
   ```json
   {"version": "0.1.32", "notes": "新增截屏、弹窗、检查更新等命令"}
   ```
3. 目录里可以留多个 APK，服务端取**版本最高**的（版本号从文件名解析，
   按 `.` 分段数值比较，`0.1.10 > 0.1.9` 正确）。

### 运行逻辑（v0.1.37 起为完整自动更新闭环）

```
下发 check_update（或设备「关于」页点「检查更新」，同一入口 UpdateManager）
  → 客户端带当前 versionName 请求 GET /api/updates/latest
  → 服务端扫描目录取最新 APK，与客户端版本比对
  → 无 APK        回执「服务器暂无更新包」
    已是最新      回执「已是最新版本」
    有新版本      自动下载 APK（通知栏进度）→ 拉起系统安装页 → 用户点「安装」完成
```

- 鉴权：设备头（`X-Device-Id` + `X-Device-Token`，与音乐下载同一套）。
- 下载：`GET /api/updates/{filename}` 服务器直链（仅放行单级 `.apk` 文件名）；
  先写 `.tmp` 再原子重命名，半截文件不会进缓存；同版本已下载过则跳过下载。
- **防重复触发**：下载/安装中再次下发命令或连点按钮，直接拒绝并提示
  （UpdateManager 原子锁贯穿全流程）。
- **安装**：Android 不允许静默安装，最终在系统安装页由用户点一次「安装」；
  Android 8+ 需「允许安装未知应用」——未开启时自动跳设置页引导，APK 已保留，
  开启后再触发一次即可直接进安装页（Manifest 已声明 REQUEST_INSTALL_PACKAGES，
  APK 经 FileProvider 共享给系统安装器）。
- 会话保持提示：更新成功安装后 App 会被系统重启，WebSocket 自动重连。
