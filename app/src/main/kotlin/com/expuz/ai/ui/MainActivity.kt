package com.expuz.ai.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.expuz.ai.BuildConfig
import com.expuz.ai.ExpuzApp
import com.expuz.ai.R
import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.data.ServerConfig
import com.expuz.ai.databinding.ActivityMainBinding
import com.expuz.ai.domain.FailureKind
import com.expuz.ai.domain.SaveResult
import com.expuz.ai.domain.ServerKind
import com.expuz.ai.domain.ServerManager
import com.expuz.ai.domain.ServerTarget
import com.expuz.ai.domain.UrlPolicy
import com.expuz.ai.domain.WebFailure
import com.expuz.ai.ui.onboarding.OnboardingActivity
import com.expuz.ai.ui.settings.SettingsActivity
import com.expuz.ai.util.NetworkMonitor
import com.expuz.ai.web.WebViewManager
import com.expuz.ai.update.UpdateDialog
import com.expuz.ai.update.UpdateManager
import com.expuz.ai.update.UpdateResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverManager: ServerManager
    private lateinit var urlPolicy: UrlPolicy
    private lateinit var webViewManager: WebViewManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var updateManager: UpdateManager

    private var currentConfig = ServerConfig()
    private var configurationLoaded = false
    private var onboardingLaunched = false
    private var activeBaseUrl: String? = null
    private var restoredUrl: String? = null
    private var hasRenderedPage = false
    private var pageState = PageState.IDLE
    private var networkOnline = true
    private var lastFailure: WebFailure? = null
    private var fallbackSuggestion: ServerTarget? = null
    private var fallbackJob: Job? = null
    private var lastBlockedMessageAt = 0L
    private var toolbarHidden = false
    private var configurationLoadedForUpdate = false

    // Edge-to-edge metrics, in pixels.
    private var topBarHeightPx = 0
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var loadingBaseTop = 0
    private var loadingBaseBottom = 0
    private var errorBaseTop = 0
    private var errorBaseBottom = 0
    // Glass blur capture state (API 31+). The bitmap is a blurred snapshot of
    // the strip immediately below the bar, used as a frosted backdrop.
    private val glassHandler = Handler(Looper.getMainLooper())
    private var lastGlassCaptureAt = 0L
    private var glassBufferA: Bitmap? = null
    private var glassBufferB: Bitmap? = null
    private var useBufferA = true
    private val glassCaptureRunnable = Runnable { captureGlassBackdrop() }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onboardingLaunched = false
        if (result.resultCode == RESULT_OK) {
            lifecycleScope.launch {
                handleConfiguration(serverManager.currentConfig())
            }
        } else if (!currentConfig.isConfigured) {
            finish()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        when (result.data?.getStringExtra(SettingsActivity.EXTRA_FRONTEND_ACTION)) {
            SettingsActivity.ACTION_RELOAD -> {
                refreshFrontend(clearCache = false)
                showMessage(R.string.frontend_reloaded)
            }

            SettingsActivity.ACTION_CLEAR_CACHE -> {
                refreshFrontend(clearCache = true)
                showMessage(R.string.cache_cleared_reloading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        configureWindow()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val container = (application as ExpuzApp).container
        serverManager = container.serverManager
        urlPolicy = container.urlPolicy
        updateManager = container.updateManager

        restoredUrl = savedInstanceState?.getString(KEY_LAST_URL)
        splashScreen.setKeepOnScreenCondition { !configurationLoaded }

        applyInsets()
        setupToolbar()
        setupWebView()
        setupActions()
        setupNetworkMonitor()
        setupBackHandling()
        setupGlassToolbar()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverManager.config.collect { config ->
                    handleConfiguration(config)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateManager.startupResult.collect {
                    maybeShowStartupUpdate()
                }
            }
        }
    }

    private fun configureWindow() {
        // Use one explicit inset model on every API level. Android 15+ enforces
        // edge-to-edge for targetSdk 35+, while older releases otherwise use a
        // different default fitting policy.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun setupGlassToolbar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.toolbarBlur.setRenderEffect(
                RenderEffect.createBlurEffect(56f, 56f, Shader.TileMode.CLAMP)
            )
        }

        // The bar is layout-driven (status inset, offline banner). Keep the
        // WebView viewport aligned whenever its measured height changes.
        binding.toolbarGlass.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != topBarHeightPx) {
                topBarHeightPx = newHeight
                binding.toolbarBlur.layoutParams = binding.toolbarBlur.layoutParams.apply {
                    width = binding.toolbarGlass.width
                    height = newHeight
                }
                binding.toolbarTint.layoutParams = binding.toolbarTint.layoutParams.apply {
                    width = binding.toolbarGlass.width
                    height = newHeight
                }
                binding.pageProgress.layoutParams = binding.pageProgress.layoutParams.apply {
                    width = binding.toolbarGlass.width
                }
                binding.toolbarHairline.layoutParams = binding.toolbarHairline.layoutParams.apply {
                    width = binding.toolbarGlass.width
                }
                applyContentInsets()
                scheduleGlassCapture(180)
            }
        }
        updateFloatingToolbarPosition()
    }

    private fun applyInsets() {
        loadingBaseTop = binding.loadingOverlay.paddingTop
        loadingBaseBottom = binding.loadingOverlay.paddingBottom
        errorBaseTop = binding.errorOverlay.paddingTop
        errorBaseBottom = binding.errorOverlay.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            insetTop = maxOf(bars.top, cutout.top)
            insetBottom = maxOf(bars.bottom, cutout.bottom, ime.bottom)
            insetLeft = cutout.left
            insetRight = cutout.right

            updateFloatingToolbarPosition()
            applyContentInsets()
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Insets the page viewport instead of changing the WebView's bounds: the
     * WebView still fills the window, its content starts below the status bar
     * and ends above the navigation bar or keyboard. Applied to the native
     * WebView container so the HTML viewport itself starts at the safe origin.
     */
    private fun applyContentInsets() {
        // Reserve only the status-bar inset, not the toolbar height. The
        // toolbar remains a floating overlay over the page viewport.
        val top = insetTop
        val bottom = insetBottom
        if (::webViewManager.isInitialized) {
            webViewManager.setContentInsets(insetLeft, top, insetRight, bottom)
        }
        binding.loadingOverlay.updatePadding(
            top = loadingBaseTop + insetTop,
            bottom = loadingBaseBottom + bottom,
        )
        binding.errorOverlay.updatePadding(
            top = errorBaseTop + insetTop,
            bottom = errorBaseBottom + bottom,
        )
    }

    private fun updateFloatingToolbarPosition() {
        val params = binding.toolbarGlass.layoutParams as? FrameLayout.LayoutParams ?: return
        val topMargin = insetTop + (12 * resources.displayMetrics.density).toInt()
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            binding.toolbarGlass.layoutParams = params
        }
    }

    private fun setupToolbar() {
        binding.refreshButton.setOnClickListener { refreshFrontend(clearCache = false) }
        binding.settingsButton.setOnClickListener { openSettings() }
    }

    private fun hideFloatingToolbar() {
        if (toolbarHidden || !binding.toolbarGlass.isShown) return
        toolbarHidden = true
        binding.toolbarGlass.animate().cancel()
        binding.toolbarGlass.animate()
            .translationY(-(binding.toolbarGlass.height + dp(8)).toFloat())
            .alpha(0f)
            .setDuration(240L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { binding.toolbarGlass.visibility = View.INVISIBLE }
            .start()
    }

    private fun showFloatingToolbar() {
        if (!toolbarHidden) return
        toolbarHidden = false
        binding.toolbarGlass.visibility = View.VISIBLE
        binding.toolbarGlass.animate().cancel()
        binding.toolbarGlass.alpha = 0f
        binding.toolbarGlass.translationY = -(binding.toolbarGlass.height + dp(8)).toFloat()
        binding.toolbarGlass.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(240L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Captures the strip of page directly under the bar and shows a blurred
     * copy inside the bar. Without this the "glass" still works as a
     * translucent scrim, so capture failures are not user-visible.
     */
    private fun scheduleGlassCapture(delayMs: Long = 160) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        glassHandler.removeCallbacks(glassCaptureRunnable)
        glassHandler.postDelayed(glassCaptureRunnable, delayMs)
    }

    private fun obtainGlassBuffer(width: Int, height: Int): Bitmap {
        val useA = useBufferA
        val existing = if (useA) glassBufferA else glassBufferB
        if (existing != null &&
            !existing.isRecycled &&
            existing.width == width &&
            existing.height == height
        ) {
            return existing
        }
        if (existing != null && !existing.isRecycled) existing.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            if (useA) glassBufferA = it else glassBufferB = it
        }
    }

    private fun captureGlassBackdrop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (isFinishing || isDestroyed) return
        if (binding.webContainer.width <= 0 || binding.webContainer.height <= 0) return

        val barHeight = topBarHeightPx
        if (barHeight <= 0) return

        val location = IntArray(2)
        binding.root.getLocationInWindow(location)
        val width = binding.webContainer.width
        val stripTop = binding.toolbarGlass.top + barHeight
        val stripHeight = minOf(barHeight, location[1] + binding.webContainer.height - stripTop)
        if (stripHeight <= 0 || width <= 0) return

        val bitmap = obtainGlassBuffer(width, stripHeight)
        val rect = Rect(
            location[0],
            location[1] + stripTop,
            location[0] + width,
            location[1] + stripTop + stripHeight,
        )

        try {
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS && !isFinishing && !isDestroyed) {
                        lastGlassCaptureAt = SystemClock.elapsedRealtime()
                        binding.toolbarBlur.setImageBitmap(bitmap)
                        useBufferA = !useBufferA
                    }
                },
                glassHandler,
            )
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Glass backdrop capture unavailable", error)
        }
    }

    private fun clearGlassBackdrop() {
        glassHandler.removeCallbacks(glassCaptureRunnable)
        binding.toolbarBlur.setImageDrawable(null)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGlassCaptureAt > GLASS_CAPTURE_INTERVAL_MS) {
                scheduleGlassCapture(260)
            }
        }
        return handled
    }

    private fun setupWebView() {
        webViewManager = WebViewManager(
            activity = this,
            container = binding.webContainer,
            urlPolicy = urlPolicy,
            callbacks = object : WebViewManager.Callbacks {
                override fun onPageStarted(url: String) {
                    lastFailure = null
                    pageState = PageState.CONNECTING
                    binding.errorOverlay.isVisible = false
                    binding.fallbackButton.isVisible = false
                    fallbackJob?.cancel()
                    binding.loadingTitle.setText(R.string.loading_connecting)
                    if (!hasRenderedPage) {
                        showLoading()
                    }
                    updateConnectionIndicator()
                }

                override fun onProgress(progress: Int) {
                    binding.pageProgress.isVisible = progress in 1..99
                    binding.pageProgress.setProgressCompat(progress, true)
                    if (binding.loadingOverlay.isVisible) {
                        binding.loadingPercent.isVisible = progress > 0
                        binding.loadingPercent.text = getString(R.string.percent_format, progress)
                        if (progress > 0) {
                            binding.loadingTitle.setText(R.string.loading_loading)
                        }
                    }
                }

                override fun onPageFinished(url: String) {
                    hasRenderedPage = true
                    pageState = PageState.CONNECTED
                    binding.loadingOverlay.isVisible = false
                    binding.pageProgress.isVisible = false
                    binding.errorOverlay.isVisible = false
                    binding.fallbackButton.isVisible = false
                    fallbackSuggestion = null
                    fallbackJob?.cancel()
                    updateConnectionIndicator()
                    showFloatingToolbar()
                    scheduleGlassCapture(220)
                }

                override fun onScrollDirection(direction: WebViewManager.ScrollDirection) {
                    when (direction) {
                        WebViewManager.ScrollDirection.DOWN -> hideFloatingToolbar()
                        WebViewManager.ScrollDirection.UP -> showFloatingToolbar()
                    }
                }

                override fun onFailure(failure: WebFailure) {
                    showFailure(failure)
                }

                override fun onExternalLink(url: String) {
                    promptExternalLink(url)
                }

                override fun onBlockedNavigation(url: String) {
                    val now = System.currentTimeMillis()
                    if (now - lastBlockedMessageAt > 1500) {
                        lastBlockedMessageAt = now
                        showMessage(R.string.blocked_navigation)
                    }
                }

                override fun onDownloadStarted(fileName: String) {
                    showMessage(getString(R.string.download_started_file, fileName))
                }

                override fun onDownloadUnsupported() {
                    showMessage(R.string.download_unsupported)
                }

                override fun onDownloadFailed() {
                    showMessage(R.string.download_failed)
                }

                override fun onFileChooserFailed() {
                    showMessage(R.string.file_picker_failed)
                }

                override fun onHttpAuthRequested() {
                    showMessage(R.string.http_auth_required)
                }

                override fun onRenderProcessGone() {
                    showFailure(
                        WebFailure(
                            kind = FailureKind.WEBVIEW_CRASH,
                            url = webViewManager.currentUrl() ?: activeBaseUrl,
                        )
                    )
                }
            },
        )
        // Insets may have been dispatched while the WebView manager was not
        // initialized yet. Apply the already-computed values to the newly
        // created WebView instead of waiting for a second inset dispatch.
        applyContentInsets()
    }

    private fun setupActions() {
        binding.retryButton.setOnClickListener { retryCurrentPage() }
        binding.errorSettingsButton.setOnClickListener { openSettings() }
        binding.fallbackButton.setOnClickListener { confirmFallbackSwitch() }
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { online ->
            networkOnline = online
            updateConnectionIndicator()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webViewManager.canGoBack()) {
                        webViewManager.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    private fun handleConfiguration(config: ServerConfig) {
        val firstLoad = !configurationLoaded
        configurationLoaded = true
        configurationLoadedForUpdate = true
        maybeShowStartupUpdate()
        currentConfig = config

        if (!config.isConfigured) {
            updateConnectionIndicator()
            if (!onboardingLaunched && !isFinishing) {
                launchOnboarding()
            }
            return
        }

        val restoredIsSafe = restoredUrl?.let { candidate ->
            candidate != "about:blank" && urlPolicy.sameOrigin(candidate, config.activeServerUrl)
        } == true

        val target = when {
            firstLoad && restoredIsSafe -> restoredUrl!!
            firstLoad -> config.activeServerUrl
            activeBaseUrl == null -> config.activeServerUrl
            !urlPolicy.sameOrigin(activeBaseUrl!!, config.activeServerUrl) -> config.activeServerUrl
            else -> null
        }
        restoredUrl = null

        if (target != null) {
            loadServer(target)
        } else {
            updateConnectionIndicator()
        }
    }

    private fun maybeShowStartupUpdate() {
        if (!configurationLoadedForUpdate) return
        val available = updateManager.consumeStartupAvailable() ?: return
        UpdateDialog.show(this, available.release, available.installedVersion)
    }

    private fun loadServer(url: String) {
        activeBaseUrl = url
        lastFailure = null
        fallbackSuggestion = null
        fallbackJob?.cancel()
        hasRenderedPage = false
        pageState = PageState.CONNECTING
        binding.errorOverlay.isVisible = false
        binding.fallbackButton.isVisible = false
        binding.pageProgress.isVisible = false
        binding.pageProgress.setProgressCompat(0, false)
        showLoading()
        updateConnectionIndicator()
        webViewManager.loadUrl(url)
    }

    private fun showLoading() {
        binding.loadingOverlay.isVisible = true
        binding.loadingSpinner.isVisible = true
        binding.loadingTitle.setText(R.string.loading_connecting)
        binding.loadingPercent.isVisible = false
        binding.loadingPercent.text = ""
        binding.errorOverlay.isVisible = false
        clearGlassBackdrop()
    }

    private fun showFailure(failure: WebFailure) {
        lastFailure = failure
        pageState = PageState.FAILED
        binding.loadingOverlay.isVisible = false
        binding.pageProgress.isVisible = false
        binding.errorOverlay.isVisible = true
        binding.fallbackButton.isVisible = false
        fallbackSuggestion = null

        binding.errorTitle.text = UiText.failureTitle(this, failure)
        binding.errorMessage.text = UiText.failureMessage(this, failure)

        val url = failure.url ?: activeBaseUrl ?: currentConfig.activeServerUrl
        binding.errorDetail.text = if (failure.httpStatus != null) {
            getString(R.string.error_http_detail, failure.httpStatus, url)
        } else {
            getString(R.string.error_detail, url)
        }
        binding.errorDetail.isVisible = url.isNotBlank()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Load failure: kind=${failure.kind} url=$url detail=${failure.detail}")
        }

        updateConnectionIndicator()
        suggestFallback(failure)
    }

    private fun suggestFallback(failure: WebFailure) {
        fallbackJob?.cancel()
        val config = currentConfig
        if (!config.isConfigured || config.mode != ConnectionMode.AUTO) return
        val failedUrl = failure.url ?: activeBaseUrl ?: return

        fallbackJob = lifecycleScope.launch {
            val target = serverManager.findFallback(config, failedUrl) ?: return@launch
            if (lastFailure != failure || !binding.errorOverlay.isVisible) return@launch
            fallbackSuggestion = target
            binding.fallbackButton.text = getString(
                R.string.error_switch_to,
                UiText.kind(this@MainActivity, target.kind),
            )
            binding.fallbackButton.isVisible = true
        }
    }

    private fun retryCurrentPage() {
        val failure = lastFailure
        if (failure?.kind == FailureKind.WEBVIEW_CRASH) {
            val url = activeBaseUrl ?: currentConfig.activeServerUrl
            if (url.isBlank()) {
                openSettings()
                return
            }
            showLoading()
            pageState = PageState.CONNECTING
            updateConnectionIndicator()
            webViewManager.recreateAndLoad(url)
            return
        }

        val url = failure?.url ?: activeBaseUrl ?: currentConfig.activeServerUrl
        if (url.isBlank()) {
            openSettings()
            return
        }

        binding.fallbackButton.isVisible = false
        fallbackSuggestion = null
        fallbackJob?.cancel()
        showLoading()
        pageState = PageState.CONNECTING
        updateConnectionIndicator()
        webViewManager.loadUrl(url)
    }

    private fun refreshFrontend(clearCache: Boolean) {
        if (!currentConfig.isConfigured) {
            openSettings()
            return
        }

        if (clearCache) {
            webViewManager.clearHttpCache()
        }

        if (binding.errorOverlay.isVisible) {
            retryCurrentPage()
            return
        }

        webViewManager.reloadOrLoad(activeBaseUrl ?: currentConfig.activeServerUrl)
    }

    private fun confirmFallbackSwitch() {
        val target = fallbackSuggestion ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_server_changed_title)
            .setMessage(R.string.settings_server_changed_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                lifecycleScope.launch {
                    when (val result = serverManager.switchActiveServer(target.url)) {
                        is SaveResult.Success -> {
                            loadServer(target.url)
                        }

                        is SaveResult.Invalid -> {
                            showMessage(UiText.urlProblem(this@MainActivity, result.problem))
                        }
                    }
                }
            }
            .show()
    }

    private fun openSettings() {
        if (!currentConfig.isConfigured) {
            launchOnboarding()
            return
        }
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun launchOnboarding() {
        if (onboardingLaunched) return
        if (!configurationLoaded) return
        if (currentConfig.isConfigured) return
        onboardingLaunched = true
        onboardingLauncher.launch(Intent(this, OnboardingActivity::class.java))
    }

    private fun promptExternalLink(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in EXTERNAL_SCHEMES) {
            showMessage(R.string.blocked_navigation)
            return
        }

        val destination = uri.host ?: scheme ?: url
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_external_title)
            .setMessage(getString(R.string.open_external_message, destination))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    showMessage(R.string.no_external_app)
                } catch (_: Throwable) {
                    showMessage(R.string.no_external_app)
                }
            }
            .show()
    }

    private fun updateConnectionIndicator() {
        // Connection state remains available to loading/error flows, but the
        // floating toolbar intentionally contains icons only.
    }

    private fun showMessage(messageRes: Int) {
        showMessage(getString(messageRes))
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.expuz_surface_high))
            .setTextColor(ContextCompat.getColor(this, R.color.expuz_text))
            .show()
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
    }

    override fun onStop() {
        networkMonitor.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webViewManager.onResume()
        scheduleGlassCapture(300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleGlassCapture(200)
    }

    override fun onPause() {
        webViewManager.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = webViewManager.currentUrl() ?: activeBaseUrl
        if (!current.isNullOrBlank()) {
            outState.putString(KEY_LAST_URL, current)
        }
    }

    override fun onDestroy() {
        clearGlassBackdrop()
        glassBufferA?.recycle()
        glassBufferB?.recycle()
        glassBufferA = null
        glassBufferB = null
        webViewManager.destroy()
        super.onDestroy()
    }

    private enum class PageState {
        IDLE,
        CONNECTING,
        CONNECTED,
        FAILED,
    }

    companion object {
        private const val TAG = "ExpuzMain"
        private const val KEY_LAST_URL = "expuz_last_url"
        private const val GLASS_CAPTURE_INTERVAL_MS = 300L
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")
    }
}
