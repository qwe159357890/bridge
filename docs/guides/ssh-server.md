# SSH 服务器（sshd）使用文档

> Bridge 客户端内嵌 SSH 服务器：让电脑直接连接手机，进行**文件管理（SFTP）**与**远程终端运维**。
> 技术选型：Apache MINA SSHD 2.20.0 纯 Java 内嵌（零 NDK、零安装）。开发过程与修复记录见 `docs/dev/ssh-integration-change-ledger.md`，调研与设计见 `docs/research/sshd-integration.md`。
> 本文面向使用者，随功能迭代更新。

---

## 一、功能概览

| 能力 | 说明 |
|---|---|
| **SFTP 文件管理** | WinSCP / FileZilla / MobaXterm 等客户端直接浏览、上传、下载手机文件 |
| **交互终端** | MobaXterm / PuTTY / `ssh` 命令行登录，执行 shell 命令 |
| **单命令执行（exec）** | `ssh bridge@手机IP "命令"` 直接拿输出，可脚本化调用 |
| **scp 传输** | `scp` 命令直接推拉文件 |
| **多会话** | 支持多个客户端同时连接（状态页显示当前会话数） |
| **VNT 虚拟网协同** | SSH 绑定 `0.0.0.0`，局域网与 VNT 虚拟网内均可访问（虚拟网 IP + 8022） |

## 二、如何启动

三种方式（任选）：

1. **App 内开关**：设置 → 连接 → 「SSH 服务器（SFTP · 终端）」打开 Switch 即启动，启动后显示运行状态与会话数
2. **管理后台命令**：对设备下发 `ssh_start` / `ssh_stop`（支持状态联动与强制下发，规则同组网命令）
3. **开机自启**：曾开启过 SSH 的话，App 冷启动与设备重启后自动恢复（`ssh_enabled` 记录的是用户意愿）

启动后为**前台服务**（通知栏常驻「SSH 服务」），灭屏后台默认持有 WakeLock 保持连接（可在设置页关闭）。

## 三、连接信息（App 内「SSH 服务」页可查）

| 项 | 值 | 说明 |
|---|---|---|
| 地址 | 手机 IP（局域网）或 VNT 虚拟 IP | 两种网络均可连 |
| 端口 | **8022**（默认） | 可在页面修改，范围 1024–65535（非 root 不能绑 <1024） |
| 用户名 | `bridge`（固定） | |
| 密码 | 首次启动随机生成（3 组 × 5 位，去易混字符） | 页面可查看与修改（≥8 位），修改后持久化，重启服务/APP 均沿用 |
| 存储档 | 全盘 / 基础（自动判定） | 见下文 §五 |

**连接示例**（MobaXterm：Session → SSH，填地址与端口即可）：

```bash
ssh bridge@192.168.31.109 -p 8022
# 单命令：
ssh bridge@192.168.31.109 -p 8022 "ls /sdcard"
# SFTP：WinSCP 协议选 SFTP，端口 8022
```

## 四、终端使用说明

### 4.1 登录落点

登录后初始目录 = **存储档根目录**（全盘档 = `/sdcard`，提示符显示 `~`），SFTP 连接初始目录相同。

### 4.2 快捷目录命令（内置定制）

| 命令 | 效果 | 提示符显示 |
|---|---|---|
| 裸 `cd` | 进入 **App 私有目录** `/data/data/my.bridge.com` | `[app]`（子路径如 `[app]/files`） |
| `cd ~` | 回 HOME（`/sdcard`） | `~`（子路径如 `~/DCIM`） |
| `cd -` | 回上一次所在目录 | 随路径 |
| `pwd` | 显示当前真实路径 | `/sdcard` 与 `/storage/emulated/0` 为同一位置（符号链接），提示符统一归一化为 `~` |

### 4.3 内置别名（alias）

| 别名 | 实际命令 | 用途 |
|---|---|---|
| `h` | （函数） | 显示内置命令帮助 |
| `ll` / `la` / `lh` | `ls -l` / `ls -la` / `ls -lh` | 长列表 / 含隐藏 / 人类可读大小 |
| `..` / `...` | `cd ..` / `cd ../..` | 上级 / 上上级目录 |
| `df` | `df -h` | 磁盘用量 |

### 4.4 提示符

彩色提示符：**青色路径 + 绿色 `$`**，路径实时跟随 cd 变化；连接时显示欢迎语。若客户端不支持颜色会显示原始转义序列（极少数老旧客户端），不影响功能。

## 五、存储访问档

App 按权限自动判定，无需手动配置：

| 档位 | 条件 | 范围 |
|---|---|---|
| **全盘档** | 已授予「所有文件访问」权限（Android 11+ 为系统特殊权限，页面有跳转入口） | 整个 `/sdcard` + `/data/data/my.bridge.com` |
| **基础档** | 未授权 | 仅 App 外部私有目录 `/sdcard/Android/data/my.bridge.com/files` |

> 两档均可进入 App 私有目录 `/data/data/my.bridge.com`（终端裸 `cd` 即达）。

## 六、能做什么 / 不能做什么

### ✅ 能做

- **文件管理**：SFTP 浏览/上传/下载/改名/删除；`scp` 推拉文件
- **常用命令**：Android 自带 toybox 工具集全部可用——`ls`、`cat`、`cp`、`mv`、`rm`、`grep`、`curl`、`ping`、`logcat`、`dumpsys`、`pm`、`am`、`df`、`top` 等
- **脚本化运维**：`ssh bridge@IP "单条命令"` 拿输出，配合本地脚本批量管理多台设备
- **查看 App 私有数据**：`/data/data/my.bridge.com`（本应用 uid 权限内自由读写）

### ❌ 不能做（均为固有限制，知悉即可）

#### ★ 不是真正的 Linux 环境，装不了常用 Linux 软件

这里只是 **Android 的 shell**（mksh + toybox 工具集，真机实测 toybox 0.8.9 / Android 14），**不是发行版 Linux**：

- **没有任何包管理器**（apt / pkg / dnf 均无），无法 `install` 任何东西
- **主流 Linux 软件官方不发布 Android（bionic libc）二进制**，即使手动传二进制也大概率因 libc 不兼容跑不起来
- 真机实测出厂工具情况：`curl` ✅ 有；`wget` ❌ 无；`python`/`python3`/`pip` ❌ 无；`git` ❌ 无；`vim`/`nano` ❌ 无；`gcc`/`make` ❌ 无；`node`/`ruby`/`php`/`perl` ❌ 无

| 常见需求 | 结论 | 替代方案 |
|---|---|---|
| 安装 Python | ❌ 装不了 | **Termux**：`pkg install python` 全套 pip 可用，与 Bridge 并存不冲突 |
| 安装 wget | ❌ 出厂没有 | 用 `curl`（已内置，能力覆盖 wget：`curl -O <url>` 下载） |
| 安装 git / node / gcc 等编译构建环境 | ❌ 装不了 | Termux（`pkg install git nodejs` 等）或 Termux proot 容器（`proot-distro install ubuntu` 得到完整 Ubuntu，apt 随便装） |
| 运行依赖脚本语言的任务 | ❌ 无解释器 | 在电脑端跑完把产物经 SFTP/scp 推回手机 |

> 定位总结：本模块 = **文件管理 + 轻量运维**（查状态、看日志、传文件、调系统命令如 `pm`/`am`/`logcat`）。要完整 Linux 体验请上 **Termux**（手机上直接装 APK 即可，无需 root）。

#### 其余交互限制

| 限制 | 原因 |
|---|---|
| **装 zsh / oh-my-zsh / bash** | 同上——无包管理器、无 zsh/bash 二进制；oh-my-zsh 还依赖 git。要漂亮 shell 请用 Termux |
| **跨行语法块** | `if...fi`、`for...done` 等**必须一行写完**（用 `;` 分隔，如 `for f in *; do echo $f; done`）。终端为无 PTY 的行式 shell（读取-执行循环） |
| **Tab 补全 / 交互式 TUI 程序** | 无 PTY，zsh 式补全、vim 全屏交互基本不可用 |
| **子命令读 stdin** | 会与命令循环抢输入（如 `cat` 单独运行后无法退出），避免使用 |
| **Ctrl+C 中断运行中命令** | 只清空当前输入行；要终止卡住的命令直接关闭会话重连 |
| **访问其它 App 的 /data 目录** | Android 沙箱权限，`ls /data/data/其它应用` 会 Permission denied（属正常） |

## 七、配置项速查

| 配置 | 默认 | 修改方式 | 说明 |
|---|---|---|---|
| 开关 | 关 | App 页 Switch / `ssh_start`/`ssh_stop` 命令 | `ssh_status` 查询状态 |
| 端口 | 8022 | 页面「修改」 | 1024–65535 |
| 密码 | 首启随机 | 页面「修改」 | ≥8 位；持久化 |
| 存储档 | 自动 | 系统设置授权「所有文件访问」 | 页面有跳转入口，返回后自动刷新 |
| 灭屏保持连接（WakeLock） | 开 | 页面 Switch | 关闭更省电，但灭屏后连接可能断 |

## 八、常见问题（FAQ）

**Q1：连不上？**
按序排查：① App/后台确认 SSH 状态为「运行中」；② 客户端与手机是否同网段（或经 VNT 虚拟网）；③ 端口是否 8022（改过就是新端口）；④ 用户名 `bridge` + 页面显示的密码；⑤ 手机休眠断 WiFi？（可开 WakeLock / 用 VNT 移动数据路径）。

**Q2：状态页显示「运行中. 会话 2」是什么意思？**
sshd 支持多会话，「会话 2」表示当前有 2 个客户端连接（比如 SFTP 客户端 + 终端各算一个）。会话数只增减不断开服务。

**Q3：`ls` 提示 `Permission denied`？**
看当前目录：在 `/` 或 `/data` 下系统目录无权属正常；先 `cd ~`（回 /sdcard）或裸 `cd`（回 App 目录）再操作。

**Q4：`cd ~` 后 `pwd` 显示 `/storage/emulated/0`，是不是不对？**
是同一位置——`/sdcard` 是 `/storage/emulated/0` 的符号链接，`pwd` 显示真实路径，两者等效，提示符已统一归一化为 `~`。

**Q5：终端打字没有 Tab 补全、vim 打不开？**
无 PTY 的固有限制（见 §六），属预期行为。文件编辑建议走 SFTP 拉到电脑改完再推回。

**Q6：修改了密码/端口，客户端连旧端口失败？**
端口与密码修改后即时生效（无需重启服务），客户端需用新值重连。

**Q7：升级 App 后 SSH 要重新开吗？**
不用——开关状态持久化，升级/重启后自动恢复。

**Q8：安全性如何？**
密码认证（首启随机生成，非弱口令）；主机密钥持久化（`hostkey.ser`），客户端首次连接记录指纹后可校验中间人。建议仅在可信网络或 VNT 虚拟网内使用，不要暴露到公网。

---

## 附：版本与文档

- 功能演进与五层修复实录：`docs/dev/ssh-integration-change-ledger.md`
- 调研与设计决策：`docs/research/sshd-integration.md`
- 命令文档（ssh_start/stop/status）：`docs/api/commands/system.md`
