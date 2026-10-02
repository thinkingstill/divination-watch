package com.wearcast.liuyao.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.wearcast.liuyao.R
import com.wearcast.liuyao.core.CastResult
import com.wearcast.liuyao.core.ShakeDetector
import com.wearcast.liuyao.core.YaoType
import com.wearcast.liuyao.data.HexagramData
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.view.HexagramView

/**
 * 结果页：两种模式共用。
 *
 * 两种模式的首屏落点**一致**，都停在页面开头（六爻卦象），不做任何自动滚动
 * （v0.3.1 用户反馈：一键模式原来自动滚到卦辞，实际观感是「一进来就在最下面」，与沉浸模式不一致）。
 *
 * 186×215 dp 方屏放不下「本卦 / 变卦」左右双列，因此整页纵向滚动；
 * 圆表上内容宽度收进内接正方形安全区，靠左右内边距实现（方表内边距 = 0 增量）。
 */
class ResultActivity : Activity() {

    companion object {
        const val EXTRA_TYPES = "types"
        const val EXTRA_MODE = "mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // 看卦文时也保持常亮：结果页是长文纵向滚动，读到一半黑屏要重新抬手，
        // 与摇卦页同一条体验线（v0.3.1 用户反馈）。旗帜随窗口生命周期自动失效。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ordinals = intent.getIntArrayExtra(EXTRA_TYPES)
        if (ordinals == null || ordinals.size != 6) {
            finish()
            return
        }
        val mode = intent.getIntExtra(EXTRA_MODE, CastActivity.MODE_IMMERSIVE)
        val result = CastResult(ordinals.map { YaoType.entries[it] })

        val geometry = DisplayGeometry.of(this)
        applySafeArea(geometry)

        // 本卦
        findViewById<TextView>(R.id.nameBen).text = result.ben.name
        findViewById<TextView>(R.id.trigramBen).text = result.ben.shapeDetail
        findViewById<HexagramView>(R.id.hexBen).setCast(result)

        val moving = findViewById<TextView>(R.id.moving)
        moving.text = if (result.hasMoving) result.movingLabel() else getString(R.string.result_no_moving)
        if (!result.hasMoving) moving.setTextColor(0xFF8A93A0.toInt())

        // 变卦（上下分区，有动爻才显示）
        if (result.hasMoving) {
            findViewById<LinearLayout>(R.id.bianBlock).visibility = View.VISIBLE
            findViewById<TextView>(R.id.nameBian).text = result.bian.name
            findViewById<TextView>(R.id.trigramBian).text = result.bian.shapeDetail
            findViewById<HexagramView>(R.id.hexBian).setPlain(result.bianYang)
        }

        renderTexts(result)

        // 首屏一律停在页面开头（卦象），两种模式行为一致。
        // 这里刻意不做任何 scrollTo / smoothScrollTo：之前一键模式用 OnGlobalLayoutListener
        // 滚到卦辞锚点，观感是「一进来就在最下面」，与沉浸模式割裂。
        findViewById<TextView>(R.id.btnAgain).setOnClickListener {
            startActivity(
                Intent(this, CastActivity::class.java)
                    .putExtra(CastActivity.EXTRA_MODE, mode)
            )
            finish()
        }

        Log.d(
            ShakeDetector.TAG,
            "result ben=${result.ben.name} bian=${result.bian.name} moving=${result.moving} " +
                "mode=$mode geom=$geometry"
        )
    }

    /** 卦辞 / 爻辞 / 用九用六 / 断语 */
    private fun renderTexts(result: CastResult) {
        val todo = getString(R.string.result_text_todo)
        findViewById<TextView>(R.id.guaCi).text = result.ben.guaCi ?: todo

        // 有动爻只出动爻爻辞；六爻安静则把六条爻辞都摆出来，否则一键模式太薄
        val yaoCi = result.ben.yaoCi
        val positions = if (result.hasMoving) result.moving else (1..6).toList()
        findViewById<TextView>(R.id.yaoCiTitle).apply {
            visibility = View.VISIBLE
            setText(if (result.hasMoving) R.string.result_yao_ci else R.string.result_yao_ci_all)
        }
        findViewById<TextView>(R.id.yaoCi).apply {
            visibility = View.VISIBLE
            text = yaoCi?.let { list ->
                positions.joinToString("\n") { pos ->
                    HexagramData.yaoTitle(pos, result.benYang[pos - 1]) + "：" + list[pos - 1]
                }
            } ?: todo
        }

        // 用九 / 用六：只有乾、坤两卦有
        findViewById<TextView>(R.id.yongCi).apply {
            val yong = result.ben.yongCi
            visibility = if (yong != null) View.VISIBLE else View.GONE
            if (yong != null) text = yong
        }

        // 断语：本版没有编写，明确占位而不编造内容
        findViewById<TextView>(R.id.duanCi).setText(R.string.result_duan_ci_todo)
    }

    /** 圆表：内容收进内接正方形；方表 safeInset = 0，与 v0.2 一致 */
    private fun applySafeArea(geometry: DisplayGeometry) {
        val column = findViewById<LinearLayout>(R.id.resultColumn)
        val base = geometry.dp(16f).toInt()
        val inset = geometry.safeInsetPx
        column.setPadding(
            base + inset,
            geometry.dp(12f).toInt(),
            base + inset,
            geometry.dp(16f).toInt(),
        )
    }
}
