package my.bridge.com.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * 保活兜底周期任务：setPersisted(true) 重启后仍有效。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i("KeepAliveJobService", "JobScheduler 兜底任务触发")
        try {
            KeepAliveService.start(this)
        } catch (e: Exception) {
            Log.e("KeepAliveJobService", "拉起保活服务失败", e)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
