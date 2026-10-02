package com.wearcast.liuyao.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * 摇晃识别：三重判定（低通滤波 / 动态基线 / 阈值）+ 峰值波形校验 + 防抖 + 陀螺仪交叉验证。
 *
 * 使用约定（与方案 v0.2 的能力边界一致）：
 *  - 只在摇卦页前台调用 [start]；一次出爻采集完成后调用 [stop] 立即释放传感器；
 *  - 单次采样窗口 1.2 秒，六爻合计约 8 秒，处于「前台短时持有」的安全区。
 *
 * 调整手感只改 [DEFAULT_THRESHOLD_G]。
 */
class ShakeDetector(
    context: Context,
    private val onCaptured: (pool: ByteArray, peakG: Float) -> Unit,
    private val onSample: (lpG: Float) -> Unit = {},
    private val thresholdG: Float = DEFAULT_THRESHOLD_G,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : SensorEventListener {

    companion object {
        const val TAG = "LiuYao"
        private const val G = 9.80665f

        /** 触发阈值（G）。真机调手感就改这一个值：摇不出来的话往 2.2 调，误触发就往 2.8 调。 */
        const val DEFAULT_THRESHOLD_G = 2.5f

        /**
         * 一键模式的「轻摇确认」阈值（方案 v0.3 8.1 决策 10）。
         * 比沉浸模式低 0.5 G —— 确认动作不该比正式摇卦还费劲。
         */
        const val QUICK_THRESHOLD_G = DEFAULT_THRESHOLD_G - 0.5f

        /** 一次出爻的采样窗口 */
        const val DEFAULT_WINDOW_MS = 1200L
        private const val DEFAULT_DEBOUNCE_MS = 500L

        /** 陀螺仪交叉验证下限（rad/s），过滤「走路摆臂」这类平移运动 */
        private const val GYRO_MIN = 1.0f

        /** 低通滤波系数 */
        private const val LP_ALPHA = 0.35f

        /** 一次有效采集至少需要多少帧（约 50Hz × 1.2s ≈ 60 帧） */
        private const val MIN_FRAMES = 12
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val sampler = EntropySampler()

    private var lowPass = G
    private var baseline = G
    private var capturing = false
    private var captureUntil = 0L
    private var lastTrigger = 0L
    private var started = false

    /** 加速度计是否可用（不可用时上层应给出提示） */
    val available: Boolean get() = accelSensor != null

    fun start() {
        if (started) return
        started = true
        sampler.reset()
        lowPass = G
        baseline = G
        lastTrigger = 0L
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        capturing = false
    }

    /** 当前是否在采集窗口内（上层据此显示"正在感应"状态） */
    val isCapturing: Boolean get() = capturing

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()

        if (!capturing) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt(x * x + y * y + z * z)
                lowPass = LP_ALPHA * mag + (1 - LP_ALPHA) * lowPass
                // 动态基线：缓慢跟随佩戴姿态，让阈值对姿态自适应
                baseline = 0.98f * baseline + 0.02f * lowPass
                onSample(lowPass / G)

                if (now - lastTrigger > debounceMs && lowPass >= thresholdG * G) {
                    lastTrigger = now
                    capturing = true
                    captureUntil = now + windowMs
                    sampler.reset()
                    sampler.accept(event)
                }
            }
            return
        }

        sampler.accept(event)
        if (now >= captureUntil) finishCapture(now)
    }

    private fun finishCapture(now: Long) {
        capturing = false
        lastTrigger = now
        val peakG = sampler.peakMagnitude / G
        val gyroPeak = sampler.gyroPeak()
        val frames = sampler.frames

        val valid = frames >= MIN_FRAMES && peakG >= thresholdG && gyroPeak >= GYRO_MIN
        val verdict = if (valid) "valid" else "rejected"
        Log.d(
            TAG,
            "shake $verdict peak=%.2fG gyro=%.2f frames=%d baseline=%.2fG"
                .format(peakG, gyroPeak, frames, baseline / G)
        )
        if (valid) onCaptured(sampler.toPool(), peakG)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
