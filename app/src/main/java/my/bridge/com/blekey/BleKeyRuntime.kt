package my.bridge.com.blekey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 蓝牙钥匙对外可见的运行状态（前台服务写，UI 读）。
 *
 * 与 [BleKeyBroadcaster.state] 的分工：
 * - [BleKeyBroadcaster.state] 只描述「底层广播会话」是否成功（STOPPED/ADVERTISING/FAILED…）；
 * - 本类描述「为什么处在当前档位」——生命周期状态机的输出（FAST / SLOW / 静默 / 宽限）。
 *
 * 设计文档：docs/research/ble-key-broadcast.md §7（状态机）、§11（UI 生效原因）。
 */
enum class BleKeyMode {
    OFF,        // 总开关关闭
    BLOCKED,    // 不能播（未设编号 / 蓝牙未开 / 无权 / 不支持 / 启动失败）
    BOOST,      // 100 ms：亮屏提速窗口内（人可能正在用车），优先级高于前台/静默
    FAST,       // 250 ms：前台或极空已连接（含 5 分钟宽限）
    SLOW,       // 1000 ms：后台保底
    QUIET,      // 静默时段：后台且未连极空 → 停播（对端 75 秒后断电）
}

data class BleKeyRuntimeState(
    val mode: BleKeyMode = BleKeyMode.OFF,
    /** 人读的「当前生效原因」，直接展示在 UI 与通知里。 */
    val reason: String = "未开启",
    /** 本次连续广播的起始时刻（0 = 未在播）。 */
    val advertisingSince: Long = 0L,
    val quietWindowActive: Boolean = false,
    val error: String? = null,
)

object BleKeyRuntime {

    private val _state = MutableStateFlow(BleKeyRuntimeState())
    val state: StateFlow<BleKeyRuntimeState> = _state.asStateFlow()

    fun update(block: (BleKeyRuntimeState) -> BleKeyRuntimeState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = BleKeyRuntimeState()
    }

    fun onAdvertisingStarted() {
        // ★ 例行重建（每 60 秒强制重建）会重复回调本方法，不能把「已播时长」清零。
        // 只在「从没在播 → 开始播」时记录起点；档位切换（SLOW→BOOST 等）也不该重置。
        if (_state.value.advertisingSince == 0L) {
            update { it.copy(advertisingSince = System.currentTimeMillis()) }
        }
    }

    fun onAdvertisingStopped() {
        update { it.copy(advertisingSince = 0L) }
    }
}
