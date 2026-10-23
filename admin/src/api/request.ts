import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const TOKEN_KEY = 'bridge_admin_token'

// 读取/写入 token（简单封装，供 auth store 与拦截器共用）
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

// baseURL：优先环境变量，否则空（走 vite 代理）
const baseURL = import.meta.env.VITE_API_BASE || ''

const instance: AxiosInstance = axios.create({
  baseURL,
  timeout: 15000,
})

// 请求拦截：注入 admin token
instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一错误处理 + 401 跳登录
instance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const status = error.response.status
      const detail = error.response.data?.detail
      if (status === 401) {
        clearToken()
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      } else if (status === 400) {
        ElMessage.error(typeof detail === 'string' ? detail : '请求参数错误')
      } else {
        ElMessage.error(typeof detail === 'string' ? detail : `请求失败（${status}）`)
      }
    } else {
      ElMessage.error('网络错误，请检查服务端是否启动')
    }
    return Promise.reject(error)
  },
)

// 泛型请求辅助
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return instance.request(config).then((res) => res.data)
}

export default instance
