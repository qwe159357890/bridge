import { request } from './request'
import type { ConnectionInfo } from '@/types'

export function listConnections(): Promise<{
  active_connections: number
  connections: ConnectionInfo[]
}> {
  return request({ url: '/api/connections', method: 'get' })
}
