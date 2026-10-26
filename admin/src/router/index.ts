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

// 登录守卫：未登录跳登录页
router.beforeEach((to, _from, next) => {
  const token = getToken()
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

// 更新页面标题
router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - Bridge 管理后台` : 'Bridge 管理后台'
})

export default router
