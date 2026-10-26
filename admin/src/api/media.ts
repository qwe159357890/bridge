import instance, { getToken, request } from './request'

export interface MediaItem {
  id: number
  device_id: string
  kind: string
  kind_label: string
  filename: string
  size: number
  created_at: string
  exists: boolean
}

export function listMedia(limit = 200): Promise<{ items: MediaItem[]; total: number }> {
  return request({ url: '/api/media', method: 'get', params: { limit } })
}

/**
 * 文件本体 URL（图片预览 / 视频播放）。
 * `<img>/<video>` 标签带不了 Bearer 头，走 `?token=` 查询参数鉴权（服务端兼容）。
 */
export function mediaFileUrl(id: number): string {
  const base = instance.defaults.baseURL || ''
  return `${base}/api/media/${id}/file?token=${encodeURIComponent(getToken())}`
}

export function deleteMedia(id: number): Promise<{ status: string; deleted: number }> {
  return request({ url: `/api/media/${id}`, method: 'delete' })
}
