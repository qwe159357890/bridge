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
            下载（{{ kind === 'video' ? '.ts' : kind === 'screen' ? '.flv' : '.aac' }}）
          </el-button>
          <el-button :icon="Close" size="small" circle title="关闭" @click="hide" />
        </div>
      </div>
    </template>

    <!-- 视频：mpegts.js 直播（摄像头=MPEG-2 TS / 投屏=FLV） -->
    <video
      v-show="kind !== 'audio'"
      ref="videoEl"
      class="stream-video"
      controls
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
import { computed, onBeforeUnmount, ref } from 'vue'
import { Close } from '@element-plus/icons-vue'
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
 */
function startManual(id: string, k: 'video' | 'audio' | 'screen') {
  if (visible.value && !ended.value && deviceId.value === id && kind.value === k) return
  startStream(id, k)
}

function startStream(id: string, k: 'video' | 'audio' | 'screen') {
  stopAll() // 防重入
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
    // mpegts.js 直播：直接把专用流 WS 当数据源（服务端推裸二进制帧）
    // 摄像头流=MPEG-2 TS；投屏直播=FLV（客户端 MediaCodec+FlvMuxer 打包）
    if (mpegts.getFeatureList().mseLivePlayback) {
      player = mpegts.createPlayer(
        { type: k === 'screen' ? 'flv' : 'mpegts', isLive: true, url: streamWsUrl() },
        {
          enableStashBuffer: false,
          isLive: true,
          liveBufferLatencyChasing: true,
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
    } else {
      playerError.value = '当前浏览器不支持 MSE 直播（建议 Chrome/Edge）'
    }
    // 录制累积通道：再开一条 WS 收原始字节（与播放解耦）
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
  return `${wsUrl(`/api/admin/stream/${deviceId.value}`)}?token=${encodeURIComponent(token)}`
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

/**
 * 视频健康监控（1 秒一查，三件事）：
 * ① 追帧：画面落后实时 >2.5 秒立即 seek 到缓冲末尾前 0.3 秒；
 * ② 诊断：把浏览器解码帧数（totalVideoFrames）显示到卡片——
 *    「只出首帧后冻结」时若解码帧停增即定位为 video 轨解码问题；
 * ③ 自愈：currentTime 与解码帧数连续 4 秒都不动（音流正常收帧）→
 *    自动重建播放器（服务端流头缓存保证重建后能重新初始化解码器）。
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
      // 追帧
      if (v.buffered.length > 0) {
        const tail = v.buffered.end(v.buffered.length - 1)
        if (tail - v.currentTime > 2.5 && !v.seeking && v.readyState >= 2) {
          v.currentTime = Math.max(0, tail - 0.3)
        }
      }
      // 停滞检测（画面不动 + 解码帧不增 = video 轨卡死）
      if (Math.abs(v.currentTime - lastT) < 0.05 && videoFrames.value === lastF) {
        stall++
        if (stall >= 4) {
          stall = 0
          console.warn('[StreamPlayer] 视频轨停滞 4s，重建播放器自愈')
          rebuildPlayer()
        }
      } else {
        stall = 0
      }
      lastT = v.currentTime
      lastF = videoFrames.value
    } catch {
      /* buffered/quality 访问异常忽略 */
    }
  }, 1000)
}

/** 重建播放器（流头缓存让重建后的播放器能重新初始化）。 */
function rebuildPlayer() {
  if (!player) return
  destroyPlayer()
  playerError.value = ''
  try {
    player = mpegts.createPlayer(
      { type: kind.value === 'screen' ? 'flv' : 'mpegts', isLive: true, url: streamWsUrl() },
      {
        enableStashBuffer: false,
        isLive: true,
        liveBufferLatencyChasing: true,
        lazyLoad: false,
      },
    )
    player.attachMediaElement(videoEl.value!)
    player.on(mpegts.Events.ERROR, (type: string, detail: string) => {
      playerError.value = `播放器错误（${type}）：${detail}`
    })
    player.load()
    player.play()?.catch?.(() => {})
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
  const mime = kind.value === 'video' ? 'video/mp2t' : kind.value === 'screen' ? 'video/x-flv' : 'audio/aac'
  const ext = kind.value === 'video' ? 'ts' : kind.value === 'screen' ? 'flv' : 'aac'
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
