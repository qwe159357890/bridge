import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminLogin } from '@/api/auth'
import { getToken, setToken, clearToken } from '@/api/request'

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
  }

  return { token, isLoggedIn, login, logout }
})
