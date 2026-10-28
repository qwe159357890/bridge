import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/api/request'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layout/index.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/dashboard/index.vue'),
          meta: { title: '仪表盘' },
        },
        {
          path: 'device',
          name: 'device',
          component: () => import('@/views/device/index.vue'),
          meta: { title: '设备管理' },
        },
        {
          path: 'command',
          name: 'command',
          component: () => import('@/views/command/index.vue'),
          meta: { title: '命令下发' },
        },
        {
          path: 'log',
          name: 'log',
          component: () => import('@/views/log/index.vue'),
          meta: { title: '日志查看' },
        },
        {
          path: 'push',
          name: 'push',
          component: () => import('@/views/push/index.vue'),
          meta: { title: '消息推送' },
        },
        {
          path: 'contacts',
          name: 'contacts',
          component: () => import('@/views/contacts/index.vue'),
          meta: { title: '通讯录' },
        },
        {
          path: 'sms',
          name: 'sms',
          component: () => import('@/views/sms/index.vue'),
          meta: { title: '短信' },
        },
        {
          path: 'calllog',
          name: 'calllog',
          component: () => import('@/views/calllog/index.vue'),
          meta: { title: '通话记录' },
        },
        {
          path: 'media',
          name: 'media',
          component: () => import('@/views/media/index.vue'),
          meta: { title: '媒体文件' },
        },
        {
          path: 'connection',
          name: 'connection',
          component: () => import('@/views/connection/index.vue'),
          meta: { title: '连接监控' },
        },
      ],
    },
  ],
})

// ===== Tab 记忆（2026-10-28 用户需求）：记住该浏览器上次停留的页面，下次
// 打开/刷新自动跳回。localStorage 天然按「浏览器+电脑」隔离（用户拍板用
// localStorage 而非真实浏览器指纹技术），Chrome/Edge 各记各的互不干扰。 =====
const LAST_TAB_KEY = 'bridge_admin_last_tab'

/** 校验记忆的路径可安全跳转（路由存在且非登录页）。 */
function savedTabUsable(saved: string | null): saved is string {
  if (!saved || !saved.startsWith('/') || saved === '/login') return false
  try {
    return router.resolve(saved).matched.length > 0
  } catch {
    return false
  }
}

// 登录守卫：未登录跳登录页；登录后/首次落地默认页时恢复上次停留的 tab
router.beforeEach((to, _from, next) => {
  const token = getToken()
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    // 登录页访问但已登录：恢复上次 tab（无记忆回退仪表盘）
    const saved = localStorage.getItem(LAST_TAB_KEY)
    next(savedTabUsable(saved) ? saved : '/dashboard')
  } else if (to.path === '/' || to.path === '/dashboard') {
    // 刷新/新开落在默认落地页时恢复；用户主动点其它菜单（to 非默认页）不拦
    const saved = localStorage.getItem(LAST_TAB_KEY)
    next(saved && saved !== to.path && savedTabUsable(saved) ? saved : to.path)
  } else {
    next()
  }
})

// 更新页面标题 + 记录本次停留的 tab（登录页不记）
router.afterEach((to) => {
  if (to.path !== '/login') {
    try {
      localStorage.setItem(LAST_TAB_KEY, to.path)
    } catch {
      /* 隐私模式等写入失败静默忽略 */
    }
  }
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - Bridge 管理后台` : 'Bridge 管理后台'
})

export default router
