package app.twinfile.cloud

import app.twinfile.fs.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.Closeable
import java.util.Base64

enum class CloudProvider(val label: String) { GOOGLE("Google Drive"), ONEDRIVE("OneDrive") }
data class CloudNode(val id: String, val name: String, val directory: Boolean,
                     val size: Long = -1, val modified: Long = 0, val mime: String = "",
                     val writable: Boolean = true)

interface CloudApi {
    fun root(): CloudNode
    fun get(id: String): CloudNode
    fun children(id: String): List<CloudNode>
    fun mkdir(parent: String, name: String): CloudNode
    fun rename(id: String, name: String): CloudNode
    fun move(id: String, parent: String, oldParent: String, name: String): CloudNode
    fun trash(id: String)
    fun download(node: CloudNode): InputStream
    fun upload(parent: String, name: String, existing: CloudNode?, file: File): CloudNode
}

/** A child path carries the opaque object ID and a readable label independently. */
object CloudPaths {
    private const val PREFIX = "~tf~"
    private const val NAME_PREFIX = "~name~"
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    fun segment(node: CloudNode) = PREFIX + encode(node.id) + "~" + encode(node.name)
    fun nameSegment(name: String) = NAME_PREFIX + encode(name)
    fun literalName(segment: String): String? = try {
        if (segment.startsWith(NAME_PREFIX)) decode(segment.removePrefix(NAME_PREFIX)) else null
    } catch (_: IllegalArgumentException) { null }
    fun parse(segment: String): Pair<String, String>? = try {
        if (!segment.startsWith(PREFIX)) null else segment.removePrefix(PREFIX).split('~').takeIf { it.size == 2 }
            ?.let { decode(it[0]) to decode(it[1]) }
    } catch (_: IllegalArgumentException) { null }
}

class CloudSource(
    override val id: String, override val title: String, private val api: CloudApi, val cacheDir: File,
) : FileSource {
    override val caps = setOf(Cap.SERVER_MOVE)
    @Volatile private var disconnected = false
    private val readers = java.util.concurrent.ConcurrentHashMap.newKeySet<Closeable>()
    override fun roots() = listOf(Root(title, "/"))
    override fun label(path: String) = CloudPaths.parse(Paths.name(path))?.second ?: CloudPaths.literalName(Paths.name(path)) ?: Paths.name(path)
    override fun newPath(directory: String, name: String) = Paths.join(directory, CloudPaths.nameSegment(name))
    override fun displayPath(path: String) = "/" + path.split('/').filter { it.isNotEmpty() }
        .joinToString("/") { CloudPaths.parse(it)?.second ?: CloudPaths.literalName(it) ?: it }

    private fun active() { if (disconnected) throw CloudAuthRequired() }
    private fun node(path: String): CloudNode {
        active()
        if (path == "/") return api.root()
        val segment = Paths.name(path)
        val literal = CloudPaths.literalName(segment)
        if (literal == null) CloudPaths.parse(segment)?.let { return api.get(it.first) }
        val parent = node(Paths.parent(path) ?: "/")
        val matches = api.children(parent.id).filter { it.name == (literal ?: segment) }
        if (matches.size > 1) throw FsException("此文件夹中有多个同名文件，请刷新后从列表中选择具体文件")
        return matches.singleOrNull() ?: throw CloudHttpException(404)
    }

    private fun entry(node: CloudNode) = FEntry(node.name, node.directory, node.size, node.modified,
        pathName = CloudPaths.segment(node))
    override fun resolvePath(path: String): String {
        var current = "/"
        for (segment in Paths.normalize(path).split('/').filter { it.isNotEmpty() }) {
            val child = node(newPath(current, segment))
            if (!child.directory) throw FsException("这不是文件夹：${child.name}")
            current = Paths.join(current, CloudPaths.segment(child))
        }
        return current
    }
    override fun list(dir: String): List<FEntry> {
        val parent = node(dir)
        if (!parent.directory) throw FsException("这不是文件夹")
        return api.children(parent.id).map(::entry)
    }
    override fun stat(path: String): FEntry? = try { entry(node(path)) }
        catch (e: CloudHttpException) { if (e.status == 404) null else throw e }
    override fun exists(path: String): Boolean = try {
        if (path == "/" || CloudPaths.parse(Paths.name(path)) != null) stat(path) != null
        else api.children(node(Paths.parent(path) ?: "/").id).any { it.name == (CloudPaths.literalName(Paths.name(path)) ?: Paths.name(path)) }
    } catch (e: CloudHttpException) { if (e.status == 404) false else throw e }
    override fun sameFile(first: String, second: String): Boolean = try { node(first).id == node(second).id }
        catch (e: CloudHttpException) { if (e.status == 404) false else throw e }
    override fun isInside(path: String, directory: String): Boolean {
        val ancestor = node(directory).id
        if (ancestor == api.root().id) return true
        var current = "/"
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = Paths.join(current, segment)
            if (node(current).id == ancestor) return true
        }
        return false
    }

    private fun destination(path: String, existingReference: Boolean = false): Pair<CloudNode, String> {
        if (path == "/") throw FsException("不能修改云盘根目录")
        val segment = Paths.name(path)
        val name = CloudPaths.literalName(segment) ?: if (existingReference) CloudPaths.parse(segment)?.second ?: segment else segment
        if (name.isBlank() || name in setOf(".", "..") || '/' in name || '\u0000' in name)
            throw FsException("名称无效")
        return node(Paths.parent(path) ?: "/") to name
    }
    override fun mkdir(path: String) {
        val (parent, name) = destination(path)
        if (stat(path) != null) throw FsException("已存在同名文件")
        api.mkdir(parent.id, name)
    }
    override fun createFile(path: String) {
        if (stat(path) != null) throw FsException("已存在同名文件")
        openWrite(path, 0).close()
    }
    override fun rename(path: String, newName: String): String {
        val parent = Paths.parent(path) ?: throw FsException("不能重命名云盘根目录")
        destination(newPath(parent, newName))
        if (newName != node(path).name && stat(newPath(parent, newName)) != null) throw FsException("已存在同名文件")
        val changed = api.rename(node(path).id, newName)
        return Paths.join(parent, CloudPaths.segment(changed))
    }
    override fun move(path: String, dstPath: String) {
        val (parent, name) = destination(dstPath)
        if (stat(dstPath) != null) throw FsException("已存在同名文件")
        api.move(node(path).id, parent.id, node(Paths.parent(path) ?: "/").id, name)
    }
    override fun delete(path: String, progress: Progress) {
        if (path == "/") throw FsException("不能删除云盘根目录")
        val item = node(path)
        if (!progress.step(0, item.name)) throw CancelledException()
        api.trash(item.id)
    }
    override fun openRead(path: String): InputStream {
        val raw = api.download(node(path))
        val stream = object : FilterInputStream(raw) {
            @Volatile private var closed = false
            private fun readable() {
                active()
                if (closed) throw FsException("文件读取已结束")
            }
            override fun read(): Int { readable(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { readable(); return super.read(b, off, len) }
            override fun close() {
                if (closed) return
                closed = true
                readers.remove(this)
                super.close()
            }
        }
        readers.add(stream)
        try { active() } catch (e: Exception) { stream.close(); throw e }
        return stream
    }
    override fun openWrite(path: String, size: Long): OutputStream {
        active()
        val (parent, name) = destination(path, existingReference = true)
        val existing = stat(path)?.let { node(path) }
        if (existing?.directory == true) throw FsException("目标是文件夹")
        if (existing?.writable == false) throw FsException("此云端文件不支持直接写入")
        val cache = File(cacheDir, "uploads")
        if (!cache.isDirectory && !cache.mkdirs()) throw FsException("无法创建上传缓存")
        val staged = File.createTempFile("upload-", ".tmp", cache)
        val output = staged.outputStream().buffered()
        return object : OutputStream(), AbortableWrite {
            private var closed = false
            private var written = 0L
            override fun write(b: Int) { active(); check(!closed); output.write(b); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { active(); check(!closed); output.write(b, off, len); written += len }
            override fun abort() { if (!closed) { closed = true; try { output.close() } finally { staged.delete() } } }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    output.close()
                    if (size >= 0 && size != written) throw FsException("文件传输未完成，已取消上传")
                    active()
                    api.upload(parent.id, existing?.name ?: name, existing, staged)
                } finally { staged.delete() }
            }
        }
    }
    /** Logging out revokes every outstanding source reference, including clipboard jobs. */
    override fun close() {
        disconnected = true
        readers.toList().forEach { runCatching { it.close() } }
    }
}
