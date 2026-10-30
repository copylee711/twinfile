package app.twinfile.fs

import java.io.File
import java.nio.file.Files
import java.util.TreeSet

data class RecentFile(val path: String, val entry: FEntry, val changedAt: Long)

/** Bounded top-N collection; files with identical names in different folders stay distinct. */
class RecentFileIndex(private val limit: Int = 500) {
    init { require(limit > 0) }
    private val ordered = TreeSet(compareBy<RecentFile> { it.changedAt }.thenBy { it.path })
    private val byPath = HashMap<String, RecentFile>()

    fun offer(item: RecentFile) {
        if (item.entry.isDir || item.changedAt <= 0) return
        val old = byPath[item.path]
        if (old != null) {
            ordered.remove(old)
            val merged = item.copy(changedAt = maxOf(item.changedAt, old.changedAt))
            ordered.add(merged); byPath[item.path] = merged
        } else {
            if (ordered.size >= limit && ordered.comparator().compare(item, ordered.first()) <= 0) return
            ordered.add(item); byPath[item.path] = item
            if (ordered.size > limit) ordered.pollFirst()?.let { byPath.remove(it.path) }
        }
    }

    fun snapshot(): List<RecentFile> = ordered.descendingSet().toList()

    companion object {
        fun allowed(file: File, roots: List<File>, showHidden: Boolean): Boolean {
            val path = file.toPath().toAbsolutePath().normalize()
            val root = roots.map { it.toPath().toAbsolutePath().normalize() }.firstOrNull { path.startsWith(it) } ?: return false
            val segments = root.relativize(path).map { it.toString() }
            if (segments.size >= 2 && segments[0] == "Android" && segments[1] in setOf("data", "obb")) return false
            if (segments.any { it == ".TwinFileTrash" || it == ".thumbnails" || it.startsWith(".twinfile-extract-") }) return false
            return showHidden || segments.none { it.startsWith('.') }
        }
    }

    /** Walk only public storage roots; never follow links into another tree or app storage. */
    fun scan(roots: List<File>, showHidden: Boolean, checkCancelled: () -> Unit,
             progress: (Int) -> Unit = {}) {
        var checked = 0
        for (root in roots) {
            val pending = ArrayDeque<File>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                checkCancelled()
                val dir = pending.removeLast()
                val children = dir.listFiles() ?: continue
                for (file in children) {
                    checkCancelled()
                    if (!allowed(file, roots, showHidden) || Files.isSymbolicLink(file.toPath())) continue
                    if (file.isDirectory) pending.add(file)
                    else if (file.isFile && file.canRead()) {
                        val time = file.lastModified()
                        offer(RecentFile(file.path, FEntry(file.name, false, file.length(), time), time))
                        checked++
                        if (checked % 300 == 0) progress(checked)
                    }
                }
            }
        }
        progress(checked)
    }
}
