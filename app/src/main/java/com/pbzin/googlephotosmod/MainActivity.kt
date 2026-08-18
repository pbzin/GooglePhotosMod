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
            text = "Google Photos Mod"
            textSize = 22f
        }, matchParams())

        content.addView(TextView(this).apply {
            text = "Configurações do módulo LSPosed"
            textSize = 15f
            alpha = 0.7f
        }, matchParams())

        content.addView(SwitchCompat(this).apply {
            text = "Forçar decoder HEVC por software"
            textSize = 16f
            isChecked = preferences.getBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit()
                    .putBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, enabled)
                    .commit()
                makePreferencesReadable()
                broadcastSetting(enabled)
                Toast.makeText(
                    context,
                    if (enabled) "Decoder HEVC por software ativado" else "Decoder padrão restaurado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 32
        })

        content.addView(SwitchCompat(this).apply {
            text = "Manter Job de upload ativo sob pressão"
            textSize = 16f
            isChecked = preferences.getBoolean(ModuleSettings.HOLD_BACKUP_JOB, false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit()
                    .putBoolean(ModuleSettings.HOLD_BACKUP_JOB, enabled)
                    .commit()
                makePreferencesReadable()
                broadcastSettings(
                    preferences.getBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, false),
                    enabled
                )
                Toast.makeText(
                    context,
                    if (enabled) "Retenção temporária de upload ativada" else "Retenção de upload desativada",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 18
        })

        content.addView(TextView(this).apply {
            text = "Aplica-se somente aos Jobs de backup conhecidos e apenas quando houver tráfego recente. O encerramento é adiado uma única vez por até 60 segundos."
            textSize = 14f
            alpha = 0.75f
        }, matchParams().apply { topMargin = 8 })

        content.addView(TextView(this).apply {
            text = "Ative para vídeos HEVC de alta resolução que exibem “não foi possível reproduzir”. Desative para voltar ao decoder padrão. A alteração vale no próximo vídeo; force o encerramento do Google Fotos se necessário."
            textSize = 14f
            alpha = 0.75f
        }, matchParams().apply { topMargin = 12 })

        setContentView(content)
        makePreferencesReadable()
        broadcastSettings(
            preferences.getBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, false),
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

    private fun broadcastSetting(enabled: Boolean) {
        broadcastSettings(enabled, false)
    }

    private fun broadcastSettings(hevcEnabled: Boolean, holdBackupJob: Boolean) {
        try {
            sendBroadcast(
                Intent(ModuleSettings.GET_SETTINGS_ACTION)
                    .setPackage("com.google.android.apps.photos")
                    .putExtra(ModuleSettings.ENABLED_RESULT_KEY, hevcEnabled)
                    .putExtra(ModuleSettings.HOLD_BACKUP_JOB_RESULT_KEY, holdBackupJob)
            )
        } catch (_: Throwable) {
        }
    }
}
