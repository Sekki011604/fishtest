package com.example.fishfreshness

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var displayRect: RectF? = null

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setResults(
        boxes: List<RectF>,
        labels: List<String>,
        imgWidth: Int,
        imgHeight: Int,
        displayRect: RectF?
    ) {
        this.boxes = boxes
        this.labels = labels
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        this.displayRect = displayRect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val scaleX = (displayRect?.width() ?: width.toFloat()) / imageWidth
        val scaleY = (displayRect?.height() ?: height.toFloat()) / imageHeight
        val dx = displayRect?.left ?: 0f
        val dy = displayRect?.top ?: 0f

        for (i in boxes.indices) {
            val box = boxes[i]
            val label = labels.getOrNull(i) ?: ""

            val left = box.left * scaleX + dx
            val top = box.top * scaleY + dy
            val right = box.right * scaleX + dx
            val bottom = box.bottom * scaleY + dy

            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText(label, left, top - 10f, textPaint)
        }
    }
}
