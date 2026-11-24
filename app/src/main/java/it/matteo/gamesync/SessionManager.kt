package it.matteo.gamesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class CopyResult(val ok: Boolean, val msg: String)

object SessionManager {
    private val busy = AtomicBoolean(false)
    private const val TRASH = "/storage/emulated/0/GameSync/.trash"
    private fun svc(): IUserService = Bridge.service ?: throw IllegalStateException("Shizuku non collegato")

    private suspend fun copy(src: String, dst: String, mirror: Boolean, tag: String): CopyResult =
        withContext(Dispatchers.IO) {
            val o = JSONObject(svc().copyTree(src, dst, mirror, TRASH))
            val msg = when {
                o.optBoolean("skipped") -> "$tag: saltato, sorgente vuota (nessun dato perso)"
                o.optBoolean("ok") -> "$tag: OK ${o.optInt("files")} file, ${o.optLong("bytes") / 1024} KB"
                else -> "$tag: ERRORE ${o.opt("errors") ?: o.optString("msg")}"
            }
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            Store.lastLog = "$ts  $msg\n" + Store.lastLog.take(3000)
            CopyResult(o.optBoolean("ok"), msg)
        }

    suspend fun push(e: Entry) = copy(e.extPath, e.intPath, e.mirror, "cloud→gioco")
    suspend fun pull(e: Entry) = copy(e.intPath, e.extPath, e.mirror, "gioco→cloud")

    fun begin(ctx: Context, e: Entry) {
        Store.session = Session(e, System.currentTimeMillis())
        if (Store.watchdog) SessionService.start(ctx)
    }

    suspend fun finish(ctx: Context, reason: String): String {
        val s = Store.session ?: return "nessuna sessione aperta"
        if (!busy.compareAndSet(false, true)) return "copia già in corso"
        return try {
            val r = pull(s.entry)
            if (r.ok) { Store.session = null; SessionService.stop(ctx); "${r.msg} ($reason)" } else r.msg
        } catch (e: Exception) { "ERRORE: ${e.message}" } finally { busy.set(false) }
    }
}
