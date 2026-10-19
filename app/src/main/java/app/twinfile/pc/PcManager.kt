package app.twinfile.pc

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import app.twinfile.TwinApp
import app.twinfile.fs.FsException
import java.util.concurrent.Executors

/** Paired PCs, each registered as a [PcSource]; links close after two idle minutes. */
class PcManager(private val app: TwinApp) {
    private val store = PcStore(app.filesDir)
    val me = Me(store.deviceId, store.deviceName)
    /** Observable list for the UI. */
    val pcs = mutableStateListOf<PcInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    init {
        store.all().forEach { register(it) }
        pcs.addAll(store.all())
        handler.postDelayed(object : Runnable {
            override fun run() {
                val idle = app.sources.values.filterIsInstance<PcSource>().filter { it.link.idleFor() > IDLE_MS }
                if (idle.isNotEmpty()) io.execute { idle.forEach { it.close() } }
                handler.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    fun source(pc: PcInfo): PcSource? = app.sources[pc.sourceId] as? PcSource

    private fun register(pc: PcInfo) {
        app.sources.remove(pc.sourceId)?.let { old -> io.execute { old.close() } }
        val link = PcLink(pc, me, onHost = { updated -> store.upsert(updated); refreshList() }, onUnpaired = {
            handler.post { forget(pc.id) }
        })
        app.sources[pc.sourceId] = PcSource(link)
    }

    private fun refreshList() = handler.post {
        pcs.clear()
        pcs.addAll(store.all())
    }

    /** Blocking: pairs from a scanned QR code. */
    fun pairQr(text: String): PcInfo {
        val (hosts, port, token) = PcPairing.parseQr(text) ?: throw FsException("这不是 SyncBoard 的配对二维码")
        return added(PcPairing.pair(me, hosts, port, token))
    }

    /** Blocking: pairs with a discovered PC; the user compares [onCode]'s digits on the PC. */
    fun pairCode(host: String, port: Int, onCode: (String, String) -> Unit): PcInfo =
        added(PcPairing.pair(me, listOf(host), port, null, onCode))

    private fun added(pc: PcInfo): PcInfo {
        val keepAlias = store.get(pc.id)?.alias.orEmpty()
        val p = pc.copy(alias = keepAlias)
        store.upsert(p)
        handler.post { register(p) }
        refreshList()
        return p
    }

    fun discover() = PcPairing.discover(me, 1500)

    fun rename(pc: PcInfo, alias: String) {
        val p = pc.copy(alias = alias.trim())
        store.upsert(p)
        source(pc)?.link?.pc = p
        refreshList()
    }

    /** Unpairs on both sides when the PC is reachable. */
    fun unpair(pc: PcInfo) {
        val src = source(pc)
        io.execute { src?.link?.unpair() }
        forget(pc.id)
    }

    private fun forget(id: String) {
        store.remove(id)
        app.sources.remove("pc:$id")?.let { old -> io.execute { old.close() } }
        refreshList()
    }

    private companion object {
        const val IDLE_MS = 2 * 60_000L
    }
}
