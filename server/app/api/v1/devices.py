"""设备相关 REST 接口：设备注册、设备列表、设备统计。

这些端点是给「业务侧/管理侧」调用的 HTTP 接口，与 WebSocket 长连接互补。
依赖注入：单例 registry / manager 来自 ``request.app.state``。

多客户端对应关系（关键）：
- ``device_id`` 是全局唯一键，所有「精确到设备」的操作（推送/命令/日志/踢下线）都以它定位。
- 昵称 ``device_name`` 仅作展示名，便于管理侧在设备列表中「认出」设备再下发命令，不参与唯一性判断。
"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, Request, status
from pydantic import BaseModel, Field

from app.api.v1.admin import require_admin
from app.core import adb_helper
from app.core.adb_helper import AdbError
from app.core.connection_manager import ConnectionManager
from app.core.device_registry import DeviceRegistry
from app.schemas.ws import (
    DeviceListItem,
    DeviceRegisterRequest,
    DeviceRegisterResponse,
)

router = APIRouter(prefix="/devices", tags=["devices"])


def _get_registry(request: Request) -> DeviceRegistry:
    return request.app.state.device_registry


def _get_manager(request: Request) -> ConnectionManager:
    return request.app.state.connection_manager


@router.post("/register", response_model=DeviceRegisterResponse, status_code=status.HTTP_201_CREATED)
async def register_device(body: DeviceRegisterRequest, request: Request) -> DeviceRegisterResponse:
    """注册设备，返回 device_id + token（客户端保存 token 用于后续连接鉴权）。"""
    registry = _get_registry(request)
    device = registry.register(body.device_id, device_name=body.device_name)
    return DeviceRegisterResponse(device_id=device.device_id, token=device.token)


@router.get("", response_model=list[DeviceListItem], dependencies=[Depends(require_admin)])
async def list_devices(request: Request) -> list[DeviceListItem]:
    """列出所有已注册设备（含昵称 + 在线状态），供管理侧挑设备下发命令/推送。"""
    registry = _get_registry(request)
    manager = _get_manager(request)
    result = []
    for device in registry.list_devices():
        result.append(
            DeviceListItem(
                device_id=device.device_id,
                device_name=device.device_name,
                online=manager.get(device.device_id) is not None,
                created_at=device.created_at,
            )
        )
    return result


@router.get("/{device_id}/music-state", dependencies=[Depends(require_admin)])
async def get_music_state(device_id: str, request: Request) -> dict:
    """查询设备最新音乐播放状态（管理侧，供命令下发页展示与按钮禁用）。

    返回 {device_id, state, source, updated_at}；state ∈ playing/paused/idle/unknown。
    unknown = 设备从未上报或当前离线（断连时 ws 层已清除记录）。
    """
    store = request.app.state.music_state_store
    record = store.get(device_id)
    return {"device_id": device_id, **record}


@router.get("/count", dependencies=[Depends(require_admin)])
async def device_count(request: Request) -> dict:
    """已注册设备总数。"""
    registry = _get_registry(request)
    return {"registered_devices": registry.count()}


class AdbDirectRequest(BaseModel):
    """直连模式可选参数：手工指定手机 IP（默认从 WS 连接自动获取）。"""

    ip: Optional[str] = Field(None, description="手机局域网 IP（留空自动识别）")


@router.get("/{device_id}/net-info", dependencies=[Depends(require_admin)])
async def device_net_info(device_id: str, request: Request) -> dict:
    """查询设备的网络信息（供 ADB 弹窗展示，用户交叉确认操作的是哪台手机）。

    返回 {device_ips: [...], adb_devices: [{serial, ip?, conn_type}]}：
    - device_ips：该设备 hello 上报的本机全部 IPv4（WiFi 优先）+ WS 连接 IP；
    - adb_devices：服务端 adb 当前连接的每台手机及其 IP 与连接类型
      （conn_type ∈ usb / wifi / wifi_mdns，供后台明确展示连接方式）。
    两者有交集（或只连了一台 adb 设备）即可放心执行；adb 环境异常时 adb_devices 为空表。
    """
    manager = _get_manager(request)
    conn = manager.get(device_id)
    device_ips: list[str] = []
    if conn is not None:
        device_ips.extend(conn.ips)
        if conn.ip and conn.ip not in device_ips:
            device_ips.append(conn.ip)
    adb_devices: list[dict] = []
    try:
        adb_path = adb_helper.resolve_adb_path(request.app.state.settings.adb.path)
        adb_devices = adb_helper.list_devices_with_ips(adb_path)
    except AdbError:
        pass  # adb 未装/无设备：前端只展示设备 IP，不给 adb 对照
    return {"device_ips": device_ips, "adb_devices": adb_devices}


@router.post("/{device_id}/adb-enable-accessibility", dependencies=[Depends(require_admin)])
async def adb_enable_accessibility(
    device_id: str, request: Request, body: Optional[AdbDirectRequest] = None
) -> dict:
    """ADB 一键开启无障碍（借鉴 autoxClient 的 WRITE_SECURE_SETTINGS 方案）。

    前提：该手机已用数据线连到运行服务端的电脑（允许过 USB 调试），
    或已建立 WiFi adb 连接。服务端经 adb 一次性授予 WRITE_SECURE_SETTINGS，
    再直接写系统设置自动启用「Bridge 辅助服务」——设备端零操作，
    授权永久有效（重启不掉，卸载重装才需重做）。

    设备定位：单台 adb 设备直接采用（IP 匹配不可靠——手机连服务端可能走公网，
    WS 记录的 IP 与局域网 IP 不一致）；多台时用设备上报的**全部 IP**（hello.ips）
    逐一比对，任一命中即确认，可用 body.ip 手工指定兜底。

    返回 {success, serial?, device_ip(s), adb_devices?, steps?/error?}；
    device_ips 为该设备上报的全部 IP（供用户交叉确认）；失败时附带 adb_devices
    （已连 adb 设备及其 IP），HTTP 仍 200 便于后台弹窗展示。
    """
    manager = _get_manager(request)
    conn = manager.get(device_id)
    candidates: list[str] = []
    if body and body.ip and body.ip.strip():
        candidates.append(body.ip.strip())
    if conn is not None:
        candidates.extend(conn.ips)          # 客户端上报的全部 IP（wlan 优先）
        if conn.ip:
            candidates.append(conn.ip)       # WS 连接 IP 兜底
    # 去重保序
    seen: set[str] = set()
    candidates = [c for c in candidates if not (c in seen or seen.add(c))]

    adb_path = adb_helper.resolve_adb_path(request.app.state.settings.adb.path)

    def _adb_devices_safe() -> list[dict]:
        try:
            return adb_helper.list_devices_with_ips(adb_path)
        except AdbError:
            return []

    if not candidates:
        return {
            "success": False,
            "device_ips": [],
            "adb_devices": _adb_devices_safe(),
            "error": "无法确定手机 IP（设备不在线且未手工填写），请在弹窗中填写手机 IP",
        }
    try:
        serial = adb_helper.find_serial_for_ips(adb_path, candidates)
    except AdbError as e:
        return {
            "success": False,
            "device_ips": candidates,
            "adb_devices": _adb_devices_safe(),
            "error": str(e),
        }
    steps = adb_helper.enable_accessibility(adb_path, serial)
    return {
        "success": bool(steps and steps[-1]["ok"]),  # 以最终回读验证为准（grant 失败不阻断）
        "serial": serial,
        "device_ips": candidates,
        "steps": steps,
    }


class AdbWifiSetupRequest(BaseModel):
    """WiFi adb 一条龙请求：可选配对（首次）+ 连接端口（无线调试主界面显示）。"""

    connect_port: int = Field(..., ge=1024, le=65535, description="无线调试主界面显示的端口")
    pair_port: Optional[int] = Field(None, ge=1024, le=65535, description="配对端口（未配对过时填）")
    pair_code: Optional[str] = Field(None, description="6 位配对码")
    ip: Optional[str] = Field(None, description="手机局域网 IP（默认从 WS 连接自动获取；"
                                                "WS 走公网时自动 IP 不可用，需手填局域网 IP）")


@router.post(
    "/{device_id}/adb-wifi-enable-accessibility", dependencies=[Depends(require_admin)]
)
async def adb_wifi_enable_accessibility(
    device_id: str, body: AdbWifiSetupRequest, request: Request
) -> dict:
    """WiFi adb 一条龙：配对（可选）→ connect → 一次性授权 → 自动开启无障碍。

    用户只需在手机上开「无线调试」（首次配对时再点「使用配对码配对设备」），
    在后台弹窗填入端口/配对码（IP 可手填），其余全自动。授权永久有效。
    注意：无线调试要求手机与电脑**同一局域网**——若 WS 连接走公网（自动 IP 是
    公网地址），必须手工填写手机的局域网 IP。
    """
    manager = _get_manager(request)
    conn = manager.get(device_id)
    # 候选 IP：手填优先，其次设备上报的全部 IP（wlan 优先），最后 WS 连接 IP
    candidates: list[str] = []
    if body.ip and body.ip.strip():
        candidates.append(body.ip.strip())
    if conn is not None:
        candidates.extend(conn.ips)
        if conn.ip:
            candidates.append(conn.ip)
    seen: set[str] = set()
    candidates = [c for c in candidates if not (c in seen or seen.add(c))]
    if not candidates:
        return {"success": False, "error": "无法确定手机 IP（设备不在线且未手工填写），"
                                           "请在弹窗中填写手机局域网 IP"}

    adb_path = adb_helper.resolve_adb_path(request.app.state.settings.adb.path)
    steps: list[dict] = []

    # ① 可选配对（首次使用无线调试时）：对每个候选 IP 尝试，任一成功即停
    if body.pair_port and body.pair_code:
        pair_ok = False
        last_pair_err = ""
        for ip in candidates:
            try:
                adb_helper.pair_device(adb_path, f"{ip}:{body.pair_port}", body.pair_code.strip())
                steps.append({"step": "无线调试配对", "ok": True, "detail": f"{ip}:{body.pair_port}"})
                pair_ok = True
                break
            except AdbError as e:
                last_pair_err = str(e)
        if not pair_ok:
            steps.append({"step": "无线调试配对", "ok": False, "detail": last_pair_err})
            return {"success": False, "device_ips": candidates, "steps": steps}

    # ② 连接：对每个候选 IP 尝试 connect，任一成功即确认设备（不再要求手工填 IP）
    connected_ip = ""
    last_connect_err = ""
    for ip in candidates:
        address = f"{ip}:{body.connect_port}"
        try:
            detail = adb_helper.connect_device(adb_path, address)
            steps.append({"step": "建立 WiFi adb 连接", "ok": True, "detail": detail})
            connected_ip = ip
            break
        except AdbError as e:
            last_connect_err = str(e)
    if not connected_ip:
        steps.append({"step": "建立 WiFi adb 连接", "ok": False, "detail": last_connect_err})
        return {"success": False, "device_ips": candidates, "steps": steps}

    # ③ 授权 + 开启无障碍（复用既有逐步骤实现）
    try:
        serial = adb_helper.find_serial_for_ips(adb_path, [connected_ip])
    except AdbError as e:
        return {"success": False, "device_ips": candidates, "steps": steps, "error": str(e)}
    steps.extend(adb_helper.enable_accessibility(adb_path, serial))
    return {
        "success": bool(steps and steps[-1]["ok"]),  # 以最终回读验证为准（grant 失败不阻断）
        "serial": serial,
        "device_ips": candidates,
        "steps": steps,
    }
