package com.subtitleedit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityFileManagementSettingsBinding
import com.subtitleedit.util.SettingsManager

class FileManagementSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityFileManagementSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val settings = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.switchShowAllFileTypes.isChecked = settings.isShowAllFileTypesEnabled()
        binding.switchShowHiddenFiles.isChecked = settings.isShowHiddenFilesEnabled()
        binding.switchShowAllFileTypes.setOnCheckedChangeListener { _, checked ->
            settings.setShowAllFileTypesEnabled(checked)
        }
        binding.switchShowHiddenFiles.setOnCheckedChangeListener { _, checked ->
            settings.setShowHiddenFilesEnabled(checked)
        }
    }
}
