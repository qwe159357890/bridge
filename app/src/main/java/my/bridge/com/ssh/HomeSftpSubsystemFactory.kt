package my.bridge.com.ssh

import java.nio.file.Path
import java.nio.file.Paths
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.sftp.server.SftpSubsystem
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/**
 * 自定义 SFTP 子系统工厂：把 SFTP 会话的初始目录（realpath "."）钉到指定 home。
 *
 * ★ 根因（javap 实证，sshd 2.20）：SftpSubsystem.setFileSystem 里
 *   defaultDir = fs.getPath("").toAbsolutePath().normalize()
 *   —— 取的是【进程工作目录】（Android 上 user.dir = "/"），
 *   NativeFileSystemFactory.usersHomeDir 只在 createHome=true 时用于建目录，
 *   对会话初始目录完全无效 → WinSCP/MobaXterm 连上落在 "/"，非 root 全无权限。
 *   覆写 getDefaultDirectory() 钉死 home，相对路径（"."）解析即以此为基准。
 */
class HomeSftpSubsystemFactory(private val homeDir: String) : SftpSubsystemFactory() {

    override fun createSubsystem(channel: ChannelSession): Command {
        val sub = object : SftpSubsystem(channel, this) {
            override fun getDefaultDirectory(): Path = Paths.get(homeDir)
        }
        // 照官方默认实现的装配顺序：把工厂上注册的监听器搬到子实例
        registeredListeners.forEach { sub.addSftpEventListener(it) }
        return sub
    }
}
