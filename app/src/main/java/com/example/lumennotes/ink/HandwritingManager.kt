package com.example.lumennotes.ink

import com.example.lumennotes.data.Stroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink

/**
 * Gère la reconnaissance d'écriture via Google ML Kit avec support multi-langues.
 */
object HandwritingManager {

    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val modelManager = RemoteModelManager.getInstance()

    /**
     * Prépare le modèle pour une langue donnée (ex: "fr-FR").
     */
    fun setup(lang: String, onReady: () -> Unit, onError: (Exception) -> Unit) {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang)
        if (modelIdentifier == null) {
            onError(IllegalArgumentException("Langue non supportée : $lang"))
            return
        }

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()

        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    initRecognizer(lang, model)
                    onReady()
                } else {
                    downloadModel(lang, model, onReady, onError)
                }
            }
            .addOnFailureListener(onError)
    }

    private fun downloadModel(lang: String, model: DigitalInkRecognitionModel, onReady: () -> Unit, onError: (Exception) -> Unit) {
        val conditions = DownloadConditions.Builder().build()
        modelManager.download(model, conditions)
            .addOnSuccessListener {
                initRecognizer(lang, model)
                onReady()
            }
            .addOnFailureListener(onError)
    }

    private fun initRecognizer(lang: String, model: DigitalInkRecognitionModel) {
        if (!recognizers.containsKey(lang)) {
            val options = DigitalInkRecognizerOptions.builder(model).build()
            recognizers[lang] = DigitalInkRecognition.getClient(options)
        }
    }

    /**
     * Transcrit une liste de traits en texte pour une langue donnée.
     */
    fun transcribe(lang: String, strokes: List<Stroke>, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val inkBuilder = Ink.builder()
        
        for (stroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            val pts = stroke.points
            val times = stroke.times
            val count = pts.size / 3
            
            for (i in 0 until count) {
                val x = pts[i * 3]
                val y = pts[i * 3 + 1]
                val t = times?.getOrNull(i) ?: (i * 10L)
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        val engine = recognizers[lang]
        if (engine == null) {
            // Tentative de setup automatique si pas encore fait
            setup(lang, {
                transcribe(lang, strokes, onResult, onError)
            }, onError)
            return
        }

        engine.recognize(inkBuilder.build())
            .addOnSuccessListener { result ->
                val text = result.candidates.firstOrNull()?.text ?: ""
                onResult(text)
            }
            .addOnFailureListener(onError)
    }
}
