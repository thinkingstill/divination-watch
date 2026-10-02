package com.wearcast.liuyao.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.wearcast.liuyao.R
import com.wearcast.liuyao.data.Prefs
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.view.CoinTossView
import com.wearcast.liuyao.view.MeanderBandView

/**
 * 首页：两个并列入口（方案 v0.3 4.2 ①）。
 *
 *  - 「一键」铜金实心 → 一键起卦（先轻摇确认，再自动连抛六爻）
 *  - 「沉浸」铜金描边 → 沉浸摇卦（用户主动摇六次）
 *
 * 次要入口仍是底部的音效开关。历史卦例与详解页留待下一轮。
 *
 * 圆表适配：内容整体以「内接正方形」为安全区，圆表上按安全区边长补上下内边距；
 * 方表安全区 = 全宽，内边距与 v0.2 完全一致（几何层短路，无额外分支）。
 */
class MainActivity : Activity() {

    private lateinit var soundToggle: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val geometry = DisplayGeometry.of(this)
        applySafeArea(geometry)

        findViewById<MeanderBandView>(R.id.bandTop).bind(geometry)

        findViewById<CoinTossView>(R.id.coin).apply {
            coinCount = 1
            bind(geometry)
            startIdle()
        }

        hint = findViewById(R.id.homeHint)

        val quick = findViewById<TextView>(R.id.btnQuick)
        val immersive = findViewById<TextView>(R.id.btnImmersive)

        // 说明文字跟随最近一次触点切换
        quick.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) hint.setText(R.string.home_hint_quick)
            false
        }
        immersive.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) hint.setText(R.string.home_hint_immersive)
            false
        }

        quick.setOnClickListener { startCast(CastActivity.MODE_QUICK) }
        immersive.setOnClickListener { startCast(CastActivity.MODE_IMMERSIVE) }

        soundToggle = findViewById(R.id.btnSound)
        soundToggle.setOnClickListener {
            Prefs.setSoundOn(this, !Prefs.soundOn(this))
            renderSound()
        }
    }

    override fun onResume() {
        super.onResume()
        renderSound()
        findViewById<CoinTossView>(R.id.coin).startIdle()
    }

    private fun startCast(mode: Int) {
        startActivity(
            Intent(this, CastActivity::class.java)
                .putExtra(CastActivity.EXTRA_MODE, mode)
        )
    }

    /**
     * 圆表：给内容列补上安全区上下内边距，避免标题与音效开关落到圆角被裁的区域。
     * 方表：safeInset = 0，内边距就是 XML 里的基准值 —— 方表布局零改动。
     */
    private fun applySafeArea(geometry: DisplayGeometry) {
        val column = findViewById<LinearLayout>(R.id.homeColumn)
        val bx = geometry.dp(4f).toInt()
        val byTop = geometry.dp(6f).toInt()
        val byBottom = geometry.dp(4f).toInt()
        val inset = geometry.safeInsetPx
        column.setPadding(bx + inset, byTop + inset, bx + inset, byBottom + inset)
    }

    private fun renderSound() {
        soundToggle.setText(if (Prefs.soundOn(this)) R.string.sound_on else R.string.sound_off)
    }
}
