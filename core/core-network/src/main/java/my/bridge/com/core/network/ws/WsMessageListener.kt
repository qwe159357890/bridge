package my.bridge.com.core.network.ws

/**
 * 收到服务端推送（`message` 帧）的回调接口。
 *
 * 业务层实现该接口以接收推送；当前默认仅记录日志，不抛异常。
 */
fun interface WsMessageListener {
    /**
     * 收到一条业务推送。
     *
     * @param data 推送的 data 载荷（JSON 对象，可能为 null）
     */
    fun onMessage(data: com.google.gson.JsonObject?)
}
