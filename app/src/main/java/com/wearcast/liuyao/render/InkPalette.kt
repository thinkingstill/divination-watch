package com.wearcast.liuyao.render

/**
 * 国风配色（方案 v0.3 §4.2.2）。
 *
 * 自绘 View 在 onDraw 里直接取这里的常量，不去查资源表 —— 每帧查 resources 在低端表上是白给的抖动。
 * 与 `res/values/colors.xml` 一一对应，改色时两处同步。
 */
object InkPalette {

    /** 墨底 */
    const val BG = 0xFF0B0D10.toInt()
    const val SURFACE = 0xFF14171C.toInt()

    /** 铜金：主色（背 / 铜钱） */
    const val GOLD = 0xFFC9A227.toInt()

    /** 赤金：描边脉冲用 */
    const val GOLD_BRIGHT = 0xFFE0B33A.toInt()

    /** 暗金：内轮 / 次级描边 */
    const val GOLD_DIM = 0xFF6B5A18.toInt()
    const val GOLD_DEEP = 0xFF3A3212.toInt()

    /** 朱砂：动爻与变卦的强调色 */
    const val CINNABAR = 0xFFA8322A.toInt()

    /** 宣纸米白 */
    const val PAPER = 0xFFE8E2D4.toInt()

    const val TEXT_SECONDARY = 0xFF8A93A0.toInt()
    const val TEXT_FAINT = 0xFF5A636F.toInt()
    const val TRACK = 0xFF232830.toInt()
}
