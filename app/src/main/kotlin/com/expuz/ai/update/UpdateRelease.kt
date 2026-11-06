package com.expuz.ai.update

data class UpdateRelease(
    val name: String,
    val tag: String,
    val publishedAt: String,
    val body: String,
    val htmlUrl: String,
    val assetName: String?,
    val assetUrl: String?,
    val assetSize: Long,
    val digest: String?,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: UpdateRelease, val installedVersion: String) : UpdateResult
    data class Failure(val message: String) : UpdateResult
}
