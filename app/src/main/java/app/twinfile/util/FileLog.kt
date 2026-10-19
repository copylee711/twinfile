package app.twinfile.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat plus a small rotating file in the app's files dir. Some ROMs (vivo OriginOS) hide
 * third-party logcat output, so the file is what bug reports rely on.
 */
object FileLog {
    private const val TAG = "TwinFile"
    private const val MAX = 512 * 1024L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        file = File(dir, "log.txt")
    }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, msg, t)
            "W" -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg)
        }
        val f = file ?: return
        try {
            if (f.length() > MAX) f.renameTo(File(f.parentFile, "log.old.txt"))
            f.appendText(buildString {
                append(fmt.format(Date())).append(' ').append(level).append(' ').append(msg).append('\n')
                if (t != null) append(Log.getStackTraceString(t)).append('\n')
            })
        } catch (_: Exception) {
        }
    }
}
