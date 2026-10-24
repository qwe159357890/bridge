import { request } from './request'
import type { ConnectionInfo, ConnectionEvent } from '@/types'

export function listConnections(): Promise<{
  active_connections: number
  connections: ConnectionInfo[]
}> {
  return request({ url: '/api/connections', method: 'get' })
}

export function listConnectionLog(params?: {
  device_id?: string
  limit?: number
}): Promise<{ total: number; events: ConnectionEvent[] }> {
  return request({ url: '/api/connections/log', method: 'get', params })
}
