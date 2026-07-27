package com.subtitleedit

import android.os.Bundle
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivitySpeechToSubtitleSettingsBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import java.util.Locale

class SpeechToSubtitleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeechToSubtitleSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeechToSubtitleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupListeners()
        loadSettings()
        binding.tvWhisperThreadsTitle.visibility = View.GONE
        binding.tvWhisperThreadsHint.visibility = View.GONE
        binding.layoutWhisperThreads.visibility = View.GONE
        binding.cardHotwords.visibility = View.GONE
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "语音转字幕配置"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupListeners() {
        binding.switchVadDynamicPadding.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechVadDynamicPaddingEnabled(checked)
        }

        binding.switchHotwords.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechHotwordsEnabled(checked)
        }

        binding.sliderFixedSegmentSeconds.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etFixedSegmentSeconds.setText(String.format(Locale.US, "%d", value.toInt()))
            if (!loading) settingsManager.setSpeechFixedSegmentSeconds(value.toInt())
        }
        binding.etFixedSegmentSeconds.addTextChangedListener(simpleTextWatcher {
            val value = it.toIntOrNull() ?: return@simpleTextWatcher
            val clamped = value.coerceIn(5, 120)
            val snapped = ((clamped + 2) / 5) * 5
            if (binding.sliderFixedSegmentSeconds.value.toInt() != snapped) {
                binding.sliderFixedSegmentSeconds.value = snapped.toFloat()
            }
            if (!loading) settingsManager.setSpeechFixedSegmentSeconds(clamped)
        })

        binding.btnSaveHotwords.setOnClickListener {
            settingsManager.setSpeechHotwords(binding.etHotwords.text?.toString().orEmpty())
            OverwritingToast.makeText(this, "热词已保存", Toast.LENGTH_SHORT).show()
        }

        binding.sliderHotwordsScore.addOnChangeListener { _, value, _ ->
            binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", value)
            if (!loading) settingsManager.setSpeechHotwordsScore(value)
        }
        binding.sliderWhisperThreads.addOnChangeListener { _, value, _ ->
            binding.tvWhisperThreads.text = String.format(Locale.getDefault(), "%d", value.toInt())
            if (!loading) settingsManager.setSpeechWhisperThreads(value.toInt())
        }
    }

    private fun loadSettings() {
        loading = true

        val segmentSeconds = settingsManager.getSpeechFixedSegmentSeconds()
        binding.sliderFixedSegmentSeconds.value = segmentSeconds.toFloat()
        binding.etFixedSegmentSeconds.setText(String.format(Locale.US, "%d", segmentSeconds))
        binding.switchVadDynamicPadding.isChecked = settingsManager.isSpeechVadDynamicPaddingEnabled()

        binding.switchHotwords.isChecked = settingsManager.isSpeechHotwordsEnabled()
        binding.etHotwords.setText(settingsManager.getSpeechHotwords())

        val hotwordsScore = settingsManager.getSpeechHotwordsScore()
        binding.sliderHotwordsScore.value = hotwordsScore
        binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", hotwordsScore)

        val whisperThreads = settingsManager.getSpeechWhisperThreads()
        binding.sliderWhisperThreads.value = whisperThreads.toFloat()
        binding.tvWhisperThreads.text = String.format(Locale.getDefault(), "%d", whisperThreads)

        loading = false
    }

    private fun simpleTextWatcher(afterChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                afterChanged(s?.toString().orEmpty())
            }
        }
    }
}
