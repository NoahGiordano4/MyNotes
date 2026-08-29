package com.example.lumennotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.example.lumennotes.data.*
import com.example.lumennotes.databinding.ActivityEditorBinding
import com.example.lumennotes.ink.*
import com.example.lumennotes.util.AppLog
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity(), InkCanvasView.Host {

    private lateinit var binding: ActivityEditorBinding
    private val repo by lazy { NoteRepository(this) }
    private val io = AppLog.loggedExecutor("editor-io")
    private val main = Handler(Looper.getMainLooper())

    private var noteId: String = ""
    private var meta: NoteMeta? = null
    private var pageIndex = 0
    private val pages = HashMap<Int, MutableList<Stroke>>()
    private val histories = HashMap<Int, HistoryStack>()

    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var closed = false
    private var selectionMenu: PopupMenu? = null

    private val spellCheckers = HashMap<String, SpellCheckManager>()
    private val hunspellCheckers = HashMap<String, HunspellSpellChecker>()
    private val recognitionHandler = Handler(Looper.getMainLooper())
    private var recognitionRunnable: Runnable? = null

    private val accent by lazy { getColor(R.color.primary) }
    private val accentSoft by lazy { getColor(R.color.accent_soft) }
    private val iconDark by lazy { getColor(R.color.icon_dark) }

    private var selectedSize = 1
    private val penSizes = floatArrayOf(2.2f, 3.4f, 5.4f)
    private lateinit var sizeButtons: List<ImageButton>

    override fun inputMode(): String = Prefs.inputMode(this)

    override fun onStrokeCommitted(stroke: Stroke, pageIndex: Int) {
        pages[pageIndex]?.add(stroke)
        binding.ink.appendStroke(stroke, pageIndex)
        history(pageIndex).push(InkOp.Add(stroke))
        scheduleSave()
        scheduleRecognition(pageIndex)
    }

    override fun onErase(items: List<EraseHit>, pageIndex: Int) {
        history(pageIndex).push(InkOp.Erase(items))
        binding.ink.invalidateCache()
        scheduleSave()
        scheduleRecognition(pageIndex)
    }

    override fun onZoomChanged(scale: Float) {
        binding.zoomChip.text = getString(R.string.zoom_percent, (scale * 100).roundToInt())
    }

    override fun onPageChanged(pageIndex: Int) {
        if (pageIndex == this.pageIndex) {
            updatePageUi()
            return
        }
        flushSave()
        this.pageIndex = pageIndex
        updatePageUi()
        updateHistoryButtons()
        updateLanguageUi()
    }

    override fun onSelectionChanged(selection: Selection?) {
        main.post {
            selectionMenu?.dismiss()
            if (selection != null) {
                showSelectionMenu(selection)
            }
        }
    }

    private fun showSelectionMenu(sel: Selection) {
        val menu = PopupMenu(this, binding.ink)
        menu.menu.add("Transcript").setOnMenuItemClickListener {
            transcribeSelection(sel)
            true
        }
        selectionMenu = menu
        menu.show()
    }

    private fun transcribeSelection(sel: Selection) {
        val lang = meta?.getLanguageForPage(sel.pageIndex) ?: "fr-FR"
        setSaveState(R.string.saving)
        HandwritingManager.transcribe(
            lang,
            sel.strokes,
            onResult = { text: String ->
                main.post {
                    if (text.isNotBlank()) {
                        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
                            .setAction("Copy") {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
                            }
                            .show()
                    } else {
                        Snackbar.make(binding.root, "Transcription vide", Snackbar.LENGTH_SHORT).show()
                    }
                    setSaveState(R.string.saved)
                }
            },
            onError = { e: Exception ->
                main.post {
                    Snackbar.make(binding.root, "Erreur: ${e.message}", Snackbar.LENGTH_SHORT).show()
                    setSaveState(R.string.saved)
                }
            }
        )
    }

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
        preloadRecognitionModel()   // ← NOUVEAU : modèle téléchargé dès l'ouverture
    }

    private fun preloadRecognitionModel() {
        val lang = meta?.getLanguageForPage(pageIndex) ?: "fr-FR"
        HandwritingManager.preload(
            lang,
            onReady = { AppLog.log("reco", "modèle $lang prêt pour la reco") },
            onError = { e ->
                AppLog.log("reco", "modèle $lang indisponible", e)
                Snackbar.make(
                    binding.root,
                    "Reconnaissance : échec du chargement du modèle (${e.message}). Connexion requise au 1er usage.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        )
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
            binding.ink.tool = if (binding.ink.tool == InkInputHandler.Tool.ERASER) InkInputHandler.Tool.PEN else InkInputHandler.Tool.ERASER
            updateToolUi()
        }

        binding.btnLasso.setOnClickListener {
            binding.ink.tool = if (binding.ink.tool == InkInputHandler.Tool.LASSO) InkInputHandler.Tool.PEN else InkInputHandler.Tool.LASSO
            updateToolUi()
        }

        binding.btnLanguage.setOnClickListener { showLanguageMenu() }

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

    private fun load() {
        io.execute {
            val m = repo.getMeta(noteId)
            if (m == null) {
                main.post { finish() }
                return@execute
            }
            meta = m
            val lists = ArrayList<MutableList<Stroke>>(m.pageCount)
            for (i in 0 until m.pageCount) {
                val l = repo.loadPage(m.id, i)
                lists.add(l)
                pages[i] = l
            }
            main.post {
                binding.titleInput.setText(m.title)
                binding.ink.setDocument(lists)
                binding.ink.scrollToPage(0, smooth = false)
                updatePageUi()
                updateHistoryButtons()
                updateLanguageUi()
                preloadRecognitionModel()   // ← NOUVEAU : langue réelle de la note
            }
        }
    }

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
        updateLanguageUi()
        io.execute {
            repo.savePage(m.id, m.pageCount - 1, list)
            m.updatedAt = System.currentTimeMillis()
            repo.saveMeta(m)
        }
    }

    private fun showLanguageMenu() {
        val menu = PopupMenu(this, binding.btnLanguage)
        menu.menu.add("Français").setOnMenuItemClickListener { setPageLanguage("fr-FR"); true }
        menu.menu.add("English").setOnMenuItemClickListener { setPageLanguage("en-US"); true }
        menu.menu.add("Español").setOnMenuItemClickListener { setPageLanguage("es-ES"); true }
        menu.show()
    }

    private fun setPageLanguage(lang: String) {
        meta?.setLanguageForPage(pageIndex, lang)
        updateLanguageUi()
        preloadRecognitionModel()   // ← NOUVEAU
        scheduleSave()
        scheduleRecognition(pageIndex)
    }

    private fun updateLanguageUi() {
        val lang = meta?.getLanguageForPage(pageIndex) ?: "fr-FR"
        binding.btnLanguage.text = lang.substring(0, 2).uppercase()
    }

    private fun scheduleRecognition(page: Int) {
        recognitionHandler.removeCallbacksAndMessages(null)
        recognitionRunnable = Runnable { runPageRecognition(page) }
        recognitionHandler.postDelayed(recognitionRunnable!!, 1200)
    }

    private fun runPageRecognition(page: Int) {
        val strokes = pages[page] ?: return
        if (strokes.isEmpty()) {
            binding.ink.clearPageSpellFeedback(page)
            return
        }

        val lang = meta?.getLanguageForPage(page) ?: "fr-FR"

        HandwritingManager.transcribe(lang, strokes, onResult = { fullText ->
            if (fullText.isBlank()) {
                AppLog.log("reco", "page $page : le texte reconnu est VIDE")
                main.post { binding.ink.clearPageSpellFeedback(page) }
                return@transcribe
            }
            AppLog.log("reco", "page $page reconnue : « $fullText »")

            runSpellCheck(page, lang, fullText, strokes)
        }, onError = { e ->
            AppLog.log("reco", "reconnaissance impossible (page $page)", e)
            main.post {
                Snackbar.make(
                    binding.root,
                    "Reco : ${e.message ?: "erreur du modèle"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        })
    }

    /**
     * Point d'entrée commun de la correction : privilégie le correcteur
     * Hunspell embarqué (hors ligne, dans l'APK), sinon retombe sur le
     * correcteur système Android.
     */
    private fun runSpellCheck(page: Int, lang: String, fullText: String, strokes: List<Stroke>) {
        val hunspell = hunspellCheckers.getOrPut(lang) { HunspellSpellChecker(this, lang) }
        if (hunspell.isAvailable) {
            val misspelled = hunspell.checkSpelling(fullText)
            applySpellFeedback(page, fullText, strokes, misspelled)
            return
        }
        val locale = Locale.forLanguageTag(lang)
        val spellChecker = spellCheckers.getOrPut(lang) {
            SpellCheckManager(this, locale) {
                // service de correction absent : on prévient UNE fois
                main.post {
                    Snackbar.make(
                        binding.root,
                        "Correcteur orthographique désactivé — aucun soulignement ne sera affiché",
                        Snackbar.LENGTH_LONG
                    )
                        .setAction("Activer") {
                            try {
                                startActivity(Intent("android.settings.TEXT_SERVICES_SETTINGS"))
                            } catch (e: Exception) {
                                AppLog.log("spell", "ouverture des réglages impossible", e)
                            }
                        }
                        .show()
                }
            }
        }
        spellChecker.checkSpelling(fullText) { applySpellFeedback(page, fullText, strokes, it) }
    }

    private fun applySpellFeedback(
        page: Int,
        fullText: String,
        strokes: List<Stroke>,
        misspelledWords: List<MisspelledWord>
    ) {
        main.post {
            val feedback = mutableListOf<InkCanvasView.SpellFeedback>()

            val words = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }
            // Regroupe spatialement les traits pour retrouver la position réelle
            // de chaque mot (plusieurs lignes possibles) sur la page.
            val wordBoxes = clusterWords(strokes)

            var currentSearchIndex = 0
            var wordIndex = 0

            for (wordText in words) {
                val offset = fullText.indexOf(wordText, currentSearchIndex)
                if (offset == -1) continue
                currentSearchIndex = offset + wordText.length

                val length = wordText.length
                val isError = misspelledWords.any {
                    (it.offset >= offset && it.offset < offset + length) ||
                            (offset >= it.offset && offset < it.offset + it.length)
                }

                // Sous le mot auquel ce mot reconnu correspond (par ordre de
                // lecture) : aura, ari est cette portion-là.
                val box = wordBoxes.getOrNull(wordIndex) ?: continue
                wordIndex++

                feedback.add(
                    InkCanvasView.SpellFeedback(
                        wordText,
                        box,
                        isError
                    )
                )
            }

            AppLog.log("spellcheck", "Page $page: ${feedback.size} mots placés, ${misspelledWords.size} erreurs")
            binding.ink.setPageSpellFeedback(page, feedback)
        }
    }

    /**
     * Regroupe les traits en mots (par proximité horizontale sur une même
     * ligne), triés dans l'ordre de lecture (ligne par ligne, puis gauche →
     * droite). Retourne la boîte englobante de chaque mot, en coordonnées page.
     * Exploité pour souligner chaque mot à sa place réelle (hauteur et largeur
     * propres), au lieu de tout étirer sur une ligne unique.
     */
    private fun clusterWords(strokes: List<Stroke>): List<RectF> {
        // Boîte englobante + centre vertical de chaque trait (un trait ≈ une lettre).
        data class E(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val midY: Float, val h: Float)
        val elems = strokes.mapNotNull { s ->
            var x1 = Float.MAX_VALUE; var y1 = Float.MAX_VALUE; var x2 = -Float.MAX_VALUE; var y2 = -Float.MAX_VALUE
            val pts = s.points
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i]; val y = pts[i + 1]
                if (x < x1) x1 = x
                if (y < y1) y1 = y
                if (x > x2) x2 = x
                if (y > y2) y2 = y
                i += 3
            }
            if (x2 <= x1 || y2 <= y1) null else E(x1, y1, x2, y2, (y1 + y2) / 2f, y2 - y1)
        }
        if (elems.isEmpty()) return emptyList()
        val medH = elems.map { it.h }.sorted()[elems.size / 2]

        // Tri lecture : lignes du haut vers le bas, puis gauche → droite.
        val sorted = elems.sortedWith(compareBy({ it.midY }, { it.x1 }))
        val clusters = mutableListOf<MutableList<E>>()
        var cur = mutableListOf<E>()
        var prev: E? = null
        for (e in sorted) {
            if (prev != null) {
                val lineJump = Math.abs(e.midY - prev.midY) >
                        Math.max(0.7f * Math.max(e.h, prev.h), 0.6f * medH)
                val bigGap = (e.x1 - prev.x2) > 0.8f * medH
                if (lineJump || bigGap) {
                    clusters.add(cur); cur = mutableListOf()
                }
            }
            cur.add(e)
            prev = e
        }
        clusters.add(cur)

        return clusters.map { cl ->
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (e in cl) {
                l = minOf(l, e.x1); t = minOf(t, e.y1)
                r = maxOf(r, e.x2); b = maxOf(b, e.y2)
            }
            RectF(l, t, r, b)
        }
    }

    private fun history(page: Int): HistoryStack = histories.getOrPut(page) { HistoryStack().also { it.onChanged = { main.post { updateHistoryButtons() } } } }

    private fun updateHistoryButtons() {
        val h = histories[pageIndex]
        binding.btnUndo.isEnabled = h?.canUndo == true
        binding.btnUndo.alpha = if (binding.btnUndo.isEnabled) 1f else 0.35f
        binding.btnRedo.isEnabled = h?.canRedo == true
        binding.btnRedo.alpha = if (binding.btnRedo.isEnabled) 1f else 0.35f
    }

    private fun applyHistoryOp(redo: Boolean) {
        val op = if (redo) history(pageIndex).redoOp() else history(pageIndex).undoOp()
        if (op == null) return
        val arr = pages[pageIndex] ?: return
        when (op) {
            is InkOp.Add -> if (redo) { if (!arr.contains(op.stroke)) arr.add(op.stroke) } else { arr.remove(op.stroke) }
            is InkOp.Erase -> if (redo) { op.items.forEach { arr.remove(it.stroke) } } else { op.items.sortedBy { it.index }.forEach { arr.add(if (it.index > arr.size) arr.size else it.index, it.stroke) } }
        }
        binding.ink.invalidateCache()
        scheduleSave()
        scheduleRecognition(pageIndex)
    }

    private fun selectSize(i: Int) {
        selectedSize = i
        binding.ink.penSize = penSizes[i]
        if (binding.ink.tool == InkInputHandler.Tool.ERASER || binding.ink.tool == InkInputHandler.Tool.LASSO) {
            binding.ink.tool = InkInputHandler.Tool.PEN
        }
        updateToolUi()
    }

    private fun updateToolUi() {
        val eraserActive = binding.ink.tool == InkInputHandler.Tool.ERASER
        val lassoActive = binding.ink.tool == InkInputHandler.Tool.LASSO
        binding.btnEraser.imageTintList = ColorStateList.valueOf(if (eraserActive) accent else iconDark)
        binding.btnEraser.backgroundTintList = ColorStateList.valueOf(if (eraserActive) accentSoft else Color.TRANSPARENT)
        binding.btnLasso.imageTintList = ColorStateList.valueOf(if (lassoActive) accent else iconDark)
        binding.btnLasso.backgroundTintList = ColorStateList.valueOf(if (lassoActive) accentSoft else Color.TRANSPARENT)
        sizeButtons.forEach { it.imageTintList = ColorStateList.valueOf(iconDark) }
        if (!eraserActive && !lassoActive) sizeButtons[selectedSize].imageTintList = ColorStateList.valueOf(accent)
    }

    private fun setSaveState(resId: Int) { binding.saveState.text = getString(resId); binding.saveState.visibility = TextView.VISIBLE }

    private fun scheduleSave() {
        if (closed) return
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        saveRunnable = Runnable { flushSave() }.also { saveHandler.postDelayed(it, 350) }
    }

    private fun flushSave() {
        val m = meta ?: return
        val list = pages[pageIndex] ?: return
        setSaveState(R.string.saving)
        val idx = pageIndex
        io.execute {
            repo.savePage(m.id, idx, list); m.updatedAt = System.currentTimeMillis(); repo.saveMeta(m)
            main.post { if (!closed) setSaveState(R.string.saved) }
        }
    }

    private fun closeNote() {
        if (closed) return
        closed = true
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        meta?.let { m -> pages[pageIndex]?.let { list -> val idx = pageIndex; io.execute { repo.savePage(m.id, idx, list); m.updatedAt = System.currentTimeMillis(); repo.saveMeta(m) } } }
        spellCheckers.values.forEach { it.close() }
        hunspellCheckers.values.forEach { it.close() }
        finish()
    }

    override fun onPause() { super.onPause(); if (!closed) { saveRunnable?.let { saveHandler.removeCallbacks(it) }; flushSave() } }
    override fun onDestroy() { saveRunnable?.let { saveHandler.removeCallbacks(it) }; super.onDestroy() }
}
