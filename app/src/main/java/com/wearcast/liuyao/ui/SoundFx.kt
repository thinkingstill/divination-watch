package com.wearcast.liuyao.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.wearcast.liuyao.R
import com.wearcast.liuyao.core.ShakeDetector

/**
 * 铜钱碰撞音。设置项默认关闭（决策 ⑤），开启后才真正播放。
 */
class SoundFx(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var sampleId = 0
    private var loaded = false

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            loaded = status == 0
            if (!loaded) Log.w(ShakeDetector.TAG, "coin.wav load failed status=$status")
        }
        runCatching { sampleId = pool.load(context, R.raw.coin, 1) }
            .onFailure { Log.w(ShakeDetector.TAG, "coin.wav load threw: $it") }
    }

    fun play() {
        if (!loaded || sampleId == 0) return
        pool.play(sampleId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        runCatching { pool.release() }
    }
}
