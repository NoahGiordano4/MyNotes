package com.example.lumennotes

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.lumennotes.data.EraseHit
import com.example.lumennotes.data.NoteMeta
import com.example.lumennotes.data.NoteRepository
import com.example.lumennotes.data.Prefs
import com.example.lumennotes.data.Stroke
import com.example.lumennotes.databinding.ActivityEditorBinding
import com.example.lumennotes.ink.HistoryStack
import com.example.lumennotes.ink.InkCanvasView
import com.example.lumennotes.ink.InkOp
import com.example.lumennotes.util.AppLog
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Éditeur : document de pages A4 empilées verticalement (scroll continu),
 * encre noire fluide, outils, undo/redo par page, sauvegarde automatique.
 */
class EditorActivity : AppCompatActivity(), InkCanvasView.Host {

    private lateinit var binding: ActivityEditorBinding
    private val repo by lazy { NoteRepository(this) }
    private val io = AppLog.loggedExecutor("editor-io")
    private val main = Handler(Looper.getMainLooper())


    private var noteId: String = ""
    private var meta: NoteMeta? = null

    /** Page courante (suivie via le scroll). */
    private var pageIndex = 0
    private val pages = HashMap<Int, MutableList<Stroke>>()
    private val histories = HashMap<Int, HistoryStack>()

    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var closed = false

    private val accent by lazy { getColor(R.color.primary) }
    private val accentSoft by lazy { getColor(R.color.accent_soft) }
    private val iconDark by lazy { getColor(R.color.icon_dark) }

    private var selectedSize = 1
    private val penSizes = floatArrayOf(2.2f, 3.4f, 5.4f)
    private lateinit var sizeButtons: List<android.widget.ImageButton>

    /* --------------------------- hôte du moteur --------------------------- */

    override fun inputMode(): String = Prefs.inputMode(this)

    override fun onStrokeCommitted(stroke: Stroke, pageIndex: Int) {
        val list = pages[pageIndex] ?: return
        list.add(stroke)
        binding.ink.appendStroke(stroke, pageIndex)
        history(pageIndex).push(InkOp.Add(stroke))
        scheduleSave()
    }

    override fun onErase(items: List<EraseHit>, pageIndex: Int) {
        history(pageIndex).push(InkOp.Erase(items))
        binding.ink.invalidateCache()
        scheduleSave()
    }

    override fun onZoomChanged(scale: Float) {
        binding.zoomChip.text = getString(R.string.zoom_percent, (scale * 100).roundToInt())
    }

    override fun onPageChanged(newIndex: Int) {
        if (newIndex == pageIndex) {
            updatePageUi()
            return
        }
        flushSave()               // sécurise la page qu'on quitte
        pageIndex = newIndex
        updatePageUi()
        updateHistoryButtons()
    }

    /* ------------------------------ création ------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteId = intent.getStringExtra("note_id") ?: ""
        if (noteId.isBlank()) {
            finish()
            return
        }

        sizeButtons = listOf(binding.btnSizeFine, binding.btnSizeMedium, binding.btnSizeBold)
        binding.ink.host = this

        wiring()
        updateToolUi()
        load()
    }

    private fun wiring() {
        binding.btnClose.setOnClickListener { closeNote() }
        binding.btnFit.setOnClickListener { binding.ink.fitPage() }
        binding.zoomChip.setOnClickListener { binding.ink.fitPage() }
        binding.btnUndo.setOnClickListener { applyHistoryOp(false) }
        binding.btnRedo.setOnClickListener { applyHistoryOp(true) }

        binding.btnSizeFine.setOnClickListener { selectSize(0) }
        binding.btnSizeMedium.setOnClickListener { selectSize(1) }
        binding.btnSizeBold.setOnClickListener { selectSize(2) }

        binding.btnEraser.setOnClickListener {
            binding.ink.tool =
                if (binding.ink.tool == InkCanvasView.Tool.ERASER) InkCanvasView.Tool.PEN
                else InkCanvasView.Tool.ERASER
            updateToolUi()
        }

        binding.btnPrevPage.setOnClickListener { binding.ink.scrollToPage(pageIndex - 1) }
        binding.btnNextPage.setOnClickListener { binding.ink.scrollToPage(pageIndex + 1) }
        binding.btnAddPage.setOnClickListener { addPage() }

        binding.titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                meta?.let { m ->
                    val t = s?.toString()?.trim().orEmpty()
                    if (t != m.title) {
                        m.title = t
                        scheduleSave()
                    }
                }
            }
        })
    }

    /* ----------------------------- chargement ----------------------------- */

    private fun load() {
        io.execute {
            val m = repo.getMeta(noteId)
            if (m == null) {
                main.post { finish() }
                return@execute
            }
            meta = m
            // toutes les pages sont chargées d'un coup (document continu)
            val lists = ArrayList<MutableList<Stroke>>(m.pageCount)
            for (i in 0 until m.pageCount) {
                val l = repo.loadPage(m.id, i)
                lists.add(l)
                pages[i] = l
            }
            AppLog.log("editor", "note introuvable ou corrompue ($noteId) → fermeture")
            main.post {
                binding.titleInput.setText(m.title)
                binding.ink.setDocument(lists)
                binding.ink.scrollToPage(0, smooth = false)
                updatePageUi()
                updateHistoryButtons()
            }
        }
    }

    /* ------------------------------- pages -------------------------------- */

    private fun updatePageUi() {
        val m = meta ?: return
        binding.pageLabel.text = getString(R.string.page_of, pageIndex + 1, m.pageCount)
        binding.btnPrevPage.isEnabled = pageIndex > 0
        binding.btnPrevPage.alpha = if (pageIndex > 0) 1f else 0.35f
        binding.btnNextPage.isEnabled = pageIndex < m.pageCount - 1
        binding.btnNextPage.alpha = if (pageIndex < m.pageCount - 1) 1f else 0.35f
    }

    private fun addPage() {
        val m = meta ?: return
        flushSave()
        m.pageCount += 1
        val list = mutableListOf<Stroke>()
        pages[m.pageCount - 1] = list
        binding.ink.appendPage(list)
        binding.ink.scrollToPage(m.pageCount - 1)
        updatePageUi()
        updateHistoryButtons()
        io.execute {
            repo.savePage(m.id, m.pageCount - 1, list)
            m.updatedAt = System.currentTimeMillis()
            repo.saveMeta(m)
        }
    }

    /* ----------------------------- historique ----------------------------- */

    private fun history(page: Int): HistoryStack =
        histories.getOrPut(page) {
            HistoryStack().also { it.onChanged = { updateHistoryButtonsSafe() } }
        }

    private fun updateHistoryButtonsSafe() {
        main.post { updateHistoryButtons() }
    }

    private fun updateHistoryButtons() {
        val h = histories[pageIndex]
        binding.btnUndo.isEnabled = h?.canUndo == true
        binding.btnUndo.alpha = if (binding.btnUndo.isEnabled) 1f else 0.35f
        binding.btnRedo.isEnabled = h?.canRedo == true
        binding.btnRedo.alpha = if (binding.btnRedo.isEnabled) 1f else 0.35f
    }

    private fun applyHistoryOp(redo: Boolean) {
        val op = if (redo) history(pageIndex).redoOp() else history(pageIndex).undoOp()
        if (op == null) {
            updateHistoryButtons()
            return
        }
        val arr = pages[pageIndex] ?: return
        when (op) {
            is InkOp.Add -> {
                if (redo) {
                    if (!arr.contains(op.stroke)) arr.add(op.stroke)
                } else {
                    val i = arr.indexOf(op.stroke)
                    if (i >= 0) arr.removeAt(i)
                }
            }
            is InkOp.Erase -> {
                if (redo) {
                    for (hit in op.items) {
                        val i = arr.indexOf(hit.stroke)
                        if (i >= 0) arr.removeAt(i)
                    }
                } else {
                    for (hit in op.items.sortedBy { it.index }) {
                        val at = if (hit.index > arr.size) arr.size else hit.index
                        arr.add(at, hit.stroke)
                    }
                }
            }
        }
        binding.ink.invalidateCache()
        scheduleSave()
    }

    /* ------------------------------ outils -------------------------------- */

    private fun selectSize(i: Int) {
        selectedSize = i
        binding.ink.penSize = penSizes[i]
        if (binding.ink.tool == InkCanvasView.Tool.ERASER) {
            binding.ink.tool = InkCanvasView.Tool.PEN
        }
        updateToolUi()
    }

    private fun updateToolUi() {
        val eraserActive = binding.ink.tool == InkCanvasView.Tool.ERASER
        binding.btnEraser.imageTintList =
            ColorStateList.valueOf(if (eraserActive) accent else iconDark)
        binding.btnEraser.backgroundTintList =
            ColorStateList.valueOf(if (eraserActive) accentSoft else Color.TRANSPARENT)
        sizeButtons.forEach { it.imageTintList = ColorStateList.valueOf(iconDark) }
        if (!eraserActive) {
            sizeButtons[selectedSize].imageTintList = ColorStateList.valueOf(accent)
        }
    }

    /* ---------------------------- sauvegarde ------------------------------ */

    private fun setSaveState(resId: Int) {
        binding.saveState.text = getString(resId)
        binding.saveState.visibility = TextView.VISIBLE
    }

    private fun scheduleSave() {
        if (closed) return
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        val r = Runnable { flushSave() }
        saveRunnable = r
        saveHandler.postDelayed(r, 350)
    }

    private fun flushSave() {
        val m = meta ?: return
        val list = pages[pageIndex] ?: return
        setSaveState(R.string.saving)
        val idx = pageIndex
        io.execute {
            repo.savePage(m.id, idx, list)
            m.updatedAt = System.currentTimeMillis()
            repo.saveMeta(m)
            main.post {
                if (!closed) setSaveState(R.string.saved)
            }
        }
    }

    private fun closeNote() {
        if (closed) return
        closed = true
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        meta?.let { m ->
            pages[pageIndex]?.let { list ->
                val idx = pageIndex
                io.execute {
                    repo.savePage(m.id, idx, list)
                    m.updatedAt = System.currentTimeMillis()
                    repo.saveMeta(m)
                }
            }
        }
        finish()
    }

    /* ---------------------------- cycle de vie ---------------------------- */

    override fun onPause() {
        super.onPause()
        if (!closed) {
            saveRunnable?.let { saveHandler.removeCallbacks(it) }
            flushSave()
        }
    }

    override fun onDestroy() {
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}