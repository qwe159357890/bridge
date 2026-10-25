"""配置加载模块。

职责：从 YAML 配置文件 + 环境变量加载配置，经 Pydantic 校验后提供全局只读配置对象。

加载优先级（从低到高）：
1. 代码内置默认值（本模块内的 fallback）
2. ``config.yaml``（仓库内默认配置）
3. ``config.local.yaml``（本地敏感配置，gitignore，存在才加载，不存在跳过）
4. 环境变量 ``BRIDGE_CONFIG`` 指定的额外 YAML 文件（如生产配置）
5. 环境变量（``BRIDGE_*`` 前缀，覆盖单个参数，用于敏感项）

校验失败（fail-fast）时抛异常，进程启动即报错，绝不静默使用错误配置。
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Optional

import yaml
from pydantic import BaseModel, Field, field_validator

# 默认配置文件路径（相对本文件，即 server/app/config.py -> server/config.yaml）
DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.yaml"

# 本地敏感配置（与 config.yaml 同目录；gitignore，存在才加载）
LOCAL_CONFIG_PATH = DEFAULT_CONFIG_PATH.parent / "config.local.yaml"


class ServerConfig(BaseModel):
    """HTTP/WS 服务监听相关配置。"""

    host: str = "0.0.0.0"
    port: int = Field(default=15160, ge=1, le=65535)
    max_message_size: int = Field(default=65536, gt=0)


class HeartbeatConfig(BaseModel):
    """心跳相关参数。"""

    interval: float = Field(default=20, gt=0, description="客户端心跳发送周期（秒）")
    client_pong_timeout: float = Field(default=10, gt=0, description="客户端 ping 后等 pong 超时（秒）")
    server_read_timeout: float = Field(default=45, gt=0, description="服务端读超时（秒）")

    @field_validator("server_read_timeout")
    @classmethod
    def _read_timeout_gt_interval(cls, v: float, info) -> float:
        interval = info.data.get("interval")
        if interval is not None and v <= interval:
            raise ValueError("server_read_timeout 必须大于 heartbeat.interval（建议 > 2 倍）")
        return v


class ReconnectConfig(BaseModel):
    """客户端重连退避参数（服务端记录并下发，客户端据此计算退避）。"""

    base_delay: float = Field(default=1, gt=0)
    max_delay: float = Field(default=60, gt=0)
    jitter: float = Field(default=0.2, ge=0, le=1)

    @field_validator("max_delay")
    @classmethod
    def _max_ge_base(cls, v: float, info) -> float:
        base = info.data.get("base_delay")
        if base is not None and v < base:
            raise ValueError("reconnect.max_delay 必须 >= reconnect.base_delay")
        return v


class ConnectionConfig(BaseModel):
    """连接管理策略。"""

    unique_per_device: bool = Field(default=True, description="同一 device_id 仅保留一条活跃连接")
    max_connections: int = Field(default=10000, ge=0, description="全局最大并发连接数，0 表示不限")


class AuthConfig(BaseModel):
    """设备鉴权配置。"""

    enabled: bool = Field(default=False, description="是否启用鉴权（false 时接受任意连接，便于联调）")
    token_ttl: int = Field(default=0, ge=0, description="token 有效期（秒），0 表示永久")


class AdminConfig(BaseModel):
    """管理后台鉴权配置（简单管理员密钥方案）。"""

    username: str = Field(default="admin", min_length=1, description="管理后台登录账号")
    password: str = Field(default="admin123", min_length=1, description="管理后台登录密码")
    token_ttl: int = Field(default=86400, ge=0, description="管理员 token 有效期（秒），0 表示永久")


class MusicConfig(BaseModel):
    """音乐共享目录配置（play_music 命令的「裸文件名」音源）。"""

    dir: str = Field(
        default="data/music",
        min_length=1,
        description="音乐共享目录，相对路径相对 server 根目录（绝对路径原样使用）",
    )


class MySQLConfig(BaseModel):
    """MySQL 连接配置（位置持久化存储用）。"""

    host: str = Field(default="127.0.0.1", description="MySQL 主机")
    port: int = Field(default=3306, ge=1, le=65535)
    user: str = Field(default="root", min_length=1)
    password: str = Field(default="", description="密码（敏感，建议放 config.local.yaml）")
    database: str = Field(default="bridge", min_length=1, description="库名，不存在自动创建")


class AmapConfig(BaseModel):
    """高德地图 JS API 配置（管理后台位置轨迹渲染用）。"""

    key: str = Field(default="", description="高德 Web端(JS API) Key，敏感建议放 config.local.yaml")
    security_js_code: str = Field(default="", description="配套安全密钥（2021-12 后申请的 Key 必填）")


class LocationsConfig(BaseModel):
    """位置持久化（get_location 上报存档）+ 地图渲染配置。"""

    mysql: MySQLConfig = MySQLConfig()
    amap: AmapConfig = AmapConfig()


class LoggingConfig(BaseModel):
    """日志配置。"""

    level: str = Field(default="INFO", pattern="^(DEBUG|INFO|WARNING|ERROR|CRITICAL)$")


class Settings(BaseModel):
    """顶层配置聚合对象。"""

    server: ServerConfig = ServerConfig()
    heartbeat: HeartbeatConfig = HeartbeatConfig()
    reconnect: ReconnectConfig = ReconnectConfig()
    connection: ConnectionConfig = ConnectionConfig()
    auth: AuthConfig = AuthConfig()
    admin: AdminConfig = AdminConfig()
    logging: LoggingConfig = LoggingConfig()
    music: MusicConfig = MusicConfig()
    locations: LocationsConfig = LocationsConfig()


def _env_overrides() -> dict:
    """收集 ``BRIDGE_*`` 环境变量，映射为嵌套 dict 覆盖配置。

    支持点号转义：``BRIDGE_HEARTBEAT_INTERVAL`` -> ``heartbeat.interval``。
    仅覆盖数值/字符串叶子项，不做深层类型转换（Pydantic 会负责校验）。
    """
    overrides: dict = {}
    for key, value in os.environ.items():
        if not key.startswith("BRIDGE_"):
            continue
        # BRIDGE_HEARTBEAT_INTERVAL -> heartbeat.interval
        path = key[len("BRIDGE_"):].lower().split("_")
        node = overrides
        for part in path[:-1]:
            node = node.setdefault(part, {})
        node[path[-1]] = value
    return overrides


def _deep_merge(base: dict, override: dict) -> dict:
    """递归合并两个 dict，override 覆盖 base。"""
    result = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def _load_yaml(path: Path) -> dict:
    """加载 YAML 文件，文件不存在返回空 dict。"""
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    if not isinstance(data, dict):
        raise ValueError(f"配置文件 {path} 顶层必须是映射（map），实际为 {type(data).__name__}")
    return data


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """加载并缓存配置（进程内单例，只加载一次）。"""
    config: dict = {}

    # 1. 默认 config.yaml
    config = _deep_merge(config, _load_yaml(DEFAULT_CONFIG_PATH))

    # 2. 本地敏感配置 config.local.yaml（gitignore；不存在跳过，不报错）
    config = _deep_merge(config, _load_yaml(LOCAL_CONFIG_PATH))

    # 3. BRIDGE_CONFIG 指定的额外文件
    extra_path = os.environ.get("BRIDGE_CONFIG")
    if extra_path:
        config = _deep_merge(config, _load_yaml(Path(extra_path)))

    # 4. 环境变量覆盖
    config = _deep_merge(config, _env_overrides())

    return Settings(**config)
