package org.openrecorder.app.data

import android.content.Context
import androidx.core.content.edit

/** Whether the first-launch setup is still to be shown. Only new installs see it; the settings can open it again. */
class SetupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    /** [used]: there are recordings or downloaded models. Unless the setup was already started, that is not a new install. */
    fun needed(used: Boolean): Boolean {
        if (prefs.getBoolean("done", false)) return false
        if (used && !prefs.getBoolean("started", false)) { finish(); return false }
        prefs.edit { putBoolean("started", true) }
        return true
    }

    fun finish() = prefs.edit { putBoolean("done", true) }
}
