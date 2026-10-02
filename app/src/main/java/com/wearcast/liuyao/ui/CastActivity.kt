package com.wearcast.liuyao.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.wearcast.liuyao.R
import com.wearcast.liuyao.core.CoinMapper
import com.wearcast.liuyao.core.EntropySampler
import com.wearcast.liuyao.core.ShakeDetector
import com.wearcast.liuyao.core.YaoType
import com.wearcast.liuyao.data.Prefs
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.view.CoinTossView
import com.wearcast.liuyao.view.HexagramStackView
import com.wearcast.liuyao.view.MeanderBandView
import com.wearcast.liuyao.view.ProgressRingView
import kotlin.math.min

/**
 * 摇卦页，两种模式共用（方案 v0.3 §4.1 / 4.2 ②③ / 4.3）。
 *
 * ```
 * 沉浸模式：等待摇动 → 一次采集 → 六拍全版动画（≈2.25s）→ 堆叠长出一爻 → 重复 6 次
 * 一键模式：轻摇确认 → 立即释放传感器 → 自动连抛 6 爻（六拍快版 ≈0.7s）→ 成卦
 * ```
 *
 * 传感器使用策略不变：只在「等用户摇」时持有，采集完成立即 `unregisterListener`；
 * 一键模式在确认后就不再需要传感器（熵已经采到了），持有时间 ≈ 1.2 秒。
 */
class CastActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_IMMERSIVE = 0
        const val MODE_QUICK = 1

        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val NAV_DELAY_MS = 450L

        /** 成卦过场：六爻整体描金 + 长振，然后跳结果页 */
        private const val SETTLE_MS = 1200L

        /** 一键模式爻与爻之间的呼吸间隙 */
        private const val QUICK_GAP_MS = 90L

        /** 六爻堆叠区在方表上的宽高比（64dp : 88dp），圆表缩放时保持它不变 */
        private const val STACK_ASPECT = 64f / 88f

        private const val SHORT_VIBRATE_MS = 40L
        private const val LONG_VIBRATE_MS = 260L
    }

    private lateinit var coin: CoinTossView
    private lateinit var ring: ProgressRingView
    private lateinit var stack: HexagramStackView
    private lateinit var hint: TextView
    private lateinit var progressView: TextView
    private lateinit var btnCancel: TextView
    private lateinit var soundFx: SoundFx

    private var detector: ShakeDetector? = null
    private val handler = Handler(Looper.getMainLooper())

    private var mode = MODE_IMMERSIVE
    private val types = ArrayList<YaoType>(6)

    private var busy = false
    private var finished = false
    private var listening = false

    /** 一键模式：轻摇确认是否已完成 */
    private var confirmed = false

    /** 一键模式：确认那一次采集到的熵池，六爻各取一片切片 */
    private var quickPool: ByteArray? = null

    private val idleTimeout = Runnable { onIdleTimeout() }

    private val isDebuggable: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val isQuick: Boolean get() = mode == MODE_QUICK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast)

        // 卜卦全程不许熄屏：一次沉浸起卦要摇六次、每爻 2.25s 动画，一键模式也要数秒，
        // 中途黑屏会直接打断仪式感，也让抛掷动画白放。窗口旗标随 Activity 销毁自动失效，
        // 所以离开摇卦页后不需要手动清（结果页另有自己的旗标）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_IMMERSIVE)

        val geometry = DisplayGeometry.of(this)

        coin = findViewById(R.id.coin)
        ring = findViewById(R.id.ring)
        stack = findViewById(R.id.stack)
        hint = findViewById(R.id.hint)
        progressView = findViewById(R.id.progress)
        btnCancel = findViewById(R.id.btnCancel)
        soundFx = SoundFx(this)

        // 圆表安全区要在拿到视图之后再套
        applySafeArea(geometry)

        findViewById<MeanderBandView>(R.id.bandTop).bind(geometry)
        coin.bind(geometry)
        ring.bind(geometry)
        stack.bind(geometry)

        ring.setProgress(0)
        renderProgress(0)
        coin.startIdle()

        btnCancel.setText(R.string.cast_cancel)
        btnCancel.setOnClickListener { finish() }

        // 调试构建才显示「长按铜钱模拟出爻」；release 下这一行只剩居中的「取消」
        findViewById<TextView>(R.id.debugHint).visibility =
            if (isDebuggable) View.VISIBLE else View.GONE

        detector = ShakeDetector(
            context = this,
            onCaptured = ::onCaptured,
            onSample = { g -> coin.setEnergy(g) },
            // 一键模式的确认动作要比正式摇卦轻一档（v0.3 8.1 决策 10）
            thresholdG = if (isQuick) ShakeDetector.QUICK_THRESHOLD_G else ShakeDetector.DEFAULT_THRESHOLD_G,
        )

        if (!(detector?.available ?: false)) {
            hint.setText(R.string.cast_hint_timeout)
            Log.w(ShakeDetector.TAG, "accelerometer unavailable")
        }

        // 30 秒无有效摇动后释放传感器，轻触恢复
        ring.setOnClickListener { if (!busy && !finished && !listening) resumeWaiting() }

        // 调试构建专用：长按铜钱直接推进链路，用于不开真实摇晃验证整条流程
        coin.isLongClickable = true
        coin.setOnLongClickListener {
            if (isDebuggable && !busy && !finished) {
                Log.d(ShakeDetector.TAG, "DEBUG simulate input yao=${types.size + 1}")
                onCaptured(EntropySampler.syntheticPool(System.nanoTime()), 0f)
                true
            } else {
                false
            }
        }

        hint.setText(if (isQuick) R.string.cast_hint_confirm else R.string.cast_hint_idle)
        Log.d(ShakeDetector.TAG, "cast start mode=${if (isQuick) "quick" else "immersive"} geom=$geometry")
    }

    override fun onResume() {
        super.onResume()
        if (!finished) resumeWaiting()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        handler.removeCallbacks(idleTimeout)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        soundFx.release()
    }

    // ── 圆表安全区 ────────────────────────────────────────────

    private fun applySafeArea(geometry: DisplayGeometry) {
        val column = findViewById<LinearLayout>(R.id.castColumn)
        val bx = geometry.dp(4f).toInt()
        val by = geometry.dp(2f).toInt()
        val inset = geometry.safeInsetPx
        column.setPadding(bx + inset, by + inset, bx + inset, by + inset)

        if (geometry.isRound) {
            // 圆表安全区只有 min(W,H)/√2 见方。六爻堆叠区按方表比例（64:88 ≈ 1:1.38）
            // 等比缩到安全高度的 30%，既不顶到圆角，也给抛掷区留住垂直空间。
            val safeSide = geometry.safeWidthPx     // 圆表安全区是正方形，宽与高同值
            val h = min(geometry.dp(88f), safeSide * 0.30f).toInt().coerceAtLeast(1)
            val lp = stack.layoutParams
            lp.width = (h * STACK_ASPECT).toInt()
            lp.height = h
            stack.layoutParams = lp
            hint.textSize = 11f
            progressView.textSize = 10f
        }
    }

    // ── 状态机 ────────────────────────────────────────────────

    private fun resumeWaiting() {
        if (busy || finished) return
        coin.startIdle()
        coin.setEnergy(1f)
        when {
            isQuick && !confirmed -> hint.setText(R.string.cast_hint_confirm)
            isQuick -> hint.setText(R.string.cast_hint_settling)
            else -> hint.setText(R.string.cast_hint_idle)
        }
        startListening()
    }

    private fun startListening() {
        val d = detector ?: return
        d.start()
        listening = true
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, IDLE_TIMEOUT_MS)
    }

    private fun stopListening() {
        detector?.stop()
        listening = false
        handler.removeCallbacks(idleTimeout)
    }

    private fun onIdleTimeout() {
        if (busy || finished) return
        stopListening()
        coin.setEnergy(1f)
        hint.setText(R.string.cast_hint_timeout)
        Log.d(ShakeDetector.TAG, "idle timeout, sensor released")
    }

    /** 一次有效采集完成 */
    private fun onCaptured(pool: ByteArray, peakG: Float) {
        if (busy || finished) return
        stopListening()

        if (isQuick && !confirmed) {
            // 轻摇确认：拿到熵就立刻放掉传感器，后面六爻不再需要它
            confirmed = true
            quickPool = pool
            coin.setEnergy(1f)
            Log.d(ShakeDetector.TAG, "quick confirmed pool=%d bytes peak=%.2fG".format(pool.size, peakG))
            quickStep(0)
            return
        }

        if (isQuick) {
            Log.d(ShakeDetector.TAG, "quick mode already confirmed, sensor input ignored")
            return
        }

        tossOne(pool, peakG)
    }

    // ── 沉浸模式：一爻一次 ─────────────────────────────────────

    private fun tossOne(pool: ByteArray, peakG: Float) {
        val index = types.size
        val t = CoinMapper.toss(pool, System.nanoTime(), index)
        types.add(t.type)
        busy = true

        ring.setProgress(types.size)
        renderProgress(types.size)
        hint.text = getString(R.string.cast_yao_fmt, t.type.label, backsLabel(t.backs))
        Log.d(
            ShakeDetector.TAG,
            "yao=%d type=%s backs=%d combo=%d peak=%.2fG".format(
                types.size, t.type.label, t.backs, t.combo, peakG
            )
        )

        coin.playToss(
            t = t,
            quick = false,
            onSettle = {
                stack.growLine(index, t.type.yang, t.type.moving)
                vibrate(SHORT_VIBRATE_MS)
                if (Prefs.soundOn(this)) soundFx.play()
            },
            onEnd = {
                busy = false
                if (types.size >= 6) finishCast() else resumeWaiting()
            },
        )
    }

    // ── 一键模式：确认之后自动连抛六爻 ──────────────────────────

    private fun quickStep(index: Int) {
        if (index >= 6) {
            finishCast()
            return
        }
        busy = true
        val pool = quickPool ?: ByteArray(0)
        val t = CoinMapper.toss(pool, System.nanoTime(), index)
        types.add(t.type)

        ring.setProgress(types.size)
        renderProgress(types.size)
        hint.text = getString(R.string.cast_yao_fmt, t.type.label, backsLabel(t.backs))
        Log.d(
            ShakeDetector.TAG,
            "quick yao=%d type=%s backs=%d combo=%d slice=%d".format(
                types.size, t.type.label, t.backs, t.combo, index
            )
        )

        coin.playToss(
            t = t,
            quick = true,
            onSettle = {
                stack.growLine(index, t.type.yang, t.type.moving)
                vibrate(SHORT_VIBRATE_MS)
                if (Prefs.soundOn(this)) soundFx.play()
            },
            onEnd = {
                // busy 保持 true，爻与爻之间只是呼吸间隙，不让用户插手
                handler.postDelayed({ quickStep(index + 1) }, QUICK_GAP_MS)
            },
        )
    }

    private fun finishCast() {
        finished = true
        busy = true
        stopListening()
        btnCancel.visibility = View.GONE

        hint.setText(R.string.cast_hint_full)
        progressView.setText(R.string.cast_hint_settling)
        stack.flash()
        vibrate(LONG_VIBRATE_MS)

        val ordinals = IntArray(6) { types[it].ordinal }
        Log.d(ShakeDetector.TAG, "cast done mode=$mode types=${ordinals.joinToString(",")}")

        handler.postDelayed({
            startActivity(
                Intent(this, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_TYPES, ordinals)
                    .putExtra(ResultActivity.EXTRA_MODE, mode)
            )
            finish()
        }, SETTLE_MS)
    }

    // ── 反馈 ─────────────────────────────────────────────────

    private fun renderProgress(count: Int) {
        // 单行即可：调试提示挪到底部与「取消」同一行，省下的这一行留给抛掷区（铜钱能画大一圈）
        progressView.text = getString(R.string.cast_progress, count)
    }

    private fun backsLabel(backs: Int): String = getString(
        when (backs) {
            3 -> R.string.backs_3
            2 -> R.string.backs_2
            1 -> R.string.backs_1
            else -> R.string.backs_0
        }
    )

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) {
            Log.d(ShakeDetector.TAG, "no vibrator")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }.onFailure { Log.w(ShakeDetector.TAG, "vibrate failed: $it") }
    }
}
