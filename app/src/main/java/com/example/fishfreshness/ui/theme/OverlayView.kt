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
    private var labelColors: List<Int>? = null

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Slightly more opaque dark background for better readability
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setResults(
        boxes: List<RectF>,
        labels: List<String>,
        imgWidth: Int,
        imgHeight: Int,
        displayRect: RectF?,
        labelColors: List<Int>? = null
    ) {
        this.boxes = boxes
        this.labels = labels
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        this.displayRect = displayRect
        this.labelColors = labelColors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val scaleX = (displayRect?.width() ?: width.toFloat()) / imageWidth
        val scaleY = (displayRect?.height() ?: height.toFloat()) / imageHeight
        val dx = displayRect?.left ?: 0f
        val dy = displayRect?.top ?: 0f

        // Keep track of previously placed label rectangles so we can avoid overlaps
        val placedLabelRects = mutableListOf<RectF>()

        for (i in boxes.indices) {
            val box = boxes[i]
            val label = labels.getOrNull(i) ?: ""

            // Draw exactly where the model says the box is, without any
            // extra per-part shrinking so it points precisely to the region.
            val left = box.left * scaleX + dx
            val top = box.top * scaleY + dy
            val right = box.right * scaleX + dx
            val bottom = box.bottom * scaleY + dy

            canvas.drawRect(left, top, right, bottom, boxPaint)

            if (label.isNotEmpty()) {
                // Always use white text against the dark background for maximum contrast
                textPaint.color = Color.WHITE

                val textBounds = Rect()
                textPaint.getTextBounds(label, 0, label.length, textBounds)
                val padding = 8f

                // First try to place the label *inside* the box near the top-left,
                // so it doesn't overlap the red box border or go off-screen.
                var bgLeft = left
                var bgTop = top + padding
                var bgRight = bgLeft + textBounds.width() + 2 * padding
                var bgBottom = bgTop + textBounds.height() + 2 * padding

                // If going beyond the right edge, shift left
                if (bgRight > width) {
                    val shiftX = bgRight - width
                    bgLeft -= shiftX
                    bgRight -= shiftX
                }

                // If going beyond the bottom edge, move label above the box instead
                if (bgBottom > height) {
                    bgTop = top - textBounds.height() - 2 * padding
                    bgBottom = bgTop + textBounds.height() + 2 * padding
                }

                // Avoid overlapping with previously drawn labels by shifting this
                // label upward in small steps until there is no intersection or
                // we run out of space.
                val stepY = textBounds.height() + 2 * padding + 4f
                var tries = 0
                var labelRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
                while (placedLabelRects.any { RectF.intersects(it, labelRect) } && tries < 10) {
                    bgTop -= stepY
                    bgBottom -= stepY
                    labelRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
                    tries++
                }

                placedLabelRects.add(labelRect)

                canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, textBgPaint)
                canvas.drawText(label, bgLeft + padding, bgBottom - padding, textPaint)
            }
        }
    }
}
