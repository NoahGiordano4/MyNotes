package com.example.lumennotes.ink

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

enum class Mode { NONE, DRAW, ERASE, PAN, PINCH }

object PtrType {
    const val TOUCH = 0
    const val STYLUS = 1
    const val STYLUS_ERASER = 2
    const val MOUSE = 3
}

class Ptr(var x: Float, var y: Float, val type: Int)

class Live(
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

class Gesture(
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
