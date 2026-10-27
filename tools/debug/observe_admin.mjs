/**
 * 真实管理后台观察脚本：登录 5173 Vite → 命令页选设备/命令 → 下发 screen_live
 * → 逐 300ms 采样 .stream-video 状态 + 捕获播放器事件与 console。
 * 用法：node observe_admin.mjs <token> [观察秒数=24]
 */
import { chromium } from './.pwenv/node_modules/playwright/index.mjs'

const token = process.argv[2] || ''
const watchSec = Number(process.argv[3] || 24)

const browser = await chromium.launch({
  channel: 'chrome',
  headless: false, // 有头：用户可肉眼同步观察画面卡顿
  args: ['--autoplay-policy=no-user-gesture-required'],
})
const page = await browser.newPage({ viewport: { width: 1600, height: 950 } })
const logs = []
page.on('console', (m) => logs.push(`[console.${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))

// 预置 token 免登录（注意：不能写 ([t]) => 解构——字符串参数会被拆成首字符）
await page.addInitScript(
  (t) => localStorage.setItem('bridge_admin_token', t),
  token,
)
await page.goto('http://localhost:5173/command', { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.command-card', { timeout: 15000 })
await page.waitForTimeout(1000)

// 1. 选目标设备
const picker = page.locator('.el-select:has-text("选择在线设备")').first()
await picker.click()
await page.waitForTimeout(800)
await page.locator('.el-select-dropdown__item:has-text("一加 9Plus")').first().click()
await page.waitForTimeout(500)
console.log('设备已选择')

// 2. 切到音视频分类，搜索并选中命令
await page.locator('li:has-text("音视频"), [class*="category"]:has-text("音视频")').last().click()
await page.waitForTimeout(600)
await page.getByPlaceholder('搜索命令名称 / 说明…').fill('投屏直播')
await page.waitForTimeout(600)
await page.locator('.command-card:has-text("投屏直播")').first().click()
await page.waitForTimeout(600)
console.log('命令已选中')

// 3. 注入视频观察器（等卡片 video 出现后挂钩）
await page.evaluate(() => {
  window.__events = []
  window.__samples = []
  const ev = (t, x) => window.__events.push({ t: Date.now(), e: t, x: x ?? '' })
  // TimeRanges 不可 spread（不可迭代），用下标转换
  const bufs = (v) =>
    Array.from({ length: v.buffered.length }, (_, i) => [
      +v.buffered.start(i).toFixed(2),
      +v.buffered.end(i).toFixed(2),
    ])
  const hook = () => {
    const v = document.querySelector('video.stream-video')
    if (!v || v.__hooked) return false
    v.__hooked = true
    for (const name of ['waiting', 'stalled', 'playing', 'seeking', 'seeked', 'pause', 'suspend', 'emptied', 'abort']) {
      v.addEventListener(name, () => ev(name, `cur=${v.currentTime.toFixed(2)} buf=${JSON.stringify(bufs(v))}`))
    }
    ev('hooked')
    return true
  }
  window.__hook = hook
  const mo = new MutationObserver(() => hook())
  mo.observe(document.body, { childList: true, subtree: true })
  hook()
  // 采样器
  window.__sampler = setInterval(() => {
    const v = document.querySelector('video.stream-video')
    if (!v) return
    const q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : {}
    window.__samples.push({
      t: Date.now(),
      cur: +v.currentTime.toFixed(2),
      rate: v.playbackRate,
      ready: v.readyState,
      paused: v.paused,
      frames: q.totalVideoFrames ?? -1,
      dropped: q.droppedVideoFrames ?? -1,
      buf: bufs(v),
    })
  }, 300)
})

// 4. 下发
await page.locator('button:has-text("下发命令")').first().click()
console.log('已点击下发，开始观察…')

// 5. 观察窗口
await page.waitForTimeout(watchSec * 1000)

const out = await page.evaluate(() => ({
  events: window.__events,
  samples: window.__samples,
}))
console.log('=== 播放器事件 ===')
for (const e of out.events) console.log(new Date(e.t).toISOString().slice(11, 23), e.e, e.x)
console.log('=== 采样（300ms，压缩显示：仅变化点） ===')
let prev = ''
for (const s of out.samples) {
  const line = `cur=${s.cur} rate=${s.rate} ready=${s.ready} paused=${s.paused} f=${s.frames} drop=${s.dropped} buf=${JSON.stringify(s.buf)}`
  if (line !== prev) {
    console.log(new Date(s.t).toISOString().slice(11, 23), line)
    prev = line
  }
}
console.log('=== 页面 console（最近 40 条） ===')
for (const l of logs.slice(-40)) console.log(l)

await browser.close()
