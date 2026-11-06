package com.expuz.ai.ui.onboarding

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.expuz.ai.ExpuzApp
import com.expuz.ai.R
import com.expuz.ai.databinding.ActivityOnboardingBinding
import com.expuz.ai.domain.ConnectionTestResult
import com.expuz.ai.domain.SaveResult
import com.expuz.ai.domain.ServerManager
import com.expuz.ai.ui.UiText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var serverManager: ServerManager
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverManager = (application as ExpuzApp).container.serverManager

        applyInsets()
        binding.testButton.setOnClickListener { testConnection(saveAfterTest = false) }
        binding.saveButton.setOnClickListener { save() }
        binding.serverUrlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                testConnection(saveAfterTest = false)
                true
            } else {
                false
            }
        }
        binding.serverUrlInput.doAfterTextChanged {
            binding.serverUrlLayout.error = null
        }

        lifecycleScope.launch {
            val config = serverManager.currentConfig()
            if (config.activeServerUrl.isNotBlank() && binding.serverUrlInput.text.isNullOrBlank()) {
                binding.serverUrlInput.setText(config.activeServerUrl)
                binding.serverUrlInput.setSelection(binding.serverUrlInput.text?.length ?: 0)
            }
        }
    }

    private fun applyInsets() {
        val baseTop = binding.onboardingScroll.paddingTop
        val baseBottom = binding.onboardingScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.onboardingScroll.updatePadding(
                top = baseTop + bars.top,
                bottom = baseBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.onboardingRoot)
    }

    private fun save() {
        if (busy) return
        val raw = binding.serverUrlInput.text?.toString().orEmpty()
        val validation = serverManager.validate(raw)
        if (validation is com.expuz.ai.domain.UrlValidation.Invalid) {
            binding.serverUrlLayout.error = UiText.urlProblem(this, validation.problem)
            return
        }
        testConnection(saveAfterTest = true)
    }

    private fun testConnection(saveAfterTest: Boolean) {
        if (busy) return

        val raw = binding.serverUrlInput.text?.toString().orEmpty()
        if (raw.isBlank()) {
            binding.serverUrlLayout.error = getString(R.string.url_error_empty)
            return
        }

        hideKeyboard()
        setBusy(true)
        binding.testResultCard.isVisible = false

        lifecycleScope.launch {
            val result = serverManager.testConnection(raw)
            if (isFinishing || isDestroyed) return@launch

            setBusy(false)
            if (result.urlProblem != null) {
                binding.serverUrlLayout.error = UiText.urlProblem(this@OnboardingActivity, result.urlProblem)
                binding.testResultCard.isVisible = false
                return@launch
            }

            binding.serverUrlLayout.error = null
            result.normalizedUrl?.let { normalized ->
                binding.serverUrlInput.setText(normalized)
                binding.serverUrlInput.setSelection(normalized.length)
            }
            showTestResult(result)

            if (!saveAfterTest) return@launch

            val normalized = result.normalizedUrl ?: return@launch
            if (result.reachable) {
                saveAndFinish(normalized)
            } else {
                showUnreachableDialog(normalized)
            }
        }
    }

    private fun showTestResult(result: ConnectionTestResult) {
        binding.testResultCard.isVisible = true
        binding.testResultText.text = UiText.testSummary(this, result)
        val good = result.reachable
        binding.testResultIcon.setImageResource(
            if (good) R.drawable.ic_check_circle else R.drawable.ic_warning
        )
        binding.testResultIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (good) R.color.expuz_success else R.color.expuz_error)
        )
    }

    private fun showUnreachableDialog(normalizedUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_unreachable_title)
            .setMessage(R.string.onboarding_unreachable_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.onboarding_save_anyway) { _, _ ->
                lifecycleScope.launch {
                    setBusy(true)
                    saveAndFinish(normalizedUrl)
                }
            }
            .show()
    }

    private suspend fun saveAndFinish(normalizedUrl: String) {
        val result = serverManager.completeOnboarding(normalizedUrl)
        setBusy(false)
        when (result) {
            is SaveResult.Success -> {
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_SERVER_URL, result.config.activeServerUrl),
                )
                finish()
            }

            is SaveResult.Invalid -> {
                binding.serverUrlLayout.error = UiText.urlProblem(this, result.problem)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.testButton.isEnabled = !value
        binding.saveButton.isEnabled = !value
        binding.serverUrlInput.isEnabled = !value
        binding.onboardingProgress.isVisible = value
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return
        inputMethodManager.hideSoftInputFromWindow(binding.serverUrlInput.windowToken, 0)
        binding.serverUrlInput.clearFocus()
    }

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"
    }
}
