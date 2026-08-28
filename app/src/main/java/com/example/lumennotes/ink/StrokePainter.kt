package com.example.lumennotes.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.example.lumennotes.data.Stroke
import kotlin.math.hypot

/**
 * Rendu des traits — moteur v2 « RUBAN » à largeur variable.
 *
 * Au lieu de dessiner segment par segment avec joints circulaires — ce qui
 * fait des « perles » et des bosses dès que l'espacement des points varie —
 * on construit UNE SEULE forme pleine :
 *
 *   1. centreline lissée par deux passes de moyenne centrée — les positions
 *      ET la pression sont lissées, donc une largeur douce ;
 *   2. normale calculée à chaque point, décalage de ±largeur/2 de chaque
 *      côté → deux courbes de contour, gauche et droite ;
 *   3. contours tracés en Catmull-Rom cubique — la courbe passe par tous
 *      les points, sans sur-oscillation — puis remplis en un seul tenant ;
 *   4. caps ronds aux deux extrémités.
 *
 * → silhouette parfaitement continue à n'importe quel zoom, du premier au
 * dernier point. Espace de travail par thread : utilisable depuis le thread
 * UI, pour le trait vivant, ET le thread de rendu d'arrière-plan, pour le
 * cache des pages.
 */
object StrokePainter {

    /** Largeur de plume pour une pression donnée. */
    fun widthFor(size: Float, pressure: Float): Float {
        val p = if (pressure < 0f) 0f else if (pressure > 1f) 1f else pressure
        return (size * (0.42f + 0.95f * p)).coerceAtLeast(0.8f)
    }

    fun paintStroke(c: Canvas, paint: Paint, stroke: Stroke) {
        paintPoints(c, paint, stroke.color, stroke.size, stroke.points, stroke.points.size / 3)
    }

    /**
     * Dessine, en coordonnées du contexte courant, les points [x, y,
     * pression, …] depuis l'indice `from` jusqu'à `count` exclu. `from`
     * permet de ne redessiner que la QUEUE du trait vivant, pour le rendu
     * incrémental — le lissage garde ses points de contexte internes, la
     * transition est invisible.
     */
    fun paintPoints(
        c: Canvas,
        paint: Paint,
        color: Int,
        size: Float,
        pts: FloatArray,
        count: Int,
        from: Int = 0
    ) {
        if (count <= 0 || pts.size < count * 3) return
        val start = if (from > 0) from else 0
        val n = count - start
        if (n <= 0) return

        paint.isAntiAlias = true
        paint.isFilterBitmap = false
        paint.isDither = false

        val w = work.get()!!
        w.ensure(n)

        // 1. copie de la fenêtre [from, count]
        var j = 0
        while (j < n) {
            val k = (start + j) * 3
            w.xs[j] = pts[k]
            w.ys[j] = pts[k + 1]
            w.ps[j] = pts[k + 2]
            j++
        }

        // 2. lissage centré double passe : positions ET pression
        var pass = 0
        while (pass < 2) {
            var i = 1
            while (i < n - 1) {
                w.xs[i] = (w.xs[i - 1] + 2f * w.xs[i] + w.xs[i + 1]) * 0.25f
                w.ys[i] = (w.ys[i - 1] + 2f * w.ys[i] + w.ys[i + 1]) * 0.25f
                w.ps[i] = (w.ps[i - 1] + 2f * w.ps[i] + w.ps[i + 1]) * 0.25f
                i++
            }
            pass++
        }

        // point isolé, simple toucher
        if (n == 1) {
            paint.style = Paint.Style.FILL
            paint.color = color
            c.drawCircle(w.xs[0], w.ys[0], widthFor(size, w.ps[0]) * 0.5f, paint)
            return
        }

        // 3. normales + décalage gauche/droite, largeur lissée par point
        var nx = 0f
        var ny = -1f
        var i = 0
        while (i < n) {
            val i0 = if (i > 0) i - 1 else i
            val i1 = if (i < n - 1) i + 1 else i
            val dx = w.xs[i1] - w.xs[i0]
            val dy = w.ys[i1] - w.ys[i0]
            val len = hypot(dx, dy)
            if (len > 1e-4f) {
                nx = -dy / len
                ny = dx / len
            }
            val hw = widthFor(size, w.ps[i]) * 0.5f
            w.lx[i] = w.xs[i] + nx * hw
            w.ly[i] = w.ys[i] + ny * hw
            w.rx[i] = w.xs[i] - nx * hw
            w.ry[i] = w.ys[i] - ny * hw
            i++
        }

        // 4. contour unique : gauche à l'aller, droite au retour, en
        //    cubiques Catmull-Rom de contrôle = tangentes des voisins sur 6
        val p = w.path
        p.reset()
        p.moveTo(w.lx[0], w.ly[0])
        var k = 0
        while (k < n - 1) {
            val a = if (k > 0) k - 1 else k
            val b = if (k + 2 < n) k + 2 else k + 1
            val x1 = w.lx[k]; val y1 = w.ly[k]
            val x2 = w.lx[k + 1]; val y2 = w.ly[k + 1]
            p.cubicTo(
                x1 + (x2 - w.lx[a]) / 6f, y1 + (y2 - w.ly[a]) / 6f,
                x2 - (w.lx[b] - x1) / 6f, y2 - (w.ly[b] - y1) / 6f,
                x2, y2
            )
            k++
        }
        var m = n - 1
        while (m > 0) {
            val a = if (m < n - 1) m + 1 else m
            val b = if (m - 2 >= 0) m - 2 else m - 1
            val x1 = w.rx[m]; val y1 = w.ry[m]
            val x2 = w.rx[m - 1]; val y2 = w.ry[m - 1]
            p.cubicTo(
                x1 + (x2 - w.rx[a]) / 6f, y1 + (y2 - w.ry[a]) / 6f,
                x2 - (w.rx[b] - x1) / 6f, y2 - (w.ry[b] - y1) / 6f,
                x2, y2
            )
            m--
        }
        p.close()

        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(p, paint)

        // 5. caps ronds aux extrémités
        c.drawCircle(w.xs[0], w.ys[0], widthFor(size, w.ps[0]) * 0.5f, paint)
        c.drawCircle(w.xs[n - 1], w.ys[n - 1], widthFor(size, w.ps[n - 1]) * 0.5f, paint)
    }

    /* -------- espace de travail par thread, zéro allocation -------- */

    private class Work {
        val path = Path()
        var xs = FloatArray(256)
        var ys = FloatArray(256)
        var ps = FloatArray(256)
        var lx = FloatArray(256)
        var ly = FloatArray(256)
        var rx = FloatArray(256)
        var ry = FloatArray(256)

        fun ensure(n: Int) {
            if (xs.size >= n) return
            val cap = maxOf(n, xs.size * 2)
            xs = xs.copyOf(cap); ys = ys.copyOf(cap); ps = ps.copyOf(cap)
            lx = lx.copyOf(cap); ly = ly.copyOf(cap)
            rx = rx.copyOf(cap); ry = ry.copyOf(cap)
        }
    }

    private val work = ThreadLocal.withInitial { Work() }
}