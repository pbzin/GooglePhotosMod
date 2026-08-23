package com.pbzin.googlephotosmod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ModuleSettings.REQUEST_SETTINGS_ACTION) return

        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val holdBackupJob = prefs.getBoolean(ModuleSettings.HOLD_BACKUP_JOB, false)

        val response = Intent(ModuleSettings.GET_SETTINGS_ACTION)
        response.setPackage("com.google.android.apps.photos")
        @Suppress("WrongConstant")
        response.addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
        response.putExtra(ModuleSettings.HOLD_BACKUP_JOB_RESULT_KEY, holdBackupJob)
        
        context.sendBroadcast(response)
    }
}
