package app.twinfile.priv

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku user service: lives in a separate process with the shell uid (or root) and runs
 * commands for [ShizukuShell]. Instantiated by Shizuku through reflection, hence [Keep].
 */
@Keep
class PrivService : IPrivService.Stub() {
    private class Running(val process: Process, val pumps: List<Thread>)

    private val running = ConcurrentHashMap<Int, Running>()
    private val ids = AtomicInteger()

    override fun destroy() {
        exitProcess(0)
    }

    override fun start(argv: Array<String>, stdin: ParcelFileDescriptor?, stdout: ParcelFileDescriptor?, stderr: ParcelFileDescriptor?): Int {
        val p = ProcessBuilder(*argv).start()
        val pumps = ArrayList<Thread>()
        pumps += pump(stdin?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }, p.outputStream)
        pumps += pump(p.inputStream, stdout?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) })
        pumps += pump(p.errorStream, stderr?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) })
        val id = ids.incrementAndGet()
        running[id] = Running(p, pumps)
        return id
    }

    override fun waitFor(handle: Int): Int {
        val r = running.remove(handle) ?: return -1
        val code = r.process.waitFor()
        r.pumps.forEach { it.join(5000) }
        return code
    }

    /** Copies [from] into [to] until EOF, then closes both; a missing side is drained or closed. */
    private fun pump(from: InputStream?, to: OutputStream?): Thread = thread(isDaemon = true) {
        try {
            if (from == null) to?.close()
            else from.use { i ->
                if (to != null) to.use { i.copyTo(it, 64 * 1024) }
                else { val buf = ByteArray(8192); while (i.read(buf) >= 0) Unit }
            }
        } catch (_: Exception) {
        }
    }
}
