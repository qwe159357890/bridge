把最新版 APK 放进本目录（文件名需含版本号），客户端下发 check_update 即可检查：

  bridge-v0.1.32.apk          ← APK（文件名解析版本号，取最高版本）
  update.json                 ← 可选，更新说明：
                                {"version": "0.1.32", "notes": "新增截屏、弹窗、检查更新等命令"}

客户端通过 GET /api/updates/latest 检查、GET /api/updates/{文件名} 下载（服务器直链，不走 GitHub）。
