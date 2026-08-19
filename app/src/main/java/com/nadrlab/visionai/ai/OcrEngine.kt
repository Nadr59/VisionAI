package com.nadrlab.visionai.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.arabic.ArabicTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object OcrEngine {

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val arabicRecognizer = TextRecognition.getClient(ArabicTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): String {
        val latin = runRecognizer(bitmap, latinRecognizer)
        val arabic = runRecognizer(bitmap, arabicRecognizer)

        return when {
            latin.length > arabic.length -> latin
            arabic.length > latin.length -> arabic
            else -> latin
        }.ifEmpty { "لم يتم اكتشاف نص في الصورة" }
    }

    private suspend fun runRecognizer(
        bitmap: Bitmap,
        recognizer: com.google.mlkit.vision.text.TextRecognizer
    ): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(result.text)
            }
            .addOnFailureListener {
                cont.resume("")
            }
    }
}
