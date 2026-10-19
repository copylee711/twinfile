package app.twinfile

import app.twinfile.fs.FileSource
import app.twinfile.fs.LocalSource
import app.twinfile.fs.Paths
import app.twinfile.fs.Root
import app.twinfile.ops.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import java.io.File

/** The file-operation contract every source must pass. */
fun runContract(s: FileSource, root: String, tmp: TemporaryFolder) {
    val d = Paths.join(root, "dir 中文")
    s.mkdir(d)
    assertTrue(s.stat(d)!!.isDir)
    val a = Paths.join(d, "a.txt")
    s.openWrite(a, 5).use { it.write("hello".toByteArray()) }
    assertEquals("hello", s.openRead(a).use { String(it.readBytes()) })
    val listed = s.list(d).single { it.name == "a.txt" }
    assertEquals(5L, listed.size)
    s.createFile(Paths.join(d, "empty"))
    assertEquals(0L, s.stat(Paths.join(d, "empty"))!!.size)

    val b = s.rename(a, "b.txt")
    assertNull(s.stat(a))
    s.mkdir(Paths.join(d, "sub"))
    s.move(b, Paths.join(d, "sub/b.txt"))
    assertEquals("hello", s.openRead(Paths.join(d, "sub/b.txt")).use { String(it.readBytes()) })

    // Copy a local tree up and back down through the copy engine.
    val local = LocalSource({ listOf(Root("t", tmp.root.path)) })
    val src = tmp.newFolder().apply {
        File(this, "up/deep").mkdirs()
        File(this, "up/deep/x.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(this, "up/y.txt").writeText("why")
    }
    Transfer(local, listOf("${src.path}/up"), s, d, move = false) { null }.run()
    val back = tmp.newFolder()
    Transfer(s, listOf(Paths.join(d, "up")), local, back.path, move = false) { null }.run()
    assertTrue(File(src, "up/deep/x.bin").readBytes().contentEquals(File(back, "up/deep/x.bin").readBytes()))
    assertEquals("why", File(back, "up/y.txt").readText())

    s.delete(d)
    assertNull(s.stat(d))
    s.close()
}
