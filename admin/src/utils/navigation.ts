/**
 * 导航拉起工具（v0.2.127）。
 *
 * 在管理后台（尤其客户端内嵌 WebView）点「导航」后，跳转到手机上已安装的地图
 * App 并直接规划到目标位置的路线。三类 scheme：
 *  - androidamap:// （高德）
 *  - baidumap://   （百度）
 *  - qqmap://      （腾讯）
 * 另提供 geo: 通用协议兜底（交给系统选择已装地图）。
 *
 * 坐标说明：定位记录是 WGS-84，同时带 GCJ-02（gcj_latitude/gcj_longitude）。
 * 高德/腾讯直接吃 GCJ-02；百度地图 App 内部会自动做 GCJ→BD09 纠偏，故也传 GCJ-02。
 * 若 GCJ 缺失（境外点），退回 WGS-84 原坐标。
 */

export interface NavCoord {
  /** 目标纬度（GCJ-02 优先） */
  lat: number
  /** 目标经度（GCJ-02 优先） */
  lng: number
}

export interface NavTarget {
  coord: NavCoord
  /** 目标名称（如设备昵称），用于地图 App 里显示目的地标题 */
  name?: string
}

/** 从定位记录取 GCJ-02 坐标（缺省退回 WGS-84）。 */
export function toNavCoord(r: {
  latitude: number
  longitude: number
  gcj_latitude?: number | null
  gcj_longitude?: number | null
}): NavCoord {
  return {
    lat: r.gcj_latitude ?? r.latitude,
    lng: r.gcj_longitude ?? r.longitude,
  }
}

export type MapApp = 'amap' | 'baidu' | 'qq'

/** 各地图 App 的 URI scheme（Android）。 */
export function buildNavUri(app: MapApp, t: NavTarget): string {
  const { lat, lng } = t.coord
  const name = encodeURIComponent(t.name || '目标位置')
  switch (app) {
    case 'amap':
      // 高德驾车导航（GCJ-02）；dev=0 表示起点不传，由高德自动定位
      return `androidamap://navi?sourceApplication=${name}&lat=${lat}&lon=${lng}&dev=0&style=2`
    case 'baidu':
      // 百度地图路线规划（百度内部做坐标纠偏）
      return `baidumap://map/direction?destination=${lat},${lng}&mode=driving&coord_type=gcj02&src=bridge`
    case 'qq':
      // 腾讯地图驾车路线（GCJ-02）
      return `qqmap://map/routeplan?type=drive&to=${name}&tocoord=${lat},${lng}&referer=bridge`
  }
}

/** geo 通用协议（系统选择已装地图，仅当明确指定时用）。 */
export function buildGeoUri(t: NavTarget): string {
  return `geo:${t.coord.lat},${t.coord.lng}?q=${encodeURIComponent(t.name || '目标位置')}`
}

/** 探测某地图 App 是否可用：跳转后失去焦点即视为成功（WebView 内由宿主拦截）。 */
export function tryOpenUri(uri: string): void {
  // 用隐藏 iframe 或 location 跳转；WebView 宿主会拦截 map scheme 并拉起对应 App。
  // 这里优先 location.href，配合客户端 shouldOverrideUrlLoading 拦截。
  window.location.href = uri
}
