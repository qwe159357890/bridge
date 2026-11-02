package my.bridge.com.vnt

import android.content.Context
import my.bridge.com.core.common.DiagLog

/**
 * 冷启动自动组网（v0.2.31）。
 *
 * 用户拍板语义：**点「开始组网」记忆、下次启动 App 自动开始；点「停止组网」清记忆**。
 * 记忆存于 [VntMeshPrefs.autoStart]，由 UI 按钮与 `vnt_start` / `vnt_stop` 命令写入。
 *
 * 调用时机：[my.bridge.com.MainActivity.onStart]（Activity 已可见，避开 Android 12+
 * 「后台启动前台服务」限制），且每个 Activity 实例只尝试一次，避免旋转/回前台重复触发。
 *
 * 三道守卫（缺一不可）：
 * 1. 记忆未开 → 直接跳过（用户点过「停止组网」）；
 * 2. 已在 RUNNING / STARTING → 跳过（[VntMeshController.start] 本身也有守卫，双保险）；
 * 3. **VPN 授权已失效 → 跳过**：`VpnService.prepare()` 非 null 表示系统授权被撤销
 *    （例如用户在系统设置里关掉了 VPN、或另一个 App 抢走了唯一通道），此时不能后台
 *    静默弹授权框，也不该反复重试；由用户进组网页手动点一次「开始组网」重新授权。
 *
 * 失败不重试：配置错误（如 token 为空、服务端地址格式非法）会落到 ERROR 状态并显示原因，
 * 由用户改配置后再点开始；自动启动绝不循环，避免刷屏与耗电。
 */
object VntAutoStart {

    private const val TAG = "组网"

    fun tryStart(context: Context) {
        if (!VntMeshPrefs.autoStart(context)) {
            DiagLog.i(TAG, "自动组网：未记忆开启，跳过")
            return
        }
        val st = VntMeshController.state.value.status
        // v0.2.38：RECONNECTING 也视为已在工作（监督循环会自动建回来）
        if (st == VntStatus.RUNNING || st == VntStatus.STARTING || st == VntStatus.RECONNECTING) {
            DiagLog.i(TAG, "自动组网：已在运行/启动中/重连中，跳过")
            return
        }
        if (!VntMeshController.isPrepared(context)) {
            DiagLog.w(TAG, "自动组网：系统 VPN 授权已失效，跳过（需进组网页手动点一次开始组网）")
            return
        }
        DiagLog.i(TAG, "自动组网：记忆已开启，冷启动自动开始组网")
        VntMeshController.start(context)
    }
}
