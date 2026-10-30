package app.twinfile.ops

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.twinfile.fs.CancelledException
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ArchivePasswordRequest(val name: String, val error: String?, val answer: (CharArray?) -> Unit)

/** Worker-side password handshake. Passwords live in memory for the operation only. */
class ArchivePasswords {
    var request by mutableStateOf<ArchivePasswordRequest?>(null)
        private set
    @Volatile private var closed = false

    @Synchronized
    fun <T> run(file: File, cancelled: () -> Boolean = { false }, work: (CharArray?) -> T): T {
        var password: CharArray? = null
        try {
            while (true) {
                if (closed || cancelled()) throw CancelledException()
                try { return work(password) }
                catch (e: ArchivePasswordException) {
                    password?.fill('\u0000'); password = null
                    val answer = CompletableFuture<CharArray?>()
                    request = ArchivePasswordRequest(file.name, if (e.retry) e.message else null) {
                        if (!answer.complete(it)) it?.fill('\u0000')
                    }
                    try {
                        while (true) {
                            if (closed || cancelled()) throw CancelledException()
                            try {
                                password = answer.get(250, TimeUnit.MILLISECONDS) ?: throw CancelledException()
                                break
                            } catch (_: TimeoutException) { }
                        }
                    } finally {
                        request = null
                        answer.complete(null)
                        val pending = answer.getNow(null)
                        if (pending !== password) pending?.fill('\u0000')
                    }
                }
            }
        } finally { password?.fill('\u0000') }
    }

    fun close() { closed = true; request?.answer?.invoke(null); request = null }
}
