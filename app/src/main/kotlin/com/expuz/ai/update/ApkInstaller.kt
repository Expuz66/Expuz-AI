package com.expuz.ai.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val context: Context) {
    fun validate(file: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(
            file.path,
            flags,
        ) ?: return false
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        if (info.packageName != context.packageName || archiveVersion <= installedVersionCode()) {
            return false
        }
        return hasCompatibleSigning(info, flags)
    }

    fun install(file: File): InstallResult {
        if (!validate(file)) {
            Log.e(TAG, "Rejected APK validation: ${file.name}")
            return InstallResult.InvalidApk
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.d(TAG, "Install permission required")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.PermissionRequired
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        Log.d(TAG, "Opened Android package installer for ${file.name}")
        return InstallResult.Started
    }

    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }

    private fun hasCompatibleSigning(
        archive: android.content.pm.PackageInfo,
        flags: Int,
    ): Boolean {
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val archiveSigners = archive.signingInfo?.apkContentsSigners ?: return false
            val installedSigners = installed.signingInfo?.apkContentsSigners ?: return false
            archiveSigners.any { candidate -> installedSigners.any { it == candidate } }
        } else {
            @Suppress("DEPRECATION")
            val archiveSigners = archive.signatures ?: return false
            @Suppress("DEPRECATION")
            val installedSigners = installed.signatures ?: return false
            archiveSigners.any { candidate -> installedSigners.any { it == candidate } }
        }
    }

    companion object {
        private const val TAG = "ExpuzUpdater"
    }
}

enum class InstallResult {
    Started,
    PermissionRequired,
    InvalidApk,
}
