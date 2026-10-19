package app.twinfile.pc

import app.twinfile.fs.Paths
import app.twinfile.runContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Against a real SyncBoard engine (`sbcli serve` with SB_ALLOW_BROWSE=1). Runs only with
 * TWINFILE_PC_URI (the PAIR uri it prints), TWINFILE_PC_PORT and TWINFILE_PC_ROOT (a scratch
 * folder on the PC, native path).
 */
class PcLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun contractAgainstSyncBoard() {
        val uri = System.getenv("TWINFILE_PC_URI") ?: return
        val port = System.getenv("TWINFILE_PC_PORT")!!.toInt()
        val root = System.getenv("TWINFILE_PC_ROOT")!!
        val (_, _, token) = PcPairing.parseQr(uri)!!
        val me = Me("twinfile-test", "TwinFile test")
        val pc = PcPairing.pair(me, listOf("127.0.0.1"), port, token)
        val src = PcSource(PcLink(pc, me, onHost = {}, onUnpaired = {}))

        // Drives at "/", quick-access folders among the roots.
        assertTrue(src.list("/").any { it.name == "C:" })
        assertTrue(src.roots().isNotEmpty())

        File(root).mkdirs()
        runContract(src, PcSource.fromPc(root), tmp)

        // Copy inside the PC under a new name (server copy + rename).
        val dir = PcSource.fromPc(root)
        src.openWrite(Paths.join(dir, "x.txt"), 3).use { it.write("abc".toByteArray()) }
        src.copy(Paths.join(dir, "x.txt"), Paths.join(dir, "y.txt")) { _, _ -> true }
        assertEquals("abc", src.openRead(Paths.join(dir, "y.txt")).use { String(it.readBytes()) })
        assertEquals("abc", src.openRead(Paths.join(dir, "x.txt")).use { String(it.readBytes()) })
        assertEquals(null, src.stat(Paths.join(dir, "x (1).txt")))

        // Closing a pull early cancels it and leaves the session usable.
        File(root, "big.bin").writeBytes(ByteArray(6 * 1024 * 1024) { 1 })
        src.openRead(Paths.join(dir, "big.bin")).use { it.read(ByteArray(1000)) }
        assertEquals(3L, src.stat(Paths.join(dir, "y.txt"))!!.size)
        src.close()
    }
}
