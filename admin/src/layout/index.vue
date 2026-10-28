<template>
  <el-container class="layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="aside">
      <div class="logo">
        <el-icon class="logo-icon"><Connection /></el-icon>
        <span v-if="!isCollapse">Bridge 管理后台</span>
      </div>
      <el-menu
        :default-active="$route.path"
        :collapse="isCollapse"
        :collapse-transition="false"
        router
        background-color="#ffffff"
        text-color="#606266"
        active-text-color="#409eff"
      >
        <el-menu-item index="/dashboard" title="仪表盘">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/device" title="设备管理">
          <el-icon><Monitor /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/command" title="命令下发">
          <el-icon><Promotion /></el-icon>
          <span>命令下发</span>
        </el-menu-item>
        <el-menu-item index="/log" title="日志查看">
          <el-icon><Document /></el-icon>
          <span>日志查看</span>
        </el-menu-item>
        <el-menu-item index="/push" title="消息推送">
          <el-icon><Message /></el-icon>
          <span>消息推送</span>
        </el-menu-item>
        <el-menu-item index="/contacts" title="通讯录">
          <el-icon><User /></el-icon>
          <span>通讯录</span>
        </el-menu-item>
        <el-menu-item index="/sms" title="短信">
          <el-badge
            v-if="unreadSms > 0"
            :value="unreadSms"
            :max="99"
            :hidden="false"
            class="sms-badge"
          >
            <el-icon><ChatDotRound /></el-icon>
          </el-badge>
          <span>短信</span>
        </el-menu-item>
        <el-menu-item index="/calllog" title="通话记录">
          <el-icon><Phone /></el-icon>
          <span>通话记录</span>
        </el-menu-item>
        <el-menu-item index="/media" title="媒体文件">
          <el-icon><Picture /></el-icon>
          <span>媒体文件</span>
        </el-menu-item>
        <el-menu-item index="/connection" title="连接监控">
          <el-icon><Connection /></el-icon>
          <span>连接监控</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="toggleCollapse">
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ $route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
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
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Fold, Expand } from '@element-plus/icons-vue'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'
import { ElNotification } from 'element-plus'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// ---------------- 侧边栏收缩/展开（localStorage 记忆） ----------------
const isCollapse = ref(false)
function toggleCollapse() {
  isCollapse.value = !isCollapse.value
  try {
    localStorage.setItem('bridge_admin_aside_collapse', isCollapse.value ? '1' : '0')
  } catch {
    /* 隐私模式忽略 */
  }
}

function handleCommand(command: string) {
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

// ---------------- 全局新短信提醒（冒泡 + 声音 + 侧边栏未读角标） ----------------
const unreadSms = ref(0)
let ws: WebSocket | null = null
let reconnectTimer: number | null = null
let wsFailCount = 0
let reconnectDelay = 3000
const RECONNECT_MAX_DELAY = 30000
// 前端去重：最近 60s 内已提醒过的 sms_key（第二道防线，配合服务端去重）
const recentSmsKeys = new Map<string, number>()

/** Web Audio 合成短促提示音（880Hz 双音，零音频资源依赖）。 */
function playBeep() {
  try {
    const AudioCtx = window.AudioContext || (window as any).webkitAudioContext
    if (!AudioCtx) return
    const ctx = new AudioCtx()
    const now = ctx.currentTime
    // 两段短音：叮—叮
    ;[0, 0.18].forEach((offset) => {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.value = 880
      gain.gain.setValueAtTime(0.0001, now + offset)
      gain.gain.exponentialRampToValueAtTime(0.35, now + offset + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.15)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now + offset)
      osc.stop(now + offset + 0.16)
    })
    // 播放完后释放 AudioContext
    window.setTimeout(() => ctx.close().catch(() => {}), 500)
  } catch {
    /* 音频合成失败忽略 */
  }
}

function notifyNewSms(data: any) {
  console.log('[Bridge] 收到新短信事件:', data)
  // 前端去重：同一 sms_key 60s 内只提醒一次
  const key = `${data.device_id}:${data.sms_key || ''}`
  const now = Date.now()
  for (const [k, t] of recentSmsKeys) {
    if (now - t > 60000) recentSmsKeys.delete(k)
  }
  if (recentSmsKeys.has(key)) {
    console.log('[Bridge] 新短信重复事件，跳过提醒:', key)
    return
  }
  recentSmsKeys.set(key, now)

  unreadSms.value += 1
  const body = (data.body || '').slice(0, 40)
  ElNotification({
    title: `新短信 · ${data.device_id}`,
    message: `${data.address || '未知号码'}：${body}${(data.body || '').length > 40 ? '…' : ''}`,
    type: 'info',
    duration: 6000,
    position: 'top-right',
    onClick: () => {
      router.push('/sms')
    },
  })
  playBeep()
}

function connectWS() {
  const token = getToken()
  if (!token) {
    console.warn('[Bridge] 无 admin token，跳过 WS 连接')
    return
  }
  let url = wsUrl('/api/admin/ws')
  url += `${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
  console.log('[Bridge] 连接管理 WS:', url.replace(/token=[^&]+/, 'token=***'))
  ws = new WebSocket(url)
  ws.onopen = () => {
    console.log('[Bridge] 管理 WS 已连接')
    wsFailCount = 0
    reconnectDelay = 3000
  }
  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      console.log('[Bridge] 管理 WS 收到事件:', msg.type)
      if (msg.type === 'new_sms') {
        notifyNewSms(msg.data)
      }
    } catch {
      console.warn('[Bridge] 管理 WS 消息解析失败:', event.data)
    }
  }
  ws.onclose = (e) => {
    console.warn('[Bridge] 管理 WS 已断开:', e.code, e.reason, '将在稍后重连')
    scheduleReconnect()
  }
  ws.onerror = () => {
    console.error('[Bridge] 管理 WS 连接错误')
    ws?.close()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  wsFailCount += 1
  const jitter = reconnectDelay * 0.8 + Math.random() * reconnectDelay * 0.4
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectWS()
  }, Math.round(jitter))
  reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY)
}

onMounted(() => {
  try {
    isCollapse.value = localStorage.getItem('bridge_admin_aside_collapse') === '1'
  } catch {
    /* 忽略 */
  }
  connectWS()
})

onBeforeUnmount(() => {
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  reconnectTimer = null
  ws?.close()
})
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
  white-space: nowrap;
  overflow: hidden;
}
.logo-icon {
  font-size: 20px;
  color: var(--brand);
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #606266;
  transition: color 0.15s;
}
.collapse-btn:hover {
  color: var(--brand);
}
.sms-badge {
  margin-right: 4px;
  flex-shrink: 0;
}
/* 角标显示在图标左上角（默认 el-badge 在内容右上角） */
.sms-badge :deep(.el-badge__content) {
  font-size: 11px;
  top: 0;
  right: auto;
  left: 0;
  transform: translate(-40%, -40%) scale(0.85);
  z-index: 1;
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
