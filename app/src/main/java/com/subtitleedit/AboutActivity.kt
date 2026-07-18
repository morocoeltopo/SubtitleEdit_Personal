package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ActivityAboutBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.UpdateChecker
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.tvVersion.text = getString(R.string.version_format, versionName)

        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }

        binding.btnGithub.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/nihaina/SubtitleEditforAndroid")
                )
            )
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "检测中…"
        lifecycleScope.launch {
            when (val result = UpdateChecker.checkResult(this@AboutActivity)) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    UpdateChecker.showUpdateDialog(this@AboutActivity, result.update)
                }
                UpdateChecker.CheckResult.UpToDate -> {
                    OverwritingToast.makeText(
                        this@AboutActivity,
                        "当前已是最新版本",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                UpdateChecker.CheckResult.Failure -> {
                    OverwritingToast.makeText(
                        this@AboutActivity,
                        "检测更新失败，请检查网络后重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.setText(R.string.check_for_updates)
        }
    }
}
