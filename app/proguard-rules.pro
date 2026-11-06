# HttpURLConnection / OkHttp are handled by R8 automatically.
# Keep Kotlin coroutine internals service file.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# WebView JavaScript is loaded from the remote frontend, not from bundled classes.
# There is intentionally no @JavascriptInterface bridge to keep.
