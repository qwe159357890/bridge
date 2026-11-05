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
  cell_full_voltage?: number
  cell_empty_voltage?: number
  charge_over_temp?: number
  discharge_over_temp?: number
  charge_current_limit?: number
  discharge_current_limit?: number
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
