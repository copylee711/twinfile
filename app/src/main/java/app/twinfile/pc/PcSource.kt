package app.twinfile.pc

import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import app.twinfile.fs.Root
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * A paired PC as a file source. Panes use '/'-separated paths; the PC's are native:
 * "/C:/Users/a" here is "C:\Users\a" there, and "/" lists the drives.
 */
class PcSource(val link: PcLink) : FileSource {
    override val id get() = link.pc.sourceId
    override val title get() = link.pc.display
    override val caps = setOf(Cap.SERVER_MOVE, Cap.SERVER_COPY, Cap.THUMB, Cap.MTIME)

    @Volatile private var rootsCache: Pair<List<Root>, String>? = null

    private fun fetchRoots(): Pair<List<Root>, String> {
        rootsCache?.let { return it }
        val h = link.request("roots").header
        val vols = h.optJSONArray("volumes") ?: JSONArray()
        val roots = (0 until vols.length()).map { i ->
            val v = vols.getJSONObject(i)
            val name = v.optString("name")
            Root(if (v.optString("kind") == "drive") "$name 盘" else name, fromPc(v.optString("path")), v.optLong("total", -1), v.optLong("free", -1))
        }
        val r = roots to fromPc(h.optString("home", "C:\\"))
        rootsCache = r
        return r
    }

    /** Drives first, then Desktop/Downloads...; fetched once per connection. */
    override fun roots(): List<Root> = runCatching { fetchRoots().first }.getOrElse { listOf(Root(title, "/")) }
    override fun home(): String = "/"

    /** Quick-access folders (Desktop, Downloads...) once known; never touches the network. */
    fun cachedPlaces(): List<Root> = rootsCache?.first?.filter { !it.name.endsWith(" 盘") } ?: emptyList()

    override fun list(dir: String): List<FEntry> {
        if (dir == "/") {
            return fetchRoots().first.filter { it.name.endsWith(" 盘") }.map {
                FEntry(Paths.name(it.path), true, -1, 0)
            }
        }
        val out = ArrayList<FEntry>()
        var offset = 0
        while (true) {
            val r = link.request("list", JSONObject().put("path", toPc(dir)).put("offset", offset))
            val arr = JSONArray(String(r.body, Charsets.UTF_8))
            for (i in 0 until arr.length()) out += entry(arr.getJSONObject(i))
            offset = r.header.optInt("next", -1)
            if (offset < 0) break
        }
        return out
    }

    private fun entry(o: JSONObject, name: String = o.optString("n")): FEntry {
        val dir = o.optBoolean("d")
        return FEntry(name, dir, if (dir) -1 else o.optLong("s"), o.optLong("m"), hidden = o.optBoolean("h") || name.startsWith("."))
    }

    override fun stat(path: String): FEntry? {
        if (path == "/" || path.count { it == '/' } == 1) return FEntry(Paths.name(path), true, -1, 0)
        val e = link.request("stat", JSONObject().put("path", toPc(path))).header.optJSONObject("e") ?: return null
        return entry(e, Paths.name(path))
    }

    override fun mkdir(path: String) {
        link.request("mkdir", JSONObject().put("path", toPc(path)))
    }

    override fun createFile(path: String) {
        link.request("create", JSONObject().put("path", toPc(path)))
    }

    override fun rename(path: String, newName: String): String {
        val to = Paths.join(Paths.parent(path) ?: "/", newName)
        link.request("rename", JSONObject().put("path", toPc(path)).put("to", toPc(to)))
        return to
    }

    /** A rename, so it fails across drives and the copy engine falls back to copy + delete. */
    override fun move(path: String, dstPath: String) {
        link.request("rename", JSONObject().put("path", toPc(path)).put("to", toPc(dstPath)))
    }

    override fun copy(path: String, dstPath: String, progress: Progress) {
        val dstDir = Paths.parent(dstPath) ?: throw FsException("目标无效")
        if (Paths.parent(path) != dstDir && Paths.name(dstPath) == Paths.name(path)) {
            link.request("copy", JSONObject().put("paths", JSONArray().put(toPc(path))).put("dir", toPc(dstDir)), LONG)
            return
        }
        // Same folder or a new name: the PC would pick its own " (n)" name, so stream the
        // bytes through the link instead. The upload is accepted before the pull starts, so
        // the reader thread never sits on a full pull queue while the upload waits for it.
        val size = stat(path)?.size ?: throw FsException("文件不存在：${Paths.name(path)}")
        link.push(toPc(dstDir), Paths.name(dstPath), size).use { out ->
            link.pull(toPc(path)).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (!progress.step(n.toLong(), Paths.name(path))) throw app.twinfile.fs.CancelledException()
                }
            }
        }
    }

    /** Goes to the PC's Recycle Bin. */
    override fun delete(path: String, progress: Progress) {
        if (!progress.step(0, Paths.name(path))) throw app.twinfile.fs.CancelledException()
        link.request("delete", JSONObject().put("paths", JSONArray().put(toPc(path))), LONG)
    }

    override fun openRead(path: String): InputStream = link.pull(toPc(path))

    override fun openWrite(path: String, size: Long): OutputStream {
        val dir = Paths.parent(path) ?: throw FsException("目标无效")
        return link.push(toPc(dir), Paths.name(path), size)
    }

    override fun setMtime(path: String, mtime: Long) {
        if (mtime > 0) runCatching { link.request("setmtime", JSONObject().put("path", toPc(path)).put("m", mtime)) }
    }

    override fun thumb(path: String, px: Int): ByteArray? =
        runCatching { link.request("thumb", JSONObject().put("path", toPc(path)).put("px", px)).body }.getOrNull()?.takeIf { it.isNotEmpty() }

    override fun close() {
        rootsCache = null
        link.close()
    }

    companion object {
        private const val LONG = 30 * 60_000L

        /** "/C:/Users/a" -> "C:\Users\a"; "/C:" -> "C:\". */
        fun toPc(path: String): String {
            val p = path.trim('/').replace('/', '\\')
            return if (p.length == 2 && p[1] == ':') "$p\\" else p
        }

        /** "C:\Users\a" -> "/C:/Users/a". */
        fun fromPc(path: String): String = "/" + path.replace('\\', '/').trimEnd('/')
    }
}
