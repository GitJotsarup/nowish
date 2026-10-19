package com.jotsarup.nowish.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.jotsarup.nowish.R
import com.jotsarup.nowish.data.Prefs

/** The little "cha-ching" when a task gets cleared. */
class SoundFx(context: Context, private val prefs: Prefs) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val success = pool.load(context, R.raw.success, 1)

    fun success() {
        if (prefs.soundOn) pool.play(success, 1f, 1f, 1, 0, 1f)
    }
}
