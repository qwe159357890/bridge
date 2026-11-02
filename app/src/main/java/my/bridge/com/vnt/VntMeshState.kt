package my.bridge.com.vnt

/**
 * VNT 组网 UI 状态（v0.2.32，1.x / VntApp v1.2.9）。
 *
 * 与 2.x 的差异：1.x 底层只提供 `Vnt.list() -> PeerRouteInfo[]`（native 侧缓存的路由表），
 * 没有 getClientList/getServerList/getNatInfo/getTrafficInfo/RuntimeEvent，因此
 * **没有速率、NAT 类型、真实 RTT、服务端列表**这些字段；P2P/中继只能由
 * `Route.metric == 1` 判定（官方 ConnectActivity 源码明确这么用）。
 *
 * 单一状态树，由 [VntMeshController] 以 StateFlow 发布，`VntPages.kt` 只订阅、不持有连接。
 */
enum class VntStatus {
    /** 未连接（默认）。 */
    STOPPED,

    /** 已下发 START，等待 native 注册并在回调里建立 VPN 接口。 */
    STARTING,

    /** 已连接，虚拟 IP 可用。 */
    RUNNING,

    /**
     * 断线重连中（v0.2.38）：监督循环仍在服务内运行，将按退避节奏自动重建连接；
     * 用户点「停止组网」随时可终止。**不是终态**，勿当 ERROR 处理。
     */
    RECONNECTING,

    /** 正在停止。 */
    STOPPING,

    /** 启动/运行期出错，[VntMeshState.message] 为原因。 */
    ERROR,
}

/** 设备列表一行（由 1.x `PeerRouteInfo` 映射）。 */
data class VntPeer(
    val ip: String,
    val name: String,
    /** native 原样返回的 status 字符串（如 Online/Offline）。 */
    val status: String,
    val online: Boolean,
    /** true = P2P 直连（官方判定：`Route.metric == 1`），false = 经服务端中继。 */
    val direct: Boolean,
    /** `Route.rt`，官方 UI 原样显示，单位/含义由底层定义，这里不臆断为毫秒。 */
    val rt: Int? = null,
)

data class VntMeshState(
    val status: VntStatus = VntStatus.STOPPED,
    /** 本机虚拟 IPv4（如 10.26.0.5），来自 `RegisterInfo` / `DeviceConfig`。 */
    val virtualIp: String? = null,
    val prefixLen: Int = 24,
    val mtu: Int = 1410,
    /** 1.x 的「组网编号」叫 token。 */
    val token: String = "",
    val deviceName: String = "",
    val deviceId: String = "",
    val server: String = "",
    /** false = UDP（用户存量 1.2.13 客户端就是 UDP），true = TCP。 */
    val tcp: Boolean = false,
    /** 握手时服务端返回的版本，便于核对 1.x 服务端。 */
    val serverVersion: String? = null,
    val peers: List<VntPeer> = emptyList(),
    /** 状态/错误说明（显示在状态卡下方）。 */
    val message: String = "",
    /** 最后一次成功刷新时间（System.currentTimeMillis），0 = 从未刷新。 */
    val lastUpdatedAt: Long = 0L,
    /** v0.2.38：RECONNECTING 专用——第几次重连尝试（从 1 起）。 */
    val retryAttempt: Int = 0,
    /** v0.2.38：RECONNECTING 专用——距下次重连的秒数。 */
    val retryDelaySec: Int = 0,
) {
    val isRunning: Boolean get() = status == VntStatus.RUNNING

    /** 服务/监督循环仍在为「保持组网」工作（含启动与重连），UI 据此决定显示停止按钮。 */
    val isActive: Boolean
        get() = status == VntStatus.RUNNING || status == VntStatus.STARTING ||
            status == VntStatus.RECONNECTING
}
