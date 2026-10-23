# 构建与发布链路

> 本文档描述 Bridge 从「写代码 → 推 GitHub → GitHub 打包」的完整链路，以及签名密钥的安全管理。

## 一、链路总览

```
本地写代码 ──► git commit ──► git push（GitHub）──► 打 tag v* ──► GitHub Actions 自动打包 ──► 产出签名 APK + Release
```

| 环节 | 涉及文件/配置 | 状态 |
|---|---|---|
| 借鉴参考项目 | `references/`（Aurelay / SmsForwarder，不提交仓库） | 已完成 |
| 写代码 | `app/` + `core/`（多模块） | 骨架完成 |
| gradle wrapper | `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` / `gradle-wrapper.properties`（Gradle 8.13） | 已就绪 |
| 签名配置 | `app/build.gradle.kts` 的 `signingConfigs.release`（读 `key.properties` 或 CI 环境变量） | 已就绪 |
| CI 打包 | `.github/workflows/release.yml` | 已就绪 |
| 密钥安全 | GitHub Secrets（`KEYSTORE_BASE64` 等），`key.properties`/`*.keystore` 已被 `.gitignore` 排除 | 需手动配置 |

## 二、首次推送到 GitHub

```bash
# 1. 在 GitHub 网页新建一个空仓库（不要勾选 README/.gitignore，保持空）
# 2. 关联远程并推送
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

## 三、生成签名密钥（一次性）

```bash
# 生成 keystore（记牢密码与别名，丢失无法恢复，将导致无法更新应用）
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias bridge \
  -keyalg RSA -keysize 2048 -validity 10000

# 本地签名验证：把 release.keystore 放到项目根目录，复制模板填真实值
cp key.properties.example key.properties   # 编辑填入 storeFile/storePassword/keyAlias/keyPassword
```

> 注意：`key.properties` 与 `release.keystore` 已被 `.gitignore` 排除，**绝不能提交到仓库**。

## 四、配置 GitHub Secrets

在仓库 **Settings → Secrets and variables → Actions → New repository secret**，添加以下 4 个：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出（keystore 文件内容 base64 编码） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名（如 `bridge`） |
| `KEY_PASSWORD` | key 密码 |

生成 base64 的命令（Linux/macOS）：

```bash
base64 -w0 release.keystore
```

## 五、触发打包

**方式 A —— 打 tag 发正式版（自动创建 Release）：**

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

**方式 B —— 手动触发：**

在 GitHub 仓库 **Actions → Build Release APK → Run workflow** 手动运行。

打包产物：
- **Release 页面**：签名的 APK（仅 tag 触发时）；
- **Artifact**：`bridge-release-apk`（任何触发都上传，可在 Actions 运行详情下载）。

## 六、安全注意事项

1. **keystore 绝不入库**：`key.properties`、`release.keystore`、`*.jks` 均在 `.gitignore` 中。
2. **密钥丢失不可逆**：换新 keystore 将导致已安装用户无法覆盖升级。
3. **`references/` 不入库**：第三方参考源码（32MB）仅保留本地，避免仓库臃肿与许可证风险。
4. **CI 版本**：`release.yml` 使用 JDK 17 与 Gradle 8.13，与项目 `compileOptions/JVM 17`、AGP 8.13.1 严格匹配。

## 七、后续可扩展

- 按 ABI 拆分 APK（参考 `references/SmsForwarder-main/.github/workflows/` 的 split 方案）；
- 接入 XUpdate/蒲公英等自动分发（参考 SmsForwarder 的 `Weekly_Build.yml`）；
- 接入自动发布到 Google Play / 应用市场。
