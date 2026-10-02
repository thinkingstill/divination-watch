package com.wearcast.liuyao.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 一爻的四种形态。
 * 传统金钱卦以三枚铜钱的「背」数决定：3 背 = 老阳，2 背 = 少阴，1 背 = 少阳，0 背 = 老阴。
 */
enum class YaoType(val label: String, val yang: Boolean, val moving: Boolean) {
    SHAO_YANG("少阳", true, false),
    SHAO_YIN("少阴", false, false),
    LAO_YANG("老阳", true, true),
    LAO_YIN("老阴", false, true);

    companion object {
        fun of(backs: Int): YaoType = when (backs) {
            3 -> LAO_YANG
            2 -> SHAO_YIN
            1 -> SHAO_YANG
            else -> LAO_YIN
        }
    }
}

/** 一次掷币的结果 */
data class Toss(
    /** 三枚铜钱的 8 种正反组合之一，0..7 */
    val combo: Int,
    /** 背面枚数 0..3 */
    val backs: Int,
    val type: YaoType,
)

/**
 * 熵 → 铜钱组合的映射（方案 v0.2 决策 ①：物理熵 + 系统 CSPRNG 混合）。
 *
 * 设计要点：
 *  - **物理熵只作为混淆位参与**，不单独决定取值，因为它可能有偏；
 *  - 最终取值由 `SecureRandom.nextInt(8)` 提供，在 8 种组合上严格均匀；
 *  - 混淆方式为 **异或**：r = nextInt(8) XOR noise3。
 *    均匀随机变量与任意独立变量异或后仍严格均匀，因此分布不被破坏；
 *  - 于是「背」的枚数严格服从 Binomial(3, 1/2)：
 *    老阳 1/8、少阴 3/8、少阳 3/8、老阴 1/8，
 *    与三枚独立铜钱的真实概率完全一致。
 */
object CoinMapper {

    private val rng = SecureRandom()

    /** 每次混淆取用的熵池切片长度（字节） */
    private const val SLICE_LEN = 32

    /**
     * 一次掷币。
     *
     * @param entropy  物理熵池；在沉浸模式下每次摇动都是一份新熵池
     * @param nanoTime 采集时刻，参与混淆，保证同一份熵池不会给出同一结果
     * @param slice    熵池切片序号。一键模式只有一次采集却要连出六爻，
     *                 于是六爻各取熵池的不同切片做混淆位（见方案 v0.3 §4.2 ②）。
     *                 混淆只是异或，切片怎么取都不影响分布；六爻之间的独立性由
     *                 `rng.nextInt(8)` 的连续六次抽样保证。
     */
    fun toss(entropy: ByteArray, nanoTime: Long, slice: Int = 0): Toss {
        val seed = ByteArray(8).also { rng.nextBytes(it) }
        val noise3 = mix(entropy, seed, nanoTime, slice)[0].toInt() and 0x07
        val combo = rng.nextInt(8) xor noise3
        val backs = Integer.bitCount(combo)
        return Toss(combo, backs, YaoType.of(backs))
    }

    /** 物理熵池 + CSPRNG 种子 + 纳秒时间戳 → SHA-256 摘要，取低位作为混淆位 */
    private fun mix(entropy: ByteArray, seed: ByteArray, nanoTime: Long, slice: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        if (entropy.isNotEmpty()) {
            val offset = (slice * SLICE_LEN).mod(entropy.size)
            val len = minOf(SLICE_LEN, entropy.size - offset)
            md.update(entropy, offset, len)
        }
        md.update(seed)
        md.update(
            byteArrayOf(
                (nanoTime ushr 56).toByte(), (nanoTime ushr 48).toByte(),
                (nanoTime ushr 40).toByte(), (nanoTime ushr 32).toByte(),
                (nanoTime ushr 24).toByte(), (nanoTime ushr 16).toByte(),
                (nanoTime ushr 8).toByte(), nanoTime.toByte(),
            )
        )
        return md.digest()
    }
}
