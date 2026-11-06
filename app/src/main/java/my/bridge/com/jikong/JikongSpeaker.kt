package my.bridge.com.jikong

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

/**
 * 极空语音播报（v0.2.88）：连接成功且拿到电量后的中文 TTS 播报。
 *
 * 与 [my.bridge.com.interact.TtsSpeaker]（speak 命令用，每次独立初始化/释放、同步阻塞）不同，
 * 本类为**长驻单例**：首次播报惰性初始化引擎，之后复用——播报是骑行/查看数据时的即时反馈，
 * 不能容忍 1~3 秒的初始化延迟。未就绪时文案暂存 pending，引擎就绪后补播。
 *
 * 无任何权限；依赖设备装有系统 TTS 引擎（主流国产 ROM 自带）。精简 ROM 缺引擎时
 * 静默失败（记日志），不影响任何功能。
 */
object JikongSpeaker {

    private const val TAG = "JikongSpeaker"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** 播报一段文字（异步，立即返回）。引擎未就绪时暂存待播。 */
    @Synchronized
    fun speak(context: Context, text: String) {
        val cur = tts
        if (cur != null) {
            if (ready) doSpeak(cur, text) else pending = text
            return
        }
        pending = text
        Timber.tag(TAG).i("初始化 TTS 引擎并播报：%s", text)
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
}
