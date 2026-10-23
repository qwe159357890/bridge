package my.bridge.com.core.model

/**
 * 领域模型基类约定：
 * - 纯 Kotlin 数据类，不依赖 Android 框架；
 * - 命名遵循「领域名词」而非「表名/接口名」。
 */

/** 统一 Result 封装，供跨模块业务流转使用 */
sealed interface BridgeResult<out T> {
    data class Success<T>(val data: T) : BridgeResult<T>
    data class Failure(val code: Int, val message: String) : BridgeResult<Nothing>
    data object Loading : BridgeResult<Nothing>
}
