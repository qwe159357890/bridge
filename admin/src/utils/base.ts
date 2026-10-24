// 后端地址统一处理：开发态走 Vite 代理，生产态用 VITE_API_BASE 直连
// 这样管理后台与后端在同一台机器（开发）或不同服务器（部署）都能工作。

// 生产环境通过 .env.production 的 VITE_API_BASE 指定后端地址，
// 例如：VITE_API_BASE=http://192.168.31.226:15160
// 为空时表示开发态，HTTP 走 axios baseURL 相对路径（Vite 代理），WS 走当前 origin。

/** HTTP 请求的 baseURL（axios 用） */
export function apiBase(): string {
  return import.meta.env.VITE_API_BASE || ''
}

/** WebSocket 地址：根据 HTTP base 推导出 ws/wss 协议 */
export function wsUrl(path: string): string {
  const base = import.meta.env.VITE_API_BASE || ''
  try {
    const u = new URL(base, window.location.origin)
    u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:'
    // 保留 base 里可能的路径前缀，再拼上目标 path
    u.pathname = `${u.pathname.replace(/\/$/, '')}${path}`
    u.search = ''
    u.hash = ''
    return u.toString()
  } catch {
    // base 非法时退化为相对当前 origin
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${proto}://${window.location.host}${path}`
  }
}
