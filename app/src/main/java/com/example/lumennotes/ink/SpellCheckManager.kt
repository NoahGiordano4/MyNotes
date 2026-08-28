package com.example.lumennotes.ink

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/**
 * Gère la vérification orthographique via le service système Android.
 */
class SpellCheckManager(context: Context, private val locale: Locale) : SpellCheckerSession.SpellCheckerSessionListener {

    private val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
    private var session: SpellCheckerSession? = null
    
    private var pendingCallback: ((List<MisspelledWord>) -> Unit)? = null
    private var currentText: String = ""

    data class MisspelledWord(val text: String, val offset: Int, val length: Int)

    fun checkSpelling(text: String, callback: (List<MisspelledWord>) -> Unit) {
        if (session == null) {
            session = tsm.newSpellCheckerSession(null, locale, this, true)
        }
        
        currentText = text
        pendingCallback = callback
        session?.getSentenceSuggestions(arrayOf(TextInfo(text)), 5)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // Non utilisé pour getSentenceSuggestions
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val misspelled = mutableListOf<MisspelledWord>()
        results?.forEach { sentenceResult ->
            for (i in 0 until sentenceResult.suggestionsCount) {
                val info = sentenceResult.getSuggestionsInfoAt(i)
                // RESULT_ATTR_LOOKUP_NOT_FOUND (1) indicate that the word is not found in the dictionary
                if ((info.suggestionsAttributes and 0x0001) != 0) {
                    val offset = sentenceResult.getOffsetAt(i)
                    val length = sentenceResult.getLengthAt(i)
                    if (offset >= 0 && offset + length <= currentText.length) {
                        val wordText = currentText.substring(offset, offset + length)
                        misspelled.add(MisspelledWord(wordText, offset, length))
                    }
                }
            }
        }
        pendingCallback?.invoke(misspelled)
    }

    fun close() {
        session?.close()
        session = null
    }
}
