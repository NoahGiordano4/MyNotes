package com.mynotes.app

import android.app.*
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

private data class Stroke(val size: Float, val points: MutableList<Float>)
private data class Note(val id: String, var title: String, var updated: Long, val pages: MutableList<MutableList<Stroke>>)

private class NoteStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mynotes", Context.MODE_PRIVATE)
    fun all(): MutableList<Note> = prefs.getStringSet("ids", emptySet())!!.mapNotNull { load(it) }.sortedByDescending { it.updated }.toMutableList()
    fun load(id: String): Note? = prefs.getString("note_$id", null)?.let { decode(JSONObject(it)) }
    fun save(note: Note) { prefs.edit().putString("note_${note.id}", encode(note).toString()).putStringSet("ids", (prefs.getStringSet("ids", emptySet())!! + note.id)).apply() }
    fun delete(note: Note) { prefs.edit().remove("note_${note.id}").putStringSet("ids", prefs.getStringSet("ids", emptySet())!! - note.id).apply() }
    fun encode(n: Note) = JSONObject().apply { put("format", "mynotes.doc"); put("version", 1); put("app", "MyNotes"); put("id", n.id); put("title", n.title); put("createdAt", n.updated); put("updatedAt", n.updated); put("pages", JSONArray().apply { n.pages.forEachIndexed { i, page -> put(JSONObject().apply { put("index", i); put("strokes", JSONArray().apply { page.forEach { s -> put(JSONObject().apply { put("c", "#000000"); put("s", s.size); put("p", JSONArray(s.points)) }) } }) }) } }) }
    fun decode(o: JSONObject): Note { val pages = mutableListOf<MutableList<Stroke>>(); val a = o.optJSONArray("pages") ?: JSONArray().put(JSONObject().put("index", 0).put("strokes", JSONArray())); for (i in 0 until a.length()) { val ss = mutableListOf<Stroke>(); val strokes = a.getJSONObject(i).optJSONArray("strokes") ?: JSONArray(); for (j in 0 until strokes.length()) { val s = strokes.getJSONObject(j); val p = s.optJSONArray("p") ?: JSONArray(); ss += Stroke(s.optDouble("s", 3.4).toFloat(), MutableList(p.length()) { p.getDouble(it).toFloat() }) }; pages += ss }; if (pages.isEmpty()) pages += mutableListOf(); return Note(o.optString("id", UUID.randomUUID().toString()), o.optString("title", "Sans titre"), o.optLong("updatedAt", System.currentTimeMillis()), pages) }
}

class MainActivity : Activity() {
    private lateinit var store: NoteStore
    private lateinit var root: FrameLayout
    private var note: Note? = null
    private var page = 0
    private var mode = "stylus"
    private val blue = Color.rgb(37, 99, 235)
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(state: Bundle?) { super.onCreate(state); store = NoteStore(this); mode = getPreferences(0).getString("input", "stylus")!!; showMenu() }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(15,23,42)); gravity = Gravity.CENTER_VERTICAL; setPadding((16*dp).toInt(), 0, (16*dp).toInt(), 0) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; minHeight = (48*dp).toInt(); setOnClickListener { action() } }

    private fun showMenu() {
        root = FrameLayout(this); root.setBackgroundColor(Color.rgb(238,241,246)); setContentView(root)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding((8*dp).toInt(), 0, (8*dp).toInt(), 0) }
        bar.addView(text("✍  MyNotes", 21f), LinearLayout.LayoutParams(0, 64.dp(), 1f)); bar.addView(button("Importer") { importNote() }, LinearLayout.LayoutParams(100.dp(), 64.dp())); bar.addView(button("⚙", ::showSettings), LinearLayout.LayoutParams(64.dp(), 64.dp()))
        column.addView(bar); val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 16.dp(), 16.dp(), 90.dp()) }
        store.all().forEach { n -> val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp()); setBackgroundColor(Color.WHITE); setOnClickListener { open(n.id) } }
            card.addView(text(n.title, 18f)); card.addView(text("${n.pages.size} page(s)  •  ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(n.updated))}", 13f)); val actions = LinearLayout(this)
            actions.addView(button("Renommer") { rename(n) }, LinearLayout.LayoutParams(0, 48.dp(), 1f)); actions.addView(button("Exporter") { exportNote(n) }, LinearLayout.LayoutParams(0, 48.dp(), 1f)); actions.addView(button("Supprimer") { delete(n) }, LinearLayout.LayoutParams(0, 48.dp(), 1f)); card.addView(actions); list.addView(card, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,12.dp()) }) }
        scroll.addView(list); column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(column)
        root.addView(button("＋  Nouvelle note") { val n = Note(UUID.randomUUID().toString(), "Sans titre", System.currentTimeMillis(), mutableListOf(mutableListOf())); store.save(n); open(n.id) }, FrameLayout.LayoutParams(-1, 58.dp(), Gravity.BOTTOM).apply { setMargins(16.dp(),0,16.dp(),16.dp()) })
    }

    private fun rename(n: Note) { val input = EditText(this); input.setText(n.title); AlertDialog.Builder(this).setTitle("Renommer la note").setView(input).setNegativeButton("Annuler", null).setPositiveButton("OK") { _, _ -> n.title = input.text.toString().ifBlank { "Sans titre" }; n.updated = System.currentTimeMillis(); store.save(n); showMenu() }.show() }
    private fun delete(n: Note) { AlertDialog.Builder(this).setTitle("Supprimer la note ?").setMessage("${n.title} • ${n.pages.size} page(s)\nCette action est irréversible.").setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ -> store.delete(n); showMenu() }.show() }
    private fun showSettings() { val choices = arrayOf("Stylet uniquement", "Stylet + doigt"); AlertDialog.Builder(this).setTitle("Mode de saisie").setSingleChoiceItems(choices, if (mode == "stylus") 0 else 1) { d, which -> mode = if (which == 0) "stylus" else "finger"; getPreferences(0).edit().putString("input", mode).apply(); d.dismiss() }.setNegativeButton("Fermer", null).show() }

    private fun exportNote(n: Note) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; putExtra(Intent.EXTRA_TITLE, "${n.title}.mynotes.json") }
        pendingExport = store.encode(n).toString(2); startActivityForResult(intent, EXPORT_REQUEST)
    }
    private var pendingExport: String? = null
    private val EXPORT_REQUEST = 41
    private val IMPORT_REQUEST = 42
    override fun onActivityResult(request: Int, result: Int, data: Intent?) { super.onActivityResult(request, result, data); if (result != RESULT_OK || data?.data == null) return; try { if (request == EXPORT_REQUEST) contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(pendingExport ?: "") }; if (request == IMPORT_REQUEST) { val json = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: return; val imported = store.decode(JSONObject(json)); val copy = imported.copy(id = UUID.randomUUID().toString(), title = "${imported.title} (importée)", updated = System.currentTimeMillis()); store.save(copy); showMenu() } } catch (_: Exception) { Toast.makeText(this, "Fichier MyNotes invalide", Toast.LENGTH_SHORT).show() } }
    private fun importNote() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, IMPORT_REQUEST) }
    private fun open(id: String) { note = store.load(id) ?: return; page = 0; showEditor() }
    private fun showEditor() {
        val n = note ?: return
        root = FrameLayout(this); setContentView(root)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val canvas = InkCanvas(this)
        val label = text("", 13f).apply { gravity = Gravity.CENTER }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE) }
        bar.addView(button("‹") { saveAndMenu() }, LinearLayout.LayoutParams(54.dp(), 60.dp()))
        val title = EditText(this).apply { setText(n.title); textSize = 18f; singleLine = true }
        bar.addView(title, LinearLayout.LayoutParams(0, 60.dp(), 1f))
        bar.addView(button("↶") { canvas.undo() }, LinearLayout.LayoutParams(54.dp(), 60.dp()))
        bar.addView(button("↷") { canvas.redo() }, LinearLayout.LayoutParams(54.dp(), 60.dp()))
        bar.addView(button("＋") { n.pages += mutableListOf(); page = n.pages.lastIndex; canvas.invalidate(); updatePage(label) }, LinearLayout.LayoutParams(54.dp(), 60.dp()))
        bar.addView(label, LinearLayout.LayoutParams(90.dp(), 60.dp()))
        col.addView(bar)
        col.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE) }
        listOf("2.2" to 2.2f, "3.4" to 3.4f, "5.4" to 5.4f).forEach { (s, v) -> tools.addView(button(s) { canvas.size = v }, LinearLayout.LayoutParams(0, 58.dp(), 1f)) }
        tools.addView(button("Gomme") { canvas.eraser = !canvas.eraser }, LinearLayout.LayoutParams(0, 58.dp(), 1f))
        col.addView(tools); root.addView(col)
        title.setOnFocusChangeListener { _, has -> if (!has) { n.title = title.text.toString(); n.updated = System.currentTimeMillis(); store.save(n) } }
        updatePage(label)
    }
    private fun updatePage(label: TextView) { label.text = "${page+1} / ${note?.pages?.size ?: 1}" }
    private fun saveAndMenu() { note?.let { it.updated = System.currentTimeMillis(); store.save(it) }; note = null; showMenu() }
    override fun onBackPressed() { if (note != null) saveAndMenu() else super.onBackPressed() }
    private fun Int.dp() = (this * dp).roundToInt()

    private inner class InkCanvas(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        var size = 3.4f; var eraser = false; private var active: MutableList<Float>? = null; private var lastX=0f; private var lastY=0f; private var zoom=1f; private var panX=0f; private var panY=0f; private var oldDist=0f
        private fun list() = note!!.pages[page]
        override fun onDraw(c: Canvas) { super.onDraw(c); c.drawColor(Color.rgb(220,225,232)); val scale=min((width-32*dp)/(794f), (height-32*dp)/(1123f))*zoom; c.save(); c.translate(width/2f+panX, height/2f+panY); c.scale(scale,scale); c.translate(-397f,-561.5f); paint.style=Paint.Style.FILL; paint.color=Color.WHITE; c.drawRect(0f,0f,794f,1123f,paint); paint.style=Paint.Style.STROKE; list().forEach { drawStroke(c,it) }; active?.let { drawStroke(c,Stroke(size,it)) }; c.restore() }
        private fun drawStroke(c: Canvas,s:Stroke) { if(s.points.size<2)return; paint.strokeWidth=s.size; val p=Path(); p.moveTo(s.points[0],s.points[1]); var i=3; while(i<s.points.size){p.lineTo(s.points[i],s.points[i+1]);i+=3}; c.drawPath(p,paint) }
        private fun world(x:Float,y:Float):Pair<Float,Float>{ val scale=min((width-32*dp)/(794f), (height-32*dp)/(1123f))*zoom; return Pair((x-width/2f-panX)/scale+397f,(y-height/2f-panY)/scale+561.5f) }
        override fun onTouchEvent(e: MotionEvent): Boolean { when(e.actionMasked){ MotionEvent.ACTION_DOWN -> { if(mode=="stylus" && e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER){lastX=e.x;lastY=e.y;return true}; if(e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER) eraser=true; val q=world(e.x,e.y); active= mutableListOf(q.first,q.second,e.pressure.coerceIn(.1f,1f)); lastX=e.x;lastY=e.y }
            MotionEvent.ACTION_MOVE -> { if(e.pointerCount>=2){active=null; val dx=e.getX(1)-e.getX(0);val dy=e.getY(1)-e.getY(0);val d=hypot(dx,dy);if(oldDist>0)zoom=(zoom*d/oldDist).coerceIn(.2f,8f);oldDist=d;panX+=(e.x-e.getHistoricalX(0));panY+=(e.y-e.getHistoricalY(0));invalidate();return true}; if(active!=null){val q=world(e.x,e.y);if(hypot(e.x-lastX,e.y-lastY)>1){active!!.addAll(listOf(q.first,q.second,e.pressure.coerceIn(.1f,1f)));lastX=e.x;lastY=e.y;invalidate()}} }
            MotionEvent.ACTION_POINTER_DOWN -> { if(e.pointerCount>=2){active=null;oldDist=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));invalidate()} }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active?.let { if(eraser){list().removeAll{hit(it,e.x,e.y)}}else list().add(Stroke(size,it)) };active=null;oldDist=0f;note!!.updated=System.currentTimeMillis();store.save(note!!);invalidate() }; return true }
        private fun hit(s:Stroke,x:Float,y:Float):Boolean{val q=world(x,y);var i=0;while(i<s.points.size){if(hypot(s.points[i]-q.first,s.points[i+1]-q.second)<18)return true;i+=3};return false}
        fun undo(){if(list().isNotEmpty()){list().removeAt(list().lastIndex);invalidate();store.save(note!!)}}; fun redo() {}
    }
}
