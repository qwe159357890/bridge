package my.bridge.com.core.network.ws

/**
 * 连接状态。
 *
 * 对外经 StateFlow 暴露，供业务层（设置页连接状态、UI 指示）观察。
 */
enum class WsConnectionState {
    /** 未连接（初始 / 主动停止） */
    DISCONNECTED,

    /** 正在连接（TCP + WS 握手 + hello） */
    CONNECTING,

    /** 已连接（握手完成，进入心跳循环） */
    CONNECTED,

    /** 断线后等待重连（指数退避中） */
    RECONNECTING,

    /** 已停止（被顶旧 / 服务端踢下线 / 主动关闭，不再重连） */
    STOPPED,
}
