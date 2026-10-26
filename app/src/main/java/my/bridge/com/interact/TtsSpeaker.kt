package my.bridge.com.interact

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS 语音播报（speak 命令）。
 *
 * 系统级 [TextToSpeech] 引擎：无任何权限，依赖设备装有 TTS 引擎
 * （主流国产 ROM 自带；精简 ROM 可能缺失，缺失时报错回执）。
 *
 * 同步模型：初始化（1~3 秒）→ setLanguage(中文，失败回退英文) → speak →
 * 等待 UtteranceProgressListener 完成（超时按字数估算兜底）→ shutdown 释放。
 * 每次命令独立初始化/释放（命令低频，简单可靠优先）。
 */
object TtsSpeaker {

    private const val INIT_TIMEOUT_MS = 6_000L

    /**
     * 朗读一段文字并阻塞到播完（或超时/失败）。
     * @param text 朗读内容（≤ 1000 字，超长在执行器侧截断拒绝）
     * @return 回执消息；失败抛 [IllegalStateException]
     */
    fun speak(context: Context, text: String, timeoutMs: Long): String {
        val appContext = context.applicationContext
        val initLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        val engine = TextToSpeech(appContext) { status ->
            initStatus = status
            initLatch.countDown()
        }
        try {
            if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("TTS 引擎初始化超时（${INIT_TIMEOUT_MS / 1000} 秒）")
            }
            if (initStatus != TextToSpeech.SUCCESS) {
                throw IllegalStateException("设备没有可用的 TTS 引擎（请检查系统「文字转语音」设置）")
            }

            // 中文优先，失败回退英文（引擎不支持中文时仍能读出英文/数字）
            val langResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            val langNote = if (langResult < 0) {
                engine.setLanguage(Locale.US)
                "（引擎不支持中文，已回退英文）"
            } else {
                ""
            }

            val doneLatch = CountDownLatch(1)
            var spokenError = false
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = doneLatch.countDown()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    spokenError = true
                    doneLatch.countDown()
                }
            })

            val utteranceId = "bridge_speak_${System.currentTimeMillis()}"
            val accepted = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (accepted != TextToSpeech.SUCCESS) {
                throw IllegalStateException("TTS 引擎拒绝了朗读请求（可能引擎未就绪）")
            }

            // 等播完：超时兜底 = 字数估算（中文约 4 字/秒）+ 8 秒余量，下限 15 秒
            val estimate = text.length / 4_000.0 * 1000 + 8_000
            val waitMs = maxOf(15_000L, estimate.toLong()).coerceAtMost(timeoutMs)
            val finished = doneLatch.await(waitMs, TimeUnit.MILLISECONDS)
            if (spokenError) {
                throw IllegalStateException("TTS 播报出错（引擎中断）")
            }
            if (!finished) {
                Timber.w("speak：等待播报超时（%d ms），按已播报处理", waitMs)
                return "已发起播报（${text.length} 字，等待超时未确认播完）$langNote"
            }
            Timber.i("speak：播报完成（%d 字）", text.length)
            return "已播报 ${text.length} 字$langNote"
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
