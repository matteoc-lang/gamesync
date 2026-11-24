package it.matteo.gamesync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val id: String, val name: String, val pkg: String,
                 val extPath: String, val intPath: String, val mirror: Boolean)
data class Session(val entry: Entry, val started: Long)

object Store {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("gamesync", Context.MODE_PRIVATE)
    }
    var autoOpen: Boolean get() = p.getBoolean("auto_open", true); set(v) { p.edit().putBoolean("auto_open", v).apply() }
    var watchdog: Boolean get() = p.getBoolean("watchdog", true); set(v) { p.edit().putBoolean("watchdog", v).apply() }
    var lastLog: String get() = p.getString("log", "") ?: ""; set(v) { p.edit().putString("log", v).apply() }

    private fun Entry.json() = JSONObject().put("id", id).put("name", name).put("pkg", pkg)
        .put("ext", extPath).put("int", intPath).put("mirror", mirror)
    private fun fromJson(o: JSONObject) = Entry(o.getString("id"), o.getString("name"), o.getString("pkg"),
        o.getString("ext"), o.getString("int"), o.optBoolean("mirror", false))

    var entries: List<Entry>
        get() { val a = JSONArray(p.getString("entries", "[]")); return (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        set(v) { val a = JSONArray(); v.forEach { a.put(it.json()) }; p.edit().putString("entries", a.toString()).apply() }

    var session: Session?
        get() { val s = p.getString("session", null) ?: return null; val o = JSONObject(s)
            return Session(fromJson(o.getJSONObject("entry")), o.getLong("started")) }
        set(v) { if (v == null) p.edit().remove("session").apply()
            else p.edit().putString("session", JSONObject().put("started", v.started).put("entry", v.entry.json()).toString()).apply() }
}
