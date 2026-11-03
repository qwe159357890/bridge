package my.bridge.com.automation.commands

import android.content.Context
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandResult
import timber.log.Timber
import java.io.File

/**
 * 文件 + 媒体命令执行器（批次 4：AutoX Files + Media）。
 *
 * 文件操作限制在应用私有目录 + 公共存储（scoped storage 允许的路径），
 * 不越权访问其它应用数据。
 */
object FileMediaCommandExecutors {

    /** 解析文件路径：支持绝对路径，否则相对 App 私有 filesDir。 */
    private fun resolveFile(context: Context, path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(context.filesDir, path)
    }

    /** ui_file_read：读文本。 */
    fun fileRead(context: Context, command: Command): CommandResult {
        val path = command.strParam("path", "")
        if (path.isEmpty()) return CommandResult(command.commandId, command.command, false, "path 不能为空")
        return try {
            val f = resolveFile(context, path)
            if (!f.exists()) return CommandResult(command.commandId, command.command, false, "文件不存在：$path")
            val content = f.readText()
            CommandResult(command.commandId, command.command, true, content)
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读文件失败：${e.message}")
        }
    }

    /** ui_file_write / ui_file_append：写/追加文本。 */
    fun fileWrite(context: Context, command: Command, append: Boolean): CommandResult {
        val path = command.strParam("path", "")
        val content = command.strParam("content", "")
        if (path.isEmpty()) return CommandResult(command.commandId, command.command, false, "path 不能为空")
        return try {
            val f = resolveFile(context, path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            CommandResult(command.commandId, command.command, true, "已${if (append) "追加" else "写入"}：$path")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "写文件失败：${e.message}")
        }
    }

    /** ui_file_exists：文件是否存在。 */
    fun fileExists(context: Context, command: Command): CommandResult {
        val path = command.strParam("path", "")
        if (path.isEmpty()) return CommandResult(command.commandId, command.command, false, "path 不能为空")
        val exists = resolveFile(context, path).exists()
        return CommandResult(command.commandId, command.command, true, exists.toString())
    }

    /** ui_file_list：列目录。 */
    fun fileList(context: Context, command: Command): CommandResult {
        val path = command.strParam("path", ".")
        return try {
            val dir = resolveFile(context, path)
            if (!dir.exists() || !dir.isDirectory) {
                return CommandResult(command.commandId, command.command, false, "目录不存在：$path")
            }
            val list: List<File> = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            val sb = StringBuilder("[")
            list.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                sb.append("{\"name\":").append(CommandJson.jsonStr(f.name))
                sb.append(",\"isDir\":").append(f.isDirectory)
                sb.append(",\"size\":").append(f.length())
                sb.append("}")
            }
            sb.append("]")
            CommandResult(command.commandId, command.command, true, "共 ${list.size} 项：$sb")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "列目录失败：${e.message}")
        }
    }

    /** ui_file_copy / ui_file_move：复制/移动。 */
    fun fileCopyMove(context: Context, command: Command, move: Boolean): CommandResult {
        val src = command.strParam("src", "")
        val dst = command.strParam("dst", "")
        if (src.isEmpty() || dst.isEmpty()) {
            return CommandResult(command.commandId, command.command, false, "src/dst 不能为空")
        }
        return try {
            val s = resolveFile(context, src)
            val d = resolveFile(context, dst)
            if (!s.exists()) return CommandResult(command.commandId, command.command, false, "源不存在：$src")
            d.parentFile?.mkdirs()
            val ok = if (move) s.renameTo(d) else s.copyTo(d, overwrite = true).let { true }
            if (!ok) return CommandResult(command.commandId, command.command, false, "操作失败（跨存储移动可能失败，请用 copy）")
            CommandResult(command.commandId, command.command, true, "已${if (move) "移动" else "复制"}：$src → $dst")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "操作失败：${e.message}")
        }
    }

    /** ui_file_remove：删除文件。 */
    fun fileRemove(context: Context, command: Command): CommandResult {
        val path = command.strParam("path", "")
        if (path.isEmpty()) return CommandResult(command.commandId, command.command, false, "path 不能为空")
        return try {
            val f = resolveFile(context, path)
            if (!f.exists()) return CommandResult(command.commandId, command.command, false, "文件不存在：$path")
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            CommandResult(command.commandId, command.command, ok, if (ok) "已删除：$path" else "删除失败")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "删除失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 媒体（复用 MusicPlayer）
    // ------------------------------------------------------------------

    /** ui_media_seek / volume / position / duration / state：音乐播放控制增强。 */
    fun mediaControl(context: Context, command: Command, op: String): CommandResult {
        return try {
            val player = my.bridge.com.media.MusicPlayer
            when (op) {
                "seek" -> {
                    val m = command.intParam("position", 0, 0, Int.MAX_VALUE)
                    player.seekTo(m)
                    CommandResult(command.commandId, command.command, true, "已跳转到 $m 毫秒")
                }
                "volume" -> {
                    val v = command.intParam("volume", -1, 0, 100)
                    if (v < 0) return CommandResult(command.commandId, command.command, false, "volume 必须 0~100")
                    player.setVolume(v)
                    CommandResult(command.commandId, command.command, true, "已设置音量 $v")
                }
                "position" -> CommandResult(command.commandId, command.command, true, player.currentPosition().toString())
                "duration" -> CommandResult(command.commandId, command.command, true, player.duration().toString())
                "state" -> CommandResult(command.commandId, command.command, true, player.state.name)
                else -> CommandResult(command.commandId, command.command, false, "未知媒体操作：$op")
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "媒体操作失败：${e.message}")
        }
    }
}
