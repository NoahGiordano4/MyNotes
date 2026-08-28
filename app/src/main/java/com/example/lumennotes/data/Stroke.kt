package com.example.lumennotes.data

/**
 * Un trait d'encre : couleur + taille de plume + points
 * [x, y, pression, x, y, pression, …] en coordonnées page
 * (page A4 = 794 × 1123 unités, soit 210 × 297 mm à 96 dpi).
 *
 * times (OPTIONNEL — format v2) : timestamp en millisecondes de chaque
 * point, tiré de l'horloge des MotionEvent. Destiné à la reconnaissance
 * d'écriture (ML Kit). Null pour les traits v1 : tout le reste fonctionne
 * sans — rétrocompatible par construction.
 */
class Stroke(
    val color: Int,
    val size: Float,
    val points: FloatArray,
    val times: LongArray? = null
) {
    val pointCount: Int get() = points.size / 3
}

/** Un trait effacé, avec sa position d'origine dans la page (pour l'undo). */
class EraseHit(
    val stroke: Stroke,
    val index: Int
)