"""Bridge RPC Python SDK —— 用 Python 脚本驱动 Bridge 手机自动化。"""

from .client import Bridge, RpcError
from .selector import Selector, to_selector
from .vision import Vision

__version__ = "0.2.0"

__all__ = ["Bridge", "RpcError", "Selector", "to_selector", "Vision", "__version__"]
