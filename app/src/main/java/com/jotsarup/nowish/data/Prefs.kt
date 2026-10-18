package com.jotsarup.nowish.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nowish", Context.MODE_PRIVATE)

    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(value) = sp.edit().putBoolean("sound", value).apply()

    var space: Space
        get() = runCatching { Space.valueOf(sp.getString("space", null) ?: "PERSONAL") }
            .getOrDefault(Space.PERSONAL)
        set(value) = sp.edit().putString("space", value.name).apply()
}
