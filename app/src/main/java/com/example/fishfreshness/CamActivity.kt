package com.example.fishfreshness

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CamActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var imgUploaded: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var tvPrediction: TextView
    private lateinit var btnUpload: Button
    private lateinit var btnScan: Button

    private val TAG = "CamActivity"
    private val IMAGE_SIZE = 640
    private val LABELS_FILE = "labels.txt"

    private var interpreterBest: Interpreter? = null
    private var interpreterLast: Interpreter? = null
    private var labels: List<String> = emptyList()

    private val reqExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requestedParts = listOf("eye", "caudal_fin", "pectoral_fin", "skin_texture")

    // CameraX state
    private var cameraProvider: ProcessCameraProvider? = null
    private var hasShownLiveResult: Boolean = false

    // ------------------ IMAGE PICKER ------------------
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val uri: Uri? = res.data!!.data
            uri?.let {
                try {
                    // Use a robust decoder that works with more URI types (cloud, documents, large images, etc.)
                    val bitmap = loadBitmapFromUri(uri)
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to decode selected image")
                        tvPrediction.text = "Unable to load selected image"
                        return@let
                    }

                    imgUploaded.setImageBitmap(bitmap)
                    imgUploaded.visibility = ImageView.VISIBLE
                    previewView.visibility = PreviewView.GONE

                    imgUploaded.post {
                        val dispRect = computeImageViewDisplayRect(imgUploaded, bitmap)
                        reqExecutor.execute {
                            val (boxes, labelsOut) = detectWithBothModels(bitmap)
                            Log.d(TAG, "Gallery detection - boxes: ${boxes.size}, labels: $labelsOut")
                            val labelColors = computeLabelColors(bitmap, boxes)
                            runOnUiThread {
                                if (labelsOut.isNotEmpty()) {
                                    // Show detections normally
                                    overlayView.setResults(boxes, labelsOut, bitmap.width, bitmap.height, dispRect, labelColors)

                                    val predictedShelfLife = ShelfLifePredictor.predictShelfLife(labelsOut)
                                    val partsDetected = labelsOut.size
                                    val note = if (partsDetected < requestedParts.size) {
                                        "\nNote: Some fish parts were not detected, so this prediction may be less accurate."
                                    } else {
                                        ""
                                    }
                                    tvPrediction.text = "Shelf-life prediction: $predictedShelfLife$note"
                                    tvPrediction.setTextColor(Color.BLACK)
                                } else {
                                    // No parts detected: clear overlay and text
                                    overlayView.setResults(emptyList(), emptyList(), bitmap.width, bitmap.height, dispRect, emptyList())
                                    tvPrediction.text = ""
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "load image failed", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cam_activity)

        previewView = findViewById(R.id.previewView)
        imgUploaded = findViewById(R.id.imgUploaded)
        overlayView = findViewById(R.id.overlayView)
        tvPrediction = findViewById(R.id.tvPrediction)
        btnUpload = findViewById(R.id.btnUpload)
        btnScan = findViewById(R.id.btnScan)

        // Ensure the camera preview preserves the same aspect ratio as the
        // analyzed bitmap (no center-crop). This keeps bounding boxes aligned
        // with the actual fish parts instead of being shifted into the background.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        btnUpload.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        btnScan.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 123)
            } else {
                imgUploaded.visibility = ImageView.GONE
                previewView.visibility = PreviewView.VISIBLE
                startCamera()
            }
        }

        // Initialize shelf-life predictor (loads fish_model.tflite if present)
        ShelfLifePredictor.init(assets)

        // Load labels
        try {
            labels = loadLabels(LABELS_FILE)
        } catch (e: Exception) {
            labels = emptyList()
            Log.w(TAG, "labels load failed: ${e.message}")
        }

        // Load models
        try {
            // Use the int8-quantized "best" model as requested; keep "last" as-is.
            interpreterBest = Interpreter(loadModelFile("best_int8.tflite"))
            interpreterLast = Interpreter(loadModelFile("last_float32.tflite"))
            tvPrediction.text = "Models loaded"
        } catch (e: Exception) {
            interpreterBest = null
            interpreterLast = null
            tvPrediction.text = "Model load failed"
            Log.e(TAG, "Model load error", e)
        }
    }

    // ------------------ UTILS ------------------
    /**
     * Robustly load a bitmap from a content URI, downscaling large images so they fit in memory.
     * This avoids issues with some gallery / cloud providers that `MediaStore.Images.Media.getBitmap` cannot handle.
     */
    private fun loadBitmapFromUri(uri: Uri, maxDim: Int = 2048): Bitmap? {
        return try {
            // First decode only bounds to compute an inSampleSize
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) {
                Log.e(TAG, "Invalid image bounds for uri=$uri (w=$origW, h=$origH)")
                return null
            }

            var sampleSize = 1
            val largestDim = kotlin.math.max(origW, origH)
            while (largestDim / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmapFromUri error for uri=$uri", e)
            null
        }
    }

    private fun computeImageViewDisplayRect(iv: ImageView, bmp: Bitmap): RectF {
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        if (vw == 0f || vh == 0f) return RectF(0f, 0f, vw, vh)
        val scale = min(vw / iw, vh / ih)
        val dispW = iw * scale
        val dispH = ih * scale
        val left = (vw - dispW) / 2f
        val top = (vh - dispH) / 2f
        return RectF(left, top, left + dispW, top + dispH)
    }

    private data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val dx: Int, val dy: Int)
    private fun letterboxImage(src: Bitmap, targetSize: Int): LetterboxResult {
        val scale = min(targetSize.toFloat() / src.width, targetSize.toFloat() / src.height)
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val p = Paint()
        p.color = Color.BLACK
        canvas.drawRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), p)
        val dx = (targetSize - newW) / 2
        val dy = (targetSize - newH) / 2
        canvas.drawBitmap(resized, dx.toFloat(), dy.toFloat(), null)
        return LetterboxResult(output, scale, dx, dy)
    }

    private data class RawDet(val box: RectF, val cls: Int, val score: Float)

    // Simple class-wise Non-Maximum Suppression to reduce duplicate boxes.
    // Keeps high-score boxes and removes others that highly overlap with them.
    private fun nmsPerPart(dets: List<RawDet>, iouThresh: Float = 0.5f): List<RawDet> {
        if (dets.isEmpty()) return dets
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<RawDet>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)

            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                val iou = computeIoU(best.box, other.box)
                if (iou > iouThresh) {
                    it.remove()
                }
            }
        }
        return kept
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }

    /**
     * Compute adaptive text colors for each detection box based on the brightness
     * of the area where the label text will be drawn.
     * Dark background -> white text, light background -> black text.
     */
    private fun computeLabelColors(bitmap: Bitmap, boxes: List<RectF>): List<Int> {
        val result = mutableListOf<Int>()
        if (boxes.isEmpty()) return result

        val sampleWidth = 32
        val sampleHeight = 16

        for (box in boxes) {
            // Sample near the top-left of the box where the text is drawn
            val startX = box.left.toInt().coerceIn(0, bitmap.width - 1)
            val startY = (box.top - 10f).toInt().coerceIn(0, bitmap.height - 1)

            var sumLuma = 0f
            var count = 0

            val endX = kotlin.math.min(startX + sampleWidth, bitmap.width)
            val endY = kotlin.math.min(startY + sampleHeight, bitmap.height)

            for (y in startY until endY) {
                for (x in startX until endX) {
                    val c = bitmap.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    // Standard luminance approximation
                    val luma = 0.299f * r + 0.587f * g + 0.114f * b
                    sumLuma += luma
                    count++
                }
            }

            val avgLuma = if (count > 0) sumLuma / count else 255f
            // Threshold at mid-gray (128): darker background -> white text, lighter -> black text
            val color = if (avgLuma < 128f) Color.WHITE else Color.BLACK
            result.add(color)
        }

        return result
    }

    // ------------------ DETECTION ------------------
    private fun detectWithBothModels(bitmap: Bitmap): Pair<List<RectF>, List<String>> {
        val detBest = detectWithInterpreter(bitmap, interpreterBest)
        val detLast = detectWithInterpreter(bitmap, interpreterLast)
        return ensembleDetections(detBest.first, detLast.first)
    }

    private fun detectWithInterpreter(bitmap: Bitmap, interp: Interpreter?): Pair<List<RawDet>, List<String>> {
        if (interp == null) return Pair(emptyList(), emptyList())

        val lb = letterboxImage(bitmap, IMAGE_SIZE)
        val inpBmp = lb.bitmap

        val input = Array(1) { Array(IMAGE_SIZE) { Array(IMAGE_SIZE) { FloatArray(3) } } }
        for (y in 0 until IMAGE_SIZE) {
            for (x in 0 until IMAGE_SIZE) {
                val px = inpBmp.getPixel(x, y)
                input[0][y][x][0] = (px shr 16 and 0xFF) / 255f
                input[0][y][x][1] = (px shr 8 and 0xFF) / 255f
                input[0][y][x][2] = (px and 0xFF) / 255f
            }
        }

        val output = Array(1) { Array(20) { FloatArray(8400) } }
        interp.run(input, output)

        val numClasses = 20 - 4
        val rawDetections = ArrayList<RawDet>(400)

        for (i in 0 until 8400) {
            val cx = output[0][0][i] * IMAGE_SIZE
            val cy = output[0][1][i] * IMAGE_SIZE
            val w = output[0][2][i] * IMAGE_SIZE
            val h = output[0][3][i] * IMAGE_SIZE

            val scores = FloatArray(numClasses) { c -> output[0][4 + c][i] }
            val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: -1
            val score = if (maxIdx >= 0) scores[maxIdx] else 0f

            // Single best class per anchor (standard YOLO behaviour). Threshold here is
            // deliberately low; stricter, per-part thresholds are applied in ensembleDetections.
            if (score > 0.2f && maxIdx >= 0) {
                val bx1 = cx - w / 2f
                val by1 = cy - h / 2f
                val bx2 = cx + w / 2f
                val by2 = cy + h / 2f

                val left = max(0f, (bx1 - lb.dx) / lb.scale)
                val top = max(0f, (by1 - lb.dy) / lb.scale)
                val right = min(bitmap.width.toFloat(), (bx2 - lb.dx) / lb.scale)
                val bottom = min(bitmap.height.toFloat(), (by2 - lb.dy) / lb.scale)

                if ((right - left) > 5 && (bottom - top) > 5) {
                    rawDetections.add(RawDet(RectF(left, top, right, bottom), maxIdx, score))
                }
            }
        }
        return Pair(rawDetections, emptyList())
    }

    private fun ensembleDetections(det1: List<RawDet>, det2: List<RawDet>): Pair<List<RectF>, List<String>> {
        val combinedBoxes = mutableListOf<RectF>()
        val combinedLabels = mutableListOf<String>()

        Log.d(TAG, "ensembleDetections - det1: ${det1.size}, det2: ${det2.size}")
        Log.d(TAG, "Available labels: $labels")
        Log.d(TAG, "Requested parts: $requestedParts")

        for (part in requestedParts) {
            // Part-specific score thresholds.
            // Raise thresholds so detections are more "sure" and reduce over-acting boxes.
            val partThreshold = when (part) {
                "eye" -> 0.3f
                "skin_texture" -> 0.7f
                "caudal_fin", "pectoral_fin" -> 0.3f
                else -> 0.6f
            }

            // Map model labels to this anatomical part (eye, fin, skin), case-insensitive.
            val partLower = part.lowercase()
            val indicesForPart = labels.mapIndexedNotNull { idx, lbl ->
                val lower = lbl.lowercase()
                if (lower.endsWith("_$partLower") || lower.contains(partLower)) idx else null
            }
            Log.d(TAG, "Part '$part' indices (flexible match): $indicesForPart")

            // Only keep detections for this part whose confidence is above the part-specific threshold
            val rawCandidates = (det1 + det2)
                .filter { it.cls in indicesForPart && it.score >= partThreshold }
            Log.d(TAG, "Raw candidates for '$part' (>= threshold $partThreshold): ${rawCandidates.size}")

            // Apply stricter NMS so overlapping boxes collapse more aggressively.
            val candidates = nmsPerPart(rawCandidates, iouThresh = 0.4f)
            Log.d(TAG, "Post-NMS candidates for '$part': ${candidates.size}")

            // Keep only the single strongest box per part by default to avoid
            // multiple detections "over-acting" on a single fish.
            val maxPerPart = 1
            for (det in candidates.sortedByDescending { it.score }.take(maxPerPart)) {
                combinedBoxes.add(det.box)
                val labelName = if (det.cls < labels.size) labels[det.cls] else "Class ${det.cls}"
                combinedLabels.add("$labelName ${(det.score * 100).toInt()}%")
                Log.d(TAG, "Added detection for part '$part': $labelName with score ${det.score}")
            }
        }
        Log.d(TAG, "Final ensemble result - boxes: ${combinedBoxes.size}, labels: $combinedLabels")
        return Pair(combinedBoxes, combinedLabels)
    }

    // ------------------ CAMERAX ------------------
    private fun startCamera() {
        hasShownLiveResult = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(reqExecutor) { imageProxy ->
                        // If we've already produced a stable live result, skip further analysis
                        // so bounding boxes and text stay fixed on the scanned parts.
                        if (hasShownLiveResult) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        try {
                            val bmp = imageProxyToBitmap(imageProxy)
                            if (bmp != null) {
                                val (boxes, labelsOut) = detectWithBothModels(bmp)
                                Log.d(TAG, "Camera detection - boxes: ${boxes.size}, labels: $labelsOut")
                                val labelColors = computeLabelColors(bmp, boxes)
                                runOnUiThread {
                                    try {
                                        if (labelsOut.isNotEmpty()) {
                                            // Update overlay normally when we have detections
                                            overlayView.setResults(boxes, labelsOut, bmp.width, bmp.height, null, labelColors)

                                            // Once we have at least one detected part, "lock in" the result:
                                            // freeze further analysis, keep the current boxes/text,
                                            // and also pop out a dialog with the same description.
                                            val predictedShelfLife = ShelfLifePredictor.predictShelfLife(labelsOut)
                                            val partsDetected = labelsOut.size
                                            val note = if (partsDetected < requestedParts.size) {
                                                "\nNote: Some fish parts were not detected, so this prediction may be less accurate."
                                            } else {
                                                ""
                                            }
                                            val message = "Shelf-life prediction: $predictedShelfLife$note"
                                            tvPrediction.text = message
                                            tvPrediction.setTextColor(Color.BLACK)
                                            showResultDialog(message)
                                            hasShownLiveResult = true
                                        } else {
                                            // No parts detected: clear overlay and text
                                            overlayView.setResults(emptyList(), emptyList(), bmp.width, bmp.height, null, emptyList())
                                            tvPrediction.text = ""
                                        }
                                    } catch (uiEx: Exception) {
                                        Log.e(TAG, "UI update error in analyzer", uiEx)
                                    }
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "Analyzer error", ex)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "bind camera failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val baos = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, baos)
            val bytes = baos.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "imageProxyToBitmap error", e)
            null
        }
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "stopCamera error", e)
        }
    }

    // ------------------ ASSET LOADERS ------------------
    private fun loadModelFile(name: String): MappedByteBuffer {
        val fd = assets.openFd(name)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun loadLabels(filename: String): List<String> {
        val out = mutableListOf<String>()
        assets.open(filename).bufferedReader().useLines { lines -> lines.forEach { out.add(it.trim()) } }
        return out
    }

    private fun showResultDialog(predictedShelfLife: String) {
        // Activity might already be finishing or destroyed; avoid WindowManager crash.
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Shelf-life prediction")
            .setMessage(predictedShelfLife)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                // Clear previous bounding boxes and text, and resume live scanning
                // for the next fish.
                overlayView.setResults(emptyList(), emptyList(), 1, 1, null, emptyList())
                tvPrediction.text = ""
                hasShownLiveResult = false
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        reqExecutor.shutdown()
        interpreterBest?.close()
        interpreterLast?.close()
        ShelfLifePredictor.close()
    }
}
