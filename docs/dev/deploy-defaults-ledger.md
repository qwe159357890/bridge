# 部署默认值改动 · 改前留档台账

> 建立：2026-11-10 17:5x
> 依据：项目「改前留档」铁律 —— 改前逐字固化原文（git 未跟踪文件必抄原文）→ 改 → 回填改后 → 附回滚配方与触发信号。
> 范围：二维码地址 / admin URL / 服务地址三处默认值 + UI 配套 + nginx conf 端口策略。
> 环境对照 SSOT：`docs/admin/environment-matrix.md`。

---

## §1 改动背景

用户把 `admin/dist/` 上传服务器并改用 nginx 托管后，拍板三处默认值统一为生产地址，
并要求每处标明「本机 `npm run dev` 测试环境」应填的值。详见 `environment-matrix.md`。

---

## §2 改前原文（逐字固化，勿改本节）

### 2.1 `server/config.yaml`（updates 段）

> 注意：`public_base_url` 这一行在本次改动**之前已由用户手工改成生产值**（工作区 M 状态，未提交），
> 本次只补测试环境注释，不改该值。

```yaml
updates:
  dir: "data/updates"            # 更新 APK 目录（check_update 命令服务器目录版）
  # 「扫码下载」二维码里用的对外地址（含协议+端口）。留空 = 下载页运行时用请求的
  # Host 头自动推导（同一 WiFi 下扫码即可）。跨网/公网扫码时填真实可达地址，
  # 例如 http://192.168.31.5:15160 或 https://app.example.com
  public_base_url: "https://appclient.039039.xyz:15443"
```

### 2.2 `app/src/main/java/my/bridge/com/admin/AdminPrefs.kt`（类注释 + KEY_URL + url()）

```kotlin
/**
 * 「管理后台」内嵌页配置持久化（v0.2.125，见 docs/admin/client-embed-admin.md）。
 *
 * 语义：客户端内嵌 WebView 打开管理后台（admin），URL 可配置（默认空，首次进入
 * 提示填写）。admin 本身走相对路径（VITE_API_BASE 空），由托管它的地址自动指向服务端，
 * 故只需存「admin 的访问地址」，无需存服务端 API 地址。
 */
```

```kotlin
    /** admin 访问地址（如 http://192.168.31.226:5173 或 nginx 部署的地址）。 */
    private const val KEY_URL = "admin_url"
```

```kotlin
    /** 读取 admin URL；未配置返回空串。 */
    fun url(context: Context): String =
        normalize(sp(context).getString(KEY_URL, "") ?: "")
```

### 2.3 `app/src/main/java/my/bridge/com/admin/AdminWebPage.kt`（地址编辑区 placeholder）

```kotlin
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("管理后台地址") },
                    placeholder = { Text("http://192.168.31.226:5173") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
```

### 2.4 `core/core-network/src/main/java/my/bridge/com/core/network/ws/WsConfig.kt`（常量段）

```kotlin
    /** WebSocket 服务地址默认值（10.0.2.2 为 Android 模拟器回环；真机改为服务器地址）。 */
    const val WS_URL_DEFAULT = "ws://10.0.2.2:15160/ws"

    /** REST 基础地址默认值（设备注册 / 日志上传）。 */
    const val REST_BASE_URL_DEFAULT = "http://10.0.2.2:15160"

    /** 默认 host（不含协议/端口）。 */
    const val HOST_DEFAULT = "10.0.2.2"

    /** 默认端口。 */
    const val PORT_DEFAULT = "15160"
```

> 附：`WS_URL_DEFAULT` / `REST_BASE_URL_DEFAULT` 全项目**无任何引用**（死常量），
> 但为避免与真实默认值矛盾，本次一并对齐。

### 2.5 `app/src/main/java/my/bridge/com/ui/FirstRunSetupScreen.kt`（首次配置引导页）

```kotlin
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("15160") }
```

```kotlin
            placeholder = { Text("如 example.com 或 192.168.1.10") },
```

```kotlin
            placeholder = { Text("默认 15160") },
```

### 2.6 `app/src/main/java/my/bridge/com/ui/SettingsPages.kt`（服务地址编辑弹窗）

```kotlin
                    placeholder = { Text("如 10.0.2.2 或 example.com") },
```

```kotlin
                    placeholder = { Text("如 15160") },
```

### 2.7 `docs/admin/bridge-admin.conf`（★ git 未跟踪文件，整段抄原文）

```nginx
# ★ 落地前必须确认的 3 个变量：
#   1) upstream bridge_backend → 后端真实 IP:端口（server/config.yaml: port 15160）
#   2) server_name / listen     → 若 wxhelper.conf 已在用同一组，必须换，否则 conflicting server name
#   3) ssl_certificate / key    → 必须是 Android WebView 信任的证书（自签会让「管理」Tab 白屏）
#
# ★ 与「复用 wxhelper.conf」二选一：若直接改原文件，只把下面缺失的 location 补进去即可。
```

```nginx
server {
    listen 15080;
    listen 15443 ssl http2;          # nginx >= 1.25.1 建议 listen 15443 ssl; + http2 on;
    server_name appclient.039039.xyz;

    if ($scheme = http) {
        return 301 https://$host:15443$request_uri;
    }
```

### 2.8 「单框带协议」改造追加的改前原文（均为 git 已跟踪文件，完整原文以 git 为准）

> 本节文件全部纳入 git，完整改前原文可由 `git show HEAD:<path>` 逐字取回，
> 故此处固化**关键签名/表达式**（回滚锚点），避免与 git 双写全文。

`core/core-network/src/main/java/my/bridge/com/core/network/ws/ServerAddress.kt`：

```kotlin
    @Volatile
    private var host: String = WsConfig.HOST_DEFAULT
    @Volatile
    private var port: String = WsConfig.PORT_DEFAULT
    fun set(host: String, port: String) { ... }
    fun host(): String = host
    fun port(): String = port
    fun wsUrl(): String = "ws://$host:$port/ws"
    fun restBaseUrl(): String = "http://$host:$port"
    fun validate(host: String, port: String): String? { ... }   // 明确拒绝含 "://"
```

`core/core-storage/.../DeviceStore.kt`：`SERVER_HOST` / `SERVER_PORT` 双 key，
`serverHost()` / `serverPort()` / `saveServerAddress(host: String, port: String)`。

`app/src/main/java/my/bridge/com/di/NetworkModule.kt`：

```kotlin
            loadServerAddress = {
                val host = deviceStore.serverHost()
                val port = deviceStore.serverPort()
                if (host != null || port != null) {
                    ServerAddress.set(
                        host ?: ServerAddress.host(),
                        port ?: ServerAddress.port(),
                    )
                }
            },
```

`ConnectionViewModel.kt`：`_serverHost` / `_serverPort` 双 StateFlow；
`saveServerAddress(host, port)`、`testServerConnection(host, port, onResult)`；
init 里 `_isFirstRun.value = savedHost == null`。

`SettingsPages.kt`：`AddressEditDialog(initialHost, initialPort, ..., onConfirm: (String, String) -> Unit)`
双输入框，placeholder `如 10.0.2.2 或 example.com` / `如 15160`。

`FirstRunSetupScreen.kt`：`var host = mutableStateOf("")` + `var port = mutableStateOf("15160")`
+ 两个 OutlinedTextField + `testServerConnection(host, port)`。

---

## §3 改后状态（已回填）

| # | 文件 | 改后 |
|---|---|---|
| 1 | `server/config.yaml` | 值不变（用户已改生产值），补三行环境注释：生产 `https://…:15443` / 测试 `http://039039.xyz:15160` 或 `http://192.168.31.226:15160`，并说明测试环境二维码由后端 15160 提供、不走 Vite 5173 |
| 2 | `AdminPrefs.kt` | 新增 `DEFAULT_ADMIN_URL = "https://appclient.039039.xyz:15443"`；`url()` 未配置/清空时回落默认值；注释标测试 `http://192.168.31.226:5173` |
| 3 | `AdminWebPage.kt` | placeholder 改为默认 URL，`supportingText` 标生产/测试两种取值 |
| 4 | `WsConfig.kt` | `HOST_DEFAULT`/`PORT_DEFAULT` → `BASE_URL_DEFAULT`（生产 https）；`WS_URL_DEFAULT` = `wss://…:15443/ws`；`REST_BASE_URL_DEFAULT` = `https://…:15443/`；注释标测试 `http://039039.xyz:15160` |
| 5 | `ServerAddress.kt` | 重写为单值 `scheme://host[:port]`；`set(url)` / `baseUrl()` / `normalize()` / `wsUrl()`（https→wss）/ `restBaseUrl()`（尾 `/`）/ `validate(url)` 强制带协议、禁路径参数片段 |
| 6 | `DeviceStore.kt` | 新增 `server_base_url` key；`serverAddress()` 兼容老双 key 自动补 `http://`，过渡态 `appclient.039039.xyz:15080` 一次性收敛到 `https://…:15443`；`saveServerAddress(baseUrl)`；老 key 保留不清 |
| 7 | `NetworkModule.kt` | `loadServerAddress = { deviceStore.serverAddress()?.let { ServerAddress.set(it) } }` |
| 8 | `ConnectionViewModel.kt` | 双 StateFlow → `_serverAddress`；init 用 `deviceStore.serverAddress()`；`saveServerAddress(url)` / `testServerConnection(url, onResult)`；探测失败恢复原地址 |
| 9 | `SettingsPages.kt` | 单输入框；显示完整 URL；弹窗 `onConfirm: (String) -> Unit`；说明文字与提示均标生产/测试取值 |
| 10 | `FirstRunSetupScreen.kt` | 单输入框预填 `WsConfig.BASE_URL_DEFAULT`；`supportingText` 标生产/测试；`testServerConnection(address)` |
| 11 | `environment-matrix.md` | 重写：三处对照表 + 端口语义（按 18:0x 实测修订）+ 迁移说明 + 切换步骤 |
| 12 | `bridge-admin.conf` | 头部补环境对照、证书实测结论、15080 301 说明；**server 级 301 保留**（设备端已直连 15443） |
| 13 | `CHANGELOG.md` | 修正脉络6 标题误拼行；新增脉络7 |

## §4 回滚配方

```bash
# 1) 已跟踪文件：逐个回退到改前（提交前用）
git checkout -- \
  server/config.yaml \
  app/src/main/java/my/bridge/com/admin/AdminPrefs.kt \
  app/src/main/java/my/bridge/com/admin/AdminWebPage.kt \
  core/core-network/src/main/java/my/bridge/com/core/network/ws/WsConfig.kt \
  core/core-network/src/main/java/my/bridge/com/core/network/ws/ServerAddress.kt \
  core/core-storage/src/main/java/my/bridge/com/core/storage/DeviceStore.kt \
  app/src/main/java/my/bridge/com/di/NetworkModule.kt \
  app/src/main/java/my/bridge/com/ui/ConnectionViewModel.kt \
  app/src/main/java/my/bridge/com/ui/FirstRunSetupScreen.kt \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
  docs/CHANGELOG.md

# 2) 未跟踪新文件：删除
rm -f docs/admin/bridge-admin.conf docs/admin/environment-matrix.md docs/dev/deploy-defaults-ledger.md

# 3) ⚠️ server/config.yaml 的 public_base_url 在本次改动前用户已手工改为
#    https://appclient.039039.xyz:15443（未提交），git checkout 会把它退回
#    http://039039.xyz:15160 —— 若只想回滚「本次注释」而保留该值，改完手工补回这一行。
```

## §5 触发回滚的信号

- 设备端「设置 → 连接」显示 `appclient.039039.xyz:15080` 但握手/在线状态一直失败，
  且 `nginx access.log` 里 15080 出现大量 301 → 说明 conf 的端口策略没生效或设备端仍被跳转。
- 客户端「管理」Tab 白屏 → 自签证书问题（非本次代码回滚能解，需换可信证书或把 admin URL 改 15080 明文）。
- 扫码下载二维码指向的地址打不开 → 检查 `public_base_url` 与 nginx `location = /d`。
- 首次安装引导页预填地址连不上 → 检查 `WsConfig.HOST_DEFAULT/PORT_DEFAULT`。

## §6 编译与验收

- `./gradlew :app:compileDebugKotlin --no-daemon` → **BUILD SUCCESSFUL in 6m22s**（96 tasks，1 executed / 95 up-to-date），仅既有 deprecation 警告，无 `e:` 错误；迁移特例改动后增量复编另见提交前记录。
- 全局 grep 旧 API（`serverHost|serverPort|HOST_DEFAULT|PORT_DEFAULT|ServerAddress.host()|validate(host, port)`）→ 仅 `FirstRunSetupScreen` 一处注释残留，已改为 `DeviceStore.serverAddress()`。
- Python 等价验证（`validate` / `wsUrl` / 迁移规则）：
  - `https://appclient.039039.xyz:15443` → OK → `wss://appclient.039039.xyz:15443/ws`
  - `http://039039.xyz:15160` → OK → `ws://039039.xyz:15160/ws`
  - 裸 `appclient.039039.xyz:15080` / 带路径 / 带查询 / `ftp://` / 空串 → 全部按预期拒绝
  - 迁移：老 `appclient.039039.xyz:15080` → `https://…:15443`；老 `039039.xyz:15160` → `http://039039.xyz:15160`；新 key 原样返回；无数据 → null（判定首次安装）
- 生产实测：证书 `Let's Encrypt / CN=*.039039.xyz`、`SSLVerify=0`；15080 所有路径（含 POST）均 301；用户 18:07 服务端日志 tts 播报命令回执 `success`。
- **待真机验证**：升级后服务地址自动变为 `https://appclient.039039.xyz:15443`（迁移命中）、不弹首次配置引导页、主 WS 走 `wss` 直连 15443、命令回执正常；改测试地址 `http://039039.xyz:15160` 后能连本机后端。
