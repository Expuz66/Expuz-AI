package com.expuz.ai.web

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.expuz.ai.BuildConfig
import com.expuz.ai.domain.FailureKind
import com.expuz.ai.domain.UrlPolicy
import com.expuz.ai.domain.WebFailure

/**
 * Owns the single WebView used by the native shell.
 *
 * Security decisions:
 *  - JavaScript is enabled (Open WebUI requires it), but no JavaScript bridge
 *    is exposed to the page.
 *  - File and content access from WebView is disabled; uploads go through SAF.
 *  - Mixed content is never allowed.
 *  - TLS errors are always cancelled, never bypassed.
 *  - Main-frame navigation is restricted to the configured server origin.
 */
class WebViewManager(
    private val activity: ComponentActivity,
    private val container: ViewGroup,
    private val urlPolicy: UrlPolicy,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onPageStarted(url: String)
        fun onProgress(progress: Int)
        fun onPageFinished(url: String)
        fun onScrollDirection(direction: ScrollDirection)
        fun onFailure(failure: WebFailure)
        fun onExternalLink(url: String)
        fun onBlockedNavigation(url: String)
        fun onDownloadStarted(fileName: String)
        fun onDownloadUnsupported()
        fun onDownloadFailed()
        fun onFileChooserFailed()
        fun onHttpAuthRequested()
        fun onRenderProcessGone()
    }

    private var webView: WebView? = null
    private var allowedServerUrl: String = ""
    private var lastFailure: WebFailure? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    enum class ScrollDirection {
        UP,
        DOWN,
    }

    // Viewport insets in pixels. The WebView itself keeps filling the window;
    // padding shrinks the page viewport so the native glass bar and the system
    // bars / keyboard never cover page content.
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index)?.uri?.let(uris::add)
                }
            }
            data?.data?.let(uris::add)
            callback.onReceiveValue(
                uris.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
            )
        }

    fun createWebView() {
        destroyWebView()

        val view = WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            setPadding(insetLeft, insetTop, insetRight, insetBottom)
            configureSettings(settings)
            webViewClient = ExpuzWebViewClient()
            webChromeClient = ExpuzChromeClient()
            setDownloadListener(ExpuzDownloadListener())
            addJavascriptInterface(ScrollBridge(), "ExpuzScroll")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }

        webView = view
        container.addView(view)
    }

    /**
     * Insets the page viewport without changing the WebView's window-filling
     * bounds. Re-applied automatically when the WebView is recreated.
     */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) {
            return
        }
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        webView?.setPadding(left, top, right, bottom)
    }

    private fun configureSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.textZoom = 100
        settings.setGeolocationEnabled(false)
    }

    fun loadUrl(url: String) {
        allowedServerUrl = url
        lastFailure = null
        val view = webView ?: run {
            createWebView()
            webView
        } ?: return
        view.loadUrl(url)
    }

    fun reload() {
        lastFailure = null
        webView?.reload()
    }

    fun reloadOrLoad(configuredUrl: String) {
        val current = webView?.url
        if (current.isNullOrBlank() || current == "about:blank") {
            loadUrl(configuredUrl)
        } else {
            reload()
        }
    }

    fun clearHttpCache() {
        webView?.clearCache(true)
    }

    fun recreateAndLoad(url: String) {
        destroyWebView()
        createWebView()
        loadUrl(url)
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun currentUrl(): String? = webView?.url

    fun onResume() {
        webView?.onResume()
        webView?.resumeTimers()
    }

    fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        // Persist cookies/local storage before the process can be killed. This
        // preserves the login session; it does not clear anything.
        CookieManager.getInstance().flush()
    }

    fun destroy() {
        destroyWebView()
    }

    private fun destroyWebView() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView?.let { view ->
            view.evaluateJavascript("window.__expuzScrollCleanup && window.__expuzScrollCleanup();", null)
            view.removeJavascriptInterface("ExpuzScroll")
            view.stopLoading()
            container.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        webView = null
    }

    private fun handleMainFrameNavigation(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()

        if (scheme == null || scheme == "about") return false

        if (scheme == "http" || scheme == "https") {
            if (urlPolicy.isAllowedNavigation(url, allowedServerUrl)) return false

            if (scheme == "http" && !urlPolicy.isAllowedNavigation(url, url)) {
                // Cleartext to a non-LAN/external target is never loaded.
                callbacks.onBlockedNavigation(url)
                return true
            }

            callbacks.onExternalLink(url)
            return true
        }

        return when (scheme) {
            "mailto", "tel" -> {
                callbacks.onExternalLink(url)
                true
            }

            else -> {
                callbacks.onBlockedNavigation(url)
                true
            }
        }
    }

    private fun notifyFailure(failure: WebFailure) {
        lastFailure = failure
        callbacks.onFailure(failure)
    }

    private inner class ExpuzWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            return handleMainFrameNavigation(request.url.toString())
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null && !url.startsWith("about:")) {
                lastFailure = null
                callbacks.onPageStarted(url)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // A failed load can still emit onPageFinished. Do not hide the
            // native error screen in that case.
            if (lastFailure == null && url != null) {
                callbacks.onPageFinished(url)
                installScrollObserver(view, url)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != true || error == null) return
            notifyFailure(
                WebFailure(
                    kind = error.errorCode.toFailureKind(),
                    url = request.url.toString(),
                    detail = error.description?.toString(),
                )
            )
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame != true) return
            val status = errorResponse?.statusCode ?: return
            if (status < 400) return
            notifyFailure(
                WebFailure(
                    kind = FailureKind.HTTP_ERROR,
                    url = request.url.toString(),
                    httpStatus = status,
                )
            )
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: android.net.http.SslError?,
        ) {
            // Never call handler.proceed(). Certificate validation must fail
            // closed for both LAN and remote HTTPS servers.
            handler?.cancel()
            notifyFailure(
                WebFailure(
                    kind = FailureKind.TLS,
                    url = error?.url,
                    detail = "SSL certificate validation failed",
                )
            )
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?,
        ) {
            handler?.cancel()
            callbacks.onHttpAuthRequested()
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            // The WebView instance is unusable after this callback. Retry
            // recreates it from scratch; returning true prevents the app from
            // being killed by the framework.
            callbacks.onRenderProcessGone()
            return true
        }
    }

    private fun installScrollObserver(view: WebView?, url: String) {
        if (view == null || !urlPolicy.sameOrigin(url, allowedServerUrl)) return
        view.evaluateJavascript(
            """
            (() => {
              if (window.__expuzScrollCleanup) window.__expuzScrollCleanup();
              let target = null;
              let rootObserver = null;
              let frame = 0;
              let last = 0;
              let onScroll = null;
              const attach = () => {
                if (target) return true;
                target = document.querySelector('#messages-container');
                if (!target) return false;
                last = target.scrollTop;
                const emit = () => {
                  frame = 0;
                  const current = target.scrollTop;
                  const delta = current - last;
                  last = current;
                  if (Math.abs(delta) < 6) return;
                  window.ExpuzScroll.onDirection(delta > 0 ? 'down' : 'up');
                };
                onScroll = () => {
                  if (!frame) frame = requestAnimationFrame(emit);
                };
                target.addEventListener('scroll', onScroll, {passive: true});
                if (rootObserver) rootObserver.disconnect();
                return true;
              };
              attach();
              if (!target) {
                rootObserver = new MutationObserver(attach);
                rootObserver.observe(document.documentElement, {childList: true, subtree: true});
              }
              window.__expuzScrollCleanup = () => {
                if (rootObserver) rootObserver.disconnect();
                if (target && onScroll) target.removeEventListener('scroll', onScroll);
                if (frame) cancelAnimationFrame(frame);
                frame = 0;
                target = null;
                onScroll = null;
              };
            })();
            """.trimIndent(),
            null,
        )
    }

    private inner class ScrollBridge {
        @JavascriptInterface
        fun onDirection(direction: String?) {
            val current = webView?.url ?: return
            if (!urlPolicy.sameOrigin(current, allowedServerUrl)) return
            when (direction) {
                "down" -> callbacks.onScrollDirection(ScrollDirection.DOWN)
                "up" -> callbacks.onScrollDirection(ScrollDirection.UP)
            }
        }
    }

    private inner class ExpuzChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            callbacks.onProgress(newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null) return false

            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback

            return try {
                fileChooserLauncher.launch(buildFileChooserIntent(fileChooserParams))
                true
            } catch (_: ActivityNotFoundException) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                callbacks.onFileChooserFailed()
                false
            } catch (_: Throwable) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                callbacks.onFileChooserFailed()
                false
            }
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            // Camera/microphone requests are not needed for the chat shell.
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }

        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            if (BuildConfig.DEBUG && consoleMessage != null) {
                Log.d(TAG, "${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
            }
            return true
        }
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            val mimeTypes = params?.acceptTypes
                .orEmpty()
                .filter { it.contains('/') && it != "*/*" }
                .distinct()
            if (mimeTypes.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
        }
        return intent
    }

    private inner class ExpuzDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ) {
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                callbacks.onDownloadUnsupported()
                return
            }
            if (!urlPolicy.isAllowedNavigation(url, allowedServerUrl)) {
                callbacks.onBlockedNavigation(url)
                return
            }

            try {
                val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val fileName = sanitizeFileName(guessed.ifBlank { "expuz-download" })
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDescription(activity.getString(com.expuz.ai.R.string.app_name))
                    .setMimeType(mimeType)
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalFilesDir(
                        activity,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName,
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)

                CookieManager.getInstance().getCookie(url)?.let { cookie ->
                    request.addRequestHeader("Cookie", cookie)
                }
                if (!userAgent.isNullOrBlank()) {
                    request.addRequestHeader("User-Agent", userAgent)
                }

                val manager = activity.getSystemService(DownloadManager::class.java)
                if (manager == null) {
                    callbacks.onDownloadFailed()
                    return
                }
                manager.enqueue(request)
                callbacks.onDownloadStarted(fileName)
            } catch (_: Throwable) {
                callbacks.onDownloadFailed()
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
        return sanitized.takeIf { it.isNotBlank() } ?: "expuz-download"
    }

    private fun Int.toFailureKind(): FailureKind = when (this) {
        WebViewClient.ERROR_HOST_LOOKUP -> FailureKind.HOST_NOT_FOUND
        WebViewClient.ERROR_CONNECT -> FailureKind.CONNECTION_REFUSED
        WebViewClient.ERROR_TIMEOUT -> FailureKind.TIMEOUT
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> FailureKind.TLS
        WebViewClient.ERROR_UNSUPPORTED_SCHEME,
        WebViewClient.ERROR_BAD_URL,
        -> FailureKind.BLOCKED

        WebViewClient.ERROR_UNKNOWN,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_FILE,
        WebViewClient.ERROR_FILE_NOT_FOUND,
        -> FailureKind.CONNECTION_REFUSED

        else -> FailureKind.UNKNOWN
    }

    companion object {
        private const val TAG = "ExpuzWebView"
    }
}
