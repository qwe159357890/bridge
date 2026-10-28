import { defineStore } from 'pinia'

/**
 * 全局「目标设备」（2026-10-28 用户需求）：
 * 跨页面保持——命令下发/通话记录/联系人等页面共享同一目标设备；
 * 只有用户**主动切换**才改变，切换页面/刷新页面都不丢（localStorage 持久化）。
 */
export const useTargetDeviceStore = defineStore('targetDevice', {
  state: () => ({
    deviceId: localStorage.getItem('bridge_target_device') || '',
  }),
  actions: {
    set(id: string) {
      this.deviceId = id || ''
      if (id) {
        localStorage.setItem('bridge_target_device', id)
      } else {
        localStorage.removeItem('bridge_target_device')
      }
    },
  },
})
