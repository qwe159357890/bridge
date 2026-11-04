"""应用更新检查 REST 接口（check_update 命令，服务器目录版，不依赖 GitHub）。

约定流程：
- 开发者把最新 APK 放进更新目录（``updates.dir``，默认 ``server/data/updates/``），
  文件名含版本号（如 ``bridge-v0.1.32.apk``），可选放 ``update.json`` 写更新说明：
  ``{"version": "0.1.32", "notes": "新增截屏、弹窗等命令"}``；
- 客户端 ``GET /api/updates/latest?current_version=0.1.31``：服务端扫描目录取
  版本最高的 APK，与客户端当前版本比对，返回是否可用更新；
- APK 下载走服务器自身：``GET /api/updates/{filename}``（不碰 GitHub，通道稳定）。

安全：仅放行扩展名 ``.apk`` 且单级纯文件名（拒绝路径穿越）。
"""

from __future__ import annotations

import html as _html
import json
import logging
import mimetypes
import re
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request, status
from fastapi.responses import FileResponse, HTMLResponse

from app.api.v1.music import _check_device_auth
from app.config import get_settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/updates", tags=["updates"])

# 「扫码下载」公共路由（无 /api/updates 前缀，直接挂在 api_router 下）：
# - GET /download        二维码落地页
# - GET /api/download/app  免鉴权下载最新 APK
public_router = APIRouter(tags=["download"])

# 版本号解析：取文件名里第一段形如 1.2.3 / v0.1.32 的数字串
_VERSION_RE = re.compile(r"v?(\d+(?:\.\d+)+)", re.IGNORECASE)


def _md5_of(path: Path) -> str:
    """现算文件 md5（用户拍板：每次请求现算，永远新鲜；18MB APK 约几十毫秒）。

    用途：客户端缓存命中判定 + 下载完成后的完整性校验，防「文件名相同但
    内容被换过」（如 0.1.43 改名冒充 0.1.44 导致客户端永远无法更新）。
    """
    import hashlib

    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _updates_dir() -> Path:
    """解析更新目录（相对路径相对 server 根目录）。"""
    path = Path(get_settings().updates.dir)
    if not path.is_absolute():
        path = Path(__file__).resolve().parent.parent.parent.parent / path
    return path


def _parse_version(text: str) -> tuple[int, ...] | None:
    """从文件名/版本串解析版本号元组（不可解析返回 None）。"""
    m = _VERSION_RE.search(text)
    if not m:
        return None
    try:
        return tuple(int(p) for p in m.group(1).split("."))
    except ValueError:
        return None


def _version_raw(text: str) -> str | None:
    """取文件名/版本串里的原始版本文本（保留前导零，如 0.2.01）。

    v0.2.01 教训：版本号一旦过 _parse_version 的 int 元组再拼接，前导零会被
    吃掉（0.2.01 → 0.2.1），与 APK 清单里的 versionName 对不上，触发客户端
    「改名冒充」防伪校验误伤，用户报障「服务器上面的 v0.2.1 实际是 v0.2.01」。
    """
    m = _VERSION_RE.search(text)
    return m.group(1) if m else None


def _scan_latest() -> dict | None:
    """扫描更新目录，取版本最高的 APK。返回最新文件信息或 None。"""
    directory = _updates_dir()
    if not directory.is_dir():
        return None

    candidates: list[tuple[tuple[int, ...], Path]] = []
    for f in directory.iterdir():
        if not f.is_file() or f.suffix.lower() != ".apk":
            continue
        version = _parse_version(f.stem)
        if version is None:
            logger.warning("更新目录里的 APK 文件名不含版本号，跳过：%s", f.name)
            continue
        candidates.append((version, f))

    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    version, path = candidates[-1]

    # 可选 update.json：{"version": "...", "notes": "..."}
    notes = ""
    meta_version = ""
    meta_path = directory / "update.json"
    if meta_path.is_file():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            notes = str(meta.get("notes", "")).strip()
            meta_version = str(meta.get("version", "")).strip()
        except Exception:
            logger.warning("update.json 解析失败（忽略）", exc_info=True)

    # 对外版本串：update.json 显式声明 > 文件名原文（都保留 0.2.01 的前导零），
    # 绝不用 int 元组回拼（会吃前导零 → 客户端防伪校验误伤，见 _version_raw 注释）
    version_str = meta_version or _version_raw(path.stem) or ".".join(str(p) for p in version)

    return {
        "version": version_str,
        "filename": path.name,
        "size_bytes": path.stat().st_size,
        "path": path,
        "notes": notes,
    }


def _validate_filename(filename: str) -> Path:
    """校验文件名：单级纯文件名 + .apk 扩展名。"""
    if (
        not filename
        or "/" in filename
        or "\\" in filename
        or ".." in filename
        or Path(filename).name != filename
    ):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="仅支持单级文件名")
    if Path(filename).suffix.lower() != ".apk":
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="仅支持 .apk 文件")
    return _updates_dir() / filename


@router.get("/latest", status_code=status.HTTP_200_OK)
async def latest_update(
    request: Request,
    current_version: str = "",
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> dict:
    """检查更新：目录最新版本 vs 客户端当前版本（比对在服务端完成）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    latest = _scan_latest()
    if latest is None:
        return {"found": False, "available": False, "current_version": current_version}

    current = _parse_version(current_version)
    latest_version = _parse_version(latest["version"])
    # 当前版本不可解析时保守视为「有更新」（客户端 versionName 异常的兜底）
    available = current is None or (latest_version is not None and current < latest_version)
    return {
        "found": True,
        "available": available,
        "current_version": current_version,
        "version": latest["version"],
        "filename": latest["filename"],
        "size_bytes": latest["size_bytes"],
        "md5": _md5_of(latest["path"]),
        "notes": latest["notes"],
    }


@router.get("/{filename}", status_code=status.HTTP_200_OK)
async def download_update(
    filename: str,
    request: Request,
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> FileResponse:
    """下载更新目录里的 APK（客户端/人工下载，服务器自身直链）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    target = _validate_filename(filename)
    if not target.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="服务器更新目录无此文件")
    logger.info("下发更新 APK %s（设备 %s）", filename, x_device_id or "anonymous")
    return FileResponse(
        path=target,
        filename=filename,
        media_type="application/vnd.android.package-archive",
    )


# ---------------------------------------------------------------------------
# 「扫码下载」：免鉴权下载最新 APK + 二维码落地页（v0.2.44 新增）
#
# 背景：现有 /api/updates/{filename} 走设备鉴权（X-Device-Id + X-Device-Token），
# 而「扫二维码下载」是浏览器匿名场景，没有设备 token，会 401。故单独开一条
# 免鉴权通道：GET /api/download/app 返回更新目录里版本最高的 APK。
#
# 落地页 GET /download 返回一个自包含 HTML：内嵌 qrcode.js（app/static/）在前端
# 生成二维码，二维码内容 = 免鉴权下载端点的绝对 URL。用户扫码/识别 → 浏览器下载
# APK → 通知栏「下载完成」→ 点一下进系统 PackageInstaller 安装。
#
# 安全取舍（用户拍板「完全免鉴权」）：任何拿到该 URL 的人都能下载最新 APK，
# 适合内网/受控环境；公开部署时请自行评估。
# ---------------------------------------------------------------------------

# APK 免鉴权下载短链：越短越好（用户扫码/手输），故用单字符 /d。
# 同时保留 /api/download/app 作为兼容别名（旧二维码/旧链接仍可用）。
DOWNLOAD_PATH = "/d"
DOWNLOAD_PATH_ALIAS = "/api/download/app"


def _public_base_url(request: Request) -> str:
    """落地页/二维码所用的对外地址（含协议+端口）。

    优先用配置 updates.public_base_url；留空则用请求的 Host 头推导（同 WiFi 扫码可用）。
    推导时协议取 X-Forwarded-Proto（反代后）或 URL scheme。
    """
    cfg = get_settings().updates.public_base_url.strip()
    if cfg:
        return cfg.rstrip("/")
    scheme = request.headers.get("x-forwarded-proto", "").split(",")[0].strip()
    if scheme not in ("http", "https"):
        scheme = request.url.scheme or "http"
    host = request.headers.get("x-forwarded-host") or request.headers.get("host") or "127.0.0.1:15160"
    return f"{scheme}://{host}"


def _human_size(n: int) -> str:
    """字节数 → 人类可读（B/KB/MB）。"""
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024
    return f"{n:.1f} GB"


@public_router.get(DOWNLOAD_PATH, status_code=status.HTTP_200_OK)
async def download_latest_apk() -> FileResponse:
    """免鉴权下载更新目录里版本最高的 APK（扫码下载短链）。"""
    latest = _scan_latest()
    if latest is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="服务器更新目录暂无 APK")
    filename = latest["filename"]
    logger.info("免鉴权下发最新 APK %s（短链下载）", filename)
    return FileResponse(
        path=latest["path"],
        filename=filename,
        media_type="application/vnd.android.package-archive",
    )


@public_router.get(DOWNLOAD_PATH_ALIAS, status_code=status.HTTP_200_OK)
async def download_latest_apk_alias() -> FileResponse:
    """/api/download/app 兼容别名（与 /d 等价，旧二维码/链接仍可用）。"""
    return await download_latest_apk()


@public_router.get("/api/download/info")
async def download_info(request: Request) -> dict:
    """返回扫码下载的对外地址（用 public_base_url 权威推导）。

    前端命令卡片据此生成二维码，避免前端自行拼 host（拿不到 public_base_url）。
    返回：{ apk_url: 短链直下地址, page_url: 二维码落地页地址 }。
    """
    base = _public_base_url(request)
    return {
        "apk_url": f"{base}{DOWNLOAD_PATH}",
        "page_url": f"{base}/download",
    }


@public_router.get("/download", response_class=HTMLResponse)
async def download_page(request: Request) -> HTMLResponse:
    """扫码下载落地页：展示二维码 + 版本号/大小/更新说明 + 兜底下载按钮。"""
    base = _public_base_url(request)
    latest = _scan_latest()

    if latest is None:
        html = """<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bridge 下载</title>
<body style="font-family:-apple-system,system-ui,sans-serif;max-width:560px;margin:0 auto;padding:24px;text-align:center;">
<h2>暂无可用安装包</h2>
<p style="color:#888;">服务器更新目录里还没有 APK，请先放入 <code>data/updates/bridge-vX.Y.Z.apk</code>。</p>
</body></html>"""
        return HTMLResponse(html)

    version = latest["version"]
    filename = latest["filename"]
    size = _human_size(latest["size_bytes"])
    notes = (latest["notes"] or "").strip()
    download_url = f"{base}{DOWNLOAD_PATH}"

    notes_html = ""
    if notes:
        safe_notes = _html.escape(notes).replace("\n", "<br>")
        notes_html = f'<div class="notes"><strong>更新说明</strong><br>{safe_notes}</div>'

    # JS 侧要的 URL 字符串（JSON 转义，杜绝引号/反斜杠注入）
    js_url = json.dumps(download_url, ensure_ascii=False)

    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bridge 客户端下载</title>
<script src="/static/qrcode.js"></script>
<style>
  * {{ box-sizing: border-box; }}
  body {{ font-family: -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
         background:#0f1115; margin:0; padding:0; color:#e6e8eb; }}
  /* 铺满窗口：大二维码居中占满可视区，其余信息收到下方一条窄栏。
     二维码尺寸用 min(80vmin, 100vw-32px, 100vh-180px) 硬性收敛在窗口内，
     SVG 用 viewBox 等比缩放（scalable），任何窗口比例都不会溢出。 */
  .stage {{ position:fixed; inset:0; display:flex; flex-direction:column; }}
  .qrcode-zone {{ flex:1; display:flex; align-items:center; justify-content:center;
                 padding:12px; min-height:0; overflow:hidden; }}
  .qrcode {{ background:#fff; border-radius:16px;
            box-shadow:0 8px 40px rgba(0,0,0,.5);
            width:min(80vmin, calc(100vw - 32px), calc(100vh - 180px));
            height:auto; aspect-ratio:1; padding:12px; box-sizing:border-box; }}
  .qrcode svg {{ display:block; width:100%; height:100%; }}
  .bar {{ flex-shrink:0; text-align:center; padding:14px 20px calc(14px + env(safe-area-inset-bottom));
         background:rgba(255,255,255,.04); border-top:1px solid rgba(255,255,255,.08); }}
  .bar .title {{ font-size:16px; font-weight:600; margin-bottom:4px; }}
  .bar .meta {{ color:#9aa0a6; font-size:13px; margin-bottom:8px; }}
  .bar .tip {{ color:#9aa0a6; font-size:12px; }}
  .bar .btn {{ display:inline-block; background:#07c160; color:#fff; text-decoration:none;
              padding:9px 28px; border-radius:20px; font-size:14px; }}
  .notes {{ max-width:520px; margin:6px auto 0; text-align:left; color:#c9cdd3;
           font-size:12px; line-height:1.6; }}
</style>
</head>
<body>
<div class="stage">
  <div class="qrcode-zone">
    <div class="qrcode" id="qrcode"></div>
  </div>
  <div class="bar">
    <div class="title">Bridge 客户端 <strong>{_html.escape(version)}</strong></div>
    <div class="meta">{_html.escape(filename)} · {_html.escape(size)}</div>
    <a class="btn" href="{_html.escape(download_url, quote=True)}">点击下载</a>
    <div class="tip">手机用相机 / 微信「扫一扫」识别上方二维码，即可下载安装</div>
    {notes_html}
  </div>
</div>
<script>
  var url = {js_url};
  // 二维码铺满窗口：SVG 只带 viewBox（scalable，无固定宽高），实际尺寸交给 CSS 的
  // min(80vmin, ...) 收敛，任何窗口比例都不溢出。cellSize 取较大值保证放大后仍清晰。
  try {{
    var qr = qrcode(0, 'M');
    qr.addData(url, 'Byte');
    qr.make();
    document.getElementById('qrcode').innerHTML = qr.createSvgTag({{cellSize:6, margin:0, scalable:true}});
  }} catch (e) {{
    document.getElementById('qrcode').innerHTML = '<p style="color:#f66;">二维码生成失败</p>';
  }}
</script>
</body>
</html>"""
    return HTMLResponse(html)

