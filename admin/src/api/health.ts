import { request } from './request'
import type { HealthInfo } from '@/types'

export function getHealth(): Promise<HealthInfo> {
  return request<HealthInfo>({ url: '/health', method: 'get' })
}
