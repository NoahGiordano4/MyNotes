package com.example.lumennotes

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.example.lumennotes.data.NoteMeta
import com.example.lumennotes.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NotesAdapter(
    private val items: List<NoteMeta>,
    private val listener: Listener
) : RecyclerView.Adapter<NotesAdapter.VH>() {

    interface Listener {
        fun onOpen(meta: NoteMeta)
        fun onRename(meta: NoteMeta)
        fun onDelete(meta: NoteMeta)
    }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val meta = items[position]
        holder.b.noteTitle.text = meta.title
        holder.b.noteMeta.text = formatDate(meta.updatedAt)
        holder.b.root.setOnClickListener { listener.onOpen(meta) }
        holder.b.btnMore.setOnClickListener { anchor ->
            val pm = PopupMenu(anchor.context, anchor)
            pm.menuInflater.inflate(R.menu.menu_card, pm.menu)
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.miRename -> listener.onRename(meta)
                    R.id.miDelete -> listener.onDelete(meta)
                }
                true
            }
            pm.show()
        }
    }

    private fun formatDate(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date(ts))
        } else {
            SimpleDateFormat("d MMM yyyy", Locale.FRENCH).format(Date(ts))
        }
    }
}