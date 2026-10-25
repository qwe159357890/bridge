import { request } from './request'

/** 一条位置记录（服务端 locations 表行）。 */
export interface LocationRecord {
  id: number
  device_id: string
  latitude: number
  longitude: number
  /** GCJ-02 坐标（高德底图渲染用；境外与 WGS-84 相同） */
  gcj_latitude: number | null
  gcj_longitude: number | null
  accuracy: number
  provider: string
  altitude: number | null
  speed: number | null
  first_fix_time: string
  last_fix_time: string
  server_time: string
  report_count: number
}

export function listLocations(params: {
  device_id?: string
  start_ms?: number
  end_ms?: number
  limit?: number
  offset?: number
}): Promise<{ total: number; locations: LocationRecord[] }> {
  return request({
    url: '/api/locations',
    method: 'get',
    params,
  })
}

export function latestLocation(deviceId: string): Promise<{ location: LocationRecord | null }> {
  return request({
    url: '/api/locations/latest',
    method: 'get',
    params: { device_id: deviceId },
  })
}

/** 手动清理：id / device_id / before_ms 至少传一个（服务端强制校验）。 */
export function deleteLocations(params: {
  id?: number
  device_id?: string
  before_ms?: number
}): Promise<{ deleted: number }> {
  return request({
    url: '/api/locations',
    method: 'delete',
    params,
  })
}

/** 高德 JS API 配置（Key 存服务端 yaml，前端动态获取）。 */
export function getAmapConfig(): Promise<{ key: string; security_js_code: string }> {
  return request({
    url: '/api/locations/amap-config',
    method: 'get',
  })
}
