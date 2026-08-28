package com.example.lumennotes.ink

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.lumennotes.data.Stroke
import com.example.lumennotes.util.AppLog
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea

/**
 * Reconnaissance d'écriture via ML Kit Digital Ink — version instrumentée.
 *
 *  - précharge le modèle dès qu'on le demande (preload), avec file
 *    d'attente : les demandes arrivées pendant le téléchargement sont
 *    rejouées dès qu'il est prêt ;
 *  - journalise CHAQUE étape via AppLog (tag « reco ») : état du modèle,
 *    téléchargement, reconnaissances lancées, candidats, erreurs ;
 *  - remonte les erreurs à l'appelant — plus jamais d'échec silencieux ;
 *  - synthétise des timestamps réalistes pour les traits sans `t`
 *    (écart de lever de plume entre traits, ~16 ms par point) ;
 *  - v2 : transmet à ML Kit la zone d'écriture réelle (RecognitionContext
 *    + WritingArea) — aide le modèle à distinguer majuscules/minuscules
 *    et la ponctuation ;
 *  - auto-guérison : si recognize() échoue, le recognizer est jeté et le
 *    modèle re-vérifié au prochain appel.
 */
object HandwritingManager {

    private const val TAG = "reco"

    private val main = Handler(Looper.getMainLooper())
    private val modelManager = RemoteModelManager.getInstance()

    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val downloading = HashSet<String>()
    private val waiting = HashMap<String, MutableList<() -> Unit>>()

    /** Le modèle de cette langue est-il prêt à reconnaître ? */
    fun isReady(lang: String): Boolean = recognizers.containsKey(lang)

    /**
     * Précharge le modèle de la langue — à appeler dès l'ouverture de
     * l'éditeur. Sans danger si appelé plusieurs fois : les demandes
     * pendant un téléchargement en cours sont mises en file et rejouées.
     */
    fun preload(
        lang: String,
        onReady: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null,
    ) {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang)
        if (identifier == null) {
            val e = IllegalArgumentException("Langue non supportée : $lang")
            AppLog.log(TAG, "identifiant de langue invalide : $lang", e)
            onError?.invoke(e)
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()

        if (recognizers.containsKey(lang)) {
            onReady?.invoke()
            return
        }

        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    initRecognizer(lang, model)
                    AppLog.log(TAG, "modèle $lang déjà téléchargé — prêt ✔")
                    flushWaiting(lang)
                    onReady?.invoke()
                } else {
                    startDownload(lang, model, onReady, onError)
                }
            }
            .addOnFailureListener { e ->
                // typique : Google Play Services absent ou trop ancien
                AppLog.log(TAG, "isModelDownloaded($lang) a échoué — Play Services ?", e)
                onError?.invoke(e)
            }
    }

    private fun startDownload(
        lang: String,
        model: DigitalInkRecognitionModel,
        onReady: (() -> Unit)?,
        onError: ((Exception) -> Unit)?,
    ) {
        if (downloading.contains(lang)) {
            onReady?.let { waiting.getOrPut(lang) { ArrayList() }.add(it) }
            return
        }
        downloading.add(lang)
        AppLog.log(TAG, "téléchargement du modèle $lang… — première fois : connexion requise, cela peut prendre plusieurs secondes")
        modelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                downloading.remove(lang)
                initRecognizer(lang, model)
                AppLog.log(TAG, "modèle $lang téléchargé — prêt ✔")
                flushWaiting(lang)
                onReady?.invoke()
            }
            .addOnFailureListener { e ->
                downloading.remove(lang)
                AppLog.log(TAG, "échec du téléchargement du modèle $lang", e)
                onError?.invoke(e)
            }
    }

    private fun initRecognizer(lang: String, model: DigitalInkRecognitionModel) {
        if (!recognizers.containsKey(lang)) {
            recognizers[lang] = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
        }
    }

    private fun flushWaiting(lang: String) {
        val queue = waiting.remove(lang) ?: return
        for (r in queue) main.post { r() }
    }

    /**
     * Transcrit une liste de traits. Si le modèle n'est pas encore prêt,
     * la demande est mise en file et rejouée dès qu'il l'est — ou l'erreur
     * est remontée.
     */
    fun transcribe(
        lang: String,
        strokes: List<Stroke>,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (strokes.isEmpty()) {
            onResult("")
            return
        }
        val engine = recognizers[lang]
        if (engine == null) {
            AppLog.log(TAG, "modèle $lang pas prêt — demande mise en file, préchargement lancé")
            preload(
                lang,
                onReady = { transcribe(lang, strokes, onResult, onError) },
                onError = onError
            )
            return
        }

        val ink = buildInk(strokes)
        val pointTotal = strokes.sumOf { it.pointCount }
        val hasTimes = strokes.any { it.times != null }
        AppLog.log(TAG, "reconnaissance lancée : ${strokes.size} traits, $pointTotal points, temps ${if (hasTimes) "réels" else "synthétiques"}")

        // Zone d'écriture réelle, dans le même repère que les points :
        // améliore la distinction majuscule/minuscule et la ponctuation.
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (stroke in strokes) {
            val pts = stroke.points
            var i = 0
            while ((i + 1) < pts.size) {
                val x = pts[i]
                val y = pts[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 3
            }
        }
        val area = WritingArea(
            maxOf(1f, maxX - minX),
            maxOf(1f, maxY - minY)
        )
        val recognitionContext = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(area)
            .build()

        engine.recognize(ink, recognitionContext)
            .addOnSuccessListener { result ->
                val text = result.candidates.firstOrNull()?.text ?: ""
                AppLog.log(TAG, "résultat : ${result.candidates.size} candidat(s) → « $text »")
                main.post { onResult(text) }
            }
            .addOnFailureListener { e ->
                AppLog.log(TAG, "recognize() a échoué pour $lang", e)
                // auto-guérison : on jette, le modèle sera re-vérifié au prochain appel
                recognizers.remove(lang)
                main.post { onError(e) }
            }
    }

    /** Construit l'Ink ML Kit, avec des timestamps réalistes si absents. */
    private fun buildInk(strokes: List<Stroke>): Ink {
        val inkBuilder = Ink.builder()
        // base de temps réaliste quand les traits n'ont pas de timestamps :
        // ML Kit segmente les mots grâce aux écarts temporels entre traits
        var synth = SystemClock.uptimeMillis()
        for (stroke in strokes) {
            val sb = Ink.Stroke.builder()
            val pts = stroke.points
            val times = stroke.times
            val count = pts.size / 3
            synth += 200L   // écart entre traits ≈ lever de plume
            for (i in 0 until count) {
                val t = times?.getOrNull(i) ?: (synth + i * 16L)
                sb.addPoint(Ink.Point.create(pts[i * 3], pts[i * 3 + 1], t))
            }
            inkBuilder.addStroke(sb.build())
        }
        return inkBuilder.build()
    }
}