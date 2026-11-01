package my.bridge.com.vnt

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** 单套 VNT 组网配置（已拍板：不做多配置切换）。 */
data class VntMeshConfig(
    /**
     * 服务端地址（**v2.0.11 最新 so 规则**，见 `docs/research/vnt-mesh-integration.md` §12）：
     * - 裸 `host:port` 默认 **tcp://（TLS TCP）**；
     * - 可显式写 `tcp://` / `wss://` / `quic://` / `dynamic://`；
     * - **不支持 `udp://`**——那是 1.2.x 旧版协议（官方 1.2.13 的「协议 UDP」单选），
     *   v2.0.x 核心已移除。自建 VNTS 需升级到 2.x 并开启 TLS TCP / WSS / QUIC 监听。
     */
    val server: String = DEFAULT_SERVER,
    /** 网络编号（三要素之一，必填）。 */
    val networkCode: String = "",
    /** 组网密码；非空时启用 ChaCha20-Poly1305 端到端加密。 */
    val password: String = "",
    val deviceName: String = "Bridge",
    /** 设备 ID，默认取 ANDROID_ID 并持久化（同一设备保持不变）。 */
    val deviceId: String = "",
    /** 固定虚拟 IP；留空由服务端动态分配。 */
    val ip: String = "",
    val mtu: Int = DEFAULT_MTU,
    val compress: Boolean = true,
    val fec: Boolean = false,
    val rtx: Boolean = false,
    val noPunch: Boolean = false,
    /** 证书模式，如 `finger:<64hex>`；留空不校验。 */
    val certMode: String = "",

    // ---- 预留位：子网转发 / 端口映射（本期不做，见 §九 决策 8）----
    // 接口已核准：VntConfig.Builder.addOutputRoute("真实CIDR")
    //   / addInputRoute("源网段CIDR,目标虚拟IP")
    //   / addSubnetMapping("映射CIDR,真实CIDR")
    //   / setAutoSyncSubnet(Boolean)
    //   / setAllowMapping(Boolean) + addPortMapping("tcp://0.0.0.0:81-10.26.0.2-192.168.1.10:80")
    // 启用这些接口会令运行期快照 needsVpnRebuild=true → 必须重建 VPN 接口（fd 切换），
    // 是本项目最容易出事故的动作之一。将来真要做，只做 output 单向。
    // 当前这些字段恒为空 / false，load() 与 save() 不持久化它们。
    val outputRoutes: List<String> = emptyList(),
    val inputRoutes: List<String> = emptyList(),
    val subnetMappings: List<String> = emptyList(),
    val autoSyncSubnet: Boolean = false,
    val portMappings: List<String> = emptyList(),
    val allowMapping: Boolean = false,
) {
    companion object {
        /** 官方公共测试服，仅作默认值；生产可填自建 VNTS。 */
        const val DEFAULT_SERVER = "vnt2.rustvnt.com:29872"
        const val DEFAULT_MTU = 1380
        const val MIN_MTU = 1280
        const val MAX_MTU = 9000
    }
}

/**
 * 单套配置持久化（SharedPreferences）。
 *
 * 与 `RelaySwitchPrefs` / `ScanPrefs` 同一范式：object + 私有 SP 文件 + data class。
 */
object VntMeshPrefs {

    private const val FILE = "vnt_mesh_prefs"
    private const val K_SERVER = "server"
    private const val K_NETWORK_CODE = "network_code"
    private const val K_PASSWORD = "password"
    private const val K_DEVICE_NAME = "device_name"
    private const val K_DEVICE_ID = "device_id"
    private const val K_IP = "ip"
    private const val K_MTU = "mtu"
    private const val K_COMPRESS = "compress"
    private const val K_FEC = "fec"
    private const val K_RTX = "rtx"
    private const val K_NO_PUNCH = "no_punch"
    private const val K_CERT_MODE = "cert_mode"
    /** v0.2.31：自动组网记忆（点「开始组网」置 true，点「停止组网」清 false）。 */
    private const val K_AUTO_START = "auto_start"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 稳定设备 ID：优先已持久化的值，否则取 ANDROID_ID，再兜底随机 UUID。 */
    fun stableDeviceId(context: Context): String {
        val saved = sp(context).getString(K_DEVICE_ID, null)
        if (!saved.isNullOrBlank()) return saved
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        sp(context).edit().putString(K_DEVICE_ID, id).apply()
        return id
    }

    fun load(context: Context): VntMeshConfig {
        val p = sp(context)
        return VntMeshConfig(
            server = p.getString(K_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: VntMeshConfig.DEFAULT_SERVER,
            networkCode = p.getString(K_NETWORK_CODE, null) ?: "",
            password = p.getString(K_PASSWORD, null) ?: "",
            deviceName = p.getString(K_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: "Bridge",
            deviceId = stableDeviceId(context),
            ip = p.getString(K_IP, null) ?: "",
            mtu = p.getInt(K_MTU, VntMeshConfig.DEFAULT_MTU)
                .coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU),
            compress = p.getBoolean(K_COMPRESS, true),
            fec = p.getBoolean(K_FEC, false),
            rtx = p.getBoolean(K_RTX, false),
            noPunch = p.getBoolean(K_NO_PUNCH, false),
            certMode = p.getString(K_CERT_MODE, null) ?: "",
        )
    }

    fun save(context: Context, config: VntMeshConfig) {
        sp(context).edit()
            .putString(K_SERVER, config.server.trim())
            .putString(K_NETWORK_CODE, config.networkCode.trim())
            .putString(K_PASSWORD, config.password)
            .putString(K_DEVICE_NAME, config.deviceName.trim().ifBlank { "Bridge" })
            .putString(K_DEVICE_ID, config.deviceId.ifBlank { stableDeviceId(context) })
            .putString(K_IP, config.ip.trim())
            .putInt(K_MTU, config.mtu.coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU))
            .putBoolean(K_COMPRESS, config.compress)
            .putBoolean(K_FEC, config.fec)
            .putBoolean(K_RTX, config.rtx)
            .putBoolean(K_NO_PUNCH, config.noPunch)
            .putString(K_CERT_MODE, config.certMode.trim())
            .apply()
    }

    // ------------------------------------------------------------------
    // v0.2.31：自动组网记忆
    //
    // 语义（用户拍板）：**只有「开始组网」/「停止组网」两个按钮改变记忆**。
    // - 点开始 → true → 下次冷启动 App 自动开始组网；
    // - 点停止 → false → 下次冷启动需用户手动点开启。
    // 退出应用 / 切后台结束进程**不动记忆**（用户未要求清除）；进程重启后按记忆恢复。
    // 远程命令 vnt_start / vnt_stop 同步写记忆，保证「最后一次显式意图」一致。
    // restart()（配置保存重连）刻意不写记忆，避免「未连接时保存配置」误清标记。
    // ------------------------------------------------------------------

    fun autoStart(context: Context): Boolean = sp(context).getBoolean(K_AUTO_START, false)

    fun setAutoStart(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_AUTO_START, enabled).apply()
    }
}

/**
 * v2.0.11 服务端地址规则校验（见 `vnt-core/.../transport/config.rs::parse_server`）。
 *
 * 合法：裸 `host:port`（默认 TlsTcp）、`tcp://`、`wss://`、`quic://`、`dynamic://`。
 * 非法：任何其它 `://` 前缀——尤其 `udp://`（1.2.x 旧协议），会让 native
 * `createNetwork()` 抛 `Unknown protocol in server address`，错误晦涩且会被反复重试刷屏。
 *
 * 返回可直接展示给用户的错误说明；`null` 表示合法。
 */
object VntServerAddress {
    private val ALLOWED_PREFIXES = listOf("tcp://", "wss://", "quic://", "dynamic://")

    fun error(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null                 // 空串由 VntConfigBuilder 兜底成默认公共服
        if (!s.contains("://")) return null          // 裸 host:port 合法
        val lower = s.lowercase()
        if (ALLOWED_PREFIXES.any { lower.startsWith(it) }) return null
        val proto = s.substringBefore("://") + "://"
        return "不支持的协议前缀「$proto」。VNT v2.0.11 只支持 " +
            "tcp:// / wss:// / quic:// / dynamic://，或裸 host:port（默认 tcp:// TLS）。" +
            "udp:// 是 1.2.x 旧版协议，请升级自建 VNTS 到 2.x 并开启 TLS TCP/WSS/QUIC，" +
            "或先用公共服 vnt2.rustvnt.com:29872 验证。"
    }
}
