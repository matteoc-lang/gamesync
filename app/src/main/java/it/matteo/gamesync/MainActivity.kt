package it.matteo.gamesync

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(this)
        applyOrientation()
        setContent {
            GameSyncTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Ui(::applyOrientation)
                }
            }
        }
    }

    private fun applyOrientation() {
        requestedOrientation = if (Store.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onResume() {
        super.onResume()
        Bridge.bind()
        if (Store.session == null) return
        lifecycleScope.launch {
            if (Bridge.service == null) return@launch
            val top = withContext(Dispatchers.IO) { runCatching { Bridge.service?.topPackage() ?: "" }.getOrDefault("") }
            if (top.isEmpty() || top == packageName) SessionManager.finish(this@MainActivity, "rientro nell'app")
            else if (Store.watchdog) SessionService.start(this@MainActivity)
        }
    }
}

@Composable
private fun GameSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7CE0A0), onPrimary = Color(0xFF06231A),
            secondary = Color(0xFF8FD6FF),
            background = Color(0xFF0B1512), surface = Color(0xFF12211C),
            surfaceVariant = Color(0xFF1B302A), onSurface = Color(0xFFE6F2EC),
            onBackground = Color(0xFFE6F2EC), outline = Color(0xFF3C5A50)
        ), content = content
    )
}

private fun gamepadConnected(ctx: Context): Boolean {
    val im = ctx.getSystemService(InputManager::class.java) ?: return false
    return im.inputDeviceIds.any { id ->
        val d = im.getInputDevice(id) ?: return@any false
        val s = d.sources
        !d.isVirtual && ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
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
        .joinToString("\n") { c -> s.shell(c).trim().ifEmpty { "ok" } }
}

@Composable
private fun appIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, 128, 128)
            d.draw(c)
            bmp.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun GameButton(
    text: String,
    icon: ImageVector? = null,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val mod = Modifier.onKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            onClick(); true
        } else false
    }.then(modifier)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) Button(onClick = onClick, modifier = mod, content = content)
    else OutlinedButton(onClick = onClick, modifier = mod, content = content)
}

@Composable
private fun GameSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                onChange(!checked); true
            } else false
        })
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun Keys(onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBack(); true
        } else false
    }) { content() }
}

@Composable
private fun GameTile(
    e: Entry, fr: FocusRequester, isFocused: Boolean,
    onPlay: () -> Unit, onEdit: () -> Unit, onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = modifier
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) false else when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> { onPlay(); true }
                    KeyEvent.KEYCODE_BUTTON_X -> { onEdit(); true }
                    else -> false
                }
            }
            .clip(shape)
            .clickable { onPlay() }
            .border(3.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val ic = appIcon(e.pkg)
            if (ic != null) Image(ic, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            else Icon(painterResource(R.drawable.ic_gamepad), null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.pkg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (e.mirror) " mirror" else " copia", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Ui(onOrientationChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalConfiguration.current

    var entries by remember { mutableStateOf(Store.entries) }
    var focused by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Entry?>(null) }
    var picker by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(Store.autoOpen) }
    var wd by remember { mutableStateOf(Store.watchdog) }
    var land by remember { mutableStateOf(Store.landscape) }
    var log by remember { mutableStateOf(Store.lastLog) }
    var tick by remember { mutableStateOf(0) }
    var padOk by remember { mutableStateOf(false) }
    var columns by remember { mutableStateOf(2) }

    val requesters = remember(entries.size) { List(entries.size) { FocusRequester() } }
    val dialogOpen = edit != null || picker || settings || browse != null || busy != null || msg != null
    val maxH = (cfg.screenHeightDp * 0.55f).dp

    LaunchedEffect(Unit) { while (true) { padOk = gamepadConnected(ctx); delay(2500) } }
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            focused = focused.coerceIn(0, entries.size - 1)
            delay(150)
            runCatching { requesters[focused].requestFocus() }
        }
    }

    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) treePath(u)?.let { p -> edit = edit?.copy(extPath = p) }
    }

    fun move(d: Int) {
        if (entries.isEmpty()) return
        focused = (focused + d).coerceIn(0, entries.size - 1)
        val n = focused
        scope.launch { runCatching { requesters[n].requestFocus() } }
    }

    fun play(e: Entry) = scope.launch {
        busy = "Copia cloud → gioco…"
        val r = runCatching { SessionManager.push(e) }.getOrElse { CopyResult(false, "ERRORE: ${it.message}") }
        log = Store.lastLog; busy = null
        if (!r.ok) { msg = r.msg; return@launch }
        SessionManager.begin(ctx, e)
        val li = ctx.packageManager.getLaunchIntentForPackage(e.pkg)
        if (li == null) { msg = "App non trovata: ${e.pkg}"; return@launch }
        li.flags = li.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        try { ctx.startActivity(li) } catch (ex: Exception) { msg = "Avvio fallito: ${ex.message}" }
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || dialogOpen) false
        else when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(columns); true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(-columns); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { picker = true; true }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> { settings = true; true }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gamepad), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("GameSync", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(if (padOk) "controller collegato" else "nessun controller", fontSize = 12.sp,
                    color = if (padOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.weight(1f))
                Text(Bridge.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.width(10.dp))
                GameButton("Impostazioni", Icons.Filled.Settings, false) { settings = true }
            }
            Spacer(Modifier.height(8.dp))

            val sess = remember(tick) { Store.session }
            if (sess != null) Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sessione aperta: ${sess.entry.name}", fontWeight = FontWeight.SemiBold)
                        Text("I save tornano nella cartella esterna alla chiusura", fontSize = 11.sp)
                    }
                    GameButton("Copia e chiudi", Icons.Filled.Refresh) {
                        scope.launch {
                            busy = "Copia gioco → cloud…"
                            msg = SessionManager.finish(ctx, "manuale")
                            log = Store.lastLog; busy = null; tick++
                        }
                    }
                }
            }

            if (entries.isEmpty()) Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nessun gioco configurato", fontWeight = FontWeight.SemiBold)
                    Text("Premi Y sul controller oppure il pulsante in basso per aggiungere il primo.", fontSize = 12.sp)
                }
            }

            BoxWithConstraints(Modifier.weight(1f)) {
                val cols = (maxWidth / 300.dp).toInt().coerceIn(1, 4)
                LaunchedEffect(cols) { columns = cols }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    entries.chunked(cols).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { c, e ->
                                val i = r * cols + c
                                GameTile(e, requesters[i], focused == i,
                                    onPlay = { play(e) }, onEdit = { edit = e }, onFocus = { focused = i },
                                    modifier = Modifier.weight(1f).padding(5.dp))
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f).padding(5.dp)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameButton("Aggiungi gioco", Icons.Filled.Add) { picker = true }
                Spacer(Modifier.width(12.dp))
                Text("A Gioca · X Modifica · Y Nuovo · B Indietro · Start Impostazioni",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }

    if (busy != null) AlertDialog(onDismissRequest = {}, confirmButton = {},
        title = { Text(busy ?: "") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp)); Text("Shizuku sta copiando…")
            }
        })

    msg?.let { m ->
        AlertDialog(onDismissRequest = { msg = null },
            confirmButton = { GameButton("OK", Icons.Filled.Check) { msg = null } },
            title = { Text("GameSync") },
            text = { Keys({ msg = null }) { Text(m, fontSize = 13.sp) } })
    }

    if (settings) AlertDialog(onDismissRequest = { settings = false },
        confirmButton = { GameButton("Chiudi", Icons.Filled.Close) { settings = false } },
        title = { Text("Impostazioni") },
        text = {
            Keys({ settings = false }) {
                Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                    Text(Bridge.status, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        GameButton("Permesso Shizuku", Icons.Filled.Lock, false) { Bridge.request() }
                        Spacer(Modifier.width(8.dp))
                        GameButton("Configura", Icons.Filled.Refresh, false) {
                            scope.launch { busy = "Configurazione…"; msg = setup(ctx); busy = null }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GameSwitch(auto, "Apri quando collego il controller") { auto = it; Store.autoOpen = it }
                    GameSwitch(wd, "Sorveglianza durante la partita") { wd = it; Store.watchdog = it }
                    GameSwitch(land, "Blocca orientamento orizzontale") { land = it; Store.landscape = it; onOrientationChange() }
                    Spacer(Modifier.height(10.dp))
                    Text("Log", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(log.ifEmpty { "vuoto" }, fontSize = 11.sp)
                }
            }
        })

    if (picker) AlertDialog(onDismissRequest = { picker = false },
        confirmButton = { GameButton("Annulla", Icons.Filled.Close, false) { picker = false } },
        title = { Text("Scegli il gioco") },
        text = {
            Keys({ picker = false }) {
                val apps = remember { launchables(ctx) }
                LazyColumn(Modifier.heightIn(max = maxH)) {
                    items(apps) { (name, pkg) ->
                        GameButton(name, null, false, Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            picker = false
                            edit = Entry(System.currentTimeMillis().toString(), name, pkg,
                                "/storage/emulated/0/GameSync/$pkg",
                                "/storage/emulated/0/Android/data/$pkg/files", false)
                        }
                    }
                }
            }
        })

    edit?.let { e ->
        AlertDialog(onDismissRequest = { edit = null },
            confirmButton = {
                GameButton("Salva", Icons.Filled.Check) {
                    val list = entries.filter { it.id != e.id } + e
                    entries = list; Store.entries = list; edit = null
                }
            },
            dismissButton = { GameButton("Annulla", Icons.Filled.Close, false) { edit = null } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ic = appIcon(e.pkg)
                    if (ic != null) Image(ic, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(e.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Keys({ edit = null }) {
                    Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                        OutlinedTextField(e.name, { edit = e.copy(name = it) }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(e.pkg, { edit = e.copy(pkg = it) }, label = { Text("Package") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(e.extPath, { edit = e.copy(extPath = it) }, label = { Text("Cartella esterna (Syncthing)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(e.intPath, { edit = e.copy(intPath = it) }, label = { Text("Cartella in Android/data") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row {
                            GameButton("Cartella esterna", Icons.Filled.List, false) { folder.launch(null) }
                            Spacer(Modifier.width(8.dp))
                            GameButton("Sfoglia Android/data", Icons.Filled.Search, false) { browse = e.intPath }
                        }
                        GameSwitch(e.mirror, "Mirror (cestina i file rimossi)") { edit = e.copy(mirror = it) }
                        Spacer(Modifier.height(8.dp))
                        GameButton("Elimina gioco", Icons.Filled.Delete, false) {
                            val list = entries.filter { it.id != e.id }
                            entries = list; Store.entries = list; edit = null
                        }
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
            confirmButton = { GameButton("Usa questa", Icons.Filled.Check) { edit = edit?.copy(intPath = path); browse = null } },
            dismissButton = { GameButton("Su", Icons.Filled.ArrowBack, false) { browse = path.substringBeforeLast('/', "/storage/emulated/0") } },
            title = { Text(path, fontSize = 12.sp) },
            text = {
                Keys({ browse = null }) {
                    LazyColumn(Modifier.heightIn(max = maxH)) {
                        items(dirs) { d ->
                            GameButton(d, Icons.Filled.List, false, Modifier.fillMaxWidth().padding(vertical = 2.dp)) { browse = "$path/$d" }
                        }
                    }
                }
            })
    }
}
