"""管理后台鉴权核心层（简单管理员密钥方案）。

职责：
- 校验管理员账号密码，签发 admin token。
- 校验请求携带的 admin token 是否有效且未过期。
- 与设备鉴权（DeviceRegistry）解耦。

token 持久化：内存 dict 为一级缓存，可选的 ``AdminTokenStore``（MySQL）
为持久层——签发时写穿透（write-through），未命中时读穿透（read-through）。
服务重启后已签发 token 依旧有效，避免管理后台 403 重连刷屏。
MySQL 不可达时自动降级为纯内存模式（登录/校验照常，重启后 token 失效）。

安全要点：
- 密码用 ``secrets.compare_digest`` 常量时间比较，避免时序攻击。
- token 用 ``secrets.token_hex`` 生成，足够随机不可猜测。
- 可选 token 有效期（config.yaml 的 admin.token_ttl，0 = 永久）。
"""

from __future__ import annotations

import logging
import secrets
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.admin_token_store import AdminTokenStore

logger = logging.getLogger(__name__)


class AdminAuth:
    """管理员鉴权（内存缓存 + 可选 DB 持久化）。"""

    def __init__(
        self,
        username: str,
        password: str,
        token_ttl: int = 0,
        store: "AdminTokenStore | None" = None,
    ) -> None:
        self._username = username
        self._password = password
        self._token_ttl = token_ttl
        # 一级缓存：token -> 签发时间戳
        self._tokens: dict[str, float] = {}
        # 二级持久层（可为 None = 纯内存模式）
        self._store = store

    def load_persisted(self) -> None:
        """启动时从持久层预热内存缓存，并清理已过期行（TTL > 0 时）。"""
        if self._store is None:
            return
        try:
            rows = self._store.load_all()
        except Exception:
            logger.warning("管理员 token 持久层不可达，本次启动降级为纯内存模式", exc_info=True)
            return
        now = time.time()
        expired: list[str] = []
        for token, issued_at in rows.items():
            if self._token_ttl > 0 and now - issued_at > self._token_ttl:
                expired.append(token)
                continue
            self._tokens[token] = issued_at
        for token in expired:
            try:
                self._store.delete(token)
            except Exception:
                pass  # 过期行清不掉无碍，下次启动再清
        logger.info(
            "管理员 token 预热完成：内存 %d 条%s",
            len(self._tokens),
            f"，清理过期 {len(expired)} 条" if expired else "",
        )

    def login(self, username: str, password: str) -> str | None:
        """校验账号密码，通过则签发新 token，失败返回 None。"""
        username_ok = secrets.compare_digest(self._username, username)
        password_ok = secrets.compare_digest(self._password, password)
        if not (username_ok and password_ok):
            logger.warning("管理员登录失败（账号或密码错误）")
            return None
        token = secrets.token_hex(32)
        issued_at = time.time()
        self._tokens[token] = issued_at
        if self._store is not None:
            try:
                self._store.save(token, issued_at)  # 写穿透
            except Exception:
                # 持久层故障不阻断登录：本次 token 仍在内存有效（重启后失效）
                logger.warning("管理员 token 持久化失败（本次仅内存有效）", exc_info=True)
        logger.info("管理员登录成功，签发 token")
        return token

    def _expired(self, issued_at: float) -> bool:
        return self._token_ttl > 0 and time.time() - issued_at > self._token_ttl

    def verify(self, token: str | None) -> bool:
        """校验 admin token 是否有效且未过期（内存未命中时读持久层）。"""
        if not token:
            return False
        issued_at = self._tokens.get(token)
        if issued_at is None and self._store is not None:
            try:
                issued_at = self._store.get(token)  # 读穿透
            except Exception:
                logger.warning("管理员 token 持久层查询失败，按未命中处理", exc_info=True)
                return False
            if issued_at is not None:
                self._tokens[token] = issued_at  # 回填内存缓存
        if issued_at is None:
            return False
        if self._expired(issued_at):
            self._tokens.pop(token, None)
            if self._store is not None:
                try:
                    self._store.delete(token)
                except Exception:
                    pass  # 过期行清不掉无碍
            return False
        return True

    def logout(self, token: str | None) -> None:
        """注销 token（可选）。"""
        if token:
            self._tokens.pop(token, None)
            if self._store is not None:
                try:
                    self._store.delete(token)
                except Exception:
                    pass  # 持久层故障不影响内存注销
