# 客户端集成 SSH 服务器（sshd）调研

> 需求：在 Bridge 客户端 APP 内集成一个 SSH 服务器，让 PC / 其它设备用标准 SSH 客户端连进手机。
> 已拍板：① 用途 = **SFTP 文件管理 + 远程终端** 都要（端口转发为加分项）；② 启停形态 = **APP UI 开关 + 命令体系控制**（参考 VNT 组网 / 智能控制的集成模式）；③ 替代方案一并对比。
> 状态：**调研完成，未实现**。实施前按「§10 决策清单」逐项拍板。

---

## 一、结论速览（TL;DR）

- **推荐方案 = 方案 A：Apache MINA SSHD（纯 Java 内嵌）+ sshd-mina 传输层**。
  - 零 NDK、零本地安装、零第三方二进制——一条 Gradle 依赖进 `app`，完全符合「禁止擅自安装」约定。
  - 官方提供 Android 支持 hooks（home 目录、CWD、BC provider 等），社区有 Android 实跑案例，**primitive ftpd**（F-Droid 十年成熟项目）用同款库内嵌 SFTP 服务，先例充分。
  - SFTP / SCP / 远程终端 / 端口转发**一个库全覆盖**，与「两者都要」的需求精确匹配。
- **方案 B（Dropbear NDK 交叉编译）作废**：需要装 NDK 工具链（违反禁装约定）、需要 bionic 补丁、SFTP 还得另带 OpenSSH sftp-server 二进制、且 targetSdk 29+ 的 W^X 限制让外部二进制无法直接 exec（见 §3.3）。
- **替代协议对照结论**：SFTP 在「加密 + 认证 + 单端口 + 生态成熟」四项上全优；FTP/SMB/WebDAV 均有硬伤或无可靠 Java 服务端库；ADB over WiFi 是「系统级临时方案」不可内嵌（见 §7）。
- **Android 侧最大的三个坑**：① `AsynchronousSocketChannel` 是 API 26+，我们 minSdk=24，必须用 sshd-mina/sshd-netty 替换默认 NIO2 传输层；② targetSdk 36 的 scoped storage——SFTP 想浏览整个 /sdcard 必须申请 `MANAGE_EXTERNAL_STORAGE`（我们不走 Google Play，无政策障碍）；③ Android 9+ 的 BouncyCastle provider 被弃用，需显式 removeProvider + addProvider。
- **保活/前台服务**：sshd 是常驻监听服务，必须按 `docs/dev/crash-ledger.md` §5「新前台服务接入检查清单」接入（参考 VNT 的 `specialUse` FGS 模式），并依赖电池白名单抵消 Doze 对入站连接的影响。

---

## 二、需求拆解与能力边界

### 2.1 三个可用能力（SSH 协议自带）

| 能力 | 对应 SSH 通道 | 对 Bridge 的价值 |
|---|---|---|
| **SFTP 文件管理** | sftp 子系统 | PC 用 WinSCP/FileZilla/`scp` 批量浏览、上传、下载手机文件——现有 WS 命令体系做不到批量/断点/目录树传输 |
| **远程终端** | session → shell | PC `ssh` 直连手机执行命令；与现有命令下发互补（现有=结构化回执，终端=交互式探索） |
| **端口转发（TCP forwarding）** | direct-tcpip / forwarded-tcpip | 加分项：反向把手机侧服务（如媒体流 WS）暴露给 PC，或正向代理 |

### 2.2 能力边界（必须如实告知，非 root 手机）

- **终端 ≠ root shell**：sshd 以 App 自身 uid（`u0_aXX`）运行，登录 shell 是 `/system/bin/sh`（mksh/toybox）。能跑 `ls/cat/ps/top` 等 toybox 命令、`am`/`cmd` 的部分动作；**不能** `su`、`dumpsys`（shell uid 专属）、`input`（INJECT_EVENTS 权限）等。
- **文件面**：默认只能看 App 私有目录 + 自己创建的 `/sdcard/bridge/`；要看全盘共享存储，必须申请 `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）。见 §6.5。
- **端口**：非 root 不能绑 <1024，用高位端口（建议默认 **8022**，Termux 惯例，可配置）。
- **并发会话**：手机端资源有限，建议限并发 2~3 个连接。

---

## 三、Android 平台硬约束盘点（动手前逐条对照）

| # | 约束 | 事实依据 | 对策 |
|---|---|---|---|
| 3.1 | **非 root 端口 <1024 禁绑** | Linux 保留端口 + Android 沙箱 | 默认 8022，Prefs 可配 |
| 3.2 | **`AsynchronousSocketChannel` / `AsynchronousServerSocketChannel` 是 API 26+** | Android 官方文档 `[Register(ApiSince=26)]`；我们 **minSdk=24** | **弃用 sshd-core 默认 NIO2 传输，改用 `sshd-mina`（或 sshd-netty）**——sshd 的 I/O 层是可插拔的，官方提供这两种替换 artifact |
| 3.3 | **targetSdk 29+ W^X 限制：不能 exec 应用数据目录里的二进制** | Android 10 起 `untrusted_app` SELinux 域禁止从可写目录 exec，仅允许加载/执行 `jniLibs` 内的 `lib*.so` | 对方案 B 是致命伤（Dropbear 二进制得伪装成 .so 塞 jniLibs，且需多 ABI）；**方案 A 纯 Java 完全绕开** |
| 3.4 | **Android 9+ BouncyCastle provider 弃用** | AOSP `Providers.java`：AndroidP 起 BC 被标记 deprecated 且被strip | `Security.removeProvider("BC")` → `Security.addProvider(new BouncyCastleProvider())`（需自带 bcprov 依赖，ed25519 等 KEX 依赖 BC） |
| 3.5 | **Android 无 `user.home` / `user.dir` 概念** | mina-sshd 官方 `docs/android.md` | `PathUtils.setUserHomeFolderResolver()` + `NativeFileSystemFactory.setUsersHomeDir()` + `OsUtils.setAndroid(true)` 三个 hook 显式设置 |
| 3.6 | **scoped storage（targetSdk 36）** | Android 11+ 强制 | 申请 `MANAGE_EXTERNAL_STORAGE`（设置页引导跳转「所有文件访问」）。**内部测试分发不走 Google Play，无政策合规问题** |
| 3.7 | **Doze / 休眠杀入站连接** | 灭屏后 WiFi 可能休眠，监听 socket 收不到新连接（同心跳台账结论：能否收包取决于 Doze 与电池白名单） | 前台服务 + 电池白名单（KeepAliveService 已有能力）；可选「运行期间持 WakeLock」（primitive ftpd 同款可选开关）；局域网+白名单下实测基本可用 |
| 3.8 | **main 线程禁网络** | `NetworkOnMainThreadException` | sshd 启动/停止全部放后台线程（协程 `Dispatchers.IO`） |
| 3.9 | **打包冲突** | sshd jar 里带 `META-INF/DEPENDENCIES` 等 | `packaging { resources.excludes += "META-INF/*" }`；proguard 规则需加 sshd/bouncycastle keep |
| 3.10 | **`startForegroundService` 义务按次计** | 崩溃台账铁律 1 | 新前台服务严格按 crash-ledger §5 清单接入，照抄 VntVpnService 的 `onCreate` 挂前台 + `onStartCommand` 幂等补挂模式 |

---

## 四、技术选型：候选方案全景

### 方案 A：Apache MINA SSHD（纯 Java 内嵌）★ 推荐

- **是什么**：Apache 官方 100% 纯 Java SSH-2 协议库，同时支持 server/client，内含 SFTP 子系统、SCP、shell command factory、TCP 端口转发。Apache-2.0 许可。
- **版本**：最新稳定 **2.20.0**（2026-09-28）；3.0.0 还在 M6 里程碑且 API 不兼容，**不要碰**，锁 2.20.x。
- **依赖面**：`sshd-core` + `sshd-common` + `sshd-sftp` + `sshd-mina`（I/O 层）+ `slf4j-api`（配 nop 或 android 实现）+ `bcprov`（ed25519/KEX）。粗估 APK 增量 **3~4.5MB**（R8 后更小），对 40MB 的 APK 无压力。
- **Android 先例**：
  - 官方 `docs/android.md` 提供专门 hooks（§3.5 那三个）；
  - **primitive ftpd**（wolpi/prim-ftpd，F-Droid，50+ releases，活跃维护至 2025-12）内嵌 SFTP 十余年，功能清单已验证这条路的产品可行性（公钥认证、开机自启、WakeLock、SAF、hostkey 管理全有）；
  - 社区多篇文章（CSDN 2026、StackOverflow）记录 Android 9/10/11+ 实跑 server 成功，坑与解法齐备（见 §8）。
- **优点**：零 NDK 零安装；协议完整（SFTP+SCP+shell+转发一个库）；纯 Java 与现有 Kotlin/Hilt 技术栈无缝；许可证宽松；维护活跃（Apache 顶级项目）。
- **缺点**：官方明说 Android「非正式支持目标」（hooks 是社区反馈驱动）；默认传输层在 minSdk 24 不可用需替换（§3.2）；库偏大；性能弱于原生 openssh（手机场景无感）。

### 方案 B：Dropbear（NDK 交叉编译，SimpleSSHD 路线）✗ 作废

- Dropbear 是轻量 C 实现，但：**必须装 NDK 工具链交叉编译**（违反「禁止擅自安装」最高约定）；bionic 无 `crypt()` 需打补丁（`-A` Android mode）；**Android 没有 sftp-server，SFTP 要再交叉编译一份 OpenSSH sftp-server 一起带**；targetSdk 29+ 的 W^X 限制要求把二进制伪装成 `lib*.so` 塞 jniLibs 且按 ABI 出多份（arm64/x86_64/armeabi-v7a……VNT 的 ABI 矩阵教训还热着）；SELinux enforcing 机型行为不可控。
- 简单 SSHD（SimpleSSHD）App 本身是这条路线的活体证明，但它是「把现成 App 装上去」，**不可内嵌**，且项目老旧。

### 方案 C：Termux openssh / Copyparty 二进制搬运 ✗ 不可行

- Termux 的 `openssh` 包依赖 Termux 前缀环境（`$PREFIX`、动态链接其目录树），无法独立搬进普通 App；Copyparty 是「Termux 里跑 Python」的产品形态，是「教用户装 Termux」不是「集成进 Bridge」。**此路线的合理形态是「推荐用户装独立 App」的兜底选项**，不是集成。

### 方案 D：Go gomobile（gliderlabs/ssh + x/crypto/ssh）△ 备选不推荐

- Go 的 SSH 服务端库成熟（gliderlabs/ssh），可 gomobile 编译成 .aar 内嵌。但：引入整条 Go 工具链（又一处「安装」+ 构建复杂度）；SFTP 还要另找 Go sftp 库拼装；APK 增量更大；与 Kotlin 交互层要手写 JNI 边界。**仅在方案 A 出现不可逾越的性能/协议障碍时再看**。

### 对比表

| 维度 | A. MINA SSHD | B. Dropbear | C. Termux 搬运 | D. Go gomobile |
|---|---|---|---|---|
| 本地安装需求 | **无** | NDK 工具链 ✗ | 无（但需用户装Termux） | Go 工具链 ✗ |
| SFTP | **内置** | 要另带 sftp-server | 有 | 要拼装 |
| 远程终端 | 内置（ProcessShellFactory） | 内置 | 有 | 需自写 shell 处理 |
| 端口转发 | **内置** | 内置 | 有 | 部分 |
| minSdk 24 兼容 | 换 sshd-mina 即可 | 是 | 是 | 是 |
| targetSdk 36 / W^X | **无影响** | exec 受限 ✗ | 无关 | 无影响 |
| 维护/社区 | Apache 活跃 | 上游活跃但 Android 补丁散落 | — | 库活跃、集成散落 |
| APK 增量 | ~3-4.5MB | ~1-2MB + 多 ABI 二进制 | 0（装外部App） | ~8-15MB |
| 许可 | Apache-2.0 ✓ | MIT-ish ✓ | — | ✓ |

---

## 五、现成开源项目借鉴（怎么把轮子用对）

| 项目 | 借鉴点 |
|---|---|
| **wolpi/prim-ftpd**（GPL-3.0） | 产品级 SFTP 内嵌先例：hostkey 首次启动生成 + 展示指纹；公钥认证（authorized_keys 管理 UI）；「服务运行中→常驻通知→通知可停止」交互范式；可选 WakeLock 防传输中断；SAF / MANAGE_EXTERNAL_STORAGE 引导文案；「scp 需 `-O` 选项」等客户端兼容坑（§8.7）。**注意 GPL 传染：只借鉴思路与交互，不抄代码**（我们用 Apache-2.0 的 MINA SSHD 自研）。 |
| **apache/mina-sshd 官方 docs/android.md** | Android 适配 hooks 的权威来源：`OsUtils.setAndroid` / `PathUtils.setUserHomeFolderResolver` / `NativeFileSystemFactory.setUsersHomeDir` / BC provider 处理 / `sshd-contrib` 里的 AndroidOpenSSLSecurityProviderRegistrar。 |
| **SimpleSSHD / dropbear-android 系列** | 反面教材价值：完整记录了 C 路线在 Android 上的全部坑（无 crypt、无 sftp-server、SELinux、PIE、W^X），佐证方案 A 的选择。 |
| **Termux sshd** | 端口惯例（8022）与「hostkey 指纹首次连接展示」的 UX 参考。 |

---

## 六、推荐落地设计（方案 A）

### 6.1 依赖（gradle/libs.versions.toml + app/build.gradle.kts）

```
org.apache.sshd:sshd-core:2.20.0
org.apache.sshd:sshd-sftp:2.20.0
org.apache.sshd:sshd-mina:2.20.0        # I/O 层替换，解决 minSdk 24（§3.2）
org.bouncycastle:bcprov-jdk18on:1.7x    # ed25519 / KEX
org.slf4j:slf4j-api → 桥到现有日志或 slf4j-nop
```
打包排除 `META-INF/*`；R8 keep sshd/bouncycastle/mina。

### 6.2 新增代码（照目录规范，全部进 `app/.../ssh/` 包，UI 进 `ui/SshPages.kt`）

```
ssh/
├── SshServerController.kt    # 状态机 STOPPED/STARTING/RUNNING/STOPPING/ERROR + start()/stop()，
│                             #   enabled(意愿)/running(事实)分离 + 幂等守卫放所有写操作之前（v0.2.33 约定），
│                             #   重复 start/stop 回 success=false 不假装成功
├── SshServerService.kt       # 前台服务（specialUse + START_STICKY 自愈，照 VntVpnService 模式），持起 Controller
├── SshServerPrefs.kt         # 端口(默认8022)/enabled 意愿/用户名/密码哈希/hostkey 生成时间/wakeLock 开关
├── SshAuth.kt                # 密码 + 公钥双认证（PasswordAuthenticator + PublickeyAuthenticator）
├── SshFileSystemFactory.kt   # 继承 NativeFileSystemFactory：home=/sdcard/bridge（或全盘，取决于 MANAGE_EXTERNAL_STORAGE）
├── SshHostKeyStore.kt        # 首次启动生成 RSA+ECDSA hostkey 持久化 filesDir，杜绝每次换 key 触发客户端告警
└── SshCommandLog.kt          # 登录/命令审计日志（接现有 DiagLog 上报链路可选）
ui/SshPages.kt                # 设置→连接页「SSH 服务」分区 + 状态页（照 RelaySwitchPages 风格）
```

命令扩展严格走三步：`command_catalog.py` 加 `ssh_start` / `ssh_stop` / `ssh_status` → `Command.kt` + `CommandModule.executeSshStart/Stop` → `docs/api/commands/system.md` 加章节 + README 索引。**状态联动按 v0.2.33 约定全套落实**：后台按 `ssh_status` 回执禁用重复下发、unknown 一律禁用、强制下发=勾选+二次确认；设备端幂等守卫前置。

### 6.3 生命周期（★ 2026-11-02 拍板：完整自愈链 + WakeLock 默认开可关）

**核心原则：`enabled`（用户意愿，Prefs 持久化）与 `running`（运行事实，Controller 状态）分离**——一切自动恢复都看意愿不看历史（VNT 自动组网记忆同款思路）。

**状态机**：`STOPPED → STARTING → RUNNING → STOPPING → STOPPED`，异常态 `ERROR`（可重试）；`STARTING/STOPPING` 防重入；幂等守卫放所有写操作之前，重复 start/stop 回 `success=false`「重复下发被忽略」不假装成功（v0.2.33 约定）。

**启动链（5 入口，全部汇入 `SshServerController.start()`）**：
1. 设置页开关 on（写 `enabled=true`）；
2. `ssh_start` 命令（管理后台下发，写 `enabled=true`；后台 UI 按 v0.2.33 状态联动禁用重复下发）；
3. **开机自启**：BOOT_COMPLETED receiver，若 `enabled=true` 则拉起（FGS 从开机广播启动属系统豁免场景；复用现有保活开机链）；
4. **冷启动恢复**：`MainActivity.onStart` 检查 `enabled && !running` → start（Activity 已可见，避开 Android 12+ 后台 FGS 限制；`VntAutoStart` 同款：每实例仅一次、失败不重试）；
5. **进程被杀自愈**：Service 返回 `START_STICKY`，系统重建时 `onCreate` 检查 `enabled` 自动重启 server。
   （即使以上全断，Bridge 主 WS 常驻 + 管理后台 `ssh_start` 仍是远程必可达兜底。）

**停止链（3 入口，全部汇入 `SshServerController.stop()`）**：
1. 设置页开关 off（写 `enabled=false`）；2. 通知「停止」action（写 `enabled=false`）；3. `ssh_stop` 命令（写 `enabled=false`）。
   停止动作固定顺序：断开全部活动会话（回执报数量）→ 注销监听 → 撤通知 → 释放 WakeLock → `stopSelf()`。
   **收口铁律（VNT 僵尸服务教训）**：停止一律走 Controller——未运行时**绝不**经 `startService(ACTION_STOP)` 拉起新服务实例；通知停止用 `PendingIntent.getBroadcast` → Receiver 调 Controller，带运行守卫。

**页面与服务的解绑（与智能控制相反，勿混淆）**：
- 智能控制（RelaySwitch）：进入页面 `start()`、`onDispose` `stop()`——省电优先、用完即走；
- **SSH 是常驻服务：状态页/设置分区的 `onDispose` 绝不停服务**，页面只改「意愿」不动「事实」。

**WakeLock**：运行期间持 partial WakeLock（拍板：**默认开**，保证灭屏/锁屏下新连接与传输不断），服务停止即释放；设置页高级项可关闭（省电档，届时灭屏连接性靠电池白名单兜底）。

**Doze**：服务运行中不自动停；由电池白名单（KeepAliveService 已有引导）+ 默认 WakeLock 双保险。

**crash-ledger §5 清单对照**（接入时逐条打勾）：manifest `specialUse` 四件套 → 所有 `startForegroundService` 调用点 try/catch → `onCreate` 挂前台（`startForeground` try/catch + 失败 stopSelf）→ `onStartCommand` 每次幂等补挂 → 早退分支义务确认 → 新调用点登记 §3.1 表。

### 6.4 网络面与 VNT 协同

- 绑 `0.0.0.0:8022` → **同一台 sshd 同时服务三个面**：局域网 WiFi、VNT 虚拟网（TUN 网卡地址）、（如开启）热点。VNT 已保证「虚拟网必可达」，sshd 天然被覆盖——PC 在任何有网的地方经 VNT 连手机，无需端口映射。这是本项目做 sshd 相对独立 App 的独有优势。
- 安全选项：Prefs 提供「仅监听 VNT 虚拟网地址」开关（绑 TUN IP），公网暴露面归零。

### 6.5 存储暴露面（分两档，P0/P1 对应）

| 档 | home 目录 | 前置条件 | 说明 |
|---|---|---|---|
| 基础档（P0） | `/sdcard/bridge/` + App 私有目录 | 无新权限 | 共享存储 App 专属目录，最小暴露面 |
| 全盘档（P1） | `/sdcard/` 全盘 + 二选一实现 | `MANAGE_EXTERNAL_STORAGE`（设置页引导授权） | 全盘浏览；不授权时自动回退基础档 |

内部测试分发，无 Play 政策顾虑；授权引导文案参考 prim-ftpd 的「设置→应用→特殊权限→所有文件访问」路径。

### 6.6 认证与安全

- 用户名固定 `bridge`（不可改，降低配置面）；密码默认**首次启动随机生成**并在状态页展示（照 prim-ftpd「主屏显示连接信息」范式），可改；密码哈希存储。
- 公钥认证（P1）：状态页粘贴 `authorized_keys`，存 Prefs。
- hostkey：首次启动生成 RSA + ECDSA + Ed25519，持久化 `filesDir/ssh/hostkeys/`；状态页展示指纹（首次连接信任的基础）。
- 防爆破：认证失败 ≥5 次 / 分钟 → 临时封禁该源 IP 60s；连接数上限 3。
- 审计：登录成功/失败、执行的命令写本地日志（可选 DiagLog 上报，接服务端 MySQL 日志库，排障链路现成）。

### 6.7 终端 shell 的现实预期

`ProcessShellFactory` 默认 exec `/system/bin/sh`。要预设的体验：`ls/cp/mv/rm/df/top/ps` 可用；`am/cmd/pm` 部分可用；`su/dumpsys/input/logcat`（部分）不可用。可在 P0 用「受限命令白名单 shell」（自定义 `Command`/`Factory`，只放行 toybox 安全子集）作为安全档，P1 再放开完整 sh——**列入决策清单 §10**。

---

## 七、替代方案对照（同目标不同技术）

| 方案 | 加密 | 认证 | 客户端生态 | Android 可内嵌性 | 结论 |
|---|---|---|---|---|---|
| **SFTP（方案A）** | **传输全程加密** | 密码+公钥 | **WinSCP/FileZilla/scp/rsync 全家桶** | **MINA SSHD 成熟** | **胜者** |
| FTP（Apache FtpServer，prim-ftpd 同款） | 明文（FTPS 又要另配证书） | 弱 | 广 | 成熟（prim-ftpd 在用） | 明文传输不可接受，只配作为「兼容模式」 |
| WebDAV（Milton 等 Java 服务端库） | 依赖 HTTPS 证书 | 弱~中 | Windows 资源管理器可挂载 | 无权威内嵌先例，库重（Servlet 生态） | 「Windows 原生挂载」体验有独特性，可作 P2 加分项，不做主方案 |
| SMB 服务器 | 协议复杂 | 中 | Windows 原生 | **Java 世界没有可靠 SMB 服务端库**（jCIFS-ng/smbj 全是客户端） | 不可行 |
| ADB over WiFi / 无线调试 | 系统级加密 | 配对码 | adb/scrcpy | **系统功能，App 无法内嵌，端口随机、需重复配对、开发者选项易被系统关** | 已有（server 侧 tools/adb），定位是开发调试而非日常文件通道 |
| 纯 HTTP 下载页（NanoHTTPD） | 无 | 无 | 浏览器 | 简单 | 只能「单向取文件」，无上传无管理无终端，不满足需求 |

> 附注：项目用户侧已有 PC（Windows），WinSCP/`scp`/`rsync` 现成；这直接决定了「SSH 生态复用」的性价比最高——零客户端开发。

---

## 八、坑清单（集成时逐条防）

1. **minSdk 24 vs NIO2 API 26**：不改传输层必崩 `NoClassDefFoundError`（Android 7/7.1 设备）。→ 用 `sshd-mina`，`CoreModuleProperties`/`IoServiceFactoryFactory` 设为 MinaServiceFactory。
2. **BC provider 弃用**（Android 9+）：不处理则 KEX/签名随机失败。→ 先 `removeProvider("BC")` 再 `addProvider(new BouncyCastleProvider())`（每次进程启动做一次，放在 sshd 启动前）。
3. **No user home folder available**：`PathUtils.setUserHomeFolderResolver()` 不设则 SFTP 一启动就抛 `IllegalArgumentException`（社区实锤报错原文）。三个 hook 一起设：`OsUtils.setAndroid(true)`、home resolver、`NativeFileSystemFactory.setUsersHomeDir()`。
4. **NetworkOnMainThreadException**：`sshd.start()` 内部会碰网络 → 全部放 `Dispatchers.IO`。
5. **META-INF 冲突**：`3 files found with path 'META-INF/DEPENDENCIES'` → packaging excludes。
6. **hostkey 重生成**：每次启动重新生成会让所有客户端反复弹「主机密钥已更改」告警 → 必须持久化。
7. **scp 客户端兼容**：OpenSSH 9.0+ 默认走 SFTP 协议的 scp，但部分 Linux 发行版的老 ssh 需 `scp -O`（prim-ftpd 记录在案）；文档里注明即可，服务端无需改。
8. **Doze 下新连接进不来**：前台服务+白名单是前提；传输大文件中途灭屏被冻 → 「服务运行中持部分 WakeLock」可选开关（默认开，照 prim-ftpd）。
9. **`startForegroundService` 义务**：ssh_start 命令后台下发拉起服务 = 又一个「命令拉起前台服务」入口 → 对照 crash-ledger §5 清单逐条打勾，勿重蹈 VNT `startService(ACTION_STOP)` 僵尸服务覆辙（复用 Controller 未运行不拉起的收口模式）。
10. **R8 混淆**：sshd 大量反射/Apache MINA → keep 规则提前写好，`minifyEnabled` 构建先真机回归 SFTP+终端再发版。
11. **端口占用**：8022 被占（可能与其它工具冲突）→ 绑定失败要给出明确 UI 错误而非静默 ERROR 状态。

---

## 九、分期实施建议

| 期 | 内容 | 验收 |
|---|---|---|
| **P0（可行内核）** | MINA SSHD + sshd-mina + hostkey + 密码认证 + SFTP（/sdcard/bridge）+ 前台服务 + 设置页开关 + `ssh_start/stop/status` 三命令（状态联动全套） | WinSCP 连上、能传文件；手机重启后服务可由命令拉起 |
| **P1** | 全盘档（MANAGE_EXTERNAL_STORAGE）+ 公钥认证 + 远程终端（完整 sh）+ 防爆破 + 审计日志 | `ssh bridge@<ip> -p 8022` 进 shell；公钥免密登录 |
| **P2（可选）** | 端口转发、WebDAV 兼容面、受限命令白名单 shell、DiagLog 审计上报 | 按需 |

预估工作量：P0 为主的一个版本（对照 VNT 集成节奏），P1 半个版本。

---

## 十、决策清单（2026-11-02 用户已全部拍板 ✅）

1. ✅ **默认端口 8022**（保留 Prefs 可配）。
2. ✅ **认证形态**：首启随机密码 + 状态页展示；用户可随时修改；修改结果**持久化**（重启服务/重启 App 均沿用，不重生成）。
3. ✅ **存储：直接申请全盘**（P0 即引导 `MANAGE_EXTERNAL_STORAGE`「所有文件访问」，home=/sdcard；未授权时回退基础档并提示）。
4. ✅ **终端：完整 `sh`**（P0 即开放，不做白名单；靠认证 + 防爆破 + 审计兜底）。
5. ✅ **需要 UI 交互原型**：`assets/design/ui-mockup-ssh-page.html`（已产出）。
6. ✅ **命令命名**：`ssh_start / ssh_stop / ssh_status`。

> 澄清记录：用户曾问「SFTP/SCP 带 S 是否必须有证书」——**否**。SSH 家族的加密来自协议自身密钥协商（KEX），仅需服务端自生成、自持久化的 host key（TOFU 指纹确认），与 TLS/CA 证书体系完全无关；需要证书的是 HTTPS/FTPS。

---

## 十一、验证方法（实施后）

```bash
# Windows / Linux 均可
ssh -p 8022 bridge@<手机IP>            # 远程终端
sftp -P 8022 bridge@<手机IP>           # 交互 SFTP
scp -P 8022 local.zip bridge@<手机IP>:/sdcard/bridge/
rsync -av -e "ssh -p 8022" dir/ bridge@<手机IP>:/sdcard/bridge/   # P1，需 rsync（Termux/服务端侧有）
```
GUI：WinSCP / FileZilla（协议 SFTP、端口 8022）。首次连接核对状态页展示的 hostkey 指纹。
真机回归清单：WiFi 直连 ✓ / VNT 虚拟网直连 ✓ / 灭屏 5 分钟后新连接 ✓ / 传输中断线重连 ✓ / Android 7.1（minSdk 边界设备 360N7）✓。

---

## 十二、参考链接

- Apache MINA SSHD 官网/下载：https://mina.apache.org/downloads-sshd.html （稳定版 2.20.0；3.0.0-M* API 不兼容勿用）
- 官方 Android 支持文档（hooks 权威）：mina-sshd 仓库 `docs/android.md`（github.com/apache/mina-sshd）
- primitive ftpd（SFTP 内嵌先例 + 产品交互借鉴，GPL 勿抄码）：https://github.com/wolpi/prim-ftpd
- Dropbear Android 交叉编译坑全集（反面佐证）：https://github.com/Maxr1998/dropbear-android 、nerdoftheherd.com 交叉编译指南、blog.sigsegv.top API23 编译记
- Android NIO2 API level 实证：developer.android.com `AsynchronousSocketChannel`（ApiSince=26）
- Android 9 密码学变更（BC 弃用）：android-developers.googleblog.com 2018-03 "Cryptography changes in Android P"
- 社区 Android 实跑记录：CSDN《在 Android 中使用 mina-sshd 库并解决 No user home folder available》、StackOverflow "How to run an Apache-Mina SSHD-Server on Android Pie"
