import { getAmapConfig } from '@/api/locations'

/**
 * 高德地图 JS API 动态加载器。
 *
 * Key 从服务端 /api/locations/amap-config 动态获取（配置在服务端
 * config.local.yaml，不进前端代码）；SDK 全局只加载一次。
 */
let loader: Promise<any> | null = null

export function loadAmap(): Promise<any> {
  if (loader) return loader
  loader = (async () => {
    if ((window as any).AMap) return (window as any).AMap
    const cfg = await getAmapConfig()
    // 2021-12 之后申请的 Key 需要安全密钥，必须在 SDK 脚本之前设置
    if (cfg.security_js_code) {
      ;(window as any)._AMapSecurityConfig = { securityJsCode: cfg.security_js_code }
    }
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(cfg.key)}`
      script.onload = () => resolve()
      script.onerror = () => reject(new Error('高德地图脚本加载失败'))
      document.head.appendChild(script)
    })
    return (window as any).AMap
  })()
  // 加载失败时清除缓存，允许下次重试
  loader.catch(() => {
    loader = null
  })
  return loader
}
