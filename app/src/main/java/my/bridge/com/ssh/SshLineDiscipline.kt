package my.bridge.com.ssh

import my.bridge.com.core.common.DiagLog
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 无 PTY 环境下的迷你「行规程」（line discipline）。
 *
 * 背景（2026-11-02 真机排障）：sshd 的 ProcessShell 用管道对接子进程，不分配真实 PTY。
 * 终端回显、Ctrl+C、\r→\n 翻译这些本应由内核行规程完成的事全部缺失，表现为：
 * - 客户端（MobaXterm 等）打字无回显（SSH 协议下客户端默认不做本地回显）
 * - 回车发送的 \r 不会被翻成 \n，mksh 读管道不认 \r，命令永远不执行
 *
 * 本类在「客户端 stdin → shell 进程」之间做字节级过滤，模拟规范模式（canonical mode）：
 * - 可打印字符：缓冲 + 回显
 * - 回车（\r/\n）：提交整行（以 \n 交给 mksh）+ 回显 \r\n
 * - 退格（DEL/BS）：删缓冲最后一字符（含 UTF-8 整字符）+ 回显 "\b \b"
 * - Ctrl+U 清行、Ctrl+C 清行并回显 ^C（无法真正 SIGINT，见下）、Ctrl+D 空行=EOF
 * - ESC 序列（方向键等）：吞掉，避免进入命令行变成乱码
 *
 * 已知取舍：无 PTY 就无法给前台进程发信号，Ctrl+C 只能清行不能杀进程（关会话即可终止）；
 * 作业控制（Ctrl+Z / bg / fg）不可用；需逐键交互的程序（vim 全屏）降级。
 * 这是无 PTY 的固有限制，非本类缺陷。
 */
class LineDisciplineInputStream(
    private val src: InputStream,
    private val echoSink: OutputStream,
) : InputStream() {

    private val line = ArrayList<Byte>(128)
    private var pending: ByteArray = EMPTY
    private var pendingPos = 0
    private var inEscape = false   // 已收到 ESC
    private var inCsi = false      // 已收到 ESC [
    private var eofSeen = false    // 底层已 EOF

    /**
     * ★ 关键重写：InvertedShellWrapper 的泵线程轮转三个流时完全依赖 available() 判断
     * 有无数据（javap pumpStream 实证：available()<=0 直接跳过该流）。默认实现恒返回 0
     * 会导致 stdin 永远不被泵送（真机首次实测踩坑）。这里只暴露「已提交待转发」的字节——
     * 行缓冲中（尚未回车）返回 0 是正确语义：半行本来就不该流向 shell。
     */
    override fun available(): Int {
        if (pendingPos < pending.size) return pending.size - pendingPos
        return if (eofSeen) -1 else 0
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (pendingPos < pending.size) {
                val n = minOf(len, pending.size - pendingPos)
                System.arraycopy(pending, pendingPos, b, off, n)
                pendingPos += n
                return n
            }
            pending = EMPTY
            pendingPos = 0
            val c = src.read()
            if (c < 0) {
                eofSeen = true
                return -1
            }
            val forward = process(c) ?: return -1 // null = EOF（Ctrl+D 空行）
            if (forward.isNotEmpty()) {
                pending = forward
            }
        }
    }

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n < 0) -1 else buf[0].toInt() and 0xFF
    }

    /** 处理一个输入字节：做行规程变换；返回要转发给 shell 的字节（null=EOF，空=吞掉）。 */
    private fun process(c: Int): ByteArray? {
        // ESC 序列吞除：ESC [ ... final(0x40-0x7E)，防止方向键等变成命令行乱码
        if (inCsi) {
            if (c in 0x40..0x7E) inCsi = false
            return EMPTY_BYTES
        }
        if (inEscape) {
            inEscape = false
            if (c == 0x5B) { // '['
                inCsi = true
                return EMPTY_BYTES
            }
            return EMPTY_BYTES // 其他 ESC x 序列同样吞掉
        }

        when (c) {
            0x1B -> { inEscape = true; return EMPTY_BYTES }
            0x0D, 0x0A -> { // 回车：提交整行，\r 统一翻成 \n
                val data = ByteArray(line.size + 1)
                for (i in line.indices) data[i] = line[i]
                data[line.size] = '\n'.code.toByte()
                line.clear()
                echo("\r\n")
                return data
            }
            0x7F, 0x08 -> { eraseLast(); return EMPTY_BYTES }
            0x03 -> { line.clear(); echo("^C\r\n"); return EMPTY_BYTES }
            0x15 -> { line.clear(); echo("\r\n"); return EMPTY_BYTES } // Ctrl+U 清行
            0x04 -> { // Ctrl+D：空行 = EOF（mksh 退出）；非空 = 提交当前行（不带换行）
                if (line.isEmpty()) {
                    echo("^D\r\n")
                    return null
                }
                val data = ByteArray(line.size)
                for (i in line.indices) data[i] = line[i]
                line.clear()
                return data
            }
        }

        if (c == 0x09 || c in 0x20..0x7E || c >= 0x80) {
            line.add(c.toByte())
            echoByte(c)
            return EMPTY_BYTES
        }
        return EMPTY_BYTES // 其余控制字符吞掉
    }

    /** 删除最后一个「字符」（UTF-8 整字符），并回显对应宽度的退格擦除。 */
    private fun eraseLast() {
        if (line.isEmpty()) return
        var count = 1
        while (count < line.size && (line[line.size - count].toInt() and 0xC0) == 0x80) count++
        // 宽字符（CJK 等首字节 >=0xE0）占两列，需擦两次才能清干净
        val lead = line[line.size - count].toInt() and 0xFF
        val cols = if (lead >= 0xE0) 2 else 1
        repeat(count) { line.removeAt(line.size - 1) }
        repeat(cols) { echo("\b \b") }
    }

    private fun echo(s: String) {
        try {
            echoSink.write(s.toByteArray(Charsets.UTF_8))
            echoSink.flush()
        } catch (_: IOException) {
            // 会话已断开等，静默（外层泵线程会随之结束）
        }
    }

    private fun echoByte(c: Int) {
        try {
            echoSink.write(c)
            echoSink.flush()
        } catch (_: IOException) {
            // 同上
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val EMPTY_BYTES = ByteArray(0)
    }
}

/**
 * 交互式 shell 命令 = ProcessBuilder + 迷你行规程（推送式泵）。
 *
 * ★ 为什么不用 InvertedShellWrapper（真机两次实测踩坑后确立）：
 * 它的泵线程轮转三条流完全依赖 InputStream.available()（javap pumpStream 实证：
 * available()<=0 直接跳过该流）。行规程必须整行缓冲，而「行未提交时 available()==0、
 * 又只有 read() 被调用才能消费字节提交行」——拉模式下两者互为死锁，字节永远到不了 shell。
 *
 * 本类改用推送式泵：三条流各一个专用阻塞读线程，
 * - stdin：阻塞读 channel 输入 → [LineDisciplineInputStream] 行规程（回显/退格/\r→\n）→ 进程 stdin
 * - stdout/stderr：进程输出 → channel
 * - 守护线程：等进程退出 → ExitCallback
 */
class EchoShellCommand(
    private val argv: List<String>,
    private val workDir: String? = null,
) : Command {

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var err: OutputStream? = null
    private var exit: ExitCallback? = null
    private var process: java.lang.Process? = null

    override fun setInputStream(input: InputStream?) { this.input = input }
    override fun setOutputStream(output: OutputStream?) { this.output = output }
    override fun setErrorStream(error: OutputStream?) { this.err = error }
    override fun setExitCallback(callback: ExitCallback?) { this.exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        val pb = ProcessBuilder(argv)
        if (workDir != null) {
            pb.directory(java.io.File(workDir))
            // ★ HOME 必须显式设置（0.2.36 补充）：App 进程环境无 HOME，而 mksh 的
            //   `cd ~`、裸 `cd`、~ 路径展开全依赖 $HOME——不设则这些快捷方式全失效。
            //   （`cd -` 走 OLDPWD，是 shell 自管的开箱即用能力，与此无关。）
            pb.environment()["HOME"] = workDir
        }
        pb.environment().putIfAbsent("PATH", "/system/bin:/system/xbin:/vendor/bin")
        // ★ stderr 合并进 stdout（OS 层）：真机实测 sshd 提供的独立 err 流写不达客户端
        //   （CHILD_ERR 实验实锤），而 mksh -i 的警告/提示符全走 stderr —— 合并后统一走
        //   已验证可用的 stdout 链路。对终端场景无损失（客户端本来就合并显示）。
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: IOException) {
            DiagLog.e("EchoShell", "进程创建失败：$e")
            throw e
        }
        process = p
        // 回显线程（stdin 行规程）与 stdout 泵线程并发写同一输出流 → 统一加锁防交错
        val o = SynchronizedOutputStream(checkNotNull(output))
        DiagLog.i("EchoShell", "进程已启动：$argv err=${if (err == null) "null" else "set"}")

        // stdin：阻塞读 → 行规程 → 进程 stdin（行规程在线程内回显到 o）
        // ▼ 临时诊断：记录每次写入子进程的内容
        val stdinLog = object : java.io.FilterOutputStream(p.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                DiagLog.i("EchoShell", "stdin→子进程 ${len}B: " + String(b, off, len).replace("\n", "<LF>"))
                out.write(b, off, len)
                out.flush()
            }
        }
        Thread {
            try {
                LineDisciplineInputStream(checkNotNull(input), o).use { it.copyTo(stdinLog) }
                DiagLog.i("EchoShell", "stdin 线程正常结束（EOF）")
            } catch (ex: Exception) {
                DiagLog.w("EchoShell", "stdin 线程结束：${ex.javaClass.simpleName}: ${ex.message}")
            } finally {
                try { p.outputStream.close() } catch (_: IOException) { }
            }
        }.apply { isDaemon = true; name = "ssh-stdin-line"; start() }

        // stdout（已合并 stderr）→ channel。
        // ★ 附加 \n→\r\n 翻译（0.2.36 六次修复，真机阶梯状缩进实锤）：无 PTY 时子进程
        //   输出裸 LF，真实 PTY 的 ONLCR 会在 LF 前补 \r，终端才回到行首——缺失即
        //   MobaXterm 上的「楼梯」。按字节扫描，仅对前面无 \r 的裸 LF 补 \r（避免 \r\n→\r\r\n）。
        Thread {
            try {
                val src = p.inputStream
                val buf = ByteArray(8192)
                var prevCr = false
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    var start = 0
                    for (i in 0 until n) {
                        if (buf[i] == LF_BYTE && !prevCr) {
                            if (i > start) o.write(buf, start, i - start)
                            o.write(CRLF_BYTES)
                            start = i + 1
                        }
                        prevCr = buf[i] == CR_BYTE
                    }
                    if (n > start) o.write(buf, start, n - start)
                    o.flush()
                }
                DiagLog.i("EchoShell", "stdout 泵正常结束（EOF）")
            } catch (ex: Exception) {
                DiagLog.w("EchoShell", "stdout 泵结束：${ex.javaClass.simpleName}: ${ex.message}")
            }
        }.apply { isDaemon = true; name = "ssh-stdout-pump"; start() }

        // 进程退出 → 通知 sshd 关闭通道
        Thread {
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            DiagLog.i("EchoShell", "进程退出：code=$code")
            try { exit?.onExit(code and 0xFF) } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "ssh-exit-watch"; start() }
    }

    override fun destroy(channel: ChannelSession?) {
        process?.destroy()
    }

    private companion object {
        const val CR_BYTE: Byte = 0x0D
        const val LF_BYTE: Byte = 0x0A
        val CRLF_BYTES = byteArrayOf(0x0D, 0x0A)
    }
}

/** 多线程写保护：行规程回显线程与 stdout 泵线程共用同一 channel 输出流，防止字节交错。 */
class SynchronizedOutputStream(private val sink: OutputStream) : OutputStream() {
    override fun write(b: Int) = synchronized(sink) { sink.write(b); sink.flush() }
    override fun write(b: ByteArray) = synchronized(sink) { sink.write(b); sink.flush() }
    override fun write(b: ByteArray, off: Int, len: Int) =
        synchronized(sink) { sink.write(b, off, len); sink.flush() }
    override fun flush() = synchronized(sink) { sink.flush() }
    override fun close() = synchronized(sink) { sink.close() }
}
