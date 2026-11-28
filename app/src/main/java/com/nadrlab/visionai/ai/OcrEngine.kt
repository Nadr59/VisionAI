package com.nadrlab.visionai.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object OcrEngine {

    /** A block of recognized text with its position in the bitmap (= screen pixels for screenshots). */
    data class OcrBlock(val text: String, val bounds: Rect)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.ifEmpty { "No text detected in image" })
                }
                .addOnFailureListener {
                    cont.resume("OCR failed: ${it.message}")
                }
        }
    }

    /** Blocking: call from a worker thread only. */
    fun recognizeBlocks(bitmap: Bitmap): List<OcrBlock> {
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS
        )
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            OcrBlock(block.text, Rect(box))
        }
    }
}
