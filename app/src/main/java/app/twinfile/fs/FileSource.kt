package app.twinfile.fs

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One entry in a folder listing. [size] is -1 when unknown (folders usually). */
data class FEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val hidden: Boolean = name.startsWith("."),
    val perms: String? = null,
    val isLink: Boolean = false,
)

/** A place a pane can start from: a drive, a volume, the share root of a server... */
data class Root(val name: String, val path: String, val total: Long = -1, val free: Long = -1)

enum class Cap {
    /** Rename/move inside the source without copying bytes. */
    SERVER_MOVE,
    /** Copy inside the source without streaming through the phone. */
    SERVER_COPY,
    /** Deleted files go to a recycle bin the user can restore from. */
    TRASH,
    /** [FileSource.thumb] can return previews. */
    THUMB,
    /** [FileSource.setMtime] keeps modification times on copy. */
    MTIME,
}

/** Reports work done; returning false asks the operation to stop. */
fun interface Progress {
    fun step(doneBytes: Long, currentName: String): Boolean
}

val NoProgress = Progress { _, _ -> true }

/** An error whose message is meant for the user. */
open class FsException(message: String, cause: Throwable? = null) : IOException(message, cause)

class CancelledException : IOException("cancelled")

/**
 * A browsable file tree: local storage, a network server or the PC. Paths are absolute,
 * '/'-separated strings with no trailing slash (except the root "/").
 *
 * All calls block; callers run them on an I/O dispatcher.
 */
interface FileSource {
    /** Stable id, e.g. "local", "sftp:<uuid>", "pc:<pcId>". */
    val id: String
    /** Name shown to the user. */
    val title: String
    val caps: Set<Cap>
    /** For a source living inside a local file (a zip): that file's path. ".." at "/" leaves to it. */
    val container: String? get() = null
    val readOnly: Boolean get() = false
    /** Paths are this phone's own file system (local, or local through root/Shizuku). */
    val phonePaths: Boolean get() = false

    /** Where the user can start; also the path shown as "home". */
    fun roots(): List<Root>
    fun home(): String = roots().firstOrNull()?.path ?: "/"

    fun list(dir: String): List<FEntry>
    fun stat(path: String): FEntry?
    fun exists(path: String): Boolean = stat(path) != null

    fun mkdir(path: String)
    fun createFile(path: String)
    /** Renames in place and returns the new path. */
    fun rename(path: String, newName: String): String
    /** Permanently deletes [path], folders recursively. */
    fun delete(path: String, progress: Progress = NoProgress)

    fun openRead(path: String): InputStream
    /** [size] is the expected length when known (some protocols need it up front). */
    fun openWrite(path: String, size: Long): OutputStream
    fun setMtime(path: String, mtime: Long) {}

    /** Server-side move of [path] into [dstPath] (full target path). Needs [Cap.SERVER_MOVE]. */
    fun move(path: String, dstPath: String): Unit = throw UnsupportedOperationException()
    /** Server-side copy. Needs [Cap.SERVER_COPY]. */
    fun copy(path: String, dstPath: String, progress: Progress): Unit = throw UnsupportedOperationException()

    /** JPEG/PNG bytes of a preview, or null. Needs [Cap.THUMB]. */
    fun thumb(path: String, px: Int): ByteArray? = null

    /** Drops idle connections; the source reconnects on next use. */
    fun close() {}
}
