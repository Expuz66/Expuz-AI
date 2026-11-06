package com.expuz.ai.update

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DownloadProgress(val downloaded: Long, val total: Long)

class ApkDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        release: UpdateRelease,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = release.assetUrl ?: throw IllegalStateException("No compatible APK was found in this release.")
        Log.d(TAG, "Downloading APK asset=${release.assetName} size=${release.assetSize}")
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "expuz-${release.tag.removePrefix("v")}.apk")
        val temporary = File(directory, "${target.name}.part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("APK download failed with HTTP ${response.code}.")
            val body = response.body ?: throw IllegalStateException("The APK response was empty.")
            val total = body.contentLength().takeIf { it >= 0 } ?: release.assetSize
            var downloaded = 0L
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
            }
        }
        if (release.assetSize > 0 && temporary.length() != release.assetSize) {
            temporary.delete()
            throw IllegalStateException("Downloaded APK size does not match the release asset.")
        }
        release.digest?.let { expected ->
            val expectedHash = expected.removePrefix("sha256:").lowercase(Locale.US)
            val actualHash = sha256(temporary)
            if (actualHash != expectedHash) {
                Log.e(TAG, "APK SHA-256 mismatch for ${release.assetName}")
                temporary.delete()
                throw IllegalStateException("APK SHA-256 verification failed.")
            }
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) throw IllegalStateException("Unable to save the downloaded APK.")
        Log.d(TAG, "APK download completed: ${target.name} bytes=${target.length()}")
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ExpuzUpdater"
    }
}
