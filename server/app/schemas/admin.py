"""管理后台相关的 Pydantic 校验模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class AdminLoginRequest(BaseModel):
    """管理员登录请求体。"""

    username: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=1, max_length=128)


class AdminLoginResponse(BaseModel):
    """管理员登录响应体。"""

    token: str
    token_type: str = Field(default="bearer")
    expires_in: int = Field(default=0, description="token 有效期（秒），0 表示永久")
