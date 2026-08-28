package com.example.lumennotes.ink

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import com.example.lumennotes.util.AppLog
import java.util.Locale

/**
 * Vérification orthographique via le service système Android.
 *
 * v2 : on force la locale de la note lors de la création de session.
 * Avant, referToSpellCheckerLanguageSettings = true faisait que la
 * session n'était créée QUE si la langue du correcteur dans les
 * Réglages système était identique à la nôtre — sur ta tablette elle
 * renvoyait donc null (correcteur désactivé ou réglé sur une autre
 * langue) et AUCUN soulignement n'était jamais possible.
 *
 * Si aucun service n'existe, on le journalise, on prévient l'appelant
 * UNE fois (pour afficher un message à l'utilisateur), et on rend la
 * main avec une liste vide — le pipeline de reco n'est jamais bloqué.
 */
class SpellCheckManager(
    context: Context,
    private val locale: Locale,
    private val onServiceMissing: (() -> Unit)? = null
) : SpellCheckerSession.SpellCheckerSessionListener {

    private val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
    private var session: SpellCheckerSession? = null
    private var missingReported = false

    private var pendingCallback: ((List<MisspelledWord>) -> Unit)? = null
    private var currentText: String = ""

    data class MisspelledWord(val text: String, val offset: Int, val length: Int)

    fun checkSpelling(text: String, callback: (List<MisspelledWord>) -> Unit) {
        if (session == null) {
            session = try {
                // false = la session utilise NOTRE locale, pas celle des
                // Réglages du correcteur (vrai correctif du « aucun
                // service de correcteur pour fr_FR »)
                tsm.newSpellCheckerSession(null, locale, this, false)
            } catch (e: Exception) {
                AppLog.log("spell", "création de session impossible pour $locale", e)
                null
            }
            if (session == null) {
                AppLog.log(
                    "spell",
                    "aucun correcteur disponible pour $locale — active-le : " +
                            "Réglages > Gestion générale > Langues et saisie > Correcteur orthographique"
                )
                if (!missingReported) {
                    missingReported = true
                    onServiceMissing?.invoke()
                }
                callback(emptyList())
                return
            }
            AppLog.log("spell", "session de correction créée pour $locale")
        }
        currentText = text
        pendingCallback = callback
        session?.getSentenceSuggestions(arrayOf(TextInfo(text)), 5)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // non utilisé : on passe par getSentenceSuggestions
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val misspelled = mutableListOf<MisspelledWord>()
        results?.forEach { sentenceResult ->
            for (i in 0 until sentenceResult.suggestionsCount) {
                val info = sentenceResult.getSuggestionsInfoAt(i)
                // Piège classique de cette API : RESULT_ATTR_LOOKUP_NOT_FOUND
                // (0x0001) est posé par certains correcteurs sur TOUS les mots,
                // y compris les corrects, lorsqu'ils n'ont pas pu les vérifier
                // (dictionnaire de la locale non chargé, segmentation, etc.).
                // Inversement, un mot correct a RESULT_ATTR_IN_THE_DICTIONARY
                // (0x0002). Le seul signal fiable d'une VRAIE faute — celui
                // qu'utilise TextView/SpellChecker en interne — est
                // RESULT_ATTR_LOOKS_LIKE_TYPO (0x0004) : on ne souligne que ce
                // que le correcteur affirme lui-même être une faute de frappe.
                if ((info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) == 0) {
                    continue
                }
                val offset = sentenceResult.getOffsetAt(i)
                val length = sentenceResult.getLengthAt(i)
                if (offset >= 0 && offset + length <= currentText.length) {
                    val wordText = currentText.substring(offset, offset + length)
                    misspelled.add(MisspelledWord(wordText, offset, length))
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