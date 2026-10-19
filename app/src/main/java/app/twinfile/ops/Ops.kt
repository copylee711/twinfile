package app.twinfile.ops

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.twinfile.fs.CancelledException
import app.twinfile.util.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch

/** A long file operation shown in the progress panel and the notification. */
class OpJob(val title: String) {
    val stats = TransferStats()
    var state by mutableStateOf(State.RUNNING)
    var error by mutableStateOf<String?>(null)
    /** Set while the worker waits for the user to settle a name clash. */
    var conflict by mutableStateOf<Conflict?>(null)
    @Volatile private var answer: Pair<Resolution, Boolean>? = null
    private var latch: CountDownLatch? = null

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    /** Called on the worker thread; blocks until [answer] is called. */
    fun ask(c: Conflict): Pair<Resolution, Boolean>? {
        val l = CountDownLatch(1)
        latch = l
        answer = null
        conflict = c
        l.await()
        conflict = null
        return answer
    }

    /** [r] null cancels the job. */
    fun answer(r: Resolution?, applyToAll: Boolean) {
        answer = r?.let { it to applyToAll }
        if (r == null) stats.cancelled = true
        latch?.countDown()
    }

    fun cancel() {
        stats.cancelled = true
        latch?.countDown()
    }
}

/** Runs file operations off the UI thread and keeps a foreground service alive meanwhile. */
object Ops {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val jobs = mutableStateListOf<OpJob>()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    val running get() = jobs.filter { it.state == OpJob.State.RUNNING }

    /** Runs [work] as a job; [done] runs on the worker thread afterwards, success or not. */
    fun start(title: String, work: (OpJob) -> Unit, done: () -> Unit = {}): OpJob {
        val job = OpJob(title)
        jobs += job
        startService()
        scope.launch {
            try {
                work(job)
                job.state = if (job.stats.cancelled) OpJob.State.CANCELLED else OpJob.State.DONE
            } catch (_: CancelledException) {
                job.state = OpJob.State.CANCELLED
            } catch (e: Exception) {
                FileLog.e("op failed: $title", e)
                job.error = e.message ?: e.javaClass.simpleName
                job.state = OpJob.State.FAILED
            } finally {
                done()
            }
        }
        return job
    }

    fun dismiss(job: OpJob) {
        jobs -= job
    }

    private fun startService() {
        val i = Intent(app, TransferService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            // Background start restrictions: the job still runs while the app is visible.
            FileLog.w("could not start TransferService", e)
        }
    }
}
