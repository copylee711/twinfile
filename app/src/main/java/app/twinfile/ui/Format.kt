package app.twinfile.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val short = SimpleDateFormat("yy-MM-dd HH:mm", Locale.US)
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun time(ms: Long): String = if (ms <= 0) "" else synchronized(short) { short.format(Date(ms)) }
    fun fullTime(ms: Long): String = if (ms <= 0) "" else synchronized(full) { full.format(Date(ms)) }

    fun bytesExact(bytes: Long): String = "%,d 字节".format(bytes)
}
