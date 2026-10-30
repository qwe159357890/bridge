package my.bridge.com.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 快递扫码 UI 状态快照，**不可变**（照 RelaySwitchState 的「单一不可变快照」纪律，
 * 杜绝 LazyVerticalGrid / 列表漏刷）。
 */
data class ScanUiState(
    /** 全部会话，按日期倒序（今日在前）。 */
    val sessions: List<ScanSession> = emptyList(),
    /** 全局已见值数量（全局去重用）。 */
    val seenCount: Int = 0,
)

/**
 * 快递扫码全局状态中心。
 *
 * 状态写入发生在 IO/分析器线程，Compose snapshot 保证线程安全通知 UI；
 * UI 唯一观察点 = [state]。
 */
object ScanState {

    private var _state by mutableStateOf(ScanUiState())

    val state: ScanUiState get() = _state

    /** 统一写入口：产出全新快照对象。 */
    fun replace(sessions: List<ScanSession>, seenCount: Int) {
        _state = ScanUiState(
            sessions = sessions.sortedWith(
                compareByDescending<ScanSession> { it.date }.thenByDescending { it.createdAt }
            ),
            seenCount = seenCount,
        )
    }
}
