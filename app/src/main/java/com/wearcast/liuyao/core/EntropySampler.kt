package com.wearcast.liuyao.core

import android.hardware.Sensor
import android.hardware.SensorEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * 一次摇动的物理熵池。
 *
 * 采集来源全部来自设备动作本身的不可预测性：
 *  · 加速度帧数、峰值位置与峰值大小
 *  · 合加速度去趋势后相邻差分的符号序列
 *  · 陀螺仪角速度分量的指定位
 *  · SensorEvent.timestamp 的纳秒末位
 *
 * 注意：本池只提供「混淆位」；最终爻象由 [CoinMapper] 用 CSPRNG 决定。
 */
class EntropySampler(private val capacity: Int = 192) {

    private val accelMag = FloatArray(capacity)
    private val accelStamp = LongArray(capacity)
    private var accelCount = 0

    private val gyro = FloatArray(capacity * 3)
    private val gyroStamp = LongArray(capacity)
    private var gyroCount = 0

    var peakMagnitude = 0f
        private set
    var peakIndex = 0
        private set

    val frames: Int get() = accelCount
    val gyroFrames: Int get() = gyroCount

    fun reset() {
        accelCount = 0
        gyroCount = 0
        peakMagnitude = 0f
        peakIndex = 0
    }

    fun accept(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (accelCount >= capacity) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val g = sqrt(x * x + y * y + z * z)
                accelMag[accelCount] = g
                accelStamp[accelCount] = event.timestamp
                if (g > peakMagnitude) {
                    peakMagnitude = g
                    peakIndex = accelCount
                }
                accelCount++
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (gyroCount >= capacity) return
                val i = gyroCount * 3
                gyro[i] = event.values[0]
                gyro[i + 1] = event.values[1]
                gyro[i + 2] = event.values[2]
                gyroStamp[gyroCount] = event.timestamp
                gyroCount++
            }
        }
    }

    /** 陀螺仪峰值角速度（rad/s），用于交叉验证「腕部甩动」而非「走路摆臂」 */
    fun gyroPeak(): Float {
        var max = 0f
        for (i in 0 until gyroCount) {
            val j = i * 3
            val v = sqrt(gyro[j] * gyro[j] + gyro[j + 1] * gyro[j + 1] + gyro[j + 2] * gyro[j + 2])
            if (v > max) max = v
        }
        return max
    }

    /** 把采集到的时序特征打包成混淆用的字节池 */
    fun toPool(): ByteArray {
        val bos = ByteArrayOutputStream(96)
        val out = DataOutputStream(bos)
        out.writeShort(accelCount)
        out.writeShort(gyroCount)
        out.writeShort(peakIndex)
        out.writeInt(peakMagnitude.toRawBits())
        out.writeByte(accelDiffSignBits())
        out.writeByte(gyroDiffSignBits())
        out.writeLong(if (accelCount > 0) accelStamp[accelCount - 1] else 0L)
        out.writeLong(if (gyroCount > 0) gyroStamp[gyroCount - 1] else 0L)
        // 抽稀塞入若干帧原始模长，提高熵密度
        var i = 0
        while (i < accelCount) {
            out.writeShort(accelMag[i].toRawBits() and 0xFFFF)
            i += 3
        }
        out.flush()
        return bos.toByteArray()
    }

    /** 相邻二阶差分的符号位（去趋势后的 ± 序列） */
    private fun accelDiffSignBits(): Int {
        var bits = 0
        var count = 0
        var i = 1
        while (i < accelCount && count < 8) {
            val d1 = accelMag[i] - accelMag[i - 1]
            val d0 = if (i >= 2) accelMag[i - 1] - accelMag[i - 2] else 0f
            if (d1 - d0 > 0f) bits = bits or (1 shl count)
            count++
            i++
        }
        return bits and 0xFF
    }

    private fun gyroDiffSignBits(): Int {
        var bits = 0
        var count = 0
        var i = 1
        while (i < gyroCount && count < 8) {
            val j = i * 3
            val p = (i - 1) * 3
            val d = (gyro[j] - gyro[p]) + (gyro[j + 1] - gyro[p + 1]) + (gyro[j + 2] - gyro[p + 2])
            if (d > 0f) bits = bits or (1 shl count)
            count++
            i++
        }
        return bits and 0xFF
    }

    companion object {
        /**
         * 仅供 debug 构建使用：合成一份结构与真实采集一致的熵池，
         * 用于在没有真实摇晃的情况下验证「出爻 → 成卦 → 结果页」链路。
         */
        fun syntheticPool(tag: Long): ByteArray {
            val bos = ByteArrayOutputStream(96)
            val out = DataOutputStream(bos)
            var x = tag
            repeat(48) {
                x = x * 6364136223846793005L + 1442695040888963407L
                out.writeByte(((x ushr 33) and 0xFF).toInt())
            }
            out.flush()
            return bos.toByteArray()
        }
    }
}
