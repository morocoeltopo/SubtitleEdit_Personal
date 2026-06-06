package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityAiSettingsBinding
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.SettingsManager

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var selectedProvider: String = AiProviderConfig.SILICONFLOW
    private var suppressTextSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI 翻译设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupProviderSpinner()
        loadSettings()
        setupSave()
    }

    private fun setupProviderSpinner() {
        val providerNames = AiProviderConfig.providers.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAiProvider.adapter = adapter
        binding.spinnerAiProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = AiProviderConfig.providers[position].id
                if (provider == selectedProvider) return
                saveCurrentProviderFields()
                selectedProvider = provider
                settingsManager.setAiProvider(provider)
                loadProviderFields(provider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadSettings() {
        selectedProvider = settingsManager.getAiProvider()
        binding.spinnerAiProvider.setSelection(AiProviderConfig.indexOf(selectedProvider))
        loadProviderFields(selectedProvider)
        binding.etSourceLanguage.setText(settingsManager.getAiSourceLanguage())
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())
    }

    private fun loadProviderFields(provider: String) {
        val config = AiProviderConfig.getProvider(provider)
        suppressTextSave = true
        binding.tvProviderTitle.text = "AI 翻译设置（${config.displayName}）"
        binding.tilApiKey.hint = "${config.displayName} API Key"
        binding.tilModel.hint = "模型名称（默认 ${config.defaultModel}）"
        binding.tvProviderHint.text = "请求地址：${config.apiUrl}"
        binding.etApiKey.setText(settingsManager.getAiApiKey(provider))
        binding.etModel.setText(settingsManager.getAiModel(provider))
        suppressTextSave = false
    }

    private fun saveCurrentProviderFields() {
        settingsManager.setAiApiKey(selectedProvider, binding.etApiKey.text?.toString()?.trim().orEmpty())
        settingsManager.setAiModel(selectedProvider, binding.etModel.text?.toString()?.trim().orEmpty())
    }

    private fun setupSave() {
        binding.etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiApiKey(selectedProvider, s.toString().trim())
            }
        })
        binding.etModel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiModel(selectedProvider, s.toString().trim())
            }
        })
        binding.etSourceLanguage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiSourceLanguage(s.toString().trim())
            }
        })
        binding.etTargetLanguage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiTargetLanguage(s.toString().trim())
            }
        })
    }
}
