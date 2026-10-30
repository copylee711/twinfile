package app.twinfile

import app.twinfile.cloud.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudHttpTest {
    @Test fun paginationReadsEveryPageAndRejectsForeignNextLinks() {
        MockWebServer().use { server ->
            server.start()
            val http = CloudHttp(server.url("/v1.0/"), { "secret" })
            server.enqueue(MockResponse().setBody("""{"value":[{"id":"1","name":"a","size":1}],"@odata.nextLink":"${server.url("/next")}"}"""))
            server.enqueue(MockResponse().setBody("""{"value":[{"id":"2","name":"b","size":2}]}"""))
            assertEquals(listOf("a", "b"), OneDriveApi(http).children("root").map { it.name })
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"value":[],"@odata.nextLink":"https://evil.invalid/steal"}"""))
            assertTrue(runCatching { OneDriveApi(http).children("root") }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun unauthorizedRefreshesOnceAndNeverExposesProviderErrorBody() {
        MockWebServer().use { server ->
            server.start()
            val flags = mutableListOf<Boolean>()
            val http = CloudHttp(server.url("/"), { force -> flags += force; if (force) "new" else "old" })
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("{}"))
            http.json(server.url("/test"))
            assertEquals(listOf(false, true), flags)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret-upload-url-and-token"))
            val error = runCatching { http.json(server.url("/test")) }.exceptionOrNull()!!
            assertFalse(error.toString().contains("secret-upload"))
            assertNull(error.cause)
        }
    }

    @Test fun preauthenticatedDownloadDoesNotCarryBearerEvenOnSameHost() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/temporary-download")))
            server.enqueue(MockResponse().setBody("file content"))
            val http = CloudHttp(server.url("/"), { "token" })
            assertEquals("file content", http.stream(server.url("/content")).use { String(it.readBytes()) })
            assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun revokedAccountBlocksUploadSessionRequestsToo() {
        MockWebServer().use { server ->
            server.start()
            val http = CloudHttp(server.url("/"), { "token" }, allowed = { false })
            assertTrue(runCatching { http.request(server.url("/upload"), authenticated = false) }.exceptionOrNull() is CloudAuthRequired)
            assertNull(server.takeRequest(20, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun googlePaginationAndPdfExport() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"files":[{"id":"a","name":"Report","mimeType":"application/vnd.google-apps.document"}],"nextPageToken":"page2"}"""))
            server.enqueue(MockResponse().setBody("""{"files":[{"id":"b","name":"data.bin","mimeType":"application/octet-stream","size":"10"}]}"""))
            val api = GoogleDriveApi(CloudHttp(server.url("/drive/v3/"), { "token" }))
            val files = api.children("parent'id")
            assertEquals(listOf("Report.pdf", "data.bin"), files.map { it.name })
            assertFalse(files.first().writable)
            val first = server.takeRequest()
            assertTrue(first.requestUrl!!.queryParameter("q")!!.contains("parent\\'id"))
            assertEquals("page2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
            server.enqueue(MockResponse().setBody("%PDF"))
            assertEquals("%PDF", api.download(files.first()).use { String(it.readBytes()) })
            assertEquals("application/pdf", server.takeRequest().requestUrl!!.queryParameter("mimeType"))
        }
    }

    @Test fun repeatedUnauthorizedStopsAfterOneRefreshAndBusyReadsRetry() {
        MockWebServer().use { server ->
            server.start()
            val http = CloudHttp(server.url("/"), { "token" })
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { http.json(server.url("/test")) }.exceptionOrNull() is CloudAuthRequired)
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{}"))
            http.json(server.url("/test"))
            assertEquals(4, server.requestCount)
        }
    }
}
