import { request } from './request'
import type { LogListResponse } from '@/types'

export function listLogDevices(): Promise<{ devices: string[]; total_logs: number }> {
  return request<{ devices: string[]; total_logs: number }>({ url: '/api/logs', method: 'get' })
}

export function getDeviceLogs(deviceId: string): Promise<LogListResponse> {
  return request<LogListResponse>({ url: `/api/logs/${deviceId}`, method: 'get' })
}

export function clearDeviceLogs(deviceId: string): Promise<{ status: string; removed: number }> {
  return request({ url: `/api/logs/${deviceId}`, method: 'delete' })
}
