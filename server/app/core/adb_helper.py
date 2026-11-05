"""adb 助手：经 PC 端 adb 为客户端做一次性系统级授权（借鉴 autoxClient 方案）。

autox 的机制（references/autoxClient，实测可行）：
1. **一次性 adb 授权**：`adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS`
   —— 永久有效（重启不掉，仅卸载重装失效），普通应用即可持有该签名级权限；
2. **应用/服务端直写系统设置**：持有 WRITE_SECURE_SETTINGS 后，
   `settings put secure enabled_accessibility_services <追加目标服务>` +
   `settings put secure accessibility_enabled 1` —— 系统随即**自动绑定**无障碍服务，
   用户完全不需要到系统设置里手动开启。

由此实现管理后台「ADB 一键开启无障碍」：浏览器本身无法执行 adb，
但管理后台背后是运行在用户电脑上的本服务端（FastAPI），由服务端 subprocess
代跑 adb 命令，效果等同「网页上一键执行」。

设备定位：`adb devices` 枚举 → WiFi 序列号（形如 ip:port）直接取 IP；
USB 序列号经 `ip addr show wlan0` 查询设备 IP → 与 WebSocket 连接的客户端 IP 匹配，
确保操作的就是后台选中的那台 Bridge 设备（多设备并发时也不串）。
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Optional

# 与客户端 app/src/main/java/my/bridge/com/access/BridgeAccessibilityService.kt 对应
PACKAGE = "my.bridge.com"
COMPONENT = "my.bridge.com/my.bridge.com.access.BridgeAccessibilityService"
WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"


def resolve_adb_path(raw: str) -> str:
    """解析 adb 可执行文件路径。

    相对路径按**服务端根目录**（app/core/adb_helper.py 上三级 = server/）解析，
    与 config.yaml 其它相对路径（data/updates 等）的语义一致；解析不到再交给
    PATH 查找。Windows 上 PATH 里的 adb 可能是 C:\\Windows 下的 1.0.32 古董版
    （不支持 adb pair），因此推荐 config 显式指向项目自带 platform-tools。
    """
    p = Path(raw)
    if p.is_absolute():
        return str(p)
    server_root = Path(__file__).resolve().parent.parent.parent  # app/core/ → server/
    cand = (server_root / raw).resolve()
    return str(cand) if cand.exists() else raw


class AdbError(Exception):
    """adb 执行环境问题（找不到 adb / 无设备 / 设备不匹配等），消息可直接展示给管理员。"""


def _run(adb_path: str, args: list[str], timeout: int = 15) -> tuple[int, str, str]:
    """执行一条 adb 命令，返回 (returncode, stdout, stderr)。环境异常抛 AdbError。"""
    cmd = [adb_path, *args]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise AdbError(
            f"找不到 adb 可执行文件（{adb_path}）。请安装 Android 平台工具并加入 PATH，"
            f"或在 server/config.yaml 的 adb.path 配置绝对路径"
        )
    except subprocess.TimeoutExpired:
        raise AdbError(f"adb 命令超时（{timeout}s）：{' '.join(args)}")
    return proc.returncode, (proc.stdout or "").strip(), (proc.stderr or "").strip()


def _serial_ip(adb_path: str, serial: str) -> Optional[str]:
    """获取某 adb 序列号对应设备的 WiFi IP。

    WiFi 序列号（形如 192.168.1.5:5555）直接解析；
    USB 序列号经 `ip addr show wlan0` 查询（查不到返回 None）。
    """
    if ":" in serial:
        return serial.rsplit(":", 1)[0]
    rc, out, _ = _run(
        adb_path,
        ["-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
        timeout=10,
    )
    if rc != 0:
        return None
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
    return m.group(1) if m else None


def _connected_serials(adb_path: str) -> list[str]:
    """枚举当前 state=device 的 adb 设备序列号。adb 环境异常抛 AdbError。"""
    rc, out, err = _run(adb_path, ["devices"])
    if rc != 0:
        raise AdbError(f"adb devices 执行失败：{err or out}")
    serials: list[str] = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def _conn_type(serial: str) -> str:
    """按序列号形态判定连接类型（供管理后台明确展示连接方式）。

    - ``adb-xxx._adb-tls-connect._tcp`` → wifi_mdns（mDNS 自动发现的无线设备）
    - ``ip:port``（如 192.168.1.5:5555） → wifi（adb connect 建立的无线连接）
    - 其余（纯硬件序列号） → usb（数据线）
    """
    if "._adb" in serial:
        return "wifi_mdns"
    if re.fullmatch(r"\d+\.\d+\.\d+\.\d+:\d+", serial):
        return "wifi"
    return "usb"


def list_devices_with_ips(adb_path: str) -> list[dict]:
    """列出所有已连接 adb 设备及其 IP 与连接类型（供管理后台展示，让用户交叉确认）。

    返回 [{serial, ip?, conn_type}]；conn_type ∈ usb / wifi / wifi_mdns。
    WiFi 序列号直接解析 IP，USB 设备查 wlan0。
    """
    result = []
    for serial in _connected_serials(adb_path):
        result.append(
            {"serial": serial, "ip": _serial_ip(adb_path, serial), "conn_type": _conn_type(serial)}
        )
    return result


def find_serial_for_ips(adb_path: str, ips: list[str]) -> str:
    """在与服务端 adb 已连接的设备里，找出目标 Bridge 设备对应的那台。

    ips 为目标 Bridge 设备上报的**全部** IP 列表（hello 里 ips 字段），
    **任一命中**即确认。匹配策略（按序）：
    1. **单设备直连**：只连了一台 adb 设备时直接采用——IP 匹配不可靠
       （手机连服务端可能走公网，WS 记录的 IP 与局域网 IP 不一致），单设备场景
       用户插/连的显然就是目标机；
    2. 多设备时按 IP 匹配（WiFi 序列号直接解析 / USB 查 wlan0，对 ips 逐个比对）；
    3. 多设备且无匹配 → 报错列出所有设备与 IP，请用户先只连目标机。
    """
    serials = _connected_serials(adb_path)
    if not serials:
        raise AdbError(
            "没有已连接的 adb 设备。请用数据线连接手机到运行服务端的电脑"
            "（手机上允许「USB 调试」），或建立 WiFi adb 连接后重试"
        )
    if len(serials) == 1:
        return serials[0]
    targets = {ip for ip in ips if ip}
    for serial in serials:
        if _serial_ip(adb_path, serial) in targets:
            return serial
    raise AdbError(
        f"已连接多台 adb 设备（{', '.join(serials)}），其 IP 与目标设备上报的"
        f"任一 IP（{', '.join(sorted(targets)) or '无'}）均不匹配，无法确定哪台是目标设备。"
        "请先只连接目标手机后重试"
    )


def pair_device(adb_path: str, address: str, code: str) -> None:
    """adb pair 无线调试配对。失败抛 AdbError（消息可直接展示）。

    address 形如 192.168.1.8:37125（手机「使用配对码配对设备」页显示的 IP:端口）。
    """
    rc, out, err = _run(adb_path, ["pair", address, code], timeout=20)
    combined = f"{out} {err}".lower()
    if rc != 0 or ("successfully paired" not in combined and "already paired" not in combined):
        raise AdbError(
            f"配对失败（adb pair {address}）：{(out + ' ' + err).strip() or '未知错误'}。"
            "请核对：① 配对端口是「使用配对码配对设备」页显示的端口（每次点开会变）；"
            "② 配对码是页面上的 6 位数字；③ 手机与电脑在同一 WiFi"
        )


def connect_device(adb_path: str, address: str) -> str:
    """adb connect 建立无线连接，返回连接输出。失败抛 AdbError。

    address 形如 192.168.1.8:43567（手机「无线调试」主界面显示的 IP:端口，
    与配对端口不同；端口在重开无线调试后会变）。
    """
    try:
        rc, out, err = _run(adb_path, ["connect", address], timeout=20)
    except AdbError as e:
        if "超时" in str(e):
            raise AdbError(
                f"连接 {address} 超时：手机 IP 可能不正确，或与电脑不在同一 WiFi、"
                "无线调试开关未开启。请核对手机「无线调试」主界面显示的 IP 和端口"
            )
        raise
    combined = f"{out} {err}"
    low = combined.lower()
    # adb connect 失败时退出码也是 0，必须按输出判断
    ok = ("connected to" in low) and not ("cannot" in low or "failed" in low)
    if rc != 0 or not ok:
        raise AdbError(
            f"连接失败（adb connect {address}）：{combined.strip() or '未知错误'}。"
            "请核对：① 端口是「无线调试」主界面显示的端口（不是配对端口）；"
            "② 手机无线调试开关处于开启状态"
        )
    return combined.strip()


def enable_accessibility(adb_path: str, serial: str) -> list[dict]:
    """对指定设备执行「授权 + 自动开启无障碍」，返回逐步骤结果供管理后台展示。

    步骤：① pm grant WRITE_SECURE_SETTINGS（已授予视为成功）
          ② 追加写入 enabled_accessibility_services（保留已有服务）
          ③ accessibility_enabled = 1
          ④ 回读验证
    """
    steps: list[dict] = []

    def step(name: str, ok: bool, detail: str = "") -> bool:
        steps.append({"step": name, "ok": ok, "detail": detail})
        return ok

    # ① 一次性授予 WRITE_SECURE_SETTINGS（重复执行返回 already granted，视为成功）
    #    注意：此步失败**不中止**——后续 settings put 由 adb 直接执行，不依赖应用持有
    #    该权限；授予成功仅用于「应用侧直写」能力（autox 式重开无障碍）作为储备。
    rc, out, err = _run(
        adb_path, ["-s", serial, "shell", "pm", "grant", PACKAGE, WRITE_SECURE_SETTINGS]
    )
    combined = f"{out} {err}".lower()
    granted = rc == 0 or "already" in combined
    if granted:
        step("授予 WRITE_SECURE_SETTINGS（一次性，永久有效）", True, "应用侧直写能力就绪")
    else:
        detail = f"{out} {err}".strip()
        # 未声明权限 → APK 过旧（v0.1.40 及以前），不影响本次 adb 直写
        if "has not requested permission" in combined:
            step("授予 WRITE_SECURE_SETTINGS", False, f"APK 未声明该权限（需 v0.1.41+），本次跳过、不影响开启：{detail}")
        else:
            step("授予 WRITE_SECURE_SETTINGS", False, detail)

    # ② 追加 Bridge 辅助服务到已启用列表（不清除其他应用的无障碍服务）
    rc, cur, err = _run(
        adb_path,
        ["-s", serial, "shell", "settings", "get", "secure", "enabled_accessibility_services"],
    )
    current = "" if cur in ("", "null") else cur
    if COMPONENT in current:
        step("追加 Bridge 辅助服务", True, "已在启用列表中，跳过写入")
    else:
        merged = f"{current}:{COMPONENT}" if current else COMPONENT
        rc, out, err = _run(
            adb_path,
            [
                "-s", serial, "shell", "settings", "put", "secure",
                "enabled_accessibility_services", merged,
            ],
        )
        if not step("写入 enabled_accessibility_services", rc == 0, err or merged):
            return steps

    # ③ 总开关
    rc, out, err = _run(
        adb_path,
        ["-s", serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1"],
    )
    if not step("开启总开关 accessibility_enabled", rc == 0, err or "1"):
        return steps

    # ④ 回读验证
    rc, out, _ = _run(
        adb_path,
        ["-s", serial, "shell", "settings", "get", "secure", "enabled_accessibility_services"],
    )
    step("回读验证", COMPONENT in out, out)
    return steps


# ---------------------------------------------------------------------------
# 首次安装权限盘点 + ADB 一键全量授权（v0.2.51）
#
# 权限分三类，逐类处理：
# 1. pm grant 可直接授予的「危险权限」（dangerous）——定位/相机/麦克风/蓝牙/通讯录/
#    短信/通话记录/日历/电话状态/通知/媒体/体感等，重启不掉、卸载重装才需重做。
# 2. pm grant 可授予的「特殊权限」（special）——WRITE_SECURE_SETTINGS（无障碍直写开关）。
# 3. appops 类（需 appops set 或特殊命令）——WRITE_SETTINGS / SYSTEM_ALERT_WINDOW /
#    REQUEST_INSTALL_PACKAGES / PACKAGE_USAGE_STATS / MANAGE_EXTERNAL_STORAGE /
#    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
#
# 白名单刻意只含「Bridge 实际声明且业务会用到」的权限，不滥授无关权限。
# ---------------------------------------------------------------------------

# ① pm grant 危险权限白名单（全部在 AndroidManifest 已声明）
GRANT_DANGEROUS: list[str] = [
    # 定位
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    # 相机 / 麦克风
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    # 通讯录 / 账户
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.GET_ACCOUNTS",
    # 通话记录 / 电话状态 / 接听
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.ANSWER_PHONE_CALLS",
    # 短信 / 彩信
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.RECEIVE_MMS",
    "android.permission.RECEIVE_WAP_PUSH",
    # 日历
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    # 体感 / 活动识别
    "android.permission.BODY_SENSORS",
    "android.permission.ACTIVITY_RECOGNITION",
    # 蓝牙（Android 12+ 运行时权限）
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_ADVERTISE",
    # 媒体（Android 13+ 细分；旧版 READ/WRITE_EXTERNAL_STORAGE 一并兼容）
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    # 通知（Android 13+）
    "android.permission.POST_NOTIFICATIONS",
    # 附近 WiFi 设备（Android 13+）
    "android.permission.NEARBY_WIFI_DEVICES",
]

# ② pm grant 特殊权限白名单
GRANT_SPECIAL: list[str] = [
    "android.permission.WRITE_SECURE_SETTINGS",
]

# ③ appops 类：{权限名: (appops 名, 允许值)}
APPOPS_PERMISSIONS: dict[str, tuple[str, str]] = {
    "android.permission.WRITE_SETTINGS": ("android:write_settings", "allow"),
    "android.permission.SYSTEM_ALERT_WINDOW": ("android:system_alert_window", "allow"),
    "android.permission.REQUEST_INSTALL_PACKAGES": ("android:request_install_packages", "allow"),
    "android.permission.PACKAGE_USAGE_STATS": ("android:get_usage_stats", "allow"),
    "android.permission.MANAGE_EXTERNAL_STORAGE": ("android:manage_external_storage", "allow"),
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": ("android:ignore_battery_optimizations", "allow"),
}


def grant_all_permissions(adb_path: str, serial: str) -> dict:
    """对指定设备执行「ADB 一键全量授权」，返回分组的授权结果供管理后台展示。

    三类：
    - ``granted``：pm grant 危险 + 特殊权限的逐条结果（已授予视为成功）；
    - ``appops``：appops 类权限的逐条结果（不可 pm grant，改用 appops set）；
    - ``manual``：仍需设备端手动确认的清单（默认短信应用 / 通知使用权 / VPN 授权等）。

    返回 {granted: [...], appops: [...], manual: [...]}，每条含 name/ok/detail。
    """
    granted: list[dict] = []
    appops: list[dict] = []
    manual: list[dict] = []

    # ① pm grant：危险 + 特殊权限
    for perm in GRANT_DANGEROUS + GRANT_SPECIAL:
        rc, out, err = _run(adb_path, ["-s", serial, "shell", "pm", "grant", PACKAGE, perm])
        combined = f"{out} {err}".lower()
        ok = rc == 0 or "already" in combined
        granted.append(
            {
                "name": perm,
                "ok": ok,
                "detail": "已授予" if ok else f"{out} {err}".strip() or "授予失败",
            }
        )

    # ② appops 类权限
    for perm, (op, value) in APPOPS_PERMISSIONS.items():
        rc, out, err = _run(
            adb_path,
            ["-s", serial, "shell", "appops", "set", PACKAGE, op, value],
        )
        ok = rc == 0
        appops.append(
            {
                "name": perm,
                "ok": ok,
                "detail": f"appops {op}={value} 已设置" if ok else f"{out} {err}".strip() or "设置失败",
            }
        )

    # ③ 仍需设备端手动确认的清单（无法经 adb 自动授权）
    manual = [
        {"name": "默认短信应用", "reason": "需在设备端短信页点「设为默认」一次，或系统设置手动切换"},
        {"name": "通知使用权", "reason": "NotificationListener 需在系统「通知使用权」页手动开启一次"},
        {"name": "VPN 授权", "reason": "VpnService.prepare() 需设备端前台点一次「确定」（组网首次启动时）"},
        {"name": "电池白名单", "reason": "厂商 ROM 各异的电池优化白名单，需设备端手动加入（部分 ROM 可 appops 处理）"},
    ]

    return {"granted": granted, "appops": appops, "manual": manual}

