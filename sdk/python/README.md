# Bridge RPC Python SDK

用 Python 脚本驱动 Bridge 手机自动化。手机端内嵌 HTTP/REST 服务，Python 脚本作为客户端连入（通讯方向与 Droidline 相反：手机被动监听、Python 主动连入）。

## 快速开始

### 1. 手机上启动 RPC 服务

在 Bridge 管理后台下发命令 `rpc_start`，得到：

```
RPC 服务已启动 · 端口 15161 · token 3f2a...
```

### 2. 连入地址（任选其一）

RPC 服务监听在 `0.0.0.0`（所有网卡），默认端口 `15161`，PC 上的 Python 脚本可通过以下任一地址直接连入：

**方式 A：同一局域网（推荐）**

手机与 PC 同一 WiFi 下，用手机局域网 IP 直连：

```python
phone = Bridge("192.168.31.109:15161", token="3f2a...")
```

**方式 B：VNT 虚拟网组网**

已组 VNT 虚拟网时，用手机虚拟网 IP 直连（无需同 WiFi）：

```python
phone = Bridge("86.74.128.88:15161", token="3f2a...")
```

**方式 C：adb 端口转发（USB / 无线 adb）**

```bash
# 把 PC 的 15161 转发到手机本机 15161
adb forward tcp:15161 tcp:15161
```

```python
phone = Bridge("127.0.0.1:15161", token="3f2a...")
```

> 安全提示：监听 `0.0.0.0` 意味着任何能路由到手机 IP 的主机都可尝试连接，鉴权完全依赖 token。请妥善保管 token，用完及时 `rpc_stop`。

### 3. 安装 SDK

```bash
pip install -e .          # 或 pip install requests 后直接把 bridge_rpc 目录放进脚本目录
```

### 4. 写脚本

```python
from bridge_rpc import Bridge, Selector

# 连接（token 来自 rpc_start 回执；地址用局域网/VNT/本机转发任选其一）
phone = Bridge("192.168.31.109:15161", token="3f2a...")

# 导出控件树（含 depth 层级，可打印看结构）
tree = phone.dump()
for node in tree[:10]:
    print(node.get("depth"), node.get("className"), node.get("text"), node.get("id"))

# 查找控件
nodes = phone.find("text('登录')")
print("找到", len(nodes), "个登录按钮")

# 等待控件出现（最多等 10 秒）
phone.wait_for(Selector.text_contains("确定").clickable(), timeout_ms=10000)

# 点击（字符串或 Selector 均可）
phone.click("登录")
phone.click(Selector.id("com.xx:id/btn"))

# 按坐标点击
phone.click(x=500, y=300)

# 输入文本（自动 ACTION_SET_TEXT → IME 兜底）
phone.set_text("className('EditText')", "hello world")

# 滑动
phone.swipe(100, 800, 100, 200, duration_ms=500)

# 返回 / 回桌面
phone.back()
phone.home()

# 截屏（返回 JPEG 字节，可落盘）
img = phone.screenshot(save_to="screen.jpg")

# 无障碍就绪检查
print("无障碍就绪:", phone.a11y_ready())
```

## 更多能力（v0.2.40 起）

SDK 提供通用 `exec` 入口 + 语义化方法，覆盖 70 条自动化命令：

```python
# 元素动作增强
phone.scroll_to("text('目标')", direction="down")   # 滚动直到可见
phone.wait_idle(ms=500)                              # 等画面静止
phone.clear("className('EditText')")                 # 清空输入框
phone.sendkey(key="enter")                           # 发送按键
phone.exists("text('登录')")                         # 是否立即存在
phone.get_text("text('标题')")                       # 读取文本
phone.count("className('Button')")                   # 匹配数量

# 关系查询（对象 JSON，支持 row/below/inside/has 等）
phone.find({"text": "¥99", "below": {"text": "加入购物车"}})

# 设备 / 应用
phone.launch("com.tencent.mm")                       # 启动应用
phone.launch_app("微信")                             # 按名称启动
phone.open_url("https://example.com")
phone.apps(system=False)                             # 已装应用
phone.set_brightness(128)
phone.screen_on() / phone.locked() / phone.orientation()

# 找色 / 传感器（游戏自动化）
phone.find_color("#FF0000", threshold=16)            # → {"x":..,"y":..}
phone.find_multi_colors("#FF0000", points=[10, 0, 0xFFFFFF, ...])
phone.sensors("accelerometer")

# 文件 / 媒体
phone.file_read("/sdcard/a.txt") / phone.file_write("/sdcard/a.txt", "x")
phone.media_seek(10000) / phone.media_position()

# 通知 / 剪贴板（需通知使用权）
phone.notifications()                                # 通知列表
phone.has_notification("textContains", "验证码")
phone.clipboard()                                    # 读剪贴板
phone.clipboard("hello")                             # 写剪贴板

# 通用入口（任意白名单命令）
phone.exec("ui_launch", package="com.tencent.mm")
```

## 视觉能力（opencv 找图 + OCR，跑在 PC 端）

```python
from bridge_rpc import Vision

vis = Vision(phone)
pt = vis.find_image("template.png")     # 找图 → (x, y) 或 None
vis.tap_image("template.png")           # 找图并点击
for item in vis.ocr():                  # OCR 整屏
    print(item["text"], item["x"], item["y"])
vis.ocr_tap("登录")                     # OCR 找文字并点击
```

> 依赖：找图 `pip install opencv-python numpy`；OCR `pip install pytesseract`（或 `paddleocr`）。

## 选择器 DSL

`Selector` 链式构建，最终序列化为 autox 风格表达式（也支持直接传字符串）：

```python
Selector.text("登录")                       # text("登录")
Selector.text_contains("确定").clickable()  # textContains("确定").clickable()
Selector.id("com.xx:id/btn")
Selector.class_name("Button").enabled()
Selector.desc("搜索").clickable()
```

字符串形式等价（直接传给 find/click 即可）：

```python
phone.click("textContains('确定').clickable()")
```

支持的方法：`text/text_contains/text_starts_with/text_ends_with/text_matches`、`id/id_contains`、`desc/desc_contains`、`class_name/class_name_contains`、`package_name`，以及布尔 `clickable/enabled/scrollable/editable/long_clickable/checkable/checked/focusable/focused/selected/visible_to_user/password`、`depth`。

## 错误处理

所有失败抛 `RpcError`，带结构化字段：

```python
from bridge_rpc import Bridge, RpcError

try:
    phone.click("登录")
except RpcError as e:
    print(e.error_code)   # COMMAND_FAILED / UNAUTHORIZED / TIMEOUT / NOT_FOUND ...
    print(e.retryable)    # 是否可重试
    if e.retryable:
        # 可重试：等待后重试
        phone.wait_for("登录", timeout_ms=5000)
        phone.click("登录")
```

## 完整示例（分场景，复制改地址即可跑）

示例脚本在 `examples/` 目录，共用 `_common.py` 里的连接配置（先改 `_common.py` 的 HOST / TOKEN 再跑）：

| 文件 | 内容 |
|---|---|
| `examples/example1_basic.py` | 快速上手：连接、导树、找控件、点击、输入、滑动、截屏 |
| `examples/example2_ui_controls.py` | 控件进阶：关系查询、滚动、等待、多分支、读文本 |
| `examples/example3_device.py` | 设备/应用：启动、设备信息、亮度、内存、应用列表 |
| `examples/example4_color_sensor.py` | 找色/传感器：find_color、find_multi_colors、取色 |
| `examples/example5_notification_code.py` | 验证码自动化：通知监听 → 读验证码 → 回填 |
| `examples/example6_vision.py` | 视觉：opencv 找图 + OCR |
| `examples/example7_file_media.py` | 文件/媒体：读写、复制、seek、音量 |

完整使用文档见 `docs/guides/automation.md`（面向小白），能力全景见 `docs/research/droidline-feature-inventory.md`。
