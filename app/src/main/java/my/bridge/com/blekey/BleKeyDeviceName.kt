package my.bridge.com.blekey

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 设备名（对端 `SCAN` 列表「名称」列显示的就是它）。
 *
 * **为什么单独一个文件**：Android 没有「把自定义字符串写进广播」的 API。
 * `AdvertiseData.Builder` 只有 `setIncludeDeviceName(boolean)`，它塞进去的是
 * **系统蓝牙名**（`BluetoothAdapter.getName()`）。所以想带编号后缀，只能改系统名。
 *
 * 设计取舍（docs/research/ble-key-broadcast.md §4.1）：
 * - 默认**不动**系统名——广播的就是手机原名，功能完整（对端按 UUID 认钥匙，名字只用于看）；
 * - 想带编号就点「按编号改名」，**明确提示副作用**（配对过的耳机/车载都会看到新名字）后
 *   才调 `setName()`。绝不静默改全局设置。
 *
 * 约束：scan response 只有 31 字节，名字过长会被系统截断——**只影响对端显示，不影响匹配**。
 */
object BleKeyDeviceName {

    private const val TAG = "BleKeyName"

    /** 建议名长度上限（中文按 3 字节算，约 8 个字；超出仅在广播里被截断）。 */
    const val SUGGEST_MAX_LEN = 20

    private fun adapter(context: Context) =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    /** 读/改系统蓝牙名需要 BLUETOOTH_CONNECT（Android 12+）。 */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** 当前系统蓝牙名；无权限 / 蓝牙关 / 异常 → null。 */
    fun current(context: Context): String? {
        if (!hasPermission(context)) return null
        return runCatching { adapter(context)?.name }.getOrNull()
    }

    /**
     * 建议名 = 系统名去空格 + `-编号`（如 `OPPOReno9-03`）。
     *
     * 目的：同型号手机出厂名完全相同（`OPPOReno9` 有几千万台），带编号后缀能让人在
     * 对端列表里区分，且天然降低了「同名混淆」的概率。
     */
    fun suggest(context: Context, number: Int): String? {
        val base = current(context)?.filterNot { it.isWhitespace() }?.take(SUGGEST_MAX_LEN)
            ?.takeIf { it.isNotBlank() } ?: return null
        return "%s-%02d".format(base, number)
    }

    /** 改名。成功返回 true（系统会在下次广播时生效）。 */
    fun apply(context: Context, name: String): Boolean {
        if (!hasPermission(context)) return false
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            val ok = adapter(context)?.setName(clean) == true
            if (ok) Timber.tag(TAG).i("系统蓝牙名已改为 %s", clean)
            ok
        }.getOrElse {
            Timber.tag(TAG).w(it, "改名失败")
            false
        }
    }
}
