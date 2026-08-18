package com.pbzin.googlephotosmod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ModuleSettings.REQUEST_SETTINGS_ACTION) return

        val enabled = context.getSharedPreferences(
            ModuleSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, false)
        val holdBackupJob = context.getSharedPreferences(
            ModuleSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(ModuleSettings.HOLD_BACKUP_JOB, false)

        context.sendBroadcast(
                Intent(ModuleSettings.GET_SETTINGS_ACTION)
                    .setPackage("com.google.android.apps.photos")
                    .putExtra(ModuleSettings.ENABLED_RESULT_KEY, enabled)
                    .putExtra(ModuleSettings.HOLD_BACKUP_JOB_RESULT_KEY, holdBackupJob)
            )
    }
}
