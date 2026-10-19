package app.twinfile

import app.twinfile.fs.FileSource
import app.twinfile.fs.LocalSource
import app.twinfile.fs.Paths
import app.twinfile.fs.Root
import app.twinfile.net.FtpSource
import app.twinfile.net.HostKeyUnknown
import app.twinfile.net.NetConfig
import app.twinfile.net.NetType
import app.twinfile.net.SftpSource
import app.twinfile.net.WebDavSource
import app.twinfile.ops.Transfer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Runs every network source through the same file-operation contract against a real server. */
class NetSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun contract(s: FileSource, root: String) = runContract(s, root, tmp)

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun ftp() {
        val home = tmp.newFolder("ftp")
        val port = freePort()
        val f = FtpServerFactory()
        f.addListener("default", ListenerFactory().apply { this.port = port }.createListener())
        f.userManager.save(BaseUser().apply {
            name = "u"; password = "p"; homeDirectory = home.path; authorities = listOf(WritePermission())
        })
        val server = f.createServer()
        server.start()
        try {
            contract(FtpSource(NetConfig(type = NetType.FTP, name = "", host = "127.0.0.1", port = port, user = "u", password = "p")), "/")
        } finally {
            server.stop()
        }
    }

    @Test fun sftp() {
        val home = tmp.newFolder("sftp")
        val sshd = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp.root, "hostkey.ser").toPath())
            setPasswordAuthenticator { u, p, _ -> u == "u" && p == "p" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(home.toPath())
        }
        sshd.start()
        try {
            var cfg = NetConfig(type = NetType.SFTP, name = "", host = "127.0.0.1", port = sshd.port, user = "u", password = "p")
            // First contact: the fingerprint must be confirmed before anything else happens.
            val fp = try {
                SftpSource(cfg).list("/")
                fail("expected HostKeyUnknown"); ""
            } catch (e: HostKeyUnknown) {
                e.fingerprint
            }
            cfg = cfg.copy(hostKey = fp)
            contract(SftpSource(cfg), "/")
            // A different pinned key is refused.
            val r = runCatching { SftpSource(cfg.copy(hostKey = "00:11")).list("/") }
            assertTrue(r.exceptionOrNull() is app.twinfile.net.HostKeyChanged)
        } finally {
            sshd.stop()
        }
    }

    @Test fun webdav() {
        val root = tmp.newFolder("dav")
        val server = MockWebServer()
        server.dispatcher = DavDispatcher(root)
        server.start()
        try {
            contract(WebDavSource(NetConfig(type = NetType.WEBDAV, name = "", host = "127.0.0.1", port = server.port, path = "/")), "/")
        } finally {
            server.shutdown()
        }
    }

    /**
     * Manual end-to-end aid: with TWINFILE_SERVE=<minutes>, keep FTP (2121), SFTP (2222) and
     * WebDAV (8088) up so an emulator can browse them at 10.0.2.2. User "u", password "p".
     */
    @Test fun serveForEmulator() {
        val minutes = System.getenv("TWINFILE_SERVE")?.toLongOrNull() ?: return
        val home = File(System.getenv("TWINFILE_SERVE_DIR") ?: tmp.newFolder("serve").path).apply { mkdirs() }
        val f = FtpServerFactory()
        f.addListener("default", ListenerFactory().apply {
            port = 2121
            dataConnectionConfiguration = org.apache.ftpserver.DataConnectionConfigurationFactory().apply {
                passiveExternalAddress = "10.0.2.2"
                passivePorts = "2130-2140"
            }.createDataConnectionConfiguration()
        }.createListener())
        f.userManager.save(BaseUser().apply { name = "u"; password = "p"; homeDirectory = home.path; authorities = listOf(WritePermission()) })
        val ftp = f.createServer().apply { start() }
        val sshd = SshServer.setUpDefaultServer().apply {
            port = 2222
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(home.parentFile, "serve-hostkey.ser").toPath())
            setPasswordAuthenticator { u, p, _ -> u == "u" && p == "p" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(home.toPath())
            start()
        }
        val dav = MockWebServer().apply { dispatcher = DavDispatcher(home); start(java.net.InetAddress.getByName("127.0.0.1"), 8088) }
        println("serving ${home.path} for $minutes min")
        Thread.sleep(minutes * 60_000)
        dav.shutdown(); sshd.stop(); ftp.stop()
    }

    /** A minimal WebDAV server over a folder, enough for the contract above. */
    private class DavDispatcher(val root: File) : Dispatcher() {
        private val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }

        private fun fileOf(urlPath: String) = File(root, URLDecoder.decode(urlPath, "UTF-8").trimEnd('/'))

        private fun href(f: File): String {
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val b = "http://x/".toHttpUrl().newBuilder()
            rel.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
            if (f.isDirectory) b.addPathSegment("")
            return b.build().encodedPath
        }

        private fun prop(f: File) = """<d:response><d:href>${href(f)}</d:href><d:propstat><d:prop>""" +
            (if (f.isDirectory) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>${f.length()}</d:getcontentlength>") +
            "<d:getlastmodified>${date.format(f.lastModified())}</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val f = fileOf(request.requestUrl!!.encodedPath)
            fun dest() = fileOf(request.getHeader("Destination")!!.toHttpUrl().encodedPath)
            return when (request.method) {
                "PROPFIND" -> {
                    if (!f.exists()) return MockResponse().setResponseCode(404)
                    val items = listOf(f) + if (request.getHeader("Depth") == "1" && f.isDirectory) f.listFiles()!!.toList() else emptyList()
                    MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${items.joinToString("") { prop(it) }}</d:multistatus>""")
                }
                "MKCOL" -> if (f.exists()) MockResponse().setResponseCode(405) else { f.mkdirs(); MockResponse().setResponseCode(201) }
                "PUT" -> { f.writeBytes(request.body.readByteArray()); MockResponse().setResponseCode(201) }
                "GET" -> if (!f.isFile) MockResponse().setResponseCode(404) else MockResponse().setBody(Buffer().write(f.readBytes()))
                "DELETE" -> { f.deleteRecursively(); MockResponse().setResponseCode(204) }
                "MOVE" -> { val d = dest(); if (d.exists()) MockResponse().setResponseCode(412) else { f.renameTo(d); MockResponse().setResponseCode(201) } }
                "COPY" -> { val d = dest(); if (d.exists()) MockResponse().setResponseCode(412) else { f.copyRecursively(d); MockResponse().setResponseCode(201) } }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}
