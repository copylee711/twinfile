package app.twinfile

import app.twinfile.fs.FsException
import app.twinfile.net.NetConfig
import app.twinfile.net.NetType
import app.twinfile.net.SmbSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SmbSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun missingShareMustNotReportSuccessfulEmptyListing() {
        val source = SmbSource(NetConfig(type = NetType.SMB, name = "test", host = "invalid.invalid", port = 445))
        try {
            val error = runCatching { source.list(source.home()) }.exceptionOrNull()
            assertTrue("Missing share must fail with guidance, got $error", error is FsException && error.message.orEmpty().contains("共享"))
        } finally { source.close() }
    }

    @Test fun windowsStyleHomePath() {
        val source = SmbSource(NetConfig(type = NetType.SMB, name = "test", host = "localhost", port = 445, path = "\\share\\folder"))
        assertEquals("/share/folder", source.home())
    }

    /** Optional real SMB2 server, never a user's configured share. */
    @Test fun liveServerContract() {
        val port = System.getenv("TWINFILE_SMB_TEST_PORT")?.toIntOrNull()
        assumeTrue("Set TWINFILE_SMB_TEST_PORT for the isolated SMB test server", port != null)
        val source = SmbSource(NetConfig(type = NetType.SMB, name = "test", host = "127.0.0.1", port = port!!, path = "/test"))
        try {
            assertTrue(source.list("/").any { it.name == "test" })
            assertTrue(source.list(source.home()).any { it.name == "marker.txt" })
            assertTrue(runCatching { source.list("/test/missing-directory") }.exceptionOrNull() is FsException)
            val missing = SmbSource(source.config.copy(path = "/missing-share"))
            try {
                assertTrue(runCatching { missing.list("/") }.exceptionOrNull() is FsException)
                assertTrue(runCatching { missing.list(missing.home()) }.exceptionOrNull() is FsException)
            } finally { missing.close() }
            runContract(source, "/test", tmp)
        } finally { source.close() }
    }
}
