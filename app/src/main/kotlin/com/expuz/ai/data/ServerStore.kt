package com.expuz.ai.data

import kotlinx.coroutines.flow.Flow

interface ServerStore {
    val config: Flow<ServerConfig>

    suspend fun current(): ServerConfig

    suspend fun save(config: ServerConfig)
}
