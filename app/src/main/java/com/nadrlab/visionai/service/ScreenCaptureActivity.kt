package com.nadrlab.visionai.service

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Invisible activity whose only job is to show the system "start recording/casting" permission dialog. */
class ScreenCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            try {
                ScreenCaptureService.start(applicationContext, result.resultCode, data)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to start capture service", error)
                ScreenReaderService.onCaptureUnavailable("تعذر بدء خدمة الالتقاط: ${error.message}")
            }
        } else {
            ScreenReaderService.onCaptureUnavailable(null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // dialog already launched before a recreation
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createScreenCaptureIntent())
        } catch (error: Exception) {
            Log.e(TAG, "Unable to show capture permission", error)
            ScreenReaderService.onCaptureUnavailable("تعذر عرض نافذة إذن التقاط الشاشة: ${error.message}")
            finish()
        }
    }

    private companion object {
        const val TAG = "ScreenCaptureActivity"
    }
}
