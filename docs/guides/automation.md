# 自动化模块使用文档

> 面向使用者（小白友好）。回答「这是什么、怎么装、怎么连、能干什么、怎么排错」。  
> 本文只讲**怎么用**；「怎么实现的」见 `docs/research/droidline-feature-inventory.md`（功能全量清单）、  
> `docs/research/automation-implementation-plan.md`（实施路线）、`docs/research/automation-summary.md`（阶段性总结）。

---

## 一、这是什么（30 秒看懂）

自动化模块让你**用电脑上的 Python 脚本，远程操控你的 Android 手机**——点按钮、输文字、滑动、  
读界面、发通知、找颜色、截屏，全都由脚本自动完成。

打个比方：

- 手机 = 一双手（能点能滑）+ 一双眼睛（能读屏幕）+ 一副耳朵（能收通知）；
- 脚本 = 大脑，负责「先看屏幕 → 再决定点哪里」；
- 中间连一条线（HTTP），让大脑能指挥手脚。

**典型用途**：自动抢红包、自动签收快递验证码、自动打卡、批量注册、游戏挂机找图、定时截屏上报等。

> 本模块对标业界两大开源项目：**Droidline**（手机受控终端）与 **Auto.js/AutoX**（手机自动化脚本）。  
> 我们取其精华、去其繁重，做成了「PC 脚本驱动手机」的轻量形态。通讯方向与 Droidline 相反：  
> **手机被动监听、电脑主动连入**，无需手机常连服务器。

---

## 二、先决条件（一次性配置，5 分钟）

自动化依赖手机上的几个系统授权，**只做一次**：

| 授权                   | 作用                   | 去哪里开                            |
| -------------------- | -------------------- | ------------------------------- |
| **Bridge 辅助服务（无障碍）** | 读屏幕控件树、执行点击/滑动/手势    | 系统设置 → 无障碍 → 开启「Bridge 辅助服务」    |
| **Bridge 输入辅助（输入法）** | 往密码框/网页框输文字（兜底方案）    | 系统设置 → 语言与输入法 → 启用「Bridge 输入辅助」 |
| **通知使用权**            | 读取/回复/清除通知（验证码自动化必用） | 系统设置 → 通知使用权 → 允许「Bridge」       |

> 注意：无障碍服务如果**改过 XML 配置后要重新开关一次**才生效。三个授权都开齐，才能解锁全部能力；  
> 只开无障碍也能用「读屏 + 点击 + 滑动」这最常用的 80%。

---

## 三、连接手机（三步）

### 第 1 步：手机上启动 RPC 服务

打开 Bridge 管理后台，对目标设备下发命令 **`rpc_start`**，回执会给出：

```
RPC 服务已启动 · 监听 0.0.0.0:15161 · token 3f2a...
```

- **默认端口固定 15161**（不再随机），监听在所有网卡（`0.0.0.0`）。
- **token 是唯一鉴权凭据**，务必记下并保管好；用完记得 `rpc_stop` 关闭。

### 第 2 步：选一条网络通路（三选一）

| 方式               | 适用场景               | 连接地址示例                                                   |
| ---------------- | ------------------ | -------------------------------------------------------- |
| **A. 局域网直连**（推荐） | 手机和电脑在同一个 WiFi     | `192.168.31.109:15161`                                   |
| **B. VNT 虚拟网**   | 手机和电脑不在同一网络，已组 VNT | `86.74.128.88:15161`                                     |
| **C. adb 端口转发**  | USB 连电脑，或用无线 adb   | 先 `adb forward tcp:15161 tcp:15161`，再连 `127.0.0.1:15161` |

> 手机局域网 IP 在「设置 → WLAN → 当前网络」里查看；VNT 虚拟网 IP 在 Bridge 的组网页面查看。

### 第 3 步：电脑上安装 Python SDK

```bash
pip install requests
# 把 sdk/python/bridge_rpc 目录放到你的脚本旁边即可，或：
pip install -e sdk/python
```



---

## 四、第一个脚本（Hello World）

```python
from bridge_rpc import Bridge

# 连接手机（地址按上面三选一，token 来自 rpc_start 回执）
phone = Bridge("192.168.31.109:15161", token="你的token")

# 1. 检查无障碍是否就绪
print("无障碍就绪：", phone.a11y_ready())

# 2. 看看当前屏幕上有啥（控件树）
for node in phone.dump()[:10]:
    print(node.get("text") or node.get("desc") or node.get("className"))

# 3. 点一个按钮
phone.click("text('登录')")     # 按文字点
# phone.click(x=500, y=300)      # 或按坐标点

# 4. 输入文字
phone.set_text("className('EditText')", "hello")

# 5. 截屏保存
phone.screenshot(save_to="screen.jpg")
```

运行 `python hello.py`，手机就会跟着动起来。

---

## 五、能力全景（能做什么）

### 5.1 找控件（定位是核心技能）

一切操作的前提是「找到控件」。选择器 DSL 与 Auto.js 对齐，链式组合：

```python
from bridge_rpc import Bridge, Selector

# 三种等价写法
phone.find("text('登录')")                        # 字符串
phone.find(Selector.text("登录"))                  # 链式对象
phone.find(Selector.text_contains("确定").clickable())  # 组合条件
```

常用选择器方法：

| 方法                                                                                                                       | 含义                         |
| ------------------------------------------------------------------------------------------------------------------------ | -------------------------- |
| `text(x)` / `text_contains(x)` / `text_starts_with(x)` / `text_ends_with(x)` / `text_matches(re)`                        | 按文字精确/包含/前后缀/正则            |
| `id(x)` / `id_contains(x)`                                                                                               | 按资源 id                     |
| `desc(x)` / `desc_contains(x)`                                                                                           | 按无障碍描述                     |
| `class_name(x)`                                                                                                          | 按类名（如 `Button`、`EditText`） |
| `package_name(x)`                                                                                                        | 按包名                        |
| `clickable()` / `enabled()` / `scrollable()` / `editable()` / `checkable()` / `checked()` / `selected()` / `focused()` 等 | 布尔过滤（可传 `true/false`）      |
| `depth(n)`                                                                                                               | 按层级深度                      |

**关系查询**（找「某个元素旁边的另一个元素」，列表自动化核心）：

```python
# 找到文本为 ¥99 的那一行，点它里面的「购买」按钮
phone.find({"text": "¥99", "has": {"text": "购买"}})
# 找到「标题」下方的按钮
phone.find({"text": "标题", "below": {"clickable": True}})
```

支持的关系：`has`（后代）、`inside`（祖先）、`row`（同行/同列表项）、  
`below` / `above` / `left_of` / `right_of`（方向相邻）、`bounds`（精确坐标框）。

### 5.2 点 / 滑 / 输 / 读（元素动作）

```python
phone.click(selector)                    # 点击（三级降级：节点→可点祖先→坐标手势）
phone.long_click(x, y)                   # 坐标长按
phone.swipe(x1, y1, x2, y2, 500)         # 滑动
phone.scroll_to(selector, "down")        # 滚动直到目标可见
phone.set_text(selector, "内容")          # 输入文字（自动 IME 兜底）
phone.clear(selector)                    # 清空输入框
phone.sendkey(key="enter")               # 发按键（键名/键码/文字）
phone.back() / phone.home()              # 返回 / 回桌面

phone.exists(selector)                   # 是否存在（立即返回）
phone.wait_for(selector, timeout_ms=8000) # 等它出现
phone.wait_gone(selector)                # 等它消失
phone.wait_idle(ms=500)                  # 等画面静止（动画结束）
phone.get_text(selector)                 # 读文字
phone.count(selector)                    # 数有几个
phone.which([sel1, sel2])                # 多个候选中哪个先出现
phone.last_toast()                       # 读最近一条 Toast
```

### 5.3 设备 / 应用

```python
phone.launch("com.tencent.mm")           # 按包名启动
phone.launch_app("微信")                  # 按应用名启动
phone.open_url("https://example.com")    # 打开网址
phone.apps()                             # 已装应用列表
phone.info()                             # 设备信息汇总
phone.screen_on() / phone.locked()       # 屏幕状态
phone.wake()                             # 点亮并解锁
phone.orientation()                      # 横竖屏
phone.set_brightness(128)                # 调亮度
phone.get_mem() / phone.is_charging()    # 内存 / 充电状态
```

### 5.4 找色 / 传感器（游戏自动化）

```python
phone.find_color("#FF0000", threshold=16)          # 找某个颜色 → {"x":..,"y":..}
phone.find_multi_colors("#FF0000", points=[...])   # 多点找色（游戏道具定位）
phone.color(x, y)                                  # 读某坐标的像素色
phone.sensors("accelerometer")                     # 读传感器
```

### 5.5 文件 / 媒体

```python
phone.file_read("/sdcard/a.txt")
phone.file_write("/sdcard/a.txt", "内容")
phone.file_exists("/sdcard/a.txt")
phone.file_list("/sdcard/")
phone.file_copy(src, dst) / phone.file_move(src, dst) / phone.file_remove(path)

phone.media_position() / phone.media_duration() / phone.media_state()
phone.media_seek(10000) / phone.media_volume(80)
```

### 5.6 通知 / 剪贴板（需通知使用权）

```python
phone.notifications()                    # 当前通知列表
phone.has_notification("textContains", "验证码")  # 是否有匹配通知
phone.notification_reply(key, "好的")     # 内联回复
phone.notification_click(key) / phone.notification_dismiss(key)

phone.clipboard()                        # 读剪贴板
phone.clipboard("复制这段")               # 写剪贴板
```

### 5.7 视觉（找图 + OCR，跑在电脑端）

```python
from bridge_rpc import Bridge
from bridge_rpc.vision import Vision

phone = Bridge("192.168.31.109:15161", token="...")
vis = Vision(phone)

pt = vis.find_image("template.png")      # 找图 → (x, y) 或 None
vis.tap_image("template.png")            # 找图并点击
for item in vis.ocr():                    # OCR 整屏
    print(item["text"], item["x"], item["y"])
vis.ocr_tap("登录")                       # OCR 找文字并点击
```



> 依赖：找图 `pip install opencv-python numpy`；OCR `pip install pytesseract`（或 `paddleocr`）。

### 5.8 万能入口

```python
phone.exec("ui_scroll_to", selector="text('目标')", direction="down")
```

所有 67 条命令都能通过 `phone.exec(命令名, **参数)` 调用。

---

## 六、能做与不能做

### 能做 ✅

- 读任意前台应用的控件树、文字、id（只要应用没关闭无障碍支持）；
- 点击 / 长按 / 滑动 / 手势 / 方向滑动；
- 输入文字（含密码框，走输入法兜底）；
- 启动应用 / 打开网址 / 调亮度 / 读设备状态；
- 找色 / 多点找色 / 传感器读取；
- 文件读写 / 媒体控制；
- 读取 / 回复 / 清除通知（验证码自动化）；
- 找图 / OCR（电脑端算，手机只出截图）。

### 不能做 ❌（或暂不做）

| 事项                         | 原因                                        |
| -------------------------- | ----------------------------------------- |
| **root 底层按键注入 / Shell 命令** | 需 root + native 二进制，无障碍手势已覆盖绝大多数场景（P2 后置） |
| 脚本录制（点哪里自动生成脚本）            | 定位是「脚本已写好、手机执行」，不做录制                      |
| 按应用 VPN 代理 / 多设备配对租用       | 与 Bridge 架构冲突 / 偏离定位                      |
| 清除应用数据（clear_data）         | 需 Device Owner 系统权限                       |
| 手机端内嵌找图（OpenCV）            | 太重，找图/OCR 放电脑端更合理                         |
| 需要 root 的 getevent 输入监听    | 录制/监听场景，后置                                |



---

## 七、配置项

| 配置       | 位置                           | 说明                          |
| -------- | ---------------------------- | --------------------------- |
| RPC 端口   | `rpc_start` 的 `port` 参数      | 默认 **15161**；一般无需改          |
| RPC 监听地址 | 代码内置                         | 固定 `0.0.0.0`（所有网卡）          |
| token    | `rpc_start` 回执               | 每次请求必带；重发 `rpc_start` 会重新生成 |
| 无障碍服务开关  | 系统设置                         | 读屏/手势的硬前提                   |
| 输入法开关    | 系统设置                         | 仅密码框/网页框输入需要                |
| 通知使用权    | 系统设置                         | 仅通知功能需要                     |
| 控件树上限    | `dump(max_nodes, max_depth)` | 默认 500 节点 / 20 层，可调         |

---

## 八、常见问题（FAQ）

**Q1：`phone.a11y_ready()` 返回 False？**  
手机「设置 → 无障碍」里没开「Bridge 辅助服务」，或改过配置后没重新开关。开一下就好。

**Q2：连接超时 / Connection refused？**  
检查三件事：① `rpc_start` 是否还活着（可用 `rpc_status` 查）；② 手机和电脑是否同一网络（或 adb forward 有没有生效）；③ 端口是不是 15161。

**Q3：报「鉴权失败 token 无效」？**  
token 传错了，或 `rpc_start` 重新下发过导致 token 已更换。拿最新 token 重连。

**Q4：`set_text` 输不进文字？**  
先用 `phone.click(selector)` 聚焦输入框再输；密码框/网页框需要开启「Bridge 输入辅助」输入法。

**Q5：`find` 找不到明明看到的按钮？**  
用 `phone.dump()` 打印控件树，看它的 `text`/`desc`/`id`/`className` 实际是什么，照着写选择器。  
有时文字在 `desc`（无障碍描述）里而不是 `text` 里。

**Q6：点击了没反应？**  
无障碍服务有时会「失活」，到系统设置里关掉再重开一次；或者用坐标点击 `phone.click(x=.., y=..)` 兜底。

**Q7：通知读不到？**  
通知使用权没授权。去「设置 → 通知使用权」允许 Bridge。

**Q8：`find_color` / OCR 报 ImportError？**  
这些能力跑在电脑端，需要装对应依赖：`pip install opencv-python numpy`（找色/找图）、`pip install pytesseract`（OCR）。

**Q9：用完怎么关？**  
后台下发 `rpc_stop` 停掉 RPC 服务，端口释放。安全起见，token 不要长期挂外网。

**Q10：长按桌面图标弹出的不是菜单，而是「图标拎起来」进入拖动态？**  
这是无障碍长按的经典坑。长按命令（`ui_long_click` / `phone.long_click`）有两条实现路径：

- **坐标手势**（`dispatchGesture` 注入真实按压）→ 产生完整触摸事件流，Launcher 正常走 `onLongClick` **弹菜单**，这是想要的效果；
- **节点动作**（`ACTION_LONG_CLICK`，即 `performLongClick()`）→ 在桌面图标这类 `longClickable=true` 的控件上，会**直接进拖动态**，且**读不到 `duration_ms`**（时长参数对它无效）。

v0.2.42 起已**改为手势优先、节点动作兜底**，桌面长按会正确弹菜单。若你仍遇到拖动态，多半是手机上的版本低于 0.2.42，更新即可。真机上不同 Launcher（小米/华为等）对手势注入响应可能略有差异，`duration_ms` 默认 550ms，一般不用动。

---

## 十、管理后台「自动化调试台」（可视化探查）

除了用 Python 脚本驱动，管理后台还有一个**可视化调试台**（侧栏「自动化调试台」），
让写脚本前「摸清界面」更快——不用写代码就能看控件、测操作、一键复制选择器。

### 能做什么

- **看控件树**：下发 `ui_dump`，左侧树形展示全部控件，可搜索/折叠，文字/描述/id 一目了然。
- **截图高亮联动**：中间显示手机截图，自动叠加每个控件的边框；点树里的节点 → 截图对应框高亮；
  点截图上的框 → 选中对应节点（双向联动，像 Appium Inspector）。
- **看属性 + 复制**：右侧展示选中控件的 text/desc/id/className/bounds 和全部布尔状态；
  **一键复制选择器表达式**（如 `text("登录").clickable()`）或**参数 JSON**，直接粘进 Python 脚本。
- **操作测试**：选中控件后点「点击/长按/滑动/输入」，直接真机执行并回执，验证选择器是否有效。
  长按旁有**时长输入框**（默认 550 毫秒，可调），长按和右栏「长按测试」共用该时长。
- **坐标级操作（不依赖控件）**：截图上方有「截图操作工具」切换（选中控件 / 坐标点击 / 坐标长按 /
  坐标滑动）。切到坐标工具后，直接在**截图任意位置**操作——有些图标/控件在无障碍里没有
  `longClickable`/`clickable` 标记，控件长按会「灰」，但手动在屏幕上本来就能长按，坐标工具就是
  还原这种「任意坐标」操作：
  - **坐标点击**：单击截图任意位置 → 向该坐标下发点击；
  - **坐标长按**：单击截图任意位置 → 向该坐标下发长按（时长用顶部长按时长，精确可控）；
  - **坐标滑动**：在截图上**按住拖拽**起点到终点 → 松开后按该方向/距离滑动（拖拽中有橙色箭头预览）。
- **控件滑动**：右栏「滑动测试」按钮，基于选中控件 bounds 自动生成起终点，可选方向（上/下/左/右）、
  距离、时长，适合对列表/可滚动控件做翻页测试。
- **自动刷新**：勾选后自动重新截屏 + 拉控件树，频率可选（2/3/5/10/30 秒）。
- **目标设备自动记忆**：调试台顶部设备选择与其它页面共享同一个「目标设备」，**刷新 / 切换页面后
  自动恢复**上次选的设备，无需每次重新选择（跨页面保持，localStorage 持久化）。

### 使用方法

1. 侧栏进入「自动化调试台」，顶部选择要调试的**在线设备**（切换页面后会自动记住）；
2. 点「刷新（截图 + 控件树）」；
3. 在左树或截图上点选控件 → 右侧看属性 → 复制选择器 → 点「点击测试」验证；
4. 遇到无 `clickable`/`longClickable` 标记的图标，切「坐标点击/坐标长按/坐标滑动」直接在截图该位置操作。

> 调试台底层走的是**命令下发 + 回执广播**，和 Python RPC 共用同一套无障碍执行器，
> 所以在调试台里验证通过的选择器，粘进 Python 脚本行为完全一致。

---

## 十一、参考与进阶

- **完整可运行示例**：`sdk/python/examples/`（多个分场景脚本，复制改地址就能跑）。
- **SDK 全部方法**：`sdk/python/bridge_rpc/client.py`（Bridge 类）+ `vision.py`（Vision 类）。
- **命令清单**：`docs/research/droidline-feature-inventory.md`（能力全景）+ `docs/research/automation-summary.md`（阶段性总结）。
- **怎么实现的**：`docs/research/automation-implementation-plan.md`（批次路线）。
