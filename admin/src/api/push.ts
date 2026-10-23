import { request } from './request'

export interface PushResult {
  total: number
  delivered: number
  failed: string[]
}

export function pushMessage(deviceIds: string[], payload: Record<string, any>): Promise<PushResult> {
  return request<PushResult>({
    url: '/api/push',
    method: 'post',
    data: { device_ids: deviceIds, payload },
  })
}
