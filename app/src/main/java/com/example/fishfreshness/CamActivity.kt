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

    // ------------------ IMAGE PICKER ------------------
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val uri: Uri? = res.data!!.data
            uri?.let {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    imgUploaded.setImageBitmap(bitmap)
                    imgUploaded.visibility = ImageView.VISIBLE
                    previewView.visibility = PreviewView.GONE

                    imgUploaded.post {
                        val dispRect = computeImageViewDisplayRect(imgUploaded, bitmap)
                        reqExecutor.execute {
                            val (boxes, labelsOut) = detectWithBothModels(bitmap)
                            runOnUiThread {
                                overlayView.setResults(boxes, labelsOut, bitmap.width, bitmap.height, dispRect)
                                val predictedShelfLife = ShelfLifePredictor.predictShelfLife(labelsOut)

                                if (labelsOut.isNotEmpty()) {
                                    tvPrediction.text = "Detected: ${labelsOut.joinToString(", ")}\nShelf-Life: $predictedShelfLife"
                                    tvPrediction.setTextColor(Color.YELLOW) // shelf-life text in yellow
                                } else {
                                    tvPrediction.text = "No detection"
                                    tvPrediction.setTextColor(Color.RED)
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

        // Load labels
        try {
            labels = loadLabels(LABELS_FILE)
        } catch (e: Exception) {
            labels = emptyList()
            Log.w(TAG, "labels load failed: ${e.message}")
        }

        // Load models
        try {
            interpreterBest = Interpreter(loadModelFile("best_float32.tflite"))
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
        val rawDetections = ArrayList<RawDet>(200)

        for (i in 0 until 8400) {
            val cx = output[0][0][i] * IMAGE_SIZE
            val cy = output[0][1][i] * IMAGE_SIZE
            val w = output[0][2][i] * IMAGE_SIZE
            val h = output[0][3][i] * IMAGE_SIZE

            val scores = FloatArray(numClasses) { c -> output[0][4 + c][i] }
            val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: -1
            val score = if (maxIdx >= 0) scores[maxIdx] else 0f

            if (score > 0.5f && maxIdx >= 0) {
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

        for (part in requestedParts) {
            val indicesForPart = labels.mapIndexedNotNull { idx, lbl -> if (lbl.endsWith("_$part")) idx else null }
            val candidates = det1.filter { it.cls in indicesForPart } + det2.filter { it.cls in indicesForPart }
            val best = candidates.maxByOrNull { it.score }
            if (best != null) {
                combinedBoxes.add(best.box)
                val labelName = if (best.cls < labels.size) labels[best.cls] else "Class ${best.cls}"
                combinedLabels.add("$labelName ${(best.score * 100).toInt()}%")
            }
        }
        return Pair(combinedBoxes, combinedLabels)
    }

    // ------------------ CAMERAX ------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(reqExecutor) { imageProxy ->
                        val bmp = imageProxyToBitmap(imageProxy)
                        if (bmp != null) {
                            val (boxes, labelsOut) = detectWithBothModels(bmp)
                            if (boxes.isNotEmpty()) {
                                runOnUiThread {
                                    overlayView.setResults(boxes, labelsOut, bmp.width, bmp.height, null)
                                    val predictedShelfLife = ShelfLifePredictor.predictShelfLife(labelsOut)

                                    if (labelsOut.isNotEmpty()) {
                                        tvPrediction.text = "Detected: ${labelsOut.joinToString(", ")}\nShelf-Life: $predictedShelfLife"
                                        tvPrediction.setTextColor(Color.YELLOW)
                                    } else {
                                        tvPrediction.text = "No detection"
                                        tvPrediction.setTextColor(Color.RED)
                                    }
                                }
                            }
                        }
                        imageProxy.close()
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

    override fun onDestroy() {
        super.onDestroy()
        reqExecutor.shutdown()
        interpreterBest?.close()
        interpreterLast?.close()
    }
}
