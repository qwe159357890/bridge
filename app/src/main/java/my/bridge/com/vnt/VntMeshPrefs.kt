package my.bridge.com.vnt

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 单套 VNT 组网配置（v0.2.32，1.x / VntApp v1.2.9）。
 *
 * ★ 与 2.x 的配置模型完全不同（见 `docs/dev/vnt-integration-change-ledger.md` §六）：
 * - 组网编号在 1.x 里叫 **token**（2.x 叫 networkCode）；
 * - 服务端是**裸 `host:port`，无任何协议前缀**；UDP/TCP 由布尔 [tcp] 决定
 *   （`false` = UDP，正对应用户 1.2.13 原生 APK 里选的「协议 UDP」）；
 * - 加密是 `cipherModel`（aes_gcm / aes_cbc / aes_ecb）+ `serverEncrypt` + `finger`，
 *   不是 2.x 的 ChaCha20-Poly1305 / certMode；
 * - 没有网络编号之外的「网络码」、没有 certMode、没有 compress/fec/rtx/noPunch。
 *
 * 这些字段必须与存量 1.x 客户端**逐项一致**（尤其 token / password / cipherModel /
 * serverEncrypt / finger），否则会「能注册但互相不通」。
 */
data class VntMeshConfig(
    /** 组网编号（1.x 的 token，必填；同编号设备互通）。 */
    val token: String = "",
    /** 服务端裸地址 `host:port`（如 039039.xyz:31007），**不要带协议前缀**。 */
    val server: String = DEFAULT_SERVER,
    /** false = UDP（默认，与存量 1.2.13 一致），true = TCP。 */
    val tcp: Boolean = false,
    /** 客户端间加密密码；空 = 不加密。不同加密状态的客户端不能通信。 */
    val password: String = "",
    /** 加密模式：aes_gcm / aes_cbc / aes_ecb（官方 v1.2.9 的 spinner 选项）。 */
    val cipherModel: String = DEFAULT_CIPHER,
    /** 服务端加密，官方 AddActivity 固定 true。 */
    val serverEncrypt: Boolean = true,
    /** 开启加密指纹校验。 */
    val finger: Boolean = false,
    /** 延迟优先（false = p2p 优先）。 */
    val firstLatency: Boolean = false,
    val deviceName: String = "Bridge",
    /** 设备 ID，默认取 ANDROID_ID 并持久化（同一设备保持不变）。 */
    val deviceId: String = "",
    /** 固定虚拟 IP；留空由服务端动态分配。 */
    val ip: String = "",
    val mtu: Int = DEFAULT_MTU,
    /** STUN 服务地址，官方 AddActivity 固定这三个。 */
    val stunServers: List<String> = DEFAULT_STUN,
    /** 打洞模式 ipv4/ipv6/all；留空用底层默认。 */
    val punchModel: String = "",
    /** 通道 relay/p2p/all；留空用底层默认。 */
    val useChannel: String = "",
) {
    companion object {
        /** 用户自建 1.x VNTS（其 1.2.13 原生 APK 里填的就是这个）。可在配置里改。 */
        const val DEFAULT_SERVER = "039039.xyz:31007"
        /** 官方 MyVpnService 在 mtu 为 null 时的兜底值。 */
        const val DEFAULT_MTU = 1410
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
        const val DEFAULT_CIPHER = "aes_gcm"
        val CIPHER_OPTIONS = listOf("aes_gcm", "aes_cbc", "aes_ecb")
        val DEFAULT_STUN = listOf(
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302",
            "stun.miwifi.com:3478",
        )
    }
}

/**
 * 单套配置持久化（SharedPreferences）。
 *
 * 与 `RelaySwitchPrefs` / `ScanPrefs` 同一范式：object + 私有 SP 文件 + data class。
 */
object VntMeshPrefs {

    private const val FILE = "vnt_mesh_prefs"
    private const val K_TOKEN = "token"
    private const val K_SERVER = "server"
    private const val K_TCP = "tcp"
    private const val K_PASSWORD = "password"
    private const val K_CIPHER = "cipher_model"
    private const val K_SERVER_ENCRYPT = "server_encrypt"
    private const val K_FINGER = "finger"
    private const val K_FIRST_LATENCY = "first_latency"
    private const val K_DEVICE_NAME = "device_name"
    private const val K_DEVICE_ID = "device_id"
    private const val K_IP = "ip"
    private const val K_MTU = "mtu"
    private const val K_STUN = "stun_servers"
    private const val K_PUNCH = "punch_model"
    private const val K_CHANNEL = "use_channel"
    /** v0.2.31：自动组网记忆（点「开始组网」置 true，点「停止组网」清 false）。 */
    private const val K_AUTO_START = "auto_start"
    /** v0.2.30/31 旧 key（只读，用于升级迁移；不再写入）。 */
    private const val K_LEGACY_NETWORK_CODE = "network_code"
    /** v0.2.30/31 的 2.x 公共服默认值：升级时若仍是它，自动换成 1.x 自建地址。 */
    private const val LEGACY_V2_DEFAULT_SERVER = "vnt2.rustvnt.com:29872"

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
        // 升级迁移：v0.2.30/31 把组网编号存在 network_code，1.x 里它叫 token
        val token = p.getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(K_LEGACY_NETWORK_CODE, null)
            ?: ""
        // 升级迁移：旧的 2.x 公共服默认值对 1.x 无意义，换成用户自建 1.x 服务端
        val legacyServer = p.getString(K_SERVER, null)
        val server = when {
            legacyServer.isNullOrBlank() -> VntMeshConfig.DEFAULT_SERVER
            legacyServer == LEGACY_V2_DEFAULT_SERVER -> VntMeshConfig.DEFAULT_SERVER
            else -> legacyServer
        }
        val stun = p.getString(K_STUN, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: VntMeshConfig.DEFAULT_STUN
        return VntMeshConfig(
            token = token,
            server = server,
            tcp = p.getBoolean(K_TCP, false),
            password = p.getString(K_PASSWORD, null) ?: "",
            cipherModel = p.getString(K_CIPHER, null)?.takeIf { it.isNotBlank() }
                ?: VntMeshConfig.DEFAULT_CIPHER,
            serverEncrypt = p.getBoolean(K_SERVER_ENCRYPT, true),
            finger = p.getBoolean(K_FINGER, false),
            firstLatency = p.getBoolean(K_FIRST_LATENCY, false),
            deviceName = p.getString(K_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: "Bridge",
            deviceId = stableDeviceId(context),
            ip = p.getString(K_IP, null) ?: "",
            mtu = p.getInt(K_MTU, VntMeshConfig.DEFAULT_MTU)
                .coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU),
            stunServers = stun,
            punchModel = p.getString(K_PUNCH, null) ?: "",
            useChannel = p.getString(K_CHANNEL, null) ?: "",
        )
    }

    fun save(context: Context, config: VntMeshConfig) {
        sp(context).edit()
            .putString(K_TOKEN, config.token.trim())
            .putString(K_SERVER, config.server.trim())
            .putBoolean(K_TCP, config.tcp)
            .putString(K_PASSWORD, config.password)
            .putString(K_CIPHER, config.cipherModel.trim().ifBlank { VntMeshConfig.DEFAULT_CIPHER })
            .putBoolean(K_SERVER_ENCRYPT, config.serverEncrypt)
            .putBoolean(K_FINGER, config.finger)
            .putBoolean(K_FIRST_LATENCY, config.firstLatency)
            .putString(K_DEVICE_NAME, config.deviceName.trim().ifBlank { "Bridge" })
            .putString(K_DEVICE_ID, config.deviceId.ifBlank { stableDeviceId(context) })
            .putString(K_IP, config.ip.trim())
            .putInt(K_MTU, config.mtu.coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU))
            .putString(K_STUN, config.stunServers.joinToString("\n"))
            .putString(K_PUNCH, config.punchModel.trim())
            .putString(K_CHANNEL, config.useChannel.trim())
            .apply()
    }

    // ------------------------------------------------------------------
    // v0.2.31 起：自动组网记忆（语义不变，1.x 沿用）
    //
    // - 点开始 → true → 下次冷启动 App 自动开始组网；
    // - 点停止 → false → 下次冷启动需用户手动点开启。
    // 退出应用 / 切后台结束进程**不动记忆**；远程 vnt_start / vnt_stop 同步写记忆。
    // ------------------------------------------------------------------

    fun autoStart(context: Context): Boolean = sp(context).getBoolean(K_AUTO_START, false)

    fun setAutoStart(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_AUTO_START, enabled).apply()
    }
}

/**
 * 1.x 服务端地址校验（见官方 `VntApp v1.2.9` 的 `AddActivity` / `MyVpnService`）。
 *
 * 合法：**裸 `host:port`**（如 `039039.xyz:31007`）。
 * 非法：任何 `://` 协议前缀（`udp://` / `tcp://` 等都不接受；1.x 用布尔 `tcp` 区分
 * UDP/TCP，而不是前缀）、空串、缺少端口或端口非数字。
 *
 * 返回可直接展示给用户的错误说明；`null` 表示合法。
 */
object VntServerAddress {
    fun error(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return "服务端地址不能为空"
        if (s.contains("://")) {
            return "1.x 只接受裸 host:port（如 039039.xyz:31007），不要带协议前缀；" +
                "UDP / TCP 请在下方用「连接协议」开关选择"
        }
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) {
            return "格式应为 host:port（如 039039.xyz:31007）"
        }
        val port = s.substring(idx + 1).toIntOrNull()
            ?: return "端口必须是数字（如 039039.xyz:31007）"
        if (port !in 1..65535) return "端口超出范围（1~65535）"
        return null
    }
}
