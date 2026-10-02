package com.wearcast.liuyao.data

import android.content.Context

/** 应用设置。v1 只有一项：音效开关（决策 ⑤ = 可选、默认关）。 */
object Prefs {

    private const val NAME = "liuyao"
    private const val KEY_SOUND = "sound_on"

    fun soundOn(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, false)

    fun setSoundOn(context: Context, on: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SOUND, on).apply()
    }
}
