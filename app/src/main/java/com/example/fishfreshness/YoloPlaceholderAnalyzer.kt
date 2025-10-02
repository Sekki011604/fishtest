package com.example.fishfreshness

import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Placeholder analyzer:
 * - receives ImageProxy frames from CameraX
 * - does minimal light processing (extracts simple info) and invokes callback
 * - replace the body of `processImageProxy` with your YOLOv8 inference code later
 */
class YoloPlaceholderAnalyzer(
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val TAG = "YoloAnalyzer"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun analyze(imageProxy: ImageProxy) {
        // offload heavy work to coroutine to avoid blocking camera thread
        scope.launch {
            val info = processImageProxy(imageProxy)
            onResult(info)
            imageProxy.close() // don't forget to close
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy): String {
        // Example minimal info: image size + average luminance
        val img = imageProxy.image ?: return "No image"
        val width = imageProxy.width
        val height = imageProxy.height

        // compute quick average luminance from Y plane (fast, cheap)
        val yPlane = img.planes[0].buffer
        val avg = averageByteBuffer(yPlane)

        // This is where you'll call YOLOv8 inference (TFLite / PyTorch Mobile / On-device)
        // e.g. val detections = runYoloOnByteBuffer(...)

        return "W:${width} H:${height} | Yavg:${avg.toInt()}"
    }

    private fun averageByteBuffer(buffer: ByteBuffer): Float {
        buffer.rewind()
        var sum = 0L
        var count = 0
        while (buffer.hasRemaining()) {
            val v = buffer.get().toInt() and 0xFF
            sum += v
            count++
            // limit the reads so it runs fast
            if (count >= 1024) break
        }
        return if (count == 0) 0f else sum.toFloat() / min(count, 1024)
    }
}
