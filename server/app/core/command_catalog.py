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
    _cmd("play_music", "播放音乐", "control", "▶️",
         "播放音乐：URL 流播 / 本地路径直播 / 文件名走「客户端缓存+服务器共享目录」",
         [_param("url", "音源", PARAM_TYPE_TEXT, required=True,
                 placeholder="http(s)://… 或 /storage/… 或 test.mp3",
                 description="三种形态：①远程 URL 直接流播；②本地绝对路径直接播放；"
                             "③裸文件名先查客户端缓存，未命中从服务器音乐目录下载缓存后播放（下次直接命中）"),
          _param("loop", "循环播放", PARAM_TYPE_SWITCH, default=False)]),
    _cmd("pause_music", "暂停音乐", "control", "⏸️",
         "暂停客户端正在播放的音乐（无播放时回执失败）"),
    _cmd("resume_music", "继续播放", "control", "⏯️",
         "从暂停进度恢复播放（仅暂停中有效，播放中/空闲回执失败）"),
    _cmd("stop_music", "停止音乐", "control", "⏹️",
         "停止播放并释放播放器（播放中/暂停中有效，空闲回执失败）"),
    _cmd("set_volume", "调节音量", "control", "🔊",
         "调节客户端指定音频流的音量（媒体/铃声/闹钟），按百分比换算设备实际档位，"
         "设置后回读验证；免打扰模式开启时铃声/闹钟流可能被系统拒绝，回执如实说明",
         [_param("level", "音量值", PARAM_TYPE_NUMBER, required=True, default=50,
                 placeholder="0-100", description="音量百分比（0-100）"),
          _param("stream", "音量类型", PARAM_TYPE_SELECT, default="media",
                 options=[
                     {"value": "media", "label": "媒体音量（音乐/视频）"},
                     {"value": "ring", "label": "铃声（来电/通知）"},
                     {"value": "alarm", "label": "闹钟"},
                 ],
                 description="调节哪一类音量")]),
    _cmd("lock_screen", "锁屏", "control", "🔒",
         "让客户端立即锁屏（熄屏并上锁）。首次使用需在设备上激活一次「设备管理员」"
         "（命令回执会附激活步骤；激活后应用须先停用管理员才能卸载）",
         []),
    _cmd("restart_app", "重启客户端", "control", "♻️",
         "重启 Bridge 客户端应用进程（约 1 秒后自动重连）。"
         "设备整机重启需 root 权限，未实现", []),
    _cmd("send_sms", "发送短信", "control", "📨",
         "让客户端用本机 SIM 卡发送短信（真实计费操作，请确认号码与内容后再下发）；"
         "支持多号码、长短信自动切分、指定发送卡，回执带逐号码发送结果",
         [_param("phones", "接收号码", PARAM_TYPE_TEXT, required=True,
                 placeholder="13800000001，13800000002（逗号/分号分隔可发多个）",
                 description="收件人手机号，支持中英文逗号/分号/竖线分隔多号码，逐个发送"),
          _param("content", "短信内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="要发送的内容",
                 description="超过 70 字自动按 multipart 切分发送"),
          _param("sim", "发送卡", PARAM_TYPE_SELECT, default="auto",
                 options=[
                     {"value": "auto", "label": "自动（系统默认卡）"},
                     {"value": "sim1", "label": "卡 1"},
                     {"value": "sim2", "label": "卡 2"},
                     {"value": "sim3", "label": "卡 3"},
                 ],
                 description="指定用哪张卡发送；设备无该卡时回执失败。"
                             "后台按设备实际卡动态显示（号码/运营商），"
                             "未显示具体卡信息时先下发一次「获取SIM卡信息」")]),
    _cmd("dial_phone", "拨打电话", "control", "☎️",
         "让客户端拨打电话：直接拨打（CALL_PHONE）或仅打开拨号盘预填号码",
         [_param("phone", "电话号码", PARAM_TYPE_TEXT, required=True,
                 placeholder="13800000000",
                 description="要拨打的号码"),
          _param("mode", "拨打方式", PARAM_TYPE_SELECT, default="direct",
                 options=[
                     {"value": "direct", "label": "直接拨打（跳过拨号盘）"},
                     {"value": "dialer", "label": "打开拨号盘（用户手动拨出）"},
                 ],
                 description="直接拨打需 CALL_PHONE 运行时权限（缺失自动降级为拨号盘）"),
          _param("sim", "使用卡", PARAM_TYPE_SELECT, default="auto",
                 options=[
                     {"value": "auto", "label": "自动（系统默认卡）"},
                     {"value": "sim1", "label": "卡 1"},
                     {"value": "sim2", "label": "卡 2"},
                     {"value": "sim3", "label": "卡 3"},
                 ],
                 description="直接拨打时指定用哪张卡；拨号盘模式由用户在设备上自行选卡。"
                             "后台按设备实际卡动态显示（号码/运营商），"
                             "未显示具体卡信息时先下发一次「获取SIM卡信息」")]),

    # ---- 数据采集类 ----
    _cmd("get_location", "获取位置", "data", "📍",
         "获取客户端当前位置：粗略（网络定位，快）或精确（GPS，慢）；"
         "v1 纯文本回执，后续版本将上报服务端持久化",
         [_param("precision", "定位精度", PARAM_TYPE_SELECT, default="coarse",
                 options=[
                     {"value": "coarse", "label": "粗略（网络定位，秒级返回）"},
                     {"value": "fine", "label": "精确（GPS 定位，需空旷环境）"},
                 ],
                 description="粗略=基站/WiFi 定位（误差几百米）；精确=GPS 卫星定位（误差几十米内，"
                             "冷启动可能 30 秒+，超时自动回退网络定位）"),
          _param("timeout", "超时(秒)", PARAM_TYPE_NUMBER, default=15,
                 placeholder="默认 15 秒", description="定位等待上限；精确模式建议 30 秒"),
          _param("refresh", "强制刷新", PARAM_TYPE_SWITCH, default=False,
                 description="开启后跳过 5 分钟新鲜缓存短路，直接监听一次定位；"
                             "陈旧缓存兜底仍保留（回执注明缓存年龄）")]),
    _cmd("get_current_page", "获取当前页面", "data", "📄", "获取客户端当前显示的页面信息"),
    _cmd("get_contacts", "获取通讯录", "data", "👥",
         "读取设备联系人（姓名+号码+邮箱）并上报服务端持久化（按设备覆盖快照），"
         "后台「通讯录」页查看；只读，不做任何写入",
         [_param("only_with_phones", "仅含有号码", PARAM_TYPE_SWITCH, default=True,
                 description="开启后过滤掉没有任何电话号码的联系人（如纯邮箱联系人）"),
          _param("limit", "数量上限", PARAM_TYPE_NUMBER, default=2000,
                 placeholder="默认 2000", description="最多上报的联系人数量（上限 5000，防止超大体量通讯录）")]),
    _cmd("get_sms", "获取短信", "data", "💬",
         "读取设备短信并上报服务端持久化（累积存档，按设备+短信ID去重），"
         "后台「短信」页查看；只读，不发送、不修改",
         [_param("sms_type", "短信类型", PARAM_TYPE_SELECT, default="all",
                 options=[
                     {"value": "all", "label": "全部"},
                     {"value": "inbox", "label": "收件箱"},
                     {"value": "sent", "label": "已发送"},
                     {"value": "draft", "label": "草稿"},
                     {"value": "outbox", "label": "发送中"},
                     {"value": "failed", "label": "发送失败"},
                 ],
                 description="筛选短信类型；收件箱用显式 type=1 查询，"
                             "规避部分 ROM 收件箱视图漏验证码短信的问题"),
          _param("limit", "条数上限", PARAM_TYPE_NUMBER, default=100,
                 placeholder="默认 100", description="单次最多读取的短信条数（上限 1000，分次下发可增量拉取）")]),
    _cmd("get_call_log", "获取通话记录", "data", "📞",
         "读取设备通话记录（号码/联系人/类型/时长）并上报服务端持久化（按设备覆盖快照），"
         "后台「通话记录」页查看；只读",
         [_param("call_type", "通话类型", PARAM_TYPE_SELECT, default="all",
                 options=[
                     {"value": "all", "label": "全部"},
                     {"value": "incoming", "label": "呼入"},
                     {"value": "outgoing", "label": "呼出"},
                     {"value": "missed", "label": "未接"},
                 ],
                 description="筛选通话类型"),
          _param("limit", "条数上限", PARAM_TYPE_NUMBER, default=200,
                 placeholder="默认 200", description="单次最多读取的记录条数（上限 1000）")]),
    _cmd("get_sim_info", "获取SIM卡信息", "data", "📱",
         "读取设备 SIM 卡列表（卡槽数量、本机号码、运营商）并上报服务端；"
         "「发送短信/拨打电话」的选卡下拉会按此结果动态显示具体卡信息；只读"),
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
