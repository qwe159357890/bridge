# 构建与发布链路

> 本文档描述 Bridge 从「写代码 → 推 GitHub → GitHub 打包 → 下载 APK 到本地」的**全自动闭环**。
> 当前阶段为**内部测试**，APK 用 debug 签名（无需应用签名密钥）。

## 一、链路总览

```
写代码 ──► AI 自动 git push（用 GitHub token 鉴权）
         ──► 打 tag v* ──► GitHub Actions 自动打包（debug 签名）
         ──► AI 用代理下载 APK 到本地 releases/
```

**你只需提供一个 GitHub token**，之后收到指令/有必要时，AI 就能一条命令完成全流程。

## 二、一次性配置（只需做一次）

### 1. 建 GitHub 空仓库

在 GitHub 网页新建空仓库（不要勾选 README/.gitignore）。

### 2. 生成 GitHub Token（PAT）

1. 打开 <https://github.com/settings/tokens> → **Generate new token (classic)**；
2. 勾选权限：`repo`（完整读写）、`workflow`（触发 Actions）；
3. 生成后复制 `ghp_...` 开头的 token。

### 3. 填写本地配置

```bash
cp github.env.example github.env
```

编辑 `github.env` 填入真实值：

| 字段 | 值 |
|---|---|
| `GITHUB_USER` | 你的 GitHub 用户名 |
| `GITHUB_REPO` | 仓库名（不含用户名前缀） |
| `GITHUB_TOKEN` | 上一步生成的 `ghp_...` token |

> 安全：`github.env` 已被 `.gitignore` 排除，**绝不提交到仓库**。

## 三、一键发布（日常使用）

```bash
python tools/release/release.py          # 自动打 v0.1.0
python tools/release/release.py v0.1.1   # 指定版本号
```

脚本自动完成：配置 remote → 提交 push → 打 tag → 轮询等待打包 → 下载 APK 到 `releases/`。

## 四、手动触发（备用）

```bash
git tag -a v0.1.0 -m "内部测试 v0.1.0"
git push origin v0.1.0
```

或到 GitHub **Actions → Build Internal Test APK → Run workflow** 手动触发。

## 五、APK 产物

| 位置 | 说明 |
|---|---|
| 本地 `releases/` | 脚本自动下载到这里 |
| GitHub Release | tag 触发时自动创建，附 APK |
| Actions Artifact | `bridge-debug-apk` |

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
