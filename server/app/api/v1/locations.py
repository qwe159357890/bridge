"""位置上报/查询 REST 接口（get_location 持久化）。

通道设计：
- ``POST /api/locations``：设备上报（设备头鉴权，与 /api/music 同一套）。
  客户端在命令回执之外 fire-and-forget 调用，本接口的成败**不影响回执**。
- ``GET /api/locations`` / ``GET /api/locations/latest``：管理侧查询（管理员鉴权）。
- ``DELETE /api/locations``：手动清理（无自动保留策略，删不删全由管理员决定；
  必须带 id / device_id / before 至少一个条件，防误删全表）。
- ``GET /api/locations/amap-config``：管理侧取高德 Key（配置在服务端 yaml，
  前端不落盘密钥）。

时间参数（start/end/before）均为 epoch 毫秒。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.api.v1.admin import require_admin
from app.config import get_settings
from app.core.location_store import LocationStore

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/locations", tags=["locations"])


# ----------------------------------------------------------------------
# 请求/响应模型
# ----------------------------------------------------------------------


class LocationReportRequest(BaseModel):
    """设备端一次定位上报（对应 LocationHelper.LocationFix）。"""

    device_id: str = Field(min_length=1)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy: float = Field(default=0, ge=0, description="定位误差（米）")
    provider: str = Field(default="", max_length=64, description="来源：gps/network(缓存)/…")
    altitude: float | None = None
    speed: float | None = None
    fix_time_ms: int = Field(default=0, ge=0, description="设备端定位时刻 epoch 毫秒，0=以服务端时间为准")


class LocationReportResponse(BaseModel):
    action: str = Field(description="merged（静止合并）/ inserted（新建行）")
    id: int
    distance_m: float | None = None
    report_count: int


class LocationListResponse(BaseModel):
    total: int
    locations: list[dict]


class LocationDeleteResponse(BaseModel):
    deleted: int


class AmapConfigResponse(BaseModel):
    key: str
    security_js_code: str


class ReverseGeocodeResponse(BaseModel):
    address: str
    cached: bool = False


# ----------------------------------------------------------------------
# 依赖
# ----------------------------------------------------------------------


def _get_store(request: Request) -> LocationStore:
    return request.app.state.location_store


def _check_device_auth(request: Request, device_id: str | None, token: str | None) -> None:
    """设备鉴权（auth.enabled 时生效，与 WS hello 同一套注册表校验）。"""
    settings = request.app.state.settings
    if not settings.auth.enabled:
        return
    registry = request.app.state.device_registry
    if not device_id or not token or not registry.authenticate(device_id, token):
        logger.warning("位置上报鉴权失败：device=%s", device_id or "unknown")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="设备鉴权失败")


def _store_unavailable(e: Exception) -> HTTPException:
    logger.error("位置存储不可用：%s", e)
    return HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="位置存储暂不可用")


# ----------------------------------------------------------------------
# 设备上报
# ----------------------------------------------------------------------


@router.post("", response_model=LocationReportResponse, status_code=status.HTTP_200_OK)
def report_location(
    body: LocationReportRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> LocationReportResponse:
    """设备上报一次定位（device_id 以头为准，与鉴权设备一致）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    from app.core.location_store import LocationRecord

    store = _get_store(request)
    record = LocationRecord(
        device_id=x_device_id or body.device_id,
        latitude=body.latitude,
        longitude=body.longitude,
        accuracy=body.accuracy,
        provider=body.provider,
        altitude=body.altitude,
        speed=body.speed,
        fix_time_ms=body.fix_time_ms,
    )
    try:
        result = store.report(record)
    except Exception as e:  # MySQL 连接/执行异常统一 503
        raise _store_unavailable(e) from e
    return LocationReportResponse(**result)


# ----------------------------------------------------------------------
# 管理侧查询 / 清理 / 地图配置
# ----------------------------------------------------------------------


@router.get("", response_model=LocationListResponse, dependencies=[Depends(require_admin)])
def list_locations(
    request: Request,
    device_id: str | None = Query(default=None),
    start_ms: int | None = Query(default=None, description="起始时刻 epoch 毫秒"),
    end_ms: int | None = Query(default=None, description="截止时刻 epoch 毫秒"),
    limit: int = Query(default=200, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> LocationListResponse:
    """位置历史列表（last_fix_time 倒序）。"""
    store = _get_store(request)
    try:
        rows, total = store.list(
            device_id=device_id, start_ms=start_ms, end_ms=end_ms,
            limit=limit, offset=offset,
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return LocationListResponse(total=total, locations=rows)


@router.get("/latest", response_model=dict, dependencies=[Depends(require_admin)])
def latest_location(request: Request, device_id: str = Query(min_length=1)) -> dict:
    """某设备最新一条位置（无记录返回 {location: null}）。"""
    store = _get_store(request)
    try:
        row = store.latest(device_id)
    except Exception as e:
        raise _store_unavailable(e) from e
    return {"location": row}


@router.delete("", response_model=LocationDeleteResponse, dependencies=[Depends(require_admin)])
def delete_locations(
    request: Request,
    id: int | None = Query(default=None, description="删除单条"),
    device_id: str | None = Query(default=None, description="删除某设备全部"),
    before_ms: int | None = Query(default=None, description="删除 last_fix_time 早于该时刻的行"),
) -> LocationDeleteResponse:
    """手动清理位置记录（至少一个条件）。"""
    if id is None and not device_id and before_ms is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="需至少指定 id / device_id / before_ms 之一")
    store = _get_store(request)
    try:
        deleted = store.delete(row_id=id, device_id=device_id, before_ms=before_ms)
    except ValueError as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=str(e)) from e
    except Exception as e:
        raise _store_unavailable(e) from e
    return LocationDeleteResponse(deleted=deleted)


@router.get("/amap-config", response_model=AmapConfigResponse, dependencies=[Depends(require_admin)])
def amap_config(request: Request) -> AmapConfigResponse:
    """高德 JS API 配置（Key 存服务端 yaml，前端动态获取，不进前端代码库）。"""
    cfg = get_settings().locations.amap
    if not cfg.key:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="未配置高德地图 Key（config.local.yaml 的 locations.amap.key）")
    return AmapConfigResponse(key=cfg.key, security_js_code=cfg.security_js_code)


# 逆地理编码结果缓存：坐标取 4 位小数（约 11m 网格）做 key，TTL 24 小时。
# 静止设备重复查询同一位置命中缓存，节省配额。
_geo_cache: dict[tuple[float, float], tuple[float, str]] = {}
_GEO_TTL_S = 24 * 3600


@router.get("/address", response_model=ReverseGeocodeResponse, dependencies=[Depends(require_admin)])
def reverse_geocode(
    latitude: float = Query(ge=-90, le=90),
    longitude: float = Query(ge=-180, le=180),
) -> ReverseGeocodeResponse:
    """逆地理编码：坐标 → 结构化地址（服务端调高德 Web服务 REST，稳于浏览器插件）。

    入参为记录里的 WGS-84 原始坐标，内部转 GCJ-02 后请求（高德底图坐标系）。
    web_key 未配置时返回空地址（前端显示「未配置」），不报错。
    """
    import json
    import time as _time
    import urllib.parse
    import urllib.request

    from app.core.location_store import wgs84_to_gcj02

    cfg = get_settings().locations.amap
    if not cfg.web_key:
        return ReverseGeocodeResponse(address="")

    gcj_lat, gcj_lng = wgs84_to_gcj02(latitude, longitude)
    cache_key = (round(gcj_lat, 4), round(gcj_lng, 4))
    now = _time.time()
    hit = _geo_cache.get(cache_key)
    if hit and now - hit[0] < _GEO_TTL_S:
        return ReverseGeocodeResponse(address=hit[1], cached=True)

    qs = urllib.parse.urlencode(
        {"key": cfg.web_key, "location": f"{cache_key[1]},{cache_key[0]}", "output": "json"}
    )
    try:
        with urllib.request.urlopen(
            f"https://restapi.amap.com/v3/geocode/regeo?{qs}", timeout=8
        ) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        logger.warning("逆地理编码请求失败：%s", e)
        return ReverseGeocodeResponse(address=f"解析失败（{e.__class__.__name__}）")

    if data.get("status") != "1":
        logger.warning("逆地理编码返回异常：%s", data.get("info"))
        return ReverseGeocodeResponse(address=f"解析失败（{data.get('info', '未知')}）")

    address = (data.get("regeocode") or {}).get("formatted_address") or ""
    if len(_geo_cache) > 500:  # 防膨胀：超限整体重建
        _geo_cache.clear()
    _geo_cache[cache_key] = (now, address)
    return ReverseGeocodeResponse(address=address)
