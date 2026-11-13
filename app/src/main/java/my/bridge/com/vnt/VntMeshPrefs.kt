package my.bridge.com.vnt

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** 单套 VNT 组网配置（已拍板：不做多配置切换）。 */
data class VntMeshConfig(
    /**
     * 服务端地址（**v2.0.12 规则**，见 `docs/research/vnt-mesh-integration.md` §12）：
     * - 裸 `host:port` 默认 **tcp://（TLS TCP）**；
     * - 可显式写 `tcp://` / `wss://` / `quic://` / `dynamic://`；
     * - 支持**多地址逗号分隔容灾**（如 `quic://039039.xyz:31008,tcp://039039.xyz:31008`）；
     * - **不支持 `udp://`**——那是 1.2.x 旧版协议（v2.0.x 核心已移除）。
     */
    val server: String = DEFAULT_SERVER,
    /** 网络编号（三要素之一，必填）。 */
    val networkCode: String = DEFAULT_NETWORK_CODE,
    /** 组网密码；非空时启用 ChaCha20-Poly1305 端到端加密。 */
    val password: String = DEFAULT_PASSWORD,
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
    /** 证书模式，如 `finger:<64hex>`；联调默认 `skip`（不校验）。 */
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
        /** 本项目自建 vnts2 服务端（31008，quic+tcp 双地址）。可在配置里改。 */
        const val DEFAULT_SERVER = "quic://039039.xyz:31008,tcp://039039.xyz:31008"
        /** 本项目组网编号（服务端白名单）。 */
        const val DEFAULT_NETWORK_CODE = "wzsvnttoken"
        /** 本项目组网密码（端到端加密，全网一致）。 */
        const val DEFAULT_PASSWORD = "qwe951753"
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

    // ---- 1.x 遗留 key（只读，用于 v0.2.131 反向迁移；不再写入）----
    /** 1.x 的组网编号 key（2.x 改叫 network_code）。 */
    private const val K_LEGACY_TOKEN = "token"
    /** 1.x 的裸自建服务端地址（31007，已下线）。 */
    private const val LEGACY_V1_DEFAULT_SERVER = "039039.xyz:31007"

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

        // v0.2.131 反向迁移：1.x 的组网编号存在 token，2.x 叫 network_code。
        // 优先级：新 network_code > 旧 token > 默认值。
        val networkCode = p.getString(K_NETWORK_CODE, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(K_LEGACY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: VntMeshConfig.DEFAULT_NETWORK_CODE

        // v0.2.131 反向迁移：1.x 的裸 31007 已下线，自动换成 v2 双地址；
        // 其它非法前缀（尤其 udp://）也一并兜底到默认 v2 地址，避免 native 抛晦涩错误。
        val rawServer = p.getString(K_SERVER, null)
        val server = when {
            rawServer.isNullOrBlank() -> VntMeshConfig.DEFAULT_SERVER
            rawServer == LEGACY_V1_DEFAULT_SERVER -> VntMeshConfig.DEFAULT_SERVER
            rawServer.contains("://") -> {
                // 2.x 合法前缀校验：不合法（如 udp://）则回退默认
                if (VntServerAddress.error(rawServer) == null) rawServer
                else VntMeshConfig.DEFAULT_SERVER
            }
            // 裸 host:port（2.x 合法，默认 tcp://）；但若是旧 31007 已在上面兜底
            else -> rawServer
        }

        return VntMeshConfig(
            server = server,
            networkCode = networkCode,
            password = p.getString(K_PASSWORD, null) ?: VntMeshConfig.DEFAULT_PASSWORD,
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
    // v0.2.31 起：自动组网记忆（语义不变）
    //
    // - 点开始 → true → 下次冷启动 App 自动开始组网；
    // - 点停止 → false → 下次冷启动需用户手动点开启。
    // 退出应用 / 切后台结束进程**不动记忆**；远程 vnt_start / vnt_stop 同步写记忆。
    // restart()（配置保存重连）刻意不写记忆，避免「未连接时保存配置」误清标记。
    // ------------------------------------------------------------------

    fun autoStart(context: Context): Boolean = sp(context).getBoolean(K_AUTO_START, false)

    fun setAutoStart(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_AUTO_START, enabled).apply()
    }
}

/**
 * v2.0.12 服务端地址规则校验（见 `vnt-core/.../transport/config.rs::parse_server`）。
 *
 * 合法：裸 `host:port`（默认 TlsTcp）、`tcp://`、`wss://`、`quic://`、`dynamic://`；
 * 支持逗号分隔多地址（容灾）。
 * 非法：任何其它 `://` 前缀——尤其 `udp://`（1.2.x 旧协议），会让 native
 * `createNetwork()` 抛 `Unknown protocol in server address`，错误晦涩且会被反复重试刷屏。
 *
 * 返回可直接展示给用户的错误说明；`null` 表示合法。
 */
object VntServerAddress {
    private val ALLOWED_PREFIXES = listOf("tcp://", "wss://", "quic://", "dynamic://")

    fun error(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null                 // 空串由 VntConfigBuilder 兜底成默认服务端
        // 多地址容灾：逐个校验，任一非法即报错
        for (addr in s.split(",")) {
            val a = addr.trim()
            if (a.isBlank()) continue
            val err = errorSingle(a) ?: continue
            return err
        }
        return null
    }

    private fun errorSingle(raw: String): String? {
        val s = raw.trim()
        if (!s.contains("://")) return null          // 裸 host:port 合法
        val lower = s.lowercase()
        if (ALLOWED_PREFIXES.any { lower.startsWith(it) }) return null
        val proto = s.substringBefore("://") + "://"
        return "不支持的协议前缀「$proto」。VNT v2.0.12 只支持 " +
            "tcp:// / wss:// / quic:// / dynamic://，或裸 host:port（默认 tcp:// TLS）。" +
            "udp:// 是 1.2.x 旧版协议，请升级自建 VNTS 到 2.x 并开启 TLS TCP/WSS/QUIC。"
    }
}
