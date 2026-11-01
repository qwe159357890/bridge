# VNT 组网集成 · 改前留档台账（v0.2.30 / P1+P2）

> **约定来源**：`MEMORY.md` ★ 改前留档约定（2026-11-01 用户确立，优先级最高）——
> 任何修改前**先逐字固化「改之前」的状态**，改完再回填「改之后」，目标是出错时能 **100% 完美恢复**。
> 尤其 **git 未跟踪的文件，git 兜不住**，必须在本台账登记路径与删除配方。
>
> **git 基线**：`130abbc`（`docs: 清空 TodoList（v0.2.22~v0.2.29 全部验证完成）…`，versionName `0.2.29`）。
> 本台账只覆盖 **P1+P2 本轮**改动；P0 已完成的产物（so / 校验脚本 / 调研报告 / 原型 / INDEX 登记）
> 属上一轮成果，其状态单列于 §三。
>
> **回滚总配方**（全部既有文件）：
> ```bash
> git checkout 130abbc -- \
>   app/src/main/java/my/bridge/com/ui/MainScreen.kt \
>   app/src/main/java/my/bridge/com/ui/TabOrderPrefs.kt \
>   app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
>   app/src/main/java/my/bridge/com/AppExit.kt \
>   app/src/main/java/my/bridge/com/di/CommandModule.kt \
>   core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt \
>   app/src/main/AndroidManifest.xml \
>   app/build.gradle.kts app/proguard-rules.pro \
>   server/app/core/command_catalog.py \
>   docs/api/commands/system.md docs/api/commands/README.md \
>   docs/dev/crash-ledger.md docs/dev/heartbeat-inventory.md \
>   docs/CHANGELOG.md docs/INDEX.md
> ```
> 新增文件按 §二 逐个删除（git 未跟踪，`git checkout` 无法恢复）。
>
> **触发回滚的信号**（任一出现即回滚）：
> 1. `:app:compileDebugKotlin` 或 CI 打包失败且 30 分钟内无法定位；
> 2. 真机首次启动 `UnsatisfiedLinkError` / `startForeground` 崩溃循环（40~50s 间隔同签名）；
> 3. 主页 4 个原有 Tab 出现白屏 / 排序丢失 / 自定义名称被清空；
> 4. 退出应用后进程被重新拉起（START_STICKY 未先 stopService）；
> 5. 用户明确要求撤销 VNT 集成。
>
> **状态**：🟢 已完成（改前已固化、改后已回填 §四；`compileDebugKotlin` + `assembleDebug` 均 BUILD SUCCESSFUL）。
> 说明：实现期对**新增文件** `app/src/main/java/com/vnt/VntConfig.java` 做了一处 Android 适配
> （见 §二 末尾），属「新增文件的内容」，回滚仍按 §二 整目录删除即可。

---

## 〇、改动范围清单

| # | 既有文件（git 已跟踪，可 checkout 回滚） | 改动性质 |
|---|---|---|
| 1 | `app/src/main/java/my/bridge/com/ui/MainScreen.kt` | HomePage 分发加 `4 -> VntPage()` |
| 2 | `app/src/main/java/my/bridge/com/ui/TabOrderPrefs.kt` | 4→5 项 + 旧值迁移 |
| 3 | `app/src/main/java/my/bridge/com/ui/SettingsPages.kt` | 4 处硬编码 4 → 跟随 `PAGE_TITLES.size` |
| 4 | `app/src/main/AndroidManifest.xml` | 加 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `VntVpnService` 声明 |
| 5 | `app/build.gradle.kts` | `defaultConfig` 加 `ndk { abiFilters += "arm64-v8a" }` |
| 6 | `app/proguard-rules.pro` | 加 `-keep class com.vnt.** { *; }` |
| 7 | `app/src/main/java/my/bridge/com/AppExit.kt` | import + `kill()` 先 `stopService(VntVpnService)` |
| 8 | `core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt` | 加 3 个常量 |
| 9 | `app/src/main/java/my/bridge/com/di/CommandModule.kt` | 加 3 个执行器 + 3 个函数 |
| 10 | `server/app/core/command_catalog.py` | 加 3 条 `_cmd()` |
| 11 | `docs/api/commands/system.md` | 加 3 个命令章节 |
| 12 | `docs/api/commands/README.md` | system 表加 3 行 |
| 13 | `docs/dev/crash-ledger.md` | §3.1 登记新调用点；§6 状态 |
| 14 | `docs/dev/heartbeat-inventory.md` | §1.2 登记 VNT 黑盒常驻连接 |
| 15 | `docs/INDEX.md` | 报告状态「未实现」→「已实现」 |
| 16 | `docs/research/vnt-mesh-integration.md` | 头部状态 + §11 清单回填 |
| 17 | `docs/CHANGELOG.md` | 新增 v0.2.30 里程碑 |
| 18 | `assets/design/ui-mockup-vnt-mesh.html` | 页脚要点回填（P0 已完成 → P1/P2 已完成） |

---

## 一、既有文件「改前原文」逐字固化

### 1.1 `app/src/main/java/my/bridge/com/ui/MainScreen.kt`（HomePage 分发块，原 386~393 行）

改前原文：

```kotlin
        // currentTab 语义=「位置」，经顺序表翻译成页面 id 再分发。
        // coerceIn 兜底：rememberSaveable 恢复的旧索引恒在 0..3，此处防御性钳制。
        when (tabOrder.getOrElse(currentTab) { 0 }) {
            0 -> SmsConversationsPage() // 真实短信页（v0.1.34，原假数据骨架）
            1 -> AudioRelayPage() // 音频中继页（手机放音，原通讯录占位已移除）
            2 -> RelaySwitchPage() // 智能控制页（ESP32 继电器，原「发现」假数据占位已替换）
            3 -> ScanPage() // 快递扫码页（v0.1.94 新增，CameraX + ML Kit 纯本地闭环）
        }
```

回滚配方：`git checkout 130abbc -- app/src/main/java/my/bridge/com/ui/MainScreen.kt`
触发回滚信号：主页任意原有 Tab 白屏 / VNT 页面导致 `when` 分支缺失编译失败。

### 1.2 `app/src/main/java/my/bridge/com/ui/TabOrderPrefs.kt`（全文 81 行，逐字固化）

改前原文：

```kotlin
package my.bridge.com.ui

import android.content.Context

/**
 * 主页顶部页面排序持久化（v0.2.08）。
 *
 * 设计：页面 id 恒定（0=短信 1=音频中继 2=智能控制 3=快递扫码，与 MainScreen 的
 * when 分支一一对应，永不增删改语义）；可配置的只有「显示顺序」tabOrder——
 * tabOrder[位置] = 页面 id。默认 [0,1,2,3] 与历史行为完全一致。
 *
 * 安全边界（保证不破坏现有功能）：
 * - 读取时严格校验（长度=4、元素∈0..3、无重复），任何异常回退默认顺序；
 * - 只存顺序，不碰任何页面组件/业务逻辑。
 */
object TabOrderPrefs {

    /** 页面 id → 标题（单一来源，HomePage 与设置页共用）。 */
    val PAGE_TITLES = listOf("短信", "音频中继", "智能控制", "快递扫码")

    /** 页面 id → 图标（设置页排序编辑器用）。 */
    val PAGE_ICONS = listOf("💬", "🔊", "🔌", "📦")

    private const val KEY = "home_tab_order"
    private const val DEFAULT = "0,1,2,3"

    fun load(context: Context): List<Int> = parse(
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT) ?: DEFAULT,
    )

    fun save(context: Context, order: List<Int>) {
        if (!isValid(order)) return
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, order.joinToString(",")).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, DEFAULT).apply()
    }

    private fun isValid(order: List<Int>): Boolean =
        order.size == 4 && order.toSet() == setOf(0, 1, 2, 3)

    private fun parse(raw: String): List<Int> = runCatching {
        val order = raw.split(",").map { it.trim().toInt() }
        if (isValid(order)) order else PAGE_TITLES.indices.toList()
    }.getOrDefault(PAGE_TITLES.indices.toList())

    // ---- 自定义名称（v0.2.08）：按页面 id 序存 JSON 数组，空串=用默认名 ----

    private const val KEY_NAMES = "home_tab_names"
    private const val NAME_MAX = 8

    /** 自定义名称上限（超长截断，Tab 条不换行且空间有限）。 */
    const val NAME_MAX_LEN = NAME_MAX

    /** 读取自定义名称：List[页面id]，异常/缺省回 ["","","",""]（全用默认名）。 */
    fun loadNames(context: Context): List<String> = runCatching {
        val raw = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .getString(KEY_NAMES, null) ?: return@runCatching PAGE_TITLES.map { "" }
        val arr = org.json.JSONArray(raw)
        List(PAGE_TITLES.size) { i ->
            if (i < arr.length()) arr.optString(i).take(NAME_MAX) else ""
        }
    }.getOrDefault(PAGE_TITLES.map { "" })

    /** 保存自定义名称（空串=恢复该页默认名）。 */
    fun saveNames(context: Context, names: List<String>) {
        if (names.size != PAGE_TITLES.size) return
        val arr = org.json.JSONArray()
        names.forEach { arr.put(it.trim().take(NAME_MAX)) }
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY_NAMES, arr.toString()).apply()
    }

    /** 显示名 = 自定义名（非空）否则默认名。 */
    fun displayName(names: List<String>, pageId: Int): String =
        names.getOrNull(pageId)?.takeIf { it.isNotBlank() } ?: PAGE_TITLES[pageId]
}
```

回滚配方：`git checkout 130abbc -- app/src/main/java/my/bridge/com/ui/TabOrderPrefs.kt`
触发回滚信号：老用户升级后主页排序/自定义名称丢失、`isValid()` 静默回退默认。

### 1.3 `app/src/main/java/my/bridge/com/ui/SettingsPages.kt`（4 处硬编码 4）

改前原文（逐处）：

```kotlin
// ① 原 63~64 行（GeneralSettings 默认参数）
fun GeneralSettings(
    homeTabOrder: List<Int> = listOf(0, 1, 2, 3),
    homeTabNames: List<String> = listOf("", "", "", ""),
```

```kotlin
// ② 原 151~159 行（恢复默认）
        item {
            Row {
                TextButton(onClick = { onHomeTabOrderChange(listOf(0, 1, 2, 3)) }) {
                    Text("恢复默认排序", fontSize = 13.sp)
                }
                TextButton(onClick = { onHomeTabNameChange(List(4) { "" }) }) {
                    Text("恢复默认名称", fontSize = 13.sp)
                }
            }
        }
```

```kotlin
// ③ 原 259~264 行（改名保存）
                    onClick = {
                        val next = homeTabNames.toMutableList()
                        while (next.size < 4) next.add("")
                        next[pageId] = renameText.trim()
                        onHomeTabNameChange(next)
                        renameTarget = null
                    },
```

回滚配方：`git checkout 130abbc -- app/src/main/java/my/bridge/com/ui/SettingsPages.kt`
触发回滚信号：设置页排序编辑器第 5 项越界 / 恢复默认后 Tab 数量不对。

### 1.4 `app/src/main/AndroidManifest.xml`（插入点上下文）

改前原文（权限区，原 13~14 行）：

```xml
    <!-- 前台服务通知（Android 13+ 需运行时申请，否则通知被静默拦截） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

改前原文（application 内服务区末尾，原 198~203 行 + 284 行 `</application>`）：

```xml
        <!-- 音频中继接收服务：手机放音（中转服务器 → WebSocket → Opus 解码 → 播放）。
             mediaPlayback 前台类型；后端零改动，复用 Aurelay 中心服务器。 -->
        <service
            android:name="my.bridge.com.relay.AudioRelayService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />

        <!-- Bridge 辅助服务（无障碍）：…
```

回滚配方：`git checkout 130abbc -- app/src/main/AndroidManifest.xml`
触发回滚信号：Android 14+ 安装/启动时 `SecurityException: Starting FGS with type specialUse requires permissions`。

### 1.5 `app/build.gradle.kts`（defaultConfig 块，原 17~26 行）

改前原文：

```kotlin
    defaultConfig {
        applicationId = "my.bridge.com"
        minSdk = 24
        targetSdk = 36
        // 版本号单一来源：release.py 会自动递增并写回此处，CI 打包直接读取。
        versionCode = 1
        versionName = "0.2.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
```

回滚配方：`git checkout 130abbc -- app/build.gradle.kts`（**注意**：发布后 versionName 会被 release.py 改为 0.2.30，回滚时一并回退）
触发回滚信号：加入 so 后打包体积异常 / 其它 ABI 缺失导致安装失败。

### 1.6 `app/proguard-rules.pro`（全文 3 行）

改前原文：

```
# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
```

回滚配方：`git checkout 130abbc -- app/proguard-rules.pro`
触发回滚信号：release 包 `UnsatisfiedLinkError`（JNI 类被 R8 混淆/移除）。

### 1.7 `app/src/main/java/my/bridge/com/AppExit.kt`（import + kill 停服务块）

改前原文（import 区，原 10~16 行）：

```kotlin
import my.bridge.com.core.common.DiagLog
import my.bridge.com.keepalive.KeepAliveService
import my.bridge.com.media.MediaCaptureService
import my.bridge.com.relay.AudioRelayService
import my.bridge.com.ui.AppExitPrefs
import timber.log.Timber
import java.lang.ref.WeakReference
```

改前原文（kill() 停服务块，原 105~115 行）：

```kotlin
        // 1) 显式停掉自己拉起的（sticky）服务——不做这步，杀进程后会被系统重新拉起
        runCatching { app.stopService(Intent(app, KeepAliveService::class.java)) }
            .onFailure { Timber.w(it, "停止保活服务失败") }
        runCatching { app.stopService(Intent(app, AudioRelayService::class.java)) }
            .onFailure { Timber.w(it, "停止音频中继服务失败") }
        runCatching { app.stopService(Intent(app, MediaCaptureService::class.java)) }
            .onFailure { Timber.w(it, "停止媒体采集服务失败") }

        // 2) 释放微信进程级资源（WS 连接 / 60s 心跳 / 重连 / HTTP 客户端 / 内存数据）
        runCatching { my.bridge.com.wechat.WechatRuntime.stopAll(reason) }
            .onFailure { Timber.w(it, "释放微信资源失败") }
```

回滚配方：`git checkout 130abbc -- app/src/main/java/my/bridge/com/AppExit.kt`
触发回滚信号：退出应用后进程复活（VNT 服务 START_STICKY 被重新拉起）。

### 1.8 `core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt`（常量区末尾）

改前原文（原 51~52 行）：

```kotlin
        const val SEND_NOTIFICATION = "send_notification"
        const val CHECK_UPDATE = "check_update"
```

回滚配方：`git checkout 130abbc -- core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt`
触发回滚信号：命令常量与服务端白名单不一致导致回执丢失。

### 1.9 `app/src/main/java/my/bridge/com/di/CommandModule.kt`（import 区 + mapOf 末尾）

改前原文（import 区，原 49~55 行）：

```kotlin
import my.bridge.com.media.MediaCaptureService
import my.bridge.com.media.ProjectionConsentActivity
import my.bridge.com.access.BridgeAccessibilityService
import my.bridge.com.media.ScreenCaptureHolder
import my.bridge.com.media.MusicPlayer
import retrofit2.HttpException
import timber.log.Timber
```

改前原文（mapOf 末尾，原 216~222 行）：

```kotlin
        Command.SEND_NOTIFICATION to CommandExecutor { command ->
            executeSendNotification(context, command)
        },
        Command.CHECK_UPDATE to CommandExecutor { command ->
            executeCheckUpdate(context, deviceStore, apiClient, command)
        },
        )
    }
```

回滚配方：`git checkout 130abbc -- app/src/main/java/my/bridge/com/di/CommandModule.kt`
触发回滚信号：Hilt 装配失败 / 命令表构建期崩溃。

### 1.10 `server/app/core/command_catalog.py`（COMMANDS 末尾，原 400~415 行）

改前原文：

```python
    # ---- 系统类 ----
    _cmd("upload_log", "上传日志", "system", "📤", "让客户端自动上传诊断日志"),
    _cmd("clear_log", "清除设备日志", "system", "🧹",
         "清空客户端本地日志（内存缓冲 + 磁盘日志文件），配合管理后台「清除全部日志」"
         "实现三端同步清除；仅在设备在线时有效，回执清理的磁盘文件数"),
    _cmd("check_update", "检查更新", "system", "🔎",
         "让客户端检查应用更新（服务器 updates 目录版，不依赖 GitHub）；"
         "开发者把最新 APK（文件名含版本号，如 bridge-v0.1.32.apk，可选配 update.json "
         "写更新说明）放到服务器 data/updates/ 目录即可",
         [_param("mode", "检查模式", PARAM_TYPE_SELECT, default="auto_install",
                 options=[{"value": "auto_install", "label": "自动安装（发现新版直接下载并弹安装页）"},
                          {"value": "check_only", "label": "仅检查（只回执是否需要更新，不下载）"}],
                 description="仅检查模式适合巡检场景：只看设备版本是否落后，不触发下载"),
          _param("force_download", "强制重新下载", PARAM_TYPE_SWITCH, default=False,
                 description="忽略本地缓存强制重下（md5 校验删除污染缓存的自救手段，仅自动安装模式有效）")]),
]
```

回滚配方：`git checkout 130abbc -- server/app/core/command_catalog.py`
触发回滚信号：管理后台 `/api/commands/meta` 500 / 白名单缺命令。

### 1.11 `docs/api/commands/system.md`（末尾插入点，原 60~66 行）

改前原文（文件尾部）：

```markdown
## 缓存健壮性（v0.1.45，防「假 44 实为 43」死循环）

（正文略，文件共 66 行，末尾为 `- \`md5\` 校验…` 收尾）
```

> 说明：本文件是纯追加章节，改前完整原文已由 git 跟踪（`git show 130abbc:docs/api/commands/system.md` 可取），
> 台账只固化插入点位置 = **文件末尾**。

### 1.12 `docs/api/commands/README.md`（system 分类表）

改前原文（system 分类段落）：

```markdown
## ⚙️ system（系统）→ [system.md](system.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `upload_log` | 上传日志 | — |
| `clear_log` | 清除设备日志 | — |
| `check_update` | 检查更新 | [system.md#check_update](system.md#check_update-检查更新服务器目录版不依赖-github) |
```

> 精确原文以 `git show 130abbc:docs/api/commands/README.md` 为准（表格行可能与上述摘要有标点差异）。

### 1.13 `docs/dev/crash-ledger.md`（§3.1 表格末行 + §6）

改前原文（§3.1 表格最后一行，原 66 行）：

```markdown
| `CommandModule`×4（screenshot/photo/video/audio 命令） | MediaCaptureService | 全部 try/catch | onStartCommand 主路径挂前台；**并发拒绝路径补挂（v0.2.01 补）**；挂前台异常 catch+stopSelf（v0.2.01 补） | ✅ |
```

### 1.14 `docs/dev/heartbeat-inventory.md`（§1.2 表格）

改前原文：

```markdown
### 1.2 业务期常驻（服务运行期间）

| 连接   | 源码                                                        | 应用层心跳           | 传输层 ping | 判死                       | 重连                |
| ---- | --------------------------------------------------------- | --------------- | -------- | ------------------------ | ----------------- |
| 音频中继 | `app/.../relay/RelayClient.kt`<br />常量 `RelayProtocol.kt` | **30s** 文本 ping | 无        | 60s 无数据判死 + ready 超时 10s | 1/2/4/8/16/30s 退避 |
```

### 1.15 `docs/INDEX.md`（vnt 调研报告行，P0 已改过）

改前原文（本轮开始时）：

```markdown
| [vnt-mesh-integration.md](research/vnt-mesh-integration.md) | **VNT 组网（虚拟局域网 / SD-WAN）集成调研**（…；**P0 已完成：官方预编译 `libvnt_jni.so` 已入库并校验 PASS**，业务代码待实现，交互原型见 `assets/design/ui-mockup-vnt-mesh.html`） |
```

### 1.16 `docs/research/vnt-mesh-integration.md`（头部状态行）

改前原文（本轮开始时）：

```markdown
> 状态：**P0 已完成，业务代码仍待实现**（本报告为调研与落地设计）。原型见 `assets/design/ui-mockup-vnt-mesh.html`。
```

### 1.17 `docs/CHANGELOG.md`（头部插入点）

改前原文：文件头 `---` 分隔线之后紧接 `## 2026-11-01 · 🧭 v0.2.22→v0.2.29 微信页真机收敛里程碑`。
本轮在其**前面**插入 `## 2026-11-01 · 🕸️ v0.2.30 VNT 组网集成里程碑`。

### 1.18 `assets/design/ui-mockup-vnt-mesh.html`（页脚要点）

改前原文（本轮开始时，含 P0 已回填的一条）：

```html
    <li><b>产物来源（P0 已完成，本地零安装）</b>：…</li>
```

---

## 二、新增文件清单（**git 未跟踪，git 兜不住**，回滚 = 逐个删除）

> 这些文件在本轮之前**不存在**，无「改前原文」可抄；恢复方式就是删除。
> 已在 §三 单独列出 P0 阶段产物（它们早于本轮，删除前须确认是否要一并回滚到 P0 之前）。

| # | 新增路径 | 用途 | 删除配方 |
|---|---|---|---|
| 1 | `app/src/main/java/com/vnt/*.java`（11 个官方绑定类，原样拷贝） | JNI Java 层 | `rm -rf app/src/main/java/com/vnt` |
| 2 | `app/src/main/java/my/bridge/com/vnt/VntVpnService.kt` | VpnService | `rm -f …/vnt/VntVpnService.kt` |
| 3 | `app/src/main/java/my/bridge/com/vnt/VntMeshController.kt` | 状态机/启停/刷新 | 同上 |
| 4 | `app/src/main/java/my/bridge/com/vnt/VntMeshState.kt` | 状态数据类 | 同上 |
| 5 | `app/src/main/java/my/bridge/com/vnt/VntMeshPrefs.kt` | 单套配置持久化 | 同上 |
| 6 | `app/src/main/java/my/bridge/com/vnt/VntConfigBuilder.kt` | `VntConfig.Builder` 装配（含子网转发预留位） | 同上 |
| 7 | `app/src/main/java/my/bridge/com/ui/VntPages.kt` | Compose 组网页 + 配置弹窗 | `rm -f …/ui/VntPages.kt` |
| 8 | 空目录 `app/src/main/java/my/bridge/com/vnt/` | 包目录 | `rmdir` |

> 正式回滚命令（在仓库根目录执行）：
> ```bash
> rm -rf app/src/main/java/com/vnt
> rm -rf app/src/main/java/my/bridge/com/vnt
> rm -f  app/src/main/java/my/bridge/com/ui/VntPages.kt
> ```
>
> **★ 新增文件内容改动（实现期）**：`app/src/main/java/com/vnt/VntConfig.java` 并非 100% 原样——
> 官方 `java_example` 按标准 JVM org.json 编写，而 Android 框架自带的
> `JSONObject.put()` 声明**受检异常 `JSONException`**，原样拷入会报 30 处
> 「未报告的异常错误」，导致 `compileDebugJavaWithJavac` / `assembleDebug` 失败
> （`compileDebugKotlin` 发现不了，只有全量打包才暴露）。
> 适配方式：新增静态包装方法
> ```java
> private static void put(JSONObject json, String name, Object value) {
>     try { json.put(name, value); }
>     catch (org.json.JSONException e) {
>         throw new IllegalStateException("Failed to put VNT config field: " + name, e);
>     }
> }
> ```
> 并把 `toJson()` / `putStringArray()` 内 30 处 `json.put(...)` 改为 `put(json, ...)`。
> **不改包名/类名/native 声明**，JNI 导出符号（`Java_com_vnt_*`）与 config JSON 内容均不受影响；
> `verify_so.py` 的 23↔23 声明-符号比对仍 PASS。其余 10 个 Java 文件保持原样。

---

## 三、P0 阶段既有产物（上一轮成果，本轮不动；如需整体回滚另说）

| 路径 | 说明 |
|---|---|
| `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so` | 官方 v2.0.11 预编译产物，5,042,792 B，SHA256 `c33535b1…8f48`，23↔23 符号全对、16KB 对齐 PASS |
| `tools/vnt/artifacts/vnt-jni-arm64-v8a-v2.0.11.so` + `.sha256` | 原始下载存档 |
| `tools/vnt/verify_so.py` | 纯 Python ELF/sha256/符号校验脚本 |
| `docs/research/vnt-mesh-integration.md` | 调研报告（12 章） |
| `assets/design/ui-mockup-vnt-mesh.html` | 交互原型（4 屏） |
| `docs/INDEX.md`（vnt 行） | 已登记 |
| `.workbuddy/memory/MEMORY.md`、`2026-11-01.md` | 项目长期/当日记忆 |

---

## 四、改后回填（已完成）

> 状态：🟢 已逐项回填。所有既有文件的**改前原文**见 §一，可逐字恢复；
> 新增文件见 §二，回滚按删除配方执行。

| # | 文件 | 改后摘要 | 验证 |
|---|---|---|---|
| 1 | MainScreen.kt | `when` 分发加 `4 -> VntPage()`，注释 0..3→0..4 | 编译 PASS |
| 2 | TabOrderPrefs.kt | `PAGE_TITLES/PAGE_ICONS` 各 +1（"组网"/🕸️）、`DEFAULT="0,1,2,3,4"`、`isValid` 跟随 `PAGE_TITLES.size`、`load()` 识别旧 4 项值并追加 4 回写 | 编译 PASS |
| 3 | SettingsPages.kt | 4 处硬编码 4 → `TabOrderPrefs.PAGE_TITLES` 派生（默认参数 / 恢复默认排序 / 恢复默认名称 / `while (next.size < …)`） | 编译 PASS |
| 4 | AndroidManifest.xml | 加 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `VntVpnService` 服务（`specialUse` 四件套 + `BIND_VPN_SERVICE` + `android.net.VpnService` intent-filter） | 合并后 Manifest 核对 PASS |
| 5 | build.gradle.kts | `defaultConfig` 加 `ndk { abiFilters += "arm64-v8a" }` | APK 内仅 `lib/arm64-v8a/` PASS |
| 6 | proguard-rules.pro | 加 `-keep class com.vnt.** { *; }` | 编译 PASS |
| 7 | AppExit.kt | import + `kill()` 里显式 `stopService(VntVpnService)`（含 START_NOT_STICKY 说明） | 编译 PASS |
| 8 | Command.kt | 加 `VNT_START/VNT_STOP/VNT_STATUS` 三常量 | 编译 PASS |
| 9 | CommandModule.kt | 加 3 个执行器 + `executeVntStart/executeVntStop/executeVntStatus`（含 VPN 授权检查、`vnt_status` 不触发 RPC） | 编译 PASS（Hilt/KSP） |
| 10 | command_catalog.py | system 类加 3 条 `_cmd()`（vnt_start 带 server/network_code/password 可选参数） | `py_compile` + 导入校验：38 条命令/白名单 38 PASS |
| 11 | docs/api/commands/system.md | 标题更新 + 追加「VNT 组网命令」章节（三条命令 + 平台限制 + 性能红线） | 文档核对 |
| 12 | docs/api/commands/README.md | system 表加 3 行索引 | 文档核对 |
| 13 | docs/dev/crash-ledger.md | §3.1 登记 `VntMeshController.start` 调用点；§6 记 v0.2.30 预防性接入（§5 清单逐条） | 文档核对 |
| 14 | docs/dev/heartbeat-inventory.md | 头部 5→6 条；§1.2 加 VNT 黑盒常驻连接行 + UI 5 秒轮询说明 | 文档核对 |
| 15 | docs/INDEX.md | 报告行改「已实现 v0.2.30」；dev 表加 crash-ledger 与 vnt-integration-change-ledger 两行；heartbeat 描述 5→6 | 文档核对 |
| 16 | docs/research/vnt-mesh-integration.md | 头部状态改「已实现」；§5.2 补 START_NOT_STICKY 决策调整；§十一 清单全量回填 + §11.1 本地验证结果 + VntConfig 适配说明 | 文档核对 |
| 17 | docs/CHANGELOG.md | 新增 `v0.2.30 VNT 组网集成里程碑`（4 条脉络 + 5 条关键经验沉淀） | 文档核对 |
| 18 | assets/design/ui-mockup-vnt-mesh.html | 更正 START_STICKY 表述；页脚加「实现状态（v0.2.30 已落地）」一条 | 标签闭合校验 PASS（svg/div/text/ul/li 全 OK） |
| 19 | 本台账 + 记忆 | 新增 `docs/dev/vnt-integration-change-ledger.md`；`.workbuddy/memory/` 追加当日纪要 | — |

### 4.1 本地验证结果（2026-11-01）

| 项 | 结果 |
|---|---|
| `JAVA_HOME=… ./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL**（3m25s） |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**（3m48s），APK 34,029,588 B；首次因 VntConfig.java 的 JSONException 受检异常失败，按 §二 适配后通过 |
| APK 内 so | `lib/arm64-v8a/libvnt_jni.so` 5,042,792 B，sha256 `c33535b1…`，与官方一致；无其它 ABI |
| Manifest 合并 | `VntVpnService` / `foregroundServiceType="specialUse"` / `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` / `BIND_VPN_SERVICE` / `android.net.VpnService` 全部在位 |
| `tools/vnt/verify_so.py` | SHA256 / ELF64-AArch64 / 4 段 16KB 对齐 / 23↔23 声明-符号，全 PASS |
| 服务端 `command_catalog.py` | `py_compile` PASS；38 条命令、白名单 38、三条 VNT 命令参数正确 |

> **未做（需真机环境）**：首次 VPN 授权 → 虚拟 IP → ping 通、P2P/中继标记、切 Tab/退后台停刷、
> 退出不被复活、三条命令真机回执、batterystats 开关对照。见报告 §十一 D 组。
