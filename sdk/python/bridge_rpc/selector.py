"""
选择器 DSL（Python 版）：链式构建 autox 风格选择器表达式。

用法：
    from bridge_rpc import Selector

    Selector.text("登录")                    # text("登录")
    Selector.text_contains("确定").clickable()  # textContains("确定").clickable()
    Selector.id("com.xx:id/btn")
    Selector.class_name("Button")

也可以直接传字符串（如 "text('登录').clickable()"），to_selector 会原样透传。
"""

from __future__ import annotations

from typing import Any


class Selector:
    """链式选择器，最终序列化为 autox 风格表达式字符串。"""

    def __init__(self, *parts: str):
        self._parts = list(parts)

    def _add(self, name: str, arg: Any = None) -> "Selector":
        if arg is None:
            self._parts.append(f"{name}()")
        elif isinstance(arg, bool):
            self._parts.append(f"{name}({'true' if arg else 'false'})")
        elif isinstance(arg, (int, float)):
            self._parts.append(f"{name}({arg})")
        else:
            self._parts.append(f"{name}({_q(str(arg))})")
        return self

    # 文本
    def text(self, s: str) -> "Selector": return self._add("text", s)
    def text_contains(self, s: str) -> "Selector": return self._add("textContains", s)
    def text_starts_with(self, s: str) -> "Selector": return self._add("textStartsWith", s)
    def text_ends_with(self, s: str) -> "Selector": return self._add("textEndsWith", s)
    def text_matches(self, s: str) -> "Selector": return self._add("textMatches", s)

    # id / desc / className / packageName
    def id(self, s: str) -> "Selector": return self._add("id", s)
    def id_contains(self, s: str) -> "Selector": return self._add("idContains", s)
    def desc(self, s: str) -> "Selector": return self._add("desc", s)
    def desc_contains(self, s: str) -> "Selector": return self._add("descContains", s)
    def class_name(self, s: str) -> "Selector": return self._add("className", s)
    def class_name_contains(self, s: str) -> "Selector": return self._add("classNameContains", s)
    def package_name(self, s: str) -> "Selector": return self._add("packageName", s)

    # 布尔状态（无参=真）
    def clickable(self, b: bool = True) -> "Selector": return self._add("clickable", b)
    def enabled(self, b: bool = True) -> "Selector": return self._add("enabled", b)
    def scrollable(self, b: bool = True) -> "Selector": return self._add("scrollable", b)
    def editable(self, b: bool = True) -> "Selector": return self._add("editable", b)
    def long_clickable(self, b: bool = True) -> "Selector": return self._add("longClickable", b)
    def checkable(self, b: bool = True) -> "Selector": return self._add("checkable", b)
    def checked(self, b: bool = True) -> "Selector": return self._add("checked", b)
    def focusable(self, b: bool = True) -> "Selector": return self._add("focusable", b)
    def focused(self, b: bool = True) -> "Selector": return self._add("focused", b)
    def selected(self, b: bool = True) -> "Selector": return self._add("selected", b)
    def visible_to_user(self, b: bool = True) -> "Selector": return self._add("visibleToUser", b)
    def password(self, b: bool = True) -> "Selector": return self._add("password", b)

    # 深度
    def depth(self, d: int) -> "Selector": return self._add("depth", d)

    def __str__(self) -> str:
        return ".".join(self._parts)

    def __repr__(self) -> str:
        return f"Selector({str(self)!r})"


def _q(s: str) -> str:
    """转义单引号（表达式用双引号包裹时需转义内部双引号）。"""
    return s.replace("\\", "\\\\").replace('"', '\\"')


def to_selector(obj: Any) -> str:
    """把 Selector 对象或字符串统一为选择器表达式字符串。"""
    if isinstance(obj, Selector):
        return str(obj)
    return str(obj)


__all__ = ["Selector", "to_selector"]
