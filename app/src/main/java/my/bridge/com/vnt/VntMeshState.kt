package my.bridge.com.vnt

/**
 * VNT 组网 UI 状态（v0.2.131，2.x / vnt-dev v2.0.12）。
 *
 * 2.x 底层提供 `VntApi`（getClientList / getServerList / getNatInfo / getRouteTable /
 * getTrafficInfo / isDirect 等），因此有**速率、NAT 类型、真实 RTT、服务端列表**；
 * P2P/中继由 `VntApi.isDirect()` 判定（1.x 时代只能靠 `Route.metric == 1`，已废弃）。
 *
 * 单一状态树，由 [VntMeshController] 以 StateFlow 发布，`VntPages.kt` 只订阅、不持有连接。
 */
enum class VntStatus {
    /** 未连接（默认）。 */
    STOPPED,

    /** 已下发 START，等待 native 注册并建立 VPN 接口。 */
    STARTING,

    /** 已连接，虚拟 IP 可用。 */
    RUNNING,

    /**
     * 断线重连中（v0.2.38 沿用）：监督循环仍在服务内运行，将按退避节奏自动重建连接；
     * 用户点「停止组网」随时可终止。**不是终态**，勿当 ERROR 处理。
     */
    RECONNECTING,

    /** 正在停止。 */
    STOPPING,

    /** 启动/运行期出错，[VntMeshState.message] 为原因。 */
    ERROR,
}

/** 设备列表一行。 */
data class VntPeer(
    val ip: String,
    val online: Boolean,
    /** true = P2P 直连（打洞成功），false = 经服务端中继。 */
    val direct: Boolean,
    /** RTT 毫秒；无路由时可能为 null。 */
    val rttMs: Int? = null,
    /** true 表示 rttMs 是「服务端 RTT × 2」的估算值（UI 需加 ≈ 前缀）。 */
    val rttEstimated: Boolean = false,
    /** 上行速率（字节/秒），由累计流量差分得出，首次采样为 0。 */
    val txBytesPerSec: Long = 0L,
    /** 下行速率（字节/秒）。 */
    val rxBytesPerSec: Long = 0L,
)

/** 服务端列表一行（本版只展示当前连接的主服务端）。 */
data class VntServer(
    val address: String,
    val connected: Boolean,
    val rttMs: Int?,
)

data class VntMeshState(
    val status: VntStatus = VntStatus.STOPPED,
    /** 本机虚拟 IPv4（如 86.74.128.30）。 */
    val virtualIp: String? = null,
    val prefixLen: Int = 24,
    /** 虚拟 IPv6（fd76:6e74::/96 派生）。 */
    val virtualIpv6: String? = null,
    val mtu: Int = 1380,
    val natType: String? = null,
    val serverRttMs: Int? = null,
    val servers: List<VntServer> = emptyList(),
    val networkCode: String = "",
    val deviceName: String = "",
    val deviceId: String = "",
    val peers: List<VntPeer> = emptyList(),
    /** 状态/错误说明（显示在状态卡下方）。 */
    val message: String = "",
    /** 最后一次成功刷新时间（System.currentTimeMillis），0 = 从未刷新。 */
    val lastUpdatedAt: Long = 0L,
    /** v0.2.38 沿用：RECONNECTING 专用——第几次重连尝试（从 1 起）。 */
    val retryAttempt: Int = 0,
    /** v0.2.38 沿用：RECONNECTING 专用——距下次重连的秒数。 */
    val retryDelaySec: Int = 0,
) {
    val isRunning: Boolean get() = status == VntStatus.RUNNING

    /** 服务/监督循环仍在为「保持组网」工作（含启动与重连），UI 据此决定显示停止按钮。 */
    val isActive: Boolean
        get() = status == VntStatus.RUNNING || status == VntStatus.STARTING ||
            status == VntStatus.RECONNECTING
}
