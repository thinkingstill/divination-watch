package com.wearcast.liuyao.core

import com.wearcast.liuyao.data.Hexagram
import com.wearcast.liuyao.data.HexagramData

/**
 * 六爻 → 本卦 / 变卦。
 *
 * 六爻自下而上排列，index 0 = 初爻。二进制同样以 bit0 为初爻。
 * 动爻（老阳 / 老阴）在本卦中按本象取阴阳，在变卦中取反；少阳少阴两卦一致。
 */
data class CastResult(val types: List<YaoType>) {

    init {
        require(types.size == 6) { "六爻必须有 6 个爻" }
    }

    val benBits: Int = types.foldIndexed(0) { i, acc, t -> if (t.yang) acc or (1 shl i) else acc }

    val bianBits: Int = types.foldIndexed(0) { i, acc, t ->
        val yang = if (t.moving) !t.yang else t.yang
        if (yang) acc or (1 shl i) else acc
    }

    val ben: Hexagram = HexagramData.lookup(benBits)
    val bian: Hexagram = HexagramData.lookup(bianBits)

    /** 动爻位次，自下而上 1..6 */
    val moving: List<Int> = types.indices.filter { types[it].moving }.map { it + 1 }

    val hasMoving: Boolean = moving.isNotEmpty()

    /** 本卦每一爻的阴阳（自下而上），用于画图与生成爻题 */
    val benYang: BooleanArray = BooleanArray(6) { types[it].yang }

    /** 变卦每一爻的阴阳（自下而上） */
    val bianYang: BooleanArray = BooleanArray(6) {
        val t = types[it]
        if (t.moving) !t.yang else t.yang
    }

    val movingFlags: BooleanArray = BooleanArray(6) { types[it].moving }

    /** 如「动爻：九三、上九」 */
    fun movingLabel(): String =
        "动爻：" + moving.joinToString("、") { HexagramData.yaoTitle(it, benYang[it - 1]) }
}
