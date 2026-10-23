import { request } from './request'

export interface LoginResult {
  token: string
  token_type: string
  expires_in: number
}

export function adminLogin(username: string, password: string): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/api/admin/login',
    method: 'post',
    data: { username, password },
  })
}
