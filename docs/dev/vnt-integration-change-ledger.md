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

---

# 五、v0.2.31 变更台账（改前留档 · 2026-11-01 晚）

> 状态：🔵 **改前已留档，正在实施**。git 基线 = `3226ab61b1887f1b455fc9913c2466e38eb54f36`
> （v0.2.30 已发布）。以下既有文件全部已入库，逐字原文由 git 兜住；新增文件须按删除配方清理。

## 5.0 触发本次变更的用户诉求与诊断结论

用户提出 4 项：① 对齐官方 universal（补齐 ABI）；② 设置→关于页不能上下滑动；
③ 点「开始组网」记忆、下次启动 App 自动组网，点「停止组网」清除记忆；
④ 点开始组网后报错（附运行截图 + 已上传客户端日志 + 允许 adb 取证）。

**诊断证据（MySQL `device_logs`，设备 `f2c38ebee251a196`，v0.2.30，21:27~21:29）**：

- 11 条 `E/组网` 完全一致：
  `组网失败 | RuntimeException: Failed to create network: invalid server address 'udp://039039.xyz:31007': Unknown protocol in server address: udp://039039.xyz:31007 @ com.vnt.VntManager.nativeCreateNetwork(Native Method)`
- 21:27:17 / 21:29:37 各一条 `I/组网: VNT 已连接：86.74.128.88/24`（默认公共服成功）。
- 官方 Rust 源码 `vnt-core/src/tunnel_core/server/transport/config.rs:80-101` `parse_server()`：
  合法前缀仅 `quic://` / `tcp://`(TlsTcp) / `wss://` / `dynamic://`；**裸 `host:port` 默认也是 TlsTcp**；
  任何其它 `://`（含 `udp://`）直接 `Err("Unknown protocol in server address: ...")`。
- `vnts-master/README.md:9`：vnts 支持 `quic` / `tcp(tls)` / `wss`，自动签发自签名证书。
- 本机实测 `039039.xyz:31007`：DNS→`122.6.190.161`、TCP 可连，但 **TLS 握手 0.01s 即 `UNEXPECTED_EOF`**
  ⇒ 该端口不是 vnts 的 TLS TCP 监听（端口映射/协议/版本不匹配）。
- `adb devices` 为空（设备未开无线调试），未拿到 logcat；但 MySQL 日志已给出决定性证据。
- **用户补充（21:45）**：真机跑的是 **VNT 1.2.13 原生 APK**（截图「组网参数配置」里
  「协议」单选 UDP/TCP/WS/WSS 选了 **UDP** + 服务器地址 `039039.xyz:31007`）；
  并明确拍板 **「既然集成最新的 so，就按照最新规则来实现」**
  ⇒ 不降级 so、不加 `udp://` 兼容层。
- **逐 tag 核对**：`vnt-dev/vnt` v2.0.2 / v2.0.7 / v2.0.8 / v2.0.9 / v2.0.10 / v2.0.11
  的 `parse_server()` 协议前缀完全一致（`quic://` / `tcp://` / `wss://` / `dynamic://`），
  **都不支持 `udp://`**；`ProtocolType` 枚举无 `Udp`。官方 VntApp v2.0.10 / v2.0.9 APK
  内嵌 so 与本项目 v2.0.11 sha 不同但规则一致。

**根因**：不是打包漏东西、也不是 so 版本错，而是**协议代际不匹配**——
用户按 v0.2.30 UI 提示（错误地写着「裸 host:port 默认 UDP」）填了 `udp://039039.xyz:31007`；
1.2.13 旧核心 + 老 UDP VNTS 能连，v2.0.11 核心已改为 TLS TCP/WSS/QUIC，连不上是必然。
修复 = 纠正文案 + 保存时白名单校验 + native 前防线 + ERROR 状态不被吞 +
状态卡如实反映「服务端未连接」；**按用户拍板保持 v2.0.11 最新规则**。

## 5.1 改前原文逐字固化（将修改的既有文件）

**① `app/build.gradle.kts:23-27`**
```kotlin
        versionName = "0.2.30"

        // VNT 组网（v0.2.30）：官方预编译 libvnt_jni.so 只有 arm64-v8a（已拍板只做这一 ABI）。
        // 显式限定可在构建期锁死，避免其它 ABI 缺 so 时装不上/跑不起来，顺带减小 APK。
        ndk { abiFilters += "arm64-v8a" }
```

**② `app/src/main/java/my/bridge/com/ui/SettingsPages.kt:823-828`（`AboutPage()` 根 Column，无滚动）**
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
```
（该文件 import 区**没有** `verticalScroll` / `rememberScrollState`，需补 import）

**③ `app/src/main/java/my/bridge/com/vnt/VntMeshPrefs.kt:9`**
```kotlin
    /** 服务端地址，可带 tcp:// / wss:// / quic:// 前缀；裸 host:port 默认 UDP。 */
```
（`:60-72` SP 常量区无 autoStart 键；`load()` / `save()` 不涉及记忆）

**④ `app/src/main/java/my/bridge/com/ui/VntPages.kt:488`**
```kotlin
                    "默认公共服，可改自建 VNTS；支持 tcp:// wss:// quic:// 前缀，裸 host:port 默认 UDP",
```
（`:94-101` `requestStart()`、`:178-194` 主按钮、`:207-222` `onSave` 均未写记忆；
`:240-251` `VntStatusCard` 的 RUNNING 分支无条件显示绿色「已连接」）

**⑤ `app/src/main/java/my/bridge/com/vnt/VntConfigBuilder.kt:25-26`**
```kotlin
        val builder = VntConfig.Builder()
            .addServer(config.server)
```
（无地址合法性校验，非法前缀直接进 native）

**⑥ `app/src/main/java/my/bridge/com/MainActivity.kt`**：无 `onStart()`，无 VNT 自动启动钩子。

**⑦ `app/src/main/java/my/bridge/com/di/CommandModule.kt:329-338`**：`vnt_start` 成功后未写记忆；
`:346-353` `vnt_stop` 未清记忆。

**⑧ `tools/vnt/verify_so.py`**：硬编码 `ELF64 / AArch64 (e_machine=183)`、`<Q` 64 位
phdr/shdr/dynsym 解析，无法校验 ELF32/ARM 与 x86_64 产物。

**⑨ `app/src/main/jniLibs/`**：改前**只有** `arm64-v8a/libvnt_jni.so`（官方 v2.0.11，
sha256 `c33535b17b860ae3f09d64dba286d81d51387732db2e6bf9115ba2aed0038f48`）。

改前 sha256（本机 CRLF 原文，供逐字比对）：
```
5efeffebc462bcbc145e889e6299753362a5e8eee96dfaa0043a3021608ee362 app/build.gradle.kts
871a97a22abb4c297129d639aa910c8f438738820b1db6f198ccac56c44c3e3c SettingsPages.kt
1fbce7be4a93a6bf374e4482bd9edaba3da1c2c02582d56c1e02a669eeb03de3 VntPages.kt
d351c42b36a6d29adafb57bc48157c6213ac3bfb3583ad852db77a96807bb899 VntMeshPrefs.kt
90478f43e4c7f53f78a764189a35a865a95089ea91543ac248fb939b606008e1 VntConfigBuilder.kt
b7d261bc0f03158f999b980ab8f1e6d78a5bd18c96b4f5e8d7838caea124249a VntMeshState.kt
0a49a7fb9488aee8bf18b8666a764a9b2029e2d1d0c3ac20efbbda95a98b8a4e VntMeshController.kt
07bed868be1e261ef3ddcbebd7e29e5b0242819b4c14ba8399068f8e624379f3 MainActivity.kt
bd81ed8438bd8214c5603874eb965dac05da2e3f401115ad469dea887e127c3a CommandModule.kt
8734e3441fdba5b942765fc85d9e1aff7185fb0d4c56fabec81bfbe1e2dd64cf tools/vnt/verify_so.py
```

## 5.2 新增文件清单（回滚 = 删除）

| 路径 | 用途 |
|---|---|
| `app/src/main/jniLibs/armeabi-v7a/libvnt_jni.so` | 官方 v2.0.11 armeabi-v7a 产物 |
| `app/src/main/jniLibs/x86_64/libvnt_jni.so` | 官方 v2.0.11 x86_64 产物 |
| `tools/vnt/artifacts/vnt-jni-armeabi-v7a-v2.0.11.so{,.sha256}` | 原始产物存档 |
| `tools/vnt/artifacts/vnt-jni-x86_64-v2.0.11.so{,.sha256}` | 原始产物存档 |
| `app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt` | 冷启动自动组网入口 |
| `.workbuddy/vnt_commit_msg_v0231.txt` | 发布用中文详细 commit message（被 .gitignore 忽略） |
| `releases/app-debug-v0.2.31.apk` | 发布产物 |

## 5.3 回滚配方

```bash
# ① 既有文件整体回到 v0.2.30 基线
git checkout 3226ab6 -- \
  app/build.gradle.kts \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
  app/src/main/java/my/bridge/com/ui/VntPages.kt \
  app/src/main/java/my/bridge/com/vnt/VntMeshPrefs.kt \
  app/src/main/java/my/bridge/com/vnt/VntConfigBuilder.kt \
  app/src/main/java/my/bridge/com/vnt/VntMeshState.kt \
  app/src/main/java/my/bridge/com/vnt/VntMeshController.kt \
  app/src/main/java/my/bridge/com/MainActivity.kt \
  app/src/main/java/my/bridge/com/di/CommandModule.kt \
  tools/vnt/verify_so.py \
  docs/research/vnt-mesh-integration.md assets/design/ui-mockup-vnt-mesh.html \
  docs/INDEX.md docs/CHANGELOG.md docs/dev/heartbeat-inventory.md \
  docs/api/commands/system.md docs/dev/crash-ledger.md

# ② 删除新增文件
rm -rf app/src/main/jniLibs/armeabi-v7a app/src/main/jniLibs/x86_64
rm -f  app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt
rm -f  tools/vnt/artifacts/vnt-jni-armeabi-v7a-v2.0.11.so* \
       tools/vnt/artifacts/vnt-jni-x86_64-v2.0.11.so*
rm -f  .workbuddy/vnt_commit_msg_v0231.txt releases/app-debug-v0.2.31.apk
```

**触发回滚的信号**：`assembleDebug` 失败且无法在 1 轮内修复；三套 so 任一与官方 sha256 不符；
APK 内出现 `lib/x86/`（32 位）或多于/少于 3 套 ABI；自动组网导致冷启动即崩溃或
`startForegroundService` 抛异常刷屏；发布后 CI 非 success。

## 5.4 改后回填（已完成）

> 状态：🟢 已逐项回填。所有既有文件的**改前原文/哈希**见 §5.1，可逐字恢复；
> 新增文件见 §5.2，回滚按 §5.3 删除配方执行。

| # | 文件 | 改后摘要 | 验证 |
|---|---|---|---|
| 1 | `app/build.gradle.kts` | `versionName` 0.2.30→**0.2.31**；`abiFilters` 由单 arm64 改为 **arm64-v8a / armeabi-v7a / x86_64** 三套；纠正「官方 so 只有 arm64」的错误注释 | assembleDebug PASS；APK 内 3 套、无 x86 |
| 2 | `SettingsPages.kt` | `AboutPage()` 根 `Column` 补 `.verticalScroll(rememberScrollState())` + 2 个 import | 编译 PASS |
| 3 | `VntMeshPrefs.kt` | 字段注释改为 v2.0.11 规则；新增 `K_AUTO_START` + `autoStart()`/`setAutoStart()`；新增 `VntServerAddress.error()` 白名单校验 | 编译 PASS |
| 4 | `VntConfigBuilder.kt` | `build()` 开头加 `VntServerAddress.error()` 防线，非法前缀抛可读 `IllegalArgumentException` | 编译 PASS |
| 5 | `VntVpnService.kt` | `runConnect` 加 `failed` 标志，`finally` 仅成功路径 `onStopped()`，失败保留 ERROR 文案 | 编译 PASS |
| 6 | `VntPages.kt` | 提示文案改 v2.0.11 规则；配置弹窗保存前校验 + Toast；开始/停止按钮写记忆；新增 `VntAmber`；状态卡在 RUNNING 但服务端全未连时改橙色「未连上服务端」 | 编译 PASS |
| 7 | `MainActivity.kt` | import `VntAutoStart`；加 `vntAutoStartAttempted` + `onStart()` 一次性自动组网 | 编译 PASS |
| 8 | `CommandModule.kt` | `vnt_start` 成功/已在运行写记忆 `true`；`vnt_stop` 写 `false` 并改回执文案 | 编译 PASS（KSP） |
| 9 | `tools/vnt/verify_so.py` | 重写为多 ABI（ELF32/ELF64、ARM/AArch64/x86-64、16KB/4KB 对齐）+ `--abi` 参数 | 实跑 3/3 PASS |
| 10 | `app/src/main/jniLibs/` | 新增 `armeabi-v7a/` / `x86_64/` 两套官方 so，与 artifacts 逐字节一致 | APK 校验 PASS |
| 11 | **新增** `VntAutoStart.kt` | 冷启动自动组网（三道守卫 + 失败不重试） | 编译 PASS |
| 12 | 文档 | 报告 §十二、INDEX（2 行）、CHANGELOG v0.2.31 里程碑、`system.md`、`crash-ledger.md` §3.1、原型页脚、本台账、记忆日志 | 文档自检 |

### 5.4.1 本地验证结果（2026-11-01）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **BUILD SUCCESSFUL**（3m42s） |
| `assembleDebug` | **BUILD SUCCESSFUL**（2m30s） |
| APK | 52,651,950 B（≈50.2 MiB）；**3 套 ABI × 6 个 so**；三份 `libvnt_jni.so` 与官方逐字节一致；**无 `lib/x86/`**；20 dex + Manifest + resources.arsc |
| Manifest（axml_dump） | `versionName='0.2.31'`、`package='my.bridge.com'`、`compileSdkVersion=36`、specialUse 四件套齐全 |
| `verify_so.py` | 三套全 PASS（SHA256 / 16KB·4KB 对齐 / 23↔23 声明-符号） |
| HTML 原型 | `ul/li/div/text/svg/b/code` 标签全部闭合 |

> **未做（需真机环境）**：三套 ABI 真机安装与 VNT 加载、关于页滚动手感、自动组网冷启动、
> 自建 VNTS 升级到 2.x 后的 TLS TCP 连通、`batterystats` 省电实测。
> 见报告 §十一 D 组 + §12.6。

---

## 六、v0.2.32：A1 纯替换 1.x（2026-11-01 23:00 用户拍板「a1 开工」）

> **本节是 v0.2.32 的「改前留档」，动手改任何一行之前先写。**
> 改完必须回填 §6.8「改后原文 + 验证结果」，形成「改前 / 改后 / 回滚」闭环。

### 6.1 用户诉求与决策链（不可省略的背景）

1. 用户自建 VNTS 与**全部其它客户端都是 1.X**，**不能升级**（存量客户端要继续用），因此
   `vnt-main/README.md:167`「2.0 与 1.0 不兼容」决定了 **2.x so 无法加入现有 1.x 网络**。
2. 用户明确：`我的其它客户端和自建服务端都是1.X版本的 所以必须互通1.X` ⇒ 排除 B（双服务端并存，两网不互通）。
3. 助手核实：官方 **1.x 最后一个 Java JNI 版 = `v1.2.9`**（1.2.10.1 起转 Flutter / Dart FFI，
   `librust_lib_vnt_app.so` 的 `Java_*` 符号 = 0，Bridge 用不了）；用户回 `a1 开工`。
4. **决策 A1**：删除 2.x 全套（`com/vnt/*` 11 个类 + 3 套 so），换成 `v1.2.9` 全套
   （14 个 `top/wherewego/vnt/jni/*.java` + 2 套 so），**重写** VNT 层 / UI / 命令 / 文档。
   2.x 成果不删除历史，靠本台账 + git 可 100% 恢复。
5. 版本号 `v0.2.32`；`armeabi-v7a` 失去支持（官方 1.x JNI 版从未提供，见工作日志 22:54 核实）。

### 6.2 改前基线（git 可 100% 兜底）

```
HEAD = 0f7f50985059f46fe342d7a3f9a30ac984b147c0   （tag v0.2.31）
git status --short = 空（工作区干净）
```

**本节列出的全部待改 / 待删文件，`git ls-files --error-unmatch` 全部 = tracked** ⇒
不需要靠抄原文来兜底，但按「改前留档」约定仍在 §6.4 逐字固化关键片段，
便于人眼核对回滚是否到位。

### 6.3 改前文件指纹（sha256 前 16 位 / 行数）

| 文件 | sha256(16) | 行/字节 |
|---|---|---|
| `app/build.gradle.kts` | `5a3f98e70a9bc2ab` | 126 行 |
| `app/proguard-rules.pro` | `5c095bd6f916d93f` | 6 行 |
| `app/src/main/java/my/bridge/com/vnt/VntMeshState.kt` | `b7d261bc0f03158f` | 70 行 |
| `app/src/main/java/my/bridge/com/vnt/VntMeshPrefs.kt` | `4d592fcf68ca36d8` | 176 行 |
| `app/src/main/java/my/bridge/com/vnt/VntConfigBuilder.kt` | `a47707b80d45ee14` | 76 行 |
| `app/src/main/java/my/bridge/com/vnt/VntMeshController.kt` | `0a49a7fb9488aee8` | 336 行 |
| `app/src/main/java/my/bridge/com/vnt/VntVpnService.kt` | `97dded50515756d8` | 345 行 |
| `app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt` | `5e28b891f5fc2e67` | 46 行 |
| `app/src/main/java/my/bridge/com/ui/VntPages.kt` | `8ebeaff708543714` | 593 行 |
| `app/src/main/java/my/bridge/com/di/CommandModule.kt` | `940b5e401f09fd23` | 1878 行 |
| `app/src/main/java/my/bridge/com/MainActivity.kt` | `a622043df6def734` | 181 行 |
| `app/src/main/java/my/bridge/com/AppExit.kt` | `935885d180cd4d66` | 144 行 |
| `app/src/main/AndroidManifest.xml` | `9e5ed4c6a54680ce` | 308 行 |
| `server/app/core/command_catalog.py` | `1002c758c7cb6a7b` | 450 行 |
| `docs/api/commands/system.md` | `2531a30c68a12fe1` | 130 行 |
| `docs/INDEX.md` | `75353f1207e9b7df` | 77 行 |
| `docs/CHANGELOG.md` | `eaaf4218d1ae8435` | 953 行 |
| `docs/research/vnt-mesh-integration.md` | `1337521efa244fb8` | 699 行 |
| `docs/dev/vnt-integration-change-ledger.md` | `f3cda2d536f95750` | 738 行 |
| `docs/dev/crash-ledger.md` | `0aaf7692deed8f22` | 133 行 |
| `docs/dev/heartbeat-inventory.md` | `7a679f80dd8bf8c6` | 553 行 |
| `assets/design/ui-mockup-vnt-mesh.html` | `07663d46b0b0651d` | 319 行 |
| `tools/vnt/verify_so.py` | `f152dd467c4c5310` | 235 行 |
| `server/data/updates/update.json` | `a89e889c7bc5370d` | 4 行 |
| `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so` | `c33535b17b860ae3` | 5,042,792 B |
| `app/src/main/jniLibs/armeabi-v7a/libvnt_jni.so` | `454908dffb7afcbe` | 3,500,728 B |
| `app/src/main/jniLibs/x86_64/libvnt_jni.so` | `0ee3865b9c3ea360` | 5,756,440 B |
| `app/src/main/java/com/vnt/*.java`（11 个） | — | 见 §一 |

### 6.4 改前原文（逐字固化，关键片段）

**① `app/build.gradle.kts:23-33`（版本号 + ABI）**
```kotlin
        versionName = "0.2.31"

        // VNT 组网（v0.2.31）：对齐官方 universal，收录官方现成的 3 套 ABI JNI so
        // （arm64-v8a / armeabi-v7a / x86_64，均来自 vnt-dev/vnt v2.0.11 Release）。
        // 官方未提供 x86（32 位）so：若不限定，其它依赖的 x86 so 仍会被打进 APK，
        // 但该 ABI 上 VNT 加载不到 native 库，造成「装上了但组网不可用」的假成功，故显式排除。
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
            abiFilters += "x86_64"
        }
```

**② `app/proguard-rules.pro` 全文（6 行）**
```
# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
# VNT 组网（v0.2.30）：com.vnt.* 是官方 JNI 绑定，native 侧符号按「包名+类名」硬编码
# （Java_com_vnt_VntManager_nativeInit 等）。R8 混淆或移除会直接抛 UnsatisfiedLinkError。
-keep class com.vnt.** { *; }
```

**③ `VntMeshPrefs.kt` 改前核心（默认服 / 地址规则）**
```kotlin
        /** 官方公共测试服，仅作默认值；生产可填自建 VNTS。 */
        const val DEFAULT_SERVER = "vnt2.rustvnt.com:29872"
        const val DEFAULT_MTU = 1380
```
```kotlin
object VntServerAddress {
    private val ALLOWED_PREFIXES = listOf("tcp://", "wss://", "quic://", "dynamic://")

    fun error(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null                 // 空串由 VntConfigBuilder 兜底成默认公共服
        if (!s.contains("://")) return null          // 裸 host:port 合法
        val lower = s.lowercase()
        if (ALLOWED_PREFIXES.any { lower.startsWith(it) }) return null
        val proto = s.substringBefore("://") + "://"
        return "不支持的协议前缀「$proto」。VNT v2.0.11 只支持 " +
            "tcp:// / wss:// / quic:// / dynamic://，或裸 host:port（默认 tcp:// TLS）。" +
            "udp:// 是 1.2.x 旧版协议，请升级自建 VNTS 到 2.x 并开启 TLS TCP/WSS/QUIC，" +
            "或先用公共服 vnt2.rustvnt.com:29872 验证。"
    }
}
```

**④ `VntConfigBuilder.kt:18-29`（2.x 装配 + 非法前缀防线）**
```kotlin
    fun build(context: Context, cfg: VntMeshConfig): VntConfig {
        // 网络编号 / 服务端是必填项，UI 层已校验；此处再兜一层防止空串进 native。
        val config = cfg.copy(
            server = cfg.server.ifBlank { VntMeshConfig.DEFAULT_SERVER },
            deviceId = cfg.deviceId.ifBlank { VntMeshPrefs.stableDeviceId(context) },
        )

        // v0.2.31 防线：非法前缀（尤其 1.2.x 旧协议的 udp://）不能进 native。
        // 否则 VntManager.createNetwork() 会抛晦涩的
        // 「Unknown protocol in server address」，且用户反复点开始会一直刷错误日志。
        // 这里抛可读异常，由 VntVpnService.runConnect 的 catch 转成页面 ERROR 文案。
        VntServerAddress.error(config.server)?.let { throw IllegalArgumentException(it) }

        val builder = VntConfig.Builder()
            .addServer(config.server)
            .setNetworkCode(config.networkCode)
```

**⑤ `VntMeshController.kt` 改前依赖（2.x JNI import + 唯一 RPC）**
```kotlin
import com.vnt.NetworkResult
import com.vnt.VntApi
```
```kotlin
            // ★ 唯一会发网络包的调用：主动 RPC，最多阻塞 3 秒，必须在后台线程
            val clients = a.getClientList()
```
```kotlin
    fun onConnected(
        newApi: VntApi,
        result: NetworkResult,
        cfg: VntMeshConfig,
        mtu: Int,
    ) {
```

**⑥ `VntVpnService.kt` 改前时序（2.x：Java 先建 TUN 再把 fd 传进去）**
```kotlin
import com.vnt.ChangeApplyResult
import com.vnt.NetworkResult
import com.vnt.RuntimeChange
import com.vnt.RuntimeEvent
import com.vnt.VirtualIpv6
import com.vnt.VntApi
import com.vnt.VntException
import com.vnt.VntManager
import com.vnt.VntNetwork
```
```kotlin
            // 1) 创建网络实例（后台连服务器并注册）
            net = VntManager.createNetwork(config)
            // 2) 等待注册完成，取分配的网段信息
            val registered: NetworkResult = net.getNetwork()
            val cidr = registered.toCidr()
            // 3) 建立 VPN 接口并把 detached fd 交给 VNT
            val tunFd = establishVpn(cidr, currentMtu, registered.getRoutes())
            net.startTun(tunFd)
            // 4) 对外发布查询接口 + 状态
            val api: VntApi = net.getApi()
            VntMeshController.onConnected(api, registered, cfg, currentMtu)
            // 5) 变更循环（阻塞 nextEvent，直到实例停止或异常）
            runChangeLoop(net, gen)
```
```kotlin
        } finally {
            runCatching { net?.stop() }
            network = null
            // v0.2.31：失败时**保留 ERROR 状态**，别被 onStopped() 覆盖成「未连接」
            if (!failed) VntMeshController.onStopped()
            running = false
            stopSelf()
        }
```
（★ `finally` 里 `if (!failed)` 是 v0.2.31 修的坑，重写 1.x 时必须保留「失败保留 ERROR」语义。）

**⑦ `VntPages.kt` 改前配置弹窗（2.x 字段 + 保存前拦截）**
```kotlin
                VntField("服务端地址", server, { server = it }, "vnt2.rustvnt.com:29872")
                Text(
                    "VNT v2.0.11 规则：裸 host:port 默认 tcp://（TLS）；可显式写 " +
                        "tcp:// / wss:// / quic:// / dynamic://。不支持 udp://（1.2.x 旧版协议）。" +
                        "自建 VNTS 需升级到 2.x 并开启 TLS TCP/WSS/QUIC，或先用公共服验证。",
```
```kotlin
                VntServerAddress.error(server)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    return@TextButton
                }
```
```kotlin
        val serverNotConnected = state.isRunning &&
            state.servers.isNotEmpty() && state.servers.none { it.connected }
```

**⑧ `CommandModule.kt:300-312`（vnt_start 参数覆盖）**
```kotlin
                VntMeshPrefs.save(
                    context,
                    old.copy(
                        server = server.ifEmpty { old.server },
                        networkCode = code.ifEmpty { old.networkCode },
                        password = password.ifEmpty { old.password },
                    ),
                )
            }
            val cfg = VntMeshPrefs.load(context)
            if (cfg.networkCode.isBlank()) {
```

**⑨ `server/app/core/command_catalog.py:417-428`（vnt_start 参数）**
```python
    _cmd("vnt_start", "开始组网", "system", "🕸️",
         "启动 VNT 虚拟局域网（前台服务常驻，切 Tab / 退后台不断连）；"
         "★ 需 App 在前台或已授予系统 VPN 授权：后台下发大概率失败，回执会如实说明而不假装成功",
         [_param("server", "服务端地址", PARAM_TYPE_TEXT,
                 placeholder="vnt2.rustvnt.com:29872",
                 description="可选覆盖 APP 内已保存的配置；支持 tcp:// wss:// quic:// 前缀，裸 host:port 默认 UDP"),
          _param("network_code", "网络编号", PARAM_TYPE_TEXT,
                 placeholder="留空用 APP 内保存的配置",
                 description="可选覆盖；同编号设备互通"),
          _param("password", "组网密码", PARAM_TYPE_TEXT,
                 placeholder="留空用 APP 内保存的配置",
                 description="可选覆盖；非空启用 ChaCha20-Poly1305 端到端加密")]),
```

**⑩ `docs/api/commands/system.md:85`（server 参数说明）**
```markdown
| `server` | text | 否 | 可选覆盖 APP 内保存的服务端地址（默认 `vnt2.rustvnt.com:29872`，可填自建 VNTS）。**v2.0.11 规则**：裸 `host:port` 默认 **`tcp://`（TLS TCP）**，可显式写 `tcp://` / `wss://` / `quic://` / `dynamic://`；**不支持 `udp://`**（1.2.x 旧协议）。非法前缀会在配置弹窗 / `VntConfigBuilder` 两处被拦下，不再让 native 抛 `Unknown protocol in server address` |
```

### 6.5 新增文件清单（**改前不存在，git 兜不住，必须靠删除配方**）

| 新增路径 | 来源 |
|---|---|
| `app/src/main/java/top/wherewego/vnt/jni/{CallBack,Config,DeviceBean,IpUtils,PeerRouteInfo,Route,Vnt}.java` | 官方 `VntApp` tag `v1.2.9`，gh-proxy 下载 |
| `app/src/main/java/top/wherewego/vnt/jni/param/{ConnectInfo,DeviceConfig,DeviceInfo,ErrorInfo,HandshakeInfo,PeerClientInfo,RegisterInfo}.java` | 同上（共 14 个绑定类） |
| `tools/vnt/artifacts/v1.2.9/*`（2 套 so + `.sha256`） | 官方 `v1.2.9` 产物存档 |
| `docs/dev/vnt-integration-change-ledger.md` §六回填 | 本文件 |
| `releases/app-debug-v0.2.32.apk`、`server/data/updates/bridge-v0.2.32.apk` | 发布产物（APK 被 `.gitignore:53` 忽略） |

**改前存在 → 改后删除**（git tracked，仍具名列出）：
- `app/src/main/java/com/vnt/` 全部 11 个 `.java`
- `app/src/main/jniLibs/armeabi-v7a/libvnt_jni.so`（1.x 无 v7a so）
- `app/src/main/jniLibs/arm64-v8a/libvnt_jni.so`、`x86_64/libvnt_jni.so`：内容替换为 v1.2.9 产物

### 6.6 回滚配方（**100% 恢复到 v0.2.31**）

```bash
cd /f/pythonWork/appClient
# ① 一键恢复所有 git tracked 的改前状态（含被删的 com/vnt 与 v7a so）
git checkout 0f7f50985059f46fe342d7a3f9a30ac984b147c0 -- \
  app/build.gradle.kts \
  app/proguard-rules.pro \
  app/src/main/java/com/vnt \
  app/src/main/jniLibs \
  app/src/main/java/my/bridge/com/vnt \
  app/src/main/java/my/bridge/com/ui/VntPages.kt \
  app/src/main/java/my/bridge/com/di/CommandModule.kt \
  app/src/main/java/my/bridge/com/MainActivity.kt \
  app/src/main/java/my/bridge/com/AppExit.kt \
  app/src/main/AndroidManifest.xml \
  server/app/core/command_catalog.py \
  docs/api/commands/system.md docs/INDEX.md docs/CHANGELOG.md \
  docs/research/vnt-mesh-integration.md \
  docs/dev/vnt-integration-change-ledger.md \
  docs/dev/crash-ledger.md docs/dev/heartbeat-inventory.md \
  assets/design/ui-mockup-vnt-mesh.html \
  tools/vnt/verify_so.py \
  server/data/updates/update.json

# ② 删除 v0.2.32 新增（git 未跟踪，必须显式删）
rm -rf app/src/main/java/top/wherewego/vnt/jni
rm -rf tools/vnt/artifacts/v1.2.9
rm -f  releases/app-debug-v0.2.32.apk
rm -f  server/data/updates/bridge-v0.2.32.apk

# ③ 校验
git status --short        # 应为空
grep 'versionName = ' app/build.gradle.kts   # 应回到 "0.2.31"
```

> 若已本地提交 v0.2.32 但**尚未发布**，用 `git reset --hard 0f7f5098...` 更彻底；
> 若**已发布到远端**，不要 force push，改走「再发一版 v0.2.33 回滚版」。

### 6.7 触发回滚的信号（出现任一即停手并回滚）

1. `compileDebugKotlin` / `assembleDebug` 失败且 30 分钟内无法定位（尤其
   `UnsatisfiedLinkError` / `NoSuchMethodError` / JNI 符号对不上）。
2. `System.loadLibrary("vnt_jni")` 在真机抛 `UnsatisfiedLinkError`（包名/ABI/符号三者有其一不符）。
3. 真机点「开始组网」后 native 无任何回调、`Vnt` 构造阻塞不返回，或进程崩溃（signal 11 / SIGSEGV）。
4. 与 1.x 服务端**注册成功但互 ping 不通**（协议代际仍有差异，`token/password/cipherModel/serverEncrypt/finger` 逐项核对后仍不通）。
5. 前台服务触发崩溃台账 §5 任一条（前台义务欠账、`startForeground` 抛异常未兜住）。
6. 用户中途改变决策（例如决定升服务端到 2.x）。

### 6.8 改后回填（2026-11-02 完成，本节已闭环）

> **状态：✅ 已闭环**。§6.9 的 11 项实施全部完成、全部本地验证 PASS、v0.2.32 已发布并追平本地 git，
> §6.7 的 6 条回滚信号**均未触发**（真机项属「待验证」，非失败）。
> 改前原文见 §6.4、新增文件删除清单见 §6.5、回滚配方见 §6.6；改后完整内容由远端 commit
> `11c1f1746e4a165713267401b8f224fef95d64c6` 兜底（git 可 100% 检出）。

#### 6.8.1 改后关键片段（与 §6.4 改前逐项对照）

| 位置 | 改后（1.x / VntApp v1.2.9） |
|---|---|
| `build.gradle.kts` | `versionName = "0.2.32"`；`abiFilters += "arm64-v8a"` / `"x86_64"`（2 套） |
| `proguard-rules.pro` | `-keep class top.wherewego.vnt.jni.** { *; }` |
| `Vnt.java`（新增） | 补 `static { System.loadLibrary("vnt_jni"); }`（官方绑定未带，不补必抛 `UnsatisfiedLinkError`） |
| `VntVpnService.generateTun` | `override fun generateTun(info: DeviceConfig?): Int { ... val f = Builder().addAddress(ip, prefix).addRoute(route, prefix).establish()!!; pfd = f; return f.fd }` —— **native 反过来向 Java 要 fd**；绝不 `addRoute("0.0.0.0", 0)` |
| `VntVpnService.runConnect` | `v = Vnt(config, callback)` → `onVntReady(PeerListProvider { vnt?.list() })` → `startupError?.let { throw }` → `while (running && gen == generation) { nativeVnt.awaitTimeout(2000) }`；`finally` 统一 `v?.stop()/close()` + `pfd?.close()`，仅 `!failed` 才 `onStopped()`（沿用 v0.2.31 修复） |
| `VntMeshPrefs` | `token / server(裸 host:port) / tcp(bool) / password / cipherModel / serverEncrypt / finger / firstLatency / mtu / stunServers / punchModel / useChannel`；`load()` 迁移 `network_code → token` 与 2.x 公共服 → `039039.xyz:31007`；`VntServerAddress.error()` 拒绝任何 `://` |
| `VntConfigBuilder` | `c.setTcp(config.tcp)` / `setServerEncrypt` / `setFinger` / `setFirstLatency`（显式 setter 规避 `isXxx()` Kotlin 合成属性坑）；`VntServerAddress.error()` + token 非空双防线 |
| `VntMeshController` | `fun interface PeerListProvider { fun list(): Array<PeerRouteInfo>? }`；`refreshOnce()` 里 `direct = route?.metric?.toInt() == 1`；前台 5 秒刷新守卫 + generation 校验不变 |
| `CommandModule` / catalog | `token`（兼容 `network_code` 别名）、裸 `host:port`、`tcp` 三态参数；`vnt_start/vnt_stop` 同步写 `autoStart` |
| `VntPages.kt` | 配置弹窗 Token / 裸地址 / UDP-TCP 开关 / cipherModel / MTU / STUN；移除 2.x 速率·RTT·NAT 卡片 |

#### 6.8.2 改后文件指纹（sha256 前 16 位 / 行数）

| 文件 | sha256(前16) | 行 |
|---|---|---|
| `app/build.gradle.kts` | `feb8de9056ba4593` | 127 |
| `app/proguard-rules.pro` | `6a1930b719c17581` | 7 |
| `app/.../vnt/VntMeshState.kt` | `93c211e8328285ef` | 65 |
| `app/.../vnt/VntMeshPrefs.kt` | `654d83700d7240d5` | 214 |
| `app/.../vnt/VntConfigBuilder.kt` | `07a24f2367753876` | 63 |
| `app/.../vnt/VntMeshController.kt` | `2f824fd92d5359c9` | 298 |
| `app/.../vnt/VntVpnService.kt` | `d3c5b0700cd65fe7` | 369 |
| `app/.../vnt/VntAutoStart.kt` | `4499b73babd6aca1` | 46 |
| `app/.../ui/VntPages.kt` | `88a76f7929db77f6` | 575 |
| `app/.../di/CommandModule.kt` | `58d2363278664686` | 1894 |
| `java/top/wherewego/vnt/jni/Vnt.java` | `ed605054f24a4373` | 60 |
| `jniLibs/arm64-v8a/libvnt_jni.so` | `53dcc30a81efc05e` | 2,729,632 B |
| `jniLibs/x86_64/libvnt_jni.so` | `fac9d1ee9f40911f` | 2,373,272 B |
| `server/app/core/command_catalog.py` | `90a14c819fceb2e5` | 453 |
| `tools/vnt/verify_so.py` | `7ec641c9e824e36d` | 242 |
| `docs/research/vnt-mesh-integration.md` | `34dd7b92ee2d23b9` | 791 |
| `docs/api/commands/system.md` | `faaf986dfc32d3ea` | 149 |
| `docs/INDEX.md` | `764176e033f51ec4` | 77 |
| `docs/CHANGELOG.md` | `56b4dc144271b695` | 976 |
| `docs/dev/crash-ledger.md` | `0866336fa33f6c36` | 142 |
| `docs/dev/heartbeat-inventory.md` | `fa23530723a982b8` | 555 |
| `assets/design/ui-mockup-vnt-mesh.html` | `5deca1539d77e92a` | 320 |
| `server/data/updates/update.json` | `d099494143b7d482` | 4 |

> 注：本表指纹为 §6.8 回填时的工作区快照；本文件自身在回填后会变化，以远端 commit 为准。

#### 6.8.3 本地验证结果（2026-11-02）

- `:app:compileDebugKotlin`：**BUILD SUCCESSFUL**（3m32s，gradle rc=0，无 `e:` 错误，仅既有 deprecation 警告）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**（2m15s，含 `compileDebugJavaWithJavac` 编译 14 个 1.x Java 绑定类）；仅有已知无害警告 `Unable to strip ... libvnt_jni.so`（AGP 按原样打包）。
- `tools/vnt/verify_so.py`：两套 so 全 PASS —— 6 个 `Java_top_wherewego_vnt_jni_Vnt_*` 导出符号 ↔ 6 个 native 声明零缺失；ELF64 / AArch64 / x86-64；**4KB 页对齐**（非 2.x 的 16KB）。
- 本地 APK 40,093,287 B：`arm64-v8a` / `x86_64` 各 6 个 so，`armeabi-v7a` / `x86` 为 0；两份 `libvnt_jni.so` 与 `tools/vnt/artifacts/v1.2.9/` 逐字节一致；21 dex + Manifest + resources.arsc；`versionName=0.2.32`；AXML 解析器确认 specialUse 四件套齐全（`foregroundServiceType` = 整数 flag `0x40000000`，故二进制明文搜不到属正常）；dex 中无 `Lcom/vnt/` 残留、`top/wherewego/vnt/jni` 命中 3 个 dex。
- 原型 HTML：`ul/li/div/text/svg/b/code` 标签全部闭合 OK。

#### 6.8.4 发布链路结果（按 `docs/dev/release-pipeline.md` 铁律）

| 项 | 结果 |
|---|---|
| 本地提交 | `19b9a6e`（52 文件：17 A / 12 D / 23 M，中文详细 message 见 `.workbuddy/vnt_commit_msg_v0232.txt`） |
| API 通道发布 | 沿用 `.workbuddy/release_v0232_retry.py`（显式清代理 + 6 次指数退避重试 + blob 0.3s 限速 + PATCH 前二次校验 main）；**一次成功，无 `RemoteDisconnected`** |
| 远端 commit / tag | `11c1f1746e4a165713267401b8f224fef95d64c6` / `v0.2.32` |
| CI | Actions run `41367207306`，`event=push` 由 tag **自动触发**（无需 dispatch），`conclusion=success`，11 步全 OK 含 `Create Release` |
| Release 资产 | `app-debug.apk` 40,093,287 B，`published=2026-11-02T00:31:15Z` |
| 下载 | gh-proxy `v4.gh-proxy.org` http=200 / 3.05s → `releases/app-debug-v0.2.32.apk`，sha256 `7457a6f2f4cd69069559875f8190ec1a49e8d929d58d6eb5ade82615cb0c8a88` |
| CI APK 内容校验 | 与本地一致：2 套 ABI、so 与官方 v1.2.9 一致、`versionName=0.2.32`、不含 0.2.31、无 `com/vnt` 残留 |
| 服务端更新目录 | `server/data/updates/bridge-v0.2.32.apk`（40,093,287 B，被 `.gitignore:53` 忽略）；`update.json → 0.2.32` |
| 发布后追平 | ① 清代理直连 `git fetch` 一次成功 `0f7f509..11c1f17`；`git diff --ignore-cr-at-eol --stat HEAD 11c1f174` **为空**；校验 `origin/main == 11c1f174…` 后才 `reset --hard` → `HEAD=11c1f174…`、工作区干净（「fetch 失败绝不 reset」已遵守） |

#### 6.8.5 真机验证结论（**待做，非失败**）

§6.7 的 6 条回滚信号均未触发；以下仍需用户真机环境：
1. 两套 ABI 真机安装 + `System.loadLibrary("vnt_jni")` 加载；
2. 与存量 1.x 客户端互 ping（`token/password/cipherModel/serverEncrypt/finger` 逐项一致）；
3. 冷启动自动开始 / 点停止后下次不自动开始；
4. `dumpsys batterystats` 开关省电对照；
5. Android 15+ 16KB 页设备对 4KB 对齐 so 的兼容性（官方 1.x 产物固有限制，见报告 §13.6）。

### 6.9 实施清单（逐项打勾）

| # | 动作 | 状态 |
|---|---|---|
| 1 | 删除 `com/vnt/*` 11 个类 + 3 套 2.x so | ✅ |
| 2 | 拷入 `v1.2.9` 14 个 Java 绑定（`Vnt.java` 补 `System.loadLibrary`）+ 2 套 so | ✅ |
| 3 | `build.gradle.kts`：0.2.32、abiFilters 2 套 | ✅ |
| 4 | `proguard-rules.pro`：keep 包名改 `top.wherewego.vnt.jni.**` | ✅ |
| 5 | 重写 6 个 `my.bridge.com.vnt` 文件（1.x Config / CallBack / list() 时序） | ✅ |
| 6 | 重写 `VntPages.kt`（1.x 字段、移除速率/RTT/NAT 的 2.x 专属 UI） | ✅ |
| 7 | `CommandModule.kt` / `command_catalog.py` / `system.md` 参数与文案改 1.x | ✅ |
| 8 | `verify_so.py` 适配 1.x（6 个 JNI 符号、2 套 ABI） | ✅ |
| 9 | 文档：报告 §十三、INDEX、CHANGELOG、崩溃/心跳台账、原型页脚、update.json、工作日志 | ✅ |
| 10 | `compileDebugKotlin` + `assembleDebug` + APK 静态自检 | ✅ |
| 11 | 发布 v0.2.32（铁律）+ 下载 + 追平本地 git + 回填 §6.8 | ✅ |

---

## 七、VNT 断线自动重连（v0.2.38，2026-11-02 晚）

### 7.0 触发本次变更的用户诉求与诊断结论

**现象**：切网（WiFi↔蜂窝）/ 断网一段时间恢复后，VNT 不自动重连——界面「组网失败 Disconnect」，按钮恢复「开始组网」，手动点即成功。

**根因链**：
1. 网络切换 → native 1.x 回调 `CallBack.error`，错误码 **2=Disconnect**（`ErrorInfo.java`）→ `runtimeError` 置位；
2. `VntVpnService.runConnect` 的 awaitTimeout 循环检测到 runtimeError 即 **throw** → `onError("组网失败：Disconnect")` → finally 释放 native/pfd + **stopSelf()** → 服务死亡，ERROR 终态，无任何重试；
3. 全链路无补偿：`vnt/` 包无 NetworkCallback（AudioRelayService/WechatRuntime 均有，唯 VNT 没有）；`VntAutoStart` 仅 MainActivity.onStart 冷启动触发；`START_NOT_STICKY` 进程死也不复活；
4. 官方 VntApp 能恢复 = **START_STICKY + null intent 自动恢复**（报告 §5.2 有记录）；v0.2.30 为「退出即整体结束」有意删掉 STICKY，漏了断线自恢复这条官方靠 STICKY 实现的路径，且未在应用层补替代。

**修复方案（用户拍板「进程内监督重连」）**：worker 内监督重连循环（服务不 stopSelf，天然绕开 Android 12+ 后台 FGS 限制）+ 错误分类（TokenError/InvalidIp/establish null 不可重试，其余可重试）+ 指数退避 2/4/8/16/30/60s 封顶 + `registerDefaultNetworkCallback` onAvailable 立即重试并重置退避 + 新增 `RECONNECTING` 状态（UI/admin 规则/文档联动）。

### 7.1 改前原文逐字固化（既有文件，git 已跟踪；基线 commit `6f12c26`）

改前 sha256：

```
93c211e8328285efe72bab31af22d83ea4989a9f7651dd63af7d9aeab9d44e54  app/src/main/java/my/bridge/com/vnt/VntMeshState.kt
9108ea3273baced251fce4f0f2db39ebe6c429351bacd670dbd8e912f1289653  app/src/main/java/my/bridge/com/vnt/VntMeshController.kt
c8cade7b566e8afc9bccb263f3c283ff64978431b20ed55fb4482482a3ccc852  app/src/main/java/my/bridge/com/vnt/VntVpnService.kt
4499b73babd6aca11cd8e273beeaf0183c67020e2a168475b986096f152fb582  app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt
88a76f7929db77f68f0ae065f45fb48e8be48649816c819fa118effe0a7944b8  app/src/main/java/my/bridge/com/ui/VntPages.kt
4bf80af2a8468855ccf4798a61dead1ef6ef09733edf94c1f8ed830a82a6e31a  app/src/main/java/my/bridge/com/di/CommandModule.kt
4684b98992aaa1f9226c52fe0a5b3b2c8312e24f2c1b3f226494ede1b54e6754  admin/src/views/command/index.vue
a982e69bc23321305f1bfd5672c4d57c0ea456bb6d34c14df04098c2f063a154  docs/api/commands/system.md
```

完整原文以 `git show 6f12c26:<path>` 为准（全部 git 跟踪、工作区干净），此处固化**改动点**摘录：

**① VntMeshState.kt — 枚举与状态字段**

```kotlin
enum class VntStatus {
    /** 未连接（默认）。 */
    STOPPED,

    /** 已下发 START，等待 native 注册并在回调里建立 VPN 接口。 */
    STARTING,

    /** 已连接，虚拟 IP 可用。 */
    RUNNING,

    /** 正在停止。 */
    STOPPING,

    /** 启动/运行期出错，[VntMeshState.message] 为原因。 */
    ERROR,
}
```

```kotlin
    /** 状态/错误说明（显示在状态卡下方）。 */
    val message: String = "",
    /** 最后一次成功刷新时间（System.currentTimeMillis），0 = 从未刷新。 */
    val lastUpdatedAt: Long = 0L,
) {
    val isRunning: Boolean get() = status == VntStatus.RUNNING
}
```

**② VntMeshController.kt — start() 守卫 / statusText()**

```kotlin
    fun start(context: Context): Boolean {
        val s = _state.value.status
        if (s == VntStatus.RUNNING || s == VntStatus.STARTING) return true
```

```kotlin
            VntStatus.STARTING -> "正在建立连接（${s.message}）"
            VntStatus.STOPPING -> "正在停止（${s.message}）"
            VntStatus.STOPPED -> "未连接"
            VntStatus.ERROR -> "异常：${s.message}"
```

**③ VntVpnService.kt — runConnect 整体（本次主战场）**

```kotlin
            // 运行期循环：awaitTimeout(2000) 是 1.x 实例保活与接收停止事件的唯一机制，
            // 不发网络包；设备列表刷新由 Controller 在「前台 + 页面可见」时另行调度。
            while (running && gen == generation) {
                runtimeError?.let { throw IllegalStateException(it) }
                val out = nativeVnt.awaitTimeout(2000)
                if (out || gen != generation) break
            }
        } catch (e: Throwable) {
            failed = true
            DiagLog.e(TAG, "组网失败", e)
            VntMeshController.onError("组网失败：${e.message}")
        } finally {
            // 到这里 awaitTimeout 已返回（或构造抛异常），native 线程不再使用该对象，
            // 可以安全释放；shutdown()/onDestroy() 只发 stop 信号，不在这里之外 close。
            synchronized(nativeLock) { vnt = null }
            runCatching { v?.stop() }
            runCatching { v?.close() }
            val f = synchronized(nativeLock) {
                val x = pfd
                pfd = null
                x
            }
            runCatching { f?.close() }

            VntMeshController.clearProvider()
            // v0.2.31 修的坑沿用：失败时**保留 ERROR 状态**，别被 onStopped() 覆盖成「未连接」。
            if (!failed) VntMeshController.onStopped()
            running = false
            stopSelf()
        }
```

另：`error` 回调里 `if (ready) runtimeError = text else startupError = text`（语义保留，本次增加错误码记录）；`onCreate`/`onDestroy` 原无网络回调注册；`startConnect` 原直接起 worker 跑 `runConnect(gen)`。

**④ VntAutoStart.kt — 守卫**

```kotlin
        val st = VntMeshController.state.value.status
        if (st == VntStatus.RUNNING || st == VntStatus.STARTING) {
            DiagLog.i(TAG, "自动组网：已在运行/启动中，跳过")
            return
        }
```

**⑤ VntPages.kt — 主按钮条件 / 状态卡 when / 转圈条件 / 底部说明**

```kotlin
            if (state.status == VntStatus.RUNNING || state.status == VntStatus.STARTING) {
```

```kotlin
        VntStatus.STARTING -> Triple(VntBlue, "正在建立虚拟网络…", state.message)
        VntStatus.STOPPING -> Triple(VntGray, "正在停止…", state.message)
        VntStatus.ERROR -> Triple(VntRed, "组网异常", state.message)
        VntStatus.STOPPED -> Triple(VntBlue, "组网未连接", "点击下方「开始组网」建立虚拟局域网")
```

```kotlin
            if (state.status == VntStatus.STARTING) {
                CircularProgressIndicator(
```

```kotlin
            "组网是基础设施：开始后切 Tab / 退后台不中断，只有点「停止组网」或退出应用才结束。" +
```

**⑥ CommandModule.kt — executeVntStart 幂等守卫**

```kotlin
            val st = VntMeshController.state.value.status
            if (st == VntStatus.RUNNING || st == VntStatus.STARTING) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：组网已在运行（${VntMeshController.statusText()}）；" +
                        "本次参数未生效、未改动自动组网记忆。如需变更配置请先停止再开始",
                )
            }
```

**⑦ admin/src/views/command/index.vue — VNT 状态联动**

```typescript
type VntState = 'running' | 'starting' | 'stopping' | 'stopped' | 'error' | 'unknown'

const VNT_STATE_RULES: Record<string, VntState[]> = {
  vnt_start: ['stopped', 'error'],
  vnt_stop: ['running', 'starting', 'stopping'],
}

const VNT_STATE_LABELS: Record<VntState, string> = {
  running: '运行中',
  starting: '正在建立连接',
  stopping: '正在停止',
  stopped: '未连接',
  error: '异常',
  unknown: '未知',
}
```

```typescript
const vntTagType = computed(() => {
  switch (vntState.value) {
    case 'running':
      return 'success'
    case 'starting':
    case 'stopping':
      return 'warning'
    case 'error':
      return 'danger'
    default:
      return 'info'
  }
})
```

```typescript
function parseVntStatus(message: string): VntState {
  const m = message || ''
  if (m.startsWith('运行中 ·')) return 'running'
  if (m.startsWith('正在建立连接')) return 'starting'
  if (m.startsWith('正在停止')) return 'stopping'
  if (m.startsWith('未连接')) return 'stopped'
  if (m.startsWith('异常：') || m.startsWith('执行失败')) return 'error'
  return 'unknown'
}
```

注：`type SshState = VntState`（SSH 复用 VNT 五态口径），SSH_STATE_LABELS 为 `Record<SshState, string>`，加 `reconnecting` 枚举后 SSH 标签表须同步补键（SSH 永远不会产生该态，仅为类型完整）。

**⑧ docs/api/commands/system.md — 「状态联动、幂等守卫与强制下发」小节**：补 RECONNECTING 状态语义与命令联动（改前原文见 `git show 6f12c26:docs/api/commands/system.md`）。

### 7.2 新增文件清单

无（全部为既有文件修改；不新增文件、不新增依赖、不动 Manifest/ABI/so）。

### 7.3 回滚配方

```bash
git checkout 6f12c26 -- \
  app/src/main/java/my/bridge/com/vnt/VntMeshState.kt \
  app/src/main/java/my/bridge/com/vnt/VntMeshController.kt \
  app/src/main/java/my/bridge/com/vnt/VntVpnService.kt \
  app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt \
  app/src/main/java/my/bridge/com/ui/VntPages.kt \
  app/src/main/java/my/bridge/com/di/CommandModule.kt \
  admin/src/views/command/index.vue \
  docs/api/commands/system.md
```

**触发回滚信号**：
1. `compileDebugKotlin` 失败且 30 分钟内无法定位；
2. 真机出现「断线重连风暴」（DiagLog 刷屏组网失败、耗电异常上升）；
3. 重连循环内存泄漏（native 实例未释放导致 `close()` 崩溃 / tombstone）；
4. 点「停止组网」后服务不死（监督循环退不出）或通知残留；
5. 用户明确要求撤销。

### 7.4 改后回填

**实现要点**：
- `VntVpnService`：worker 由「一次 runConnect」改为监督循环 `runSupervisor(gen)` +
  `tryConnectOnce(gen)`。可重试失败 → `onReconnecting(attempt, delaySec, reason)` +
  通知「断线重连中 · 第 N 次」→ 退避等待（250ms 轮询，可被停止/接管/网络恢复提前打断）
  → 下一轮重建；**服务不停、不 stopSelf**。致命错误（`TokenError` / `InvalidIp` /
  `FatalVntException`=establish null）→ `onError()` + stopSelf。
- 错误码记录：`error` 回调在原文案之外增记 `startupErrorCode` / `runtimeErrorCode`，
  分类以错误码为准（`Disconnect`=2 等可重试），普通异常默认可重试。
- `registerDefaultNetworkCallback` 在 `onCreate` 注册、`onDestroy` 注销；onAvailable
  只置 `networkJustRecovered` 标志，监督循环轮询消费并重置退避计数。
- **接管竞态防护**：stop→快速 start（restart 流程）时旧 worker 可能仍在退避/构造中。
  处理 = ① 各 attempt 的 finally 只在 `gen == generation` 时清共享字段（vnt/pfd），
  关闭只认**本轮**局部 `localPfd`；② 旧 supervisor 退出时若 `running` 已为 true
  （新 startConnect 接管）⇒ 不 releaseNative、不发布 STOPPED、不 stopSelf；
  ③ `hadConnected` / `localPfd` 用 AtomicBoolean / AtomicReference
  （generateTun 在 native 线程写、worker 线程读，需跨线程可见性）。
- `VntMeshState`：新增 `RECONNECTING` + `retryAttempt` / `retryDelaySec` +
  `isActive`（RUNNING/STARTING/RECONNECTING）。
- `VntMeshController`：`onReconnecting()`；`start()` 守卫含 RECONNECTING；
  `statusText()` 前缀 `断线重连中（第 N 次，Ns 后重试 · 原因）`（admin 解析契约）。
- `VntPages`：RECONNECTING 状态卡橙色 + 转圈、主按钮走 `state.isActive`（重连中仍显示
  「停止组网」）；底部说明补「断线后自动重连」；重连中保存配置 toast 提示下一轮自动生效
  （监督循环每轮重读 Prefs，无需手动 restart）。
- `CommandModule.executeVntStart`：幂等守卫把 RECONNECTING 视为「已在运行」，
  回执「重复下发被忽略：组网已在运行/自动重连中」（详情弹窗前缀分类仍兼容）。
- `VntAutoStart`：RECONNECTING 跳过（监督循环已在工作）。
- admin：`VntState` 加 `reconnecting`；`vnt_stop` 允许态 + `VNT_STATE_LABELS` /
  `vntTagType`（warning）/ `parseVntStatus`（`断线重连中` 前缀）；
  `VntResultDetailDialog.stateTagOf` 同步登记；`SshState=VntState` 类型耦合 ⇒
  SSH 标签表补 `reconnecting` 键（永不产生，仅类型完整）。

**改动文件清单（10 个，全部既有文件，无新增）**：
1. `app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`（主战场）
2. `app/src/main/java/my/bridge/com/vnt/VntMeshState.kt`
3. `app/src/main/java/my/bridge/com/vnt/VntMeshController.kt`
4. `app/src/main/java/my/bridge/com/vnt/VntAutoStart.kt`
5. `app/src/main/java/my/bridge/com/ui/VntPages.kt`
6. `app/src/main/java/my/bridge/com/di/CommandModule.kt`
7. `admin/src/views/command/index.vue`
8. `admin/src/components/VntResultDetailDialog.vue`
9. `docs/api/commands/system.md`
10. `docs/research/vnt-mesh-integration.md`（§13.8）

**本地验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL（含 Hilt/KSP，两轮，
第二轮覆盖全部最终改动）；admin 前端为 TS 常量/函数级改动，无新依赖。
**未做**：assembleDebug / 真机回归（交用户手动验收）。

**真机验收清单（用户手动）**：
1. WiFi→走出覆盖→蜂窝：界面应转「断线，正在自动重连…」，**服务不停止**，
   蜂窝就绪后自动恢复「已连接 · 虚拟IP」；不再需要手动点开始。
2. 断网数分钟再恢复 WiFi：同上，日志可见退避节奏（2/4/8/16/30/60s 封顶）。
3. 重连中点「停止组网」：服务立即停止、状态「未连接」、不再自动重连；
   下次冷启动也不自动开始（autoStart 已清）。
4. 重连中保存新配置：下一轮自动用新配置。
5. token 配错：应直接 ERROR（不无限重试）。
6. 退出应用：整体结束、无复活（START_NOT_STICKY 不变）。

---

## 八、v0.2.131：VNT 1.x → 2.x 升级（v2.0.12，2026-11-13）

### 8.0 触发本次变更的用户诉求与诊断结论

**背景**：1.x 服务端（31007）已永久下线。用户请服务端开发者把 VNTS 升级到 VNT 2.0
（`quic://039039.xyz:31008` + `tcp://039039.xyz:31008`），并提供了一份《bridge 安卓 APP 组网
VNT1 升 VNT2 改造指南》（该指南按用户口述估写，服务端/其它客户端信息正确，但**不了解本项目
真实代码**，仅作借鉴）。用户拍板：**先拉官方 v2.0.12 再动手、现在开工**。

**核心结论（本次是「反向恢复」而非「从零新写」）**：本项目 v0.2.30/31 时代**曾经就是 2.x 实现**
（`com.vnt.*` 11 类 + 2.x 业务层），v0.2.32 才因「存量客户端全 1.x 必须互通」回退到 1.x。
2.x 的全部代码完好保存在 git 历史 commit `0f7f5098`（tag v0.2.31）；2.x so 也已留存。
本次升级 = 反向走一遍 §六 的「2.x→1.x」配方，再叠加 v0.2.38 之后新增的「断线自动重连」逻辑。

**so 版本核实（v2.0.12 vs v2.0.11）**：官方 `vnt-dev/vnt` 最新 release 为 **v2.0.12**
（`/releases/latest` 指针滞后返回 v2.0.11，但 `/releases/tags/v2.0.12` 确有其物）。三套 so
（arm64-v8a / armeabi-v7a / x86_64）已下载并三重校验 PASS：
- sha256 与官方 `.sha256` 文件逐字节一致（arm64 `09049316…` / v7a `4cc3b22e…` / x86_64 `48504af5…`）；
- ELF machine 正确（183 / 40 / 62）；
- PT_LOAD 全 `0x4000`（16KB 对齐，Android 15+ 要求）；
- arm64 导出 23 个 `Java_com_vnt_*` 符号，与 v2.0.11 **完全一致** ⇒ `com.vnt.*` 封装层无需改动。

### 8.1 改前基线（git 100% 兜底）

- 基线 commit：**`c7ff577`**（"docs: 里程碑补脉络8…"）。改前所有文件均 git 已跟踪，回滚 = `git checkout c7ff577 -- <path>`。
- 改前 so：`app/src/main/jniLibs/{arm64-v8a,x86_64}/libvnt_jni.so`（1.x v1.2.9，2 套 ABI）。
- 改前绑定：`app/src/main/java/top/wherewego/vnt/jni/*`（1.x 14 类）。
- 2.x 参照：git 历史 `0f7f5098`（tag v0.2.31）里的 `com/vnt/*` 11 类 + 2.x 业务层 6 文件 + 2.x VntPages.kt。

### 8.2 改前关键文件指纹（sha256 前 16 位 / 行数，供回滚逐字比对）

| 文件 | 行数 | sha256 前 16 位 |
|---|---|---|
| `app/src/main/java/my/bridge/com/vnt/VntVpnService.kt` | 603 | `93d3d925e0d208a6` |
| `.../VntMeshController.kt` | 344 | `a24f158c84f5fd41` |
| `.../VntMeshState.kt` | 80 | `dd9f0b8aa303fdaf` |
| `.../VntMeshPrefs.kt` | 214 | `654d83700d7240d5` |
| `.../VntConfigBuilder.kt` | 63 | `07a24f2367753876` |
| `.../ui/VntPages.kt` | 588 | `79c5f7135de8414c` |
| `.../di/CommandModule.kt` | 3363 | `6b4829981a8c3337` |
| `app/proguard-rules.pro` | 17 | `522d7e001a6acc15` |

### 8.3 改动清单（12 个文件，含恢复/删除/新增）

**① 核心库层（JNI 绑定 + so）**
1. 删 `app/src/main/java/top/wherewego/vnt/jni/*`（1.x 14 类）。
2. 恢复 `app/src/main/java/com/vnt/*`（2.x 11 类）← `git checkout 0f7f5098 -- app/src/main/java/com/vnt/`。
   ★ 该版已含 Android 适配（`VntConfig.put()` 包装 JSONException）与 `network_code` 字段，无需再改。
3. 删 1.x so，拷入 v2.0.12 三套 so 到 `jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libvnt_jni.so`
   （源 `tools/vnt/artifacts/v2.0.12/`，sha256 已核对一致）。
4. `app/proguard-rules.pro`：`-keep class top.wherewego.vnt.jni.**` → `-keep class com.vnt.** { *; }`。
5. `app/build.gradle.kts`：abiFilters 2 套 → 3 套（加回 `armeabi-v7a`）。

**② 业务层（6 个 `my/bridge/com/vnt/*`）**
6. `VntMeshState.kt`：2.x 字段（virtualIpv6/natType/serverRttMs/servers/networkCode）+
   补回 v0.2.38 的 `RECONNECTING`/`retryAttempt`/`retryDelaySec`/`isActive`。
7. `VntMeshPrefs.kt`：2.x 字段（networkCode/password/compress/fec/rtx/noPunch/certMode）+
   DEFAULT_SERVER=`quic://039039.xyz:31008,tcp://039039.xyz:31008` + DEFAULT_NETWORK_CODE=`wzsvnttoken`
   + DEFAULT_PASSWORD=`qwe951753` + **反向迁移**（旧 `token`→`networkCode`、旧 `039039.xyz:31007`→新地址）。
8. `VntConfigBuilder.kt`：2.x 版，server 字段按逗号 split 逐个 `addServer()`（多地址容灾）。
9. `VntMeshController.kt`：2.x `VntApi` 查询（getClientList/getServerList/getNatInfo/getRouteTable/
   getTrafficInfo/isDirect）+ 补回 `onReconnecting`/`clearApi`。
10. `VntVpnService.kt`：**主战场**——v0.2.38 监督循环框架 + 2.x 单次尝试时序
    （createNetwork→getNetwork→establishVpn→startTun→getApi→runChangeLoop）。
11. `VntAutoStart.kt`：保留当前版（已含 RECONNECTING 守卫，比 2.x 版新）。

**③ UI / 命令 / admin**
12. `ui/VntPages.kt`：2.x UI + 补 RECONNECTING 状态卡 + 主按钮走 `state.isActive` +
    默认占位/文案改 v2.0.12 与本项目服务端。
13. `di/CommandModule.kt`：executeVntStart 参数改 `network_code`（兼容 token）/ `password`/
    `protocol`（废弃忽略），`VntMeshConfig.token/tcp` → `networkCode`（无 tcp）。
14. `server/app/core/command_catalog.py`：vnt_start 参数 server（双地址占位）/
    network_code（兼容 token）/ password / protocol（废弃）。
15. `admin/src/components/VntResultDetailDialog.vue`：parseStatusFields 改 NAT/RTT；
    startParams 改 network_code；错误文案同步。

### 8.4 断线重连错误分类重映射（唯一「新写」的技术点）

| 阶段 | 2.x 现象 | 分类 |
|---|---|---|
| `VntManager.init()` false | 核心初始化失败 | **致命**（FatalVntException） |
| `createNetwork()` null | 配置/网络不可达 | **致命**（VntException） |
| `getNetwork()` 抛 VntException | 注册失败（token 错/地址错） | **致命** |
| `establishVpn()` null | VPN 授权被抢 | **致命**（FatalVntException） |
| `runChangeLoop` nextEvent 抛异常 | 断线 | **可重试** |
| nextEvent 返回 `instance_stopped` | 实例自行退出=断线 | **可重试** |

（对照 1.x：`CallBack.error(ErrorInfo)` 错误通道 → 2.x `nextEvent()` 事件循环；
1.x 的 `TokenError/InvalidIp` 枚举 → 2.x 的 `createNetwork null`/`getNetwork 抛异常`。）

### 8.5 回滚配方（100% 恢复到 v0.2.131 改前 = c7ff577）

```bash
cd /f/pythonWork/appClient
# ① 恢复所有 git 跟踪文件（含被删的 top/wherewego/vnt 与 1.x so）
git checkout c7ff577 -- app/src/main/java/top/wherewego/vnt/ \
  app/src/main/jniLibs/ app/proguard-rules.pro app/build.gradle.kts \
  app/src/main/java/my/bridge/com/vnt/ app/src/main/java/my/bridge/com/ui/VntPages.kt \
  app/src/main/java/my/bridge/com/di/CommandModule.kt \
  server/app/core/command_catalog.py admin/src/components/VntResultDetailDialog.vue
# ② 删除 2.x 新增（git 未跟踪，必须显式删）
git rm -r --quiet app/src/main/java/com/vnt/   # 2.x 绑定（若已 git add）
rm -rf tools/vnt/artifacts/v2.0.12/            # v2.0.12 so 存档
# ③ 校验
git status
```

### 8.6 触发回滚的信号（出现任一即停手并回滚）

1. 真机 `VntManager.createNetwork()` 持续返回 null / 抛 `Unknown protocol`（说明 so 或地址规则不匹配）。
2. `UnsatisfiedLinkError`（so 缺失 / proguard 未 keep / ABI 不匹配）。
3. 断线重连死循环（instance_stopped 反复触发且永不恢复）。
4. 服务端管理后台（`http://039039.xyz:29873`）看不到设备注册（network_code/password 不匹配）。
5. 打包体积异常（三套 so 全打进 APK 后超阈值）。

### 8.7 改后回填（2026-11-13 完成，本节待真机验收）

**本地验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL（两轮，首轮 `Os.close(Int)` 类型错——
`detachFd()` 后 fd 归 native、Java 侧不该 close，已删）；`command_catalog.py` `py_compile` PASS；
admin `vue-tsc -b` 类型检查 PASS。

**未做**：assembleDebug / 真机回归（交用户手动验收）。

**真机验收清单（用户手动，对应改造指南步骤 7/8）**：
1. 装 v0.2.131 APK → 点「开始组网」→ VPN 授权 → 拿到 `86.74.128.x` 虚拟 IP；
   **VNTS 2.0 管理后台（服务端开发者环境）`http://039039.xyz:29873`** 设备列表可见该设备。
2. 双向 ping（本机 ↔ 其它 2.x 客户端）通；SSH（8022）可达。
3. `vnt_status` 回执字段齐全（运行中 · 虚拟IP · NAT · 服务端RTT · 在线设备 N）。
4. **飞行模式 10 秒 → 恢复**：界面转「断线重连中」，服务不停止，自动恢复「已连接」（v2 核心收益）。
5. 杀进程重开 → 自动组网（若 autoStart 记忆开启）。
6. 重复 vnt_start → 幂等忽略（回执「重复下发被忽略」）。
7. token/network_code 配错 → 直接 ERROR（不无限重试）。

### 8.8 两个易混事实澄清（2026-11-13 用户追问，固化为记忆）

**① 虚拟 IP `86.74.128.x` 是谁指定的？**
- **不是客户端写死**，而是「可选覆盖 + 默认留空自动分配」。
- `VntConfigBuilder.kt:47`：`if (config.ip.isNotBlank()) builder.setIp(config.ip)` —— 只有用户在配置里
  **显式填了 IP** 才会传给服务端；`VntMeshConfig.ip` 默认是**空串**（自动分配）。
- `86.74.128.0/24` 这个网段是**服务端（VNTS 2.0）下发**的，客户端只是「拿到」服务端分配到的地址，
  网段归属权在服务端，不在客户端。
- 指南说明：`86.74.128.0/24` 已占用 `.226/.30/.68/.99/.24/.67`，**建议留空 ip 让服务端自动分配**
  （避免手动指定撞到已占用地址）。
- 验收含义：看到 `86.74.128.x` 就证明连上了**新 VNTS（v2.0 的服务端网段）**。

**② 管理后台端口为什么是 `29873`？**
- **`29873` 不是本项目（appClient）的端口，是服务端开发者自己的 VNTS 2.0 管理后台端口**，写在他的
  改造指南第 23 行：`服务端管理后台 http://039039.xyz:29873（账号 qwe159357 / qwe951753）`。
- 用途：登录查看「哪些设备注册进了这个 VNT 网络」，联调时核对设备是否上线。
- 与本项目的关系：**无关**。本项目自己的 admin 走 `https://appclient.039039.xyz:15443`（生产单入口，
  nginx 反代），服务端 API 上游 15160；`29873` 纯粹是服务端作者环境的端口号，由他定。
- ⚠️ 上一轮总结把「服务端管理后台 29873」直接写进验收清单、未说明是「服务端作者的环境」，易被误读成
  本项目后台，此处正式澄清。

### 8.9 通知更新 bug 修复（2026-11-13 用户真机反馈，切网重连后通知卡「正在启动」）

**现象**：WiFi 组网成功 → 切移动蜂窝 → 短暂断线 → 自动重连成功（系统已提示「已激活为 PN、
已连接到 Bridge VPN」），但 **VNT 前台通知仍停在「VNT 正在启动 / 正在准备虚拟网络」**，未及时更新为
「已连接」。同一通知栏还有另一条「Bridge 后台服务运行中」——那是**另一个前台服务**（Bridge 主保活），
与 VNT 通知无关，两条是独立的 NOTIFICATION_ID。

**根因（三重叠加）**：
1. **更新通知用错了 API**：`promoteForeground()` 全程用 `startForeground(NOTIFICATION_ID, …)` 刷通知。
   VNT 服务切网重连时**不销毁、不重建**（`onCreate` 只走一次），「正在启动 → 断线重连中 → 已连接」
   的状态切换全程都在用 `startForeground` 硬刷。**部分国产 ROM（华为/小米/OPPO 等）上，服务已在前台时
   重复 `startForeground` 不会刷新已有通知内容**，导致通知卡在最早一次文案。
2. `onStartCommand` 每次无条件 `promoteForeground("正在准备虚拟网络…")`，切网时若 keepalive/系统
   重新 `startForegroundService`，通知先被刷回「正在启动」，再等连接成功更新（又被根因 1 卡住）。
3. 连接成功更新（`tryConnectOnce` 里 373 行）包在 `if (gen == generation)` 守卫内，配合前两点，
   即使走到也刷不动。

**改前原文**（`app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`，v0.2.131 提交 `96b0b5c`）：
```kotlin
private fun promoteForeground(text: String, connected: Boolean) {
    try {
        startForeground(NOTIFICATION_ID, buildNotification(text, connected))
    } catch (e: Exception) {
        DiagLog.e(TAG, "startForeground(specialUse) 失败，stopSelf 防崩", e)
        stopSelf()
    }
}
```

**改后**：拆两套 API——首次 `startForeground` 履行前台义务，之后全部 `NotificationManager.notify`；
新增 `foregroundStarted` 标志；`onDestroy` 补 `stopForeground(STOP_FOREGROUND_REMOVE)` 并复位标志。
```kotlin
private var foregroundStarted = false

private fun promoteForeground(text: String, connected: Boolean) {
    try {
        val notification = buildNotification(text, connected)
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        }
    } catch (e: Exception) {
        DiagLog.e(TAG, "startForeground(specialUse) 失败，stopSelf 防崩", e)
        stopSelf()
    }
}
```

**回滚配方**：`git checkout 96b0b5c -- app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`。

**验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL。真机验收待用户装新 APK 后切网复验。

---

### 8.10 通知「一直显示成功」不随断线更新（v0.2.133，探活缺失）

**现象**（真机，切网/断网后）：界面组网页已显示「未连上服务端」，但通知栏 VNT 通知仍停在
「已连接 · 86.74.128.88」，一直不更新为断线状态。

**根因**：通知与界面用了**两套不对称的断线感知机制**——
- 界面：`VntMeshController.refreshOnce` 每 5 秒主动 RPC `getClientList()`，断网立即感知「未连上服务端」；
- 通知：只靠 `VntVpnService.runChangeLoop` 里阻塞的 `net.nextEvent()` 被动等 native 发
  `instance_stopped` 事件。**`nextEvent()` 纯阻塞、无超时参数**（`VntNetwork.nextEvent()`），
  断线后 native 要等自己的 TCP 心跳超时才判定 → 才发 `instance_stopped`，期间 worker 一直卡在
  `nextEvent`，走不到监督循环的断线分支，通知保持「已连接」。

**改法（方案 A：探活线程发现失联 → 主动 stop 触发既有重连链）**：`VntVpnService` 新增探活线程
`startProbe`/`stopProbe`——连接成功后启动，每 `PROBE_INTERVAL_MS`（5s，与界面刷新同频）查一次
`api.getServerList()`，若服务端**全部 `connected=false`**（`servers.none { it.isConnected }`，
列表为空不误判），判定断线，主动 `net.stop()` 让阻塞中的 `nextEvent()` 立即返回
`instance_stopped`（或抛异常）→ 复用既有监督循环的「断线重连」链路（更新通知「断线重连中」+
退避重连）。代次校验用 `gen`，`stopProbe` 在 `tryConnectOnce` 的 finally 与 `onDestroy` 调用，
绝不误停新代次实例。

**关键事实核验**：官方参考实现的停止机制（调研 §6.3）本就是「`cancellationRequested=true`（让阻塞
的 nextEvent 退出）→ `VntNetwork.stop()`」，即**另一线程调 stop 让 nextEvent 退出是官方支持的用法**；
且 stop 后 `nextEvent` 无论返回 `instance_stopped` 还是抛异常，`tryConnectOnce` 都能正确判可重试
（非 FatalVntException），两条路径都走重连，逻辑稳健。

**改前原文**（`VntVpnService.kt`，v0.2.132 提交 `aa0ce59`）：
- `tryConnectOnce` 连接成功后直接 `runChangeLoop(nativeNet, gen)`（无探活）；
- 无 `probeThread`/`startProbe`/`stopProbe`。

**新增/改动清单**：
- 字段 `probeThread: Thread?`；常量 `PROBE_INTERVAL_MS = 5_000L`（并入既有 companion object）。
- `tryConnectOnce` 连接成功块后加 `startProbe(nativeNet, api, gen)`；finally 首行加 `stopProbe()`。
- 新增 `startProbe`/`stopProbe` 两个方法；`onDestroy` 补 `stopProbe()`。

**回滚配方**：`git checkout aa0ce59 -- app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`。

**验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL。真机验收：组网成功 → 断网/切网 → 观察通知
应在 ≤5s 内从「已连接」更新为「断线重连中 · 第 N 次」。

### 8.11 探活误判抖动：「断线重连中/已连接」高频闪烁 + 永远「第 1 次」（v0.2.134）

**现象**（真机，连不到 VNTS 服务器时）：通知在「断线重连中 · 第 N 次」和「已连接」之间**高频
切换闪烁**（间隔约几百毫秒，而非预期的 5s 探活周期），且永远显示「第 1 次」；此时打开组网页也
疯狂闪烁。日志实证（f2c38ebee251a196 组网 tag）：

```
VNT 已连接：86.74.128.88/24
探活发现服务端全部失联，主动 stop 触发重连
组网实例已停止，退出变更循环
组网失败（可重试）：组网实例已停止（视为断线）；第 1 次重连将在 2s 后进行
VNT 已连接：86.74.128.88/24   ← 又连上 → 又探活失联 → 又 stop，死循环
```

**根因（两个叠加）**：
1. **探活误判（主因）**：`getServerList()` 在「连接刚建立 / 实例重建瞬间」会返回「全失联」的
   **过渡态**（native 内部状态尚未同步）。§8.10 的探活「单次失联即 stop」把这种几百毫秒的瞬时
   抖动当成真断线 → stop → 重连成功 → 又立即探活又误判 → 死循环（周期约 1s）。
2. **退避计数被错误重置**：`runSupervisor` 里 `if (res.hadConnected) attempt = 0`——每次 stop 前
   连接其实都成功过（`hadConnected=true`），把 `attempt` 归零，退避永远停在 2s 第一档，故永远
   「第 1 次」。

**改法（v0.2.134，用户拍板「连续 3 次失联」阈值）**：
- **探活加「连续确认」**：新增常量 `DOWN_CONFIRM_COUNT = 3`，探活线程用 `downCount` 累计
  「服务端全部 `connected=false`」的次数，**连续 3 次（约 15s）持续失联**才判定真断线并 stop；
  任一次发现「仍连」即清零。列表为空/查询异常不计入失联也不清零（保守，交给 nextEvent 兜底）。
- **首查延迟一个周期**：探活线程启动后先 `sleepInterruptible` 满 5s 再第一次 `getServerList()`，
  避开连接建立瞬间的过渡态。
- **退避计数修正**：`AttemptResult` 增 `stable` 字段，`isStable()` 判定「曾连上且存活 ≥ 一个探活
  周期（`PROBE_INTERVAL_MS`）」；`runSupervisor` 改为 `if (res.stable) attempt = 0`。这样「连上即断」
  的抖动不再重置退避，退避逐步 2→4→8s 拉大；真稳定连接后断线才从第 1 档重新数。

**改前原文**（`VntVpnService.kt`，v0.2.133）：
- `startProbe` 单次失联即 `net.stop()`，无 `downCount` 计数、无首查延迟；
- `runSupervisor` 里 `if (res.hadConnected) attempt = 0`；
- `AttemptResult` 无 `stable` 字段；`tryConnectOnce` 无 `connectedAtMs`。

**新增/改动清单**：
- 常量 `DOWN_CONFIRM_COUNT = 3`；
- `startProbe`：首查前 sleep 一周期 + `downCount` 连续计数；
- `AttemptResult` 增 `stable` 字段（默认 false）；`tryConnectOnce` 增 `connectedAtMs` 记录，
  两处 return 传 `isStable(hadConnected, connectedAtMs)`；
- 新增 `isStable()` 辅助方法；`runSupervisor` 改 `if (res.stable) attempt = 0`。

**回滚配方**：`git checkout <v0.2.133 commit> -- app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`。

**验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL。真机验收：连不到 VNTS 服务器时，通知应稳定
显示「断线重连中 · 第 N 次」，N 逐步递增（2s→4s→8s…），不再高频闪烁；组网页也不再疯狂刷新。

### 8.12 探活方向性错误：服务器断连被误判为「实例死亡」→ stop 重连死循环（v0.2.135，根治）

**现象**（真机，v0.2.134 仍刷屏）：日志显示「探活：服务端全部失联（连续 1/3→2/3→3/3 次）」在**同一秒内**
连续打出（而非每隔 5s），随即「探活确认真断线，主动 stop 触发重连」→「VNT 已连接」→ 又一轮，循环周期
仅 1~2s，通知最终停在「已连接」。手机实测：蜂窝网络正常（`rmnet_data2` 10.81.159.2/30），VNT 的 tun0 接口
CONNECTED（86.74.128.88/24，MTU 1380），但 VNTS 服务器（039039.xyz:31008）连不上。

**根因（两个叠加，且 §8.11 的「连续确认」没解决根本问题）**：
1. **探活方向性错误（根本）**：`getServerList().connected=false` 反映的是「与 VNTS 服务器的实时连接断了」，
   **不是「VNT 实例死亡」**。在「连不到服务器」场景，实例其实**还活着**（已拿 VIP、TUN 正常），只是等服务器
   恢复。§8.11 的探活仍「失联→stop→重连」，重连后服务器依然连不上 → `connected` 依然 false → 又 stop，
   **死循环本质未变**。实例真正死亡应由 `nextEvent()` 发 `instance_stopped` 驱动重连（既有正确机制），
   探活用 `getServerList()` 当重连依据是**方向错了**。
2. **探活 sleep 被 `networkJustRecovered` 打断**：`registerNetworkCallback` 的 `onAvailable` 会在 **VPN 接口
   （tun0）建立时**触发，把 `networkJustRecovered` 置 true；探活线程复用的 `sleepInterruptible` 里
   `if (networkJustRecovered) return true` 立即返回 → 探活等待被瞬间打断，变成 ~250ms 一轮疯狂轮询，
   「连续 3 次」在 1 秒内就凑齐（所以日志里 1/3→2/3→3/3 挤在同一秒）。

**改法（v0.2.135，用户拍板「探活只更新通知不 stop」）**：
- **探活只更新通知，不 stop 实例**：连续 `DOWN_CONFIRM_COUNT` 次（约 15s）服务端全失联 → 通知改
  「服务器连接中断 · 等待服务端恢复」；任一次发现「仍连」→ 通知改回「已连接」。**不再调 `net.stop()`**，
  实例死亡交给 `nextEvent` 的 `instance_stopped` 走既有监督重连。
- **探活独立 sleep**：新增 `probeSleep(gen, seconds)`，**只**响应「代次变了」与「线程中断」，**不响应**
  `networkJustRecovered`；探活用稳定固定周期，不再被 onAvailable 打断。
- **通知标题三分支**：`buildNotification` 的 `when` 增「服务器连接中断」分支（title「VNT 服务器连接中断」）。

**改前原文**（`VntVpnService.kt`，v0.2.134）：
- `startProbe` 连续 3 次失联后 `net.stop()`（`if (downCount >= DOWN_CONFIRM_COUNT) { runCatching { net.stop() }; break }`）；
- 探活等待复用 `sleepInterruptible`（受 `networkJustRecovered` 影响）；
- `buildNotification` 无「服务器连接中断」标题分支。

**新增/改动清单**：
- `startProbe` 重写：删 `net.stop()`，改为 `promoteForeground("服务器连接中断…", false)` / 恢复
  `promoteForeground("已连接 · …", true)`；
- 新增 `probeSleep()`（探活专用，不响应 networkJustRecovered）+ `registeredIpOrUnknown()`；
- `buildNotification` 增「服务器连接中断」标题分支；
- 常量 `DOWN_CONFIRM_COUNT` 注释语义更新。

**回滚配方**：`git checkout <v0.2.134 commit> -- app/src/main/java/my/bridge/com/vnt/VntVpnService.kt`。

**验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL。真机验收：连不到 VNTS 服务器时，通知应**稳定**显示
「服务器连接中断 · 等待服务端恢复」（约 15s 后），不再高频闪烁、不再「断线重连中/已连接」来回跳；
服务器恢复后通知自动改回「已连接」；组网页不再疯狂刷新。

