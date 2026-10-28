package my.bridge.com.relay

import android.content.Context
import my.bridge.com.core.common.DiagLog
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音频中继地址上报器（后端零改动，与 Aurelay 中心服务器对接）。
 *
 * 为什么必须有上报：Aurelay 中转是「按 device_id 配对」的撮合模型。PC 发送端
 * 先通过 HTTP `GET /api/aurelay/devices` 查询在线设备列表拿到目标手机的 device_id，
 * 再用它连 WebSocket 与手机配对。**手机不上报，PC 就查不到这台设备，永远配不上对**
 * （表现就是「一直连接中」）。
 *
 * 上报内容：把本机 device_id 与当前链路模式（relay=蜂窝中转）POST 到中心服务器，
 * 鉴权用 X-API-Key 与 Authorization 双写 token（与 Aurelay AddressReporter 一致）。
 */
object RelayReporter {

    private const val TAG = "RelayReport"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    // 上报链路模式：relay = 只能走服务器中转（手机放音在蜂窝网络下无公网入站能力）
    private const val MODE_RELAY = "relay"

    /** 立即上报一次（后台线程执行，供 relay 启动时调用）。 */
    fun reportNow(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                reportOnce(app)
            } catch (e: Exception) {
                DiagLog.w(TAG, "立即上报失败：${e.message}")
            }
        }, "BridgeRelayReport").also { it.isDaemon = true; it.start() }
    }

    /** 执行一次上报：POST device_id 与链路模式到中心服务器。 */
    fun reportOnce(context: Context) {
        val app = context.applicationContext
        val url = RelayPrefs.reportUrl(app)
        if (url.isBlank()) {
            DiagLog.w(TAG, "上报地址未配置，跳过上报")
            return
        }
        val token = RelayPrefs.reportToken(app)
        val deviceId = RelayPrefs.deviceId(app)
        val deviceName = deviceName()

        // 构造上报请求体（字段名与 Aurelay 中心服务器约定一致）
        val payload = buildString {
            append("{")
            append("\"device_id\":\"").append(escapeJson(deviceId)).append("\",")
            append("\"device_name\":\"").append(escapeJson(deviceName)).append("\",")
            append("\"port\":").append(AudioRelayService.AUDIO_PORT).append(",")
            append("\"mode\":\"").append(MODE_RELAY).append("\",")
            append("\"ipv4\":[],")
            append("\"ipv6\":[]")
            append("}")
        }

        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-API-Key", token)
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }
            val httpCode = conn.responseCode
            val bodyText = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            // 中心服务器统一响应：HTTP 200 + body 内 code==0 才算成功
            val bizCode = extractJsonInt(bodyText, "code")
            val accepted = httpCode in 200..299 && (bizCode == null || bizCode == 0)
            if (accepted) {
                DiagLog.i(TAG, "上报成功（中转模式，device_id=$deviceId）")
            } else {
                val reason = extractJsonString(bodyText, "message") ?: "HTTP $httpCode"
                DiagLog.w(TAG, "上报被服务端拒绝：$reason（HTTP $httpCode）")
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "上报失败：${e.javaClass.simpleName} - ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /** 设备展示名（用于服务端识别）。 */
    private fun deviceName(): String {
        return try {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        } catch (e: Exception) {
            "Android"
        }
    }

    private fun escapeJson(raw: String): String {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val marker = "\"$key\""
        val at = json.indexOf(marker)
        if (at < 0) return null
        val colon = json.indexOf(':', at + marker.length)
        if (colon < 0) return null
        val digits = StringBuilder()
        for (ch in json.substring(colon + 1)) {
            if (ch == '-' && digits.isEmpty()) {
                digits.append(ch)
            } else if (ch.isDigit()) {
                digits.append(ch)
            } else if (digits.isNotEmpty()) {
                break
            }
        }
        return digits.toString().toIntOrNull()
    }

    private fun extractJsonString(json: String, key: String): String? {
        val marker = "\"$key\""
        val at = json.indexOf(marker)
        if (at < 0) return null
        val colon = json.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var index = json.indexOf('"', colon + 1)
        if (index < 0) return null
        index += 1
        val text = StringBuilder()
        while (index < json.length) {
            val ch = json[index]
            if (ch == '\\' && index + 1 < json.length) {
                val next = json[index + 1]
                when (next) {
                    'n' -> text.append('\n')
                    't' -> text.append('\t')
                    'r' -> text.append('\r')
                    else -> text.append(next)
                }
                index += 2
                continue
            }
            if (ch == '"') break
            text.append(ch)
            index += 1
        }
        return text.toString()
    }
}
