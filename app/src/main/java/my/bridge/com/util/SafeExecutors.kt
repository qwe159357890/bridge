package my.bridge.com.util

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 防拒崩执行器包装。
 *
 * 背景：给系统/相机组件（CameraX ImageCapture/ImageAnalysis、无障碍 takeScreenshot 等）
 * 传入的 executor 在我们调用 shutdown() 之后，系统内部线程仍可能向后投递任务
 * （帧回调、拍摄结果、超时后的迟到回调）。对已 shutdown 的 executor 执行
 * execute() 会抛 RejectedExecutionException，且异常发生在**系统内部线程**上，
 * 未捕获 → 进程直接崩溃（表现为「Bridge 已停止运行」，与用户操作无必然时序，
 * 因此极难复现）。
 *
 * 包装后：对已 shutdown 的 executor 投递的任务静默丢弃（页面已退出，丢弃无害）。
 */
fun safeExecutor(inner: Executor): Executor = Executor { r ->
    try {
        inner.execute(r)
    } catch (_: RejectedExecutionException) {
        // 组件已释放/页面已退出，迟到回调按设计丢弃
    }
}
