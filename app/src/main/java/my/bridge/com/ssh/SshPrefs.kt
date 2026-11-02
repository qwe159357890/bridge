package my.bridge.com.ssh

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import java.security.SecureRandom

/**
 * SSH 服务器持久化配置（v0.2.34）。
 *
 * 照 RelaySwitchPrefs / VntMeshPrefs 范式：object 单例 + SharedPreferences + apply()。
 *
 * ★ 核心约定（见调研报告 §6.3）：[KEY_ENABLED] 是「用户意愿」——开机自启、冷启动恢复、
 * START_STICKY 重建全部只看它；「运行事实」由 [SshServerController.state] 承载，两者分离。
 *
 * ★ 密码以明文存于应用私有 SharedPreferences（/data/data/my.bridge.com/）：状态页需要
 * 展示当前密码（用户拍板：首启随机生成 + 可随时查看/修改），应用沙箱内无更高权限读取者；
 * 若未来要提权可换哈希 + 仅展示不可见。内部测试分发，此取舍已确认可接受。
 */
object SshPrefs {

    private const val FILE = "ssh_server_prefs"
    private const val KEY_ENABLED = "ssh_enabled"
    private const val KEY_PORT = "ssh_port"
    private const val KEY_PASSWORD = "ssh_password"
    private const val KEY_WAKE_LOCK = "ssh_wake_lock"

    /** 非 root 不能绑 <1024；8022 为 Termux sshd 惯例端口（调研报告 §3.1 拍板）。 */
    const val DEFAULT_PORT = 8022

    /** 用户名固定 bridge（调研报告 §6.6 拍板，降低配置面）。 */
    const val USERNAME = "bridge"

    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 意愿与基础配置 ----

    fun enabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun port(context: Context): Int =
        sp(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)

    fun setPort(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()
    }

    /** 运行期 partial WakeLock（拍板：默认开；服务停止即释放）。 */
    fun wakeLockEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_WAKE_LOCK, true)
    fun setWakeLockEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_WAKE_LOCK, value).apply()
    }

    // ---- 访问密码（首启随机生成 + 持久化 + 可改）----

    /** 取当前密码；未初始化时随机生成并持久化（首次启动场景）。 */
    @Synchronized
    fun password(context: Context): String {
        val cur = sp(context).getString(KEY_PASSWORD, null)
        if (!cur.isNullOrBlank()) return cur
        val gen = generatePassword()
        sp(context).edit().putString(KEY_PASSWORD, gen).apply()
        return gen
    }

    /** 修改密码（≥8 位）；成功返回 true。 */
    fun setPassword(context: Context, value: String): Boolean {
        val v = value.trim()
        if (v.length < 8) return false
        sp(context).edit().putString(KEY_PASSWORD, v).apply()
        return true
    }

    /** 随机密码：3 组 × 5 位，去易混字符（0O1lI），中划线分组。 */
    fun generatePassword(): String {
        val rnd = SecureRandom()
        val group = { sb: StringBuilder ->
            repeat(5) { sb.append(PASSWORD_ALPHABET[rnd.nextInt(PASSWORD_ALPHABET.length)]) }
        }
        val a = StringBuilder().also(group)
        val b = StringBuilder().also(group)
        val c = StringBuilder().also(group)
        return "$a-$b-$c"
    }

    // ---- 存储暴露档（调研报告 §6.5：P0 直接申请全盘，未授权回退基础档）----

    /** 是否已获「所有文件访问」（MANAGE_EXTERNAL_STORAGE，Android 11+；低版本走 legacy 存储）。 */
    fun allFilesAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // API <30：WRITE_EXTERNAL_STORAGE（maxSdk 28）+ legacy 存储，/sdcard 可直接 File 访问
            true
        }
    }

    /** SFTP 根目录（用户 home）：全盘档 = /sdcard；基础档 = 应用外部私有目录。 */
    fun homeDir(context: Context): String {
        return if (allFilesAccessGranted()) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            context.getExternalFilesDir(null)?.absolutePath
                ?: context.filesDir.absolutePath
        }
    }

    fun storageModeText(): String = if (allFilesAccessGranted()) "全盘（/sdcard）" else "基础档（应用目录）"
}
