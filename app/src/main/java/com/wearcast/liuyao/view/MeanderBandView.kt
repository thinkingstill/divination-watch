package com.wearcast.liuyao.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.render.InkPalette

/**
 * 回纹（雷纹）装饰带：顶部 / 底部各一条（方案 v0.3 §4.2.2）。
 *
 * 这是圆表适配最好的试金石 —— 圆表上缘的可用宽度远小于屏幕宽度，
 * 回纹必须按**所在行的弦宽**收窄，格子数自然变少，且整条带子居中。
 *
 * 视图不在屏幕顶部，局部 y 不等于屏幕 y，所以这里用 [getGlobalVisibleRect]
 * 取真实的屏幕纵坐标再算弦宽。取不到（未 attach）时退回安全区宽度。
 */
class MeanderBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = InkPalette.GOLD_DIM
        alpha = 150
    }
    private val path = Path()
    private val globalRect = Rect()

    private var geometry: DisplayGeometry? = null

    fun bind(geometry: DisplayGeometry) {
        this.geometry = geometry
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: return

        val absY = if (getGlobalVisibleRect(globalRect)) globalRect.centerY() else -1
        val band = if (absY >= 0) g.widthAt(absY) else g.safeWidthPx
        val usable = minOf(band, width).toFloat()
        if (usable <= 0f) return

        val cellW = 9f * density
        val count = (usable / cellW).toInt()
        if (count < 2) return

        val ch = height * 0.62f
        val top = (height - ch) / 2f
        val mid = height / 2f
        val left = (width - count * cellW) / 2f

        // 一整个带子用一条 Path，一次 drawPath —— 不用每个格子一次 draw
        path.reset()
        path.rewind()
        for (i in 0 until count) {
            val x = left + i * cellW
            path.moveTo(x, mid + ch / 2f)
            path.lineTo(x, top)
            path.lineTo(x + cellW * 0.72f, top)
            path.lineTo(x + cellW * 0.72f, top + ch * 0.55f)
            path.lineTo(x + cellW * 0.34f, top + ch * 0.55f)
            path.lineTo(x + cellW * 0.34f, top + ch * 0.26f)
        }
        canvas.drawPath(path, paint)
    }
}
