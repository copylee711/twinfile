package app.twinfile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.twinfile.fs.SortBy
import app.twinfile.fs.SortSpec
import app.twinfile.util.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val name: String, val source: String, val path: String)

data class Recent(val source: String, val path: String, val isDir: Boolean, val time: Long)

/** Settings, bookmarks and history; observable from Compose, saved as JSON on every change. */
class Prefs(dir: File) {
    private val store = JsonStore(File(dir, "settings.json"))
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    var dualPane by mutableStateOf(true)
    var palette by mutableStateOf("GRAPHITE")
    var darkMode by mutableStateOf("SYSTEM")
    var showHidden by mutableStateOf(false)
    var compact by mutableStateOf(false)
    /** Slide the list in when entering or leaving a folder. */
    var animateNav by mutableStateOf(true)
    /** Bookmarks section in the drawer expanded. */
    var drawerBookmarks by mutableStateOf(true)
    /** Look for a new release on GitHub at startup. */
    var autoUpdate by mutableStateOf(true)
    /** OFF, SHIZUKU or ROOT: how to reach Android/data and other protected folders. */
    var privMode by mutableStateOf("OFF")
    var trashEnabled by mutableStateOf(true)
    var trashByDefault by mutableStateOf(true)
    var trashAutoCleanDays by mutableStateOf(30)
    var keepMtime by mutableStateOf(true)
    var homeLeft by mutableStateOf("")
    var homeRight by mutableStateOf("")
    var sortLeft by mutableStateOf(SortSpec())
    var sortRight by mutableStateOf(SortSpec())
    /** Per-folder sort overrides, keyed by "source:path". */
    val folderSort = HashMap<String, SortSpec>()
    val bookmarks = mutableStateListOf<Bookmark>()
    /** Default app per file extension (or MIME type), as flattened ComponentNames. */
    val openers = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val recent = mutableStateListOf<Recent>()

    init {
        val j = store.load()
        dualPane = j.optBoolean("dualPane", true)
        palette = j.optString("palette", "GRAPHITE")
        darkMode = j.optString("darkMode", "SYSTEM")
        showHidden = j.optBoolean("showHidden", false)
        compact = j.optBoolean("compact", false)
        animateNav = j.optBoolean("animateNav", true)
        drawerBookmarks = j.optBoolean("drawerBookmarks", true)
        autoUpdate = j.optBoolean("autoUpdate", true)
        privMode = j.optString("privMode", "OFF")
        trashEnabled = j.optBoolean("trashEnabled", true)
        trashByDefault = j.optBoolean("trashByDefault", true)
        trashAutoCleanDays = j.optInt("trashAutoCleanDays", 30)
        keepMtime = j.optBoolean("keepMtime", true)
        homeLeft = j.optString("homeLeft")
        homeRight = j.optString("homeRight")
        sortLeft = sortOf(j.optJSONObject("sortLeft"))
        sortRight = sortOf(j.optJSONObject("sortRight"))
        j.optJSONObject("folderSort")?.let { o -> o.keys().forEach { k -> folderSort[k] = sortOf(o.optJSONObject(k)) } }
        j.optJSONObject("openers")?.let { o -> o.keys().forEach { k -> openers[k] = o.optString(k) } }
        j.optJSONArray("bookmarks")?.forEachObj {
            bookmarks += Bookmark(it.optString("name"), it.optString("source", "local"), it.optString("path"))
        }
        j.optJSONArray("recent")?.forEachObj {
            recent += Recent(it.optString("source", "local"), it.optString("path"), it.optBoolean("dir", true), it.optLong("time"))
        }
    }

    /** Snapshots state on the caller's thread and writes it on a background thread. */
    fun save() {
        val j = snapshot()
        writer.execute { store.save(j) }
    }

    private fun snapshot(): JSONObject {
        return JSONObject()
            .put("dualPane", dualPane).put("palette", palette).put("darkMode", darkMode).put("showHidden", showHidden).put("compact", compact).put("animateNav", animateNav)
            .put("drawerBookmarks", drawerBookmarks).put("autoUpdate", autoUpdate).put("privMode", privMode)
            .put("trashEnabled", trashEnabled).put("trashByDefault", trashByDefault)
            .put("trashAutoCleanDays", trashAutoCleanDays).put("keepMtime", keepMtime)
            .put("homeLeft", homeLeft).put("homeRight", homeRight)
            .put("sortLeft", sortJson(sortLeft)).put("sortRight", sortJson(sortRight))
            .put("folderSort", JSONObject().also { o -> folderSort.forEach { (k, v) -> o.put(k, sortJson(v)) } })
            .put("openers", JSONObject().also { o -> openers.forEach { (k, v) -> o.put(k, v) } })
            .put("bookmarks", JSONArray().also { a ->
                bookmarks.forEach { a.put(JSONObject().put("name", it.name).put("source", it.source).put("path", it.path)) }
            })
            .put("recent", JSONArray().also { a ->
                recent.forEach { a.put(JSONObject().put("source", it.source).put("path", it.path).put("dir", it.isDir).put("time", it.time)) }
            })
    }


    inline fun update(block: Prefs.() -> Unit) {
        block()
        save()
    }

    fun addRecent(source: String, path: String, isDir: Boolean) {
        recent.removeAll { it.source == source && it.path == path }
        recent.add(0, Recent(source, path, isDir, System.currentTimeMillis()))
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        save()
    }

    private fun sortOf(o: JSONObject?): SortSpec {
        if (o == null) return SortSpec()
        val by = try { SortBy.valueOf(o.optString("by", "NAME")) } catch (_: Exception) { SortBy.NAME }
        return SortSpec(by, o.optBoolean("reverse"))
    }

    private fun sortJson(s: SortSpec) = JSONObject().put("by", s.by.name).put("reverse", s.reverse)

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(f)
    }

    private companion object {
        const val MAX_RECENT = 60
    }
}
