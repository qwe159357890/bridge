<template>
  <el-container class="layout">
    <el-aside :width="asideWidth" class="aside">
      <div class="logo">
        <el-icon class="logo-icon"><Connection /></el-icon>
        <span v-if="!isCollapse">Bridge</span>
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
            :value="unreadSms"
            :max="99"
            :hidden="unreadSms === 0"
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
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
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
// 侧栏宽度：收缩态固定 36px；展开态根据最长菜单项文字自动测量（见 measureAsideWidth）
const asideWidth = ref('220px')

function toggleCollapse() {
  isCollapse.value = !isCollapse.value
  asideWidth.value = isCollapse.value ? '36px' : measuredExpandedWidth.value
  try {
    localStorage.setItem('bridge_admin_aside_collapse', isCollapse.value ? '1' : '0')
  } catch {
    /* 隐私模式忽略 */
  }
}

// 展开态测量出的宽度（缓存），默认 220px 兜底
const measuredExpandedWidth = ref('220px')

/** 展开态下，测量所有菜单项文字的实际宽度，取最长项，加上图标宽 + 左右 padding + 余量，
 *  让侧栏宽度刚好贴合内容、内容靠左、右边无大空白。 */
function measureAsideWidth() {
  if (isCollapse.value) return
  const asideEl = document.querySelector('.aside') as HTMLElement | null
  if (!asideEl) return
  const items = asideEl.querySelectorAll<HTMLElement>('.el-menu-item')
  let maxText = 0
  items.forEach((item) => {
    const span = item.querySelector('span') as HTMLElement | null
    if (span) {
      const w = span.getBoundingClientRect().width
      if (w > maxText) maxText = w
    }
  })
  if (maxText <= 0) return
  // 展开态水平构成：margin 4 + padding 10 + 图标 24 + 图标右距 5 + 文字 + padding 10 + margin 4 = 57 + 文字；
  // 再加 10px 余量（右侧太紧，放宽一点）。
  const width = maxText + 57 + 10
  measuredExpandedWidth.value = `${width}px`
  asideWidth.value = `${width}px`
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

function notifyMediaButton(data: any) {
  console.log('[Bridge] 收到蓝牙媒体按键事件:', data)
  const actionMap: Record<string, string> = {
    '播放': '播放',
    '暂停': '暂停',
    '下一首': '下一首',
    '上一首': '上一首',
    '停止': '停止',
  }
  const action = actionMap[data.action] || data.action || '未知'
  ElNotification({
    title: `蓝牙按键 · ${data.device_id}`,
    message: `「${action}」`,
    type: 'info',
    duration: 3000,
    position: 'top-right',
  })
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
      } else if (msg.type === 'media_button') {
        notifyMediaButton(msg.data)
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
  if (isCollapse.value) {
    asideWidth.value = '36px'
  } else {
    // 展开态：等 DOM 渲染完成后测量最长菜单项宽度（nextTick + 微延迟等字体）
    nextTick(() => {
      measureAsideWidth()
      window.setTimeout(measureAsideWidth, 150)
    })
  }
  connectWS()
})

// 进入短信页即视为「已读」：清零未读角标（无需刷新页面）
watch(
  () => route.path,
  (path) => {
    if (path === '/sms') {
      unreadSms.value = 0
    }
  },
)

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
  overflow: hidden;
}
/* 收缩态：侧栏外壁左右各留 2px 空隙（与屏幕左缘、右侧内容区之间留缝） */
.aside:has(.el-menu--collapse) {
  margin: 0 2px;
}
.aside :deep(.el-menu) {
  border-right: none;
  overflow: hidden;
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
/* 展开态：logo 文字靠左，图标左缘与菜单项图标左缘对齐（menu margin4 + padding10 = 14px） */
.aside:not(:has(.el-menu--collapse)) .logo {
  justify-content: flex-start;
  padding-left: 14px;
}
.logo-icon {
  /* 与菜单项图标同字号（Element Plus 菜单图标默认 18px），
     保证收缩态下顶部 logo 图标与下方菜单图标宽度一致、垂直对齐 */
  font-size: 18px;
  color: var(--brand);
  flex-shrink: 0;
}
/* 收缩态：logo 区只显示图标，高度降为与菜单项一致（40px），避免过高的顶部空白 */
.aside:has(.el-menu--collapse) .logo {
  height: 40px;
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
.aside :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  margin: 4px 4px;
  padding: 0 10px;
  border-radius: 6px;
}
/* 覆盖 Element Plus 垂直菜单非收缩态对 padding-left 的高优先级设置（20px），
   否则左侧 padding-left 仍为 20px，导致内容不够靠左。 */
.aside :deep(.el-menu--vertical:not(.el-menu--collapse) .el-menu-item) {
  padding: 0 10px;
}
/* 收缩态：菜单宽度不再被 Element Plus 写死的 64px 卡住，改为跟随侧栏 100%；
   菜单项去掉左右 margin 和默认 padding(0 20px)，改用 justify-content:center
   让图标在侧栏内水平居中，左右留白对称、与顶部 logo 图标同一条中心线对齐。 */
.aside :deep(.el-menu--collapse) {
  width: 100%;
  padding: 0 2px;
}
.aside :deep(.el-menu--collapse .el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 4px 0;
  padding: 0;
  justify-content: center;
}
/* 收缩态：图标强制为正方形，并撑满父容器（宽度 = 侧栏宽 - 左右留白）。
   侧栏 36px、左右各留 6px → 图标 24px×24px，svg 用 1em 随字号缩放。 */
.aside :deep(.el-menu--collapse .el-menu-item .el-icon) {
  width: 24px;
  height: 24px;
  line-height: 24px;
  font-size: 24px;
  margin: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
/* 收缩态：短信角标项去掉右侧 margin，避免干扰图标居中 */
.aside :deep(.el-menu--collapse .el-menu-item .sms-badge) {
  margin-right: 0;
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
