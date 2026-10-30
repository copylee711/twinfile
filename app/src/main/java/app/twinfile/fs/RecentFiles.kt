package app.twinfile.fs

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File

/** Fast system index first, then a cancellable filesystem pass for unindexed documents. */
object RecentFiles {
    const val LIMIT = 500

    @Suppress("DEPRECATION")
    fun indexed(context: Context, roots: List<File>, showHidden: Boolean,
                index: RecentFileIndex, checkCancelled: () -> Unit): Boolean {
        var available = false
        val data = MediaStore.MediaColumns.DATA
        val added = MediaStore.MediaColumns.DATE_ADDED
        val modified = MediaStore.MediaColumns.DATE_MODIFIED
        for (sort in listOf(added, modified)) {
            checkCancelled()
            try {
                val args = Bundle().apply {
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sort))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, LIMIT * 2)
                    if (Build.VERSION.SDK_INT >= 30) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "is_pending = 0 AND is_trashed = 0")
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "is_pending = 0")
                    }
                }
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(data, added, modified), args, null)?.use { c ->
                    available = true
                    var count = 0
                    while (count++ < LIMIT * 2 && c.moveToNext()) {
                        checkCancelled()
                        val raw = c.getString(0) ?: continue
                        val file = File(raw).canonicalFile
                        if (!RecentFileIndex.allowed(file, roots, showHidden) || !file.isFile || !file.canRead()) continue
                        val time = file.lastModified()
                        val changed = maxOf(time, c.getLong(1) * 1000, c.getLong(2) * 1000)
                        index.offer(RecentFile(file.path, FEntry(file.name, false, file.length(), time), changed))
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (_: Exception) { /* Filesystem fallback still includes non-indexed documents. */ }
        }
        return available
    }
}
