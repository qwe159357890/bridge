# 构建与发布链路

> 本文档描述 Bridge 从「写代码 → 推 GitHub → GitHub 打包 → 下载 APK 到本地」的**全自动闭环**。
> **你只需提供一个 GitHub token，其余（建仓库、push、打包、下载）全由 AI 自动完成。**
> 当前阶段为**内部测试**，APK 用 debug 签名（无需应用签名密钥）。

## 一、链路总览

```
你提供 1 个 GitHub token（PAT）
        ↓
AI 全自动：
  ① 用 GitHub API 自动创建仓库（你无需手动建）
  ② git push 代码（token 鉴权，无需密码）
  ③ 打 tag 触发 GitHub Actions 打包（debug 签名）
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
python tools/release/release.py          # 自动建仓库(若没有) + 打 v0.1.0
python tools/release/release.py v0.1.1   # 指定版本号
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
| **内部测试（当前）** | debug 签名 | 零配置，APK 直接可装；不能上架商店 |
| 正式发布（未来） | 正式 keystore / Play App Signing | 需配置签名 |

## 七、未来上架（需要时再启用）

参考 `references/aurelay-main` 的做法：

1. **仅 GitHub Release 放正式 APK**：生成 keystore → `app/build.gradle.kts` 加 `signingConfigs.release` → GitHub Secrets 存 `KEYSTORE_BASE64`。
2. **上架 Google Play（aurelay 同款）**：Play App Signing + fastlane，只需一个服务账号 JSON，之后全自动。

## 八、安全注意事项

1. `references/`（32MB 第三方源码）不入库，仅本地保留。
2. `github.env`、`key.properties`、`*.keystore`、`*.jks` 均被 `.gitignore` 排除，绝不提交。
3. `releases/` 下载产物不入库。
4. CI 版本：JDK 17 + Gradle 8.13，与项目 `compileOptions/JVM 17`、AGP 8.13.1 严格匹配。
