<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <el-icon class="brand-icon"><Connection /></el-icon>
      </div>
      <h2 class="title">Bridge 管理后台</h2>
      <p class="subtitle">设备管理与运维平台</p>

      <!-- 自动登录进行中提示 -->
      <div v-if="autoLogging" class="auto-hint">
        <el-icon class="is-loading"><Loading /></el-icon> 正在自动登录…
      </div>

      <el-form :model="form" @keyup.enter="handleLogin">
        <el-form-item>
          <el-select
            v-model="form.username"
            placeholder="管理员账号"
            size="large"
            filterable
            allow-create
            default-first-option
            clearable
            style="width: 100%"
            :no-data-text="'暂无记住的账号，直接输入即可'"
            @change="onAccountChange"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
            <el-option
              v-for="acc in accountOptions"
              :key="acc.username"
              :label="acc.username"
              :value="acc.username"
            >
              <span style="float: left">{{ acc.username }}</span>
              <span style="float: right; color: #909399; font-size: 12px">已记住</span>
              <el-icon
                class="acc-del"
                style="float: right; margin: 6px 4px 0 8px; color: #c0c4cc"
                @click.stop="removeAccount(acc.username)"
                title="删除该账号"
              >
                <Close />
              </el-icon>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-form-item>
          <div class="opts">
            <el-checkbox v-model="rememberPwd">记住密码</el-checkbox>
            <el-checkbox v-model="autoLogin" @change="onAutoLoginChange">自动登录</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="login-btn"
            size="large"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Connection, Close, Loading } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import {
  listAccounts,
  saveAccount,
  removeAccount as removeSavedAccount,
  getPassword,
  getLastUser,
  setLastUser,
  isAutoLogin,
  setAutoLogin,
  consumeSkipAutoLoginOnce,
  type SavedAccount,
} from '@/utils/accountMemory'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const autoLogging = ref(false)
const rememberPwd = ref(false)
const autoLogin = ref(isAutoLogin())

// 记住的账号列表（下拉选项）
const accountOptions = computed<SavedAccount[]>(() => listAccounts())

function onAccountChange(username: string) {
  // 选中已记住的账号 → 自动填充密码
  if (username) {
    const pwd = getPassword(username)
    if (pwd) form.password = pwd
    rememberPwd.value = true
  }
}

function onAutoLoginChange(v: boolean) {
  // 自动登录依赖记住密码：勾选时强制勾选记住密码
  if (v) rememberPwd.value = true
}

function removeAccount(username: string) {
  removeSavedAccount(username)
  if (form.username === username) {
    form.username = ''
    form.password = ''
  }
  ElMessage.success(`已删除记住的账号「${username}」`)
}

async function handleLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    // 登录成功后按勾选记忆账号 / 自动登录开关
    if (rememberPwd.value) saveAccount(form.username, form.password)
    setAutoLogin(autoLogin.value)
    setLastUser(form.username)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch {
    // 错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

// 自动登录：页面加载时若开关开启且能取到密码，静默换新 token
async function tryAutoLogin() {
  // 手动退出后的一次性跳过：本次不自动登录（勾选偏好保持不变），清除标记下次恢复
  if (consumeSkipAutoLoginOnce()) return
  if (!isAutoLogin()) return
  const username = getLastUser()
  const pwd = username ? getPassword(username) : null
  if (!username || !pwd) {
    // 有开关但没记住账号/密码（数据被清）→ 关闭开关
    setAutoLogin(false)
    return
  }
  autoLogging.value = true
  try {
    await auth.login(username, pwd)
    router.push('/dashboard')
  } catch {
    // 密码失效/token 过期：关闭自动登录，回退手动登录（密码框已回填，方便用户改）
    setAutoLogin(false)
    autoLogin.value = false
    form.username = username
    form.password = pwd
    ElMessage.warning('自动登录失败，请手动登录')
  } finally {
    autoLogging.value = false
  }
}

onMounted(() => {
  // 上次登录的用户名预填下拉；若该账号已记住密码，回填密码并勾选「记住密码」
  const last = getLastUser()
  if (last) {
    form.username = last
    const pwd = getPassword(last)
    if (pwd) {
      form.password = pwd
      rememberPwd.value = true
    }
  }
  tryAutoLogin()
})
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a8a 0%, #0f766e 55%, #065f46 100%);
}
.login-card {
  width: 400px;
  max-width: calc(100vw - 32px);
  padding: 12px 20px 8px;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
}
.brand {
  display: flex;
  justify-content: center;
  margin-bottom: 12px;
}
.brand-icon {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--brand), #0f766e);
  color: #fff;
  font-size: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.title {
  text-align: center;
  margin-bottom: 4px;
  font-size: 20px;
}
.subtitle {
  text-align: center;
  color: #909399;
  font-size: 13px;
  margin-bottom: 24px;
}
.auto-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #2f6fd6;
  font-size: 13px;
  margin-bottom: 16px;
}
.opts {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.login-btn {
  width: 100%;
}
.acc-del:hover {
  color: #e5484d !important;
}
</style>
