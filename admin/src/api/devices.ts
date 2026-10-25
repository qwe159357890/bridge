import { request } from './request'
import type { Device } from '@/types'

export function listDevices(): Promise<Device[]> {
  return request<Device[]>({ url: '/api/devices', method: 'get' })
}

export function deviceCount(): Promise<{ registered_devices: number }> {
  return request<{ registered_devices: number }>({ url: '/api/devices/count', method: 'get' })
}

export function disconnectDevice(deviceId: string): Promise<{ status: string; device_id: string }> {
  return request({
    url: `/api/devices/${deviceId}/disconnect`,
    method: 'post',
  })
}

/** 设备音乐播放状态（playing/paused/idle/unknown）。 */
export interface MusicState {
  device_id: string
  state: 'playing' | 'paused' | 'idle' | 'unknown'
  source: string | null
  updated_at?: number | null
}

export function getMusicState(deviceId: string): Promise<MusicState> {
  return request<MusicState>({
    url: `/api/devices/${deviceId}/music-state`,
    method: 'get',
  })
}
