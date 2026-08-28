package com.example.lumennotes.data

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Stockage local des notes :
 *   files/notes/<id>/meta.json     → métadonnées
 *   files/notes/<id>/p<index>.json → contenu d'UNE page (strokes)
 *
 * Format des pages — v2 (avec en-tête de version) :
 *   { "version": 2,
 *     "strokes": [ { "c": "#000000", "s": 3.4,
 *                    "p": [x, y, pression, …],
 *                    "t": [ms, ms, …]        ← optionnel, par point } ] }
 *
 * Les fichiers v1 (sans "version", sans "t") se lisent tels quels.
 * Écriture page par page + atomique (fichier temporaire puis renommage).
 */
class NoteRepository(context: Context) {

    private val notesDir: File = File(context.filesDir, "notes").apply { mkdirs() }

    companion object {
        const val FORMAT_VERSION = 2

        fun hexColor(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

        fun parseColorOrNull(s: String?): Int? =
            if (s.isNullOrBlank()) null
            else try {
                Color.parseColor(s)
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    fun listMetas(): List<NoteMeta> {
        val dirs = notesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readMeta(it) }.sortedByDescending { it.updatedAt }
    }

    fun getMeta(id: String): NoteMeta? = readMeta(File(notesDir, id))

    fun createNote(title: String): NoteMeta {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val meta = NoteMeta(id, title, now, now, 1)
        val dir = File(notesDir, id)
        dir.mkdirs()
        writeMeta(dir, meta)
        writeAtomic(
            File(dir, "p0.json"),
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("strokes", JSONArray())
                .toString()
        )
        return meta
    }

    fun saveMeta(meta: NoteMeta) {
        val dir = File(notesDir, meta.id)
        dir.mkdirs()
        writeMeta(dir, meta)
    }

    fun deleteNote(id: String) {
        File(notesDir, id).deleteRecursively()
    }

    /* ------------------------------ pages -------------------------- */

    fun loadPage(noteId: String, index: Int): MutableList<Stroke> {
        val f = File(File(notesDir, noteId), "p$index.json")
        if (!f.exists()) return mutableListOf()
        return try {
            parseStrokes(JSONObject(f.readText()).getJSONArray("strokes"))
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePage(noteId: String, index: Int, strokes: List<Stroke>) {
        val dir = File(notesDir, noteId)
        dir.mkdirs()
        writeAtomic(
            File(dir, "p$index.json"),
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("strokes", strokesToJSON(strokes))
                .toString()
        )
    }

    /* ----------------------------- JSON ----------------------------- */

    private fun strokesToJSON(strokes: List<Stroke>): JSONArray {
        val arr = JSONArray()
        for (s in strokes) {
            val p = JSONArray()
            for (v in s.points) p.put((v * 100.0).roundToLong() / 100.0)
            val o = JSONObject()
                .put("c", hexColor(s.color))
                .put("s", (s.size * 100.0).roundToLong() / 100.0)
                .put("p", p)
            val t = s.times
            if (t != null && t.size == s.points.size / 3) {
                val ta = JSONArray()
                for (v in t) ta.put(v)
                o.put("t", ta)
            }
            arr.put(o)
        }
        return arr
    }

    private fun parseStrokes(arr: JSONArray): MutableList<Stroke> {
        val out = ArrayList<Stroke>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pj = o.optJSONArray("p") ?: continue
            val pts = FloatArray(pj.length())
            for (k in 0 until pj.length()) pts[k] = pj.optDouble(k, 0.0).toFloat()
            if (pts.size >= 3) {
                val tj = o.optJSONArray("t")
                val times: LongArray? =
                    if (tj != null && tj.length() == pts.size / 3) {
                        LongArray(tj.length()) { k -> tj.optLong(k, 0L) }
                    } else null
                out.add(
                    Stroke(
                        color = parseColorOrNull(o.optString("c")) ?: Color.BLACK,
                        size = o.optDouble("s", 3.4).toFloat(),
                        points = pts,
                        times = times
                    )
                )
            }
        }
        return out
    }

    /* --------------------------- interne ---------------------------- */

    private fun readMeta(dir: File): NoteMeta? = try {
        val j = JSONObject(File(dir, "meta.json").readText())
        val meta = NoteMeta(
            id = j.getString("id"),
            title = j.optString("title", "Note"),
            createdAt = j.optLong("createdAt", 0L),
            updatedAt = j.optLong("updatedAt", 0L),
            pageCount = j.optInt("pageCount", 1).coerceAtLeast(1)
        )
        val langs = j.optJSONArray("pageLanguages")
        if (langs != null) {
            for (i in 0 until langs.length()) {
                meta.pageLanguages.add(langs.getString(i))
            }
        }
        meta
    } catch (e: Exception) {
        null
    }

    private fun writeMeta(dir: File, meta: NoteMeta) {
        val langs = JSONArray()
        for (l in meta.pageLanguages) langs.put(l)
        
        writeAtomic(
            File(dir, "meta.json"),
            JSONObject()
                .put("id", meta.id)
                .put("title", meta.title)
                .put("createdAt", meta.createdAt)
                .put("updatedAt", meta.updatedAt)
                .put("pageCount", meta.pageCount)
                .put("pageLanguages", langs)
                .toString()
        )
    }

    /** Écriture atomique : fichier temporaire puis renommage. */
    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            // une sauvegarde manquée ne doit jamais faire planter l'app
        }
    }
}