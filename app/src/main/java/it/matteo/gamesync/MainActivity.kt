package it.matteo.gamesync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s); Store.init(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Ui() } } }
    }
    override fun onResume() {
        super.onResume(); Bridge.bind()
        if (Store.session == null) return
        lifecycleScope.launch {
            if (Bridge.service == null) return@launch
            val top = withContext(Dispatchers.IO) { runCatching { Bridge.service?.topPackage() ?: "" }.getOrDefault("") }
            if (top.isEmpty() || top == packageName) SessionManager.finish(this@MainActivity, "rientro nell'app")
            else if (Store.watchdog) SessionService.start(this@MainActivity)
        }
    }
}

private fun treePath(u: Uri): String? {
    val id = DocumentsContract.getTreeDocumentId(u) ?: return null
    val parts = id.split(":", limit = 2)
    val root = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
    return if (parts.size > 1 && parts[1].isNotEmpty()) "$root/${parts[1]}" else root
}

private fun launchables(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }.sortedBy { it.first.lowercase() }
}

private suspend fun setup(ctx: Context): String = withContext(Dispatchers.IO) {
    val s = Bridge.service ?: return@withContext "Shizuku non collegato"
    val p = ctx.packageName
    listOf("appops set $p SYSTEM_ALERT_WINDOW allow",
        "appops set $p MANAGE_EXTERNAL_STORAGE allow",
        "pm grant $p android.permission.BLUETOOTH_CONNECT",
        "pm grant $p android.permission.POST_NOTIFICATIONS",
        "dumpsys deviceidle whitelist +$p")
        .joinToString("\n") { c -> s.shell(c).trim().ifEmpty { "ok: ${c.substringBeforeLast(' ')}" } }
}

@Composable
private fun Ui() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(Store.entries) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Entry?>(null) }
    var picker by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(Store.autoOpen) }
    var wd by remember { mutableStateOf(Store.watchdog) }
    var log by remember { mutableStateOf(Store.lastLog) }
    var tick by remember { mutableStateOf(0) }

    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) treePath(u)?.let { p -> edit = edit?.copy(extPath = p) }
    }

    fun play(e: Entry) = scope.launch {
        busy = "Copia cloud → gioco…"
        val r = runCatching { SessionManager.push(e) }.getOrElse { CopyResult(false, "ERRORE: ${it.message}") }
        log = Store.lastLog; busy = null
        if (!r.ok) { msg = r.msg; return@launch }
        SessionManager.begin(ctx, e)
        val li = ctx.packageManager.getLaunchIntentForPackage(e.pkg)
        if (li == null) { msg = "App non trovata: ${e.pkg}"; return@launch }
        li.flags = li.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()   // stesso task: rientro naturale
        try { ctx.startActivity(li) } catch (ex: Exception) { msg = "Avvio fallito: ${ex.message}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("GameSync", style = MaterialTheme.typography.headlineMedium)
        Text(Bridge.status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { Bridge.request() }) { Text("Permesso Shizuku") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { busy = "Configurazione…"; msg = setup(ctx); busy = null } }) { Text("Configura") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(auto, { auto = it; Store.autoOpen = it }); Text("Apri quando collego il controller")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(wd, { wd = it; Store.watchdog = it }); Text("Sorveglianza solo durante la partita")
        }

        val sess = remember(tick) { Store.session }
        if (sess != null) Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Sessione aperta: ${sess.entry.name}")
                Button(onClick = { scope.launch {
                    busy = "Copia gioco → cloud…"
                    msg = SessionManager.finish(ctx, "manuale"); log = Store.lastLog; busy = null; tick++
                } }) { Text("Copia adesso e chiudi") }
            }
        }

        entries.forEach { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text("${e.pkg}\n${e.extPath}\n↕ ${e.intPath}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = { play(e) }) { Text("Gioca") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { edit = e }) { Text("Modifica") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { entries = entries.filter { it.id != e.id }; Store.entries = entries }) { Text("Elimina") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { picker = true }) { Text("+ Aggiungi gioco") }
        Spacer(Modifier.height(12.dp))
        Text(log, style = MaterialTheme.typography.bodySmall)
    }

    if (busy != null) AlertDialog(onDismissRequest = {}, confirmButton = {},
        title = { Text(busy!!) }, text = { CircularProgressIndicator() })

    msg?.let { m -> AlertDialog(onDismissRequest = { msg = null },
        confirmButton = { TextButton(onClick = { msg = null }) { Text("OK") } },
        title = { Text("GameSync") }, text = { Text(m) }) }

    if (picker) AlertDialog(onDismissRequest = { picker = false }, confirmButton = {},
        title = { Text("Scegli il gioco") },
        text = {
            val apps = remember { launchables(ctx) }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(apps) { (name, pkg) ->
                    TextButton(onClick = {
                        picker = false
                        edit = Entry(System.currentTimeMillis().toString(), name, pkg,
                            "/storage/emulated/0/GameSync/$pkg",
                            "/storage/emulated/0/Android/data/$pkg/files", false)
                    }) { Text("$name  ·  $pkg") }
                }
            }
        })

    edit?.let { e ->
        AlertDialog(onDismissRequest = { edit = null },
            confirmButton = { TextButton(onClick = {
                val list = entries.filter { it.id != e.id } + e
                entries = list; Store.entries = list; edit = null
            }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Annulla") } },
            title = { Text(e.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(e.name, { edit = e.copy(name = it) }, label = { Text("Nome") })
                    OutlinedTextField(e.pkg, { edit = e.copy(pkg = it) }, label = { Text("Package") })
                    OutlinedTextField(e.extPath, { edit = e.copy(extPath = it) }, label = { Text("Cartella esterna (Syncthing)") })
                    TextButton(onClick = { folder.launch(null) }) { Text("Scegli cartella esterna") }
                    OutlinedTextField(e.intPath, { edit = e.copy(intPath = it) }, label = { Text("Cartella in Android/data") })
                    TextButton(onClick = { browse = e.intPath }) { Text("Sfoglia con Shizuku") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(e.mirror, { edit = e.copy(mirror = it) })
                        Text("Mirror (cestina i file rimossi)")
                    }
                }
            })
    }

    browse?.let { path ->
        var dirs by remember(path) { mutableStateOf(listOf<String>()) }
        LaunchedEffect(path) {
            dirs = withContext(Dispatchers.IO) {
                runCatching {
                    val a = JSONObject(Bridge.service!!.listDirs(path)).getJSONArray("dirs")
                    (0 until a.length()).map { a.getString(it) }
                }.getOrDefault(emptyList())
            }
        }
        AlertDialog(onDismissRequest = { browse = null },
            confirmButton = { TextButton(onClick = { edit = edit?.copy(intPath = path); browse = null }) { Text("Usa questa") } },
            dismissButton = { TextButton(onClick = { browse = path.substringBeforeLast('/', "/storage/emulated/0") }) { Text("Su") } },
            title = { Text(path, style = MaterialTheme.typography.bodySmall) },
            text = { LazyColumn(Modifier.heightIn(max = 320.dp)) { items(dirs) { d ->
                TextButton(onClick = { browse = "$path/$d" }) { Text(d) } } } })
    }
}
