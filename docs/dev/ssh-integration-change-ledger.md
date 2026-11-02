# SSH 服务器（sshd）集成改前留档台账

> 依据「改前留档约定」：任何修改前逐字固化改前原文 → 动手改 → 回填改后 → 附回滚配方与触发回滚信号。
> 本台账对应 `docs/research/sshd-integration.md`（调研 + 决策已拍板）的 P0 实施。
>
> **基线**：git `HEAD = 1a2fcd2`（「后台：组网状态未知时禁用开始/停止组网下发」，其父 `1129ae4`、`b0e54c5`）。
> 改前工作区状态：仅本调研新增 `docs/research/sshd-integration.md`、`assets/design/ui-mockup-ssh-page.html`（未跟踪）与 `docs/INDEX.md`（登记行）。

## §一 回滚配方（全局）

```bash
# 回滚所有被修改的既有文件（全部 git 跟踪）
git checkout 1a2fcd2 -- \
  gradle/libs.versions.toml \
  app/build.gradle.kts \
  app/proguard-rules.pro \
  app/src/main/AndroidManifest.xml \
  core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt \
  app/src/main/java/my/bridge/com/di/CommandModule.kt \
  app/src/main/java/my/bridge/com/MainActivity.kt \
  app/src/main/java/my/bridge/com/ui/MainScreen.kt \
  app/src/main/java/my/bridge/com/ui/SettingsPages.kt \
  server/app/core/command_catalog.py \
  admin/src/views/command/index.vue \
  docs/INDEX.md docs/api/commands/system.md docs/api/commands/README.md docs/dev/crash-ledger.md

# 删除本次新增文件（无 git 跟踪，删除即回滚）
# app/src/main/java/my/bridge/com/ssh/ 整个目录
# app/src/main/java/my/bridge/com/ui/SshPages.kt
# docs/dev/ssh-integration-change-ledger.md（本文件，如需）
```

**触发回滚的信号**：编译失败且无法在合理范围内修复 / 真机上 sshd 启动即崩且根因深不见底（如 desugaring 与 R8 冲突）/ 覆盖安装后其它前台服务（保活/VNT/媒体采集）出现异常。
**局部回滚**：只回滚出问题的文件即可，其余改动独立、无交叉依赖（除 Command.kt ⇄ CommandModule ⇄ catalog 三者需同时回滚）。

---

## §二 逐文件改前原文（逐字固化）

### 2.1 `gradle/libs.versions.toml`（追加 SSH 依赖段）

改前 `[versions]` 尾部（L40-47）：
```toml
# ---- 日志 ----
timber = "5.0.1"

# ---- 测试 ----
junit = "4.13.2"
androidxJunit = "1.2.1"
espresso = "3.6.1"
```
改前 `[libraries]` 尾部（L96-102）：
```toml
# 日志
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }

# 测试
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "androidxJunit", version.ref = "androidxJunit" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
```
（注：上段 androidx-junit 行实际为 `version.ref = "androidxJunit"`，逐字照原文件。）

### 2.2 `app/build.gradle.kts`（追加依赖 + packaging + desugaring）

改前 dependencies 尾部（L111-127）：
```kotlin
    // ---- 网络（NetworkModule 装配 Retrofit/Gson） ----
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)

    // ---- 快递扫码（CameraX 采集 + ML Kit bundled 识别）----
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
```
改前 buildFeatures 块（L71-75，packaging 块此前不存在）：
```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
```
改前 compileOptions 块（L66-70）：
```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
```

### 2.3 `app/proguard-rules.pro`（追加 keep 规则）

改前全文（7 行）：
```
# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
# VNT 组网（v0.2.32，1.x）：top.wherewego.vnt.jni.* 是官方 VntApp v1.2.9 的 JNI 绑定，
# native 侧符号按「包名+类名」硬编码（Java_top_wherewego_vnt_jni_Vnt_new0 等）。
# R8 混淆或移除会直接抛 UnsatisfiedLinkError。
-keep class top.wherewego.vnt.jni.** { *; }
```

### 2.4 `app/src/main/AndroidManifest.xml`（权限 + 服务 + receiver）

改前权限锚点（L29-34）：
```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```
改前 VNT 服务块（L207-225，其后紧跟 BridgeAccessibilityService 注释）：
```xml
        <!-- VNT 组网前台服务（v0.2.30）：Android 14+ specialUse 四件套
             ① FOREGROUND_SERVICE_SPECIAL_USE 权限（见上）
             ② foregroundServiceType="specialUse"
             ③ PROPERTY_SPECIAL_USE_FGS_SUBTYPE 说明用途
             ④ BIND_VPN_SERVICE 权限 + android.net.VpnService intent-filter
             VpnService.prepare() 授权由组网页经 Activity 弹系统框；返回 START_NOT_STICKY，
             进程被杀不自动复活（退出应用 / 切后台立即结束 为最高优先级）。 -->
        <service
            android:name="my.bridge.com.vnt.VntVpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="VNT virtual private network tunnel" />
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
```

### 2.5 `core-network/.../ws/Command.kt`（追加命令常量）

改前 L52-58：
```kotlin
        // ---- VNT 组网（v0.2.30）----
        const val VNT_START = "vnt_start"
        const val VNT_STOP = "vnt_stop"
        const val VNT_STATUS = "vnt_status"

        /** 从 command 消息的 data 解析；字段缺失返回 null。 */
```

### 2.6 `app/.../di/CommandModule.kt`（imports + 注册表 + 执行器）

改前 imports 锚点（L54-58）：
```kotlin
import my.bridge.com.vnt.VntMeshController
import my.bridge.com.vnt.VntMeshPrefs
import my.bridge.com.vnt.VntServerAddress
import my.bridge.com.vnt.VntStatus
import retrofit2.HttpException
```
改前注册表尾部（L226-237）：
```kotlin
        // ---- VNT 组网（v0.2.30）：三条命令走「catalog → Command.kt → 本执行器」三步法 ----
        Command.VNT_START to CommandExecutor { command ->
            executeVntStart(context, command)
        },
        Command.VNT_STOP to CommandExecutor { command ->
            executeVntStop(context, command)
        },
        Command.VNT_STATUS to CommandExecutor { command ->
            executeVntStatus(command)
        },
        )
    }
```

### 2.7 `app/.../MainActivity.kt`（冷启动恢复）

改前 L39-40：
```kotlin
    /** v0.2.31：自动组网每个 Activity 实例只尝试一次，避免旋转/回前台重复触发。 */
    private var vntAutoStartAttempted = false
```
改前 L117-130（onStart 全文）：
```kotlin
    /**
     * v0.2.31：冷启动自动组网。
     *
     * 放在 `onStart`（而非 `Application.onCreate`）：此时 Activity 已可见，
     * `startForegroundService` 不会撞 Android 12+ 后台启动限制；同时每个实例只试一次。
     * 具体守卫（记忆 / VPN 授权 / 失败不重试）见 [VntAutoStart.tryStart]。
     */
    override fun onStart() {
        super.onStart()
        if (!vntAutoStartAttempted) {
            vntAutoStartAttempted = true
            VntAutoStart.tryStart(this)
        }
    }
```

### 2.8 `app/.../ui/MainScreen.kt`（SSH 状态页覆盖层）

改前内容区分发尾部（L197-232 的 `when (selectedBottom) {...}` 闭合处，其后为退出提醒冒泡注释）：
```kotlin
                3 -> SettingsPage(
                    currentTab = settingsTab,
                    homeTabOrder = homeTabOrder,
                    homeTabNames = homeTabNames,
                    onHomeTabOrderChange = { order ->
                        homeTabOrder = order
                        TabOrderPrefs.save(context, order)
                    },
                    onHomeTabNameChange = { names ->
                        homeTabNames = names
                        TabOrderPrefs.saveNames(context, names)
                    },
                    onTabChange = { settingsTab = it },
                )
            }
```

### 2.9 `app/.../ui/SettingsPages.kt`（连接页新增 SSH 分区）

改前 L1339-1344（ConnectionSettings 的 LazyColumn 尾部）：
```kotlin
        // ===== 智能控制分区 =====
        item { SectionTitle("智能控制") }
        item {
            RelaySwitchSettings()
        }
    }
```

### 2.10 `server/app/core/command_catalog.py`（system 类别追加三条命令）

改前 L434-441（vnt_stop/vnt_status 条目及 COMMANDS 列表闭合）：
```python
    _cmd("vnt_stop", "停止组网", "system", "🛑",
         "停止 VNT 虚拟局域网并释放系统 VPN 通道；无平台限制，最可靠；"
         "★ v0.2.33：管理后台按 vnt_status 快照禁用重复下发（未组网时按钮不可用），"
         "确需绕过请用「强制下发」（需二次确认）；设备端在未组网时直接忽略，不再拉起空前台服务、不清除开机自动组网记忆"),
    _cmd("vnt_status", "组网状态", "system", "📶",
         "查询 VNT 组网状态（状态 / 虚拟 IP / Token / 协议 / 在线设备数 / 服务端版本）；"
         "只读本地缓存快照，**不触发 Vnt.list()**（详细设备列表仅在组网页可见时刷新）"),
]
```

### 2.11 `admin/src/views/command/index.vue`（动手前逐字补录，2026-11-02）

将修改的锚点区域（改前原文逐字如下，行号以基线 1a2fcd2 为准）：

**顶栏徽标区（L33-51）**：
```html
            <!-- v0.2.33：组网状态徽标（vnt_status 回执快照，悬停看完整回执） -->
            <el-tooltip
              v-if="form.deviceId"
              :content="vntSnapshot?.message || '尚未取得组网状态，点右侧刷新按钮查询'"
              placement="top"
              :show-after="300"
            >
              <el-tag :type="vntTagType" size="small" class="music-tag">
                🕸️ 组网：{{ vntStateText }}
              </el-tag>
            </el-tooltip>
            <el-button
              v-if="form.deviceId"
              :icon="Refresh"
              size="small"
              circle
              title="刷新设备状态（播放 / 组网）"
              @click="refreshDeviceStates"
            />
```

**回执列详情按钮（L359-368）**：
```html
              <!-- v0.2.33+：VNT 三条命令的回执单独设计详情弹窗；pending 行不显示 -->
              <el-button
                v-if="canViewVntDetail(row)"
                size="small"
                type="primary"
                link
                @click="vntDetail?.show(row)"
              >
                查看详情
              </el-button>
```

**详情组件 import 与判定（L437-443）**：
```ts
// VNT 三条命令的「查看详情」：每条单独设计弹窗（见 VntResultDetailDialog.vue）。
// 只在 VNT 三条、且已有回执时显示，pending 行点开没有内容。
const VNT_DETAIL_COMMANDS = ['vnt_status', 'vnt_start', 'vnt_stop']
const vntDetail = ref<{ show: (row: CommandRecord) => void } | null>(null)
function canViewVntDetail(row: CommandRecord): boolean {
  return VNT_DETAIL_COMMANDS.includes(row.command) && !!row.result_message
}
```

**状态联动核心块（L794-800）**：
```ts
type VntState = 'running' | 'starting' | 'stopping' | 'stopped' | 'error' | 'unknown'

/** 需要按组网状态限制下发的命令 → 允许的设备状态。vnt_status 自身不受限。 */
const VNT_STATE_RULES: Record<string, VntState[]> = {
  vnt_start: ['stopped', 'error'],
  vnt_stop: ['running', 'starting', 'stopping'],
}
```

**showForceSend（L918-926）**：
```ts
const showForceSend = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd) return false
  // 组网状态未知 / 不匹配时下发按钮均被禁用，强制下发是唯一出口（需二次确认）
  if (VNT_STATE_RULES[cmd.name] !== undefined) return true
  if (MUSIC_STATE_RULES[cmd.name] !== undefined) return true
  // 采集互斥场景：目标设备有采集命令在途时，提供强制下发出口
  return CAPTURE_COMMANDS.includes(cmd.name) && !!pendingCapture.value
})
```

**forceSendTip（L929-936）**：
```ts
const forceSendTip = computed(() => {
  const name = selectedCommand.value?.name
  if (name && VNT_STATE_RULES[name] !== undefined) {
    if (forceSendDisabled.value) return '当前命令可直接下发，无需强制下发'
    return '开启后忽略后台记录的组网状态，直接下发开始/停止组网命令（需二次确认）；设备端仍按真实状态执行或忽略重复命令'
  }
  return '开启后忽略播放状态限制，音乐控制命令直接下发，由设备按真实状态执行（状态不匹配会回执失败原因）'
})
```

**isStateBlocked（L975-990，节选关键尾段）**：
```ts
  const musicAllowed = MUSIC_STATE_RULES[cmd.name]
  const vntAllowed = VNT_STATE_RULES[cmd.name]
  if (!musicAllowed && !vntAllowed) return false
  if (!form.deviceId) return true
```
```ts
  if (!vntAllowed) return false
  // 2026-11-02 用户拍板：unknown 一律禁用（含从未查询 / 回执超时 / 解析失败）。
  // VNT_STATE_RULES 不含 unknown ⇒ includes() 为 false ⇒ 这里返回 true（禁用）。
  // 出口由「强制下发」承担：命令被禁用时 forceSendDisabled=false，开关可勾选 + 二次确认，
  // 故仍满足「必可达（可接受延迟、不可永远访问不到）」硬约束。
  return !vntAllowed.includes(vntState.value)
}
```

**forceSendDisabled（L1003-1007）**：
```ts
const forceSendDisabled = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd || VNT_STATE_RULES[cmd.name] === undefined) return false
  return !isStateBlocked(cmd)
})
```

**handleSend 二次确认（L1326-1343，节选判定行）**：
```ts
  if (forceSend.value && VNT_STATE_RULES[cmd.name] !== undefined) {
```

**command_result 处理（L1452-1473）**：
```ts
        // v0.2.33：vnt_status 回执 → 解析并缓存组网状态，驱动开始/停止按钮联动
        if (
          msg.data.command === 'vnt_status' &&
          msg.data.device_id === form.deviceId
        ) {
          const state = msg.data.success ? parseVntStatus(msg.data.message || '') : 'unknown'
          setVntSnapshot(msg.data.device_id, state, msg.data.message || '')
        }
        // 开始/停止回执后自动重新探测（状态收敛有延迟，1.5s 后再查一次）。
        // 失败回执同样重查：设备端守卫可能已把「重复下发」忽略，状态并未改变。
        if (
          (msg.data.command === 'vnt_start' || msg.data.command === 'vnt_stop') &&
          msg.data.device_id === form.deviceId
        ) {
          const dev = msg.data.device_id as string
          window.setTimeout(() => {
            if (form.deviceId === dev) {
              lastVntProbeAt = 0 // 绕过防抖，确保这次探测一定发出
              refreshVntStatus()
            }
          }, 1500)
        }
```

另将新建 `admin/src/components/SshResultDetailDialog.vue`（新增文件，删除即回滚）。

### 2.12 `docs/` 系列（INDEX / commands/system.md / commands/README.md / crash-ledger.md）

均为追加式改动，改前原文在动手时以 git diff 形式核对（`git diff 1a2fcd2 -- docs/` 可见全部追加），不逐字抄录。

---

## §三 新增文件清单（无改前原文，删除即回滚）

| 文件 | 职责 |
|---|---|
| `app/src/main/java/my/bridge/com/ssh/SshPrefs.kt` | Prefs：enabled 意愿/端口/密码/WakeLock 开关 |
| `app/src/main/java/my/bridge/com/ssh/SshHostKeyStore.kt` | hostkey 首次生成 + 持久化 + 指纹 |
| `app/src/main/java/my/bridge/com/ssh/SshServerController.kt` | 状态机 + 启停编排 + 会话计数 |
| `app/src/main/java/my/bridge/com/ssh/SshServerService.kt` | specialUse 前台服务 + MINA SSHD 装配 + WakeLock |
| `app/src/main/java/my/bridge/com/ssh/SshAutoStart.kt` | 意愿自愈（BOOT/冷启动/STICKY 共用守卫） |
| `app/src/main/java/my/bridge/com/ssh/SshBootReceiver.kt` | 开机/更新自启 receiver |
| `app/src/main/java/my/bridge/com/ui/SshPages.kt` | 设置分区 + 状态页 + 修改密码弹窗 |

## §四 改后回填

（各文件改完后逐节回填「改后原文」，见下文追加。）

### 4.1 实施结果总览（2026-11-02 回填）

全部改动已完成，编译 / 构建校验通过：

| 校验 | 结果 |
|---|---|
| `:app:compileDebugKotlin`（`JAVA_HOME=D:/Tool/Java/jdk-17.0.20.1+1`） | ✅ 第三轮通过（前两轮 22+4 个编译错误均已修复，见 §4.6） |
| `admin npm run build`（vue-tsc -b + vite build） | ✅ 一次通过（exit 0，21.12s） |

### 4.2 依赖与构建（§2.1–2.3 对应）

- `gradle/libs.versions.toml`：[versions] 加 `sshd="2.20.0"` / `bouncycastle="1.78.1"` /
  `slf4jAndroid="1.7.36"` / `desugar="2.1.5"`；[libraries] 加 sshd-core / sshd-sftp /
  sshd-scp / sshd-mina / bouncycastle / slf4j-android / desugar-jdk-libs。
- `app/build.gradle.kts`：compileOptions 加 `isCoreLibraryDesugaringEnabled = true`；
  packaging 排除 `META-INF/DEPENDENCIES` 等；dependencies 加 5 个 sshd 依赖 +
  `coreLibraryDesugaring(libs.desugar.jdk.libs)`。
- `app/proguard-rules.pro`：追加 keep `org.apache.sshd.**` / `org.apache.mina.**` /
  `org.bouncycastle.**` + dontwarn（含 `org.slf4j.**`）。

### 4.3 客户端（§2.4–2.9 对应）

- `AndroidManifest.xml`：`MANAGE_EXTERNAL_STORAGE`（tools:ignore="ScopedStorage"）+
  `SshServerService`（specialUse，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE="Embedded SSH server (SFTP file transfer and remote shell)"`，
  **无 BIND_VPN_SERVICE**）+ `SshBootReceiver`（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED /
  MY_PACKAGE_REPLACED）。
- `Command.kt`：加 `SSH_START` / `SSH_STOP` / `SSH_STATUS` 三常量。
- `CommandModule.kt`：注册表加 3 条映射；`executeSshStart`（幂等守卫在最前：RUNNING/STARTING →
  success=false「重复下发被忽略：SSH 服务已在运行（…）」；STOPPING → 等待提示；
  然后 setEnabled(true) + start）、`executeSshStop`（STOPPED/ERROR → success=false
  「重复下发被忽略：当前 SSH 服务未运行（…）；未执行停止、自恢复意愿保持不变」；
  然后 setEnabled(false) + stop）、`executeSshStatus`（只读 statusText()）。
- `MainActivity.kt`：`sshAutoStartAttempted` + onStart 中 `SshAutoStart.tryStart(this)`（每实例一次）。
- `MainScreen.kt`：SettingsPage 分支闭合后加 `if (SshUiState.showPage) { SshServerPage() }` 覆盖层。
- `SettingsPages.kt`：ConnectionSettings 尾部加 `SectionTitle("SSH 服务")` + `SshSettingsSection()`。

### 4.4 服务端（§2.10 对应）

`command_catalog.py`：vnt_status 后追加 `ssh_start` / `ssh_stop` / `ssh_status` 三条 `_cmd`
（system 类别，含状态联动说明文案）。

### 4.5 admin 状态联动（§2.11 对应，index.vue 实际改了 12 处 + 新组件）

- **新建 `admin/src/components/SshResultDetailDialog.vue`**（398 行）：照 VNT 组件范式，
  defineExpose({ show })、meta 条 + 三套布局 + 回执原文 copyRaw：
  - `ssh_status`：大号状态徽标（stateTagOf 与 parseSshStatus 同口径）+ KV 网格
    （端口 / 用户名 / 活动会话数 / 存储暴露，`parseStatusFields` 正则解析 `运行中 · 端口 X · …`）+
    提示「密码与 hostkey 指纹只在 APP 状态页展示」；
  - `ssh_start`：结果横幅（已下发启动 / 重复被忽略：已在运行 / 停止中暂不处理 / 启动失败 / 执行失败）
    +「本次启动信息」KV（命令无参数，字段从回执解析：端口 / 用户名 / 存储暴露 / 连接示例）+ 下一步建议；
  - `ssh_stop`：结果横幅 +「停止前状态」徽标与快照 KV（从「停止前：…）」提取）+
    「是否执行停止」+「自恢复意愿（已清除 / 保持不变）」标签。
  verdict 文案逐字对齐客户端 `CommandModule.executeSshStart/Stop` 固定前缀。
- **`index.vue` 12 处**（锚点见 §2.11）：① 顶栏 SSH 徽标（🔐 + sshStateText 悬停全文）+
  刷新按钮 title 扩为「播放 / 组网 / SSH」；② 详情按钮条件扩为 `canViewVntDetail(row) || canViewSshDetail(row)`
  且 click 分流两个弹窗；③ 模板挂载 `<SshResultDetailDialog ref="sshDetail">`；
  ④ import + `SSH_DETAIL_COMMANDS` + `sshDetail` + `canViewSshDetail`；
  ⑤ SSH 联动全套（`SSH_STATE_RULES`{ssh_start:[stopped,error], ssh_stop:[running,starting,stopping]} /
  `SSH_STATE_LABELS`（starting=「正在启动」等，措辞区别于 VNT）/ `sshSnapshots` / `parseSshStatus`
  （`运行中 ·`→running、`正在启动`→starting、`正在停止`→stopping、`未运行`→stopped、`异常：`/`执行失败`→error、其余 unknown）/
  `refreshSshStatus`（800ms 防抖 + silent=true）/ `sshAutoKey` watch）；
  ⑥ `refreshDeviceStates` 加 `refreshSshStatus()`；⑦ 切换设备 watch 加 `sshQueryKey = ''`；
  ⑧ `showForceSend` 加 SSH 分支；⑨ `forceSendTip` 加 SSH 分支（含置灰 tooltip）；
  ⑩ `isStateBlocked` 扩 sshAllowed（unknown 一律禁用，与 VNT 同款）；⑪ `forceSendDisabled`
  扩为 VNT 或 SSH 状态命令；⑫ `blockHint` 加 SSH 三段提示 + `handleSend` 二次确认扩展
  （消息按命令族切换「组网状态」/「SSH 状态」）+ result handler 加 ssh_status 快照解析与
  ssh_start/stop 回执后 1.5s 重探（`lastSshProbeAt = 0` 绕防抖）。

### 4.6 客户端编译错误与修复记录（三轮）

| 轮次 | 错误数 | 根因与修复 |
|---|---|---|
| 1 | ~22 | sshd 2.20.0 包路径与常见文档不符（KeyUtils / MinaServiceFactoryFactory / ProcessShellFactory / SftpSubsystemFactory 全 Unresolved）→ find + unzip -l + javap 逐一实证真实路径；`SimpleGeneratorHostKeyProvider.path` 需 `java.nio.file.Path`（`.toPath()`）；`fileSystemFactory` 属性名 / `isCreateHome`（非 isCreateHomeDir）；`ProcessShellFactory(String, String...)` varargs 非数组；`setDelegateCommandFactory`（非 setDelegate）+ SAM 两参数 + `createShell(channel)`；`java.net.NetworkInterface`（非 android.net）；`SshUiState` 全限定引用改同包 |
| 2 | 4 | android.net.NetworkInterface 残留 import（首轮 Edit 只改了使用处）；ProcessShellFactory 构造器 / createShell 收尾修正 |
| 3 | 0 | ✅ 通过 |

另：`SshPages.kt` 初版含上下文桥接 hack（SshInitAppContextHolder 等）→ 自查后改用
Composable 内 `LocalContext.current` 直读，hack 已删。

### 4.7 文档回填（§2.12 对应）

- `docs/api/commands/system.md`：标题行加三条 SSH 命令；文末追加「SSH 服务器命令（v0.2.34 引入）」
  大节（架构 / 生命周期 enabled-running 分离 / 三命令各节 / 状态联动与强制下发节）。
- `docs/api/commands/README.md`：system 索引表加 ssh_start / ssh_stop / ssh_status 三行。
- `docs/dev/crash-ledger.md` §3.1：登记 `SshServerController.start` → SshServerService 行
  （specialUse、通知 ID 2103、**START_STICKY 与 VNT 的 NOT_STICKY 相反**并注明原因、
  stop 未运行不拉起）。
- `docs/INDEX.md`：sshd-integration.md 登记行（早已完成）。
- 本台账 §四 回填即本节。

### 4.8 遗留事项（真机验证清单，下个会话/用户手动）

1. **WinSCP / FileZilla SFTP 连接**：`bridge@<手机IP>:8022`，首次接受 hostkey 指纹
   （与 APP 状态页指纹核对），浏览 / 上传 / 下载 / 删除。
2. **ssh 终端**：`ssh -p 8022 bridge@<IP>`，确认 `/system/bin/sh -i` 交互正常。
3. **灭屏 / 移动数据下连接**（对应「必可达」硬约束 + WakeLock 默认开）。
4. **自愈链**：杀进程（STICKY 重建）、重启手机（BOOT_COMPLETED）、覆盖安装
   （MY_PACKAGE_REPLACED）后自动恢复；`ssh_stop` 后三者均**不**恢复（意愿已清）。
5. **Android 7.1（360N7，minSdk 24 边界）**：core library desugaring 生效性、
   EC hostkey 生成、BouncyCastle provider 重装。
6. **admin 状态联动**：选中 ssh_start/stop 卡片自动探测、unknown 禁用、强制下发二次确认、
   详情弹窗三套布局回执解析。

---

## §五 终端与 SFTP 修复（v0.2.36，2026-11-02 下午—晚间，五轮真机迭代）

> 背景：v0.2.35 真机首验，SFTP 可用但终端「执行命令卡死 → 无回显 → 整行不执行 → 格式错乱 → 落点 / 无权限」逐层暴露，共五层，全部当日修复。

### 5.1 第一层：exec 通道卡死（本地 commit eaeab07）

- **根因**（javap + 桌面 MiniSshd 复现 + 真机双实证）：sshd 2.20 `ProcessShellFactory.resolveEffectiveCommand` **无视调用者传入 elements**，非 Windows 硬编码 `["/bin/sh","-c",构造器首参]` → exec 实跑 `/bin/sh -c "/system/bin/sh"`（无人喂数据的交互 sh）→ 卡死。
- **改后**：绕开 OS 推断，直接 `InvertedShellWrapper(ProcessShell(...))` 精确构造。
- **回滚**：`git show eaeab07` 之前状态 = `shellFactory = ProcessShellFactory(...)`。

### 5.2 第二层：终端无回显、回车无反应

- **根因**：ProcessShell 用管道无 PTY，内核行规程（回显/`\r`→`\n`）缺失。
- **改后**：新增 `SshLineDiscipline.kt`——`LineDisciplineInputStream`（回显+退格+CJK 整字符擦除+ESC/CSI 吞除+`\r`→`\n`+Ctrl+C/U/D）+ `EchoShellCommand`。
- **回滚**：新文件，删除 + shellFactory 还原即回滚。

### 5.3 第三层：行规程与泵的死锁

- **根因**（javap pumpStream 实证）：`InvertedShellWrapper` 轮转泵靠 `available()` 判断，行规程整行缓冲时 available()==0 → 字节永远不流动。且 sshd 独立 err 流真机写不达客户端（CHILD_ERR 实验实锤），mksh 提示符/警告全走 stderr。
- **改后**：`EchoShellCommand` 改**推送式泵**（stdin 行规程线程 / stdout 泵线程 / exit-watch 线程）+ `ProcessBuilder.redirectErrorStream(true)` OS 层合并 stderr；输出统一过 `SynchronizedOutputStream` 防双线程写字节交错。

### 5.4 第四层：mksh 交互模式拿到整行不执行 → 读取-执行循环

- **现象**：`-c` 模式一切正常，`-i` 模式从管道拿到完整行也不执行（诊断日志版未及装机，WiFi/VNT 链路不稳中断）。
- **改后**：**放弃 `-i`**，用 `sh -c 'print ':/ $ '; while IFS= read -r line; do eval "$line"; print ':/ $ '; done'`——与已验证的 `-c` 同源机制；提示符由循环内 mksh 内建 `print` 合成（兼作「上条执行完」信号）。**取舍**：跨行语法块（if/fi、for/done）不支持需一行写完；子命令读 stdin 会与循环抢输入；Ctrl+C 只清行不能杀前台进程（无 PTY 固有限制）。

### 5.5 第五层：输出阶梯缩进 + SFTP/终端落点 `/`（v0.2.36 装机验证版，三处）

- **阶梯缩进**：无 PTY 时子进程输出裸 `\n`，缺真实 PTY 的 ONLCR 补 `\r` → EchoShellCommand stdout 泵逐字节扫描，仅对前面无 `\r` 的裸 LF 补 `\r`（防 `\r\n`→`\r\r\n`）。
- **SFTP 初始目录 = `/`**（javap 实证）：`SftpSubsystem.setFileSystem` 里 `defaultDir = fs.getPath("").toAbsolutePath()` = **进程工作目录**（Android = `/`）；`NativeFileSystemFactory.usersHomeDir` 只在 createHome=true 时建目录用，对初始目录**无效**。→ 新增 `HomeSftpSubsystemFactory.kt` 覆写 `getDefaultDirectory()` 钉到 `SshPrefs.homeDir`（全盘档 /sdcard）。
- **shell/exec 落点 `/`**：ProcessBuilder 未设工作目录 → EchoShellCommand 增加 `workDir` 参数（shell 起始 /sdcard）；exec 委托补 `cd "<home>" &&` 前缀（对齐 OpenSSH「exec 在 home 执行」语义）。
- **回滚配方**：`git checkout <v0.2.36 上一提交> -- app/src/main/java/my/bridge/com/ssh/`；新文件删除即回滚。

### 5.6 发布与装机通道（当日实锤）

- **central-server 跑在本机**（`F:/pythonWork/appClient/server/.venv`，端口 15160）→ `server/data/updates/` 即实时生效升级目录，装包流程 = bump versionName → assembleDebug → cp APK + 改 update.json → 手机 App「检查更新」。
- **adb over VNT 不可靠**：外网地址走 VNT 隧道，push 45MB 两次成功但 `adb shell` 恒失败（device offline/连接蒸发），同机 WiFi adb 正常 → 高延迟移动数据隧道下 adb 握手不稳；且大流量可能压崩 App 连带 VNT 失效（用户推断，主 WS 曾掉线佐证）。**装机优先 check_update / USB / WiFi adb，禁 adb over VNT 传大文件。**
- git 直连/代理均挂时，发布走 Git Data API 重建提交（`build/_api_push_v0236.py`），**远端 commit hash 与本地不同（内容一致）**，本地 origin/main 引用滞后，下次发布先 API 查远端真实 sha。
