package com.expuz.ai.domain

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class UrlProblem {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    USER_INFO_NOT_ALLOWED,
    QUERY_OR_FRAGMENT_NOT_ALLOWED,
    INSECURE_REMOTE_HTTP,
    CLEARTEXT_DISABLED,
    INVALID_HOST,
}

sealed interface UrlValidation {
    data class Valid(val url: NormalizedServerUrl) : UrlValidation
    data class Invalid(val problem: UrlProblem) : UrlValidation
}

data class NormalizedServerUrl(
    val value: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val isLan: Boolean,
    val origin: String,
)

/**
 * Central URL policy. It is deliberately independent from Android so it can be
 * unit-tested. Cleartext HTTP is only valid for private/LAN hosts, and only in
 * builds that opt into cleartext (debug builds for development).
 */
class UrlPolicy(private val allowCleartext: Boolean) {

    fun validate(raw: String): UrlValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlValidation.Invalid(UrlProblem.EMPTY)

        val explicitScheme = SCHEME_PREFIX.find(trimmed)?.groupValues?.getOrNull(1)
        if (trimmed.contains("://")) {
            if (explicitScheme == null || explicitScheme.lowercase() !in SUPPORTED_SCHEMES) {
                return UrlValidation.Invalid(UrlProblem.UNSUPPORTED_SCHEME)
            }
        } else if (explicitScheme != null && explicitScheme.lowercase() in KNOWN_NON_HTTP_SCHEMES) {
            // "ftp:...", "javascript:...", or "http:/missing-slash".
            return UrlValidation.Invalid(
                if (explicitScheme.lowercase() in SUPPORTED_SCHEMES) {
                    UrlProblem.MALFORMED
                } else {
                    UrlProblem.UNSUPPORTED_SCHEME
                }
            )
        }

        val candidate = if (trimmed.contains("://")) trimmed else inferScheme(trimmed)
        val parsed = candidate.toHttpUrlOrNull()
            ?: return UrlValidation.Invalid(UrlProblem.MALFORMED)

        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return UrlValidation.Invalid(UrlProblem.USER_INFO_NOT_ALLOWED)
        }
        if (parsed.query != null || parsed.fragment != null) {
            return UrlValidation.Invalid(UrlProblem.QUERY_OR_FRAGMENT_NOT_ALLOWED)
        }
        if (!isPlausibleHost(parsed.host)) {
            return UrlValidation.Invalid(UrlProblem.INVALID_HOST)
        }

        val lan = isLanHost(parsed.host)
        if (parsed.scheme == "http") {
            if (!lan) return UrlValidation.Invalid(UrlProblem.INSECURE_REMOTE_HTTP)
            if (!allowCleartext) return UrlValidation.Invalid(UrlProblem.CLEARTEXT_DISABLED)
        }

        return UrlValidation.Valid(parsed.toNormalized())
    }

    fun isAllowedNavigation(target: String, allowedServerUrl: String): Boolean {
        val targetUrl = target.toHttpUrlOrNull() ?: return false
        val allowedUrl = allowedServerUrl.toHttpUrlOrNull() ?: return false
        if (targetUrl.scheme !in SUPPORTED_SCHEMES) return false
        if (targetUrl.scheme == "http" && !allowCleartext) return false

        if (sameOrigin(targetUrl, allowedUrl)) return true

        // Allow a secure upgrade on the same host (e.g. http://host:8080 ->
        // https://host). Cookies are host-scoped, but localStorage is not; the
        // upgrade is safe and commonly used by reverse proxies.
        return targetUrl.scheme == "https" && targetUrl.host == allowedUrl.host
    }

    fun sameOrigin(first: String, second: String): Boolean {
        val firstUrl = first.toHttpUrlOrNull() ?: return false
        val secondUrl = second.toHttpUrlOrNull() ?: return false
        return sameOrigin(firstUrl, secondUrl)
    }

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme &&
            first.host == second.host &&
            first.port == second.port

    private fun inferScheme(raw: String): String {
        val authority = raw.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = when {
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
            authority.contains(':') -> authority.substringBefore(':')
            else -> authority
        }
        return if (isLanHost(host)) "http://$raw" else "https://$raw"
    }

    private fun HttpUrl.toNormalized(): NormalizedServerUrl {
        val hostForOrigin = if (host.contains(':')) "[$host]" else host
        val defaultPort = HttpUrl.defaultPort(scheme)
        val portPart = if (port == defaultPort) "" else ":$port"
        return NormalizedServerUrl(
            value = toString(),
            scheme = scheme,
            host = host,
            port = port,
            isLan = isLanHost(host),
            origin = "$scheme://$hostForOrigin$portPart",
        )
    }

    private fun isPlausibleHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.looksLikeIpv4()) return parseIpv4(host) != null
        return true
    }

    companion object {
        private val SUPPORTED_SCHEMES = setOf("http", "https")
        private val KNOWN_NON_HTTP_SCHEMES = setOf(
            "http", "https", "ftp", "file", "javascript", "data", "mailto", "tel", "intent"
        )
        private val SCHEME_PREFIX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val IPV4_PATTERN = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

        fun isLanHost(rawHost: String): Boolean {
            val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (host.isEmpty()) return false

            if (host == "localhost" || host.endsWith(".localhost")) return true
            if (host.endsWith(".local") ||
                host.endsWith(".lan") ||
                host.endsWith(".home.arpa") ||
                host.endsWith(".internal")
            ) {
                return true
            }

            if (host.looksLikeIpv4()) {
                val octets = parseIpv4(host) ?: return false
                return isPrivateIpv4(octets)
            }

            if (host.contains(':')) return isPrivateIpv6(host)

            // Single-label hostnames (for example "expuz") are resolved by the
            // local network. Public domains always contain a dot.
            return !host.contains('.')
        }

        fun isPrivateIpv4(octets: List<Int>): Boolean {
            if (octets.size != 4) return false
            val (a, b) = octets
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }

        fun parseIpv4(host: String): List<Int>? {
            if (!IPV4_PATTERN.matches(host)) return null
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4) return null
            return octets.takeIf { values -> values.all { it in 0..255 } }
        }

        private fun isPrivateIpv6(host: String): Boolean {
            val normalized = host.lowercase()
            if (normalized == "::1") return true
            if (normalized.startsWith("fc") || normalized.startsWith("fd")) return true
            if (normalized.startsWith("fe8") ||
                normalized.startsWith("fe9") ||
                normalized.startsWith("fea") ||
                normalized.startsWith("feb")
            ) {
                return true
            }
            if (normalized.startsWith("::ffff:")) {
                val mapped = normalized.removePrefix("::ffff:")
                val octets = parseIpv4(mapped) ?: return false
                return isPrivateIpv4(octets)
            }
            return false
        }

        private fun String.looksLikeIpv4(): Boolean = IPV4_PATTERN.matches(this)
    }
}
