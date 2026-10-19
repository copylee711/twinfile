package app.twinfile.priv

import android.os.ParcelFileDescriptor
import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import app.twinfile.fs.Root
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * The phone's own files seen through a [PrivShell], for folders the app itself may not read
 * (Android/data and obb since Android 11, or anything with root). Every call is a toybox
 * command, so it is slower than [app.twinfile.fs.LocalSource] and only used where needed.
 */
class ShellSource(private val shell: PrivShell, private val rootsProvider: () -> List<Root>) : FileSource {
    override val id = ID
    override val title get() = shell.label
    override val caps = setOf(Cap.SERVER_MOVE, Cap.SERVER_COPY, Cap.MTIME)
    override val phonePaths: Boolean get() = true

    override fun roots(): List<Root> = rootsProvider()

    override fun list(dir: String): List<FEntry> {
        // One stat for the whole folder; -L reports what links point at.
        val r = shell.exec(listOf("find", dir, "-mindepth", "1", "-maxdepth", "1", "-exec", "stat", "-L", "-c", STAT_FMT, "{}", "+"))
        val entries = r.out.lineSequence().mapNotNull(::parse).toList()
        if (!r.ok && entries.isEmpty() && stat(dir)?.isDir != true) {
            throw FsException("无法读取此文件夹：${r.err.lineSequence().firstOrNull() ?: dir}")
        }
        return entries
    }

    override fun stat(path: String): FEntry? {
        val r = shell.exec(listOf("stat", "-L", "-c", STAT_FMT, path))
        return if (r.ok) parse(r.out.trim()) else null
    }

    /** "<hex mode>/<size>/<mtime s>/<path>" from `stat -c %f/%s/%Y/%n`. */
    private fun parse(line: String): FEntry? {
        val parts = line.split('/', limit = 4)
        if (parts.size < 4) return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val name = Paths.name(parts[3])
        val dir = mode and 0xF000 == 0x4000
        return FEntry(name, dir, if (dir) -1 else parts[1].toLongOrNull() ?: 0, (parts[2].toLongOrNull() ?: 0) * 1000)
    }

    private fun run(vararg argv: String, what: String) {
        val r = shell.exec(argv.toList())
        if (!r.ok) throw FsException("$what：${r.err.lineSequence().firstOrNull()?.ifBlank { null } ?: "exit ${r.code}"}")
    }

    override fun mkdir(path: String) {
        if (stat(path) != null) throw FsException("已存在同名文件：${Paths.name(path)}")
        run("mkdir", "-p", path, what = "无法创建文件夹")
    }

    override fun createFile(path: String) {
        if (stat(path) != null) throw FsException("已存在同名文件：${Paths.name(path)}")
        run("touch", path, what = "无法创建文件")
    }

    override fun rename(path: String, newName: String): String {
        val target = Paths.join(Paths.parent(path) ?: "/", newName)
        if (stat(target) != null) throw FsException("已存在同名文件：$newName")
        run("mv", path, target, what = "重命名失败")
        return target
    }

    override fun delete(path: String, progress: Progress) {
        progress.step(0, Paths.name(path))
        run("rm", "-rf", path, what = "删除失败")
    }

    override fun move(path: String, dstPath: String) = run("mv", path, dstPath, what = "移动失败")

    override fun copy(path: String, dstPath: String, progress: Progress) {
        progress.step(0, Paths.name(path))
        run("cp", "-r", path, dstPath, what = "复制失败")
    }

    override fun setMtime(path: String, mtime: Long) {
        if (mtime > 0) shell.exec(listOf("touch", "-m", "-d", "@${mtime / 1000}", path))
    }

    override fun openRead(path: String): InputStream {
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CompletableFuture<ExecResult>()
        thread(isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { done.complete(shell.exec(listOf("cat", path), output = it)) }
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        // At EOF, a failed cat (missing file, no permission) becomes an error, not an empty file.
        return object : FilterInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            private fun check(n: Int): Int {
                if (n < 0) result(done, "读取失败")
                return n
            }
            override fun read(): Int = check(super.read())
            override fun read(b: ByteArray, off: Int, len: Int): Int = check(super.read(b, off, len))
        }
    }

    override fun openWrite(path: String, size: Long): OutputStream {
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CompletableFuture<ExecResult>()
        thread(isDaemon = true) {
            try {
                done.complete(shell.exec(listOf("sh", "-c", "cat > \"\$1\"", "sh", path), input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])))
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        return object : FilterOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                result(done, "写入失败")
            }
        }
    }

    private fun result(done: CompletableFuture<ExecResult>, what: String) {
        val r = try {
            done.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw FsException("$what：${e.cause?.message}", e.cause)
        }
        if (!r.ok) throw FsException("$what：${r.err.lineSequence().firstOrNull()?.ifBlank { null } ?: "exit ${r.code}"}")
    }

    override fun close() = shell.close()

    companion object {
        const val ID = "priv"
        private const val STAT_FMT = "%f/%s/%Y/%n"
    }
}
