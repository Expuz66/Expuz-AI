package com.expuz.ai.update

import android.util.Log
import android.os.Build
import com.expuz.ai.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManager(
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private val startupStarted = AtomicBoolean(false)
    private val startupConsumed = AtomicBoolean(false)
    private val _startupResult = MutableStateFlow<UpdateResult?>(null)
    val startupResult: StateFlow<UpdateResult?> = _startupResult.asStateFlow()

    fun checkOnProcessStart() {
        if (!startupStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            _startupResult.value = checkLatest(BuildConfig.VERSION_NAME)
        }
    }

    fun consumeStartupAvailable(): UpdateResult.Available? {
        val result = _startupResult.value as? UpdateResult.Available ?: return null
        return if (startupConsumed.compareAndSet(false, true)) result else null
    }

    fun checkLatestAsync(installedVersion: String = BuildConfig.VERSION_NAME): Job =
        scope.launch(Dispatchers.IO) { checkLatest(installedVersion) }

    suspend fun checkLatest(installedVersion: String = BuildConfig.VERSION_NAME): UpdateResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
            try {
                Log.d(TAG, "Checking latest stable release; installed=$installedVersion")
                val request = Request.Builder()
                    .url(LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "GitHub releases response: HTTP ${response.code}")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub update check rejected: HTTP ${response.code}")
                        return@withLock UpdateResult.Failure("GitHub returned HTTP ${response.code}.")
                    }
                    val body = response.body?.string().orEmpty()
                    Log.d(TAG, "GitHub response received (${body.length} bytes)")
                    val json = JSONObject(body)
                    if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                        Log.w(TAG, "Ignoring draft or prerelease response")
                        return@withLock UpdateResult.Failure("The latest GitHub release is not stable.")
                    }
                    val release = parseRelease(json)
                    val latestVersion = SemVer.parse(release.tag)
                    val currentVersion = SemVer.parse(installedVersion)
                    Log.d(TAG, "Version comparison: installed=$currentVersion latest=$latestVersion")
                    if (latestVersion > currentVersion) {
                        UpdateResult.Available(release, installedVersion)
                    } else {
                        UpdateResult.UpToDate
                    }
                }
            } catch (error: CancellationException) {
                Log.d(TAG, "Update check cancelled")
                throw error
            } catch (error: JSONException) {
                Log.e(TAG, "GitHub release JSON parsing failed", error)
                UpdateResult.Failure("GitHub returned an invalid release response.")
            } catch (error: Exception) {
                Log.e(TAG, "Update check failed: ${error::class.java.simpleName}", error)
                UpdateResult.Failure(error.message ?: "Unable to check for updates.")
            }
            }
        }

    private fun parseRelease(json: JSONObject): UpdateRelease {
        val tag = json.optString("tag_name").ifBlank {
            Log.e(TAG, "GitHub release did not contain tag_name")
            throw JSONException("Release tag is missing")
        }
        val assets = json.optJSONArray("assets")
        Log.d(TAG, "Inspecting ${assets?.length() ?: 0} release assets")
        val apk = buildList {
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) &&
                        asset.optString("browser_download_url").startsWith("https://github.com/")) {
                        add(asset)
                    }
                }
            }
        }.sortedWith(compareByDescending<JSONObject> { compatibilityScore(it.optString("name")) }
            .thenByDescending { it.optLong("size") })
        val selected = apk.firstOrNull()
        if (selected == null) {
            Log.w(TAG, "No compatible APK asset found")
        } else {
            Log.d(TAG, "Selected APK asset=${selected.optString("name")} size=${selected.optLong("size")}")
        }
        return UpdateRelease(
            name = json.optString("name").ifBlank { json.optString("tag_name") },
            tag = tag,
            publishedAt = json.optString("published_at"),
            body = json.optString("body"),
            htmlUrl = json.optString("html_url"),
            assetName = selected?.optString("name")?.ifBlank { null },
            assetUrl = selected?.optString("browser_download_url")?.ifBlank { null },
            assetSize = selected?.optLong("size") ?: 0L,
            digest = selected?.optString("digest")?.takeIf { it.startsWith("sha256:") },
        )
    }

    private fun compatibilityScore(name: String): Int {
        val lower = name.lowercase()
        if ("universal" in lower) return 100
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase().orEmpty()
        return when {
            abi.isNotBlank() && abi in lower -> 80
            "arm64-v8a" in lower -> 60
            "armeabi-v7a" in lower -> 50
            "x86_64" in lower -> 40
            "x86" in lower -> 30
            else -> 10
        }
    }

    companion object {
        private const val TAG = "ExpuzUpdater"
        const val LATEST_URL = "https://api.github.com/repos/Expuz66/Expuz-AI/releases/latest"
    }
}

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        fun parse(raw: String): SemVer {
            val values = Regex("\\d+").findAll(raw).take(3).map { it.value.toInt() }.toList()
            return SemVer(values.getOrElse(0) { 0 }, values.getOrElse(1) { 0 }, values.getOrElse(2) { 0 })
        }
    }
}
