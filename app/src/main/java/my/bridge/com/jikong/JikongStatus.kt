package my.bridge.com.jikong

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 极空 BMS 状态快照（数据模型）。
 *
 * 所有字段均可空/缺省——BLE 未连接、固件不支持某字段、或该帧未解析到时保持
 * 默认值。管理后台与客户端页面据此渲染，真实数据由真机联调后逐步补全。
 */
data class JikongStatus(
    // ---- 连接信息 ----
    var deviceName: String = "",
    var deviceAddress: String = "",
    var connected: Boolean = false,
    var lastUpdateAt: Long = 0L,

    // ---- 实时总览 ----
    var totalVoltage: Float = 0f,        // 总压 V
    var current: Float = 0f,             // 电流 A（正=充电，负=放电）
    var soc: Int = 0,                    // 剩余电量百分比 %
    var remainCapacity: Float = 0f,      // 剩余容量 Ah
    var nominalCapacity: Float = 0f,     // 标称容量 Ah
    var power: Float = 0f,               // 功率 W

    // ---- 单体 ----
    var cellCount: Int = 0,              // 电池串数（自动探测）
    var cellVoltages: List<Float> = emptyList(),  // 各串电压
    var maxCellVoltage: Float = 0f,
    var minCellVoltage: Float = 0f,
    var maxCellIndex: Int = -1,
    var minCellIndex: Int = -1,
    var diffVoltage: Float = 0f,         // 压差

    // ---- 温度 ----
    var tempCount: Int = 0,              // 温度传感器数量
    var temperatures: List<Float> = emptyList(),
    var maxTemperature: Float = 0f,
    var minTemperature: Float = 0f,
    var tempDiff: Float = 0f,            // 温差

    // ---- 保护/告警状态 ----
    var protectionStates: Map<String, Boolean> = emptyMap(), // 保护项名 -> 是否触发
    var alarmStates: Map<String, Boolean> = emptyMap(),

    // ---- MOS / 均衡 ----
    var chargeMosOn: Boolean = false,    // 充电 MOS
    var dischargeMosOn: Boolean = false, // 放电 MOS
    var balanceOn: Boolean = false,      // 均衡开关
    var balanceCurrent: Float = 0f,      // 均衡电流

    // ---- 设备信息 ----
    var hardwareVersion: String = "",
    var softwareVersion: String = "",
    var model: String = "",
    var cycleCount: Int = 0,             // 循环次数
    var chargeCount: Int = 0,            // 充电次数

    // ---- 参数（可写，用于参数设置页）----
    var cellFullVoltage: Float = 0f,     // 单体过压保护
    var cellEmptyVoltage: Float = 0f,    // 单体欠压保护
    var chargeOverTemp: Float = 0f,      // 充电过温
    var dischargeOverTemp: Float = 0f,   // 放电过温
    var chargeCurrentLimit: Float = 0f,  // 充电限流
    var dischargeCurrentLimit: Float = 0f, // 放电限流
) {
    /** 序列化为上报 JSON（供 WS jikong_state 上报）。 */
    fun toJson(): JsonObject {
        val o = JsonObject()
        o.addProperty("device_name", deviceName)
        o.addProperty("device_address", deviceAddress)
        o.addProperty("connected", connected)
        o.addProperty("last_update_at", lastUpdateAt)
        o.addProperty("total_voltage", totalVoltage)
        o.addProperty("current", current)
        o.addProperty("soc", soc)
        o.addProperty("remain_capacity", remainCapacity)
        o.addProperty("nominal_capacity", nominalCapacity)
        o.addProperty("power", power)
        o.addProperty("cell_count", cellCount)
        val cv = JsonArray()
        cellVoltages.forEach { cv.add(it) }
        o.add("cell_voltages", cv)
        o.addProperty("max_cell_voltage", maxCellVoltage)
        o.addProperty("min_cell_voltage", minCellVoltage)
        o.addProperty("max_cell_index", maxCellIndex)
        o.addProperty("min_cell_index", minCellIndex)
        o.addProperty("diff_voltage", diffVoltage)
        o.addProperty("temp_count", tempCount)
        val tv = JsonArray()
        temperatures.forEach { tv.add(it) }
        o.add("temperatures", tv)
        o.addProperty("max_temperature", maxTemperature)
        o.addProperty("min_temperature", minTemperature)
        o.addProperty("temp_diff", tempDiff)
        o.add("protection_states", mapToJson(protectionStates))
        o.add("alarm_states", mapToJson(alarmStates))
        o.addProperty("charge_mos_on", chargeMosOn)
        o.addProperty("discharge_mos_on", dischargeMosOn)
        o.addProperty("balance_on", balanceOn)
        o.addProperty("balance_current", balanceCurrent)
        o.addProperty("hardware_version", hardwareVersion)
        o.addProperty("software_version", softwareVersion)
        o.addProperty("model", model)
        o.addProperty("cycle_count", cycleCount)
        o.addProperty("charge_count", chargeCount)
        o.addProperty("cell_full_voltage", cellFullVoltage)
        o.addProperty("cell_empty_voltage", cellEmptyVoltage)
        o.addProperty("charge_over_temp", chargeOverTemp)
        o.addProperty("discharge_over_temp", dischargeOverTemp)
        o.addProperty("charge_current_limit", chargeCurrentLimit)
        o.addProperty("discharge_current_limit", dischargeCurrentLimit)
        return o
    }

    private fun mapToJson(m: Map<String, Boolean>): JsonObject {
        val o = JsonObject()
        m.forEach { (k, v) -> o.addProperty(k, v) }
        return o
    }

    companion object {
        fun toJson(status: JikongStatus): String = Gson().toJson(status.toJson())
    }
}
