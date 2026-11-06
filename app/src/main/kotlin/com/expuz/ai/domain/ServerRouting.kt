package com.expuz.ai.domain

import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.data.ServerConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerKind {
    LOCAL,
    REMOTE,
    UNKNOWN,
}

data class ServerTarget(
    val kind: ServerKind,
    val url: String,
)

/**
 * Pure routing rules shared by the app and unit tests. The active origin is
 * never changed implicitly here; ServerManager only stores a new origin after
 * the user confirms a server switch.
 */
object ServerRouting {

    fun candidates(config: ServerConfig): List<ServerTarget> {
        val targets = when (config.mode) {
            ConnectionMode.MANUAL -> listOfNotNull(
                config.activeServerUrl.takeIf { it.isNotBlank() }
                    ?.let { ServerTarget(kindForUrl(it, config), it) }
            )

            ConnectionMode.AUTO -> listOfNotNull(
                config.localUrl.takeIf { it.isNotBlank() }
                    ?.let { ServerTarget(ServerKind.LOCAL, it) },
                config.remoteUrl.takeIf { it.isNotBlank() }
                    ?.let { ServerTarget(ServerKind.REMOTE, it) },
            )

            ConnectionMode.LOCAL_ONLY -> listOfNotNull(
                config.localUrl.takeIf { it.isNotBlank() }
                    ?.let { ServerTarget(ServerKind.LOCAL, it) }
            )

            ConnectionMode.REMOTE_ONLY -> listOfNotNull(
                config.remoteUrl.takeIf { it.isNotBlank() }
                    ?.let { ServerTarget(ServerKind.REMOTE, it) }
            )
        }
        return targets.distinctBy { it.url.trimEnd('/') }
    }

    fun preferredActiveUrl(
        config: ServerConfig,
        currentActiveUrl: String,
    ): String = when (config.mode) {
        ConnectionMode.MANUAL -> currentActiveUrl
        ConnectionMode.AUTO -> {
            val candidates = listOf(config.localUrl, config.remoteUrl)
                .filter { it.isNotBlank() }
            candidates.firstOrNull { sameOriginIt(it, currentActiveUrl) }
                ?: candidates.firstOrNull().orEmpty()
        }

        ConnectionMode.LOCAL_ONLY -> config.localUrl
        ConnectionMode.REMOTE_ONLY -> config.remoteUrl
    }

    fun kindForUrl(url: String, config: ServerConfig? = null): ServerKind {
        if (config != null) {
            if (config.localUrl.isNotBlank() && sameOriginIt(url, config.localUrl)) {
                return ServerKind.LOCAL
            }
            if (config.remoteUrl.isNotBlank() && sameOriginIt(url, config.remoteUrl)) {
                return ServerKind.REMOTE
            }
        }

        val host = url.toHttpUrlOrNull()?.host ?: return ServerKind.UNKNOWN
        return if (UrlPolicy.isLanHost(host)) ServerKind.LOCAL else ServerKind.REMOTE
    }

    private fun sameOriginIt(first: String, second: String): Boolean {
        val firstUrl = first.toHttpUrlOrNull() ?: return false
        val secondUrl = second.toHttpUrlOrNull() ?: return false
        return firstUrl.scheme == secondUrl.scheme &&
            firstUrl.host == secondUrl.host &&
            firstUrl.port == secondUrl.port
    }
}
