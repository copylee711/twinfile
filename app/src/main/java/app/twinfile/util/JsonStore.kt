package app.twinfile.util

import org.json.JSONObject
import java.io.File

/** One JSON document in the app's files dir, written atomically. */
class JsonStore(private val file: File) {
    @Synchronized
    fun load(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        FileLog.w("bad ${file.name}, starting fresh", e)
        JSONObject()
    }

    @Synchronized
    fun save(json: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
