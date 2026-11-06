package com.expuz.ai.domain

import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.data.ServerConfig
import com.expuz.ai.data.ServerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManagerTest {

    private val policy = UrlPolicy(allowCleartext = true)

    @Test
    fun onboardingNormalizesAndPersists() = runTest {
        val store = FakeStore()
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val result = manager.completeOnboarding("192.168.1.20:8080")

        assertTrue(result is SaveResult.Success)
        val config = store.state.value
        assertEquals("http://192.168.1.20:8080/", config.activeServerUrl)
        assertEquals(ConnectionMode.MANUAL, config.mode)
        assertTrue(config.onboardingCompleted)
        assertTrue(config.isConfigured)
    }

    @Test
    fun prepareConfigurationDoesNotPersistUntilConfirmed() = runTest {
        val store = FakeStore(
            ServerConfig(
                activeServerUrl = "http://192.168.1.20:8080/",
                localUrl = "http://192.168.1.20:8080/",
                remoteUrl = "https://ai.example.com/",
                mode = ConnectionMode.MANUAL,
                onboardingCompleted = true,
            )
        )
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val prepared = manager.prepareConfiguration(
            mode = ConnectionMode.AUTO,
            primaryRaw = "",
            localRaw = "192.168.1.20:8080",
            remoteRaw = "https://ai.example.com",
        )

        assertTrue(prepared is SaveResult.Success)
        assertEquals(ConnectionMode.MANUAL, store.state.value.mode)
        val config = (prepared as SaveResult.Success).config
        assertEquals(ConnectionMode.AUTO, config.mode)
        assertEquals("http://192.168.1.20:8080/", config.activeServerUrl)
        assertEquals("https://ai.example.com/", config.remoteUrl)
    }

    @Test
    fun preparedConfigIsPersistedOnConfirmation() = runTest {
        val store = FakeStore(
            ServerConfig(
                activeServerUrl = "http://192.168.1.20:8080/",
                onboardingCompleted = true,
            )
        )
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)
        val prepared = manager.prepareConfiguration(
            mode = ConnectionMode.AUTO,
            primaryRaw = "",
            localRaw = "http://192.168.1.20:8080/",
            remoteRaw = "https://ai.example.com/",
        ) as SaveResult.Success

        manager.persistConfiguration(prepared.config)

        assertEquals(ConnectionMode.AUTO, store.state.value.mode)
        assertEquals("http://192.168.1.20:8080/", store.state.value.activeServerUrl)
    }

    @Test
    fun automaticModeKeepsCurrentRemoteOriginWhenItMatches() = runTest {
        val store = FakeStore(
            ServerConfig(
                activeServerUrl = "https://ai.example.com/",
                localUrl = "http://192.168.1.20:8080/",
                remoteUrl = "https://ai.example.com/",
                mode = ConnectionMode.AUTO,
                onboardingCompleted = true,
            )
        )
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val prepared = manager.prepareConfiguration(
            mode = ConnectionMode.AUTO,
            primaryRaw = "",
            localRaw = "http://192.168.1.20:8080/",
            remoteRaw = "https://ai.example.com/",
        ) as SaveResult.Success

        assertEquals("https://ai.example.com/", prepared.config.activeServerUrl)
    }

    @Test
    fun remoteAddressMustBeHttps() = runTest {
        val store = FakeStore(ServerConfig(onboardingCompleted = true))
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val result = manager.prepareConfiguration(
            mode = ConnectionMode.AUTO,
            primaryRaw = "",
            localRaw = "http://192.168.1.20:8080/",
            remoteRaw = "http://192.168.1.21:8080/",
        )

        assertTrue(result is SaveResult.Invalid)
        val invalid = result as SaveResult.Invalid
        assertEquals(ServerField.REMOTE, invalid.field)
        assertEquals(UrlProblem.INSECURE_REMOTE_HTTP, invalid.problem)
    }

    @Test
    fun automaticModeAcceptsRemoteOnlySetup() = runTest {
        val store = FakeStore(ServerConfig(onboardingCompleted = true))
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val result = manager.prepareConfiguration(
            mode = ConnectionMode.AUTO,
            primaryRaw = "",
            localRaw = "",
            remoteRaw = "https://ai.example.com",
        )

        assertTrue(result.toString(), result is SaveResult.Success)
        assertEquals(
            "https://ai.example.com/",
            (result as SaveResult.Success).config.activeServerUrl,
        )
    }

    @Test
    fun fallbackIsSuggestedOnlyInAutomaticMode() = runTest {
        val config = ServerConfig(
            activeServerUrl = "http://192.168.1.20:8080/",
            localUrl = "http://192.168.1.20:8080/",
            remoteUrl = "https://ai.example.com/",
            mode = ConnectionMode.AUTO,
            onboardingCompleted = true,
        )
        val store = FakeStore(config)
        val probe = FakeProbe(
            mapOf(
                "http://192.168.1.20:8080/" to false,
                "https://ai.example.com/" to true,
            )
        )
        val manager = ServerManager(store, probe, policy)

        val fallback = manager.findFallback(config, "http://192.168.1.20:8080/")

        assertNotNull(fallback)
        assertEquals(ServerKind.REMOTE, fallback?.kind)
        assertEquals("https://ai.example.com/", fallback?.url)

        val manual = config.copy(mode = ConnectionMode.MANUAL)
        assertNull(manager.findFallback(manual, "http://192.168.1.20:8080/"))
    }

    @Test
    fun localFallbackIsSuggestedWhenRemoteFails() = runTest {
        val config = ServerConfig(
            activeServerUrl = "https://ai.example.com/",
            localUrl = "http://192.168.1.20:8080/",
            remoteUrl = "https://ai.example.com/",
            mode = ConnectionMode.AUTO,
            onboardingCompleted = true,
        )
        val store = FakeStore(config)
        val probe = FakeProbe(
            mapOf(
                "http://192.168.1.20:8080/" to true,
                "https://ai.example.com/" to false,
            )
        )
        val manager = ServerManager(store, probe, policy)

        val fallback = manager.findFallback(config, "https://ai.example.com/")

        assertEquals(ServerKind.LOCAL, fallback?.kind)
        assertEquals("http://192.168.1.20:8080/", fallback?.url)
    }

    @Test
    fun switchingServerChangesActiveOriginOnly() = runTest {
        val store = FakeStore(
            ServerConfig(
                activeServerUrl = "http://192.168.1.20:8080/",
                localUrl = "http://192.168.1.20:8080/",
                remoteUrl = "https://ai.example.com/",
                mode = ConnectionMode.AUTO,
                onboardingCompleted = true,
            )
        )
        val manager = ServerManager(store, FakeProbe(emptyMap()), policy)

        val result = manager.switchActiveServer("https://ai.example.com/")

        assertTrue(result is SaveResult.Success)
        assertEquals("https://ai.example.com/", store.state.value.activeServerUrl)
        assertEquals(ConnectionMode.AUTO, store.state.value.mode)
        assertEquals("http://192.168.1.20:8080/", store.state.value.localUrl)
    }

    @Test
    fun serverKindUsesConfiguredAddresses() = runTest {
        val config = ServerConfig(
            activeServerUrl = "https://ai.example.com/",
            localUrl = "http://192.168.1.20:8080/",
            remoteUrl = "https://ai.example.com/",
            mode = ConnectionMode.AUTO,
            onboardingCompleted = true,
        )
        val manager = ServerManager(FakeStore(config), FakeProbe(emptyMap()), policy)

        assertEquals(ServerKind.REMOTE, manager.serverKind(config))
        assertEquals(
            ServerKind.LOCAL,
            manager.serverKind(config, "http://192.168.1.20:8080/"),
        )
        assertFalse(
            manager.candidates(config).isEmpty()
        )
    }

    private class FakeStore(
        initial: ServerConfig = ServerConfig(),
    ) : ServerStore {
        val state = MutableStateFlow(initial)
        override val config: Flow<ServerConfig> = state
        override suspend fun current(): ServerConfig = state.value
        override suspend fun save(config: ServerConfig) {
            state.value = config
        }
    }

    private class FakeProbe(
        private val reachability: Map<String, Boolean>,
    ) : ServerProbe {
        override suspend fun probe(url: String): ProbeResult {
            val reachable = reachability[url] ?: false
            return if (reachable) {
                ProbeResult(reachable = true, httpStatus = 200, latencyMs = 5)
            } else {
                ProbeResult(
                    reachable = false,
                    failure = FailureKind.CONNECTION_REFUSED,
                )
            }
        }
    }
}
