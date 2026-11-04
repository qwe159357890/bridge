import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminLogin } from '@/api/auth'
import { getToken, setToken, clearToken } from '@/api/request'
import { setAutoLogin } from '@/utils/accountMemory'

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
    // 关键：手动退出必须关闭「自动登录」，否则下次打开会自动登回、永远退不出去
    setAutoLogin(false)
  }

  return { token, isLoggedIn, login, logout }
})
