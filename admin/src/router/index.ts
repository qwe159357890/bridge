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
      // 根路径不配 redirect：由 beforeEach 守卫在首跳时恢复「上次停留的 tab」
      // （无记忆回退 /dashboard）。若配置 redirect，守卫看到的 to 已是目标页，
      // 无法区分「打开后台首页」与「刷新/直达具体页面」。
      path: '/',
      component: () => import('@/layout/index.vue'),
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

// 登录守卫：未登录跳登录页；**首次导航**落地默认页时恢复上次停留的 tab
// （_from.matched.length===0 表示刷新/新开的首跳；不能对所有 /dashboard 导航
//  恢复——否则用户主动点「仪表盘」菜单会被永久弹回记忆页，2026-10-28 实测踩坑）
router.beforeEach((to, _from, next) => {
  const token = getToken()
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    // 登录页访问但已登录：恢复上次 tab（无记忆回退仪表盘）
    const saved = localStorage.getItem(LAST_TAB_KEY)
    next(savedTabUsable(saved) ? saved : '/dashboard')
  } else if (to.path === '/' && _from.matched.length === 0) {
    // 仅首跳落地根路径 / 时恢复（打开管理后台的入口）；刷新/直达具体页面
    // （含 /dashboard）以 URL 为准留在原地，不弹回记忆页
    const saved = localStorage.getItem(LAST_TAB_KEY)
    next(saved && savedTabUsable(saved) ? saved : '/dashboard')
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
