package com.expuz.ai.data

/**
 * How the native shell chooses which server origin to load.
 *
 * MANUAL is used by onboarding: one address is stored as the active server.
 * The other modes keep separate local/remote addresses so Settings can offer
 * local-first routing without ever switching origins silently.
 */
enum class ConnectionMode {
    MANUAL,
    AUTO,
    LOCAL_ONLY,
    REMOTE_ONLY;

    companion object {
        fun fromStorage(value: String?): ConnectionMode =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

data class ServerConfig(
    val activeServerUrl: String = "",
    val localUrl: String = "",
    val remoteUrl: String = "",
    val mode: ConnectionMode = ConnectionMode.MANUAL,
    val onboardingCompleted: Boolean = false,
) {
    val isConfigured: Boolean
        get() = onboardingCompleted && activeServerUrl.isNotBlank()
}
