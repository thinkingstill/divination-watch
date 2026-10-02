package com.wearcast.liuyao.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.render.InkPalette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 摇卦页外圈进度环：显示「第 N / 6 爻」的进度，并在 6 个爻位打点。
 *
 * 圆表上不能贴着屏幕边缘画 —— 圆表 466 px 屏若按整屏半径铺环，弧线会被圆角裁掉。
 * 因此圆表取内切圆直径的 0.86 倍，方表沿用整屏（保持 v0.2 原样）。
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()

    private var geometry: DisplayGeometry? = null
    private var progress = 0

    fun bind(geometry: DisplayGeometry) {
        this.geometry = geometry
        invalidate()
    }

    /** 已完成爻数 0..6 */
    fun setProgress(count: Int) {
        if (progress == count) return
        progress = count.coerceIn(0, 6)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeW = 2.5f * density
        val inset = strokeW / 2f + 4f * density
        val cx = width / 2f
        val cy = height / 2f

        // 方表：整屏圆弧（与 v0.2 一致，零改动）
        // 圆表：内切圆 × 0.86，留出被圆角裁掉的余量
        val g = geometry
        val radius = if (g != null && g.isRound) {
            g.ringDiameterPx(0.86f) / 2f
        } else {
            min(width, height) / 2f - inset
        }

        box.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.strokeWidth = strokeW
        paint.strokeCap = Paint.Cap.ROUND

        paint.color = InkPalette.TRACK
        canvas.drawArc(box, -90f, 360f, false, paint)

        if (progress > 0) {
            paint.color = InkPalette.GOLD
            canvas.drawArc(box, -90f, 360f * progress / 6f, false, paint)
        }

        // 6 个爻位刻度
        val tick = 3.2f * density
        for (i in 0 until 6) {
            val angle = Math.toRadians((-90f + 60f * i).toDouble())
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            dot.color = if (i < progress) InkPalette.GOLD else InkPalette.TRACK
            canvas.drawCircle(x, y, tick, dot)
        }
    }
}
