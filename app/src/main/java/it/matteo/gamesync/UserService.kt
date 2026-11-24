package it.matteo.gamesync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserService() : IUserService.Stub() {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context?) : this()

    override fun destroy() { exit() }
    override fun exit() { System.exit(0) }

    private fun sh(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(); out + err
    } catch (e: Exception) { "ERR ${e.message}" }

    override fun shell(cmd: String): String = sh(cmd)

    private val pkgRe = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""")

    override fun topPackage(): String {
        for (line in sh("dumpsys activity activities").lineSequence())
            if (line.contains("ResumedActivity"))
                pkgRe.find(line)?.let { return it.groupValues[1] }
        for (line in sh("dumpsys window windows").lineSequence())
            if (line.contains("mCurrentFocus"))
                pkgRe.find(line)?.let { return it.groupValues[1] }
        return ""
    }

    override fun listDirs(path: String): String {
        val arr = File(path).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        return JSONObject().put("dirs", JSONArray(arr)).toString()
    }

    override fun copyTree(src: String, dst: String, mirror: Boolean, trashRoot: String): String {
        val s = File(src); val d = File(dst); val res = JSONObject()
        if (!s.isDirectory) return res.put("ok", false).put("msg", "sorgente inesistente: $src").toString()
        val files = s.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return res.put("ok", true).put("skipped", true).toString()
        if (!d.exists() && !d.mkdirs()) return res.put("ok", false).put("msg", "non creo $dst").toString()
        if (mirror) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val trash = File(trashRoot, stamp + "_" + dst.replace('/', '_')); trash.mkdirs()
            d.listFiles()?.forEach { old ->
                if (!old.renameTo(File(trash, old.name))) {
                    old.copyRecursively(File(trash, old.name), true); old.deleteRecursively()
                }
            }
        }
        var n = 0; var bytes = 0L; val errors = JSONArray()
        files.forEach { f ->
            val rel = f.relativeTo(s).path; val out = File(d, rel)
            try {
                out.parentFile?.mkdirs()
                f.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out.setLastModified(f.lastModified())
                if (out.length() == f.length()) { n++; bytes += f.length() }
                else errors.put("$rel: dimensione diversa")
            } catch (e: Exception) { errors.put("$rel: ${e.message}") }
        }
        return res.put("ok", errors.length() == 0).put("files", n).put("bytes", bytes)
            .put("srcFiles", files.size).put("errors", errors).toString()
    }
}
