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
  /** v0.2.86：时速仪表盘快照（客户端 SpeedDash 单例上报）；v0.2.101 与手机卡片同口径 */
  dash?: {
    speed_kmh?: number
    gps_fresh?: boolean
    range_km?: number
    inst_wh_km?: number
    avg_wh_km?: number
    /** v0.2.101：GPS 口径（与手机卡片一致）；旧语义的 BMS 放电口径改列 bms_trip_km */
    trip_km?: number
    bms_trip_km?: number
    /** v0.2.101：本次行程平均时速（km/h，手机行 1 三列之一） */
    avg_speed_kmh?: number
    trip_wh?: number
    trip_seconds?: number
    bms_stale?: boolean
    total_km?: number
    total_wh?: number
    /** v0.2.101：生命周期电耗（装机起累积 totalWh/totalKm，≥1km），与手机设置页一致 */
    hist_avg_wh_km?: number
    updated_at?: number
  }
  /** v0.2.86：手机本地设置；v0.2.101 补齐 1:1 复刻所需（演示/单位/播报/电费/折扣）；v0.2.102 补 speak_muted */
  app?: {
    theme?: number
    dash_enabled?: boolean
    demo_mode?: boolean
    unit_kwh100?: boolean
    speak_enabled?: boolean
    speak_interval_min?: number
    speak_low_soc?: number
    speak_warn_repeat_min?: number
    /** v0.2.102：小喇叭静音（后台「语音播报」卡镜像/同步） */
    speak_muted?: boolean
    elec_price?: number
    odo_discount?: number
    /** v0.2.107：靠近自动连接模式开关（设置页总开关，默认关） */
    proximity_auto_connect?: boolean
    /** v0.2.107：手动断开抑制态（极空页点断开后置位，重进页面/重启 APP 解除；运行时状态非配置） */
    proximity_suppressed?: boolean
  }
  /** v0.2.102：演示模式内存台账（与手机统计页演示同源；后台统计页演示期间用它替代 MySQL） */
  demo_stats?: {
    records: { ts_ms: number; odo_km: number; cycle_ah: number; volt: number }[]
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
  action: 'mos' | 'balance' | 'write_param' | 'speak' | 'set_theme' | 'set_speak' | 'set_config' | 'set_proximity' | 'demo_refresh'
  charge?: boolean
  discharge?: boolean
  on?: boolean
  param_id?: number
  value?: number
  /** v0.2.102：speak/set_theme/set_speak 透传参数；v0.2.104 set_config 同 */
  params?: Record<string, unknown>
}

export function jikongControl(deviceId: string, params: JikongControlParams): Promise<{ command_id: string; delivered: boolean }> {
  return request({
    url: `/api/jikong/${deviceId}/control`,
    method: 'post',
    data: params,
  })
}
