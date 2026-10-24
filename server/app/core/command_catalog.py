"""命令目录（单一事实来源）。

本模块是「管理后台可下发的命令」的**唯一权威定义**：
- 每个命令属于一个类别（category），带中文名、说明、图标、参数 schema。
- 管理后台通过 ``GET /api/commands/meta`` 拉取本目录，据此动态渲染
  分类树 + 命令卡片墙 + 参数表单，**无需前端写死命令清单**。
- 服务端下发校验（白名单）也由本目录派生，避免与客户端命令表脱节。

扩展命令时：只需在 ``CATEGORIES`` / ``COMMANDS`` 里新增条目，
并在客户端 [CommandModule] 注册对应的 [CommandExecutor] 即可。
"""

from __future__ import annotations

from typing import Any

# ---------------------------------------------------------------------------
# 参数 schema：轻量声明式定义（不依赖 JSON Schema 库）
#
# 每个参数字段：
#   name:         参数键（下发给客户端的 params 里的 key）
#   label:        界面展示名（中文）
#   type:         控件类型，见 ParamType
#   required:     是否必填
#   default:      缺省值（None = 无缺省）
#   placeholder:  输入框占位提示
#   options:      仅 type=select 时有效，可选项列表
#   description:  参数说明
#
# 下发时前端把填好的值组装成 params: {name: value, ...} 提交。
# ---------------------------------------------------------------------------

# 参数控件类型（与前端渲染器一一对应）
PARAM_TYPE_TEXT = "text"
PARAM_TYPE_NUMBER = "number"
PARAM_TYPE_SWITCH = "switch"
PARAM_TYPE_SELECT = "select"
PARAM_TYPE_TEXTAREA = "textarea"


def _cat(key: str, label: str, icon: str, description: str = "") -> dict[str, Any]:
    """构造一个命令类别。"""
    return {"key": key, "label": label, "icon": icon, "description": description}


def _cmd(
    name: str,
    label: str,
    category: str,
    icon: str,
    description: str,
    params: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """构造一条命令定义。"""
    return {
        "name": name,
        "label": label,
        "category": category,
        "icon": icon,
        "description": description,
        "params": params or [],
    }


def _param(
    name: str,
    label: str,
    type_: str,
    required: bool = False,
    default: Any = None,
    placeholder: str = "",
    options: list[dict[str, str]] | None = None,
    description: str = "",
) -> dict[str, Any]:
    """构造一个参数定义。"""
    p: dict[str, Any] = {
        "name": name,
        "label": label,
        "type": type_,
        "required": required,
        "placeholder": placeholder,
        "description": description,
    }
    if default is not None:
        p["default"] = default
    if options is not None:
        p["options"] = options
    return p


# ---------------------------------------------------------------------------
# 命令类别（有图标，用 Emoji 作为轻量图标，前端直接渲染）
# ---------------------------------------------------------------------------

CATEGORIES: list[dict[str, Any]] = [
    _cat("control", "控制", "🎛️", "控制客户端播放/暂停、调节音量等"),
    _cat("data", "数据采集", "📡", "获取客户端位置、页面、短信、网络等状态数据"),
    _cat("media", "音视频", "🎬", "录屏、截屏、拍照、上传音视频"),
    _cat("interact", "交互", "💬", "弹窗、语音播报、震动、Toast 等交互反馈"),
    _cat("system", "系统", "⚙️", "诊断日志、重启、锁屏等系统级命令"),
]

# ---------------------------------------------------------------------------
# 命令定义（单一事实来源）
# ---------------------------------------------------------------------------

COMMANDS: list[dict[str, Any]] = [
    # ---- 控制类 ----
    _cmd("play_music", "播放音乐", "control", "▶️", "让客户端播放指定音乐",
         [_param("url", "音乐地址", PARAM_TYPE_TEXT, required=True,
                 placeholder="http(s)://… 或本地文件路径", description="音乐文件地址"),
          _param("loop", "循环播放", PARAM_TYPE_SWITCH, default=False)]),
    _cmd("pause_music", "暂停音乐", "control", "⏸️", "暂停客户端正在播放的音乐"),
    _cmd("set_volume", "调节音量", "control", "🔊",
         "调节客户端媒体音量",
         [_param("level", "音量值", PARAM_TYPE_NUMBER, required=True, default=50,
                 placeholder="0-100", description="音量百分比（0-100）")]),
    _cmd("lock_screen", "锁屏", "control", "🔒", "锁定客户端屏幕"),
    _cmd("reboot", "重启", "control", "🔄", "重启客户端设备"),

    # ---- 数据采集类 ----
    _cmd("get_location", "获取位置", "data", "📍", "获取客户端当前 GPS 位置数据"),
    _cmd("get_current_page", "获取当前页面", "data", "📄", "获取客户端当前显示的页面信息"),
    _cmd("get_sms", "获取短信", "data", "💬", "获取客户端短信信息"),
    _cmd("get_network", "获取网络状态", "data", "📶", "获取客户端当前网络状态"),
    _cmd("get_battery", "获取电量", "data", "🔋", "获取客户端当前电量与充电状态"),

    # ---- 音视频类 ----
    _cmd("screenshot", "截屏", "media", "📸", "截取客户端当前屏幕并上传"),
    _cmd("record_screen", "录屏", "media", "🎥", "录制客户端屏幕",
         [_param("duration", "时长(秒)", PARAM_TYPE_NUMBER, required=True, default=10,
                 placeholder="默认 10 秒", description="录制时长（秒）")]),
    _cmd("take_photo", "拍照", "media", "📷", "调用客户端摄像头拍照并上传"),

    # ---- 交互类 ----
    _cmd("show_toast", "弹提示", "interact", "💬", "在客户端显示一条 Toast 提示",
         [_param("text", "提示内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="要显示的文字", description="Toast 显示的文字")]),
    _cmd("speak", "语音播报", "interact", "🔊", "让客户端朗读一段文字",
         [_param("text", "播报内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="要朗读的文字")]),
    _cmd("vibrate", "震动", "interact", "📳", "让客户端震动",
         [_param("duration", "时长(毫秒)", PARAM_TYPE_NUMBER, default=500,
                 placeholder="默认 500 毫秒")]),
    _cmd("show_dialog", "弹窗", "interact", "🪟", "在客户端弹出对话框",
         [_param("title", "标题", PARAM_TYPE_TEXT, placeholder="弹窗标题"),
          _param("content", "内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="弹窗正文")]),

    # ---- 系统类 ----
    _cmd("upload_log", "上传日志", "system", "📤", "让客户端自动上传诊断日志"),
    _cmd("check_update", "检查更新", "system", "🔎", "让客户端检查应用更新"),
]


# ---------------------------------------------------------------------------
# 白名单派生
# ---------------------------------------------------------------------------

# 服务端允许下发的命令白名单（从目录派生）
ALLOWED_COMMANDS: set[str] = {c["name"] for c in COMMANDS}


def catalog_payload() -> dict[str, Any]:
    """组装 meta 接口返回体：类别 + 命令。"""
    return {
        "categories": CATEGORIES,
        "commands": COMMANDS,
    }
