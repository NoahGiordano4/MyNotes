package com.example.lumennotes.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.example.lumennotes.data.Stroke
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cache d'encre PAR PAGE, rendu en thread d'arrière-plan — v2.1.
 *
 *  1. rendu en arrière-plan : le thread UI ne peint jamais l'encre finie ;
 *  2. deux niveaux par page : un APERÇU de page entière, basse résolution
 *     donc flou mais lisible, affiché immédiatement, et un rendu NET ;
 *  3. le rendu net est une PAGE ENTIÈRE tant que la résolution demandée
 *     tient dans le budget — sinon une TUILE couvrant la zone visible,
 *     à pleine résolution : à 800 % de zoom, l'encre visible est nette ;
 *  4. AUCUNE éviction au zoom : l'ancien bitmap reste affiché pendant que
 *     le nouveau se prépare — plus de « flash » — il est remplacé d'un
 *     bitmap à l'autre, sans frame vide ;
 *  5. pool de bitmaps + LRU plafonnés : mémoire bornée ;
 *  6. « bake » : cuire un trait engagé sur le bitmap existant de sa page.
 *
 * Sécurité des threads : instantanés superficiels des listes — les Stroke
 * engagés sont immuables — et époques par page pour jeter les rendus
 * périmés, par exemple après une gomme.
 */
class PageCache(
    private val pageW: Float,
    private val pageH: Float,
    private val pageBudgetPx: Float = 10_000_000f,
    private val tileBudgetPx: Float = 12_000_000f,
    private val fullCap: Int = 3,
    private val previewCap: Int = 5
) {

    /**
     * Une page rendue. `preview` = vrai si aperçu basse résolution.
     * `rect` = zone de page couverte par le bitmap, en coordonnées page —
     * null = page entière. Un bitmap de tuile ne couvre QUE sa zone : la
     * vue dessine l'aperçu dessous pour le reste de la page.
     */
    class Entry(
        val page: Int,
        val bitmap: Bitmap,
        val res: Float,
        val preview: Boolean,
        val rect: RectF?,
        val epoch: Long
    )

    /** Verrou exposé : la vue y synchronise ses mutations de listes. */
    val syncLock = Any()

    /** Appelé sur le thread UI quand une page vient d'être mise à jour. */
    var onPageUpdated: ((page: Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ink-cache").apply { start() }
    private val bg = Handler(thread.looper)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lists: MutableList<MutableList<Stroke>>? = null
    private var globalEpoch = 1L
    private val epochs = HashMap<Int, Long>()

    private val full = LinkedHashMap<Int, Entry>(8, 0.75f, true)
    private val previews = LinkedHashMap<Int, Entry>(8, 0.75f, true)
    private val pendingFull = HashSet<Int>()
    private val pendingPreview = HashSet<Int>()

    // derniers paramètres demandés par page — pour relancer un rendu
    // après un bake sans entrée, sans que la vue ait à répéter
    private val lastReqRes = HashMap<Int, Float>()
    private val lastReqRect = HashMap<Int, RectF?>()

    private val pool = ArrayList<Bitmap>()
    private val poolCap = 3

    /* --------------------------- document --------------------------- */

    /** Nouveau document : tout est jeté, les rendus en vol sont annulés. */
    fun setDocument(lists: MutableList<MutableList<Stroke>>?) {
        synchronized(syncLock) {
            this.lists = lists
            globalEpoch += 1
            epochs.clear()
            evictAll()
        }
    }

    /** Ajoute une page SANS jeter les bitmaps existants, ni flash. */
    fun appendPageList(list: MutableList<Stroke>) {
        synchronized(syncLock) { lists?.add(list) }
    }

    /* --------------------------- demandes --------------------------- */

    /**
     * Demande la page `page` à la résolution `res` — pixels par unité
     * monde — pour la zone visible `rect` en coordonnées page, null pour
     * la page entière. Appelable à chaque frame : les doublons sont
     * ignorés, et un rendu en cours n'est JAMAIS interrompu : l'ancien
     * bitmap reste affiché jusqu'au remplacement, sans frame vide.
     * `withFull = false` ne demande qu'un aperçu — pour les pages voisines
     * de la zone visible, qui n'ont pas besoin de rendu net tout de suite.
     */
    fun request(
        page: Int,
        res: Float,
        urgent: Boolean = false,
        rect: RectF? = null,
        withFull: Boolean = true
    ) {
        val visRect = rect ?: RectF(0f, 0f, pageW, pageH)
        val pageMax = sqrt(pageBudgetPx / (pageW * pageH))

        // cible : page entière tant que la résolution tient, sinon tuile
        // couvrant la zone visible — dans les DEUX cas à la résolution
        // demandée, l'écran : les budgets sont dimensionnés pour que le
        // min() ne mord qu'exceptionnellement, l'encre engagée doit être
        // AUSSI nette que le trait vivant vectoriel.
        val targetRect: RectF?
        val targetRes: Float
        if (res <= pageMax) {
            targetRect = null
            targetRes = res
        } else {
            val padX = visRect.width() * 0.35f + 8f
            val padY = visRect.height() * 0.35f + 8f
            val l = (visRect.left - padX).coerceAtLeast(0f)
            val t = (visRect.top - padY).coerceAtLeast(0f)
            val r = (visRect.right + padX).coerceAtMost(pageW)
            val b = (visRect.bottom + padY).coerceAtMost(pageH)
            targetRect = RectF(l, t, r, b)
            targetRes = min(res, sqrt(tileBudgetPx / (targetRect.width() * targetRect.height())))
        }
        val prevRes = min(res * 0.25f, pageMax)

        var wantFull = false
        var wantPreview = false
        synchronized(syncLock) {
            lastReqRes[page] = res
            lastReqRect[page] = RectF(visRect)
            val currentEpoch = epochs[page] ?: 0L
            
            if (withFull) {
                val f = full[page]
                val valid = f != null && f.epoch == currentEpoch && (
                        (targetRect == null && f.rect == null && f.res >= targetRes * 0.92f) ||
                                (targetRect != null && f.rect != null &&
                                        f.res >= targetRes * 0.92f && f.rect.contains(visRect))
                        )
                if (!valid && !pendingFull.contains(page)) {
                    pendingFull.add(page)
                    wantFull = true
                }
            }
            if (!pendingPreview.contains(page)) {
                val pEntry = previews[page]
                if (pEntry == null || pEntry.epoch != currentEpoch) {
                    pendingPreview.add(page)
                    wantPreview = true
                }
            }
        }
        if (!wantFull && !wantPreview) return
        if (urgent) {
            if (wantFull) postRender(page, targetRes, targetRect, preview = false, front = true)
            if (wantPreview) postRender(page, prevRes, null, preview = true, front = true)
        } else {
            if (wantPreview) postRender(page, prevRes, null, preview = true, front = false)
            if (wantFull) postRender(page, targetRes, targetRect, preview = false, front = false)
        }
    }

    /** Meilleure entrée disponible — net ou aperçu. */
    fun entryFor(page: Int): Entry? = synchronized(syncLock) {
        full[page] ?: previews[page]
    }

    /** Entrée nette uniquement — page entière ou tuile. */
    fun fullEntryFor(page: Int): Entry? = synchronized(syncLock) {
        full[page]
    }

    /** Aperçu de page entière — la couche « floue mais lisible ». */
    fun previewFor(page: Int): Entry? = synchronized(syncLock) {
        previews[page]
    }

    /**
     * Cuit un trait tout juste engagé sur le bitmap existant de sa page.
     * Le trait doit déjà être présent dans la liste de la page. S'il
     * n'existe pas encore d'entrée nette — rendu initial en vol — la page
     * est simplement re-demandée : le rendu qui arrive contiendra le trait.
     */
    fun bakeStroke(page: Int, stroke: Stroke) {
        val epoch = synchronized(syncLock) { epochs[page] }
        bg.post {
            val e: Entry? = synchronized(syncLock) {
                if (epochs[page] != epoch) null else full[page]
            }
            if (e == null) {
                reRequest(page)
                return@post
            }
            val bb = bboxOf(stroke)
            if (e.rect == null || RectF.intersects(e.rect, bb)) {
                val canvas = Canvas(e.bitmap)
                canvas.save()
                canvas.scale(e.res, e.res)
                if (e.rect != null) canvas.translate(-e.rect.left, -e.rect.top)
                StrokePainter.paintStroke(canvas, paint, stroke)
                canvas.restore()
            }
            notifyPage(page)
        }
    }

    /** Rafraîchit l'aperçu d'une page — après y avoir cuit un trait. */
    fun invalidatePreview(page: Int) {
        synchronized(syncLock) {
            previews.remove(page)?.let { poolAdd(it.bitmap) }
            pendingPreview.remove(page)
        }
    }

    /** Invalide une page : gomme, undo, redo… Son contenu a changé. */
    fun invalidatePage(page: Int) {
        synchronized(syncLock) {
            epochs[page] = ++globalEpoch
            // On ne supprime PAS les entrées des maps ici pour éviter le flash blanc.
            // Elles seront remplacées par les nouveaux rendus grâce au check de l'epoch dans request().
            pendingFull.remove(page)
            pendingPreview.remove(page)
        }
    }

    /** Invalide tout sans changer de document. */
    fun invalidateAll() {
        synchronized(syncLock) {
            globalEpoch += 1
            epochs.clear()
            evictAll()
        }
    }

    /** Libère le thread d'arrière-plan — au détachement de la vue. */
    fun destroy() {
        thread.quitSafely()
    }

    /* --------------------------- rendu BG --------------------------- */

    private fun reRequest(page: Int) {
        val res: Float
        val rect: RectF?
        synchronized(syncLock) {
            invalidatePage(page)
            res = lastReqRes[page] ?: return
            rect = lastReqRect[page]
        }
        request(page, res, urgent = true, rect = rect)
    }

    private fun postRender(page: Int, res: Float, rect: RectF?, preview: Boolean, front: Boolean) {
        val epoch = synchronized(syncLock) { epochs.getOrPut(page) { ++globalEpoch } }
        val area = rect ?: RectF(0f, 0f, pageW, pageH)
        val task = Runnable { render(page, res, area, preview, epoch) }
        if (front) bg.postAtFrontOfQueue(task) else bg.post(task)
    }

    private fun render(page: Int, res: Float, area: RectF, preview: Boolean, epoch: Long) {
        // instantané superficiel : les Stroke engagés sont immuables
        val snapshot: List<Stroke> = try {
            synchronized(syncLock) { lists?.getOrNull(page)?.toList() } ?: return
        } catch (_: Throwable) {
            synchronized(syncLock) {
                if (epochs[page] == epoch) {
                    if (preview) pendingPreview.remove(page) else pendingFull.remove(page)
                }
            }
            return
        }

        val w = maxOf(1, ceil(area.width() * res).toInt())
        val h = maxOf(1, ceil(area.height() * res).toInt())
        val bmp = synchronized(syncLock) { poolAcquire(w, h) }
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.scale(res, res)
        canvas.translate(-area.left, -area.top)
        for (s in snapshot) {
            if (!preview) {
                val bb = bboxOf(s)
                if (bb.right < area.left || bb.left > area.right ||
                    bb.bottom < area.top || bb.top > area.bottom
                ) continue
            }
            StrokePainter.paintStroke(canvas, paint, s)
        }
        canvas.restore()

        val entry = Entry(page, bmp, res, preview, if (preview) null else RectF(area), epoch)
        synchronized(syncLock) {
            if (epochs[page] != epoch) {
                poolAdd(bmp)
                return
            }
            val map = if (preview) previews else full
            val old = map.put(page, entry)
            old?.let { poolAdd(it.bitmap) }
            trim(map)
            if (preview) pendingPreview.remove(page) else pendingFull.remove(page)
        }
        notifyPage(page)
    }

    private fun notifyPage(page: Int) {
        main.post { onPageUpdated?.invoke(page) }
    }

    /* ---------------------------- pool/LRU --------------------------- */

    private fun bboxOf(s: Stroke): RectF {
        val pts = s.points
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < pts.size) {
            if (pts[i] < minX) minX = pts[i]
            if (pts[i] > maxX) maxX = pts[i]
            if (pts[i + 1] < minY) minY = pts[i + 1]
            if (pts[i + 1] > maxY) maxY = pts[i + 1]
            i += 3
        }
        val m = s.size
        return RectF(minX - m, minY - m, maxX + m, maxY + m)
    }

    private fun trim(map: LinkedHashMap<Int, Entry>) {
        val cap = if (map === full) fullCap else previewCap
        while (map.size > cap) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            val eldest = it.next()
            it.remove()
            poolAdd(eldest.value.bitmap)
        }
    }

    private fun evictAll() {
        val fIt = full.entries.iterator()
        while (fIt.hasNext()) {
            val e = fIt.next()
            fIt.remove()
            poolAdd(e.value.bitmap)
        }
        val pIt = previews.entries.iterator()
        while (pIt.hasNext()) {
            val e = pIt.next()
            pIt.remove()
            poolAdd(e.value.bitmap)
        }
        pendingFull.clear()
        pendingPreview.clear()
    }

    private fun poolAcquire(w: Int, h: Int): Bitmap {
        var i = 0
        while (i < pool.size) {
            val b = pool[i]
            if (b.width == w && b.height == h) {
                pool.removeAt(i)
                return b
            }
            i++
        }
        return createBitmap(w, h)
    }

    private fun poolAdd(b: Bitmap) {
        if (pool.size < poolCap) pool.add(b)
    }
}