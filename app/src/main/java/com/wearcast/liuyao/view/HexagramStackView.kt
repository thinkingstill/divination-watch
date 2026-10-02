package com.wearcast.liuyao.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.render.InkPalette
import kotlin.math.min

/**
 * 六爻堆叠区：自下而上生长，index 0 = 初爻画在最下方。
 *
 * 未成之爻画一个极淡的占位点，已成之爻由 [growLine] 带动画长出。
 * 动爻（老阳 / 老阴）用朱砂色并加 ○ / × 记号，与结果页 [HexagramView] 的写法一致。
 */
class HexagramStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    companion object {
        private const val GROW_MS = 220L
    }

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var geometry: DisplayGeometry? = null

    private val yang = BooleanArray(6)
    private val present = BooleanArray(6)
    private val moving = BooleanArray(6)

    /** 每一爻的生长进度 0..1，用于「长出」动画 */
    private val grow = FloatArray(6)

    private var flashValue = 0f
    private var flasher: ValueAnimator? = null

    /** 每帧只重绘内容矩形，避免整屏 invalidate（方案 4.2.1 的功耗约束） */
    private val dirty = android.graphics.Rect()

    fun bind(geometry: DisplayGeometry) {
        this.geometry = geometry
        requestLayout()
        invalidate()
    }

    fun clear() {
        for (i in 0 until 6) {
            yang[i] = false
            present[i] = false
            moving[i] = false
            grow[i] = 0f
        }
        flashValue = 0f
        invalidate()
    }

    fun setCount(count: Int) {
        for (i in 0 until 6) grow[i] = if (i < count) 1f else 0f
        invalidate()
    }

    /** 长出第 index 爻（0 = 初爻，自下而上） */
    fun growLine(index: Int, isYang: Boolean, isMoving: Boolean) {
        if (index !in 0 until 6) return
        yang[index] = isYang
        moving[index] = isMoving
        present[index] = true
        grow[index] = 0f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_MS
            addUpdateListener {
                grow[index] = it.animatedValue as Float
                invalidateRow(index)
            }
            start()
        }
    }

    /** 成卦过场：整体描金脉冲一次 */
    fun flash() {
        flasher?.cancel()
        flasher = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 900L
            addUpdateListener {
                flashValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun invalidateRow(index: Int) {
        val step = height / 6f
        val top = (height - step * (index + 1)).toInt()
        val bottom = (height - step * index).toInt()
        dirty.set(0, top - (4f * density).toInt(), width, bottom + (4f * density).toInt())
        invalidate(dirty)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flasher?.cancel()
        flasher = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val step = height / 6f
        val thickness = min(step * 0.30f, 5f * density)

        // 右侧留一条固定宽度的槽给动爻记号（○ / ×），爻线占其余宽度。
        // view 自身已在布局里按 64dp 居中，所以条从 x=0 起算即可；
        // 这里再按 safeWidthPx 收一次上限，防止有人把本视图重新拉成全宽时溢出圆表安全区。
        val gutter = 11f * density
        val maxBar = (geometry?.safeWidthPx ?: width).toFloat() - gutter
        val barW = min((width - gutter).toFloat(), maxBar).coerceAtLeast(1f)
        val centerX = barW / 2f

        for (i in 0 until 6) {
            val cy = height - step * (i + 0.5f)

            if (!present[i]) {
                dot.color = InkPalette.TRACK
                canvas.drawCircle(centerX, cy, 1.6f * density, dot)
                continue
            }

            val g = grow[i].coerceIn(0f, 1f)
            if (g <= 0f) continue

            // 生长动画：从中心向两端展开
            val halfSpan = (barW / 2f) * g
            val x0 = centerX - halfSpan
            val x1 = centerX + halfSpan

            val base = when {
                flashValue > 0.01f -> blend(InkPalette.PAPER, InkPalette.GOLD_BRIGHT, flashValue)
                moving[i] -> InkPalette.CINNABAR
                else -> InkPalette.PAPER
            }
            bar.color = base
            bar.alpha = (g * 255f).toInt().coerceIn(0, 255)

            if (yang[i]) {
                canvas.drawRect(x0, cy - thickness / 2f, x1, cy + thickness / 2f, bar)
            } else {
                val gapHalf = halfSpan * 0.18f
                canvas.drawRect(x0, cy - thickness / 2f, centerX - gapHalf, cy + thickness / 2f, bar)
                canvas.drawRect(centerX + gapHalf, cy - thickness / 2f, x1, cy + thickness / 2f, bar)
            }

            if (moving[i] && g > 0.6f) {
                drawMark(canvas, barW + gutter / 2f, cy, thickness, yang[i], g)
            }
        }
        bar.alpha = 255
    }

    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, size: Float, isYang: Boolean, alpha: Float) {
        mark.color = InkPalette.CINNABAR
        mark.strokeWidth = 1.2f * density
        mark.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val r = size * 0.80f
        if (isYang) {
            canvas.drawCircle(cx, cy, r, mark)              // 老阳 ○
        } else {
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mark)  // 老阴 ×
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, mark)
        }
        mark.alpha = 255
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * u).toInt()
        val g = (ag + (bg - ag) * u).toInt()
        val bl = (ab + (bb - ab) * u).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
