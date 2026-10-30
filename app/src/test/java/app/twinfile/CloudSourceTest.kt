package app.twinfile

import app.twinfile.cloud.*
import app.twinfile.fs.*
import app.twinfile.ops.Resolution
import app.twinfile.ops.Transfer
import app.twinfile.ops.TransferStats
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class CloudSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryApi : CloudApi {
        val nodes = linkedMapOf("root" to CloudNode("root", "Cloud", true))
        val parents = mutableMapOf<String, String>()
        val content = mutableMapOf<String, ByteArray>()
        var uploads = 0
        var lastUploadName = ""
        private var counter = 0
        override fun root() = nodes.getValue("root")
        override fun get(id: String) = nodes[id] ?: throw CloudHttpException(404)
        override fun children(id: String) = nodes.values.filter { parents[it.id] == id }
        fun add(parent: String, name: String, dir: Boolean = false, bytes: ByteArray = byteArrayOf()): CloudNode {
            val node = CloudNode("id /${++counter}", name, dir, if (dir) -1 else bytes.size.toLong(), 1_600_000_000_000)
            nodes[node.id] = node; parents[node.id] = parent; content[node.id] = bytes
            return node
        }
        override fun mkdir(parent: String, name: String) = add(parent, name, true)
        override fun rename(id: String, name: String) = get(id).copy(name = name).also { nodes[id] = it }
        override fun move(id: String, parent: String, oldParent: String, name: String) = rename(id, name).also { parents[id] = parent }
        override fun trash(id: String) {
            children(id).toList().forEach { trash(it.id) }
            nodes.remove(id); parents.remove(id); content.remove(id)
        }
        override fun download(node: CloudNode): InputStream = ByteArrayInputStream(content.getValue(node.id))
        override fun upload(parent: String, name: String, existing: CloudNode?, file: File): CloudNode {
            uploads++
            lastUploadName = name
            if (existing == null) return add(parent, name, bytes = file.readBytes())
            content[existing.id] = file.readBytes()
            return existing.copy(size = file.length()).also { nodes[it.id] = it }
        }
    }

    private fun source(api: MemoryApi) = CloudSource("cloud:test", "Test cloud", api, tmp.newFolder())

    @Test fun cloudImplementsFullFileOperationContract() { runContract(source(MemoryApi()), "/", tmp) }

    @Test fun duplicateNamesRemainDistinctAndCopyWithoutLosingFiles() {
        val api = MemoryApi()
        api.add("root", "same.txt", bytes = "first".toByteArray())
        api.add("root", "same.txt", bytes = "second".toByteArray())
        val s = source(api)
        val entries = s.list("/")
        assertEquals(2, entries.size)
        assertEquals(listOf("same.txt", "same.txt"), entries.map { it.name })
        assertNotEquals(entries[0].pathName, entries[1].pathName)
        assertTrue(s.exists("/same.txt"))
        assertTrue(runCatching { s.stat("/same.txt") }.exceptionOrNull() is FsException)
        val out = tmp.newFolder()
        val local = LocalSource({ listOf(Root("tmp", tmp.root.path)) })
        Transfer(s, entries.map { "/${it.pathName}" }, local, out.path, false) { Resolution.RENAME to true }.run()
        assertEquals("first", File(out, "same.txt").readText())
        assertEquals("second", File(out, "same (1).txt").readText())
        Transfer(s, entries.map { "/${it.pathName}" }, s, "/", false) { Resolution.OVERWRITE to true }.run()
        assertEquals(4, s.list("/").size)
        assertEquals("first", s.openRead("/${entries[0].pathName}").use { String(it.readBytes()) })
        assertEquals("second", s.openRead("/${entries[1].pathName}").use { String(it.readBytes()) })
    }

    @Test fun referencesSurviveRenameAndWriterUsesRealName() {
        val api = MemoryApi()
        val file = api.add("root", "原始文件.txt", bytes = "before".toByteArray())
        val s = source(api)
        val original = "/${s.list("/").single().pathName}"
        val renamed = s.rename(original, "renamed.txt")
        assertEquals(file.id, CloudPaths.parse(Paths.name(renamed))!!.first)
        assertEquals("renamed.txt", s.stat(original)!!.name)
        s.openWrite(original, 5).use { it.write("after".toByteArray()) }
        assertEquals("renamed.txt", api.lastUploadName)
        assertEquals("renamed.txt", api.get(file.id).name)
        assertEquals("after", s.openRead(original).use { String(it.readBytes()) })
        assertEquals("/renamed.txt", s.displayPath(renamed))
    }

    @Test fun failedOrCancelledTransfersNeverCommitStagedContent() {
        val api = MemoryApi()
        val s = source(api)
        val file = tmp.newFile("broken.bin").apply { writeBytes(ByteArray(800_000)) }
        val local = LocalSource({ listOf(Root("tmp", tmp.root.path)) })
        val failing = object : FileSource by local {
            override fun openRead(path: String): InputStream = object : InputStream() {
                var returned = false
                override fun read(): Int = throw IOException("broken source")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (returned) throw IOException("broken source")
                    returned = true; b[off] = 65; return 1
                }
            }
        }
        assertTrue(runCatching { Transfer(failing, listOf(file.path), s, "/", false) { null }.run() }.isFailure)
        assertEquals(0, api.uploads)
        assertTrue(s.list("/").isEmpty())
        assertTrue(File(s.cacheDir, "uploads").listFiles()!!.isEmpty())
        val write = s.openWrite("/incomplete", 10)
        write.write(1)
        assertTrue(runCatching { write.close() }.isFailure)
        assertEquals(0, api.uploads)
        s.close()
        assertTrue(runCatching { s.list("/") }.exceptionOrNull() is CloudAuthRequired)
    }

    @Test fun cloudPathsPreserveUnicodeSlashesAndOpaqueIds() {
        val node = CloudNode("a/b!c:123", "中文/with ~ symbols.txt", false)
        val segment = CloudPaths.segment(node)
        assertFalse(segment.contains('/'))
        assertEquals(node.id to node.name, CloudPaths.parse(segment))
        assertNull(CloudPaths.parse("ordinary.txt"))
    }

    @Test fun renamedAliasCannotMoveFolderIntoItsOwnDescendant() {
        val api = MemoryApi()
        val folder = api.add("root", "folder", true)
        val child = api.add(folder.id, "child", true)
        val s = source(api)
        val old = "/${CloudPaths.segment(folder)}"
        val renamed = s.rename(old, "renamed")
        val destination = "$renamed/${CloudPaths.segment(child)}"
        assertTrue(s.isInside(destination, old))
        assertTrue(runCatching { Transfer(s, listOf(old), s, destination, true) { null }.run() }.exceptionOrNull() is FsException)
        assertEquals(0, api.uploads)
        assertEquals("root", api.parents[folder.id])
    }

    @Test fun mountedArchivesAreIncludedInAccountDisconnect() {
        val cache = tmp.newFolder()
        val account = CloudAccount(CloudProvider.GOOGLE, "account-id", "test@example.invalid", "Test")
        val api = MemoryApi()
        val base = CloudSource(account.sourceId, "Cloud", api, cache)
        val mounted = object : FileSource by base {
            override val id = "zip:cached-file"
            override val container = File(cache, "open/hash/archive.zip").path
        }
        val unrelated = object : FileSource by base {
            override val id = "zip:another-file"
            override val container = File(cache.parentFile, "${cache.name}-other/archive.zip").path
        }
        assertTrue(base.belongsToCloud(account, cache))
        assertTrue(mounted.belongsToCloud(account, cache))
        assertFalse(unrelated.belongsToCloud(account, cache))
    }

    @Test fun disconnectedSourceStopsAlreadyOpenDownloadsAndStagedWrites() {
        val api = MemoryApi()
        val item = api.add("root", "private.txt", bytes = "private".toByteArray())
        val s = source(api)
        val input = s.openRead("/${CloudPaths.segment(item)}")
        val output = s.openWrite("/new.txt", 10)
        s.close()
        assertTrue(runCatching { input.read() }.exceptionOrNull() is CloudAuthRequired)
        assertTrue(runCatching { output.write(1) }.exceptionOrNull() is CloudAuthRequired)
        (output as AbortableWrite).abort()
        assertEquals(0, api.uploads)
        assertTrue(File(s.cacheDir, "uploads").listFiles()!!.isEmpty())
    }

    @Test fun destinationNamesCannotForgeObjectReferences() {
        val api = MemoryApi()
        val victim = api.add("root", "keep.txt", bytes = "original".toByteArray())
        val s = source(api)
        val deceptiveName = CloudPaths.segment(victim)
        val localFile = File(tmp.newFolder(), deceptiveName).apply { writeText("copied data") }
        val local = LocalSource({ listOf(Root("tmp", tmp.root.path)) })
        Transfer(local, listOf(localFile.path), s, "/", false) { Resolution.OVERWRITE to true }.run()
        assertEquals("original", String(api.content[victim.id] ?: byteArrayOf()))
        assertEquals(setOf("keep.txt", deceptiveName), api.children("root").map { it.name }.toSet())
    }

    @Test fun cloudFileNamesCannotTraverseWindowsOrSmbDestinations() {
        val api = MemoryApi()
        val item = api.add("root", "..\\outside.txt", bytes = "replacement".toByteArray())
        val s = source(api)
        val folder = tmp.newFolder()
        val outside = File(folder.parentFile, "outside.txt").apply { writeText("original") }
        val local = LocalSource({ listOf(Root("tmp", tmp.root.path)) })
        assertTrue(runCatching {
            Transfer(s, listOf("/${CloudPaths.segment(item)}"), local, folder.path, false) { Resolution.OVERWRITE to true }.run()
        }.exceptionOrNull() is FsException)
        assertEquals("original", outside.readText())
        assertTrue(folder.listFiles()!!.isEmpty())
    }
}
