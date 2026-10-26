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

/** ADB 一键开启无障碍：逐步骤结果（服务端代跑 adb）。 */
export interface AdbEnableAccessibilityResult {
  success: boolean
  serial?: string
  /** 该设备上报的全部 IP（弹窗交叉确认用） */
  device_ips?: string[]
  /** 失败时附带：当前 adb 已连接的每台手机及 IP */
  adb_devices?: {
    serial: string
    ip?: string | null
    conn_type?: 'usb' | 'wifi' | 'wifi_mdns'
  }[]
  steps?: { step: string; ok: boolean; detail?: string }[]
  error?: string
}

/** 设备网络信息（ADB 弹窗交叉确认）：设备 IP 对照 adb 设备 IP。 */
export interface DeviceNetInfo {
  device_ips: string[]
  adb_devices: {
    serial: string
    ip?: string | null
    /** 连接类型：usb=数据线 / wifi=无线连接(adb connect) / wifi_mdns=mDNS 自动发现的无线设备 */
    conn_type?: 'usb' | 'wifi' | 'wifi_mdns'
  }[]
}

/** adb 设备连接类型的中文标签。 */
export const CONN_TYPE_LABEL: Record<string, string> = {
  usb: '数据线',
  wifi: 'WiFi 无线',
  wifi_mdns: 'WiFi 自动发现',
}

export function getDeviceNetInfo(deviceId: string): Promise<DeviceNetInfo> {
  return request<DeviceNetInfo>({
    url: `/api/devices/${deviceId}/net-info`,
    method: 'get',
  })
}

export function adbEnableAccessibility(
  deviceId: string,
  ip?: string,
): Promise<AdbEnableAccessibilityResult> {
  return request<AdbEnableAccessibilityResult>({
    url: `/api/devices/${deviceId}/adb-enable-accessibility`,
    method: 'post',
    data: ip ? { ip } : {},
  })
}

/** WiFi adb 一条龙请求体。 */
export interface AdbWifiSetupParams {
  /** 无线调试主界面显示的端口 */
  connect_port: number
  /** 首次配对：配对页显示的端口 */
  pair_port?: number
  /** 首次配对：6 位配对码 */
  pair_code?: string
  /** 手机局域网 IP（WS 走公网时自动 IP 不可用，需手填） */
  ip?: string
}

export function adbWifiEnableAccessibility(
  deviceId: string,
  params: AdbWifiSetupParams,
): Promise<AdbEnableAccessibilityResult> {
  return request<AdbEnableAccessibilityResult>({
    url: `/api/devices/${deviceId}/adb-wifi-enable-accessibility`,
    method: 'post',
    data: params,
  })
}
