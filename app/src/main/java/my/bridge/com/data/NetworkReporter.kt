package my.bridge.com.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import timber.log.Timber

/**
 * 网络状态读取器（get_network 命令核心）。
 *
 * 实现借鉴 autoxClient `network_watcher.getStatus` 的一次性查询路径：
 * `ConnectivityManager.activeNetwork` + `NetworkCapabilities` + `LinkProperties`。
 * ACCESS_NETWORK_STATE 为普通权限（声明即得，Manifest 已有）；
 * 蜂窝制式需 READ_PHONE_STATE、WiFi 名称需精确定位权限（均已在批量申请里，
 * 未授权或 ROM 限制时对应项显示「未知」，不影响其余信息）。
 */
object NetworkReporter {

    /** 读取网络状态，返回人类可读回执。 */
    fun read(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("设备网络服务不可用")
        val network = cm.activeNetwork
            ?: return "当前无网络连接（飞行模式或全部网络已关闭）"

        val caps = cm.getNetworkCapabilities(network)
        val lp = cm.getLinkProperties(network)

        val typeText = describeTransport(caps)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val netText = "$typeText（${if (validated) "已确认可上网" else "已连接，未确认可上网"}）"

        val msg = buildString {
            append("网络类型：$netText")
            val ip = firstIPv4(lp)
            if (ip != null) append("｜IP：$ip")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                append("｜WiFi 名称：${wifiSsid(context)}")
            }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                append("｜蜂窝制式：${cellularType(context)}")
            }
        }
        Timber.i("网络状态：%s", msg)
        return msg
    }

    /** 传输层描述（VPN 标注承载网，如「VPN（over WiFi）」）。 */
    private fun describeTransport(caps: NetworkCapabilities?): String {
        if (caps == null) return "未知"
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙网络"
            else -> "其他网络"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "VPN（over $base）" else base
    }

    /** LinkProperties 里第一个非回环 IPv4 地址。 */
    private fun firstIPv4(lp: LinkProperties?): String? {
        if (lp == null) return null
        return try {
            lp.addresses.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * WiFi SSID（Android 8.1+ 需精确定位权限 + 设备定位服务开启）。
     * 三个失败面（无权限/定位关/ROM 隐私化）统一返回「未知（需定位权限且开启定位）」。
     */
    private fun wifiSsid(context: Context): String = try {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = wm?.connectionInfo?.ssid
            ?.trim()
            ?.removeSurrounding("\"")
            .orEmpty()
        if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x") {
            "未知（需定位权限且开启定位）"
        } else {
            ssid
        }
    } catch (e: Exception) {
        Timber.w(e, "读取 WiFi SSID 失败")
        "未知（需定位权限且开启定位）"
    }

    /** 蜂窝数据网络制式（READ_PHONE_STATE；无权限/无 SIM 时「未知」）。 */
    private fun cellularType(context: Context): String = try {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return "未知"
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G (NR)"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "未知"
            else -> "未知"
        }
    } catch (e: Exception) {
        Timber.w(e, "读取蜂窝制式失败（可能无 READ_PHONE_STATE）")
        "未知"
    }
}
