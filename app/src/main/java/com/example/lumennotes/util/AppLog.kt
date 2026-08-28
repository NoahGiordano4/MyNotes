package com.example.lumennotes.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Journal de diagnostic LumenNotes.
 *
 * Objectif : comprendre les fermetures inexpliquées (plantage, mémoire,
 * thread principale bloquée). Conçu pour être INVISIBLE à l'usage :
 *  - les messages vont dans un tampon circulaire EN MÉMOIRE (aucune écriture
 *    disque pendant que l'app tourne, aucun coût pour le thread UI) ;
 *  - au moment d'un plantage, le tampon + la pile d'appel sont écrits dans
 *    files/logs/crash-<date>.txt AVANT que le système ferme l'app ;
 *  - une sentinelle détecte les blocages du thread principal (type ANR —
 *    le système ferme alors l'app sans aucun plantage Java visible) ;
 *  - les alertes mémoire du système (pression, mémoire critique) sont tracées ;
 *  - LoggedExecutor empêche la mort silencieuse du thread de sauvegarde :
 *    toute exception d'E/S est journalisée puis contenue, au lieu de tuer
 *    le thread et de faire planter les sauvegardes suivantes.
 */
object AppLog {

    private const val TAG = "LumenLog"
    private const val MAX_LINES = 600
    private const val MAX_CRASH_FILES = 5
    private const val ANR_CHECK_MS = 4_000L
    private const val ANR_THRESHOLD_MS = 8_000L

    private val buffer = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var appContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var anrReported = false

    /* --------------------------- installation --------------------------- */

    /** À appeler une seule fois, au démarrage (ex. MenuActivity.onCreate). */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashFile("PLANTAGE (thread ${thread.name})", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        appContext?.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                log("mem", "pression mémoire système (niveau $level)")
            }

            override fun onLowMemory() {
                log("mem", "mémoire critique")
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })

        startAnrWatchdog()
        log("app", "démarrage — version ${appVersion()}")
    }

    /* ------------------------------ journal ----------------------------- */

    fun log(tag: String, message: String, error: Throwable? = null) {
        val line = "${timeFmt.format(Date())} [$tag] $message" +
                (error?.let { " ← ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        android.util.Log.d(TAG, line)
        if (error != null) android.util.Log.w(TAG, line, error)
    }

    /* -------------------------- exécuteur sûr --------------------------- */

    class LoggedExecutor(name: String) {
        private val delegate = Executors.newSingleThreadExecutor { r -> Thread(r, name) }

        /**
         * Exécute la tâche sur le thread dédié ; une éventuelle exception
         * est journalisée — elle ne tue PAS le thread (plus de cascade de
         * plantages sur les sauvegardes suivantes).
         */
        fun execute(block: () -> Unit) {
            delegate.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    log("io", "tâche de fond échouée", t)
                }
            }
        }
    }

    fun loggedExecutor(name: String): LoggedExecutor = LoggedExecutor(name)

    /* --------------------------- sentinelle ANR ------------------------- */

    private fun startAnrWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val lastBeat = AtomicLong(System.currentTimeMillis())

        Thread({
            while (true) {
                try {
                    Thread.sleep(ANR_CHECK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val before = lastBeat.get()
                main.post { lastBeat.set(System.currentTimeMillis()) }
                try {
                    Thread.sleep(ANR_CHECK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val blockedMs = System.currentTimeMillis() - before
                if (blockedMs > ANR_THRESHOLD_MS) {
                    if (!anrReported) {
                        anrReported = true
                        log("anr", "thread principale bloquée ~${blockedMs / 1000} s !")
                        writeCrashFile(
                            "THREAD PRINCIPALE BLOQUÉE ~${blockedMs / 1000} s (type ANR)",
                            null
                        )
                    }
                } else {
                    anrReported = false
                }
            }
        }, "anr-watchdog").start()
    }

    /* ------------------------ rapports de plantage ---------------------- */

    fun hasCrashReport(context: Context): Boolean =
        crashFiles(context).isNotEmpty()

    fun latestCrashReport(context: Context): String? =
        crashFiles(context).maxByOrNull { it.name }?.let {
            try {
                it.readText()
            } catch (_: Throwable) {
                null
            }
        }

    /** Ouvre le menu de partage Android avec le dernier rapport. */
    fun share(context: Context) {
        val body = latestCrashReport(context)
            ?: "(aucun rapport de plantage)\n\n--- derniers événements ---\n" +
            synchronized(buffer) { buffer.joinToString("\n") }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Rapport LumenNotes")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Partager le rapport"))
    }

    private fun crashFiles(context: Context): List<File> =
        File(context.filesDir, "logs").apply { mkdirs() }
            .listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun writeCrashFile(title: String, error: Throwable?) {
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            val old = dir.listFiles { f -> f.name.startsWith("crash-") }
                ?.sortedBy { it.name } ?: emptyList()
            if (old.size >= MAX_CRASH_FILES) {
                old.take(old.size - MAX_CRASH_FILES + 1).forEach { it.delete() }
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.appendLine("=== $title ===")
            sb.appendLine("date : ${Date()}   version : ${appVersion()}")
            error?.let { sb.appendLine(android.util.Log.getStackTraceString(it)) }
            sb.appendLine()
            sb.appendLine("--- derniers événements (${buffer.size}) ---")
            synchronized(buffer) { buffer.forEach { sb.appendLine(it) } }
            File(dir, "crash-$stamp.txt").writeText(sb.toString())
        } catch (_: Throwable) {
            // ne jamais planter dans le gestionnaire de plantage
        }
    }

    private fun appVersion(): String = try {
        val ctx = appContext!!
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}