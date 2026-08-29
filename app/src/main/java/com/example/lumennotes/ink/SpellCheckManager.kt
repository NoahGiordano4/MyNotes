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
 * Vérification orthographique via le service système Android (100 % local,
 * utilises le dictionnaire installé sur l'appareil — aucune connexion réseau).
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

    fun checkSpelling(text: String, callback: (List<MisspelledWord>) -> Unit) {
        if (session == null) {
            session = createBestEffortSession()
            if (session == null) {
                AppLog.log(
                    "spell",
                    "aucun correcteur disponible — active-le : " +
                            "Réglages > Gestion générale > Langues et saisie > Correcteur orthographique"
                )
                if (!missingReported) {
                    missingReported = true
                    onServiceMissing?.invoke()
                }
                callback(emptyList())
                return
            }
        }
        currentText = text
        pendingCallback = callback
        session?.getSentenceSuggestions(arrayOf(TextInfo(text)), 5)
    }

    /**
     * Crée la session de correction avec la meilleure chance d'avoir un
     * vrai dictionnaire chargé.
     *
     * 1) On demande d'abord le correcteur ACTIF de l'appareil
     *    (referToSpellCheckerLanguageSettings = true) : c'est la langue
     *    choisie dans les Réglages, celle dont le dictionnaire est
     *    réellement installé. Il sait donc distinguer les vrais mots des
     *    fautes.
     * 2) Si aucune session active n'existe, on se rabat sur la locale de
     *    la note (referToSpellCheckerLanguageSettings = false). Piège :
     *    selon l'appareil cette session a un dictionnaire VIDE — le
     *    correcteur répond alors LOOKUP_NOT_FOUND sur TOUS les mots
     *    (ni LOOKS_LIKE_TYPO ni IN_THE_DICTIONARY), ce qui rend la
     *    détection impossible. On le journalise pour le diagnostiquer.
     */
    private fun createBestEffortSession(): SpellCheckerSession? {
        try {
            val active = tsm.newSpellCheckerSession(null, null, this, true)
            if (active != null) {
                AppLog.log("spell", "session du correcteur ACTIF (langue des Réglages) créée ; note : $locale")
                return active
            }
        } catch (e: Exception) {
            AppLog.log("spell", "session du correcteur actif impossible", e)
        }
        try {
            val forced = tsm.newSpellCheckerSession(null, locale, this, false)
            if (forced != null) {
                AppLog.log(
                    "spell",
                    "session locale forcée ($locale) — attention : son dictionnaire peut être vide"
                )
                return forced
            }
        } catch (e: Exception) {
            AppLog.log("spell", "création de session impossible pour $locale", e)
        }
        return null
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // non utilisé : on passe par getSentenceSuggestions
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val misspelled = mutableListOf<MisspelledWord>()
        var checkedWords = 0
        var notFoundWords = 0
        results?.forEach { sentenceResult ->
            for (i in 0 until sentenceResult.suggestionsCount) {
                val info = sentenceResult.getSuggestionsInfoAt(i)
                val attrs = info.suggestionsAttributes
                checkedWords++
                // Détection d'un « dictionnaire vide » : tous les mots, y
                // compris les bons, reviennent en LOOKUP_NOT_FOUND sans
                // être marqués IN_THE_DICTIONARY → le correcteur ne peut
                // rien confirmer ni rien réfuter.
                // 0x0001 = RESULT_ATTR_LOOKUP_NOT_FOUND (non exposé comme
                // constante publique dans l'API, d'où le littéral)
                if ((attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0 &&
                    (attrs and 0x0001) != 0
                ) {
                    notFoundWords++
                }
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
        if (checkedWords > 0 && notFoundWords == checkedWords) {
            AppLog.log(
                "spell",
                "dictionnaire probablement VIDE : $notFoundWords/$checkedWords mots sans décision " +
                        "(ni fautes ni mots reconnus) — la session forcée ne charge pas de dictionnaire"
            )
        }
        pendingCallback?.invoke(misspelled)
    }

    fun close() {
        session?.close()
        session = null
    }
}