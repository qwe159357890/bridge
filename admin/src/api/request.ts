import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'
import { apiBase } from '@/utils/base'

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
const baseURL = apiBase()

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
      // grouping: true → 相同文案的提示合并为一条计数，避免轮询/重试场景刷屏
      if (status === 401) {
        clearToken()
        ElMessage.error({ message: '登录已过期，请重新登录', grouping: true })
        router.push('/login')
      } else if (status === 400) {
        ElMessage.error({
          message: typeof detail === 'string' ? detail : '请求参数错误',
          grouping: true,
        })
      } else {
        ElMessage.error({
          message: typeof detail === 'string' ? detail : `请求失败（${status}）`,
          grouping: true,
        })
      }
    } else {
      ElMessage.error({ message: '网络错误，请检查服务端是否启动', grouping: true })
    }
    return Promise.reject(error)
  },
)

// 泛型请求辅助
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return instance.request(config).then((res) => res.data)
}

export default instance
