package com.example.lumennotes.ink

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import com.example.lumennotes.data.EraseHit
import com.example.lumennotes.data.Stroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class InkInputHandler(context: Context, private val callback: Callback) {

    interface Callback {
        fun invalidate()
        fun postInvalidateOnAnimation()
        fun toWorldX(sx: Float): Float
        fun toWorldY(sy: Float): Float
        fun pageTop(i: Int): Float
        fun pageOfY(wy: Float): Int
        
        val penSize: Float
        val tool: Tool
        
        var scale: Float
        var tx: Float
        var ty: Float
        
        val host: InkCanvasView.Host?
        val viewWidth: Int
        val viewHeight: Int
        
        fun docHeight(): Float
        fun minScale(): Float
        fun getPageLists(): ArrayList<MutableList<Stroke>>?
        fun getBboxCache(): HashMap<Stroke, FloatArray>
        fun getPageCache(): PageCache
        fun isReady(): Boolean
        fun stopScrolling()
        fun clampPan()
        fun afterZoom()
        fun notifyPageIfChanged()
        fun overlayReset()
        val density: Float
        val eraseRadiusPx: Float
    }

    enum class Tool { PEN, ERASER }

    companion object {
        private const val MIN_WORLD_STEP = 0.3f
        private const val STREAMLINE = 1f
        private const val PRESSURE_SMOOTH = 0.45f
        private const val SIM_P_MAX = 0.72f
        private const val SIM_P_MIN = 0.28f
        private const val MARATHON_SPLIT = 4_000
        private const val ERASE_RERENDER_MS = 64L
        private const val MAX_SCALE = 8f
    }

    val pointers = HashMap<Int, Ptr>()
    var mode = Mode.NONE
    var live: Live? = null
    var erasePointerId = -1
    var eraseType = PtrType.TOUCH
    var erasePos: FloatArray? = null
    private var erasePage = 0
    private val eraseHits = ArrayList<EraseHit>()
    private var lastEraseRender = 0L
    var gesture: Gesture? = null
    var stylusButtonHeld = false
    var hoverPos: FloatArray? = null

    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    var flingActive = false
        private set

    fun onTouchEvent(event: MotionEvent): Boolean {
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

    fun onHoverEvent(event: MotionEvent): Boolean {
        val type = event.getToolType(0)
        if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_MOUSE) {
            hoverPos = null
            return false
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
        callback.postInvalidateOnAnimation()
        return true
    }

    private fun pointerAdded(event: MotionEvent, index: Int) {
        if (!callback.isReady()) return
        callback.stopScrolling()
        val id = event.getPointerId(index)
        val sx = event.getX(index)
        val sy = event.getY(index)
        val type = typeOf(event.getToolType(index))
        pointers[id] = Ptr(sx, sy, type)

        if (type == PtrType.STYLUS || type == PtrType.STYLUS_ERASER) {
            endGesture()
            if (mode == Mode.ERASE && erasePointerId != id) finishErase()
            val lv = live
            if (mode == Mode.DRAW && lv != null && !isStylus(lv.type)) discardLive()
            val btn = stylusButtonsHeld(event)
            stylusButtonHeld = btn
            val wantErase = btn || type == PtrType.STYLUS_ERASER || callback.tool == Tool.ERASER
            if (wantErase) startErase(id, type, sx, sy)
            else startDraw(id, type, sx, sy, event.getPressure(index), event.eventTime)
            return
        }

        if (type == PtrType.TOUCH) {
            if (mode == Mode.DRAW && live?.let { isStylus(it.type) } == true) return
            if (mode == Mode.ERASE && isStylus(eraseType)) return

            val touchCount = pointers.values.count { it.type == PtrType.TOUCH }
            val stylusOnly = callback.host?.inputMode() != "finger"
            if (stylusOnly || touchCount >= 2) {
                if (mode == Mode.DRAW) discardLive()
                if (mode == Mode.ERASE) finishErase()
                startGesture()
                if (mode == Mode.PAN && velocity == null) {
                    velocity = VelocityTracker.obtain()
                    velocity?.addMovement(event)
                }
            } else {
                if (callback.tool == Tool.ERASER) startErase(id, type, sx, sy)
                else startDraw(id, type, sx, sy, event.getPressure(index), event.eventTime)
            }
            return
        }

        if (callback.tool == Tool.ERASER) startErase(id, type, sx, sy)
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
        callback.postInvalidateOnAnimation()
    }

    private fun routeMove(id: Int, sx: Float, sy: Float, pressure: Float, t: Long) {
        val rec = pointers[id] ?: return
        rec.x = sx
        rec.y = sy

        val lv = live
        when (mode) {
            Mode.DRAW -> if (lv != null && lv.pointerId == id) addLivePoint(lv, sx, sy, pressure, t)
            Mode.ERASE -> if (id == erasePointerId) eraseAt(sx, sy)
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

        val scale = callback.scale
        val tx = callback.tx
        val ty = callback.ty
        val w = InkCanvasView.PAGE_W * scale
        val h = callback.docHeight() * scale
        val viewW = callback.viewWidth
        val viewH = callback.viewHeight

        val loX = if (w <= viewW) (viewW - w) / 2f else viewW - w
        val hiX = if (w <= viewW) loX else 0f
        val loY = if (h <= viewH) (viewH - h) / 2f else viewH - h
        val hiY = if (h <= viewH) loY else 0f

        scroller.fling(
            (-tx).toInt(), (-ty).toInt(),
            (-vx).toInt(), (-vy).toInt(),
            (-hiX).toInt(), (-loX).toInt(),
            (-hiY).toInt(), (-loY).toInt()
        )
        flingActive = true
        callback.postInvalidateOnAnimation()
    }

    fun computeScroll(): Boolean {
        if (flingActive && scroller.computeScrollOffset()) {
            callback.tx = -scroller.currX.toFloat()
            callback.ty = -scroller.currY.toFloat()
            callback.clampPan()
            callback.notifyPageIfChanged()
            callback.postInvalidateOnAnimation()
            return true
        } else if (flingActive) {
            flingActive = false
        }
        return false
    }

    fun stopFling() {
        scroller.abortAnimation()
        flingActive = false
    }

    fun resetInput() {
        stylusButtonHeld = false
        discardLive()
        if (mode == Mode.ERASE) finishErase()
        endGesture()
        pointers.clear()
        velocity?.recycle()
        velocity = null
    }

    private fun startDraw(id: Int, type: Int, sx: Float, sy: Float, pressure: Float, t: Long) {
        mode = Mode.DRAW
        val wx = callback.toWorldX(sx)
        val wy = callback.toWorldY(sy)
        val p = if (type == PtrType.STYLUS && pressure > 0f) pressure.coerceIn(0.05f, 1f) else 0.5f
        val times = if (t > 0L) LongBuf().also { it.add(t) } else null
        live = Live(
            pointerId = id,
            type = type,
            color = InkCanvasView.INK_COLOR,
            size = callback.penSize,
            page = callback.pageOfY(wy),
            pts = FloatBuf().also { it.add(wx, wy, p) },
            times = times,
            lastSX = sx,
            lastSY = sy,
            lastWX = wx,
            lastWY = wy,
            lastT = System.nanoTime() / 1_000_000L,
            simP = p
        )
        callback.postInvalidateOnAnimation()
    }

    private fun addLivePoint(lv: Live, sx: Float, sy: Float, rawPressure: Float, t: Long) {
        val wx = callback.toWorldX(sx)
        val wy = callback.toWorldY(sy)
        if (lv.pts.count > 1 && hypot(wx - lv.lastWX, wy - lv.lastWY) < MIN_WORLD_STEP) return

        val d = hypot(sx - lv.lastSX, sy - lv.lastSY)
        val now = System.nanoTime() / 1_000_000L
        val dt = max(1L, now - lv.lastT)
        val pressure: Float = if (lv.type == PtrType.STYLUS && rawPressure > 0f) {
            lv.simP + (rawPressure.coerceIn(0f, 1f) - lv.simP) * PRESSURE_SMOOTH
        } else {
            val speed = d / dt
            val target = (SIM_P_MAX - speed * 0.18f * callback.density).coerceIn(SIM_P_MIN, SIM_P_MAX)
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

        if (lv.pts.count >= MARATHON_SPLIT) splitMarathon(lv)
    }

    private fun buildStroke(lv: Live): Stroke? {
        val n = lv.pts.count
        if (n == 0) return null
        val top = callback.pageTop(lv.page)
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
        callback.overlayReset()
        callback.host?.onStrokeCommitted(stroke, lv.page)
    }

    private fun splitMarathon(lv: Live) {
        val stroke = buildStroke(lv) ?: return
        callback.host?.onStrokeCommitted(stroke, lv.page)
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
        callback.overlayReset()
    }

    private fun discardLive() {
        live = null
        if (mode == Mode.DRAW) mode = Mode.NONE
        callback.overlayReset()
        callback.postInvalidateOnAnimation()
    }

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
        val lists = callback.getPageLists() ?: return
        val wx = callback.toWorldX(sx)
        val wy = callback.toWorldY(sy)
        val page = callback.pageOfY(wy)
        val list = lists.getOrNull(page) ?: return
        val ly = wy - callback.pageTop(page)
        val r = callback.eraseRadiusPx / callback.scale + 2f
        var removed = false
        for (i in list.indices.reversed()) {
            val s = list[i]
            if (strokeHit(s, wx, ly, r)) {
                list.removeAt(i)
                eraseHits.add(EraseHit(s, i))
                callback.getBboxCache().remove(s)
                removed = true
            }
        }
        erasePage = page
        if (removed) {
            val now = System.currentTimeMillis()
            if (now - lastEraseRender >= ERASE_RERENDER_MS) {
                lastEraseRender = now
                callback.getPageCache().invalidatePage(page)
                callback.getPageCache().request(page, callback.scale * callback.density, urgent = true)
            }
            callback.postInvalidateOnAnimation()
        }
    }

    private fun finishErase() {
        val hits = ArrayList(eraseHits)
        val page = erasePage
        eraseHits.clear()
        erasePointerId = -1
        eraseType = PtrType.TOUCH
        erasePos = null
        mode = Mode.NONE
        if (hits.isNotEmpty()) {
            lastEraseRender = 0L
            callback.getPageCache().invalidatePage(page)
            callback.getPageCache().request(page, callback.scale * callback.density, urgent = true)
            callback.host?.onErase(hits, page)
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
        var box = callback.getBboxCache()[s]
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
            callback.getBboxCache()[s] = box
        }
        return box
    }

    private fun gesturePointers(): List<Pair<Int, Ptr>> {
        val touches = pointers.entries.filter { it.value.type == PtrType.TOUCH }
        if (touches.isNotEmpty()) {
            return touches.sortedBy { it.key }.take(2).map { it.key to it.value }
        }
        val mouse = pointers.entries.filter { it.value.type == PtrType.MOUSE }
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
        g.v0Scale = callback.scale
        g.v0X = callback.tx
        g.v0Y = callback.ty
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
            val s = (g.v0Scale * (d / g.startDist)).coerceIn(callback.minScale(), MAX_SCALE)
            val k = s / g.v0Scale
            callback.scale = s
            callback.tx = mx - (g.startMX - g.v0X) * k
            callback.ty = my - (g.startMY - g.v0Y) * k
        } else {
            callback.tx = g.v0X + (pts[0].x - g.startX)
            callback.ty = g.v0Y + (pts[0].y - g.startY)
        }
        callback.afterZoom()
    }

    private fun endGesture() {
        gesture = null
        if (mode == Mode.PAN || mode == Mode.PINCH) mode = Mode.NONE
    }

    private fun isStylus(type: Int): Boolean =
        type == PtrType.STYLUS || type == PtrType.STYLUS_ERASER

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
                startErase(lv.pointerId, PtrType.STYLUS, p.x, p.y)
            }
        }
    }

    private fun typeOf(toolType: Int): Int = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PtrType.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PtrType.STYLUS_ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PtrType.MOUSE
        else -> PtrType.TOUCH
    }
}
