package com.pbzin.googlephotosmod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.File
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = try {
            @Suppress("DEPRECATION")
            getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        }, matchParams())

        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 15f
            alpha = 0.7f
        }, matchParams())

        content.addView(SwitchCompat(this).apply {
            text = getString(R.string.hold_backup_job_title)
            textSize = 16f
            isChecked = preferences.getBoolean(ModuleSettings.HOLD_BACKUP_JOB, false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit()
                    .putBoolean(ModuleSettings.HOLD_BACKUP_JOB, enabled)
                    .commit()
                makePreferencesReadable()
                broadcastSettings(enabled)
                Toast.makeText(
                    context,
                    if (enabled) getString(R.string.hold_backup_job_on) else getString(R.string.hold_backup_job_off),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 32
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.hold_backup_job_desc)
            textSize = 14f
            alpha = 0.75f
        }, matchParams().apply { topMargin = 8 })

        setContentView(content)
        makePreferencesReadable()
        broadcastSettings(
            preferences.getBoolean(ModuleSettings.HOLD_BACKUP_JOB, false)
        )
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun makePreferencesReadable() {
        try {
            filesDir.parentFile?.setExecutable(true, false)
            File(filesDir.parentFile, "shared_prefs/${ModuleSettings.PREFS_NAME}.xml")
                .setReadable(true, false)
        } catch (_: Throwable) {
        }
    }

    private fun broadcastSettings(holdBackupJob: Boolean) {
        try {
            val intent = Intent(ModuleSettings.GET_SETTINGS_ACTION)
            intent.setPackage("com.google.android.apps.photos")
            @Suppress("WrongConstant")
            intent.addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
            intent.putExtra(ModuleSettings.HOLD_BACKUP_JOB_RESULT_KEY, holdBackupJob)
            sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }
}
