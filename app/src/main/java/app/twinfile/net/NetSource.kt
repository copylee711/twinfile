package app.twinfile.net

import app.twinfile.fs.CancelledException
import app.twinfile.fs.FileSource
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import app.twinfile.fs.Root
import app.twinfile.util.FileLog
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import javax.net.ssl.SSLException

/**
 * Base for protocol sources: a small pool of connections ([C]) created on demand, dropped
 * when idle, and errors turned into messages for the user.
 */
abstract class NetSource<C : Any>(val config: NetConfig, private val maxConnections: Int = 3) : FileSource {
    override val id get() = config.sourceId
    override val title get() = config.display

    private val idle = ArrayDeque<C>()
    private var open = 0
    private val lock = Object()
    @Volatile private var lastUse = System.currentTimeMillis()

    protected abstract fun connect(): C
    protected abstract fun disconnect(c: C)
    /** Cheap check before reusing a pooled connection. */
    protected open fun alive(c: C): Boolean = true

    override fun roots() = listOf(Root(config.display, home()))
    override fun home(): String = Paths.normalize(config.path.ifBlank { "/" })

    protected fun borrow(): C {
        lastUse = System.currentTimeMillis()
        synchronized(lock) {
            while (true) {
                val c = idle.pollFirst()
                if (c != null) {
                    if (alive(c)) return c
                    open--
                    runCatching { disconnect(c) }
                    continue
                }
                if (open < maxConnections) {
                    open++
                    break
                }
                lock.wait(30_000)
            }
        }
        return try {
            connect()
        } catch (e: Throwable) {
            synchronized(lock) { open--; lock.notifyAll() }
            throw map(e)
        }
    }

    protected fun giveBack(c: C, broken: Boolean = false) {
        lastUse = System.currentTimeMillis()
        synchronized(lock) {
            if (broken) {
                open--
                runCatching { disconnect(c) }
            } else idle.addFirst(c)
            lock.notifyAll()
        }
    }

    /** Runs [block] on a pooled connection; retries once on a fresh one if it was stale. */
    protected fun <R> call(block: (C) -> R): R {
        var attempt = 0
        while (true) {
            val c = borrow()
            try {
                val r = block(c)
                giveBack(c)
                return r
            } catch (e: Throwable) {
                val stale = isConnectionError(e)
                giveBack(c, broken = stale)
                if (stale && attempt++ == 0) continue
                throw map(e)
            }
        }
    }

    /** An input stream that returns its connection to the pool when closed. */
    protected fun pooledInput(c: C, input: InputStream, finish: (C) -> Unit = {}): InputStream =
        object : FilterInputStream(input) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                var broken = false
                try {
                    super.close()
                    finish(c)
                } catch (e: Exception) {
                    broken = true
                } finally {
                    giveBack(c, broken)
                }
            }
        }

    protected fun pooledOutput(c: C, out: OutputStream, finish: (C) -> Unit = {}): OutputStream =
        object : FilterOutputStream(out) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                var error: Exception? = null
                try {
                    super.close()
                    finish(c)
                } catch (e: Exception) {
                    error = e
                } finally {
                    giveBack(c, error != null)
                }
                error?.let { throw map(it) }
            }
        }

    /** Recursive delete built on the protocol's file/dir primitives. */
    protected fun deleteTree(path: String, progress: Progress, isDir: (String) -> Boolean,
                             children: (String) -> List<String>, rmFile: (String) -> Unit, rmDir: (String) -> Unit) {
        if (!progress.step(0, Paths.name(path))) throw CancelledException()
        if (isDir(path)) {
            for (c in children(path)) deleteTree(Paths.join(path, c), progress, isDir, children, rmFile, rmDir)
            rmDir(path)
        } else rmFile(path)
    }

    fun idleFor(): Long = System.currentTimeMillis() - lastUse

    /** Drops idle connections; streams still open keep theirs. */
    override fun close() {
        val drop: List<C>
        synchronized(lock) {
            drop = idle.toList()
            idle.clear()
            open -= drop.size
        }
        for (c in drop) runCatching { disconnect(c) }
        if (drop.isNotEmpty()) FileLog.i("closed ${drop.size} idle connection(s) to ${config.display}")
    }

    protected open fun isConnectionError(e: Throwable): Boolean =
        e is java.net.SocketException || e is java.io.EOFException ||
            e.cause?.let { it is java.net.SocketException || it is java.io.EOFException } == true

    /** Maps library exceptions to [FsException] with a readable message. */
    protected open fun map(e: Throwable): IOException = when (e) {
        is FsException, is CancelledException -> e as IOException
        is UnknownHostException -> FsException("找不到主机：${config.host}", e)
        is ConnectException, is NoRouteToHostException -> FsException("无法连接 ${config.host}:${config.port}（${e.message ?: "连接被拒绝"}）", e)
        is SocketTimeoutException -> FsException("连接 ${config.host} 超时", e)
        is SSLException -> FsException("TLS 握手失败：${e.message}。如果服务器使用自签名证书，请在设置里勾选「信任所有证书」", e)
        is IOException -> FsException(e.message ?: e.javaClass.simpleName, e)
        else -> FsException(e.message ?: e.javaClass.simpleName, e)
    }
}
