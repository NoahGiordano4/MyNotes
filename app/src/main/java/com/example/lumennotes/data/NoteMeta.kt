package com.example.lumennotes.data

/** Métadonnées d'une note (le contenu des pages vit dans des fichiers séparés). */
class NoteMeta(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var pageCount: Int
)