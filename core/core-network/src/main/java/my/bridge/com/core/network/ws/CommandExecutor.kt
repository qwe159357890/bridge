package my.bridge.com.core.network.ws

/**
 * 命令执行器：处理服务端下发的一条命令。
 *
 * 每个命令名对应一个实现，注册到 [BridgeWebSocketClient] 的命令表。
 * 执行后返回 [CommandResult]（由客户端回执给服务端）。
 *
 * 实现须在协程/后台线程执行（可能涉及网络上传），且不抛异常（异常统一转失败回执）。
 */
fun interface CommandExecutor {
    /**
     * 执行命令。
     *
     * @param command 命令（含 command_id 与 params）
     * @return 执行结果（success/message）
     */
    suspend fun execute(command: Command): CommandResult
}
