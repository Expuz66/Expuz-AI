package com.expuz.ai.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.expuz.ai.R
import com.expuz.ai.databinding.DialogUpdateBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

object UpdateDialog {
    fun show(
        owner: LifecycleOwner,
        release: UpdateRelease,
        installedVersion: String,
    ) {
        val activity = owner as? androidx.fragment.app.FragmentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val binding = DialogUpdateBinding.inflate(activity.layoutInflater)
        binding.updateVersions.text = activity.getString(
            R.string.update_versions,
            installedVersion,
            release.tag,
        )
        binding.updateReleaseName.text = release.name
        binding.updateChangelog.text = release.body.ifBlank { activity.getString(R.string.settings_update_never_checked) }
        if (release.assetUrl == null) binding.updateNow.text = activity.getString(R.string.open)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .create()
        configureWindow(dialog.window)

        var downloadJob: Job? = null
        dialog.setOnDismissListener { downloadJob?.cancel() }
        binding.updateLater.setOnClickListener { dialog.dismiss() }
        binding.updateNow.setOnClickListener {
            val assetUrl = release.assetUrl
            if (assetUrl == null) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                dialog.dismiss()
                return@setOnClickListener
            }
            if (downloadJob?.isActive == true) return@setOnClickListener
            binding.updateNow.isEnabled = false
            binding.updateLater.isEnabled = false
            binding.updateProgress.isVisible = true
            binding.updateProgressBar.isVisible = true
            downloadJob = activity.lifecycleScope.launch {
                try {
                    val file = ApkDownloader(activity).download(release) { progress ->
                        activity.runOnUiThread {
                            val total = progress.total
                            binding.updateProgress.text = if (total > 0) {
                                "${formatBytes(progress.downloaded)} / ${formatBytes(total)}"
                            } else {
                                formatBytes(progress.downloaded)
                            }
                            if (total > 0) {
                                binding.updateProgressBar.progress =
                                    ((progress.downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                    when (ApkInstaller(activity).install(file)) {
                        InstallResult.Started -> dialog.dismiss()
                        InstallResult.PermissionRequired -> {
                            binding.updateProgress.text = activity.getString(
                                R.string.update_install_permission,
                            )
                        }
                        InstallResult.InvalidApk -> {
                            binding.updateProgress.text = activity.getString(R.string.update_invalid_apk)
                            binding.updateLater.isEnabled = true
                        }
                    }
                } catch (error: Exception) {
                    binding.updateProgress.text = activity.getString(
                        R.string.update_download_failed,
                        error.message ?: "Unknown error",
                    )
                    binding.updateLater.isEnabled = true
                } finally {
                    binding.updateNow.isEnabled = true
                }
            }
        }
        dialog.show()
        configureWindow(dialog.window)
    }

    private fun configureWindow(window: Window?) {
        window ?: return
        window.setBackgroundDrawableResource(R.drawable.bg_update_dialog)
        window.attributes = window.attributes.apply {
            dimAmount = 0.45f
            width = WindowManager.LayoutParams.MATCH_PARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(28)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
