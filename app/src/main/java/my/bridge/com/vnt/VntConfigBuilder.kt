package my.bridge.com.vnt

import android.content.Context
import com.vnt.VntConfig

/**
 * 把 [VntMeshConfig] 装配成官方 `com.vnt.VntConfig`（v0.2.131，v2.0.12）。
 *
 * ⚠️ `com.vnt.*` 是官方 JNI 绑定，包名不可改（JNI 符号按包名+类名硬编码），
 * 且 release 开了 minify，已在 `proguard-rules.pro` 加 `-keep class com.vnt.** { *; }`。
 *
 * 调用时序见 `AndroidVpnExample.java`：
 * `VntManager.init()` → `createNetwork(config)` → `getNetwork()` → `VpnService.Builder` →
 * `startTun(fd)` → `getApi()` → `nextEvent()` 变更循环 → `stop()`。
 */
object VntConfigBuilder {

    fun build(context: Context, cfg: VntMeshConfig): VntConfig {
        // 网络编号 / 服务端是必填项，UI 层已校验；此处再兜一层防止空串进 native。
        val config = cfg.copy(
            server = cfg.server.ifBlank { VntMeshConfig.DEFAULT_SERVER },
            networkCode = cfg.networkCode.ifBlank { VntMeshConfig.DEFAULT_NETWORK_CODE },
            deviceId = cfg.deviceId.ifBlank { VntMeshPrefs.stableDeviceId(context) },
        )

        // v0.2.131 防线：非法前缀（尤其 1.2.x 旧协议的 udp://）不能进 native。
        // 否则 VntManager.createNetwork() 会抛晦涩的
        // 「Unknown protocol in server address」，且用户反复点开始会一直刷错误日志。
        VntServerAddress.error(config.server)?.let { throw IllegalArgumentException(it) }

        val builder = VntConfig.Builder()
        // 多地址容灾：server 字段可能是逗号分隔的多地址，逐个 addServer
        config.server.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { builder.addServer(it) }
        builder
            .setNetworkCode(config.networkCode)
            .setDeviceId(config.deviceId)
            .setDeviceName(config.deviceName)
            .setMtu(config.mtu)
            .setCompress(config.compress)
            .setFec(config.fec)
            .setRtx(config.rtx)
            .setNoPunch(config.noPunch)
            .setDeviceMode("tun")

        if (config.password.isNotEmpty()) builder.setPassword(config.password)
        if (config.ip.isNotBlank()) builder.setIp(config.ip)
        if (config.certMode.isNotBlank()) builder.setCertMode(config.certMode)

        // ------------------------------------------------------------------
        // ★ 预留位：子网转发 / 端口映射（本期不做，接口已核准，见调研报告 §9.3）
        // （略，见 docs/research/vnt-mesh-integration.md §九）
        // ------------------------------------------------------------------

        return builder.build()
    }
}
