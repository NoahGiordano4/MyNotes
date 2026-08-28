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
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.example.lumennotes.data.Stroke
import com.example.lumennotes.data.EraseHit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Moteur d'encrage LumenNotes — document MULTI-PAGES vertical.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), InkInputHandler.Callback {

    interface Host {
        fun inputMode(): String
        fun onStrokeCommitted(stroke: Stroke, pageIndex: Int)
        fun onErase(items: List<EraseHit>, pageIndex: Int)
        fun onZoomChanged(scale: Float)
        fun onPageChanged(pageIndex: Int)
    }

    companion object {
        const val PAGE_W = 794f
        const val PAGE_H = 1123f
        const val GAP = 40f
        const val INK_COLOR: Int = 0xFF000000.toInt()

        private const val BG_COLOR: Int = 0xFFE8ECF1.toInt()
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 8f
        private const val OVERLAY_BUDGET = 12_000_000f
        private const val OVERLAY_BAKE_STEP = 384
        private const val LIVE_TAIL_OVERLAP = 4
    }

    // ------------------------------- état -----------------------------------

    private val inputHandler = InkInputHandler(context, this)

    override var host: Host? = null

    override var tool: InkInputHandler.Tool = InkInputHandler.Tool.PEN
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    override var penSize = 3.4f
        set(value) {
            field = if (value < 0.5f) 0.5f else value
        }

    private var pageLists: ArrayList<MutableList<Stroke>>? = null
    private var ready = false

    override val density = resources.displayMetrics.density
    override val eraseRadiusPx = 22f * density

    override var scale = 1f
    override var tx = 0f
    override var ty = 0f
    private var sized = false
    private var currentPage = 0

    private val pageCache = PageCache(PAGE_W, PAGE_H)

    init {
        pageCache.onPageUpdated = { postInvalidateOnAnimation() }
    }

    private var overlay: Bitmap? = null
    private var overlayCanvas = Canvas()
    private var overlayRes = 0f
    private var overlayPage = -1
    private var overlayCount = 0
    private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x8C0F172A.toInt()
    }

    private val bboxCache = HashMap<Stroke, FloatArray>()
    private var lastReportedZoom = -1f
    private var scrollAnim: android.animation.ValueAnimator? = null

    // --------------------------- Callback Implementation -------------------------------

    override fun toWorldX(sx: Float) = (sx - tx) / scale
    override fun toWorldY(sy: Float) = (sy - ty) / scale
    override fun pageTop(i: Int): Float = i * (PAGE_H + GAP)
    override fun pageOfY(wy: Float): Int = (wy / (PAGE_H + GAP)).toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    override val viewWidth: Int get() = width
    override val viewHeight: Int get() = height
    override fun docHeight(): Float = if (pageCount == 0) PAGE_H else pageCount * PAGE_H + (pageCount - 1) * GAP
    override fun minScale(): Float = MIN_SCALE
    override fun getPageLists(): ArrayList<MutableList<Stroke>>? = pageLists
    override fun getBboxCache(): HashMap<Stroke, FloatArray> = bboxCache
    override fun getPageCache(): PageCache = pageCache
    override fun isReady(): Boolean = ready
    override fun stopScrolling() = stopScrollingInternal()
    override fun clampPan() = clampPanInternal()
    override fun afterZoom() = afterZoomInternal()
    override fun notifyPageIfChanged() = notifyPageIfChangedInternal()
    override fun overlayReset() = overlayResetInternal()

    // ---------------------------- API publique ------------------------------

    fun setDocument(lists: List<MutableList<Stroke>>) {
        pageLists = ArrayList(lists)
        bboxCache.clear()
        pageCache.setDocument(pageLists)
        ready = true
        if (!sized && width > 0 && height > 0) fitPage()
        notifyPageIfChangedInternal(force = true)
        postInvalidateOnAnimation()
    }

    fun appendPage(list: MutableList<Stroke>) {
        pageLists?.add(list)
        pageCache.appendPageList(list)
        postInvalidateOnAnimation()
    }

    fun appendStroke(stroke: Stroke, pageIndex: Int) {
        pageCache.bakeStroke(pageIndex, stroke)
        pageCache.invalidatePreview(pageIndex)
        postInvalidateOnAnimation()
    }

    fun invalidateCache() {
        pageCache.invalidateAll()
        postInvalidateOnAnimation()
    }

    fun fitPage() {
        if (width <= 0 || height <= 0) return
        val s = min((width - 28f * density) / PAGE_W, (height - 28f * density) / PAGE_H).coerceIn(MIN_SCALE, MAX_SCALE)
        stopScrollingInternal()
        scale = s
        tx = (width - PAGE_W * s) / 2f
        ty = height / 2f - (pageTop(currentPage) + PAGE_H / 2f) * s
        afterZoomInternal()
    }

    fun scrollToPage(index: Int, smooth: Boolean = true) {
        if (pageCount == 0) return
        val i = index.coerceIn(0, pageCount - 1)
        val targetTx = (width - PAGE_W * scale) / 2f
        val targetTy = height / 2f - (pageTop(i) + PAGE_H / 2f) * scale
        stopScrollingInternal()
        if (!smooth) {
            tx = targetTx
            ty = targetTy
            afterZoomInternal()
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
            clampPanInternal()
            postInvalidateOnAnimation()
            notifyPageIfChangedInternal()
        }
        scrollAnim = anim
        anim.start()
    }

    private fun stopScrollingInternal() {
        scrollAnim?.cancel()
        scrollAnim = null
        inputHandler.stopFling()
    }

    override fun onDetachedFromWindow() {
        pageCache.destroy()
        super.onDetachedFromWindow()
    }

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
            clampPanInternal()
            pageCache.invalidateAll()
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BG_COLOR)
        val lists = pageLists ?: return
        val p0 = pageOfY(toWorldY(0f))
        val p1 = pageOfY(toWorldY(height.toFloat()))
        fillPaint.color = Color.WHITE
        for (p in p0..p1) {
            val top = ty + pageTop(p) * scale
            val bottom = top + PAGE_H * scale
            if (bottom < 0f || top > height) continue
            canvas.drawRect(tx, top, tx + PAGE_W * scale, bottom, fillPaint)
        }
        val res = scale * density
        val visL = toWorldX(0f)
        val visR = toWorldX(width.toFloat())
        val visT = toWorldY(0f)
        val visB = toWorldY(height.toFloat())
        for (p in max(0, p0 - 1)..min(lists.size - 1, p1 + 1)) {
            val topW = pageTop(p)
            val topS = ty + topW * scale
            val fullE = pageCache.fullEntryFor(p)
            val prevE = pageCache.previewFor(p)
            val vr = RectF(visL.coerceIn(0f, PAGE_W), (visT - topW).coerceIn(0f, PAGE_H), visR.coerceIn(0f, PAGE_W), (visB - topW).coerceIn(0f, PAGE_H))
            val hasSharpCover = fullE != null && (fullE.rect == null || fullE.rect.contains(vr))
            if (prevE != null && !hasSharpCover) {
                canvas.drawBitmap(prevE.bitmap, null, RectF(tx, topS, tx + PAGE_W * scale, topS + PAGE_H * scale), cachePaint)
            }
            if (fullE != null) {
                cachePaint.isFilterBitmap = abs(fullE.res - res) > 0.05f
                if (fullE.rect == null) {
                    canvas.drawBitmap(fullE.bitmap, null, RectF(tx, topS, tx + PAGE_W * scale, topS + PAGE_H * scale), cachePaint)
                } else {
                    val r = fullE.rect
                    canvas.drawBitmap(fullE.bitmap, null, RectF(tx + r.left * scale, topS + r.top * scale, tx + r.right * scale, topS + r.bottom * scale), cachePaint)
                }
            }
            pageCache.request(p, res, urgent = (p == currentPage), rect = vr, withFull = (p in p0..p1))
        }
        val lv = inputHandler.live
        if (lv != null && lv.pts.count > 0) drawLive(canvas, lv)
        val currentPos = if (inputHandler.mode == Mode.ERASE) inputHandler.erasePos else inputHandler.hoverPos
        val isEraserActive = (tool == InkInputHandler.Tool.ERASER && inputHandler.mode == Mode.NONE) || (inputHandler.mode == Mode.ERASE && inputHandler.eraseType != PtrType.TOUCH)
        if ((isEraserActive || (inputHandler.stylusButtonHeld && inputHandler.mode == Mode.NONE)) && currentPos != null) {
            eraserCursorPaint.strokeWidth = 1.5f * density
            canvas.drawCircle(currentPos[0], currentPos[1], eraseRadiusPx, eraserCursorPaint)
        }
    }

    override fun onHoverEvent(event: MotionEvent) = inputHandler.onHoverEvent(event)

    private fun drawLive(canvas: Canvas, lv: Live) {
        val res = min(scale * density, sqrt(OVERLAY_BUDGET / (PAGE_W * PAGE_H)))
        if (overlayRes != res || overlayPage != lv.page || overlay == null) {
            val w = max(1, ceil(PAGE_W * res).toInt())
            val h = max(1, ceil(PAGE_H * res).toInt())
            overlay = createBitmap(w, h).also { overlayCanvas.setBitmap(it) }
            overlayRes = res; overlayPage = lv.page; overlayCount = 0
        }
        if (lv.pts.count - overlayCount >= OVERLAY_BAKE_STEP) {
            val bakeTo = lv.pts.count - 8
            overlayCanvas.withScale(overlayRes, overlayRes) { withTranslation(0f, -pageTop(lv.page)) {
                StrokePainter.paintPoints(this, strokePaint, lv.color, lv.size, lv.pts.raw(), bakeTo, max(0, overlayCount - 2))
            } }
            overlayCount = bakeTo
        }
        overlay?.let { canvas.drawBitmap(it, null, RectF(tx, ty + pageTop(lv.page) * scale, tx + PAGE_W * scale, ty + (pageTop(lv.page) + PAGE_H) * scale), cachePaint.apply { isFilterBitmap = false }) }
        canvas.withTranslation(tx, ty) { withScale(scale, scale) { withTranslation(0f, -pageTop(lv.page)) {
            StrokePainter.paintPoints(this, strokePaint, lv.color, lv.size, lv.pts.raw(), lv.pts.count, max(0, overlayCount - LIVE_TAIL_OVERLAP))
        } } }
    }

    private fun overlayResetInternal() { overlay?.eraseColor(Color.TRANSPARENT); overlayCount = 0 }
    override fun computeScroll() = if (!inputHandler.computeScroll()) super.computeScroll() else Unit
    private fun clampPanInternal() {
        val w = PAGE_W * scale; val h = docHeight() * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
    }
    private fun afterZoomInternal() {
        clampPanInternal(); postInvalidateOnAnimation()
        if (abs(scale - lastReportedZoom) > 0.005f) { lastReportedZoom = scale; host?.onZoomChanged(scale) }
        notifyPageIfChangedInternal()
    }
    private fun notifyPageIfChangedInternal(force: Boolean = false) {
        if (pageCount == 0) return
        val p = pageOfY(toWorldY(height / 2f))
        if (p != currentPage || force) { currentPage = p; host?.onPageChanged(p) }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = inputHandler.onTouchEvent(event)
    val pageCount: Int get() = pageLists?.size ?: 0
}
