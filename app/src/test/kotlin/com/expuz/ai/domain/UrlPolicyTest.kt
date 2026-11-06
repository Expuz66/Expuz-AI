package com.expuz.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private val debugPolicy = UrlPolicy(allowCleartext = true)
    private val releasePolicy = UrlPolicy(allowCleartext = false)

    @Test
    fun httpsRemoteIsValid() {
        val result = debugPolicy.validate("https://ai.example.com")
        assertTrue(result is UrlValidation.Valid)
        val url = (result as UrlValidation.Valid).url
        assertEquals("https://ai.example.com/", url.value)
        assertEquals("https://ai.example.com", url.origin)
        assertFalse(url.isLan)
    }

    @Test
    fun privateHttpIsAllowedInDebug() {
        val result = debugPolicy.validate("http://192.168.1.20:8080")
        assertTrue(result is UrlValidation.Valid)
        val url = (result as UrlValidation.Valid).url
        assertTrue(url.isLan)
        assertEquals("http://192.168.1.20:8080", url.origin)
    }

    @Test
    fun privateHttpIsRejectedInRelease() {
        val result = releasePolicy.validate("http://192.168.1.20:8080")
        assertTrue(result is UrlValidation.Invalid)
        assertEquals(
            UrlProblem.CLEARTEXT_DISABLED,
            (result as UrlValidation.Invalid).problem,
        )
    }

    @Test
    fun publicHttpIsRejectedEvenInDebug() {
        val result = debugPolicy.validate("http://example.com")
        assertTrue(result is UrlValidation.Invalid)
        assertEquals(
            UrlProblem.INSECURE_REMOTE_HTTP,
            (result as UrlValidation.Invalid).problem,
        )
    }

    @Test
    fun missingSchemeInfersHttpForLan() {
        val result = debugPolicy.validate("192.168.1.20:8080")
        assertTrue(result is UrlValidation.Valid)
        assertEquals(
            "http://192.168.1.20:8080/",
            (result as UrlValidation.Valid).url.value,
        )
    }

    @Test
    fun missingSchemeInfersHttpsForPublicHost() {
        val result = debugPolicy.validate("ai.example.com")
        assertTrue(result is UrlValidation.Valid)
        assertEquals("https://ai.example.com/", (result as UrlValidation.Valid).url.value)
    }

    @Test
    fun hostnameWithPortIsNotMistakenForScheme() {
        val result = debugPolicy.validate("localhost:8080")
        assertTrue(result is UrlValidation.Valid)
        assertEquals("http://localhost:8080/", (result as UrlValidation.Valid).url.value)
    }

    @Test
    fun userInfoIsRejected() {
        val result = debugPolicy.validate("https://user:password@example.com")
        assertTrue(result is UrlValidation.Invalid)
        assertEquals(
            UrlProblem.USER_INFO_NOT_ALLOWED,
            (result as UrlValidation.Invalid).problem,
        )
    }

    @Test
    fun queryAndFragmentAreRejected() {
        assertProblem("https://example.com/?a=1", UrlProblem.QUERY_OR_FRAGMENT_NOT_ALLOWED)
        assertProblem("https://example.com/#chat", UrlProblem.QUERY_OR_FRAGMENT_NOT_ALLOWED)
    }

    @Test
    fun unsupportedSchemesAreRejected() {
        assertProblem("ftp://example.com", UrlProblem.UNSUPPORTED_SCHEME)
        assertProblem("javascript:alert(1)", UrlProblem.UNSUPPORTED_SCHEME)
        assertProblem("mailto:test@example.com", UrlProblem.UNSUPPORTED_SCHEME)
    }

    @Test
    fun malformedHttpSchemeIsRejected() {
        assertProblem("http:/missing-slash", UrlProblem.MALFORMED)
    }

    @Test
    fun ipv6LoopbackIsTreatedAsLan() {
        val result = debugPolicy.validate("http://[::1]:8080")
        assertTrue(result is UrlValidation.Valid)
        val url = (result as UrlValidation.Valid).url
        assertTrue(url.isLan)
        assertEquals("http://[::1]:8080", url.origin)
    }

    @Test
    fun lanRangesAreDetected() {
        assertTrue(UrlPolicy.isLanHost("10.0.0.2"))
        assertTrue(UrlPolicy.isLanHost("172.16.0.1"))
        assertTrue(UrlPolicy.isLanHost("172.31.255.254"))
        assertTrue(UrlPolicy.isLanHost("192.168.0.5"))
        assertTrue(UrlPolicy.isLanHost("169.254.1.1"))
        assertTrue(UrlPolicy.isLanHost("127.0.0.1"))
        assertTrue(UrlPolicy.isLanHost("localhost"))
        assertTrue(UrlPolicy.isLanHost("expuz.local"))
        assertTrue(UrlPolicy.isLanHost("living-room-pc"))
        assertTrue(UrlPolicy.isLanHost("fd00::1"))

        assertFalse(UrlPolicy.isLanHost("172.32.0.1"))
        assertFalse(UrlPolicy.isLanHost("8.8.8.8"))
        assertFalse(UrlPolicy.isLanHost("ai.example.com"))
        assertFalse(UrlPolicy.isLanHost("0.0.0.0"))
    }

    @Test
    fun navigationIsLimitedToTheConfiguredOrigin() {
        val server = "http://192.168.1.10:8080/"
        assertTrue(debugPolicy.isAllowedNavigation("$server/chat/1", server))
        assertFalse(debugPolicy.isAllowedNavigation("http://192.168.1.11:8080/", server))
        assertFalse(debugPolicy.isAllowedNavigation("https://example.com/", server))
        assertFalse(debugPolicy.isAllowedNavigation("http://example.com/", server))
        // Same host upgrade is allowed (common reverse-proxy redirect).
        assertTrue(
            debugPolicy.isAllowedNavigation(
                "https://192.168.1.10/chat",
                server,
            )
        )
    }

    @Test
    fun cleartextNavigationIsRejectedInRelease() {
        assertFalse(
            releasePolicy.isAllowedNavigation(
                "http://example.com/",
                "https://example.com/",
            )
        )
    }

    @Test
    fun sameOriginIgnoresPath() {
        assertTrue(
            debugPolicy.sameOrigin(
                "http://192.168.1.10:8080/chat?x=1",
                "http://192.168.1.10:8080/",
            )
        )
        assertFalse(
            debugPolicy.sameOrigin(
                "https://192.168.1.10:8080/",
                "http://192.168.1.10:8080/",
            )
        )
    }

    private fun assertProblem(raw: String, expected: UrlProblem) {
        val result = debugPolicy.validate(raw)
        assertTrue("Expected invalid for $raw", result is UrlValidation.Invalid)
        assertEquals(expected, (result as UrlValidation.Invalid).problem)
    }
}
