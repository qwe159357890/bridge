# 构建与发布链路

> 本文档描述 Bridge 的**打包与发布**全流程。
> 核心原则：**无论哪种打包方式，只要是「打包」操作，就必须确保本地与远程（GitHub）代码完全一致。**

---

## 一、打包话术约定（最高优先级，2026-11-05 定版）

用户口中「打包」一词，按**默认值**区分三种含义。判断时以此表为准：

| 用户说的话 | 实际含义 | 执行动作 |
|-----------|---------|---------|
| **「打包」**（默认，无修饰） | 本地打包 + 推送到 GitHub 仓库 + 执行仓库打包 | 本地 `assembleDebug` → 推代码到 GitHub → 触发 GitHub Actions（打 tag + dispatch，Actions 内自动 `assembleDebug` 并**自动创建 Release + 上传 APK**）→ 下载 APK → **落地 `server/data/updates/` + 更新 `update.json` + 唯一留存** |
| **「本地打包」** | 只做本地打包 | 仅 `assembleDebug`，出本地 APK，**不**推 GitHub、**不**跑 Actions → 落地 `server/data/updates/` + 更新 `update.json` + 唯一留存 |
| **「远程打包」/「仓库打包」/「GitHub 打包」** | 远程/仓库/GitHub 打包后下载到本地 | 走 GitHub Actions 打包（Actions 内自动 `assembleDebug` 并**自动创建 Release + 上传 APK**）→ 下载 APK 到本地 |

### ★ 「部署到远程服务器」话术约定（2026-11-13 定版）

> 用户说「**部署到远程服务器**」（或「部署」「上线」「发到服务器」等同类说法）时，**统一指下面的 v0.2.135 流程**，目标机器就是**魔百盒（mbh）**，不再泛指其它机器或其它流程。

**默认流程（v0.2.135 流程，已固化）**：

```
① 本地打包（assembleDebug）→ 落地 server/data/updates/bridge-vX.Y.Z.apk
② 回填 update.json 的 sha256 / size / released_at（打包后现算）
③ 唯一留存：删本地 updates/ 旧版本 APK + app/build/.../app-debug.apk
④ SSH 部署到魔百盒（LAN 192.168.31.30，别名 mbh，免密可用）：
   a. 备份远程旧版：mv bridge-v旧.apk .bak/  +  cp update.json .bak/update.json.v旧
   b. scp 新 APK + update.json → /home/qwe159357/software/appClient/server/data/updates/
   c. 校验远程 sha256 与本地一致
   d. 重启 supervisor：sudo supervisorctl restart appclient:appclient-server
⑤ 验证：服务 RUNNING；目录第一层只剩最新 APK（旧版已进 .bak/）
```

**关键事实（避免再踩坑）**：
- 目标机器 = 魔百盒（mbh，aarch64 / Armbian，LAN `192.168.31.30`，SSH 账号 `qwe159357 / qwe951753`，免密可用）。**不是**写代码机、不是华合/淘买等其它机器。
- 远程部署路径：`/home/qwe159357/software/appClient/server/data/updates/`（与本地 `server/data/updates/` 同构）。
- 服务端 check_update 接口是 `GET /api/updates/latest`，**扫描目录取文件名版本最高的 `.apk`**（`.bak` 子目录不会被扫）；`update.json` 的 `version`/`notes` 用于对外展示与 meta 版本。旧 APK 必须移到 `.bak/` 子目录（不能原地改名留着，否则会被扫描逻辑误判）。
- 服务由 supervisor 托管：`appclient:appclient-server`（改 update.json / 换 APK 后需 `supervisorctl restart` 重新加载）。
- 本约定由 2026-11-13 的 v0.2.136 发版固化，此前的 v0.2.135 已按同一流程部署过一次（见 `docs/CHANGELOG.md` v0.2.135 里程碑「部署」段）。

### ★ 贯穿所有打包方式的铁律

> **只要是「打包」操作，就必须确保本地和远程（GitHub）代码完全一样。**

- 打包前先核对：本地 `versionName` 与 GitHub 远程 `main` 的 `versionName` 是否一致；不一致先同步（见第六节）。
- 绝不允许「本地是 A、GitHub 是 B」却打了包——这正是 2026-11-05 之前的坑：本地已 0.2.50、GitHub 停在 0.2.44，且缺 0.2.45~0.2.50 六个 Release。
- **★ 唯一留存铁律（2026-11-05 新增）**：任何打包方式产出 APK 后，最终必须**只保留 `server/data/updates/bridge-vX.Y.Z.apk` 唯一一份**（见 §3.5），其余位置（`app/build/...`、`releases/`、`updates/` 旧版本）全部删除。落地 + 更新 `update.json` + 唯一留存三者缺一不可。

---

## 二、前置条件（一次性配置）

### 2.1 GitHub Token

1. 打开 <https://github.com/settings/tokens> → **Generate new token (classic)**；
2. 勾选权限：`repo`（完整读写）、`workflow`（触发 Actions）；
3. 生成后复制 `ghp_...` 开头的 token；
4. 把 token 写入**项目根目录 `gh_token.txt`**（内容就是 token 本身，不加引号），该文件已在 `.gitignore`。

> **★ 铁律：token 绝不走对话传递**（实测经聊天/工具参数传会被掩码替换导致 401），必须由文件磁盘直读。

### 2.2 签名（内部测试阶段）

- 内部测试用**固定 debug 签名**：`app/bridge-debug.keystore`（别名 `androiddebugkey` / 密码 `android`，PKCS12）已入库，本机与 CI 共用同一份，保证覆盖安装不报「签名冲突」。
- 正式发布（未来）才换正式 keystore / Play App Signing，见 §八。

---

## 三、「打包」（默认全链路）

默认「打包」= 完整闭环，四步 + 收尾：

```
① 本地 assembleDebug（快速验证 + 版本号 aapt 校验）
② 推代码到 GitHub（API 通道优先，见 §六）
③ 触发 GitHub Actions 打包（打 tag + dispatch）
   └─ Actions 内 release.yml：assembleDebug → upload-artifact →（打 tag 时）softprops/action-gh-release 自动创建 Release 并上传 APK
④ 下载 APK 到本地
⑤ ★ 收尾：落地 updates/ + 更新 update.json + 唯一留存（见 §3.5）
```

> ★ 关键：**Release 的创建与 APK 上传是 Actions 自动完成的**（`release.yml` 里的 `softprops/action-gh-release@v2` 在打 tag 时自动 `POST /releases` 并附 APK），**不是人工手动上传**。
> 只有「回溯补历史版本的 Release」（见 §6.5）才需要手动 `POST /releases` + 上传本地 APK 资产。

### 3.1 本地打包

```bash
JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1+1" ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 3.2 推代码到 GitHub

git 协议常不可用，**API 通道优先**（详见 §六）：

```bash
# 连通性探测（先做）
for u in "https://api.github.com/user" "https://github.com"; do
  echo "$u -> $(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 12 "$u")"
done
# api.github.com 返回 401（未带 token）即通；github.com 返回 000 即 git 协议不可用

# API 通道提交（token 从 gh_token.txt 读）
python "C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py" --repo qwe159357890/bridge --branch main \
  push "提交信息" "仓库路径=本地路径" ...
```

### 3.3 触发 GitHub Actions 打包

```bash
# 1. 建 tag
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge tag v0.2.51
# 2. 手动派发工作流（★ 建 tag 不会自动触发 on:push:tags，必须 dispatch）
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge dispatch release.yml v0.2.51
# 3. 轮询等打包完成（run success 后 Release + APK 已由 Actions 自动生成）
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge runs --limit 1 --detail
```

> ★ run 成功后，Release（含 APK 资产）已经由 `softprops/action-gh-release` 自动创建好了，**无需再手动上传 Release 资产**。直接进入 §3.4 下载即可。

### 3.4 下载 APK

```bash
# ★ 铁律：走 gh-proxy 代理，绝不用 API 直连（大文件会 SIGTERM 超时）
curl -L -o releases/app-debug-v0.2.51.apk \
  "https://v4.gh-proxy.org/https://github.com/qwe159357890/bridge/releases/download/v0.2.51/app-debug.apk"
# 下载后比对 size 校验完整性（stat -c%s vs Release asset 的 size 字段）
```

### 3.5 ★ 收尾：落地 updates/ + 更新 update.json + 唯一留存（必须执行）

本地打包一旦完成，**立即**执行以下三件事，缺一不可：

**① 落地到 check_update 目录（唯一权威留存位置）**

```bash
# 把本次 APK 复制/移动到 server/data/updates/（命名 bridge-vX.Y.Z.apk）
cp app/build/outputs/apk/debug/app-debug.apk server/data/updates/bridge-v0.2.51.apk
```

**② 更新 update.json，通知手机端「有更新」**

把 `server/data/updates/update.json` 的 `version` 改成 `0.2.51`、`notes` 写本次改动说明。

> ⚠️ 铁律：APK 与 update.json **必须同步**。只拷 APK 漏改 update.json，手机端会判「已是最新」，升级链全断。

**③ 比对各处结果一致后，唯一留存**

- 先校验 `updates/bridge-v0.2.51.apk` 与 `releases/`、`app/build/outputs/apk/debug/` 里的副本 **size 一致**（必要时 aapt 比对 versionName）。
- 确认一致后，**删除其它所有位置的 APK**，只保留 `server/data/updates/` 下这一份：
  - 删 `app/build/outputs/apk/debug/app-debug.apk`（Gradle 编译产物）
  - 删 `releases/app-debug-vX.Y.Z.apk`（下载的 Release 资产）
  - 删 `server/data/updates/` 里**旧版本**的 `bridge-vX.Y.Z.apk`（历史版本）
- 最终全项目**只存在唯一一个 APK**：`server/data/updates/bridge-v0.2.51.apk`。

> 理由：APK 体积大（~45MB），多处副本既浪费磁盘又易造成「手机装的是 A、盘里剩的是 B」的混淆。唯一留存让「装机用哪个文件」没有歧义。

---

## 四、「本地打包」

只做本地打包，**不推 GitHub、不跑 Actions**：

```bash
JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1+1" ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- 适用于：快速验证迭代、未确定是否入库的改动。
- 版本号校验用 aapt（别比对文件大小，假验证）：
  `D:/Tool/androidsdk/build-tools/35.0.0/aapt.exe dump badging <apk> | head -2`

### 4.1 ★ 本地打包的收尾（同样必须执行）

本地打包完成后的落地 + 唯一留存规则，**与 §3.5 完全相同**：

1. 把 APK 复制到 `server/data/updates/bridge-vX.Y.Z.apk`；
2. 同步更新 `update.json`（version + notes），通知手机端可更新；
3. 比对各处结果一致后，删除其它所有位置的 APK（`app/build/.../app-debug.apk`、`releases/...`、`updates/` 旧版本），**只保留 `server/data/updates/bridge-vX.Y.Z.apk` 唯一一份**。

> ⚠️ 注意：本地打包 ≠ 入库。本地打包后代码只在你本机，GitHub 不会更新。若要「本地=远程」，仍需执行 §六 的同步。

---

## 五、「远程打包」/「仓库打包」/「GitHub 打包」

走 GitHub Actions 打包后下载到本地。**前提**：本地代码已同步到 GitHub（否则打包的是远程旧代码，违反铁律）。

```bash
# 1. 确保本地=远程（见 §六，先同步）
# 2. 打 tag + 触发打包
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge tag v0.2.51
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge dispatch release.yml v0.2.51
# 3. 等打包 + 下载
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge runs --limit 1 --detail
curl -L -o releases/app-debug-v0.2.51.apk "https://v4.gh-proxy.org/https://github.com/qwe159357890/bridge/releases/download/v0.2.51/app-debug.apk"
```

---

## 六、git 网络策略与「本地=远程」同步

### 6.1 网络策略（优先级）

| 优先级 | 路径 | 说明 |
|---|---|---|
| ①（首选） | **API 通道**（`api.github.com` 最稳） | `gh_api.py`，curl 需 `--noproxy '*'` |
| ② | 清代理直连 | `env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy git fetch/push` |
| ③（兜底） | 默认代理 | 直接 `git fetch/push`，多试几次 |

### 6.2 同步本地=远程（两种方向）

**方向 A：本地改动 → 推到远程**（本地领先时）

```bash
# 找出本地改了、远程没改的文件
git diff --name-only $(git merge-base main origin/main) main
# 用 gh_api.py push 把这些文件的最新内容推到远程（Git Data API 一次性提交）
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/gh_api.py --repo qwe159357890/bridge push "sync" "路径=路径" ...
```

**方向 B：远程改动 → 追平本地**（远程领先时）

```bash
# 清代理直连 fetch（规避沙箱代理间歇 502）
env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy git fetch origin main
git reset --hard origin/main
```

> ⚠️ fetch 失败绝不能 reset（会删文件回退到过期 ref）。用 `&&` 串联并校验 ref 前进：
> `git fetch origin main && [ "$(git rev-parse --short origin/main)" = "<目标sha>" ] && git reset --hard origin/main`

### 6.3 API 通道踩坑（务必记住）

1. **`POST /git/refs` 建 tag 不触发 `on:push:tags` 工作流**：先建 tag，再 `dispatch` 指定 `ref=标签名` 手动触发。
2. **改动多文件（含二进制 keystore）用 `push` 一次提交**（Git Data API：blob → tree → commit → PATCH ref）。
3. **改 tag 指向新提交**：先 `DELETE /repos/{repo}/git/refs/tags/vX`，再重建 tag，再 dispatch。
4. **API 提交与本地 git 历史分叉**：API push 基于「远程 HEAD」新造提交，不并入本地历史，属正常。本地 git 仅作参考。
5. **行尾符差异**：本地 git 存 LF、API 上传 CRLF，`git diff` 会显示整文件重写（假差异）。用 `git diff --ignore-cr-at-eol --stat` 确认只有行尾差异即可。

### 6.4 上传 Release 资产踩坑

- 上传 APK 到 Release 的端点 `uploads.github.com` **必须直连**（禁用环境变量代理），否则 404。
- 用 `ProxyHandler({})` 直连即可。

### 6.5 回溯补历史版本的 Release（区别于默认「打包」）

默认「打包」链路里，Release 由 Actions 自动生成（见 §3.3）。但当**某个历史版本当时没走 Actions、只走了本地 check_update 装机**，事后要补它的 GitHub Release 时，必须**手动**走下面三步（2026-11-05 补 0.2.45~0.2.50 即此路径）：

```bash
# 1. 建 tag（指向目标提交，通常 = 同步后的远程 HEAD）
python .../gh_api.py --repo qwe159357890/bridge tag v0.2.45
# 2. 建 release（空）
python .../gh_api.py --repo qwe159357890/bridge release v0.2.45
# 3. 上传本地 APK 资产（★ 端点 uploads.github.com 必须 ProxyHandler({}) 直连，否则 404）
python .../gh_api.py --repo qwe159357890/bridge upload-asset v0.2.45 server/data/updates/bridge-v0.2.45.apk
```

> 这条路径**只用于补历史 Release**，不用于常规打包。常规打包一律走 §三 的 Actions 自动上传。

---

## 七、版本号管理（单一来源）

- **版本号只在 `app/build.gradle.kts` 的 `versionName` 维护**（当前 `0.2.50`）。
- 每次发布需手动递增（`0.2.50` → `0.2.51`），并保证 tag 与 versionName 一致。
- 设置页「关于」读 `BuildConfig.VERSION_NAME`（`buildFeatures.buildConfig = true` 自动注入），**不要硬编码版本字符串**。
- 历史教训：早期 versionName 写死不更新，导致手机永远显示旧版本；现已改「单一来源」。

---

## 八、装机三通道（APK 落地到手机）

| 通道 | 适用 | 要点 |
|---|---|---|
| ① USB / WiFi adb | 手机同网 | `tools/adb/platform-tools/adb.exe connect <ip>:5555 && adb install -r <apk>`（PATH 上的 adb 是旧版，必须用项目自带） |
| ② check_update 通道 | 手机只有移动数据 | APK 拷入 `server/data/updates/bridge-vX.Y.Z.apk` + 改同目录 `update.json`（version+notes）→ 手机「检查更新」。**central-server 本机 15160，目录实时生效无需重启** |
| ③ adb over VNT | 仅应急 | 极不稳定，禁止传大文件 |

> 通道 ② 铁律：**APK 与 update.json 必须同步更新**，只拷 APK 漏改 update.json 会判「已是最新」，升级链全断。
>
> **★ 唯一留存铁律**（见 §3.5）：`server/data/updates/` 是 APK 的**唯一权威留存位置**。打包完成后，全项目只保留 `updates/bridge-vX.Y.Z.apk` 一份；`app/build/.../app-debug.apk`、`releases/...`、`updates/` 旧版本全部删除，避免「多份副本、装机来源不明」。

---

## 九、安全注意事项

1. `references/`（32MB 第三方源码）不入库，仅本地保留。
2. `gh_token.txt`、`github.env`、`key.properties`、`*.jks` 均被 `.gitignore` 排除；**例外**：`app/bridge-debug.keystore`（内部测试固定签名）允许入库。
3. `releases/` 下载产物不入库。
4. CI 版本：JDK 17 + Gradle 8.13，与项目 `compileOptions/JVM 17`、AGP 8.13.1 严格匹配。

### 9.1 打包范围 ≠ 提交范围

- **APK 产物**：永远只含 `app/` + `core/` 等 Android 模块。`server/`（Python 后端）与 `admin/`（Vue 后台）是独立服务，不参与 Android 编译、不进 APK。
- **Git 提交**：`git add -A` 会把 server/admin 改动也提交进仓库，但那只是进版本历史，与 APK 内容无关。
- 二者解耦：提交 server/admin 不影响打包结果。

---

## 十、未来上架（需要时再启用）

参考 `references/aurelay-main`：

1. **正式 APK**：生成 keystore → `signingConfigs.release` → GitHub Secrets 存 `KEYSTORE_BASE64`。
2. **上架 Google Play**：Play App Signing + fastlane，一个服务账号 JSON 即可全自动。

---

## 附：验证改动真的编进 APK

用 `axml_dump.py` 解析 APK 内二进制 `AndroidManifest.xml`，确认 `versionName` / `versionCode` / `package` 生效（比对文件大小是假验证）。

```bash
python C:/Users/Administrator/.workbuddy/skills/github-api-automation/scripts/axml_dump.py app-debug.apk
```

---

*完整 API 用法见 `C:\Users\Administrator\.workbuddy\skills\github-api-automation`（含 `gh_api.py`、`axml_dump.py`）。*
