package my.bridge.com.media

import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * 媒体采集跨组件通道（进程内单例仓库）。
 *
 * 两个方向：
 * 1. 授权：[ProjectionConsentActivity] 把 MediaProjection 授权结果放进
 *    [consent]，执行器（WS 线程）通过 latch 等待（设备上用户点「立即开始」）；
 * 2. 采集：[MediaCaptureService] 完成截屏/录屏/拍照后把文件路径或错误放进
 *    [capture]，执行器继续等待并上传。
 *
 * key 均为 commandId，用完即取（take 语义，防串扰）。
 */
object ScreenCaptureHolder {

    /** 采集结果：success 时 path 有效；失败时 error 有内容。 */
    class CaptureFile(val path: String, val error: String? = null) {
        val success: Boolean get() = error == null
    }

    class Outcome<T> {
        val latch = CountDownLatch(1)

        @Volatile
        var value: T? = null

        fun complete(v: T) {
            value = v
            latch.countDown()
        }
    }

    /** 授权结果：resultCode + data；data 为 null 表示用户拒绝或超时。 */
    val consent = ConcurrentHashMap<String, Outcome<Pair<Int, Intent?>>>()

    /** 采集结果（按 commandId 取用）。 */
    val capture = ConcurrentHashMap<String, Outcome<CaptureFile>>()

    /** 取走授权结果（用完即删）。 */
    fun takeConsent(commandId: String): Outcome<Pair<Int, Intent?>>? = consent.remove(commandId)

    /** 取走采集结果（用完即删）。 */
    fun takeCapture(commandId: String): Outcome<CaptureFile>? = capture.remove(commandId)

    /** 失败结果便捷构造。 */
    fun failure(message: String): CaptureFile = CaptureFile("", message)
}
