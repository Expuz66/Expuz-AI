package com.expuz.ai.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.expuz.ai.BuildConfig
import com.expuz.ai.ExpuzApp
import com.expuz.ai.R
import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.data.ServerConfig
import com.expuz.ai.databinding.ActivitySettingsBinding
import com.expuz.ai.domain.SaveResult
import com.expuz.ai.domain.ServerField
import com.expuz.ai.domain.ServerManager
import com.expuz.ai.domain.UrlPolicy
import com.expuz.ai.domain.UrlProblem
import com.expuz.ai.ui.UiText
import com.expuz.ai.update.UpdateDialog
import com.expuz.ai.update.UpdateManager
import com.expuz.ai.update.UpdateResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var serverManager: ServerManager
    private lateinit var urlPolicy: UrlPolicy
    private lateinit var updateManager: UpdateManager

    private var loadedConfig: ServerConfig? = null
    private var mode = ConnectionMode.MANUAL
    private var busy = false
    private var closing = false

    private val modes = listOf(
        ConnectionMode.MANUAL,
        ConnectionMode.AUTO,
        ConnectionMode.LOCAL_ONLY,
        ConnectionMode.REMOTE_ONLY,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setGravity(Gravity.END)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0f }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(32)
        }

        binding.settingsPanel.layoutParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            gravity = Gravity.END
        }
        binding.settingsScrim.setOnClickListener { closePanel() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closePanel()
        })

        val container = (application as ExpuzApp).container
        serverManager = container.serverManager
        urlPolicy = container.urlPolicy
        updateManager = container.updateManager

        applyInsets()
        setupToolbar()
        setupModeDropdown()
        setupFields()
        setupActions()
        setupUpdates()
        loadConfiguration()
    }

    private fun applyInsets() {
        val baseBottom = binding.settingsScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.settingsToolbar.updatePadding(top = bars.top)
            binding.settingsScroll.updatePadding(
                bottom = baseBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.settingsRoot)
    }

    private fun setupToolbar() {
        binding.settingsToolbar.setNavigationOnClickListener { closePanel() }
    }

    private fun closePanel() {
        if (closing || isFinishing) return
        closing = true
        hideKeyboard()
        val distance = binding.settingsPanel.width.toFloat()
        val duration = 300L
        binding.settingsScrim.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
        binding.settingsPanel.animate()
            .translationX(distance)
            .setDuration(duration)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    private fun setupModeDropdown() {
        val labels = modes.map { UiText.mode(this, it) }.toTypedArray()
        binding.modeDropdown.setSimpleItems(labels)
        binding.modeDropdown.setOnItemClickListener { _, _, position, _ ->
            mode = modes.getOrElse(position) { ConnectionMode.MANUAL }
            binding.modeDropdown.setText(labels[position], false)
            clearErrors()
            applyModeUi()
        }
    }

    private fun setupFields() {
        binding.primaryInput.doAfterTextChanged { binding.primaryLayout.error = null }
        binding.localInput.doAfterTextChanged { binding.localLayout.error = null }
        binding.remoteInput.doAfterTextChanged { binding.remoteLayout.error = null }
    }

    private fun setupActions() {
        binding.testButton.setOnClickListener { testCurrentSetup() }
        binding.saveButton.setOnClickListener { saveChanges() }
        binding.reloadButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_FRONTEND_ACTION, ACTION_RELOAD),
            )
            finish()
        }
        binding.clearCacheButton.setOnClickListener { confirmClearCache() }
    }

    private fun setupUpdates() {
        binding.updateInstalled.text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.updateCheck.setOnClickListener {
            if (busy) return@setOnClickListener
            binding.updateCheck.isEnabled = false
            binding.updateStatus.text = getString(R.string.status_connecting)
            lifecycleScope.launch {
                val checkedAt = java.text.SimpleDateFormat(
                    "HH:mm:ss",
                    java.util.Locale.getDefault(),
                ).format(java.util.Date())
                binding.updateLastChecked.text = checkedAt
                when (val result = updateManager.checkLatest(BuildConfig.VERSION_NAME)) {
                    UpdateResult.UpToDate -> {
                        binding.updateLatest.text = getString(R.string.settings_update_up_to_date)
                        binding.updateStatus.text = getString(R.string.settings_update_up_to_date)
                    }
                    is UpdateResult.Available -> {
                        binding.updateLatest.text = getString(
                            R.string.settings_update_latest,
                            result.release.tag,
                        )
                        binding.updateStatus.text = result.release.name
                        UpdateDialog.show(this@SettingsActivity, result.release, result.installedVersion)
                    }
                    is UpdateResult.Failure -> {
                        binding.updateLatest.text = getString(R.string.settings_update_unavailable)
                        binding.updateStatus.text = getString(
                            R.string.settings_update_check_failed,
                            result.message,
                        )
                    }
                }
                binding.updateCheck.isEnabled = true
            }
        }
    }

    private fun loadConfiguration() {
        lifecycleScope.launch {
            val config = serverManager.currentConfig()
            if (isFinishing || isDestroyed) return@launch
            bindConfiguration(config)
        }
    }

    private fun bindConfiguration(config: ServerConfig) {
        loadedConfig = config
        mode = config.mode

        val active = config.activeServerUrl
        binding.currentKind.text = UiText.kind(this, serverManager.serverKind(config))
        binding.currentMode.text = getString(
            R.string.settings_current_mode,
            UiText.mode(this, config.mode),
        )
        binding.currentUrl.text = active.ifBlank {
            getString(R.string.settings_current_unconfigured)
        }

        binding.primaryInput.setText(active)
        val activeHost = urlPolicy.hostOf(active)
        val activeIsLan = activeHost?.let { UrlPolicy.isLanHost(it) } == true
        val localPrefill = config.localUrl.ifBlank { if (activeIsLan) active else "" }
        val remotePrefill = config.remoteUrl.ifBlank {
            if (!activeIsLan && active.startsWith("https://")) active else ""
        }
        binding.localInput.setText(localPrefill)
        binding.remoteInput.setText(remotePrefill)

        binding.modeDropdown.setText(UiText.mode(this, mode), false)
        applyModeUi()

        binding.versionText.text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun applyModeUi() {
        binding.primaryLayout.isVisible = mode == ConnectionMode.MANUAL
        binding.localLayout.isVisible =
            mode == ConnectionMode.AUTO || mode == ConnectionMode.LOCAL_ONLY
        binding.remoteLayout.isVisible =
            mode == ConnectionMode.AUTO || mode == ConnectionMode.REMOTE_ONLY

        binding.modeHelp.setText(
            when (mode) {
                ConnectionMode.MANUAL -> R.string.settings_mode_single_help
                ConnectionMode.AUTO -> R.string.settings_mode_help
                ConnectionMode.LOCAL_ONLY -> R.string.settings_mode_local_help
                ConnectionMode.REMOTE_ONLY -> R.string.settings_mode_remote_help
            }
        )
        clearErrors()
    }

    private fun testCurrentSetup() {
        if (busy) return
        clearErrors()
        hideKeyboard()

        val config = loadedConfig
        val requests = mutableListOf<TestRequest>()

        when (mode) {
            ConnectionMode.MANUAL -> {
                val raw = binding.primaryInput.text?.toString().orEmpty()
                if (raw.isBlank()) {
                    binding.primaryLayout.error = getString(R.string.url_error_empty)
                    return
                }
                requests += TestRequest(null, raw)
            }

            ConnectionMode.AUTO -> {
                val local = binding.localInput.text?.toString().orEmpty()
                val remote = binding.remoteInput.text?.toString().orEmpty()
                if (local.isBlank() && remote.isBlank()) {
                    binding.localLayout.error = getString(R.string.settings_auto_required)
                    return
                }
                if (local.isNotBlank()) {
                    requests += TestRequest(R.string.settings_test_local, local)
                }
                if (remote.isNotBlank()) {
                    requests += TestRequest(R.string.settings_test_remote, remote)
                }
            }

            ConnectionMode.LOCAL_ONLY -> {
                val raw = binding.localInput.text?.toString().orEmpty()
                if (raw.isBlank()) {
                    binding.localLayout.error = getString(R.string.settings_local_required)
                    return
                }
                requests += TestRequest(R.string.settings_test_local, raw)
            }

            ConnectionMode.REMOTE_ONLY -> {
                val raw = binding.remoteInput.text?.toString().orEmpty()
                if (raw.isBlank()) {
                    binding.remoteLayout.error = getString(R.string.settings_remote_required)
                    return
                }
                requests += TestRequest(R.string.settings_test_remote, raw)
            }
        }

        setBusy(true)
        binding.testResultCard.isVisible = false

        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            var allReachable = true
            var firstProblemField: ServerField? = null
            var firstProblem: UrlProblem? = null

            for (request in requests) {
                val result = serverManager.testConnection(request.raw, config)
                val summary = UiText.testSummary(this@SettingsActivity, result)
                lines += if (request.labelRes == null) {
                    summary
                } else {
                    getString(request.labelRes, summary)
                }
                if (!result.reachable) allReachable = false

                if (result.urlProblem != null) {
                    if (request.labelRes == R.string.settings_test_remote) {
                        firstProblemField = ServerField.REMOTE
                    } else if (request.labelRes == R.string.settings_test_local) {
                        firstProblemField = ServerField.LOCAL
                    } else {
                        firstProblemField = ServerField.PRIMARY
                    }
                    firstProblem = result.urlProblem
                }
            }

            setBusy(false)
            if (isFinishing || isDestroyed) return@launch

            showTestResult(lines.joinToString("\n"), allReachable)
            if (firstProblemField != null && firstProblem != null) {
                showFieldError(firstProblemField, firstProblem)
            }
        }
    }

    private fun showTestResult(text: String, success: Boolean) {
        binding.testResultCard.isVisible = true
        binding.testResultText.text = text
        binding.testResultIcon.setImageResource(
            if (success) R.drawable.ic_check_circle else R.drawable.ic_warning
        )
        binding.testResultIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (success) R.color.expuz_success else R.color.expuz_error)
        )
    }

    private fun saveChanges() {
        if (busy) return
        clearErrors()
        hideKeyboard()

        lifecycleScope.launch {
            setBusy(true)
            val prepared = serverManager.prepareConfiguration(
                mode = mode,
                primaryRaw = binding.primaryInput.text?.toString().orEmpty(),
                localRaw = binding.localInput.text?.toString().orEmpty(),
                remoteRaw = binding.remoteInput.text?.toString().orEmpty(),
            )
            setBusy(false)

            when (prepared) {
                is SaveResult.Invalid -> {
                    showFieldError(prepared.field, prepared.problem)
                }

                is SaveResult.Success -> {
                    val oldUrl = loadedConfig?.activeServerUrl.orEmpty()
                    val originChanged = oldUrl.isNotBlank() &&
                        !urlPolicy.sameOrigin(oldUrl, prepared.config.activeServerUrl)

                    if (originChanged) {
                        confirmOriginChange(prepared.config)
                    } else {
                        persist(prepared.config)
                    }
                }
            }
        }
    }

    private fun confirmOriginChange(config: ServerConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_server_changed_title)
            .setMessage(R.string.settings_server_changed_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                persist(config)
            }
            .show()
    }

    private fun persist(config: ServerConfig) {
        lifecycleScope.launch {
            setBusy(true)
            when (val result = serverManager.persistConfiguration(config)) {
                is SaveResult.Success -> {
                    setResult(RESULT_OK)
                    closePanel()
                }

                is SaveResult.Invalid -> {
                    setBusy(false)
                    showFieldError(result.field, result.problem)
                }
            }
        }
    }

    private fun showFieldError(field: ServerField, problem: UrlProblem) {
        val message = when {
            field == ServerField.LOCAL && problem == UrlProblem.EMPTY &&
                mode == ConnectionMode.AUTO -> getString(R.string.settings_auto_required)

            field == ServerField.LOCAL && problem == UrlProblem.EMPTY ->
                getString(R.string.settings_local_required)

            field == ServerField.REMOTE && problem == UrlProblem.EMPTY ->
                getString(R.string.settings_remote_required)

            else -> UiText.urlProblem(this, problem)
        }

        when (field) {
            ServerField.PRIMARY -> {
                binding.primaryLayout.error = message
                binding.primaryInput.requestFocus()
            }

            ServerField.LOCAL -> {
                binding.localLayout.error = message
                binding.localInput.requestFocus()
            }

            ServerField.REMOTE -> {
                binding.remoteLayout.error = message
                binding.remoteInput.requestFocus()
            }
        }
    }

    private fun clearErrors() {
        binding.primaryLayout.error = null
        binding.localLayout.error = null
        binding.remoteLayout.error = null
    }

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_cache_title)
            .setMessage(R.string.settings_clear_cache_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_cache_confirm) { _, _ ->
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_FRONTEND_ACTION, ACTION_CLEAR_CACHE),
                )
                closePanel()
            }
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.settingsProgress.isVisible = value
        binding.testButton.isEnabled = !value
        binding.saveButton.isEnabled = !value
        binding.modeDropdown.isEnabled = !value
        binding.primaryInput.isEnabled = !value
        binding.localInput.isEnabled = !value
        binding.remoteInput.isEnabled = !value
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return
        inputMethodManager.hideSoftInputFromWindow(binding.settingsRoot.windowToken, 0)
        binding.settingsRoot.clearFocus()
    }

    private data class TestRequest(
        val labelRes: Int?,
        val raw: String,
    )

    companion object {
        const val EXTRA_FRONTEND_ACTION = "extra_frontend_action"
        const val ACTION_RELOAD = "reload"
        const val ACTION_CLEAR_CACHE = "clear_cache"
    }
}
