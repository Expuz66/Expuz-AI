package com.expuz.ai.domain

import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.data.ServerConfig
import com.expuz.ai.data.ServerStore
import kotlinx.coroutines.flow.Flow

enum class ServerField {
    PRIMARY,
    LOCAL,
    REMOTE,
}

sealed interface SaveResult {
    data class Success(val config: ServerConfig) : SaveResult
    data class Invalid(val field: ServerField, val problem: UrlProblem) : SaveResult
}

data class ConnectionTestResult(
    val reachable: Boolean,
    val normalizedUrl: String? = null,
    val kind: ServerKind = ServerKind.UNKNOWN,
    val httpStatus: Int? = null,
    val latencyMs: Long = 0,
    val urlProblem: UrlProblem? = null,
    val failure: FailureKind? = null,
    val technicalMessage: String? = null,
)

class ServerManager(
    private val store: ServerStore,
    private val probe: ServerProbe,
    private val urlPolicy: UrlPolicy,
) {
    val config: Flow<ServerConfig> = store.config

    suspend fun currentConfig(): ServerConfig = store.current()

    fun validate(raw: String): UrlValidation = urlPolicy.validate(raw)

    suspend fun testConnection(
        rawUrl: String,
        currentConfig: ServerConfig? = null,
    ): ConnectionTestResult {
        val validation = urlPolicy.validate(rawUrl)
        if (validation is UrlValidation.Invalid) {
            return ConnectionTestResult(
                reachable = false,
                urlProblem = validation.problem,
                failure = validation.problem.toFailureKind(),
            )
        }

        val valid = (validation as UrlValidation.Valid).url
        val probeResult = probe.probe(valid.value)
        return ConnectionTestResult(
            reachable = probeResult.reachable,
            normalizedUrl = valid.value,
            kind = ServerRouting.kindForUrl(valid.value, currentConfig),
            httpStatus = probeResult.httpStatus,
            latencyMs = probeResult.latencyMs,
            failure = probeResult.failure,
            technicalMessage = probeResult.technicalMessage,
        )
    }

    suspend fun completeOnboarding(rawUrl: String): SaveResult {
        val validation = urlPolicy.validate(rawUrl)
        if (validation is UrlValidation.Invalid) {
            return SaveResult.Invalid(ServerField.PRIMARY, validation.problem)
        }
        val normalized = (validation as UrlValidation.Valid).url.value
        val current = store.current()
        val updated = current.copy(
            activeServerUrl = normalized,
            mode = ConnectionMode.MANUAL,
            onboardingCompleted = true,
        )
        store.save(updated)
        return SaveResult.Success(updated)
    }

    /**
     * Validates routing configuration and builds the resulting config without
     * persisting it. Settings uses this to ask for confirmation before an
     * origin change; onboarding and simple callers can use [saveConfiguration].
     */
    suspend fun prepareConfiguration(
        mode: ConnectionMode,
        primaryRaw: String,
        localRaw: String,
        remoteRaw: String,
    ): SaveResult {
        val current = store.current()

        val primary = when (mode) {
            ConnectionMode.MANUAL -> {
                val validation = urlPolicy.validate(primaryRaw)
                if (validation is UrlValidation.Invalid) {
                    return SaveResult.Invalid(ServerField.PRIMARY, validation.problem)
                }
                (validation as UrlValidation.Valid).url
            }

            else -> null
        }

        val local = when (mode) {
            ConnectionMode.AUTO -> {
                if (localRaw.isBlank()) {
                    null
                } else {
                    val validation = urlPolicy.validate(localRaw)
                    if (validation is UrlValidation.Invalid) {
                        return SaveResult.Invalid(ServerField.LOCAL, validation.problem)
                    }
                    (validation as UrlValidation.Valid).url
                }
            }

            ConnectionMode.LOCAL_ONLY -> {
                if (localRaw.isBlank()) {
                    return SaveResult.Invalid(ServerField.LOCAL, UrlProblem.EMPTY)
                }
                val validation = urlPolicy.validate(localRaw)
                if (validation is UrlValidation.Invalid) {
                    return SaveResult.Invalid(ServerField.LOCAL, validation.problem)
                }
                (validation as UrlValidation.Valid).url
            }

            else -> null
        }

        val remote = when (mode) {
            ConnectionMode.AUTO, ConnectionMode.REMOTE_ONLY -> {
                if (remoteRaw.isBlank()) {
                    if (mode == ConnectionMode.AUTO) {
                        // A local-only automatic setup is valid; it simply has
                        // no remote fallback until the user adds one.
                        null
                    } else {
                        return SaveResult.Invalid(ServerField.REMOTE, UrlProblem.EMPTY)
                    }
                } else {
                    val validation = urlPolicy.validate(remoteRaw)
                    if (validation is UrlValidation.Invalid) {
                        return SaveResult.Invalid(ServerField.REMOTE, validation.problem)
                    }
                    val normalized = (validation as UrlValidation.Valid).url
                    if (normalized.scheme != "https") {
                        return SaveResult.Invalid(
                            ServerField.REMOTE,
                            UrlProblem.INSECURE_REMOTE_HTTP,
                        )
                    }
                    normalized
                }
            }

            else -> null
        }

        if (mode == ConnectionMode.AUTO && local == null && remote == null) {
            return SaveResult.Invalid(ServerField.LOCAL, UrlProblem.EMPTY)
        }

        val newLocal = when (mode) {
            ConnectionMode.AUTO, ConnectionMode.LOCAL_ONLY -> local?.value.orEmpty()
            else -> current.localUrl
        }
        val newRemote = when (mode) {
            ConnectionMode.AUTO, ConnectionMode.REMOTE_ONLY -> remote?.value.orEmpty()
            else -> current.remoteUrl
        }
        val baseForActive = current.copy(
            localUrl = newLocal,
            remoteUrl = newRemote,
            mode = mode,
        )
        val active = when (mode) {
            ConnectionMode.MANUAL -> primary?.value.orEmpty()
            else -> ServerRouting.preferredActiveUrl(baseForActive, current.activeServerUrl)
        }

        if (active.isBlank()) {
            return SaveResult.Invalid(ServerField.LOCAL, UrlProblem.EMPTY)
        }

        val updated = current.copy(
            activeServerUrl = active,
            localUrl = newLocal,
            remoteUrl = newRemote,
            mode = mode,
            onboardingCompleted = true,
        )
        return SaveResult.Success(updated)
    }

    suspend fun saveConfiguration(
        mode: ConnectionMode,
        primaryRaw: String,
        localRaw: String,
        remoteRaw: String,
    ): SaveResult {
        val prepared = prepareConfiguration(mode, primaryRaw, localRaw, remoteRaw)
        return if (prepared is SaveResult.Success) {
            persistConfiguration(prepared.config)
        } else {
            prepared
        }
    }

    suspend fun persistConfiguration(config: ServerConfig): SaveResult {
        if (config.activeServerUrl.isBlank()) {
            return SaveResult.Invalid(ServerField.PRIMARY, UrlProblem.EMPTY)
        }
        store.save(config)
        return SaveResult.Success(config)
    }

    suspend fun switchActiveServer(rawUrl: String): SaveResult {
        val validation = urlPolicy.validate(rawUrl)
        if (validation is UrlValidation.Invalid) {
            return SaveResult.Invalid(ServerField.PRIMARY, validation.problem)
        }
        val normalized = (validation as UrlValidation.Valid).url
        val current = store.current()
        val updated = current.copy(
            activeServerUrl = normalized.value,
            onboardingCompleted = true,
        )
        store.save(updated)
        return SaveResult.Success(updated)
    }

    fun serverKind(config: ServerConfig, url: String = config.activeServerUrl): ServerKind =
        ServerRouting.kindForUrl(url, config)

    fun candidates(config: ServerConfig): List<ServerTarget> = ServerRouting.candidates(config)

    /**
     * Probes the alternate address in AUTO mode. The result is only a proposal;
     * the UI must ask before changing the WebView origin, because cookies and
     * localStorage belong to the current origin.
     */
    suspend fun findFallback(config: ServerConfig, failedUrl: String): ServerTarget? {
        if (config.mode != ConnectionMode.AUTO) return null
        val targets = candidates(config)
        if (targets.size < 2) return null
        for (target in targets) {
            if (urlPolicy.sameOrigin(target.url, failedUrl)) continue
            if (probe.probe(target.url).reachable) return target
        }
        return null
    }
}
