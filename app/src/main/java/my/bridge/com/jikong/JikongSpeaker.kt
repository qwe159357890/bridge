package my.bridge.com.jikong

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 极空语音播报（v0.2.88 引入；v0.2.90 升级为「在线合成优先 + 本地回退」两级链路）：
 *
 * 1. **在线优先（v0.2.90，用户拍板）**：请求服务端 `GET /api/tts`（edge-tts 合成，
 *    音色 [JikongPrefs.TTS_VOICE]，合成音量拉满 + 服务端响度归一化，人声远好于内置引擎）。
 *    本地缓存 `cacheDir/tts/{md5(text+voice)}.mp3`（与微信头像/缩略图缓存同范式），
 *    同一文案只请求一次；缓存 LRU 上限 [CACHE_LIMIT_BYTES]（50MB）。
 * 2. **本地回退**：离线 / 服务端不可达 / 合成失败 → 回退长驻系统 TTS 引擎（不违反
 *    必可达硬约束：服务器挂了播报能力依然在）。
 *
 * 本地引擎为**长驻单例**：首次播报惰性初始化，之后复用（播报是骑行/查看数据时的即时
 * 反馈，不能容忍 1~3 秒初始化延迟）。精简 ROM 缺引擎时静默失败（记日志），不影响其它功能。
 *
 * 播放走 STREAM_MUSIC（USAGE_MEDIA + CONTENT_TYPE_SPEECH，用户拍板），mp3 用
 * MediaPlayer 播放；同一时刻只保留一个播放实例（新播报停掉旧的，对齐 TTS QUEUE_FLUSH 语义）。
 */
object JikongSpeaker {

    private const val TAG = "JikongSpeaker"

    /** TTS 缓存目录（cacheDir 下，与微信媒体缓存同范式）。 */
    private const val CACHE_DIR = "tts"

    /** 缓存 LRU 上限（用户拍板 50MB），超限按最后访问时间淘汰。 */
    private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var apiClient: BridgeApiClient? = null

    @Volatile
    private var deviceStore: DeviceStore? = null

    // ---- 在线播放器 ----
    private var player: MediaPlayer? = null

    // ---- 本地 TTS（回退链路） ----
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** 应用启动时注入（BridgeApplication.onCreate，与 JikongStatsSync.attach 同处）。 */
    fun attach(apiClient: BridgeApiClient, deviceStore: DeviceStore) {
        this.apiClient = apiClient
        this.deviceStore = deviceStore
    }

    /** 播报一段文字（异步，立即返回）：在线缓存/合成优先，失败回退本地引擎。 */
    @Synchronized
    fun speak(context: Context, text: String) {
        val app = context.applicationContext
        scope.launch {
            val file = fetchOnline(app, text)
            if (file != null && playMp3(file)) return@launch
            localSpeak(app, text)
        }
    }

    // ==================== 在线链路（服务端 edge-tts + 本地缓存） ====================

    /** 取在线语音：缓存命中直接返回；未命中请求服务端落盘。任何失败返回 null（走本地回退）。 */
    private suspend fun fetchOnline(app: Context, text: String): File? {
        val api = apiClient ?: return null
        val ds = deviceStore ?: return null
        return try {
            val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
            val key = md5("$text|${JikongPrefs.TTS_VOICE}")
            val f = File(dir, "$key.mp3")
            if (f.exists() && f.length() > 256) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
            val body = api.synthTts(
                text, JikongPrefs.TTS_VOICE,
                ds.deviceId(), runCatching { ds.token() }.getOrNull(),
            )
            val tmp = File(dir, "$key.tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 256) {   // 异常小文件（空 mp3/错误页）不进缓存
                tmp.delete()
                return null
            }
            tmp.renameTo(f)
            pruneCache(dir)
            Timber.tag(TAG).i("在线 TTS 缓存落盘：%s（%d B）", f.name, f.length())
            f
        } catch (e: Exception) {
            Timber.tag(TAG).w("在线 TTS 不可达，回退本地引擎：%s", e.message)
            null
        }
    }

    /** 缓存 LRU 淘汰：总量超 [CACHE_LIMIT_BYTES] 时按最后访问时间从旧到新删。 */
    private fun pruneCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT_BYTES) return
            total -= f.length()
            runCatching { f.delete() }
            Timber.tag(TAG).i("TTS 缓存 LRU 淘汰：%s", f.name)
        }
    }

    /** 播放 mp3（IO 线程调用）。返回 false = 播放失败（调用方回退本地引擎）。 */
    private fun playMp3(file: File): Boolean = runCatching {
        player?.let { p -> runCatching { p.stop(); p.release() } }
        val p = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // STREAM_MUSIC（用户拍板）
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) player = null
            }
            prepare()   // 本地文件，IO 线程同步 prepare 可接受
            start()
        }
        player = p
        Timber.tag(TAG).i("在线语音播放：%s", file.name)
        true
    }.onFailure { e -> Timber.tag(TAG).w(e, "在线语音播放失败，将回退本地引擎") }.getOrDefault(false)

    // ==================== 本地回退链路（长驻系统 TTS，v0.2.88 原样保留） ====================

    private fun localSpeak(context: Context, text: String) {
        val cur = tts
        if (cur != null) {
            if (ready) doSpeak(cur, text) else pending = text
            return
        }
        pending = text
        Timber.tag(TAG).i("初始化本地 TTS 引擎并播报：%s", text)
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    Timber.tag(TAG).w("TTS 引擎初始化失败（status=%d），本次播报放弃", status)
                    pending = null
                    return@synchronized
                }
                // 中文优先；引擎不支持中文时仍能读出数字（"72%"）
                runCatching { tts?.language = Locale.SIMPLIFIED_CHINESE }
                val text0 = pending
                pending = null
                if (text0 != null && tts != null) doSpeak(tts!!, text0)
            }
        }
    }

    private fun doSpeak(engine: TextToSpeech, text: String) {
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Timber.tag(TAG).i("播报完成：%s", utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Timber.tag(TAG).w("播报出错：%s", utteranceId)
                }
            })
            val accepted = engine.speak(
                text, TextToSpeech.QUEUE_FLUSH, null, "jk_speak_${System.currentTimeMillis()}",
            )
            if (accepted != TextToSpeech.SUCCESS) {
                Timber.tag(TAG).w("TTS 引擎拒绝播报请求（可能引擎未就绪）")
            }
        }.onFailure { Timber.tag(TAG).w(it, "播报异常（已忽略）") }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
