package app.twinfile.fs

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** Storage volumes shown in the drawer: internal storage, SD cards and USB drives. */
object Volumes {
    data class Volume(val name: String, val path: String, val total: Long, val free: Long, val removable: Boolean)

    fun internalPath(): String = Environment.getExternalStorageDirectory().path

    fun list(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val out = ArrayList<Volume>()
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = volumeDir(v) ?: continue
            val name = if (v.isPrimary) "内部存储" else v.getDescription(context) ?: dir.name
            out += Volume(name, dir.path, total(dir.path), free(dir.path), v.isRemovable)
        }
        if (out.none { it.path == internalPath() }) {
            out.add(0, Volume("内部存储", internalPath(), total(internalPath()), free(internalPath()), false))
        }
        return out
    }

    private fun volumeDir(v: android.os.storage.StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        // Hidden before API 30 but present on every release since 4.x.
        return try {
            v.javaClass.getMethod("getPathFile").invoke(v) as? File
        } catch (_: Throwable) {
            null
        }
    }

    fun total(path: String): Long = try { StatFs(path).totalBytes } catch (_: Exception) { -1 }
    fun free(path: String): Long = try { StatFs(path).availableBytes } catch (_: Exception) { -1 }

    fun roots(context: Context): List<Root> =
        list(context).map { Root(it.name, it.path, it.total, it.free) } +
            Root("根目录", "/", total("/"), free("/"))

    /** The volume that holds [path], for the "storage used" line under the title. */
    fun of(context: Context, path: String): Volume? =
        list(context).filter { Paths.isInside(path, it.path) }.maxByOrNull { it.path.length }
}
