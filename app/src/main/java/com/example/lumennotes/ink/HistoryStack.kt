package com.example.lumennotes.ink

import com.example.lumennotes.data.EraseHit
import com.example.lumennotes.data.Stroke

sealed class InkOp {
    class Add(val stroke: Stroke) : InkOp()
    class Erase(val items: List<EraseHit>) : InkOp()
}

class HistoryStack(private val limit: Int = 120) {

    private val undoStack = ArrayList<InkOp>()
    private val redoStack = ArrayList<InkOp>()

    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(op: InkOp) {
        undoStack.add(op)
        if (undoStack.size > limit) undoStack.removeAt(0)
        redoStack.clear()
        onChanged?.invoke()
    }

    fun undoOp(): InkOp? {
        if (undoStack.isEmpty()) return null
        val op = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(op)
        onChanged?.invoke()
        return op
    }

    fun redoOp(): InkOp? {
        if (redoStack.isEmpty()) return null
        val op = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(op)
        onChanged?.invoke()
        return op
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        onChanged?.invoke()
    }
}