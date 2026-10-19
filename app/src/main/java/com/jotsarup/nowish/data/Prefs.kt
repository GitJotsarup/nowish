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

    /** "owner/repo" of the private repo that holds nowish.json. */
    var syncRepo: String
        get() = sp.getString("sync_repo", null) ?: DEFAULT_SYNC_REPO
        set(value) = sp.edit().putString("sync_repo", value.trim()).apply()

    var syncToken: String
        get() = sp.getString("sync_token", null) ?: ""
        set(value) = sp.edit().putString("sync_token", value.trim()).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync", 0L)
        set(value) = sp.edit().putLong("last_sync", value).apply()

    var lastSyncError: String?
        get() = sp.getString("last_sync_error", null)
        set(value) = sp.edit().putString("last_sync_error", value).apply()

    // Notes lock (PIN hash), shared with the web app through sync.
    var lockSalt: String?
        get() = sp.getString("lock_salt", null)
        set(value) = sp.edit().putString("lock_salt", value).apply()

    var lockHash: String?
        get() = sp.getString("lock_hash", null)
        set(value) = sp.edit().putString("lock_hash", value).apply()

    var lockUpdatedAt: Long
        get() = sp.getLong("lock_updated", 0L)
        set(value) = sp.edit().putLong("lock_updated", value).apply()

    val lockConfigured: Boolean get() = !lockSalt.isNullOrEmpty() && !lockHash.isNullOrEmpty()

    var biometricUnlock: Boolean
        get() = sp.getBoolean("biometric", true)
        set(value) = sp.edit().putBoolean("biometric", value).apply()

    /** Note images already pushed to the data repo. */
    var uploadedImages: Set<String>
        get() = sp.getStringSet("uploaded_images", emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet("uploaded_images", value).apply()

    val syncConfigured: Boolean get() = syncToken.isNotBlank() && syncRepo.contains('/')

    companion object {
        const val DEFAULT_SYNC_REPO = "GitJotsarup/nowish-data"
    }
}
