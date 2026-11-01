package my.bridge.com.vnt

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** 单套 VNT 组网配置（已拍板：不做多配置切换）。 */
data class VntMeshConfig(
    /** 服务端地址，可带 tcp:// / wss:// / quic:// 前缀；裸 host:port 默认 UDP。 */
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
}
