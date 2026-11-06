package com.expuz.ai.domain

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerProbeTest {

    private lateinit var server: MockWebServer
    private val probe = OkHttpServerProbe()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun http200IsReachable() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = probe.probe(server.url("/").toString())

        assertTrue(result.reachable)
        assertEquals(200, result.httpStatus)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun unauthorizedStillMeansServerIsReachable() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = probe.probe(server.url("/").toString())

        assertTrue(result.reachable)
        assertEquals(401, result.httpStatus)
    }

    @Test
    fun serverErrorIsNotReachable() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = probe.probe(server.url("/").toString())

        assertFalse(result.reachable)
        assertEquals(FailureKind.HTTP_ERROR, result.failure)
        assertEquals(503, result.httpStatus)
    }

    @Test
    fun probeStillWorksAfterServerClosesIdleConnection() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val first = probe.probe(server.url("/").toString())
        val second = probe.probe(server.url("/").toString())

        assertTrue(first.reachable)
        assertTrue("second probe failed: ${second.failure} ${second.technicalMessage}", second.reachable)
    }

    @Test
    fun redirectsAreFollowed() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/login")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val result = probe.probe(server.url("/").toString())

        assertTrue(result.reachable)
        assertEquals(200, result.httpStatus)
    }
}
