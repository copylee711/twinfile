package app.twinfile.net

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import app.twinfile.TwinApp
import app.twinfile.fs.FileSource
import java.util.concurrent.Executors

/** Saved network locations, their sources, and the idle-connection reaper. */
class NetManager(private val app: TwinApp) {
    private val store = NetStore(app.filesDir)
    val locations = mutableStateListOf<NetConfig>()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    init {
        io.execute {
            val loaded = store.load()
            handler.post {
                locations.addAll(loaded)
                loaded.forEach { register(it) }
            }
        }
        handler.postDelayed(object : Runnable {
            override fun run() {
                reap()
                handler.postDelayed(this, REAP_EVERY)
            }
        }, REAP_EVERY)
    }

    fun create(c: NetConfig): FileSource = when (c.type) {
        NetType.FTP, NetType.FTPS -> FtpSource(c)
        NetType.SFTP -> SftpSource(c)
        NetType.SMB -> SmbSource(c)
        NetType.WEBDAV -> WebDavSource(c)
    }

    private fun register(c: NetConfig) {
        app.sources.remove(c.sourceId)?.let { old -> io.execute { old.close() } }
        app.sources[c.sourceId] = create(c)
    }

    fun upsert(c: NetConfig) {
        val i = locations.indexOfFirst { it.id == c.id }
        if (i >= 0) locations[i] = c else locations += c
        register(c)
        persist()
    }

    fun remove(c: NetConfig) {
        locations.removeAll { it.id == c.id }
        app.sources.remove(c.sourceId)?.let { old -> io.execute { old.close() } }
        persist()
    }

    private fun persist() {
        val snapshot = locations.toList()
        io.execute { store.save(snapshot) }
    }

    /** Closes connections that have been idle for a while; they reopen on next use. */
    private fun reap() {
        val idle = app.sources.values.filterIsInstance<NetSource<*>>().filter { it.idleFor() > IDLE_MS }
        if (idle.isNotEmpty()) io.execute { idle.forEach { it.close() } }
    }

    private companion object {
        const val IDLE_MS = 2 * 60_000L
        const val REAP_EVERY = 30_000L
    }
}
