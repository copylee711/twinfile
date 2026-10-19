package app.twinfile.priv

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.twinfile.BuildConfig
import app.twinfile.fs.FsException
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ExecResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

/** Runs commands with more rights than the app has: as root, or as the shell user via Shizuku. */
interface PrivShell {
    /** "Root" or "Shizuku", shown in the title bar. */
    val label: String

    /**
     * Runs [argv]. [input] feeds stdin (closed at its end); with [output] stdout is streamed
     * there instead of being collected into [ExecResult.out].
     */
    fun exec(argv: List<String>, input: InputStream? = null, output: OutputStream? = null): ExecResult

    fun close() {}
}

/** Copies on a daemon thread and closes [to] at the end. */
internal fun pumpAsync(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true) {
    try {
        from.use { i -> to.use { i.copyTo(it, 64 * 1024) } }
    } catch (_: Exception) {
    }
}

/** `su -c` per command; works with Magisk, KernelSU and APatch. */
class RootShell : PrivShell {
    override val label = "Root"

    override fun exec(argv: List<String>, input: InputStream?, output: OutputStream?): ExecResult {
        val p = ProcessBuilder("su", "-c", argv.joinToString(" ") { quote(it) }).start()
        val feeder = if (input != null) pumpAsync(input, p.outputStream) else { p.outputStream.close(); null }
        val err = ByteArrayOutputStream()
        val errPump = pumpAsync(p.errorStream, err)
        val out = ByteArrayOutputStream()
        p.inputStream.use { it.copyTo(output ?: out, 64 * 1024) }
        val code = p.waitFor()
        errPump.join(5000)
        feeder?.join(5000)
        return ExecResult(code, out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()).trim())
    }

    companion object {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** True when `su` grants uid 0 (the root manager may show its own prompt first). */
        fun available(): Boolean = try {
            RootShell().exec(listOf("id", "-u")).out.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }
}

/** Commands run in [PrivService], which Shizuku starts in a process with the shell uid. */
class ShizukuShell(private val packageName: String) : PrivShell {
    override val label = "Shizuku"

    @Volatile private var service: IPrivService? = null
    private var connection: ServiceConnection? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PrivService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** Binds on first use and waits up to 10 s for Shizuku to start the service. */
    @Synchronized
    private fun service(): IPrivService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (!Shizuku.pingBinder()) throw FsException("Shizuku 未运行，请先在 Shizuku 应用里启动它")
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw FsException("没有 Shizuku 授权，请在设置里重新开启")
        }
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) service = IPrivService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        connection = conn
        android.os.Handler(android.os.Looper.getMainLooper()).post { Shizuku.bindUserService(args, conn) }
        latch.await(10, TimeUnit.SECONDS)
        return service ?: throw FsException("无法连接 Shizuku 服务")
    }

    override fun exec(argv: List<String>, input: InputStream?, output: OutputStream?): ExecResult {
        val s = service()
        val inPipe = input?.let { ParcelFileDescriptor.createPipe() }
        val outPipe = ParcelFileDescriptor.createPipe()
        val errPipe = ParcelFileDescriptor.createPipe()
        val handle = try {
            s.start(argv.toTypedArray(), inPipe?.get(0), outPipe[1], errPipe[1])
        } finally {
            // The service holds its own copies now; ours must go so EOF can reach the readers.
            inPipe?.get(0)?.close()
            outPipe[1].close()
            errPipe[1].close()
        }
        val feeder = if (input != null) pumpAsync(input, ParcelFileDescriptor.AutoCloseOutputStream(inPipe!![1])) else null
        val err = ByteArrayOutputStream()
        val errPump = pumpAsync(ParcelFileDescriptor.AutoCloseInputStream(errPipe[0]), err)
        val out = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(outPipe[0]).use { it.copyTo(output ?: out, 64 * 1024) }
        val code = s.waitFor(handle)
        errPump.join(5000)
        feeder?.join(5000)
        return ExecResult(code, out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()).trim())
    }

    override fun close() {
        connection?.let { c ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { Shizuku.unbindUserService(args, c, true) } catch (_: Exception) {}
            }
        }
        connection = null
        service = null
    }
}
