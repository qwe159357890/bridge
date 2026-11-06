import { request } from './request'

// ==================== 极空 BMS 状态 ====================

export interface JikongStatus {
  device_name?: string
  device_address?: string
  connected?: boolean
  last_update_at?: number
  total_voltage?: number
  current?: number
  soc?: number
  remain_capacity?: number
  nominal_capacity?: number
  power?: number
  cell_count?: number
  cell_voltages?: number[]
  cell_resistances?: number[]
  avg_cell_voltage?: number
  max_cell_voltage?: number
  min_cell_voltage?: number
  max_cell_index?: number
  min_cell_index?: number
  diff_voltage?: number
  temp_count?: number
  temperatures?: number[]
  max_temperature?: number
  min_temperature?: number
  temp_diff?: number
  protection_states?: Record<string, boolean>
  alarm_states?: Record<string, boolean>
  charge_mos_on?: boolean
  discharge_mos_on?: boolean
  balance_on?: boolean
  balance_current?: number
  hardware_version?: string
  software_version?: string
  model?: string
  cycle_count?: number
  charge_count?: number
  soh?: number
  battery_type?: string
  cycle_capacity?: number
  heater_on?: boolean
  heater_current?: number
  charger_state?: string
  remain_time_seconds?: number
  cell_full_voltage?: number
  cell_empty_voltage?: number
  charge_over_temp?: number
  discharge_over_temp?: number
  charge_current_limit?: number
  discharge_current_limit?: number
  /** v0.2.86：时速仪表盘快照（客户端 SpeedDash 单例上报） */
  dash?: {
    speed_kmh?: number
    gps_fresh?: boolean
    range_km?: number
    inst_wh_km?: number
    avg_wh_km?: number
    trip_km?: number
    trip_wh?: number
    trip_seconds?: number
    bms_stale?: boolean
    total_km?: number
    total_wh?: number
    hist_avg_wh_km?: number
    updated_at?: number
  }
  /** v0.2.86：手机本地设置（主题模式 / 仪表盘开关） */
  app?: {
    theme?: number
    dash_enabled?: boolean
  }
}

export interface JikongStateResponse {
  device_id: string
  status: JikongStatus | null
  updated_at: number | null
}

export function getJikongState(deviceId: string): Promise<JikongStateResponse> {
  return request<JikongStateResponse>({
    url: `/api/jikong/${deviceId}`,
    method: 'get',
  })
}

// ==================== 里程台账（管理侧只读，v0.2.86） ====================

export interface JkOdoSeg {
  d_odo_km: number
  d_ah: number
  avg_volt: number
  kwh: number
  wh_km: number
}

export interface JkOdoRecord {
  record_id: string
  ts_ms: number
  odo_km: number
  cycle_ah: number
  volt: number | null
  deleted: boolean
  seg: JkOdoSeg | null
}

export interface JkOdoSummary {
  total_km: number
  total_kwh: number
  avg_wh_km: number | null
  count: number
}

export function getJikongOdometer(deviceId: string): Promise<{ device_id: string; records: JkOdoRecord[]; summary: JkOdoSummary }> {
  return request({
    url: `/api/jikong/${deviceId}/odometer`,
    method: 'get',
  })
}

// ==================== 极空控制命令 ====================

export interface JikongControlParams {
  action: 'mos' | 'balance' | 'write_param'
  charge?: boolean
  discharge?: boolean
  on?: boolean
  param_id?: number
  value?: number
}

export function jikongControl(deviceId: string, params: JikongControlParams): Promise<{ command_id: string; delivered: boolean }> {
  return request({
    url: `/api/jikong/${deviceId}/control`,
    method: 'post',
    data: params,
  })
}
