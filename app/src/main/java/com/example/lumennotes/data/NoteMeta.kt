package com.example.lumennotes.data

/** Métadonnées d'une note (le contenu des pages vit dans des fichiers séparés). */
class NoteMeta(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var pageCount: Int,
    var pageLanguages: MutableList<String> = mutableListOf()
) {
    fun getLanguageForPage(index: Int): String {
        return if (index < pageLanguages.size) pageLanguages[index] else "fr-FR"
    }

    fun setLanguageForPage(index: Int, lang: String) {
        while (pageLanguages.size <= index) pageLanguages.add("fr-FR")
        pageLanguages[index] = lang
    }
}