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
): Promise<SendCommandResult> {
  return request<SendCommandResult>({
    url: '/api/commands',
    method: 'post',
    data: { device_id: deviceId, command, params },
  })
}

export function listCommands(): Promise<CommandRecord[]> {
  return request<CommandRecord[]>({ url: '/api/commands', method: 'get' })
}

export function getCommand(commandId: string): Promise<CommandRecord> {
  return request<CommandRecord>({ url: `/api/commands/${commandId}`, method: 'get' })
}

// 拉取命令目录（类别 + 命令 + 参数 schema），供命令下发页动态渲染
export function getCommandCatalog(): Promise<CommandCatalog> {
  return request<CommandCatalog>({ url: '/api/commands/meta', method: 'get' })
}
