package com.wearcast.liuyao.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.wearcast.liuyao.core.CastResult
import com.wearcast.liuyao.render.InkPalette
import kotlin.math.min

/**
 * 六爻图示：自下而上 6 行，阳爻实线、阴爻虚线；动爻用金色并加记号。
 * index 0 = 初爻（画在最下方）。
 *
 * 记号沿用传统写法：老阳记 ○，老阴记 ×。
 */
class HexagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    companion object {
        const val MARK_NONE = 0
        const val MARK_LAO_YANG = 1   // ○
        const val MARK_LAO_YIN = 2    // ×
    }

    /** 宣纸米白：静态爻线 */
    private val lineColor = InkPalette.PAPER

    /** 朱砂：动爻与记号（v0.3 国风规范） */
    private val movingColor = InkPalette.CINNABAR

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val density = resources.displayMetrics.density

    private var yang = BooleanArray(6)
    private var marks = IntArray(6)

    /** 画本卦（含动爻记号） */
    fun setCast(result: CastResult) {
        val yangArr = BooleanArray(6) { result.types[it].yang }
        val markArr = IntArray(6) {
            when (result.types[it]) {
                com.wearcast.liuyao.core.YaoType.LAO_YANG -> MARK_LAO_YANG
                com.wearcast.liuyao.core.YaoType.LAO_YIN -> MARK_LAO_YIN
                else -> MARK_NONE
            }
        }
        setLines(yangArr, markArr)
    }

    /** 画变卦（无记号） */
    fun setPlain(yangBottomUp: BooleanArray) {
        setLines(yangBottomUp, IntArray(6))
    }

    fun setLines(yangBottomUp: BooleanArray, marksBottomUp: IntArray) {
        yang = yangBottomUp.copyOf(6)
        marks = marksBottomUp.copyOf(6)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val markSpace = 13f * density
        val w = width - markSpace
        val step = height / 6f
        val thickness = min(step * 0.4f, 6f * density)
        val gapHalf = w * 0.09f

        for (i in 0 until 6) {
            val cy = height - step * (i + 0.5f)
            val active = marks[i] != MARK_NONE
            bar.color = if (active) movingColor else lineColor

            if (yang[i]) {
                canvas.drawRect(0f, cy - thickness / 2f, w, cy + thickness / 2f, bar)
            } else {
                canvas.drawRect(0f, cy - thickness / 2f, w / 2f - gapHalf, cy + thickness / 2f, bar)
                canvas.drawRect(w / 2f + gapHalf, cy - thickness / 2f, w, cy + thickness / 2f, bar)
            }

            if (active) drawMark(canvas, w + markSpace / 2f, cy, thickness, marks[i])
        }
    }

    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, size: Float, kind: Int) {
        mark.color = movingColor
        mark.strokeWidth = 1.3f * density
        val r = size * 0.72f

        if (kind == MARK_LAO_YANG) {
            canvas.drawCircle(cx, cy, r, mark)
        } else {
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mark)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, mark)
        }
    }
}
