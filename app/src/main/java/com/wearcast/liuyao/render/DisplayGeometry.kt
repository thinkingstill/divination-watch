package com.wearcast.liuyao.render

import android.content.Context
import android.content.res.Configuration
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 方表 / 圆表形态几何（方案 v0.3 §2.2 第 6 条、§4.1、8.1 决策 12）。
 *
 * 目标设备两类：
 *  - 方表：OPPO Watch 3（OWW212），372×430 px @320dpi → 186×215 dp 方屏，可用宽度处处等于全宽；
 *  - 圆表：任意 OPPO 圆表，可用宽度随纵坐标变化，越靠上下缘越窄。
 *
 * 设计约束（写死在实现里）：
 *  1. 形态判定只用 [Configuration.isScreenRound]，**禁止**用分辨率或机型名判断；
 *  2. 方表路径必须短路返回全宽 —— [widthAt] 第一个分支就 return，方表布局一个额外分支都不走。
 */
class DisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val isRound: Boolean,
) {

    companion object {
        private const val INV_SQRT2 = 0.7071068f

        fun of(context: Context): DisplayGeometry {
            val dm = context.resources.displayMetrics
            return DisplayGeometry(
                widthPx = dm.widthPixels,
                heightPx = dm.heightPixels,
                density = dm.density,
                isRound = context.resources.configuration.isScreenRound,
            )
        }
    }

    /**
     * 安全区宽度（像素）。
     * 方表 = 全宽；圆表 = 内接正方形边长 `min(W, H) / √2`。
     */
    val safeWidthPx: Int
        get() = if (!isRound) widthPx else (min(widthPx, heightPx) * INV_SQRT2).toInt()

    /** 安全区左右各留的内边距 */
    val safeInsetPx: Int
        get() = (widthPx - safeWidthPx) / 2

    /** 内切圆半径：圆表用它算弦宽与环直径 */
    val innerRadiusPx: Float
        get() = min(widthPx, heightPx) / 2f

    private val centerYPx: Float
        get() = heightPx / 2f

    /**
     * 第 [yPx] 行的可用宽度（像素）。
     *
     * 方表：直接返回全宽（短路，与 v0.2 布局完全一致）。
     * 圆表：`half(y) = √(R² − (H/2 − y)²)`，故 `widthAt(y) = 2 · half(y)`。
     */
    fun widthAt(yPx: Int): Int {
        if (!isRound) return widthPx
        val dy = centerYPx - yPx
        val r = innerRadiusPx
        val r2 = r * r - dy * dy
        if (r2 <= 0f) return 0
        return (2f * sqrt(r2)).toInt()
    }

    /** 把 [yPx] 行上的一段落居中所需的左边距 */
    fun leftFor(yPx: Int, spanPx: Int): Int = (widthAt(yPx) - spanPx) / 2

    /** 圆表上取内切圆的比例直径；方表直接给 [fraction] × min(W, H) */
    fun ringDiameterPx(fraction: Float): Float = min(widthPx, heightPx) * fraction

    fun dp(value: Float): Float = value * density

    override fun toString(): String =
        "DisplayGeometry(${widthPx}×${heightPx}px, d=${density}, ${if (isRound) "round" else "square"}, safe=${safeWidthPx}px)"
}
