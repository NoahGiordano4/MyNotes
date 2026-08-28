package com.example.lumennotes

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.lumennotes.data.NoteMeta
import com.example.lumennotes.data.NoteRepository
import com.example.lumennotes.data.Prefs
import com.example.lumennotes.databinding.ActivityMainBinding
import com.example.lumennotes.databinding.DialogSettingsBinding
import com.example.lumennotes.util.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), NotesAdapter.Listener {

    private lateinit var binding: ActivityMainBinding
    private val repo by lazy { NoteRepository(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fab.setOnClickListener {
            io.execute {
                repo.createNote(getString(R.string.untitled))
                main.post { refresh() }
            }
        }

        binding.btnSettings.setOnClickListener { showSettings() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        io.execute {
            val list = repo.listMetas()
            main.post {
                binding.rvNotes.layoutManager = GridLayoutManager(this, 2)
                binding.rvNotes.adapter = NotesAdapter(list, this)
                binding.emptyState.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /* ------------------------- actions des cartes ------------------------- */

    override fun onOpen(meta: NoteMeta) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("note_id", meta.id))
    }

    override fun onRename(meta: NoteMeta) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(meta.title)
            setSelection(text.length)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val wrap = FrameLayout(this).apply {
            val m = (10 * resources.displayMetrics.density).toInt()
            setPadding(m, 0, m, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    io.execute {
                        meta.title = t
                        meta.updatedAt = System.currentTimeMillis()
                        repo.saveMeta(meta)
                        main.post { refresh() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDelete(meta: NoteMeta) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, meta.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    repo.deleteNote(meta.id)
                    main.post {
                        Toast.makeText(this@MainActivity, R.string.note_deleted, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ------------------------------ réglages ------------------------------ */

    private fun showSettings() {
        val dv = DialogSettingsBinding.inflate(layoutInflater)

        if (Prefs.inputMode(this) == Prefs.MODE_FINGER) dv.rbFinger.isChecked = true
        else dv.rbStylus.isChecked = true

        dv.rbStylus.setOnCheckedChangeListener { _, checked ->
            if (checked) Prefs.setInputMode(this, Prefs.MODE_STYLUS)
        }
        dv.rbFinger.setOnCheckedChangeListener { _, checked ->
            if (checked) Prefs.setInputMode(this, Prefs.MODE_FINGER)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(dv.root)
            .setPositiveButton(R.string.done, null)
            .show()
    }
}