"""管理后台鉴权核心层（简单管理员密钥方案）。

职责：
- 校验管理员账号密码，签发 admin token。
- 校验请求携带的 admin token 是否有效且未过期。
- 与设备鉴权（DeviceRegistry）解耦，独立内存实现，接口可替换。

安全要点：
- 密码用 ``secrets.compare_digest`` 常量时间比较，避免时序攻击。
- token 用 ``secrets.token_hex`` 生成，足够随机不可猜测。
- 可选 token 有效期（config.yaml 的 admin.token_ttl，0 = 永久）。
"""

from __future__ import annotations

import logging
import secrets
import time

logger = logging.getLogger(__name__)


class AdminAuth:
    """管理员鉴权（内存实现）。"""

    def __init__(self, username: str, password: str, token_ttl: int = 0) -> None:
        self._username = username
        self._password = password
        self._token_ttl = token_ttl
        # 当前签发的 admin token 集合：token -> 签发时间戳
        self._tokens: dict[str, float] = {}

    def login(self, username: str, password: str) -> str | None:
        """校验账号密码，通过则签发新 token，失败返回 None。"""
        username_ok = secrets.compare_digest(self._username, username)
        password_ok = secrets.compare_digest(self._password, password)
        if not (username_ok and password_ok):
            logger.warning("管理员登录失败（账号或密码错误）")
            return None
        token = secrets.token_hex(32)
        self._tokens[token] = time.time()
        logger.info("管理员登录成功，签发 token")
        return token

    def verify(self, token: str | None) -> bool:
        """校验 admin token 是否有效且未过期。"""
        if not token:
            return False
        issued_at = self._tokens.get(token)
        if issued_at is None:
            return False
        if self._token_ttl > 0:
            if time.time() - issued_at > self._token_ttl:
                self._tokens.pop(token, None)
                return False
        return True

    def logout(self, token: str | None) -> None:
        """注销 token（可选）。"""
        if token:
            self._tokens.pop(token, None)
