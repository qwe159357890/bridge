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

// 命令参数控件类型
export type ParamType = 'text' | 'number' | 'switch' | 'select' | 'textarea'

// 命令参数定义（来自 /api/commands/meta）
export interface CommandParamDef {
  name: string
  label: string
  type: ParamType
  required: boolean
  default?: any
  placeholder?: string
  options?: { label: string; value: string }[]
  description?: string
}

// 命令类别
export interface CommandCategory {
  key: string
  label: string
  icon: string
  description?: string
}

// 命令定义（来自 /api/commands/meta）
export interface CommandDef {
  name: string
  label: string
  category: string
  icon: string
  description: string
  params: CommandParamDef[]
}

// 命令目录响应（/api/commands/meta）
export interface CommandCatalog {
  categories: CommandCategory[]
  commands: CommandDef[]
}

// 日志条目（一行结构化日志）
export interface LogEntry {
  device_id: string
  device_name: string
  app_version: string
  ts: number
  level: string
  tag: string
  message: string
  created_at: number
}

// 日志列表响应
export interface LogListResponse {
  device_id: string
  total: number
  offset: number
  limit: number
  logs: LogEntry[]
}

// 有日志设备的摘要（日志页左侧列表）
export interface LogDeviceSummary {
  device_id: string
  log_count: number
  last_log_at: number | null
  device_name: string
  online: boolean
}

// 连接信息
export interface ConnectionInfo {
  device_id: string
  device_name?: string
  state: string
  connected_at: number
  last_active_at: number
  idle_seconds: number
  uptime_seconds?: number | null
}

// 上下线事件
export interface ConnectionEvent {
  device_id: string
  event: 'online' | 'offline'
  reason: string
  ts: number
}

// 健康检查
export interface HealthInfo {
  status: string
  active_connections: number
  registered_devices: number
  uptime_seconds: number
}
