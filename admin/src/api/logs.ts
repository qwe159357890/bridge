import { request } from './request'
import type { LogListResponse, LogDeviceSummary } from '@/types'

export function listLogDevices(): Promise<{ devices: LogDeviceSummary[]; total_logs: number }> {
  return request<{ devices: LogDeviceSummary[]; total_logs: number }>({ url: '/api/logs', method: 'get' })
}

export function getDeviceLogs(
  deviceId: string,
  offset = 0,
  limit = 50,
  filters: { level?: string; tag?: string; keyword?: string } = {},
): Promise<LogListResponse> {
  return request<LogListResponse>({
    url: `/api/logs/${deviceId}`,
    method: 'get',
    params: { offset, limit, level: filters.level || '', tag: filters.tag || '', keyword: filters.keyword || '' },
  })
}

export function clearDeviceLogs(deviceId: string): Promise<{ status: string; removed: number }> {
  return request({ url: `/api/logs/${deviceId}`, method: 'delete' })
}
