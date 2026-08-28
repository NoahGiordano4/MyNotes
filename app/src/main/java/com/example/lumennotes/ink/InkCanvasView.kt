package com.example.lumennotes.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.widget.OverScroller
import com.example.lumennotes.data.EraseHit
import com.example.lumennotes.data.Stroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap

/**
 * Moteur d'encrage LumenNotes — v2.
 *
 *  - document MULTI-PAGES vertical : pages A4 empilées, scroll avec inertie,
 *    traits stockés en coordonnées LOCALES à chaque page ;
 *  - encre rendue PAR PAGE sur un thread d'arrière-plan (PageCache) avec
 *    aperçu flou progressif : le thread UI ne peint jamais l'encre finie,
 *    il projette des bitmaps déjà peints ;
 *  - écriture : timestamps par point (reconnaissance future), rendu
 *    INCRÉMENTAL du trait vivant — les points stables sont cuits sur un
 *    calque, seule la queue est redessinée → coût par frame constant,
 *    même en gribouillage marathon ;
 *  - événements historiques : aucune interposition du stylet n'est perdue ;
 *  - pression réelle du stylet (simulée par la vitesse pour le doigt) ;
 *  - gestes : pincement 2 doigts = zoom + déplacement ; 1 doigt = déplacement
 *    en mode stylet ; stylet prioritaire (rejet de paume) ;
 *  - S-Pen : bouton enfoncé = gomme temporaire, contact requis ;
 *  - zoom 100 % → 800 % ; page centrée dès qu'elle tient dans l'écran,
 *    bornage CONTINU sur les deux axes (aucun « cran ») ;
 *  - pages sans ombre : rien que du blanc.
 *
 * Coordonnées « monde » = document complet (pages + gouttières) :
 * page i occupe y [i*(PAGE_H+GAP), …+PAGE_H], x ∈ [0, PAGE_W].
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Host {
        /** "stylus" (stylet uniquement) ou "finger" (stylet + doigt). */
        fun inputMode(): String
        fun onStrokeCommitted(stroke: Stroke, pageIndex: Int)
        fun onErase(items: List<EraseHit>, pageIndex: Int)
        fun onZoomChanged(scale: Float)
        /** La page la plus visible a changé (après scroll/zoom). */
        fun onPageChanged(pageIndex: Int)
    }

    var host: Host? = null

    enum class Tool { PEN, ERASER }

    companion object {
        const val PAGE_W = 794f
        const val PAGE_H = 1123f
        const val GAP = 40f               // gouttière entre deux pages (unités monde)
        const val INK_COLOR: Int = 0xFF000000.toInt()

        private const val BG_COLOR: Int = 0xFFE8ECF1.toInt()
        private const val MIN_SCALE = 1f          // zoom minimal : 100 %
        private const val MAX_SCALE = 8f
        private const val STREAMLINE = 1f      // Augmenté pour réduire le lag (suivi du stylet)
        private const val PRESSURE_SMOOTH = 0.45f
        private const val SIM_P_MAX = 0.72f
        private const val SIM_P_MIN = 0.28f
        private const val MIN_WORLD_STEP = 0.3f
        private const val OVERLAY_BUDGET = 12_000_000f
        private const val OVERLAY_BAKE_STEP = 384  // points cuits par étape sur le calque
        private const val LIVE_TAIL_OVERLAP = 4   // recouvrement queue/calque
        private const val MARATHON_SPLIT = 4_000  // scission invisible des traits très longs
        private const val ERASE_RERENDER_MS = 64L // Délai réduit pour plus de réactivité sans flash

        private const val TYPE_TOUCH = 0
        private const val TYPE_STYLUS = 1
        private const val TYPE_STYLUS_ERASER = 2
        private const val TYPE_MOUSE = 3

    }

    // ------------------------------- état -----------------------------------

    var tool: Tool = Tool.PEN
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var penSize = 3.4f
        set(value) {
            field = if (value < 0.5f) 0.5f else value
        }

    /** Listes de traits par page (coordonnées locales à chaque page). */
    private var pageLists: ArrayList<MutableList<Stroke>>? = null

    private var ready = false

    private val density = resources.displayMetrics.density
    private val panMargin = 72f * density
    private val eraseRadiusPx = 22f * density

    // vue (écran = monde * scale + (tx, ty))
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var sized = false

    /** Page la plus visible (pour l'indicateur et les boutons ‹ ›). */
    private var currentPage = 0

    private var hoverPos: FloatArray? = null

    /** Cache d'encre par page, rendu en arrière-plan. */
    private val pageCache = PageCache(PAGE_W, PAGE_H)

    init {
        pageCache.onPageUpdated = { postInvalidateOnAnimation() }
    }

    // calque du trait vivant (partie stable cuite en bitmap)
    private var overlay: Bitmap? = null
    private var overlayCanvas = Canvas()
    private var overlayRes = 0f
    private var overlayPage = -1
    private var overlayCount = 0
    private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // rendu
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x8C0F172A.toInt()
    }

    // pointeurs / machine à états
    private enum class Mode { NONE, DRAW, ERASE, PAN, PINCH }

    private class Ptr(var x: Float, var y: Float, val type: Int)

    private val pointers = HashMap<Int, Ptr>()
    private var mode = Mode.NONE
    private var live: Live? = null
    private var erasePointerId = -1
    private var eraseType = TYPE_TOUCH
    private var erasePos: FloatArray? = null
    private var erasePage = 0
    private val eraseHits = ArrayList<EraseHit>()
    private var lastEraseRender = 0L
    private var gesture: Gesture? = null
    private var stylusButtonHeld = false
    private val bboxCache = HashMap<Stroke, FloatArray>()

    // inertie du scroll
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var flingActive = false

    private val settleHandler = Handler(Looper.getMainLooper())
    private var settleRunnable: Runnable? = null
    private var lastSettleScale = 1f
    private var lastReportedZoom = -1f
    private var scrollAnim: android.animation.ValueAnimator? = null

    private class Live(
        val pointerId: Int,
        val type: Int,
        val color: Int,
        val size: Float,
        val page: Int,
        val pts: FloatBuf,
        val times: LongBuf?,
        var lastSX: Float,
        var lastSY: Float,
        var lastWX: Float,
        var lastWY: Float,
        var lastT: Long,
        var simP: Float
    )

    private class Gesture(
        val ids: ArrayList<Int>,
        var type: Mode,
        var startDist: Float = 1f,
        var startMX: Float = 0f,
        var startMY: Float = 0f,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var v0Scale: Float = 1f,
        var v0X: Float = 0f,
        var v0Y: Float = 0f
    )

    /** Buffer de floats extensible (points x, y, pression). */
    class FloatBuf(initialPoints: Int = 256) {
        private var a = FloatArray(initialPoints * 3)
        var size = 0
            private set

        val count: Int get() = size / 3

        fun add(x: Float, y: Float, p: Float) {
            if (size + 3 > a.size) a = a.copyOf(a.size * 2)
            a[size] = x
            a[size + 1] = y
            a[size + 2] = p
            size += 3
        }

        fun x(i: Int) = a[i * 3]
        fun y(i: Int) = a[i * 3 + 1]
        fun p(i: Int) = a[i * 3 + 2]

        fun toArray(): FloatArray = a.copyOf(size)

        /** Tableau interne SANS copie — lecture seule, pour le rendu. */
        fun raw(): FloatArray = a
    }

    /** Buffer de longs extensible (timestamps par point). */
    class LongBuf(initialPoints: Int = 256) {
        private var a = LongArray(initialPoints)
        var size = 0
            private set

        val count: Int get() = size

        fun add(t: Long) {
            if (size >= a.size) a = a.copyOf(a.size * 2)
            a[size] = t
            size += 1
        }

        fun t(i: Int) = a[i]

        fun toArray(): LongArray = a.copyOf(size)
    }

    // --------------------------- géométrie doc -------------------------------

    val pageCount: Int get() = pageLists?.size ?: 0

    private fun docHeight(): Float =
        if (pageCount == 0) PAGE_H else pageCount * PAGE_H + (pageCount - 1) * GAP

    fun pageTop(i: Int): Float = i * (PAGE_H + GAP)

    /** Page contenant une coordonnée monde y (bornée au document). */
    private fun pageOfY(wy: Float): Int =
        (wy / (PAGE_H + GAP)).toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    // ---------------------------- API publique ------------------------------

    /** Charge tout le document (une liste de traits par page). */
    fun setDocument(lists: List<MutableList<Stroke>>) {
        pageLists = ArrayList(lists)
        bboxCache.clear()
        pageCache.setDocument(pageLists)
        ready = true
        if (!sized && width > 0 && height > 0) fitPage()
        notifyPageIfChanged(force = true)
        postInvalidateOnAnimation()
    }

    /** Ajoute une page vide à la fin du document. */
    fun appendPage(list: MutableList<Stroke>) {
        pageLists?.add(list)
        pageCache.appendPageList(list)   // sans jeter les bitmaps existants
        postInvalidateOnAnimation()
    }

    /** Cuit un trait tout juste engagé sur le bitmap de sa page. */
    fun appendStroke(stroke: Stroke, pageIndex: Int) {
        pageCache.bakeStroke(pageIndex, stroke)
        pageCache.invalidatePreview(pageIndex)  // l'aperçu doit montrer le trait aussi
        postInvalidateOnAnimation()
    }

    fun invalidateCache() {
        pageCache.invalidateAll()
        postInvalidateOnAnimation()
    }

    /** Ajuste le zoom pour voir une page entière, centrée sur la page courante. */
    fun fitPage() {
        if (width <= 0 || height <= 0) return
        val s = min((width - 28f * density) / PAGE_W, (height - 28f * density) / PAGE_H)
            .coerceIn(minScale(), MAX_SCALE)
        stopScrolling()
        scale = s
        tx = (width - PAGE_W * s) / 2f
        ty = height / 2f - (pageTop(currentPage) + PAGE_H / 2f) * s
        afterZoom()
    }

    fun currentScale(): Float = scale

    fun currentPageIndex(): Int = currentPage

    /** Centre la page demandée (avec animation douce par défaut). */
    fun scrollToPage(index: Int, smooth: Boolean = true) {
        if (pageCount == 0) return
        val i = index.coerceIn(0, pageCount - 1)
        val targetTx = (width - PAGE_W * scale) / 2f
        val targetTy = height / 2f - (pageTop(i) + PAGE_H / 2f) * scale
        stopScrolling()
        if (!smooth) {
            tx = targetTx
            ty = targetTy
            afterZoom()
            return
        }
        val startTx = tx
        val startTy = ty
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 320
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val f = a.animatedValue as Float
            tx = startTx + (targetTx - startTx) * f
            ty = startTy + (targetTy - startTy) * f
            clampPan()
            postInvalidateOnAnimation()
            notifyPageIfChanged()
        }
        scrollAnim = anim
        anim.start()
    }

    private fun stopScrolling() {
        scrollAnim?.cancel()
        scrollAnim = null
        scroller.abortAnimation()
        flingActive = false
    }

    override fun onDetachedFromWindow() {
        pageCache.destroy()
        super.onDetachedFromWindow()
    }

    // ------------------------------ rendu -----------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!sized) {
            sized = true
            if (ready) fitPage()
        } else {
            val cx = (oldw / 2f - tx) / scale
            val cy = (oldh / 2f - ty) / scale
            tx = w / 2f - cx * scale
            ty = h / 2f - cy * scale
            clampPan()
            pageCache.invalidateAll()
            postInvalidateOnAnimation()
        }
    }

    /** Zoom minimal : 100 %. */
    private fun minScale(): Float = MIN_SCALE

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BG_COLOR)

        val lists = pageLists ?: return
        val p0 = pageOfY(toWorldY(0f))
        val p1 = pageOfY(toWorldY(height.toFloat()))

        // pages blanches, sans aucune ombre
        fillPaint.color = Color.WHITE
        for (p in p0..p1) {
            val top = ty + pageTop(p) * scale
            val bottom = top + PAGE_H * scale
            if (bottom < 0f || top > height) continue
            canvas.drawRect(tx, top, tx + PAGE_W * scale, bottom, fillPaint)
        }

        // encre : aperçu de page entière en couche du bas, rendu net dessus
        val res = scale * density
        val visL = toWorldX(0f)
        val visR = toWorldX(width.toFloat())
        val visT = toWorldY(0f)
        val visB = toWorldY(height.toFloat())
        val first = max(0, p0 - 1)
        val last = min(lists.size - 1, p1 + 1)
        for (p in first..last) {
            val topW = pageTop(p)
            val topS = ty + topW * scale
            val fullE = pageCache.fullEntryFor(p)
            val prevE = pageCache.previewFor(p)

            val vr = RectF(
                visL.coerceIn(0f, PAGE_W),
                (visT - topW).coerceIn(0f, PAGE_H),
                visR.coerceIn(0f, PAGE_W),
                (visB - topW).coerceIn(0f, PAGE_H)
            )

            // OPTIMISATION : On ne dessine l'aperçu QUE si on n'a pas de rendu net 
            // couvrant déjà la zone visible, pour éviter le halo "sous-couche".
            val hasSharpCover = fullE != null && (fullE.rect == null || fullE.rect.contains(vr))
            
            if (prevE != null && !hasSharpCover) {
                canvas.drawBitmap(
                    prevE.bitmap, null,
                    RectF(tx, topS, tx + PAGE_W * scale, topS + PAGE_H * scale),
                    cachePaint
                )
            }
            if (fullE != null) {
                // Désactivation du filtrage si on est proche de la résolution native pour une netteté max
                cachePaint.isFilterBitmap = abs(fullE.res - res) > 0.05f
                if (fullE.rect == null) {
                    canvas.drawBitmap(
                        fullE.bitmap, null,
                        RectF(tx, topS, tx + PAGE_W * scale, topS + PAGE_H * scale),
                        cachePaint
                    )
                } else {
                    val r = fullE.rect
                    canvas.drawBitmap(
                        fullE.bitmap, null,
                        RectF(
                            tx + r.left * scale, topS + r.top * scale,
                            tx + r.right * scale, topS + r.bottom * scale
                        ),
                        cachePaint
                    )
                }
            }
            val inView = p >= p0 && p <= p1
            pageCache.request(p, res, urgent = (p == currentPage), rect = vr, withFull = inView)
        }

        // trait vivant : calque cuit + queue redessinée
        val lv = live
        if (lv != null && lv.pts.count > 0) {
            drawLive(canvas, lv)
        }

        // curseur de la gomme (stylet / souris uniquement)
        val currentPos = if (mode == Mode.ERASE) erasePos else hoverPos
        val isEraserActive = (tool == Tool.ERASER && mode == Mode.NONE) || (mode == Mode.ERASE && eraseType != TYPE_TOUCH)
        val showHoverCursor = stylusButtonHeld && mode == Mode.NONE
        
        if ((isEraserActive || showHoverCursor) && currentPos != null) {
            eraserCursorPaint.strokeWidth = 1.5f * density
            canvas.drawCircle(currentPos[0], currentPos[1], eraseRadiusPx, eraserCursorPaint)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val type = event.getToolType(0)
        if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_MOUSE) {
            hoverPos = null
            return super.onHoverEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverPos = floatArrayOf(event.x, event.y)
                stylusButtonHeld = stylusButtonsHeld(event)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverPos = null
                stylusButtonHeld = false
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    /** Rendu incrémental du trait vivant : cuire la partie stable, dessiner la queue. */
    private fun drawLive(canvas: Canvas, lv: Live) {
        val res = min(scale * density, sqrt(OVERLAY_BUDGET / (PAGE_W * PAGE_H)))
        if (overlayRes != res || overlayPage != lv.page || overlay == null) {
            val w = max(1, ceil(PAGE_W * res).toInt())
            val h = max(1, ceil(PAGE_H * res).toInt())
            val b = createBitmap(w, h)
            overlay = b
            overlayCanvas.setBitmap(b)
            overlayRes = res
            overlayPage = lv.page
            overlayCount = 0
        }

        // cuire la partie stable par étapes
        if (lv.pts.count - overlayCount >= OVERLAY_BAKE_STEP) {
            val bakeTo = lv.pts.count - 8
            val from = max(0, overlayCount - 2)
            overlayCanvas.save()
            overlayCanvas.scale(overlayRes, overlayRes)
            overlayCanvas.translate(0f, -pageTop(lv.page))
            StrokePainter.paintPoints(
                overlayCanvas, strokePaint, lv.color, lv.size,
                lv.pts.raw(), bakeTo, from
            )
            overlayCanvas.restore()
            overlayCount = bakeTo
        }

        // calque projeté sur la page
        val ov = overlay
        if (ov != null) {
            val top = ty + pageTop(lv.page) * scale
            // Pixel-snap pour éviter le flou sur le calque vivant
            cachePaint.isFilterBitmap = false
            canvas.drawBitmap(
                ov, null,
                RectF(tx, top, tx + PAGE_W * scale, top + PAGE_H * scale),
                cachePaint
            )
        }

        // queue du trait (fenêtre vivante)
        val tailFrom = max(0, overlayCount - LIVE_TAIL_OVERLAP)
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.translate(0f, -pageTop(lv.page))
        StrokePainter.paintPoints(
            canvas, strokePaint, lv.color, lv.size,
            lv.pts.raw(), lv.pts.count, tailFrom
        )
        canvas.restore()
    }

    private fun overlayReset() {
        overlay?.eraseColor(Color.TRANSPARENT)
        overlayCount = 0
    }

    /** Pilote l'inertie du scroll. */
    override fun computeScroll() {
        if (flingActive && scroller.computeScrollOffset()) {
            tx = -scroller.currX.toFloat()
            ty = -scroller.currY.toFloat()
            clampPan()
            notifyPageIfChanged()
            postInvalidateOnAnimation()
        } else if (flingActive) {
            flingActive = false
        }
        super.computeScroll()
    }

    // ------------------------------ zoom/pan --------------------------------

    /**
     * Bornage CONTINU sur les DEUX axes :
     *  - la page / le document reste centré dès qu'il tient dans l'écran ;
     *  - sinon il couvre toujours l'axe, l'excédent déborde de chaque côté.
     * Sur chaque axe, l'intervalle autorisé se réduit exactement à un point
     * au seuil où l'axe devient rempli → transition sans aucun « cran ».
     */
    private fun clampPan() {
        val w = PAGE_W * scale
        val h = docHeight() * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
    }

    private fun afterZoom() {
        clampPan()
        postInvalidateOnAnimation()
        // la puce ne change que si le zoom a VRAIMENT bougé
        if (abs(scale - lastReportedZoom) > 0.005f) {
            lastReportedZoom = scale
            host?.onZoomChanged(scale)
        }
        notifyPageIfChanged()
    }

    private fun notifyPageIfChanged(force: Boolean = false) {
        if (pageCount == 0) return
        val wy = toWorldY(height / 2f)
        val p = pageOfY(wy)
        if (p != currentPage || force) {
            currentPage = p
            host?.onPageChanged(p)
        }
    }

    private fun toWorldX(sx: Float) = (sx - tx) / scale
    private fun toWorldY(sy: Float) = (sy - ty) / scale

    // ------------------------------ entrées ---------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> pointerAdded(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> pointerMove(event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> pointerRemoved(event, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> resetInput()
        }
        return true
    }

    private fun pointerAdded(event: MotionEvent, index: Int) {
        if (!ready) return
        stopScrolling() // toute entrée interrompt l'inertie
        val id = event.getPointerId(index)
        val sx = event.getX(index)
        val sy = event.getY(index)
        val type = typeOf(event.getToolType(index))
        pointers[id] = Ptr(sx, sy, type)

        if (type == TYPE_STYLUS || type == TYPE_STYLUS_ERASER) {
            endGesture()
            if (mode == Mode.ERASE && erasePointerId != id) finishErase()
            val lv = live
            if (mode == Mode.DRAW && lv != null && !isStylus(lv.type)) discardLive()
            val btn = stylusButtonsHeld(event)
            stylusButtonHeld = btn
            val wantErase = btn || type == TYPE_STYLUS_ERASER || tool == Tool.ERASER
            if (wantErase) startErase(id, type, sx, sy)
            else startDraw(id, type, sx, sy, event.getPressure(index), event.eventTime)
            return
        }

        if (type == TYPE_TOUCH) {
            if (mode == Mode.DRAW && live?.let { isStylus(it.type) } == true) return
            if (mode == Mode.ERASE && isStylus(eraseType)) return

            val touchCount = pointers.values.count { it.type == TYPE_TOUCH }
            val stylusOnly = host?.inputMode() != "finger"
            if (stylusOnly || touchCount >= 2) {
                if (mode == Mode.DRAW) discardLive()
                if (mode == Mode.ERASE) finishErase()
                startGesture()
                if (mode == Mode.PAN && velocity == null) {
                    velocity = VelocityTracker.obtain()
                    velocity?.addMovement(event)
                }
            } else {
                if (tool == Tool.ERASER) startErase(id, type, sx, sy)
                else startDraw(id, type, sx, sy, event.getPressure(index), event.eventTime)
            }
            return
        }

        if (tool == Tool.ERASER) startErase(id, type, sx, sy)
        else startDraw(id, type, sx, sy, event.getPressure(index), event.eventTime)
    }

    private fun pointerMove(event: MotionEvent) {
        if (pointers.isEmpty()) return
        handleStylusButtonChange(event)
        if (mode == Mode.PAN || mode == Mode.PINCH) velocity?.addMovement(event)
        val ids = ArrayList(pointers.keys)

        for (h in 0 until event.historySize) {
            val ht = event.getHistoricalEventTime(h)
            for (id in ids) {
                val idx = event.findPointerIndex(id)
                if (idx < 0) continue
                routeMove(
                    id,
                    event.getHistoricalX(idx, h),
                    event.getHistoricalY(idx, h),
                    event.getHistoricalPressure(idx, h),
                    ht
                )
            }
        }
        val now = event.eventTime
        for (id in ids) {
            val idx = event.findPointerIndex(id)
            if (idx < 0) continue
            routeMove(id, event.getX(idx), event.getY(idx), event.getPressure(idx), now)
        }
        postInvalidateOnAnimation()
    }

    private fun routeMove(id: Int, sx: Float, sy: Float, pressure: Float, t: Long) {
        val rec = pointers[id] ?: return
        rec.x = sx
        rec.y = sy

        val lv = live
        when (mode) {
            Mode.DRAW if lv != null && lv.pointerId == id -> addLivePoint(lv, sx, sy, pressure, t)
            Mode.ERASE if id == erasePointerId -> eraseAt(sx, sy)
            Mode.PAN, Mode.PINCH -> applyGesture()
            else -> {}
        }
    }

    private fun pointerRemoved(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val wasGesture = mode == Mode.PAN || mode == Mode.PINCH
        val wasPan = mode == Mode.PAN
        pointers.remove(id)

        val lv = live
        if (mode == Mode.DRAW && lv != null && lv.pointerId == id) {
            stylusButtonHeld = false
            commitLive(lv)
        } else if (mode == Mode.ERASE && id == erasePointerId) {
            stylusButtonHeld = false
            finishErase()
        } else if (wasGesture) {
            val remaining = gesturePointers()
            val g = gesture
            if (remaining.isEmpty() || g == null) {
                if (wasPan) startFling(event)
                endGesture()
            } else {
                g.ids.clear()
                g.ids.addAll(remaining.map { it.first })
                initGestureFrame(g)
                mode = g.type
            }
        }
    }

    /** Lance l'inertie du scroll, bornée comme clampPan. */
    private fun startFling(event: MotionEvent) {
        val vt = velocity
        if (vt == null) return
        vt.addMovement(event)
        vt.computeCurrentVelocity(1000)
        val vx = vt.xVelocity.coerceIn(-8000f, 8000f)
        val vy = vt.yVelocity.coerceIn(-8000f, 8000f)
        vt.recycle()
        velocity = null
        if (hypot(vx, vy) < 400f) return

        val w = PAGE_W * scale
        val h = docHeight() * scale
        // bornes identiques à clampPan — l'inertie s'arrête où le bornage l'aurait fait
        val loX = if (w <= width) (width - w) / 2f else width - w
        val hiX = if (w <= width) loX else 0f
        val loY = if (h <= height) (height - h) / 2f else height - h
        val hiY = if (h <= height) loY else 0f
        // modèle scroll s = -t
        scroller.fling(
            (-tx).toInt(), (-ty).toInt(),
            (-vx).toInt(), (-vy).toInt(),
            (-hiX).toInt(), (-loX).toInt(),
            (-hiY).toInt(), (-loY).toInt()
        )
        flingActive = true
        postInvalidateOnAnimation()
    }

    private fun resetInput() {
        stylusButtonHeld = false
        discardLive()
        if (mode == Mode.ERASE) finishErase()
        endGesture()
        pointers.clear()
        velocity?.recycle()
        velocity = null
    }

    // ------------------------------ traits ----------------------------------

    private fun startDraw(id: Int, type: Int, sx: Float, sy: Float, pressure: Float, t: Long) {
        mode = Mode.DRAW
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        val p = if (type == TYPE_STYLUS && pressure > 0f) pressure.coerceIn(0.05f, 1f) else 0.5f
        // t > 0 = horloge réelle des événements ; sinon pas de timestamps
        val times = if (t > 0L) LongBuf().also { it.add(t) } else null
        live = Live(
            pointerId = id,
            type = type,
            color = INK_COLOR,
            size = penSize,
            page = pageOfY(wy),
            pts = FloatBuf().also { it.add(wx, wy, p) },
            times = times,
            lastSX = sx,
            lastSY = sy,
            lastWX = wx,
            lastWY = wy,
            lastT = System.nanoTime() / 1_000_000L,
            simP = p
        )
        postInvalidateOnAnimation()
    }

    private fun addLivePoint(lv: Live, sx: Float, sy: Float, rawPressure: Float, t: Long) {
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        if (lv.pts.count > 1 && hypot(wx - lv.lastWX, wy - lv.lastWY) < MIN_WORLD_STEP) return

        val d = hypot(sx - lv.lastSX, sy - lv.lastSY)
        val now = System.nanoTime() / 1_000_000L
        val dt = max(1L, now - lv.lastT)
        val pressure: Float = if (lv.type == TYPE_STYLUS && rawPressure > 0f) {
            lv.simP + (rawPressure.coerceIn(0f, 1f) - lv.simP) * PRESSURE_SMOOTH
        } else {
            val speed = d / dt
            val target = (SIM_P_MAX - speed * 0.18f * density).coerceIn(SIM_P_MIN, SIM_P_MAX)
            lv.simP + (target - lv.simP) * 0.18f
        }
        lv.simP = pressure
        lv.lastSX = sx
        lv.lastSY = sy
        lv.lastT = now

        val sxm = lv.lastWX + (wx - lv.lastWX) * STREAMLINE
        val sym = lv.lastWY + (wy - lv.lastWY) * STREAMLINE
        lv.lastWX = wx
        lv.lastWY = wy
        lv.pts.add(sxm, sym, pressure)
        lv.times?.add(t)

        // vanne « marathon » : scission invisible, le trait continue net
        if (lv.pts.count >= MARATHON_SPLIT) splitMarathon(lv)
    }

    /** Transforme le buffer vivant en Stroke engagé (ou null si vide). */
    private fun buildStroke(lv: Live): Stroke? {
        val n = lv.pts.count
        if (n == 0) return null
        val top = pageTop(lv.page)
        val arr: FloatArray
        val times: LongArray?
        if (n == 1) {
            arr = floatArrayOf(
                lv.pts.x(0), lv.pts.y(0) - top, lv.pts.p(0),
                lv.pts.x(0) + 0.01f, lv.pts.y(0) - top + 0.01f, lv.pts.p(0)
            )
            times = lv.times?.let { if (it.count == 1) longArrayOf(it.t(0), it.t(0)) else null }
        } else {
            val src = lv.pts.toArray()
            arr = FloatArray(src.size)
            var i = 1
            while (i < src.size) {
                arr[i - 1] = src[i - 1]
                arr[i] = src[i] - top
                arr[i + 1] = src[i + 1]
                i += 3
            }
            times = lv.times?.let { if (it.count == n) it.toArray() else null }
        }
        return Stroke(lv.color, lv.size, arr, times)
    }

    private fun commitLive(lv: Live) {
        live = null
        mode = Mode.NONE
        val stroke = buildStroke(lv) ?: return
        overlayReset()
        host?.onStrokeCommitted(stroke, lv.page)
    }

    /**
     * Scission marathon : engage le trait courant et poursuit l'écriture
     * dans un nouveau buffer, sans lever le stylet. Le calque conserve
     * l'encre déjà cuite — la transition est invisible.
     */
    private fun splitMarathon(lv: Live) {
        val stroke = buildStroke(lv) ?: return
        host?.onStrokeCommitted(stroke, lv.page)
        val last = lv.pts.count - 1
        val nx = lv.pts.x(last)
        val ny = lv.pts.y(last)
        val np = lv.pts.p(last)
        val nt = lv.times?.t(last) ?: 0L
        live = Live(
            pointerId = lv.pointerId,
            type = lv.type,
            color = lv.color,
            size = lv.size,
            page = lv.page,
            pts = FloatBuf().also { it.add(nx, ny, np) },
            times = if (nt > 0L) LongBuf().also { it.add(nt) } else null,
            lastSX = lv.lastSX,
            lastSY = lv.lastSY,
            lastWX = nx,
            lastWY = ny,
            lastT = lv.lastT,
            simP = lv.simP
        )
        // le calque garde ses pixels, le nouveau trait rebake par-dessus
        overlayCount = 0
    }

    private fun discardLive() {
        live = null
        if (mode == Mode.DRAW) mode = Mode.NONE
        overlayReset()
        postInvalidateOnAnimation()
    }

    // ------------------------------ gomme -----------------------------------

    private fun startErase(id: Int, type: Int, sx: Float, sy: Float) {
        mode = Mode.ERASE
        erasePointerId = id
        eraseType = type
        eraseHits.clear()
        erasePos = floatArrayOf(sx, sy)
        eraseAt(sx, sy)
    }

    private fun eraseAt(sx: Float, sy: Float) {
        erasePos = floatArrayOf(sx, sy)
        val lists = pageLists ?: return
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        val page = pageOfY(wy)
        val list = lists.getOrNull(page) ?: return
        val ly = wy - pageTop(page)
        val r = eraseRadiusPx / scale + 2f
        var removed = false
        for (i in list.indices.reversed()) {
            val s = list[i]
            if (strokeHit(s, wx, ly, r)) {
                list.removeAt(i)
                eraseHits.add(EraseHit(s, i))
                bboxCache.remove(s)
                removed = true
            }
        }
        erasePage = page
        if (removed) {
            // re-rendu throttlé pendant le geste, immédiat à la fin
            val now = System.currentTimeMillis()
            if (now - lastEraseRender >= ERASE_RERENDER_MS) {
                lastEraseRender = now
                pageCache.invalidatePage(page)
                pageCache.request(page, scale * density, urgent = true)
            }
            postInvalidateOnAnimation()
        }
    }

    private fun finishErase() {
        val hits = ArrayList(eraseHits)
        val page = erasePage
        eraseHits.clear()
        erasePointerId = -1
        eraseType = TYPE_TOUCH
        erasePos = null
        mode = Mode.NONE
        if (hits.isNotEmpty()) {
            lastEraseRender = 0L
            pageCache.invalidatePage(page)
            pageCache.request(page, scale * density, urgent = true)
            host?.onErase(hits, page)
        }
    }

    private fun strokeHit(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val box = bboxOf(s)
        if (x + r < box[0] || x - r > box[2] || y + r < box[1] || y - r > box[3]) return false
        val pts = s.points
        val n = pts.size / 3
        val rr = (r + s.size / 2f) * (r + s.size / 2f)
        var px = pts[0]
        var py = pts[1]
        if (n == 1) return (x - px) * (x - px) + (y - py) * (y - py) <= rr
        for (i in 1 until n) {
            val qx = pts[i * 3]
            val qy = pts[i * 3 + 1]
            val dx = qx - px
            val dy = qy - py
            val len2 = dx * dx + dy * dy
            var t = 0f
            if (len2 > 1e-6f) {
                t = (((x - px) * dx + (y - py) * dy) / len2).coerceIn(0f, 1f)
            }
            val ex = px + t * dx - x
            val ey = py + t * dy - y
            if (ex * ex + ey * ey <= rr) return true
            px = qx
            py = qy
        }
        return false
    }

    private fun bboxOf(s: Stroke): FloatArray {
        var box = bboxCache[s]
        if (box == null) {
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
            box = floatArrayOf(minX - m, minY - m, maxX + m, maxY + m)
            bboxCache[s] = box
        }
        return box
    }

    // ----------------------------- gestuelle --------------------------------

    private fun gesturePointers(): List<Pair<Int, Ptr>> {
        val touches = pointers.entries.filter { it.value.type == TYPE_TOUCH }
        if (touches.isNotEmpty()) {
            return touches.sortedBy { it.key }.take(2).map { it.key to it.value }
        }
        val mouse = pointers.entries.filter { it.value.type == TYPE_MOUSE }
        return if (mouse.isNotEmpty()) listOf(mouse[0].key to mouse[0].value) else emptyList()
    }

    private fun startGesture() {
        val pts = gesturePointers()
        if (pts.isEmpty()) {
            mode = Mode.NONE
            return
        }
        val g = Gesture(ArrayList(pts.map { it.first }), Mode.PAN)
        gesture = g
        initGestureFrame(g)
        mode = g.type
    }

    private fun initGestureFrame(g: Gesture) {
        val pts = g.ids.mapNotNull { id -> pointers[id]?.let { id to it } }
        if (pts.isEmpty()) return
        g.v0Scale = scale
        g.v0X = tx
        g.v0Y = ty
        if (pts.size >= 2) {
            g.type = Mode.PINCH
            val p1 = pts[0].second
            val p2 = pts[1].second
            g.startDist = max(1f, hypot(p2.x - p1.x, p2.y - p1.y))
            g.startMX = (p1.x + p2.x) / 2f
            g.startMY = (p1.y + p2.y) / 2f
        } else {
            g.type = Mode.PAN
            g.startX = pts[0].second.x
            g.startY = pts[0].second.y
        }
    }

    private fun applyGesture() {
        val g = gesture ?: return
        val pts = g.ids.mapNotNull { id -> pointers[id] }
        if (pts.isEmpty()) return
        if (g.type == Mode.PINCH && pts.size >= 2) {
            val d = max(1f, hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y))
            val mx = (pts[0].x + pts[1].x) / 2f
            val my = (pts[0].y + pts[1].y) / 2f
            val s = (g.v0Scale * (d / g.startDist)).coerceIn(minScale(), MAX_SCALE)
            val k = s / g.v0Scale
            scale = s
            tx = mx - (g.startMX - g.v0X) * k
            ty = my - (g.startMY - g.v0Y) * k
        } else {
            tx = g.v0X + (pts[0].x - g.startX)
            ty = g.v0Y + (pts[0].y - g.startY)
        }
        afterZoom()
    }

    private fun endGesture() {
        gesture = null
        if (mode == Mode.PAN || mode == Mode.PINCH) mode = Mode.NONE
    }

    // ------------------------------ helpers ---------------------------------

    private fun isStylus(type: Int): Boolean =
        type == TYPE_STYLUS || type == TYPE_STYLUS_ERASER

    private fun stylusButtonsHeld(event: MotionEvent): Boolean =
        (event.buttonState and
                (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0

    private fun handleStylusButtonChange(event: MotionEvent) {
        val held = stylusButtonsHeld(event)
        if (held == stylusButtonHeld) return
        stylusButtonHeld = held

        if (held) {
            val lv = live
            if (mode == Mode.DRAW && lv != null && isStylus(lv.type)) {
                val p = pointers[lv.pointerId] ?: return
                discardLive()
                startErase(lv.pointerId, TYPE_STYLUS, p.x, p.y)
            }
        }
    }

    private fun typeOf(toolType: Int): Int = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> TYPE_STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> TYPE_STYLUS_ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> TYPE_MOUSE
        else -> TYPE_TOUCH
    }
}