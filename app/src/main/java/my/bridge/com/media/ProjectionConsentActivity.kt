package my.bridge.com.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import timber.log.Timber

/**
 * MediaProjection 授权透明页（screenshot / record_screen 前置）。
 *
 * 系统限制：投屏授权必须经 Activity 调 [MediaProjectionManager.createScreenCaptureIntent]
 * 弹出系统授权框，用户在设备上点「立即开始」（Android 14 起授权结果一次性有效，
 * 无法跨命令复用）。Activity 透明无 UI，授权/拒绝后立即 finish，
 * 结果写入 [ScreenCaptureHolder]（key = commandId）由执行器取走。
 */
class ProjectionConsentActivity : Activity() {

    companion object {
        const val EXTRA_COMMAND_ID = "command_id"

        /** 发起授权（返回 Intent 供 startActivxxx 使用）。 */
        fun intentOf(context: Context, commandId: String): Intent =
            Intent(context, ProjectionConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_COMMAND_ID, commandId)
    }

    private var commandId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), 1001)
        } catch (e: Exception) {
            Timber.e(e, "投屏授权发起失败")
            complete(resultCode = RESULT_CANCELED, data = null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        complete(resultCode, data)
    }

    private fun complete(resultCode: Int, data: Intent?) {
        if (commandId.isNotEmpty()) {
            ScreenCaptureHolder.consent[commandId]?.complete(resultCode to data)
        }
        finish()
    }
}
