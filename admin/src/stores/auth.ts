import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminLogin } from '@/api/auth'
import { getToken, setToken, clearToken } from '@/api/request'
import { markSkipAutoLoginOnce } from '@/utils/accountMemory'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())

  const isLoggedIn = () => !!token.value

  async function login(username: string, password: string) {
    const result = await adminLogin(username, password)
    token.value = result.token
    setToken(result.token)
  }

  function logout() {
    token.value = ''
    clearToken()
    // 手动退出：打「一次性跳过自动登录」标记，让登录页本次不自动登录；
    // 但**不改动**用户勾选的「自动登录」偏好——下次打开仍恢复自动登录。
    markSkipAutoLoginOnce()
  }

  return { token, isLoggedIn, login, logout }
})
