package org.openrecorder.app.data

import android.content.Context
import androidx.core.content.edit

/** Whether the first-launch setup is still to be shown. Only new installs see it; the settings can open it again. */
class SetupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    /** [used]: there are recordings or downloaded models, so this is not a new install and counts as set up. */
    fun needed(used: Boolean): Boolean {
        if (prefs.getBoolean("done", false)) return false
        if (used) { finish(); return false }
        return true
    }

    fun finish() = prefs.edit { putBoolean("done", true) }
}
