package com.wearcast.liuyao.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.wearcast.liuyao.core.Toss
import com.wearcast.liuyao.render.DisplayGeometry
import com.wearcast.liuyao.render.InkPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 铜钱抛掷视图：一次抛掷 = 三枚铜钱 × 六拍国风分镜（方案 v0.3 §4.2.1）。
 *
 * 六拍时间轴（归一化进度 p ∈ [0, 1]）：
 * ```
 * 1 起势  250ms 入掌：整体下沉 4dp、微倾
 * 2 升空  500ms 三条抛物线抛起，自旋 2.2 / 2.6 / 3.0 圈，横向散布张开到该行弦宽的 0.6 倍
 * 3 弹跳  500ms 错开 60 / 120ms 落地，各做一次幅度递减的弹跳，自旋转为带阻尼的滚转
 * 4 翻面  400ms 绕水平轴翻转 90°「揭面」，在压成一条线的那一刻落定为背 / 字
 * 5 显象  250ms 描金脉冲 + 每枚上方浮出「背」/「字」
 * 6 成爻  350ms 向下收缩淡出（六爻堆叠由 [HexagramStackView] 同步长出）
 * ```
 * 全版合计 ≈ 2.25 s；一键模式的快版把各拍压到约 1/3、并省略第 4 拍翻面，合计 ≈ 0.7 s。
 *
 * 功耗控制：两枚铜钱的币面在 [onSizeChanged] 里预渲染成三张位图（外轮 / 字面 / 背面的内盘），
 * onDraw 只做矩阵变换 + drawBitmap；且只在动画进行时 invalidate。
 */
class CoinTossView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    companion object {
        private const val FULL_MS = 2250L
        private const val QUICK_MS = 700L
        private const val IDLE_TURN_MS = 5200L

        /** 币文：正面四字对读（上 周 / 下 易 / 右 通 / 左 宝，仿开元通宝布局） */
        private const val CHAR_UP = "\u5468"    // 周
        private const val CHAR_DOWN = "\u6613"  // 易
        private const val CHAR_RIGHT = "\u901a" // 通
        private const val CHAR_LEFT = "\u5b9d"  // 宝

        /** 自旋圈数：三枚各不相同，看上去才像三枚独立的钱 */
        private val TURNS = floatArrayOf(2.2f, 2.6f, 3.0f)

        /** 第 i 枚落地时刻在「弹跳」拍内的偏移（错开 60 / 120 ms） */
        private val LAND_OFFSET = floatArrayOf(0f, 0.12f, 0.24f)
    }

    /** 六拍在总时长里的权重（两个版本各归一化为 1） */
    private class Beats(
        val appear: Float, val rise: Float, val bounce: Float,
        val flip: Float, val show: Float, val settle: Float,
    ) {
        val total = appear + rise + bounce + flip + show + settle
        val c1 = appear / total
        val c2 = c1 + rise / total
        val c3 = c2 + bounce / total
        val c4 = c3 + flip / total
        val c5 = c4 + show / total
    }

    private val fullBeats = Beats(0.11f, 0.22f, 0.22f, 0.18f, 0.11f, 0.16f)
    private val quickBeats = Beats(0.16f, 0.30f, 0.06f, 0.00f, 0.23f, 0.25f)

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
    }
    private val holeRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private var geometry: DisplayGeometry? = null

    /** 首页只摇一枚，摇卦页三枚 */
    var coinCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 3)
            invalidate()
        }

    private var toss: Toss? = null
    private var progress = 0f
    private var quick = false
    private var settledFired = false
    private var onSettle: (() -> Unit)? = null
    private var onEnd: (() -> Unit)? = null

    private var idleAngle = 0f
    private var energy = 0f

    private var runner: ValueAnimator? = null
    private var idler: ValueAnimator? = null

    // ── 布局量 ────────────────────────────────────────────────
    private var cx = 0f
    private var cy = 0f
    private var coinR = 0f
    private var spreadStart = 0f
    private var spreadEnd = 0f
    private var stackY = 0f
    private var landY = 0f
    private var apexLift = 0f

    // ── 预渲染的币面 ───────────────────────────────────────────
    private var ringBmp: Bitmap? = null
    private var innerFrontBmp: Bitmap? = null
    private var innerBackBmp: Bitmap? = null

    fun bind(geometry: DisplayGeometry) {
        this.geometry = geometry
        layoutSelf(width.toFloat(), height.toFloat())
    }

    /** 待摇态：缓慢自转。摇卦页与首页都用它。 */
    fun startIdle() {
        if (idler?.isStarted == true) return
        idler = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = IDLE_TURN_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                idleAngle = it.animatedValue as Float
                if (toss == null) invalidate()
            }
        }
        idler?.start()
    }

    fun stopIdle() {
        idler?.cancel()
        idler = null
    }

    /** 传感器回调传入的合加速度（G），映射成待摇态的抖动幅度 */
    fun setEnergy(g: Float) {
        energy = ((g - 1f).coerceIn(0f, 2.5f)) / 2.5f
        if (toss == null) invalidate()
    }

    /**
     * 播一次抛掷动画。
     *
     * @param t        本次结果
     * @param quick    true = 一键模式快版（≈0.7 s、省略翻面）
     * @param onSettle 进入第 6 拍「成爻」时回调（此时六爻堆叠应该长出这一爻）
     * @param onEnd    动画结束时回调
     */
    fun playToss(t: Toss, quick: Boolean, onSettle: (() -> Unit)? = null, onEnd: (() -> Unit)? = null) {
        runner?.cancel()
        this.toss = t
        this.quick = quick
        this.progress = 0f
        this.settledFired = false
        this.onSettle = onSettle
        this.onEnd = onEnd

        val duration = if (quick) QUICK_MS else FULL_MS
        runner = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                progress = it.animatedValue as Float
                fireBeats()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (this@CoinTossView.toss === t && progress >= 1f) {
                        this@CoinTossView.toss = null
                        this@CoinTossView.progress = 0f
                        invalidate()
                        onEnd?.invoke()
                    }
                }
            })
        }
        runner?.start()
    }

    private fun fireBeats() {
        if (settledFired) return
        val b = if (quick) quickBeats else fullBeats
        if (progress >= b.c5) {
            settledFired = true
            onSettle?.invoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSelf(w.toFloat(), h.toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runner?.cancel()
        runner = null
        stopIdle()
        ringBmp?.recycle(); ringBmp = null
        innerFrontBmp?.recycle(); innerFrontBmp = null
        innerBackBmp?.recycle(); innerBackBmp = null
    }

    // ── 布局 ─────────────────────────────────────────────────

    private fun layoutSelf(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        cx = w / 2f
        cy = h / 2f

        // 三枚铜钱的抛掷带始终落在屏幕中央附近，那里的弦宽必然 ≥ 内接正方形边长，
        // 所以这里用「安全区宽度」而不是逐行弦宽 —— 与视图在屏幕上的位置无关，不会算错。
        val availW = min((geometry?.safeWidthPx ?: w.toInt()).toFloat(), w)

        // 单枚铜钱半径取三者的下限：
        //  ① 上限 19dp（v0.3.1 从 15dp 调大，用户反馈铜钱偏小）
        //  ② 三枚并排不能贴左右边：直径 ≤ 可用宽度 /4.25
        //  ③ 高度：升空最高点 centerY = stackY − apexLift = 0.50h − 0.22h = 0.28h，
        //     所以 R ≤ 0.28h 才不会在最高点被视图上缘裁掉。这条最容易忘，
        //     下方六爻带加高、抛掷区变矮时它会立刻变成约束条件。
        coinR = min(min(d(19f), availW / 8.5f), h * 0.28f).coerceAtLeast(d(8f))

        // 横向散布：「该行弦宽的 0.6 倍」作为外枚中心的总跨度
        spreadEnd = min(availW * 0.30f, w / 2f - coinR - d(3f)).coerceAtLeast(coinR * 1.5f)
        spreadStart = coinR * 1.12f
        stackY = h * 0.50f
        landY = h * 0.64f
        apexLift = h * 0.22f

        buildFaces()
    }

    private fun d(v: Float) = v * density

    // ── 币面预渲染 ────────────────────────────────────────────

    private fun buildFaces() {
        val size = (coinR * 2f).toInt().coerceAtLeast(12)
        ringBmp?.recycle()
        innerFrontBmp?.recycle()
        innerBackBmp?.recycle()

        ringBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .also { drawRing(Canvas(it)) }
        innerBackBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .also { drawInnerBack(Canvas(it)) }
        innerFrontBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .also { drawInnerFront(Canvas(it)) }
    }

    /** 外轮：双层轮。旋转时它不动，所以单独一张。 */
    private fun drawRing(c: Canvas) {
        val r = min(c.width, c.height) / 2f
        stroke.style = Paint.Style.STROKE
        stroke.color = InkPalette.GOLD
        stroke.strokeWidth = d(1.2f)
        c.drawCircle(r, r, r - d(0.8f), stroke)

        stroke.color = InkPalette.GOLD_DIM
        stroke.strokeWidth = d(0.6f)
        c.drawCircle(r, r, r * 0.84f, stroke)
    }

    /** 背面的内盘：实心铜金 + 方孔 + 四出短纹。与字面形成一眼可辨的明暗差。 */
    private fun drawInnerBack(c: Canvas) {
        val r = min(c.width, c.height) / 2f
        fill.color = InkPalette.GOLD
        c.drawCircle(r, r, r * 0.80f, fill)

        drawHole(c, r, r, r, punched = true, gold = false)
        drawFourNubs(c, r, r, InkPalette.BG)
    }

    /** 字面的内盘：墨底 + 金描方孔 + 四出短纹 + 「周易通宝」四字对读。 */
    private fun drawInnerFront(c: Canvas) {
        val r = min(c.width, c.height) / 2f
        fill.color = InkPalette.BG
        c.drawCircle(r, r, r * 0.80f, fill)

        stroke.color = InkPalette.GOLD_DIM
        stroke.strokeWidth = d(0.5f)
        c.drawCircle(r, r, r * 0.70f, stroke)

        drawHole(c, r, r, r, punched = false, gold = true)
        drawFourNubs(c, r, r, InkPalette.GOLD)

        labelPaint.color = InkPalette.GOLD
        labelPaint.textSize = r * 0.36f
        val ring = r * 0.52f
        drawCentered(c, CHAR_UP, r, r - ring)
        drawCentered(c, CHAR_DOWN, r, r + ring)
        drawCentered(c, CHAR_RIGHT, r + ring, r)
        drawCentered(c, CHAR_LEFT, r - ring, r)
    }

    private fun drawHole(c: Canvas, cx: Float, cy: Float, r: Float, punched: Boolean, gold: Boolean) {
        val h = r * 0.24f
        holeRect.set(cx - h, cy - h, cx + h, cy + h)
        if (punched) {
            fill.color = InkPalette.BG
            c.drawRect(holeRect, fill)
        } else {
            stroke.color = InkPalette.GOLD
            stroke.strokeWidth = d(0.8f)
            c.drawRect(holeRect, stroke)
        }
    }

    /** 孔外四向短「四出」纹 */
    private fun drawFourNubs(c: Canvas, cx: Float, cy: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = d(0.8f)
        val r = min(c.width, c.height) / 2f
        for (k in 0 until 4) {
            val a = Math.toRadians((45f + 90f * k).toDouble())
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            c.drawLine(cx + ux * r * 0.34f, cy + uy * r * 0.34f, cx + ux * r * 0.44f, cy + uy * r * 0.44f, stroke)
        }
    }

    private fun drawCentered(c: Canvas, text: String, x: Float, y: Float) {
        val fm = labelPaint.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, labelPaint)
    }

    // ── 绘制 ─────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = toss
        if (t == null) drawIdle(canvas) else drawTossFrames(canvas, t)
    }

    private fun drawIdle(canvas: Canvas) {
        val n = coinCount
        val jx = if (energy > 0f) (Math.random().toFloat() - 0.5f) * energy * d(7f) else 0f
        val jy = if (energy > 0f) (Math.random().toFloat() - 0.5f) * energy * d(7f) else 0f

        for (i in 0 until n) {
            val off = if (n == 1) 0f else (i - (n - 1) / 2f) * coinR * 0.62f
            drawCoin(
                canvas,
                x = cx + off + jx,
                y = cy + off * 0.16f + jy,
                r = coinR,
                back = true,
                rotation = idleAngle + i * 47f,
                squash = 1f,
                glow = 0f,
                alpha = 1f,
                scale = 1f,
            )
        }
    }

    private fun drawTossFrames(canvas: Canvas, t: Toss) {
        val b = if (quick) quickBeats else fullBeats
        val p = progress

        val appearU = phase(p, 0f, b.c1)
        val riseU = phase(p, b.c1, b.c2)
        val bounceU = phase(p, b.c2, b.c3)
        val flipU = phase(p, b.c3, b.c4)
        val showU = phase(p, b.c4, b.c5)
        val settleU = phase(p, b.c5, 1f)

        // 翻面过了中线就算「已揭面」，此刻锁定背 / 字
        val revealed = p >= b.c3 + (b.c4 - b.c3) * 0.5f

        val spread = if (p < b.c1) spreadStart else lerp(spreadStart, spreadEnd, easeOut(riseU))
        val squash = if (p in b.c3..b.c4 && b.c4 > b.c3) {
            abs(cos(PI.toFloat() * flipU)).coerceAtLeast(0.10f)
        } else 1f
        val glow = if (p in b.c4..b.c5) sin(PI.toFloat() * showU) else 0f
        val coinAlpha = (1f - settleU).coerceIn(0f, 1f)
        val coinScale = 1f - 0.55f * settleU

        for (i in 0 until 3) {
            val off = (i - 1) * spread
            val y = when {
                p < b.c1 -> stackY + d(4f) * sin(PI.toFloat() * appearU)
                p < b.c2 -> arcY(easeInOut(riseU), stackY, landY, apexLift)
                p < b.c3 -> bounceY(bounceU, i)
                else -> landY
            }
            val rot = rotationFor(p, b, i, riseU, bounceU)
            val back = if (revealed) i < t.backs else false

            drawCoin(canvas, cx + off, y, coinR, back, rot, squash, glow, coinAlpha, coinScale)

            if (p >= b.c4 && settleU < 1f) {
                val label = if (i < t.backs) "\u80cc" else "\u5b57" // 背 / 字
                labelPaint.color = if (i < t.backs) InkPalette.GOLD_BRIGHT else InkPalette.PAPER
                labelPaint.textSize = d(6.5f)
                labelPaint.alpha = ((showU) * coinAlpha * 255f).toInt().coerceIn(0, 255)
                drawCentered(canvas, label, cx + off, y - coinR - d(7f))
                labelPaint.alpha = 255
            }
        }
    }

    private fun rotationFor(p: Float, b: Beats, i: Int, riseU: Float, bounceU: Float): Float {
        val base = idleAngle + i * 47f
        if (p < b.c1) return base
        val spun = base + TURNS[i] * 360f * easeOut(riseU)
        if (p < b.c3) return spun
        // 带阻尼的滚转：幅度按拍内进度衰减，最终停摆
        val damp = (1f - bounceU) * (1f - bounceU)
        return spun + 90f * damp * sin(4f * PI.toFloat() * bounceU)
    }

    private fun bounceY(u: Float, i: Int): Float {
        val t0 = LAND_OFFSET[i]
        if (u < t0) return landY
        val local = ((u - t0) / (1f - LAND_OFFSET[2])).coerceIn(0f, 1f)
        val amp = d(10f) * (1f - local)
        return landY - amp * abs(sin(2f * PI.toFloat() * local))
    }

    private fun drawCoin(
        canvas: Canvas,
        x: Float, y: Float, r: Float,
        back: Boolean,
        rotation: Float,
        squash: Float,
        glow: Float,
        alpha: Float,
        scale: Float,
    ) {
        val inner = (if (back) innerBackBmp else innerFrontBmp) ?: return
        val rr = r * scale

        bitmapPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)

        // 显象拍的描金脉冲
        if (glow > 0.01f) {
            glowPaint.color = InkPalette.GOLD_BRIGHT
            glowPaint.strokeWidth = d(1.6f)
            glowPaint.alpha = (glow * alpha * 200f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, rr * 1.04f, glowPaint)
        }

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(1f, squash)
        canvas.drawBitmap(inner, -rr, -rr, bitmapPaint)
        canvas.restore()

        // 外轮不跟着转
        val ring = ringBmp ?: return
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f, squash)
        canvas.drawBitmap(ring, -rr, -rr, bitmapPaint)
        canvas.restore()

        bitmapPaint.alpha = 255
    }

    // ── 缓动 ─────────────────────────────────────────────────

    private fun phase(p: Float, from: Float, to: Float): Float {
        if (to <= from) return 0f
        return ((p - from) / (to - from)).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u

    private fun easeOut(u: Float) = 1f - (1f - u) * (1f - u)

    private fun easeInOut(u: Float) = if (u < 0.5f) 2f * u * u else 1f - 2f * (1f - u) * (1f - u)

    private fun arcY(u: Float, fromY: Float, toY: Float, lift: Float): Float =
        lerp(fromY, toY, u) - lift * sin(PI.toFloat() * u)
}
