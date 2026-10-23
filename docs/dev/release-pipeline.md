# 构建与发布链路

> 本文档描述 Bridge 从「写代码 → 推 GitHub → GitHub 打包」的完整链路。
> 当前阶段为**内部测试**，只需产出 debug 签名的 APK，**无需任何密钥配置**。

## 一、链路总览

```
本地写代码 ──► git commit ──► git push（GitHub）──► 打 tag v* ──► GitHub Actions 自动打包 ──► 产出 APK（debug 签名）
```

| 环节 | 涉及文件/配置 | 状态 |
|---|---|---|
| 借鉴参考项目 | `references/`（Aurelay / SmsForwarder，不提交仓库） | 已完成 |
| 写代码 | `app/` + `core/`（多模块） | 骨架完成 |
| gradle wrapper | `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`（Gradle 8.13） | 已就绪 |
| CI 打包 | `.github/workflows/release.yml`（debug 签名，无需密钥） | 已就绪 |

## 二、首次推送到 GitHub

```bash
# 1. 在 GitHub 网页新建一个空仓库（不要勾选 README/.gitignore，保持空）
# 2. 关联远程并推送
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

## 三、触发打包（内部测试）

打 tag 即触发打包，产出 debug 签名 APK：

```bash
git tag -a v0.1.0 -m "内部测试 v0.1.0"
git push origin v0.1.0
```

也可以到 GitHub 仓库 **Actions → Build Internal Test APK → Run workflow** 手动触发。

打包产物：
- **Release 页面**：APK（tag 触发时自动创建 Release，附上 APK）；
- **Artifact**：`bridge-debug-apk`（任何触发都上传）。

> debug 签名是 Android 自带的，同事可直接下载安装到手机测试，无需任何密钥。

## 四、为什么当前不用密钥（内部测试阶段）

| 阶段 | 签名方案 | 说明 |
|---|---|---|
| **内部测试（当前）** | debug 签名 | 零配置，APK 直接可装；不能上架商店 |
| 正式发布（未来） | 正式 keystore 或 Play App Signing | 需配置签名，见下文「未来上架」 |

## 五、未来上架（需要时再启用）

当 Bridge 需要正式发布时，有两种路线（参考 `references/aurelay-main` 的做法）：

1. **仅 GitHub Release 放正式 APK**：生成 keystore → 在 `app/build.gradle.kts` 加 `signingConfigs.release` → GitHub Secrets 存 `KEYSTORE_BASE64` 等。
2. **上架 Google Play（aurelay 同款）**：用 Play App Signing + fastlane，只需提供一个服务账号 JSON（`fastlane/google-play-key.json`），之后打包签名上传全自动，开发者无需再操作。

> 到那时再按需扩展，现在保持最简单。

## 六、安全注意事项

1. `references/` 不入库（32MB 第三方源码，仅本地保留）。
2. 正式签名材料（`key.properties`、`*.keystore`、`*.jks`）已被 `.gitignore` 排除，届时绝不提交。
3. CI 版本：JDK 17 + Gradle 8.13，与项目 `compileOptions/JVM 17`、AGP 8.13.1 严格匹配。
