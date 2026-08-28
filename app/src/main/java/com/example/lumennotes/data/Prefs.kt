package com.example.lumennotes.data

import android.content.Context

/**
 * Réglages de l'application (SharedPreferences).
 *  - MODE_STYLUS : écriture au stylet uniquement, le doigt déplace la page.
 *  - MODE_FINGER : stylet ET doigt ; 2 doigts = zoom/déplacement.
 */
object Prefs {
    const val MODE_STYLUS = "stylus"
    const val MODE_FINGER = "finger"

    private const val FILE = "settings"
    private const val KEY_INPUT = "inputMode"

    fun inputMode(context: Context): String {
        val v = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_INPUT, MODE_STYLUS)
        return if (v == MODE_FINGER) MODE_FINGER else MODE_STYLUS
    }

    fun setInputMode(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INPUT, value)
            .apply()
    }
}