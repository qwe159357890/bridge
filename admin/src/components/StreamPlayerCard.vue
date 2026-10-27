<template>
  <el-card v-if="visible" class="stream-card">
    <template #header>
      <div class="stream-header">
        <span class="title">
          📡 实时{{ kind === 'video' ? '画面' : kind === 'screen' ? '屏幕' : '声音' }}{{ deviceName ? ` - ${deviceName}` : '' }}
        </span>
        <div class="stream-header-right">
          <el-tag :type="ended ? 'info' : 'success'" size="small">
            {{ ended ? '已结束' : '直播中' }}
          </el-tag>
          <span class="stream-stats">{{ statsText }}</span>
          <el-button v-if="ended && recorded.length > 0" size="small" type="primary" @click="download">
            下载（{{ kind === 'audio' ? '.aac' : '.flv' }}）
          </el-button>
          <el-button
            v-if="kind !== 'audio'"
            :icon="FullScreen"
            size="small"
            circle
            title="全屏"
            @click="goFullscreen"
          />
          <el-button :icon="Close" size="small" circle title="关闭" @click="hide" />
        </div>
      </div>
    </template>

    <!-- 视频：mpegts.js 直播（摄像头=MPEG-2 TS / 投屏=FLV）。
         不用原生 controls：Chrome 在 waiting（缓冲耗尽，如投屏画面静止）时
         会显示旋转 spinner + 时间停走，误导「加载中/卡死」（2026-10-27 用户
         反馈）。直播进度条无意义，去掉后静止期就安静显示最后一帧。 -->
    <video
      v-show="kind !== 'audio'"
      ref="videoEl"
      class="stream-video"
      autoplay
      muted
      playsinline
    />
    <div v-if="kind !== 'audio' && playBlocked" class="stream-resume">
      <el-button type="primary" size="small" @click="resumeVideo">点击播放</el-button>
    </div>
    <el-alert
      v-if="playerError"
      :title="playerError"
      type="error"
      :closable="false"
      style="margin-top: 8px"
    />

    <!-- 音频：Web Audio 实时出声（无可视化，用状态文案替代） -->
    <div v-if="kind === 'audio'" class="stream-audio">
      <el-button v-if="!audioStarted && !ended" type="primary" @click="startAudio">
        ▶ 开始收听
      </el-button>
      <span v-if="audioStarted && !ended" class="audio-live">🔊 收听中…（浏览器自动播放被拦截时点此按钮恢复）</span>
    </div>

    <div class="stream-tip">
      {{ ended ? '流已结束；完整文件正在按正常流程上传，可在「媒体文件」页或命令回执中查看。' : '延迟约 1~2 秒；视频首次可能被浏览器静音自动播放策略限制，点击视频或下方按钮恢复声音。' }}
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { Close, FullScreen } from '@element-plus/icons-vue'
import mpegts from 'mpegts.js'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'

/**
 * 流式采集实时播放卡片（命令下发页用）。
 *
 * 数据通路：客户端流式分片 → 服务端 StreamHub → 本组件专用流 WS
 * （/api/admin/stream/{device_id}，二进制帧）：
 * - 视频：mpegts.js 以该 WS 为直播源直接播（MPEG-2 TS 原生支持），
 *   另开一条同源 WS 累积原始 TS 字节用于结束后下载；
 * - 音频：本组件自己持有 WS，按 ADTS 帧边界切分 → Web Audio decodeAudioData
 *   逐段解码 + 播放时钟调度实时出声（延迟约 1~2 秒），并累积 .aac 下载。
 *
 * 父页 WS 只传轻量控制事件（stream_status 起止），分片重负载不走父页通道。
 */
const props = defineProps<{ deviceNames: Record<string, string> }>()

const visible = ref(false)
const deviceId = ref('')
const kind = ref<'video' | 'audio' | 'screen'>('video')
const ended = ref(false)
const videoEl = ref<HTMLVideoElement>()
const playBlocked = ref(false)
const audioStarted = ref(false)
const playerError = ref('') // 播放器/流通道错误提示（不再静默黑屏）

// 统计
const chunkCount = ref(0)
const totalBytes = ref(0)
const startedAt = ref(0)
const videoFrames = ref(0) // 视频解码帧数（诊断：画面冻结时看是否停增）
const statsText = computed(() => {
  const secs = startedAt.value ? Math.round((Date.now() - startedAt.value) / 1000) : 0
  const mb = (totalBytes.value / 1048576).toFixed(1)
  return `${secs}s · ${chunkCount.value} 片 · ${mb}MB · 解码帧 ${videoFrames.value}`
})

// 资源句柄
let player: any = null // mpegts.js 播放器（视频）
let playWs: WebSocket | null = null // mpegts 直播源 WS（视频）
let recWs: WebSocket | null = null // 录制累积 WS（视频）/ 数据 WS（音频）
let audioCtx: AudioContext | null = null
let audioNextTime = 0 // Web Audio 播放时钟
let adtsBuf = new Uint8Array(0) // ADTS 帧切分缓冲
let latencyTimer: number | null = null // 视频延迟监控（追帧自愈）
let sessionKey = '' // 本次会话 (device:kind)，防僵尸 pending 反复重启（见 startManual）
let lastStartAt = 0 // 上次 startStream 时刻（startManual 冷却用）
let lastChunkAt = 0 // 最近一次流分片到达时刻（区分「上游无帧」与「解码停滞」）
let lastRebuildAt = 0 // 上次播放器重建时刻（重建冷却，防 4.5s 刷屏式重建）
const recorded = ref<BlobPart[]>([]) // 原始字节累积（下载用，响应式供下载按钮显隐）
let blobUrl = ''

defineExpose({ onStreamStatus, startManual })

const deviceName = computed(() => props.deviceNames[deviceId.value] || deviceId.value)

/** 父页转发 stream_status 事件入口（分片不经过父页）。 */
function onStreamStatus(d: {
  device_id: string
  kind: 'video' | 'audio' | 'screen'
  is_end: boolean
  info?: Record<string, any> | null
}) {
  if (!d.is_end) {
    // 新流开始：已结束/未显示/设备或类型变化 都要重启
    // （v0.1.53 修复：旧条件 !visible.value 漏掉「已结束后再次直播」→ 卡片停在已结束 0 片）
    if (!visible.value || ended.value || deviceId.value !== d.device_id || kind.value !== d.kind) {
      startStream(d.device_id, d.kind)
    }
  } else {
    // 保护：刚启动 2 秒内且没收到任何分片的结束帧 = 旧流残尾，忽略
    if (Date.now() - startedAt.value < 2000 && chunkCount.value === 0) return
    endStream()
  }
}

/**
 * 手动预启动（命令下发成功即调用）：不再依赖 stream_status 事件。
 * 流通道无流时静默保持，分片到达即播放——规避管理 WS 短暂断线/
 * 事件丢失导致「整段录制无实时画面」（2026-10-27 实测缺陷）。
 *
 * 两级守卫（2026-10-27 追加，根治「日志每 4.5s 刷一条订阅」）：
 * - 活跃会话幂等（原有）；
 * - 同 (device:kind) 已结束的会话 60s 内不自动重启——silentRefresh 每 3s
 *   轮询，15 分钟窗口内的 pending 流式命令会让 ended 卡片被反复重启，
 *   每次重建两条流 WS（服务端日志刷屏 + 无意义直连订阅）。
 */
function startManual(id: string, k: 'video' | 'audio' | 'screen') {
  if (visible.value && !ended.value && deviceId.value === id && kind.value === k) return
  const key = `${id}:${k}`
  if (key === sessionKey && Date.now() - lastStartAt < 60000) return
  startStream(id, k)
}

function startStream(id: string, k: 'video' | 'audio' | 'screen') {
  stopAll() // 防重入
  sessionKey = `${id}:${k}`
  lastStartAt = Date.now()
  lastChunkAt = 0
  deviceId.value = id
  kind.value = k
  ended.value = false
  chunkCount.value = 0
  totalBytes.value = 0
  startedAt.value = Date.now()
  recorded.value = []
  playerError.value = ''
  videoFrames.value = 0
  visible.value = true
  // 诊断期：mpegts 内部日志打 console（用户可 F12 取证）；类型未声明用运行时调用
  try {
    ;(mpegts.LoggingControl as any).enableAll?.()
  } catch {
    /* 忽略 */
  }

  if (k !== 'audio') {
    // ⚠️ 必须等 nextTick：页面刚加载后首次预连时 visible 刚置 true，
    // video 元素还没渲染出来，同步访问 videoEl 是 null → attachMediaElement
    // 抛异常被调用方吞掉 → 流 WS 根本不建立（2026-10-27「投屏首次无实时画面」根因）
    nextTick(() => {
      // 会话守卫：期间可能已被 stopAll/新 startStream 重置，过期回调直接丢弃
      if (!visible.value || ended.value || deviceId.value !== id || kind.value !== k) return
      if (mpegts.getFeatureList().mseLivePlayback) {
        createVideoPlayer()
      } else {
        playerError.value = '当前浏览器不支持 MSE 直播（建议 Chrome/Edge）'
      }
    })
    // 录制累积通道：再开一条 WS 收原始字节（与播放解耦，无 DOM 依赖可立即连）
    recWs = new WebSocket(streamWsUrl())
    recWs.binaryType = 'arraybuffer'
    recWs.onerror = () => {
      playerError.value = playerError.value || '流通道连接失败（检查服务器地址与登录状态）'
    }
    recWs.onmessage = (ev) => {
      if (ev.data instanceof ArrayBuffer) {
        chunkCount.value++
        totalBytes.value += ev.data.byteLength
        recorded.value.push(ev.data)
        lastChunkAt = Date.now()
      }
    }
  } else {
    // 音频：组件自持 WS，ADTS 切帧 → Web Audio
    recWs = new WebSocket(streamWsUrl())
    recWs.binaryType = 'arraybuffer'
    recWs.onerror = () => {
      playerError.value = playerError.value || '音频流通道连接失败（检查服务器地址与登录状态）'
    }
    recWs.onmessage = (ev) => {
      if (!(ev.data instanceof ArrayBuffer)) return
      chunkCount.value++
      totalBytes.value += ev.data.byteLength
      recorded.value.push(ev.data)
      if (audioStarted.value && audioCtx) feedAdts(new Uint8Array(ev.data.slice(0)))
    }
  }
}

function streamWsUrl(): string {
  const token = getToken() || ''
  // kind 参数：服务端按 (device, kind) 取 FLV 对齐器，中途加入/重建
  // 订阅时从「流头 + 最近关键帧」无缝接入（无对齐器=audio 直连）
  return `${wsUrl(`/api/admin/stream/${deviceId.value}`)}?kind=${kind.value}&token=${encodeURIComponent(token)}`
}

/** 音频开始收听（用户手势触发，规避自动播放限制）。 */
function startAudio() {
  if (!audioCtx) audioCtx = new AudioContext()
  audioCtx.resume()
  audioNextTime = 0
  adtsBuf = new Uint8Array(0)
  audioStarted.value = true
}

/** 点击播放（浏览器自动播放被拦时）。 */
function resumeVideo() {
  playBlocked.value = false
  try {
    videoEl.value?.play()
    if (videoEl.value) videoEl.value.muted = false
  } catch {
    /* 忽略 */
  }
}

/** 创建 mpegts.js 播放器并挂到 video 元素（须在 nextTick 后调用）。 */
function createVideoPlayer() {
  // 统一 FLV：摄像头流由服务端 TS→FLV 转封装（Android MediaMuxer 的 TS
  // 时间戳缺陷会让 mpegts.js 解码停滞，2026-10-27 浏览器复现根因），投屏=FLV
  player = mpegts.createPlayer(
    { type: 'flv', isLive: true, url: streamWsUrl() },
    {
      enableStashBuffer: false,
      isLive: true,
      liveBufferLatencyChasing: false, // 追帧由 startLatencyWatch 平滑变速处理（内建是跳变式 seek）
      lazyLoad: false,
    },
  )
  player.attachMediaElement(videoEl.value!)
  player.on(mpegts.Events.ERROR, (type: string, detail: string) => {
    playerError.value = `播放器错误（${type}）：${detail} —— 请确认设备正在推流、未被我防火墙/代理拦截`
    console.error('[StreamPlayer] mpegts error:', type, detail)
  })
  player.load()
  player.play()?.catch?.(() => {
    playBlocked.value = true
  })
  startLatencyWatch()
}

/**
 * 视频健康监控（500ms 一查，四件事）：
 * ① 缓冲垫管理：维持 0.4~2s 缓冲垫——垫子被网络抖动耗干是「周期性卡顿」
 *    的主因（冻住→来数据→恢复循环）；<0.4s 降到 0.85 倍速重建垫子，
 *    >2s 1.5 倍速消化，>8s 才硬 seek 兜底（跳变式 seek 已弃用）；
 * ② 间隙跳跃：时间轴空洞（屏幕静止无帧/源 dts 跳变）处等待无意义，
 *    卡住 1 秒即 seek 跨到下一段缓冲；
 * ③ 诊断：把浏览器解码帧数（totalVideoFrames）显示到卡片——
 *    「只出首帧后冻结」时若解码帧停增即定位为 video 轨解码问题；
 * ④ 重建兜底：无洞可跳仍卡 4s → 自动重建播放器（流头缓存保证可重新初始化）。
 */
function startLatencyWatch() {
  stopLatencyWatch()
  let lastT = -1
  let lastF = -1
  let stall = 0
  latencyTimer = window.setInterval(() => {
    const v = videoEl.value
    if (!v || !player || ended.value) return
    try {
      // 诊断帧数
      const q = v.getVideoPlaybackQuality?.()
      videoFrames.value = q ? q.totalVideoFrames : Math.round(v.currentTime * 30)
      // 平滑追帧 v3：维持 1.2~3.5s 缓冲垫。40s 实测（2026-10-27）：上游分片
      // 突发到达（设备端推流节奏不均），断供可达 ~1s——旧垫子 0.4s 必被吃干
      // （waiting 时 lag=0.02s），这就是残余卡顿的来源。垫厚 1.2s 可扛 1s 断供，
      // 代价是直播延迟多约 1s（可接受）。
      if (v.buffered.length > 0 && !v.seeking && v.readyState >= 2) {
        const tail = v.buffered.end(v.buffered.length - 1)
        const lag = tail - v.currentTime
        if (lag > 8) {
          v.currentTime = Math.max(0, tail - 1.2)
          v.playbackRate = 1.0
        } else if (lag > 3.5) {
          v.playbackRate = 1.5
        } else if (lag < 1.2) {
          v.playbackRate = 0.85
        } else {
          v.playbackRate = 1.0
        }
      }
      // 停滞检测（画面不动 + 解码帧不增 = video 轨卡死）。
      // 先排除「上游本来就没帧」：屏幕静止时设备少产帧/无帧，解码帧不增是
      // 正常现象——分片超过 2s 没到就重置 stall（2026-10-27：之前屏幕一静止
      // 就被误判停滞 → 4s 重建 → 关键帧对齐等待 → 用户看到周期性卡顿）。
      if (Math.abs(v.currentTime - lastT) < 0.05 && videoFrames.value === lastF) {
        if (Date.now() - lastChunkAt > 2000) {
          stall = 0
        } else {
          stall++
        }
        // 间隙跳跃：时间轴存在内容空洞（屏幕静止无帧/源 dts 跳变）时
        // currentTime 停在空洞左缘，实时数据只会填到洞右缘——等待无意义，
        // 卡住 1 秒即 seek 跨到下一段缓冲（空洞处本来就没有画面可放）。
        let jumped = false
        if (stall >= 2 && v.buffered.length > 1) {
          for (let i = 0; i < v.buffered.length - 1; i++) {
            if (v.currentTime >= v.buffered.end(i) - 0.05) {
              const next = v.buffered.start(i + 1)
              if (next > v.currentTime + 0.1) {
                console.warn(`[StreamPlayer] 跳过时间轴空洞 ${v.currentTime.toFixed(2)}→${next.toFixed(2)}`)
                v.currentTime = next + 0.05
                v.playbackRate = 1.0
                stall = 0
                jumped = true
                break
              }
            }
          }
        }
        // 冻结即时追尾（2026-10-27 投屏静止卡顿根治）：屏幕静止期设备低帧率
        // 出帧，数据到达速率≈播放消耗 → 垫子被耗干、播放头停住，旧逻辑要等
        // lag 攒到 8s 才硬 seek（实测冻结 7s 后猛跳 = 用户看到的卡顿）。
        // 画面本来就静止，跳变无感——垫子攒回 1.5s 即轻推到近实时恢复跟踪。
        if (!jumped && stall >= 2 && v.readyState < 3 && v.buffered.length > 0) {
          const tail = v.buffered.end(v.buffered.length - 1)
          if (tail - v.currentTime > 1.5) {
            console.warn(
              `[StreamPlayer] 画面冻结 ${v.currentTime.toFixed(2)}，追尾到 ${(tail - 1.2).toFixed(2)}`,
            )
            v.currentTime = tail - 1.2
            v.playbackRate = 1.0
            stall = 0
          }
        }
        // 重建兜底：无洞可跳仍卡 4s → 重建播放器（流头缓存保证可重新初始化）。
        // 冷却 12s：重建=断流重连+关键帧对齐等待，本身就是一次画面中断；
        // 无冷却时「停滞→重建→接入开销→又停滞」每 4.5s 循环刷屏。
        if (stall >= 8) {
          stall = 0
          if (Date.now() - lastRebuildAt > 12000) {
            lastRebuildAt = Date.now()
            console.warn('[StreamPlayer] 视频轨停滞 4s，重建播放器自愈')
            rebuildPlayer()
          }
        }
      } else {
        stall = 0
      }
      lastT = v.currentTime
      lastF = videoFrames.value
    } catch {
      /* buffered/quality 访问异常忽略 */
    }
  }, 500)
}

/** 重建播放器（流头缓存让重建后的播放器能重新初始化）。 */
function rebuildPlayer() {
  if (!player) return
  destroyPlayer()
  playerError.value = ''
  try {
    createVideoPlayer()
  } catch (e) {
    console.error('[StreamPlayer] 重建失败', e)
  }
}

function stopLatencyWatch() {
  if (latencyTimer !== null) {
    window.clearInterval(latencyTimer)
    latencyTimer = null
  }
}

// ---- ADTS 帧切分 + Web Audio 调度 ----

/** 把新到的字节并入缓冲，切出完整 ADTS 帧逐组解码播放。 */
function feedAdts(chunk: Uint8Array) {
  const merged = new Uint8Array(adtsBuf.length + chunk.length)
  merged.set(adtsBuf)
  merged.set(chunk, adtsBuf.length)
  adtsBuf = merged
  const frames: Uint8Array[] = []
  while (true) {
    const sync = findAdtsSync(adtsBuf)
    if (sync < 0 || adtsBuf.length < sync + 7) break
    const frameLen =
      ((adtsBuf[sync + 3] & 0x03) << 11) | (adtsBuf[sync + 4] << 3) | (adtsBuf[sync + 5] >> 5)
    if (frameLen < 7) {
      // 非法帧长：跳过坏同步字节继续找
      adtsBuf = adtsBuf.subarray(sync + 1)
      continue
    }
    if (adtsBuf.length < sync + frameLen) break
    frames.push(adtsBuf.slice(sync, sync + frameLen))
    adtsBuf = adtsBuf.subarray(sync + frameLen)
  }
  if (frames.length === 0) return
  // 组帧解码：每 16 帧一组（约 1/3 秒），兼顾延迟与解码次数
  const group = new Uint8Array(frames.reduce((n, f) => n + f.length, 0))
  let off = 0
  for (const f of frames) {
    group.set(f, off)
    off += f.length
  }
  decodeAndPlay(group.buffer.slice(0))
}

function findAdtsSync(buf: Uint8Array): number {
  for (let i = 0; i + 1 < buf.length; i++) {
    if (buf[i] === 0xff && (buf[i + 1] & 0xf0) === 0xf0) return i
  }
  return -1
}

/** 解码一组 ADTS 帧并按播放时钟无缝调度（jitter buffer 150ms）。 */
function decodeAndPlay(ab: ArrayBuffer) {
  const ctx = audioCtx
  if (!ctx) return
  ctx.decodeAudioData(ab, (buffer) => {
    if (!audioCtx) return
    const base = Math.max(audioNextTime, audioCtx.currentTime + 0.15)
    const src = audioCtx.createBufferSource()
    src.buffer = buffer
    src.connect(audioCtx.destination)
    src.start(base)
    audioNextTime = base + buffer.duration
  })
}

/** 流结束：停播放器、出下载链接。 */
function endStream() {
  ended.value = true
  stopLatencyWatch()
  destroyPlayer()
  if (recWs) {
    recWs.close()
    recWs = null
  }
  if (audioCtx) {
    audioCtx.close()
    audioCtx = null
    audioStarted.value = false
  }
}

/** 下载累积的原始流文件（TS / FLV / AAC ADTS，可直接播放或转封装）。 */
function download() {
  if (recorded.value.length === 0) return
  if (blobUrl) URL.revokeObjectURL(blobUrl)
  const mime = kind.value === 'audio' ? 'audio/aac' : 'video/x-flv'
  const ext = kind.value === 'audio' ? 'aac' : 'flv'
  const blob = new Blob(recorded.value, { type: mime })
  blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = `stream_${deviceId.value}_${new Date()
    .toISOString()
    .replace(/[-:T]/g, '')
    .slice(0, 14)}.${ext}`
  a.click()
}

function hide() {
  visible.value = false
  ended.value = true
  sessionKey = '' // 主动关闭清除会话冷却：用户立即重发同路命令不应被拦
  stopAll()
}

function stopAll() {
  stopLatencyWatch()
  destroyPlayer()
  if (playWs) {
    playWs.close()
    playWs = null
  }
  if (recWs) {
    recWs.close()
    recWs = null
  }
  if (audioCtx) {
    audioCtx.close()
    audioCtx = null
    audioStarted.value = false
  }
  if (blobUrl) {
    URL.revokeObjectURL(blobUrl)
    blobUrl = ''
  }
}

function destroyPlayer() {
  if (player) {
    try {
      player.destroy()
    } catch {
      /* 忽略 */
    }
    player = null
  }
}

onBeforeUnmount(stopAll)
</script>

<style scoped>
.stream-card {
  margin-top: 16px;
}
.stream-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stream-header .title {
  font-weight: 600;
}
.stream-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stream-stats {
  font-size: 12px;
  color: #909399;
}
.stream-video {
  width: 100%;
  max-height: 420px;
  border-radius: 6px;
  background: #000;
}
.stream-resume {
  text-align: center;
  margin-top: 8px;
}
.stream-audio {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 0;
  justify-content: center;
}
.audio-live {
  font-size: 13px;
  color: #67c23a;
}
.stream-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 8px;
}
</style>
