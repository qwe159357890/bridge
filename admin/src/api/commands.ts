import { request } from './request'
import type { CommandCatalog, CommandRecord } from '@/types'

export interface SendCommandResult {
  command_id: string
  device_id: string
  command: string
  delivered: boolean
}

export function sendCommand(
  deviceId: string,
  command: string,
  params: Record<string, any> = {},
  force = false,
  silent = false,
): Promise<SendCommandResult> {
  // silent=true：管理后台自动探测（如 vnt_status 状态查询）照常下发、回执照常广播，
  // 但服务端不登记命令历史，避免内部查询污染用户主动下发的记录。
  return request<SendCommandResult>({
    url: '/api/commands',
    method: 'post',
    data: { device_id: deviceId, command, params, force, silent },
  })
}

export function listCommands(): Promise<CommandRecord[]> {
  return request<CommandRecord[]>({ url: '/api/commands', method: 'get' })
}

export function getCommand(commandId: string): Promise<CommandRecord> {
  return request<CommandRecord>({ url: `/api/commands/${commandId}`, method: 'get' })
}

/** 清空命令历史（deviceId 缺省清空全部；后端存储上限 200 条防堆积）。 */
export function clearCommands(deviceId?: string): Promise<{ deleted: number }> {
  return request<{ deleted: number }>({
    url: '/api/commands',
    method: 'delete',
    params: deviceId ? { device_id: deviceId } : {},
  })
}

// 拉取命令目录（类别 + 命令 + 参数 schema），供命令下发页动态渲染
export function getCommandCatalog(): Promise<CommandCatalog> {
  return request<CommandCatalog>({ url: '/api/commands/meta', method: 'get' })
}
