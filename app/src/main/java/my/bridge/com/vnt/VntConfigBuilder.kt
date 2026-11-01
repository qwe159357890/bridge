package my.bridge.com.vnt

import android.content.Context
import com.vnt.VntConfig

/**
 * 把 [VntMeshConfig] 装配成官方 `com.vnt.VntConfig`（v0.2.30）。
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
            deviceId = cfg.deviceId.ifBlank { VntMeshPrefs.stableDeviceId(context) },
        )

        // v0.2.31 防线：非法前缀（尤其 1.2.x 旧协议的 udp://）不能进 native。
        // 否则 VntManager.createNetwork() 会抛晦涩的
        // 「Unknown protocol in server address」，且用户反复点开始会一直刷错误日志。
        // 这里抛可读异常，由 VntVpnService.runConnect 的 catch 转成页面 ERROR 文案。
        VntServerAddress.error(config.server)?.let { throw IllegalArgumentException(it) }

        val builder = VntConfig.Builder()
            .addServer(config.server)
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
        //
        // 官方 Builder 接口与参数格式：
        //   addOutputRoute("真实CIDR")                     我访问对端 LAN
        //   addInputRoute("源网段CIDR,目标虚拟IP")          对端访问我的 LAN
        //   addSubnetMapping("映射CIDR,真实CIDR")          网段冲突时映射（真实范围须被 output 覆盖）
        //   setAutoSyncSubnet(Boolean)                     自动同步对端子网
        //   setAllowMapping(Boolean) + addPortMapping("tcp://0.0.0.0:81-10.26.0.2-192.168.1.10:80")
        //
        // 启用条件与风险：
        //   1. 对端 LAN 网段必须 addRoute 进 VpnService.Builder，而对端子网是运行期才知道的；
        //   2. 一变化就经 nextEvent() 吐出 needsVpnRebuild=true → 必须重建 VPN 接口
        //      （重新 establish() 拿新 fd），期间隧道短暂中断；
        //   3. 耗电几乎为 0 增量（纯数据面转发，无新增定时器），成本全在复杂度与 fd 切换。
        // 建议：将来有硬需求时只做 output 单向，可砍掉一半复杂度与大部分网段冲突。
        //
        // TODO(后续版本): 放开以下调用，并在 VntVpnService.establishVpn() 里把
        //   config.outputRoutes / subnetMappings 转成 addRoute 网段；同时处理 needsVpnRebuild。
        // ------------------------------------------------------------------
        // config.outputRoutes.forEach { builder.addOutputRoute(it) }
        // config.inputRoutes.forEach { builder.addInputRoute(it) }
        // config.subnetMappings.forEach { builder.addSubnetMapping(it) }
        // builder.setAutoSyncSubnet(config.autoSyncSubnet)
        // builder.setAllowMapping(config.allowMapping)
        // config.portMappings.forEach { builder.addPortMapping(it) }

        return builder.build()
    }
}
