package my.bridge.com.vnt

import android.content.Context
import top.wherewego.vnt.jni.Config

/**
 * 把 [VntMeshConfig] 装配成官方 1.x `top.wherewego.vnt.jni.Config`（v0.2.32）。
 *
 * ⚠️ `top.wherewego.vnt.jni.*` 是官方 VntApp `v1.2.9` 的 JNI 绑定，**包名不可改**
 * （so 里的符号按包名+类名硬编码：`Java_top_wherewego_vnt_jni_Vnt_new0`）。
 * release 开了 minify，`proguard-rules.pro` 已加 `-keep class top.wherewego.vnt.jni.** { *; }`。
 *
 * 字段默认值对齐官方 `AddActivity.save()`：cipherModel=aes_gcm、serverEncrypt=true、
 * finger=false、firstLatency=false、三个固定 STUN、mtu 空则底层按 1410 兜底。
 *
 * 注意 `Config.deviceName` 是**虚拟网卡名（仅桌面端有效）**，Android 不用；
 * 设备名走 `Config.name`。别把两者搞混。
 */
object VntConfigBuilder {

    fun build(context: Context, cfg: VntMeshConfig): Config {
        val config = cfg.copy(
            server = cfg.server.ifBlank { VntMeshConfig.DEFAULT_SERVER },
            deviceId = cfg.deviceId.ifBlank { VntMeshPrefs.stableDeviceId(context) },
        )

        // 防线：非法地址 / 空 token 不进 native，否则 JNI 侧报错晦涩且难定位。
        VntServerAddress.error(config.server)?.let { throw IllegalArgumentException(it) }
        if (config.token.isBlank()) {
            throw IllegalArgumentException(
                "组网编号（Token）不能为空：请与存量 1.x 客户端填同一个编号，否则无法互通"
            )
        }

        val c = Config()
        c.token = config.token.trim()
        c.server = config.server.trim()
        c.name = config.deviceName.trim().ifBlank { "Bridge" }
        c.deviceId = config.deviceId
        c.cipherModel = config.cipherModel.trim().ifBlank { VntMeshConfig.DEFAULT_CIPHER }
        c.mtu = config.mtu.coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU)

        // ★ 布尔字段的 Java getter 是 isXxx()，Kotlin 合成属性名会是 isTcp / isServerEncrypt
        //   / isFinger / isFirstLatency；写 `c.tcp = ...` 会被解析成同名的**私有 Java 字段**
        //   而报 "Cannot access 'field tcp'"。这里统一显式调 setXxx() 规避。
        c.setTcp(config.tcp)                     // false = UDP（用户存量 1.2.13 的选择）
        c.setServerEncrypt(config.serverEncrypt)
        c.setFinger(config.finger)
        c.setFirstLatency(config.firstLatency)
        c.stunServer = (config.stunServers.ifEmpty { VntMeshConfig.DEFAULT_STUN }).toTypedArray()

        // 可选字段：留空就是 null（底层用默认值），绝不塞空串
        if (config.password.isNotBlank()) c.password = config.password
        if (config.ip.isNotBlank()) c.ip = config.ip.trim()
        if (config.punchModel.isNotBlank()) c.punchModel = config.punchModel.trim()
        if (config.useChannel.isNotBlank()) c.useChannel = config.useChannel.trim()

        // 本期不做：dns / portMapping / inIps / outIps / ports / packetLossRate /
        // packetDelay / tap / deviceName（虚拟网卡名），保持底层默认值。
        // 子网转发 / 端口映射仍是「本期不做但保留」的预留位（见报告 §9.3）。
        return c
    }
}
