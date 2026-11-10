# 生产/测试环境配置对照表（SSOT）

> 建立：2026-11-10（用户拍板三处默认值）
> 修订：2026-11-10 18:10 —— 服务地址改为「完整 URL 单框」；15080 的 301 实测可用，
> 改由设备端默认直连 15443，早期「15080 必须明文、禁止跳转」的判断已撤回。
> 权威定位：本文是「二维码地址 / admin URL / 服务地址」三处环境取值的**唯一对照依据**。
> 代码注释只写要点并指向本文，不双写细节。配套 `docs/admin/bridge-admin.conf`，
> 改前留档台账 `docs/dev/deploy-defaults-ledger.md`。

---

## 一、三处取值对照（★ 核心）

| # | 配置项 | 代码位置 | **生产（nginx 部署）** | **测试（本机 `npm run dev`）** |
|---|---|---|---|---|
| 1 | 二维码对外地址 | `server/config.yaml` → `updates.public_base_url` | `https://appclient.039039.xyz:15443` | `http://039039.xyz:15160` 或 `http://192.168.31.226:15160` |
| 2 | admin URL | `AdminPrefs.DEFAULT_ADMIN_URL` | `https://appclient.039039.xyz:15443` | `http://192.168.31.226:5173` |
| 3 | 服务地址 | `WsConfig.BASE_URL_DEFAULT` / `ServerAddress` / `DeviceStore.server_base_url` | `https://appclient.039039.xyz:15443` | `http://039039.xyz:15160` 或 `http://192.168.31.226:15160` |

> **三处都必须带协议**（`http://` 或 `https://`）。运行时覆盖方式：
> - #1 改 `server/config.yaml` 后**需重启后端**；
> - #2 客户端「管理」页点地址栏 →「改地址」；
> - #3 客户端「设置 → 连接 → 服务地址」，保存后自动重连。

## 二、端口语义（2026-11-10 18:0x 实测修订）

| 端口 | 协议 | 用途 | 谁在用 |
|---|---|---|---|
| **15443** | HTTPS（Let's Encrypt 受信任证书） | admin 静态 + 全部反代；设备端 `wss`/`https` 直连 | PC 浏览器、客户端 WebView、**设备端主 WS** |
| **15080** | HTTP，server 级 `301 → https:15443` | 浏览器 http 入口自动跳 https | 仅浏览器（设备端 v0.2.130 起不再使用） |
| 15160 | HTTP | 后端 uvicorn 直连（`server/config.yaml`） | 测试环境；生产仅 nginx 上游 |
| 5173 | HTTP | Vite dev server | 仅测试环境 admin |

**实测证据（推翻早期判断，勿再按旧结论设计）**：

- **证书受信任**：`issuer=Let's Encrypt / CN=YR1`，`subject=CN=*.039039.xyz`，有效至 2027-01-20；
  `curl https://appclient.039039.xyz:15443/` → `HTTP 200 / SSLVerify=0`。**不是自签**，
  WebView 与 OkHttp 均可直连，无需 `onReceivedSslError` 放行或自定义 TrustManager。
- **301 覆盖所有路径**：实测 15080 上 `/`、`/health`、`/api/devices`、`/api/download/info`、
  `/d`、`/ws`、`/static/qrcode.js` 以及 POST 探针，全部 `301 → https://…:15443/...`（server 级 `if`）。
- **用户实证（18:07 服务端日志）**：设备端服务地址填 `appclient.039039.xyz:15080` 时，
  `tts` 播报命令正常下发、设备回执 `success（已播报 3 字）` → OkHttp 跟随 301 到
  `wss://…:15443/ws` 且 TLS 握手成功，**链路本来就是通的**。
- **那为什么还要改**：301 是「多一跳 + 依赖客户端重定向行为」——OkHttp 对 301/302
  会把非 GET 请求改成 GET 并丢 body，`POST /api/devices/register` 等上报可能静默失败
  （`onBeforeHello` 对注册失败有 catch 容忍，所以播报仍成功）。v0.2.130 让服务地址
  带协议、默认直连 `https://…:15443`，**是收敛而非救火**：去掉这一跳、统一端口语义、
  不再赌重定向行为。

## 三、服务地址格式（v0.2.130 起）

- **存储**：`scheme://host[:port]` 单值，DataStore key `server_base_url`。
- **推导**：`ServerAddress.wsUrl()` 按 `https→wss` / `http→ws` 拼 `/ws`；
  `restBaseUrl()` 直接作为 Retrofit baseUrl（以 `/` 结尾）。
- **校验**：`validate()` 强制 `http(s)://` 开头，禁止路径、查询参数、片段、用户名密码。
- **老数据迁移**：`DeviceStore.serverAddress()` 读不到新 key 时，把老 `server_host` +
  `server_port` 补 `http://` 拼回完整 URL；`ConnectionViewModel` 的
  `_isFirstRun = saved == null` 不会误判 → **升级后不弹首次配置引导页**。
  老 key 保留不清除（回滚到旧版本仍可读取）。
- **顺带修正**：`testServerConnection()` 探测失败时会恢复原 `ServerAddress`，
  不再把失败地址遗留在运行时。

## 四、切换环境的步骤

**切到测试（本机开发）**
1. `server/config.yaml`：`public_base_url` 改 `http://039039.xyz:15160`（或测试机 LAN IP:15160），重启 `uv run main.py`。
2. 客户端「管理」页改地址为 `http://192.168.31.226:5173`（Vite dev，`/api` 由 vite proxy 转 15160）。
3. 客户端「设置 → 连接 → 服务地址」改 `http://039039.xyz:15160`（单框完整 URL）。
4. nginx 不参与，可不开。

**切到生产（服务器 nginx）**
1. `cd admin && npm run build` → 把 `admin/dist/` 上传 `/home/qwe159357/software/appClient/admin/dist/`。
2. `server/config.yaml`：`public_base_url` 改回 `https://appclient.039039.xyz:15443`，重启后端。
3. 部署 `docs/admin/bridge-admin.conf`（`nginx -t && nginx -s reload`）。
4. 客户端「管理」页填 `https://appclient.039039.xyz:15443`；
   「设置 → 连接 → 服务地址」填 `https://appclient.039039.xyz:15443`（设备端走 `wss` 直连）。

## 五、格式与联动约束（勿踩）

- **#1 #2 #3 都必须带协议**：`AdminPrefs.isValid()` 校验 `^https?://`；
  `ServerAddress.validate()` 强制 `http(s)://`，填裸 `host:port` 会被判非法。
- **#1 是二维码里 `/d` 短链的前缀**：nginx 必须反代 `location = /d`（46MB APK 流式下发），
  且 `public_base_url` 含 `:15443` 时扫码才不会落到默认 443。
- **二维码推导**：`updates.py::_public_base_url()` 优先读 `public_base_url`；
  留空才按 `X-Forwarded-Proto` + `X-Forwarded-Host`（conf 传 `$http_host`，含端口）推导。
- **15080 的 301 建议保留**：浏览器访问 http 自动跳 https 是合理行为；
  设备端已不走 15080，不受影响。
