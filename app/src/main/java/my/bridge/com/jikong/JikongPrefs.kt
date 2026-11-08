package my.bridge.com.jikong

import android.content.Context
import android.content.SharedPreferences

/**
 * 极空页面显示偏好持久化。
 *
 * 背景主题三态：
 *  - THEME_AUTO（默认）：跟随当前时间——白天（6:00~18:00）浅色、夜晚深色；
 *  - THEME_LIGHT：强制浅色；
 *  - THEME_DARK：强制深色。
 *
 * 优先级：用户手动指定（LIGHT/DARK）高于自动（AUTO）。AUTO 才跟随时间。
 */
object JikongPrefs {

    private const val SP_NAME = "jikong_config"

    const val KEY_THEME = "jikong_theme"

    /** 时速仪表盘开关（v0.2.83）：开=首页显示时速/续航卡片并启动 GPS；关=完全零成本。 */
    const val KEY_DASH_ENABLED = "jikong_dash_enabled"

    /** 累计统计（SpeedDash 持久化）：总里程 km / 总耗电 Wh / 历史平均电耗 Wh/km（暖启动种子）。 */
    private const val KEY_TOTAL_KM = "jikong_total_km"
    private const val KEY_TOTAL_WH = "jikong_total_wh"
    private const val KEY_HIST_AVG = "jikong_hist_avg_whkm"

    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 背景主题模式（THEME_AUTO/LIGHT/DARK）。默认自动。 */
    fun theme(context: Context): Int =
        sp(context).getInt(KEY_THEME, THEME_AUTO)

    fun setTheme(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode.coerceIn(THEME_AUTO, THEME_DARK)).apply()
    }

    /** 时速仪表盘开关。默认关（尊重省电，用户显式打开才启用 GPS）。 */
    fun dashEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_DASH_ENABLED, false)

    fun setDashEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    fun totalKm(context: Context): Float = sp(context).getFloat(KEY_TOTAL_KM, 0f)
    fun setTotalKm(context: Context, km: Float) {
        sp(context).edit().putFloat(KEY_TOTAL_KM, km.coerceAtLeast(0f)).apply()
    }

    fun totalWh(context: Context): Float = sp(context).getFloat(KEY_TOTAL_WH, 0f)
    fun setTotalWh(context: Context, wh: Float) {
        sp(context).edit().putFloat(KEY_TOTAL_WH, wh.coerceAtLeast(0f)).apply()
    }

    /** 历史平均电耗（Wh/km）。<0 = 还没有可用历史。 */
    fun histAvgWhKm(context: Context): Float = sp(context).getFloat(KEY_HIST_AVG, -1f)
    fun setHistAvgWhKm(context: Context, whkm: Float) {
        sp(context).edit().putFloat(KEY_HIST_AVG, whkm).apply()
    }

    // ==================== 语音播报（v0.2.88） ====================

    const val KEY_SPEAK_ENABLED = "jikong_speak_enabled"
    const val KEY_SPEAK_INTERVAL_MIN = "jikong_speak_interval_min"
    const val KEY_SPEAK_LOW_SOC = "jikong_speak_low_soc"
    const val KEY_SPEAK_WARN_REPEAT_MIN = "jikong_speak_warn_repeat_min"

    /** 上次播报的 SOC 与时间戳（跨连接判定用；持久化防进程重启误播）。 */
    private const val KEY_LAST_SPOKEN_SOC = "jikong_last_spoken_soc"
    private const val KEY_LAST_SPOKEN_AT = "jikong_last_spoken_at"

    /** 语音播报开关。默认关。 */
    fun speakEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_SPEAK_ENABLED, false)

    fun setSpeakEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_SPEAK_ENABLED, enabled).apply()
    }

    /** 播报间隔（分钟，1~120）。默认 10。 */
    fun speakIntervalMin(context: Context): Int =
        sp(context).getInt(KEY_SPEAK_INTERVAL_MIN, 10)

    fun setSpeakIntervalMin(context: Context, min: Int) {
        sp(context).edit().putInt(KEY_SPEAK_INTERVAL_MIN, min.coerceIn(1, 120)).apply()
    }

    /** 低电量阈值（%，1~50），低于此值播报追加提醒。默认 20。 */
    fun speakLowSoc(context: Context): Int =
        sp(context).getInt(KEY_SPEAK_LOW_SOC, 20)

    fun setSpeakLowSoc(context: Context, soc: Int) {
        sp(context).edit().putInt(KEY_SPEAK_LOW_SOC, soc.coerceIn(1, 50)).apply()
    }

    /**
     * 低电量预警重复提醒间隔（分钟，1~60）。默认 5（v0.2.89）。
     * 低于阈值期间，即使电量不再变化也按此间隔重复播报带提醒。
     */
    fun speakWarnRepeatMin(context: Context): Int =
        sp(context).getInt(KEY_SPEAK_WARN_REPEAT_MIN, 5)

    fun setSpeakWarnRepeatMin(context: Context, min: Int) {
        sp(context).edit().putInt(KEY_SPEAK_WARN_REPEAT_MIN, min.coerceIn(1, 60)).apply()
    }

    /** 上次播报的 SOC（-1 = 从未播过）。 */
    fun lastSpokenSoc(context: Context): Int =
        sp(context).getInt(KEY_LAST_SPOKEN_SOC, -1)

    fun lastSpokenAt(context: Context): Long =
        sp(context).getLong(KEY_LAST_SPOKEN_AT, 0L)

    fun setLastSpoken(context: Context, soc: Int, at: Long = System.currentTimeMillis()) {
        sp(context).edit()
            .putInt(KEY_LAST_SPOKEN_SOC, soc)
            .putLong(KEY_LAST_SPOKEN_AT, at)
            .apply()
    }

    /** 上次「带低电提醒」的播报时间（0 = 从未；v0.2.89 预警重复提醒间隔判定用）。 */
    private const val KEY_LAST_WARN_AT = "jikong_last_warn_at"

    fun lastWarnAt(context: Context): Long =
        sp(context).getLong(KEY_LAST_WARN_AT, 0L)

    fun setLastWarnAt(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_LAST_WARN_AT, at).apply()
    }

    // ==================== 设备昵称 / 默认设备（v0.2.88） ====================

    /** 昵称表（MAC → 昵称），JSON 对象存单键。 */
    private const val KEY_NICKNAMES = "jikong_nicknames"

    /** 默认设备 MAC（空 = 未设置）。 */
    private const val KEY_DEFAULT_MAC = "jikong_default_mac"

    fun nicknames(context: Context): Map<String, String> = try {
        val obj = org.json.JSONObject(sp(context).getString(KEY_NICKNAMES, "{}") ?: "{}")
        val out = LinkedHashMap<String, String>()
        obj.keys().forEach { mac -> out[mac] = obj.optString(mac) }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    /** 指定 MAC 的昵称（无则 null）。 */
    fun nickname(context: Context, mac: String): String? =
        nicknames(context)[mac]?.takeIf { it.isNotBlank() }

    /** 设置昵称；name 空白 = 清除该 MAC 的昵称。 */
    fun setNickname(context: Context, mac: String, name: String) {
        val obj = try {
            org.json.JSONObject(sp(context).getString(KEY_NICKNAMES, "{}") ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        if (name.isBlank()) obj.remove(mac) else obj.put(mac, name.trim())
        sp(context).edit().putString(KEY_NICKNAMES, obj.toString()).apply()
    }

    fun defaultMac(context: Context): String =
        sp(context).getString(KEY_DEFAULT_MAC, "") ?: ""

    fun setDefaultMac(context: Context, mac: String) {
        sp(context).edit().putString(KEY_DEFAULT_MAC, mac.trim()).apply()
    }

    // ==================== 显示单位 / 里程折扣 / 电费（v0.2.90） ====================

    /** 电耗显示单位：false = Wh/km（默认），true = kWh/100km。只改显示层，内部一律 Wh/km。 */
    private const val KEY_UNIT_KWH100 = "jikong_unit_kwh100"

    fun unitKwh100(context: Context): Boolean =
        sp(context).getBoolean(KEY_UNIT_KWH100, false)

    fun setUnitKwh100(context: Context, kwh100: Boolean) {
        sp(context).edit().putBoolean(KEY_UNIT_KWH100, kwh100).apply()
    }

    /** 三轮车里程折扣（0.1~1.0，默认 1.0）：表显里程 × 折扣 = 真实里程。只影响统计页显示层。 */
    private const val KEY_ODO_DISCOUNT = "jikong_odo_discount"

    fun odoDiscount(context: Context): Float =
        sp(context).getFloat(KEY_ODO_DISCOUNT, 1f).coerceIn(0.1f, 1f)

    fun setOdoDiscount(context: Context, discount: Float) {
        sp(context).edit().putFloat(KEY_ODO_DISCOUNT, discount.coerceIn(0.1f, 1f)).apply()
    }

    /** 电费单价（元/kWh，0.05~10.0，默认 0.6）。统计页花费显示用。 */
    private const val KEY_ELEC_PRICE = "jikong_elec_price"

    fun elecPrice(context: Context): Float =
        sp(context).getFloat(KEY_ELEC_PRICE, 0.6f).coerceIn(0.05f, 10f)

    fun setElecPrice(context: Context, price: Float) {
        sp(context).edit().putFloat(KEY_ELEC_PRICE, (price.coerceIn(0.05f, 10f) * 100).toInt() / 100f).apply()
    }

    // ==================== 静音 / TTS（v0.2.90） ====================

    /** 小喇叭静音（持久化）：true = 静音，一切播报停止（唯一例外=进程启动后首次强制播报，见 Controller）。 */
    private const val KEY_MUTED = "jikong_muted"

    fun muted(context: Context): Boolean = sp(context).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        sp(context).edit().putBoolean(KEY_MUTED, muted).apply()
    }

    /** edge-tts 在线合成音色（服务端 /api/tts 用；本地回退不受影响）。 */
    const val TTS_VOICE = "zh-CN-XiaoxiaoNeural"

    // ==================== 播报音量提升（v0.2.115） ====================

    /**
     * 音量提升主开关（默认开）：播报前临时把媒体音量拉到最大、播完恢复。
     * 开启后走「智能检测」——播报前查有无其他程序在放声音，没有才提升（避免打断听歌/看视频/导航）。
     */
    private const val KEY_VOLUME_BOOST_ENABLED = "jikong_volume_boost_enabled"

    /**
     * 强制提升子开关（默认关）：打开后跳过「其他程序在放声音」检测，所有播报只要音量不是最大就拉满。
     */
    private const val KEY_VOLUME_BOOST_FORCE = "jikong_volume_boost_force"

    fun volumeBoostEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_VOLUME_BOOST_ENABLED, true)

    fun setVolumeBoostEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_VOLUME_BOOST_ENABLED, enabled).apply()
    }

    fun volumeBoostForce(context: Context): Boolean =
        sp(context).getBoolean(KEY_VOLUME_BOOST_FORCE, false)

    fun setVolumeBoostForce(context: Context, force: Boolean) {
        sp(context).edit().putBoolean(KEY_VOLUME_BOOST_FORCE, force).apply()
    }

    // ==================== 靠近自动连接（v0.2.107） ====================

    /** 靠近自动连接开关。默认关（保持现状：必须进极空页才触发连接/回连）。 */
    const val KEY_PROXIMITY_AUTO_CONNECT = "jikong_proximity_auto_connect"

    /**
     * 开=对默认设备挂 GATT autoConnect 后台监听（蓝牙控制器低占空比监听广播，
     * 靠近即自动连接，详见 docs/research/jikong-proximity-autoconnect.md）；
     * 关=完全维持现状。未设默认设备时开=无效（UI 层回弹提示）。
     */
    fun proximityAutoConnect(context: Context): Boolean =
        sp(context).getBoolean(KEY_PROXIMITY_AUTO_CONNECT, false)

    fun setProximityAutoConnect(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_PROXIMITY_AUTO_CONNECT, enabled).apply()
    }
}
