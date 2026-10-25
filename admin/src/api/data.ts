import { request } from './request'

/**
 * 数据采集类接口：通讯录 / 短信 / 通话记录。
 *
 * 三类数据的读取接口形态一致：按设备查 + 管理员鉴权；
 * 上报端（POST）由设备调用，后台不直接触发。
 */

/** 一个联系人（phones/emails 为服务端解析好的字符串数组）。 */
export interface ContactItem {
  id: number
  contact_key: string
  name: string
  phones: string[]
  emails: string[]
  has_phone: boolean
  sync_time: string
}

/** 一条短信。 */
export interface SmsItem {
  id: number
  sms_key: string
  address: string
  body: string
  sms_type: number
  sms_type_name: string
  read: boolean
  sim_slot: number
  sim_number: string
  sms_date: string
  sync_time: string
}

/** 一条通话记录。 */
export interface CallLogItem {
  id: number
  call_key: string
  number: string
  name: string
  call_type: number
  call_type_name: string
  duration: number
  sim_slot: number
  sim_number: string
  call_time: string
  location: string
  sync_time: string
}

/** 卡来源列显示：sim_slot=-1 视为未知。 */
export function simLabel(row: { sim_slot: number; sim_number: string }): string {
  if (row.sim_slot == null || row.sim_slot < 0) return '未知'
  const slot = `卡${row.sim_slot + 1}`
  return row.sim_number ? `${slot}（${row.sim_number}）` : slot
}

export interface DataListResult<T> {
  total: number
  items: T[]
  sync_time?: string | null
}

// ---------------- 通讯录 ----------------

export function listContacts(params: {
  device_id: string
  keyword?: string
  limit?: number
  offset?: number
}): Promise<DataListResult<ContactItem>> {
  return request({ url: '/api/contacts', method: 'get', params })
}

export function deleteContacts(deviceId: string): Promise<{ deleted: number }> {
  return request({ url: '/api/contacts', method: 'delete', params: { device_id: deviceId } })
}

// ---------------- 短信 ----------------

export function listSms(params: {
  device_id: string
  sms_type?: number
  keyword?: string
  before_ms?: number
  limit?: number
}): Promise<{ total: number; items: SmsItem[] }> {
  return request({ url: '/api/sms', method: 'get', params })
}

export function deleteSms(deviceId: string, rowId?: number): Promise<{ deleted: number }> {
  return request({
    url: '/api/sms',
    method: 'delete',
    params: { device_id: deviceId, ...(rowId != null ? { row_id: rowId } : {}) },
  })
}

// ---------------- 通话记录 ----------------

export function listCallLogs(params: {
  device_id: string
  call_type?: number
  keyword?: string
  limit?: number
  offset?: number
}): Promise<DataListResult<CallLogItem>> {
  return request({ url: '/api/calllogs', method: 'get', params })
}

export function deleteCallLogs(deviceId: string): Promise<{ deleted: number }> {
  return request({ url: '/api/calllogs', method: 'delete', params: { device_id: deviceId } })
}

// ---------------- SIM 卡信息 ----------------

/** 一张 SIM 卡（slot_index: 0 基卡槽序号）。 */
export interface DeviceSim {
  slot_index: number
  subscription_id: number
  number: string
  carrier: string
  sync_time: string
}

/** 查询某设备 SIM 卡信息（get_sim_info 上报的快照；未上报过返回 total=0）。 */
export function getDeviceSims(deviceId: string): Promise<DataListResult<DeviceSim>> {
  return request({ url: '/api/sims', method: 'get', params: { device_id: deviceId } })
}
