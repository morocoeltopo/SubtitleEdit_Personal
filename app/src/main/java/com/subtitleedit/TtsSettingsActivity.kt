package com.subtitleedit

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.subtitleedit.databinding.ActivityTtsSettingsBinding
import com.subtitleedit.util.SettingsManager

/** 系统 TTS 引擎选择页面。 */
class TtsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsSettingsBinding
    private lateinit var settingsManager: SettingsManager

    private data class EngineOption(val label: String, val packageName: String) {
        override fun toString(): String = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        loadEngines()
        setupLanguageSpinner()
        setupActions()
    }

    @Suppress("DEPRECATION")
    private fun loadEngines() {
        val resolveInfos = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL
        )
        val installed = resolveInfos.mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            EngineOption(
                label = info.loadLabel(packageManager)?.toString()?.ifBlank { serviceInfo.packageName }
                    ?: serviceInfo.packageName,
                packageName = serviceInfo.packageName
            )
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

        val options = listOf(EngineOption("系统默认", "")) + installed
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerTtsEngine.adapter = adapter

        val savedEngine = settingsManager.getTtsEngine()
        val selectedIndex = options.indexOfFirst { it.packageName == savedEngine }
        if (selectedIndex >= 0) {
            binding.spinnerTtsEngine.setSelection(selectedIndex)
        } else {
            settingsManager.setTtsEngine("")
            binding.spinnerTtsEngine.setSelection(0)
        }

        binding.tvEngineStatus.text = if (installed.isEmpty()) {
            "未检测到可用的朗读引擎，请先在系统中安装或启用 TTS 引擎。"
        } else {
            "检测到 ${installed.size} 个朗读引擎；选择后立即保存。"
        }
        binding.spinnerTtsEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settingsManager.setTtsEngine(options[position].packageName)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupLanguageSpinner() {
        val options = listOf(
            "自动判断" to SettingsManager.TTS_LANGUAGE_AUTO,
            "跟随系统" to SettingsManager.TTS_LANGUAGE_SYSTEM,
            "日语（日本）" to SettingsManager.TTS_LANGUAGE_JAPANESE,
            "中文（简体）" to SettingsManager.TTS_LANGUAGE_CHINESE,
            "英语（美国）" to SettingsManager.TTS_LANGUAGE_ENGLISH
        )
        binding.spinnerTtsLanguage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options.map { it.first }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedIndex = options.indexOfFirst { it.second == settingsManager.getTtsLanguage() }
            .coerceAtLeast(0)
        binding.spinnerTtsLanguage.setSelection(selectedIndex)
        binding.spinnerTtsLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settingsManager.setTtsLanguage(options[position].second)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() {
        binding.tvHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_help)
                .setMessage(R.string.tts_help_message)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
        binding.btnSystemTtsSettings.setOnClickListener {
            openSystemTtsSettings()
        }
    }

    private fun openSystemTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            // 少数定制系统未公开独立 TTS 页面，退回到无障碍设置入口。
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
