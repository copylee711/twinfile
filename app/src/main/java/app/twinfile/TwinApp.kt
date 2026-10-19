package app.twinfile

import android.app.Application
import android.system.Os
import app.twinfile.fs.FileSource
import app.twinfile.fs.LocalSource
import app.twinfile.fs.Volumes
import app.twinfile.net.NetManager
import app.twinfile.pc.PcManager
import app.twinfile.ops.Ops
import app.twinfile.ops.Trash
import app.twinfile.util.FileLog
import app.twinfile.priv.RootShell
import app.twinfile.priv.ShellSource
import app.twinfile.priv.ShizukuShell
import java.io.File

class TwinApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var local: LocalSource
        private set
    lateinit var trash: Trash
        private set

    /** Every source a pane can show, by [FileSource.id]. Network and PC sources register here. */
    val sources: MutableMap<String, FileSource> = java.util.concurrent.ConcurrentHashMap()
    lateinit var net: NetManager
        private set
    lateinit var pc: PcManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        FileLog.init(filesDir)
        FileLog.i("start ${BuildConfigInfo.version(this)}")
        Ops.init(this)
        prefs = Prefs(filesDir)
        local = LocalSource({ Volumes.roots(this) }, ::modeString)
        sources[local.id] = local
        trash = Trash { path -> Volumes.of(this, path)?.path }
        applyPrivMode()
        net = NetManager(this)
        pc = PcManager(this)
    }

    fun source(id: String): FileSource? = sources[id] ?: pc.lazySource(id)

    /** Protected folders through root or Shizuku; null when that is switched off. */
    var priv: ShellSource? = null
        private set

    /** (Re)creates [priv] for [Prefs.privMode]. */
    fun applyPrivMode() {
        priv?.close()
        val shell = when (prefs.privMode) {
            "ROOT" -> RootShell()
            "SHIZUKU" -> ShizukuShell(packageName)
            "SYNCBOARD" -> app.twinfile.pc.SyncBoardBridge.Shell()
            else -> null
        }
        priv = shell?.let { ShellSource(it) { Volumes.roots(this) } }
        priv?.let { sources[it.id] = it } ?: sources.remove(ShellSource.ID)
    }

    private fun modeString(f: File): String? = try {
        val m = Os.lstat(f.path).st_mode
        val chars = "rwxrwxrwx"
        val s = StringBuilder()
        for (i in 0 until 9) s.append(if (m and (1 shl (8 - i)) != 0) chars[i] else '-')
        "$s (${"%o".format(m and 0x1ff)})"
    } catch (_: Exception) {
        null
    }

    companion object {
        lateinit var instance: TwinApp
            private set
    }
}

object BuildConfigInfo {
    fun version(app: Application): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
