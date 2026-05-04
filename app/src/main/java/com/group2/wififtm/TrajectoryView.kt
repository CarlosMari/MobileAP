package com.group2.wififtm

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

class TrajectoryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_POINTS = 3000
        const val DEFAULT_CANVAS_HALF_RANGE_M = 5f
    }

    var canvasHalfRangeM: Float = DEFAULT_CANVAS_HALF_RANGE_M
        set(value) { field = value; postInvalidate() }

    private val points = ArrayDeque<PointF>(MAX_POINTS + 1)
    private var apX = 0f
    private var apY = 0f
    private var ftmRadius = 0f
    private var showAp = false

    // Waypoints (phone positions + range to unknown AP) and estimated unknown AP
    data class WaypointData(val x: Float, val y: Float, val dist: Float)
    private val waypointList = mutableListOf<WaypointData>()
    private var unknownApX = 0f; private var unknownApY = 0f; private var showUnknownAp = false

    // Paints
    private val bgPaint   = Paint().apply { color = Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DDDDDD"); strokeWidth = 1f }
    private val axesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAAAAA"); strokeWidth = 2f }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3"); strokeWidth = 5f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50"); style = Paint.Style.FILL }
    private val endPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F44336"); style = Paint.Style.FILL }
    private val apPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5722"); style = Paint.Style.FILL }
    private val ftmPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800"); strokeWidth = 3f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); textSize = 28f }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textSize = 22f }
    // Waypoint paints
    private val wpFillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00ACC1"); style = Paint.Style.FILL }
    private val wpRingPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ACC1"); strokeWidth = 2.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val wpNumPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER
    }
    // Unknown AP paints
    private val unknownApFillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7B1FA2"); style = Paint.Style.FILL }
    private val unknownApLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7B1FA2"); textSize = 28f }

    // -------------------------------------------------------------------------

    fun addPoint(x: Float, y: Float) {
        if (points.size >= MAX_POINTS) points.removeFirst()
        points.addLast(PointF(x, y))
        postInvalidate()
    }

    fun setAp(x: Float, y: Float) {
        apX = x; apY = y; showAp = true
        postInvalidate()
    }

    fun setFtmRadius(r: Float) {
        ftmRadius = r
        postInvalidate()
    }

    fun addWaypoint(x: Float, y: Float, dist: Float) {
        waypointList.add(WaypointData(x, y, dist))
        postInvalidate()
    }

    fun setUnknownAp(x: Float, y: Float) {
        unknownApX = x; unknownApY = y; showUnknownAp = true
        postInvalidate()
    }

    fun clear() {
        points.clear(); ftmRadius = 0f
        waypointList.clear(); showUnknownAp = false
        postInvalidate()
    }

    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (width == 0 || height == 0) return

        // Fixed world bounds: ±canvasHalfRangeM around origin in both axes.
        val minX = -canvasHalfRangeM; val maxX = canvasHalfRangeM
        val minY = -canvasHalfRangeM; val maxY = canvasHalfRangeM

        val margin = 48f   // px left for labels
        val cx     = 0f
        val cy     = 0f
        val rangeX = maxX - minX
        val rangeY = maxY - minY
        val scale  = minOf((width - margin) / rangeX, (height - margin) / rangeY)

        // Screen origin offset (world 0,0 → screen coords)
        val ox = margin + (width - margin) / 2f - cx * scale
        val oy = height / 2f + cy * scale

        fun sx(x: Float) = ox + x * scale
        fun sy(y: Float) = oy - y * scale

        // Pick a grid step that gives at least 40px per cell
        val gridOptions = floatArrayOf(0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 20f)
        val gridStep = gridOptions.firstOrNull { it * scale >= 40f } ?: 20f

        // Grid lines + labels
        var gx = floor((minX / gridStep).toDouble()).toFloat() * gridStep
        while (gx <= maxX + gridStep) {
            val sx_ = sx(gx)
            canvas.drawLine(sx_, 0f, sx_, height.toFloat(), if (abs(gx) < gridStep * 0.01f) axesPaint else gridPaint)
            canvas.drawText("%.1fm".format(gx), sx_ + 2, height - 6f, scalePaint)
            gx += gridStep
        }
        var gy = floor((minY / gridStep).toDouble()).toFloat() * gridStep
        while (gy <= maxY + gridStep) {
            val sy_ = sy(gy)
            canvas.drawLine(0f, sy_, width.toFloat(), sy_, if (abs(gy) < gridStep * 0.01f) axesPaint else gridPaint)
            canvas.drawText("%.1fm".format(gy), 2f, sy_ - 4f, scalePaint)
            gy += gridStep
        }

        // FTM ring
        if (ftmRadius > 0f && showAp) {
            canvas.drawCircle(sx(apX), sy(apY), ftmRadius * scale, ftmPaint)
        }

        // Path
        if (points.size >= 2) {
            val path = Path()
            path.moveTo(sx(points[0].x), sy(points[0].y))
            for (i in 1 until points.size) {
                path.lineTo(sx(points[i].x), sy(points[i].y))
            }
            canvas.drawPath(path, pathPaint)
        }

        // Waypoint range circles (dashed, centred at each waypoint phone position)
        for (wp in waypointList) {
            canvas.drawCircle(sx(wp.x), sy(wp.y), wp.dist * scale, wpRingPaint)
        }

        // AP marker (anchor)
        if (showAp) {
            canvas.drawCircle(sx(apX), sy(apY), 14f, apPaint)
            canvas.drawText("AP", sx(apX) + 18, sy(apY) + 8f, labelPaint)
        }

        // Unknown AP estimated position
        if (showUnknownAp) {
            canvas.drawCircle(sx(unknownApX), sy(unknownApY), 16f, unknownApFillPaint)
            canvas.drawText("AP?", sx(unknownApX) + 20, sy(unknownApY) + 8f, unknownApLabelPaint)
        }

        // Waypoint markers (numbered filled circles)
        for ((i, wp) in waypointList.withIndex()) {
            canvas.drawCircle(sx(wp.x), sy(wp.y), 14f, wpFillPaint)
            canvas.drawText("${i + 1}", sx(wp.x), sy(wp.y) + 7f, wpNumPaint)
        }

        // Start dot (green)
        if (points.isNotEmpty()) {
            canvas.drawCircle(sx(points[0].x), sy(points[0].y), 11f, startPaint)
        }
        // Current dot (red)
        if (points.size > 1) {
            canvas.drawCircle(sx(points.last().x), sy(points.last().y), 13f, endPaint)
        }
    }
}
