package my.bridge.com.ssh

import android.content.Context
import my.bridge.com.core.common.DiagLog
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File

/**
 * SSH 主机密钥（hostkey）管理（v0.2.34）。
 *
 * 主机密钥 = 服务端自生成的「身份」密钥对（非 TLS/CA 证书，见调研报告 §十澄清）：
 * 私钥永驻手机，客户端首次连接时核对公钥指纹（TOFU 机制）。**必须持久化**——
 * 每次启动重生成会让所有客户端反复弹「主机密钥已更改」告警（调研报告 §八坑 6）。
 *
 * 实现：MINA SSHD 自带的 [SimpleGeneratorHostKeyProvider]——首次访问生成 EC(P-256) 密钥对
 * 写入 filesDir/ssh/hostkeys/hostkey.ser，之后每次启动从文件加载（默认不覆盖已存在文件）。
 *
 * ★ 2.20.0 包路径实证（javap 核对）：KeyUtils 在 `common.config.keys`（非 common.util.keys）。
 */
object SshHostKeyStore {

    private const val TAG = "SSH"

    /** hostkey 文件所在目录（应用私有，卸载重装即重置，客户端会重新确认一次指纹）。 */
    fun hostKeyDir(context: Context): File =
        File(context.filesDir, "ssh/hostkeys").apply { mkdirs() }

    fun provider(context: Context): SimpleGeneratorHostKeyProvider =
        SimpleGeneratorHostKeyProvider().apply {
            // setPath(java.nio.file.Path)：API 26+ 的类型，靠 core library desugaring 支持
            path = File(hostKeyDir(context), "hostkey.ser").toPath()
            algorithm = "EC"
        }

    /**
     * 公钥指纹（SHA256，与 `ssh` 客户端首次连接弹出的格式一致），供状态页展示核对。
     * 读取失败（如密钥文件尚未生成）返回 null，UI 显示「不可用」。
     */
    fun fingerprint(context: Context): String? {
        return try {
            val pairs = provider(context).loadKeys(null) ?: return null
            val pub = pairs.firstOrNull()?.public ?: return null
            KeyUtils.getFingerPrint(BuiltinDigests.sha256, pub)
        } catch (e: Throwable) {
            DiagLog.e(TAG, "读取 hostkey 指纹失败", e)
            null
        }
    }
}
