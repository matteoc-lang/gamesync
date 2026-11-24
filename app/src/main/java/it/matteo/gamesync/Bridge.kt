package it.matteo.gamesync

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

object Bridge {
    var service: IUserService? by mutableStateOf(null); private set
    var status: String by mutableStateOf("Shizuku non collegato"); private set
    private var bound = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
            .daemon(false).processNameSuffix("sync").debuggable(BuildConfig.DEBUG).version(1)
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = if (b != null && b.pingBinder()) IUserService.Stub.asInterface(b) else null
            bound = service != null
            status = if (bound) "Shizuku pronto" else "Binder non valido"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false; status = "Shizuku disconnesso"
        }
    }

    fun init(ctx: Context) {
        Shizuku.addBinderReceivedListenerSticky(Shizuku.OnBinderReceivedListener {
            if (granted()) bind() else status = "Permesso Shizuku mancante"
        })
        Shizuku.addBinderDeadListener(Shizuku.OnBinderDeadListener {
            service = null; bound = false; status = "Shizuku non attivo"
        })
        Shizuku.addRequestPermissionResultListener(Shizuku.OnRequestPermissionResultListener { _, r ->
            if (r == PackageManager.PERMISSION_GRANTED) bind() else status = "Permesso Shizuku negato"
        })
    }
    fun alive() = try { Shizuku.pingBinder() } catch (e: Exception) { false }
    fun granted() = try { alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Exception) { false }
    fun request() { try { if (alive()) Shizuku.requestPermission(1001) else status = "Avvia prima Shizuku" } catch (e: Exception) { status = e.message ?: "errore" } }
    fun bind() {
        if (bound || !granted()) return
        try { Shizuku.bindUserService(args, conn); status = "Collegamento…" }
        catch (e: Exception) { status = e.message ?: "bind fallito" }
    }
}
