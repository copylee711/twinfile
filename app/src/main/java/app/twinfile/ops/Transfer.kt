package app.twinfile.ops

import app.twinfile.fs.CancelledException
import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import java.io.IOException

enum class Resolution { SKIP, OVERWRITE, RENAME }

/** What to do when [name] already exists in the target folder. */
data class Conflict(val name: String, val isDir: Boolean, val existing: FEntry)

/** Live counters of a running copy; read from the UI thread, written by the worker. */
class TransferStats {
    @Volatile var totalBytes = 0L
    @Volatile var doneBytes = 0L
    @Volatile var totalFiles = 0
    @Volatile var doneFiles = 0
    @Volatile var current = ""
    @Volatile var cancelled = false
}

/**
 * Copies or moves [paths] from [src] into [dstDir] on [dst]. Uses the source's own move/copy
 * when both sides are the same source, otherwise streams bytes. [ask] is called for each name
 * clash; returning null from it cancels the whole job. [keepMtime] keeps modification times.
 */
class Transfer(
    private val src: FileSource,
    private val paths: List<String>,
    private val dst: FileSource,
    private val dstDir: String,
    private val move: Boolean,
    private val keepMtime: Boolean = true,
    val stats: TransferStats = TransferStats(),
    private val ask: (Conflict) -> Pair<Resolution, Boolean>?,
) {
    private var remembered: Resolution? = null
    private val same = src === dst
    private val buf = ByteArray(BUF)

    private val progress = Progress { n, name ->
        stats.doneBytes += n
        stats.current = name
        !stats.cancelled
    }

    fun run() {
        if (same) {
            for (p in paths) {
                if (Paths.isInside(dstDir, p)) throw FsException("不能把文件夹复制或移动到它自己里面：${Paths.name(p)}")
            }
        }
        if (same && move && Cap.SERVER_MOVE in src.caps && paths.all { Paths.parent(it) == dstDir }) return
        measure()
        for (p in paths) {
            checkCancel()
            val e = src.stat(p) ?: throw FsException("文件不存在：$p")
            transferOne(p, e, dstDir)
        }
    }

    private fun measure() {
        // Same-source moves are renames; their size is irrelevant.
        if (same && move && Cap.SERVER_MOVE in src.caps) {
            stats.totalFiles = paths.size
            return
        }
        for (p in paths) {
            val e = src.stat(p) ?: continue
            measureRec(p, e)
        }
    }

    private fun measureRec(path: String, e: FEntry) {
        checkCancel()
        if (e.isDir) {
            for (c in src.list(path)) measureRec(Paths.join(path, c.name), c)
        } else {
            stats.totalFiles++
            stats.totalBytes += maxOf(e.size, 0)
        }
    }

    /** Places [path] inside [toDir]; returns false when the item was skipped. */
    private fun transferOne(path: String, e: FEntry, toDir: String): Boolean {
        var name = e.name
        var target = Paths.join(toDir, name)
        if (same && target == path) {
            // Copy onto itself: always keep both.
            if (move) return false
            name = Paths.freeName(name, e.isDir) { dst.exists(Paths.join(toDir, it)) }
            target = Paths.join(toDir, name)
        }
        val existing = dst.stat(target)
        if (existing != null) {
            when (resolve(Conflict(name, e.isDir, existing))) {
                Resolution.SKIP -> {
                    skip(path, e)
                    return false
                }
                Resolution.RENAME -> {
                    name = Paths.freeName(name, e.isDir) { dst.exists(Paths.join(toDir, it)) }
                    target = Paths.join(toDir, name)
                }
                Resolution.OVERWRITE -> {
                    if (e.isDir && existing.isDir) return merge(path, target)
                    dst.delete(target)
                }
            }
        }
        if (same && move && Cap.SERVER_MOVE in src.caps) {
            try {
                src.move(path, target)
                stats.doneFiles++
                return true
            } catch (_: IOException) {
                // Different volumes: fall through to copy + delete.
            }
        }
        if (same && Cap.SERVER_COPY in src.caps && !e.isDir) {
            // Also the fallback when a move across volumes failed: copy on the server, then delete.
            src.copy(path, target, progress)
            if (move) src.delete(path)
            stats.doneFiles++
            return true
        }
        if (e.isDir) {
            dst.mkdir(target)
            var all = true
            for (c in src.list(path)) {
                checkCancel()
                if (!transferOne(Paths.join(path, c.name), c, target)) all = false
            }
            if (keepMtime && Cap.MTIME in dst.caps) dst.setMtime(target, e.mtime)
            if (move && all) src.delete(path)
            return all
        }
        stream(path, e, target)
        if (move) src.delete(path)
        return true
    }

    private fun merge(path: String, target: String): Boolean {
        var all = true
        for (c in src.list(path)) {
            checkCancel()
            if (!transferOne(Paths.join(path, c.name), c, target)) all = false
        }
        if (move && all) src.delete(path)
        return all
    }

    private fun stream(path: String, e: FEntry, target: String) {
        stats.current = e.name
        try {
            src.openRead(path).use { input ->
                dst.openWrite(target, e.size).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (!progress.step(n.toLong(), e.name)) throw CancelledException()
                    }
                }
            }
        } catch (ex: IOException) {
            // Never leave a truncated file behind.
            try { dst.delete(target) } catch (_: IOException) {}
            throw ex
        }
        if (keepMtime && Cap.MTIME in dst.caps) dst.setMtime(target, e.mtime)
        stats.doneFiles++
    }

    private fun skip(path: String, e: FEntry) {
        if (e.isDir) measureSkip(path) else {
            stats.doneFiles++
            stats.doneBytes += maxOf(e.size, 0)
        }
    }

    private fun measureSkip(path: String) {
        for (c in src.list(path)) {
            if (c.isDir) measureSkip(Paths.join(path, c.name)) else {
                stats.doneFiles++
                stats.doneBytes += maxOf(c.size, 0)
            }
        }
    }

    private fun resolve(c: Conflict): Resolution {
        remembered?.let { return it }
        val (r, all) = ask(c) ?: throw CancelledException()
        if (all) remembered = r
        return r
    }

    private fun checkCancel() {
        if (stats.cancelled) throw CancelledException()
    }

    private companion object {
        const val BUF = 256 * 1024
    }
}
