package app.twinfile.ops

import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import org.json.JSONObject
import java.io.File

/**
 * App-owned recycle bin. Each volume keeps its own `.TwinFileTrash` folder at its root, so
 * deleting is a rename on the same file system. An item lives in `<trash>/<id>/<name>`, with
 * `<trash>/<id>.json` holding the original path and the deletion time.
 */
class Trash(private val volumeRootOf: (String) -> String?) {
    data class Item(val id: String, val trashDir: File, val name: String, val originalPath: String,
                    val deletedAt: Long, val isDir: Boolean, val size: Long) {
        val file get() = File(File(trashDir, id), name)
    }

    fun dirFor(path: String): File? = volumeRootOf(path)?.let { File(it, DIR) }

    /** True when [path] can go to the bin (it is on a known volume and not the bin itself). */
    fun supports(path: String): Boolean {
        val d = dirFor(path) ?: return false
        return !Paths.isInside(path, d.path) && path != volumeRootOf(path)
    }

    fun moveToTrash(path: String) {
        val src = File(path)
        val dir = dirFor(path) ?: throw FsException("此位置不支持回收站")
        val id = "${System.currentTimeMillis()}-${(Math.random() * 1e6).toLong()}"
        val box = File(dir, id)
        if (!box.mkdirs()) throw FsException("无法创建回收站文件夹")
        File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        val isDir = src.isDirectory
        val size = if (isDir) -1 else src.length()
        val target = File(box, src.name)
        if (!src.renameTo(target)) {
            box.delete()
            throw FsException("无法移到回收站：${src.name}")
        }
        File(dir, "$id.json").writeText(
            JSONObject().put("path", path).put("time", System.currentTimeMillis())
                .put("dir", isDir).put("size", size).toString()
        )
    }

    fun items(volumeRoots: List<String>): List<Item> {
        val out = ArrayList<Item>()
        for (root in volumeRoots) {
            val dir = File(root, DIR)
            for (meta in dir.listFiles { f -> f.name.endsWith(".json") } ?: continue) {
                val id = meta.name.removeSuffix(".json")
                val box = File(dir, id)
                val name = box.list()?.firstOrNull() ?: continue
                try {
                    val j = JSONObject(meta.readText())
                    out += Item(id, dir, name, j.getString("path"), j.optLong("time"),
                        j.optBoolean("dir"), j.optLong("size", -1))
                } catch (_: Exception) {
                }
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    /** Puts [item] back; returns the path it was restored to (renamed if the name is taken). */
    fun restore(item: Item): String {
        val orig = File(item.originalPath)
        val parent = orig.parentFile ?: throw FsException("原位置无效")
        if (!parent.isDirectory && !parent.mkdirs()) throw FsException("无法创建原文件夹：${parent.path}")
        val name = Paths.freeName(orig.name, item.isDir) { File(parent, it).exists() }
        val to = File(parent, name)
        if (!item.file.renameTo(to)) throw FsException("无法恢复：${item.name}")
        purgeBox(item)
        return to.path
    }

    fun purge(item: Item) {
        item.file.deleteRecursively()
        purgeBox(item)
    }

    private fun purgeBox(item: Item) {
        File(item.trashDir, item.id).deleteRecursively()
        File(item.trashDir, "${item.id}.json").delete()
    }

    /** Empties items older than [days] days; 0 keeps everything. */
    fun autoClean(volumeRoots: List<String>, days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        items(volumeRoots).filter { it.deletedAt < cutoff }.forEach { purge(it) }
    }

    companion object {
        const val DIR = ".TwinFileTrash"
    }
}
