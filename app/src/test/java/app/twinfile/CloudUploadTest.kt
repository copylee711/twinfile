package app.twinfile

import app.twinfile.cloud.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudUploadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun oneDriveUploadsBoundedChunksWithoutBearer() {
        MockWebServer().use { server ->
            server.start()
            val length = 5 * 1024 * 1024 + 17
            val file = tmp.newFile().apply { writeBytes(ByteArray(length) { (it % 251).toByte() }) }
            server.enqueue(MockResponse().setBody("""{"uploadUrl":"${server.url("/upload")}"}"""))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"nextExpectedRanges":["5242880-"]}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"done","name":"file 中文.bin","size":$length}"""))
            val api = OneDriveApi(CloudHttp(server.url("/v1.0/"), { "token" }))
            assertEquals(length.toLong(), api.upload("parent/id", "file 中文.bin", null, file).size)
            val start = server.takeRequest()
            assertEquals("Bearer token", start.getHeader("Authorization"))
            assertTrue(start.path!!.contains("parent%2Fid"))
            assertFalse(start.path!!.contains("%252F"))
            assertTrue(start.body.readUtf8().contains("\"fail\""))
            val first = server.takeRequest()
            assertNull(first.getHeader("Authorization"))
            assertEquals("bytes 0-5242879/$length", first.getHeader("Content-Range"))
            assertEquals(5L * 1024 * 1024, first.bodySize)
            val last = server.takeRequest()
            assertEquals("bytes 5242880-${length - 1}/$length", last.getHeader("Content-Range"))
            assertEquals(17L, last.bodySize)
        }
    }

    @Test fun oneDriveEmptyFileIsAnActualZeroByteUpload() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"empty","name":"empty.txt","size":0}"""))
            val api = OneDriveApi(CloudHttp(server.url("/v1.0/"), { "token" }))
            assertEquals(0L, api.upload("parent", "empty.txt", null, tmp.newFile()).size)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(0L, request.bodySize)
            assertEquals("fail", request.requestUrl!!.queryParameter("@microsoft.graph.conflictBehavior"))
        }
    }

    @Test fun googleResumableUploadChecksAcknowledgedRanges() {
        MockWebServer().use { server ->
            server.start()
            val length = 4 * 1024 * 1024 + 3
            val file = tmp.newFile().apply { writeBytes(ByteArray(length)) }
            server.enqueue(MockResponse().addHeader("Location", server.url("/upload-session")))
            server.enqueue(MockResponse().setResponseCode(308).addHeader("Range", "bytes=0-4194303"))
            server.enqueue(MockResponse().setBody("""{"id":"done","name":"data.bin","mimeType":"application/octet-stream","size":"$length"}"""))
            val api = GoogleDriveApi(CloudHttp(server.url("/drive/v3/"), { "token" }), server.url("/upload/drive/v3/"))
            assertEquals("done", api.upload("parent", "data.bin", null, file).id)
            val start = server.takeRequest()
            assertEquals("resumable", start.requestUrl!!.queryParameter("uploadType"))
            assertEquals(length.toString(), start.getHeader("X-Upload-Content-Length"))
            val first = server.takeRequest()
            assertEquals("Bearer token", first.getHeader("Authorization"))
            assertEquals("bytes 0-4194303/$length", first.getHeader("Content-Range"))
            val last = server.takeRequest()
            assertEquals("bytes 4194304-${length - 1}/$length", last.getHeader("Content-Range"))
        }
    }

    @Test fun failedUploadAbandonsSessionAndDoesNotRetryCreate() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"uploadUrl":"${server.url("/session")}"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("private provider error"))
            server.enqueue(MockResponse().setResponseCode(204))
            val file = tmp.newFile().apply { writeText("data") }
            val api = OneDriveApi(CloudHttp(server.url("/v1.0/"), { "token" }))
            assertTrue(runCatching { api.upload("root", "data", null, file) }.exceptionOrNull() is CloudHttpException)
            assertEquals("POST", server.takeRequest().method)
            assertEquals("PUT", server.takeRequest().method)
            val cleanup = server.takeRequest()
            assertEquals("DELETE", cleanup.method)
            assertNull(cleanup.getHeader("Authorization"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun googleEmptyFilesDoNotNeedResumableSessions() {
        MockWebServer().use { server ->
            server.start()
            val empty = """{"id":"empty","name":"empty.txt","mimeType":"application/octet-stream","size":"0"}"""
            server.enqueue(MockResponse().setBody(empty))
            server.enqueue(MockResponse().setBody(empty))
            val api = GoogleDriveApi(CloudHttp(server.url("/drive/v3/"), { "token" }), server.url("/upload/drive/v3/"))
            val file = tmp.newFile()
            assertEquals(0L, api.upload("parent", "empty.txt", null, file).size)
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/drive/v3/files", create.requestUrl!!.encodedPath)
            val existing = CloudNode("empty", "empty.txt", false, 20)
            assertEquals(0L, api.upload("parent", "empty.txt", existing, file).size)
            val overwrite = server.takeRequest()
            assertEquals("PATCH", overwrite.method)
            assertEquals("media", overwrite.requestUrl!!.queryParameter("uploadType"))
            assertEquals(0L, overwrite.bodySize)
        }
    }
}
