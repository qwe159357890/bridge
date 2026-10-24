<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <el-icon class="brand-icon"><Connection /></el-icon>
      </div>
      <h2 class="title">Bridge 管理后台</h2>
      <p class="subtitle">设备管理与运维平台</p>
      <el-form :model="form" @keyup.enter="handleLogin">
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="管理员账号"
            size="large"
            :prefix-icon="User"
          />
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
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Connection } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch {
    // 错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}
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
.login-btn {
  width: 100%;
}
</style>
