package com.nadrlab.visionai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageProcessor {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 85

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            // Read dimensions only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // Calculate sample size
            val scale = calculateScale(options.outWidth, options.outHeight)
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, loadOptions)
            } ?: return null

            // Fix rotation
            val rotated = fixRotation(context, uri, bitmap)

            // Resize if still too large
            resize(rotated)
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun resize(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return bitmap

        val ratio = minOf(MAX_DIMENSION.toFloat() / w, MAX_DIMENSION.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun calculateScale(width: Int, height: Int): Int {
        var scale = 1
        while (width / scale > MAX_DIMENSION * 2 || height / scale > MAX_DIMENSION * 2) {
            scale *= 2
        }
        return scale
    }

    private fun fixRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            input.close()

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }
}
