// 设备
export interface Device {
  device_id: string
  device_name: string
  online: boolean
  created_at: number
}

// 命令记录
export interface CommandRecord {
  command_id: string
  device_id: string
  command: string
  params: Record<string, any>
  status: 'pending' | 'success' | 'failed'
  created_at: number
  result_message?: string
  result_at?: number
}

// 日志条目
export interface LogEntry {
  device_id: string
  device_name: string
  app_version: string
  content: string
  created_at: number
}

// 日志列表响应
export interface LogListResponse {
  device_id: string
  total: number
  logs: LogEntry[]
}

// 连接信息
export interface ConnectionInfo {
  device_id: string
  state: string
  connected_at: number
  last_active_at: number
  idle_seconds: number
}

// 健康检查
export interface HealthInfo {
  status: string
  active_connections: number
  registered_devices: number
  uptime_seconds: number
}
