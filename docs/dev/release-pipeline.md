# 构建与发布链路

> 本文档描述 Bridge 从「写代码 → 推 GitHub → GitHub 打包 → 下载 APK 到本地」的**全自动闭环**。
> **你只需提供一个 GitHub token，其余（建仓库、push、打包、下载）全由 AI 自动完成。**
> 当前阶段为**内部测试**，APK 用**固定 debug 签名**（keystore 入库，见第六节）。

## 一、链路总览

```
你提供 1 个 GitHub token（PAT）
        ↓
AI 全自动：
  ① 用 GitHub API 自动创建仓库（你无需手动建）
  ② 提交代码（优先 git push；主站不通时走 API 通道，见第十节）
  ③ 打 tag 触发 GitHub Actions 打包（固定 debug 签名）
  ④ 轮询等待打包完成
  ⑤ 用代理下载 APK 到本地 releases/
```

## 二、你唯一需要做的（一次）

生成一个 GitHub token：

1. 打开 <https://github.com/settings/tokens> → **Generate new token (classic)**；
2. 勾选权限：`repo`（完整读写，含建仓库）、`workflow`（触发 Actions）；
3. 生成后复制 `ghp_...` 开头的 token；
4. 把 token（和你的 GitHub 用户名）告诉 AI，AI 会写入本地配置并完成后续全部操作。

> 仓库由 AI 用 token 自动创建，你**无需在 GitHub 网页手动建仓库**。

## 三、一键发布（日常使用）

```bash
python tools/release/release.py          # 自动递增版本号并打包（如 0.1.0 -> v0.1.1）
python tools/release/release.py v0.2.0   # 指定版本号
```

脚本自动完成：检查/创建仓库 → 配置 remote → push → 打 tag → 轮询等打包 → 下载 APK 到 `releases/`。

## 四、配置文件说明（本地，不入库）

`github.env`（从 `github.env.example` 复制）：

| 字段 | 值 |
|---|---|
| `GITHUB_USER` | GitHub 用户名 |
| `GITHUB_REPO` | 仓库名（默认 `bridge`，AI 自动创建） |
| `GITHUB_PRIVATE` | `true` 私有 / `false` 公开 |
| `GITHUB_TOKEN` | 你的 `ghp_...` token |

> `github.env` 已被 `.gitignore` 排除，绝不提交。

## 五、APK 产物与下载加速（代理）

| 位置 | 说明 |
|---|---|
| 本地 `releases/` | 脚本自动下载到这里 |
| GitHub Release | tag 触发时自动创建，附 APK |
| Actions Artifact | `bridge-debug-apk` |

**下载加速机制**：脚本内置 gh-proxy 代理列表（按优先级逐个尝试，失败自动回退 token 直连）：

| 优先级 | 代理 | 状态 |
|---|---|---|
| 1 | `https://v4.gh-proxy.org` | ✅ 可用 |
| 2 | `https://v6.gh-proxy.org` | ✅ 可用 |
| 3 | `https://gh-proxy.org` | ✅ 可用 |
| 4 | `https://cdn.gh-proxy.org` | ⚠️ 不稳定 |
| 5 | `https://axisnow.gh-proxy.org` | ✅ 可用 |

> **重要**：gh-proxy 代理只对**公开仓库**生效（代理无法携带 token 访问私有仓库）。因此 Bridge 仓库设为**公开**（内部测试 APK 不含敏感信息）。若改为私有，脚本会自动回退到 token 直连 API（能用但可能较慢）。

## 六、签名说明（内部测试阶段）

| 阶段 | 签名方案 | 说明 |
|---|---|---|
| **内部测试（当前）** | 固定 debug 签名（keystore 入库） | 本机与 CI 共用同一份 `app/bridge-debug.keystore`，保证覆盖安装不报「签名冲突」 |
| 正式发布（未来） | 正式 keystore / Play App Signing | 需配置签名 |

> **为什么要固定签名**：Android 覆盖安装要求前后签名一致。早期本机 `~/.android/debug.keystore` 与 GitHub CI runner 自动生成的 debug keystore 不同，导致「签名冲突」无法覆盖安装。现在固定一份 `app/bridge-debug.keystore`（别名 `androiddebugkey` / 密码 `android`，PKCS12 格式）入库，本机与 CI 都用它。

## 七、版本号管理（单一来源）

- **版本号只在 `app/build.gradle.kts` 的 `versionName` 维护**（当前 `0.1.5`）。
- `release.py` 不传 tag 时自动递增 patch 号（`0.1.5` → 打 `v0.1.6`），并**把新版本号写回 build.gradle.kts** 后一并提交，保证 tag 与 APK 版本号始终一致、下次递增起点正确。
- 手动指定 tag 时（`python tools/release/release.py v0.2.0`），需自行同步修改 `versionName`。
- **历史教训**：早期 `versionName` 写死 `"0.1.0"` 从不更新，导致手机显示的版本号永远停在 0.1.0（v0.1.x 只是 APK 文件名的后缀）。现已改为「单一来源 + 自动递增」。

## 八、未来上架（需要时再启用）

参考 `references/aurelay-main` 的做法：

1. **仅 GitHub Release 放正式 APK**：生成 keystore → `app/build.gradle.kts` 加 `signingConfigs.release` → GitHub Secrets 存 `KEYSTORE_BASE64`。
2. **上架 Google Play（aurelay 同款）**：Play App Signing + fastlane，只需一个服务账号 JSON，之后全自动。

## 九、安全注意事项

1. `references/`（32MB 第三方源码）不入库，仅本地保留。
2. `github.env`、`key.properties`、`*.jks` 均被 `.gitignore` 排除，绝不提交；**例外**：`app/bridge-debug.keystore`（内部测试固定签名）允许入库。
3. `releases/` 下载产物不入库。
4. CI 版本：JDK 17 + Gradle 8.13，与项目 `compileOptions/JVM 17`、AGP 8.13.1 严格匹配。

## 十、github.com 主站不通时的 API 通道（重要经验）

**现象**：`git push` 到 `github.com` 常报 `CONNECT tunnel failed, response 502`（代理隧道失败），此时 git 协议完全不可用。

**解决**：改用 `github-api-automation` skill，走 `api.github.com`（REST API）完成全部操作。它不受主站阻断影响。

### 连通性探测（先做）

```bash
for u in "https://api.github.com/user" "https://github.com" "https://codeload.github.com"; do
  echo "$u -> $(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 12 "$u")"
done
```

- `api.github.com` 返回 401（未带 token）即「通」；`github.com` 返回 `000` 即「git 协议不可用」。
- 注意 `--noproxy '*'`：系统代理常拦截这些域名，必须绕过。

### 提交 / 打 tag / 触发打包（`gh_api.py`）

```bash
# token 从磁盘文件读（绝不走对话传递，会被掩码替换导致 401）
python scripts/gh_api.py --repo qwe159357890/bridge --branch main \
  push "提交信息" "仓库路径=本地路径" "另一个路径=本地路径"
python scripts/gh_api.py --repo qwe159357890/bridge --branch main tag v0.1.6
python scripts/gh_api.py --repo qwe159357890/bridge dispatch release.yml v0.1.6
```

**踩坑（务必记住）**：
1. **`POST /git/refs` 建 tag 不会触发 `on: push: tags` 工作流**。必须：先建 tag，再用 `dispatch` 指定 `ref=标签名` 手动触发。
2. **改动多个文件（含二进制 keystore）用 `push` 一次提交**（Git Data API：blob → tree → commit → PATCH ref）。二进制文件自动 base64。
3. **改 tag 指向新提交**：先 `DELETE /repos/{repo}/git/refs/tags/vX`，再重建 tag，再 dispatch。
4. **本地 git 历史与远程（API 提交链）会分叉**：API push 是基于「远程 HEAD」新造提交，不会并入本地 git 历史。属正常现象，后续发布继续走 API 即可，本地 git 仅作参考。

### 查看构建 / 下载 APK

```bash
python scripts/gh_api.py --repo qwe159357890/bridge runs --limit 1 --detail   # 看各步骤 conclusion
python scripts/gh_api.py --repo qwe159357890/bridge download ./releases --match ".apk"
```

- **CI 日志是 zip 压缩包**：`GET /actions/runs/{id}/logs` 返回的是 zip，需 `zipfile` 解压，读 `build/5_Build Debug APK.txt` 定位编译错误。
- **下载大文件别用 API 直连**（会 SIGTERM 超时），改走 gh-proxy 代理（见第五节）：
  ```bash
  curl -L -o releases/app-debug-v0.1.6.apk \
    "https://v4.gh-proxy.org/https://github.com/{owner}/{repo}/releases/download/v0.1.6/app-debug.apk"
  ```
  下载后对比 `stat -c%s` 与 Release asset 的 `size` 字段做完整性校验。

### 验证改动真的编进 APK

用 `axml_dump.py` 解析 APK 内二进制 `AndroidManifest.xml`，确认 `versionName` / `versionCode` / `package` 生效（比对文件大小是假验证）。

---

*本技能完整用法见 `C:\Users\Administrator\.workbuddy\skills\github-api-automation`（含 `scripts/gh_api.py`、`scripts/axml_dump.py`）。*
