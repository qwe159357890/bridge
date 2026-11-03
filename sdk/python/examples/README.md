# 示例脚本索引

所有示例共用 `_common.py` 里的连接配置（HOST / TOKEN），**先改 `_common.py` 再跑**。

## 快速开始

```bash
# 1. 手机后台下发 rpc_start，拿到 token
# 2. 编辑 _common.py，填 HOST 和 TOKEN
# 3. 直接跑：
python example1_basic.py
```

## 示例列表

| 文件 | 演示内容 | 前置授权 |
|---|---|---|
| `example1_basic.py` | **快速上手**：连接、导控件树、找控件、点击、输入、滑动、截屏 | 无障碍 |
| `example2_ui_controls.py` | **控件进阶**：关系查询、scroll_to 滚动、wait_for/wait_idle、which 多分支、读文本计数 | 无障碍 |
| `example3_device.py` | **设备/应用**：启动应用、设备信息、屏幕/电量、亮度、内存、应用列表 | — |
| `example4_color_sensor.py` | **找色/传感器**：find_color、find_multi_colors、坐标取色、传感器 | 无障碍（截屏用） |
| `example5_notification_code.py` | **验证码自动化**：通知监听 → 读验证码 → 回填 | 无障碍 + 通知使用权 |
| `example6_vision.py` | **视觉**：opencv 找图 + OCR（跑在电脑端） | 无障碍（截屏用）+ 装 opencv/OCR 依赖 |
| `example7_file_media.py` | **文件/媒体**：文件读写复制移动、媒体位置/seek/音量 | — |

## 按需安装依赖

```bash
pip install requests            # 所有示例都需要的底层依赖
pip install opencv-python numpy # example6 找图
pip install paddleocr           # example6 OCR（中文推荐）
# 或 pip install pytesseract    # example6 OCR（轻量，需系统装 tesseract）
```

## 完整 API

- `bridge_rpc/client.py` —— Bridge 类全部方法（70+ 个）。
- `bridge_rpc/vision.py` —— Vision 类（找图 + OCR）。
- 使用文档：`docs/guides/automation.md`。
