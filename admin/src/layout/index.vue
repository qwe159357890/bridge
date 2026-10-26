<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon class="logo-icon"><Connection /></el-icon>
        <span>Bridge 管理后台</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#ffffff"
        text-color="#606266"
        active-text-color="#409eff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/device">
          <el-icon><Monitor /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/command">
          <el-icon><Promotion /></el-icon>
          <span>命令下发</span>
        </el-menu-item>
        <el-menu-item index="/log">
          <el-icon><Document /></el-icon>
          <span>日志查看</span>
        </el-menu-item>
        <el-menu-item index="/push">
          <el-icon><Message /></el-icon>
          <span>消息推送</span>
        </el-menu-item>
        <el-menu-item index="/contacts">
          <el-icon><User /></el-icon>
          <span>通讯录</span>
        </el-menu-item>
        <el-menu-item index="/sms">
          <el-icon><ChatDotRound /></el-icon>
          <span>短信</span>
        </el-menu-item>
        <el-menu-item index="/calllog">
          <el-icon><Phone /></el-icon>
          <span>通话记录</span>
        </el-menu-item>
        <el-menu-item index="/media">
          <el-icon><Picture /></el-icon>
          <span>媒体文件</span>
        </el-menu-item>
        <el-menu-item index="/connection">
          <el-icon><Connection /></el-icon>
          <span>连接监控</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item>{{ $route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <el-dropdown @command="handleCommand">
          <span class="user">
            <el-avatar :size="28" class="avatar">管</el-avatar>
            <span>管理员</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

function handleCommand(command: string) {
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background-color: #ffffff;
  border-right: 1px solid #ebeef5;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #303133;
  font-size: 17px;
  font-weight: 600;
  border-bottom: 1px solid #f0f2f5;
}
.logo-icon {
  font-size: 20px;
  color: var(--brand);
}
.aside :deep(.el-menu) {
  border-right: none;
}
.aside :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  margin: 4px 8px;
  border-radius: 6px;
}
.aside :deep(.el-menu-item.is-active) {
  background: rgba(64, 158, 255, 0.1) !important;
  font-weight: 600;
}
.aside :deep(.el-menu-item:hover) {
  background: #f5f7fa !important;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
  background: #fff;
}
.user {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.avatar {
  background: var(--brand);
  font-size: 14px;
}
.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
