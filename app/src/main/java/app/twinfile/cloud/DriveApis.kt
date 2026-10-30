package app.twinfile.cloud

import app.twinfile.fs.FsException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.Instant

internal fun JSONObject.body() = toString().toRequestBody("application/json; charset=utf-8".toMediaType())
private fun time(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
private fun segment(value: String) = HttpUrl.Builder().scheme("https").host("example.invalid").addPathSegment(value).build().encodedPath.removePrefix("/")
private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }

class OneDriveApi(private val http: CloudHttp) : CloudApi {
    private fun item(id: String, tail: String = "") = http.file("me/drive/items", id, tail)
    private fun node(j: JSONObject): CloudNode {
        // Remote items/shortcuts need a driveId-aware API, so don't follow them implicitly.
        if (j.has("remoteItem")) return CloudNode(j.getString("id"), j.getString("name"), false,
            mime = "application/x-twinfile-remote-shortcut", writable = false)
        return CloudNode(j.getString("id"), j.getString("name"), j.has("folder"), j.optLong("size", -1),
            time(j.optString("lastModifiedDateTime")), j.optJSONObject("file")?.optString("mimeType").orEmpty())
    }
    override fun root() = node(http.json(http.api("me/drive/root")))
    override fun get(id: String) = node(http.json(item(id)))
    override fun children(id: String): List<CloudNode> {
        val all = mutableListOf<CloudNode>()
        var next: HttpUrl? = item(id, "children")
        val seen = HashSet<String>()
        while (next != null) {
            if (!http.trusted(next) || !seen.add(next.toString()) || seen.size > 10_000) throw FsException("云盘分页地址无效")
            val json = http.json(next)
            all += objects(json.getJSONArray("value")).map(::node)
            next = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }?.toHttpUrl()
        }
        return all
    }
    override fun mkdir(parent: String, name: String) = node(http.json(item(parent, "children"), "POST",
        JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail").body()))
    override fun rename(id: String, name: String) = node(http.json(item(id), "PATCH", JSONObject().put("name", name).body()))
    override fun move(id: String, parent: String, oldParent: String, name: String) = node(http.json(item(id), "PATCH",
        JSONObject().put("name", name).put("parentReference", JSONObject().put("id", parent)).body()))
    override fun trash(id: String) { http.request(item(id), "DELETE").close() }
    override fun download(node: CloudNode): InputStream {
        if (node.mime == "application/x-twinfile-remote-shortcut") throw FsException("暂不支持 OneDrive 共享快捷方式，请打开原始云盘")
        return http.stream(item(node.id, "content"))
    }

    override fun upload(parent: String, name: String, existing: CloudNode?, file: File): CloudNode {
        val path = if (existing != null) "me/drive/items/${segment(existing.id)}" else
            "me/drive/items/${segment(parent)}:/${segment(name)}:"
        if (file.length() == 0L) {
            val url = http.api("$path/content").newBuilder().addQueryParameter("@microsoft.graph.conflictBehavior", if (existing == null) "fail" else "replace").build()
            return node(http.json(url, "PUT", ByteArray(0).toRequestBody("application/octet-stream".toMediaType())))
        }
        val session = http.json(http.api("$path/createUploadSession"), "POST", JSONObject().put("item",
            JSONObject().put("@microsoft.graph.conflictBehavior", if (existing == null) "fail" else "replace").put("name", name)).body())
        val upload = session.getString("uploadUrl").toHttpUrl()
        var completed = false
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(5 * 1024 * 1024) // 16 x 320 KiB, bounded regardless of file size.
                var position = 0L
                while (position < file.length()) {
                    val length = minOf(buffer.size.toLong(), file.length() - position).toInt()
                    java.io.DataInputStream(input).readFully(buffer, 0, length)
                    http.request(upload, "PUT", buffer.toRequestBody("application/octet-stream".toMediaType(), 0, length),
                        mapOf("Content-Range" to "bytes $position-${position + length - 1}/${file.length()}"), authenticated = false).use { response ->
                        val result = JSONObject(response.body!!.string())
                        if (response.code == 200 || response.code == 201) {
                            if (position + length != file.length()) throw FsException("云盘过早结束上传")
                            completed = true
                            return node(result)
                        }
                        if (response.code != 202) throw FsException("云盘上传会话状态无效")
                        val expected = result.getJSONArray("nextExpectedRanges").getString(0).substringBefore('-').toLong()
                        if (expected != position + length) throw FsException("云盘上传进度不一致，请重试")
                    }
                    position += length
                }
            }
            throw FsException("云盘没有确认上传完成")
        } finally { if (!completed) runCatching { http.request(upload, "DELETE", authenticated = false).close() } }
    }
}

class GoogleDriveApi(private val http: CloudHttp, private val uploadBase: HttpUrl = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl()) : CloudApi {
    private val fields = "id,name,mimeType,size,modifiedTime,capabilities(canEdit,canDownload)"
    private fun item(id: String) = http.file("files", id).newBuilder().addQueryParameter("supportsAllDrives", "true").build()
    private fun node(j: JSONObject): CloudNode {
        val mime = j.getString("mimeType")
        val export = mime in EXPORTS
        val name = j.getString("name") + if (export) ".pdf" else ""
        return CloudNode(j.getString("id"), name, mime == FOLDER, j.optLong("size", -1), time(j.optString("modifiedTime")), mime,
            writable = j.optJSONObject("capabilities")?.optBoolean("canEdit", true) != false &&
                (!mime.startsWith("application/vnd.google-apps.") || mime == FOLDER))
    }
    override fun root() = get("root")
    override fun get(id: String) = node(http.json(item(id).newBuilder().addQueryParameter("fields", fields).build()))
    override fun children(id: String): List<CloudNode> {
        val all = mutableListOf<CloudNode>()
        val seen = HashSet<String>()
        var token = ""
        do {
            if (!seen.add(token) || seen.size > 10_000) throw FsException("云盘分页状态无效")
            val queryId = id.replace("\\", "\\\\").replace("'", "\\'")
            val url = http.api("files").newBuilder().addQueryParameter("q", "'$queryId' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files($fields)").addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true").addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { if (token.isNotEmpty()) addQueryParameter("pageToken", token) }.build()
            val json = http.json(url)
            all += objects(json.getJSONArray("files")).map(::node)
            token = json.optString("nextPageToken")
        } while (token.isNotEmpty())
        return all
    }
    override fun mkdir(parent: String, name: String) = node(http.json(http.api("files").newBuilder()
        .addQueryParameter("fields", fields).addQueryParameter("supportsAllDrives", "true").build(), "POST",
        JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parent)).body()))
    override fun rename(id: String, name: String): CloudNode {
        val current = get(id)
        val realName = if (current.mime in EXPORTS && name.endsWith(".pdf")) name.dropLast(4) else name
        return node(http.json(item(id).newBuilder().addQueryParameter("fields", fields).build(), "PATCH", JSONObject().put("name", realName).body()))
    }
    override fun move(id: String, parent: String, oldParent: String, name: String) = node(http.json(item(id).newBuilder()
        .addQueryParameter("addParents", parent).addQueryParameter("removeParents", oldParent).addQueryParameter("fields", fields).build(), "PATCH",
        JSONObject().put("name", if (get(id).mime in EXPORTS) name.removeSuffix(".pdf") else name).body()))
    override fun trash(id: String) { http.request(item(id), "PATCH", JSONObject().put("trashed", true).body()).close() }
    override fun download(node: CloudNode): InputStream {
        if (node.mime.startsWith("application/vnd.google-apps.") && node.mime !in EXPORTS) throw FsException("暂不支持导出此类 Google 云端文件")
        val url = if (node.mime in EXPORTS) http.file("files", node.id, "export").newBuilder().addQueryParameter("mimeType", "application/pdf").build()
            else item(node.id).newBuilder().addQueryParameter("alt", "media").build()
        return http.stream(url)
    }
    override fun upload(parent: String, name: String, existing: CloudNode?, file: File): CloudNode {
        val metadata = JSONObject()
        if (existing == null) metadata.put("name", name).put("parents", JSONArray().put(parent))
        if (file.length() == 0L) {
            if (existing == null) return node(http.json(http.api("files").newBuilder()
                .addQueryParameter("supportsAllDrives", "true").addQueryParameter("fields", fields).build(), "POST",
                metadata.put("mimeType", "application/octet-stream").body()))
            val empty = uploadBase.newBuilder().addPathSegment("files").addPathSegment(existing.id)
                .addQueryParameter("uploadType", "media").addQueryParameter("supportsAllDrives", "true").addQueryParameter("fields", fields).build()
            return node(http.json(empty, "PATCH", ByteArray(0).toRequestBody("application/octet-stream".toMediaType())))
        }
        val url = uploadBase.newBuilder().addPathSegment("files").apply { if (existing != null) addPathSegment(existing.id) }
            .addQueryParameter("uploadType", "resumable").addQueryParameter("supportsAllDrives", "true").addQueryParameter("fields", fields).build()
        val session = http.request(url, if (existing == null) "POST" else "PATCH", metadata.body(), mapOf(
            "X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to file.length().toString())).use {
            it.header("Location")?.toHttpUrl() ?: throw FsException("云盘没有返回上传会话")
        }
        var completed = false
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(4 * 1024 * 1024)
                var position = 0L
                do {
                    val length = minOf(buffer.size.toLong(), file.length() - position).toInt()
                    java.io.DataInputStream(input).readFully(buffer, 0, length)
                    val range = if (length == 0) "bytes */0" else "bytes $position-${position + length - 1}/${file.length()}"
                    http.request(session, "PUT", buffer.toRequestBody("application/octet-stream".toMediaType(), 0, length),
                        mapOf("Content-Range" to range), accepted = setOf(308)).use { response ->
                        if (response.isSuccessful) {
                            if (position + length != file.length()) throw FsException("云盘过早结束上传")
                            completed = true
                            return node(JSONObject(response.body!!.string()))
                        }
                        val next = response.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1)
                        if (next != position + length || length == 0) throw FsException("云盘上传进度不一致，请重试")
                    }
                    position += length
                } while (position < file.length())
            }
            throw FsException("云盘没有确认上传完成")
        } finally { if (!completed) runCatching { http.request(session, "DELETE", accepted = setOf(499)).close() } }
    }
    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        const val SHORTCUT = "application/vnd.google-apps.shortcut"
        val EXPORTS = setOf("application/vnd.google-apps.document", "application/vnd.google-apps.spreadsheet", "application/vnd.google-apps.presentation", "application/vnd.google-apps.drawing")
    }
}
