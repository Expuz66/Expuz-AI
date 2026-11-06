package com.expuz.ai

import android.content.Context
import com.expuz.ai.data.DataStoreServerStore
import com.expuz.ai.data.ServerStore
import com.expuz.ai.domain.OkHttpServerProbe
import com.expuz.ai.domain.ServerManager
import com.expuz.ai.domain.ServerProbe
import com.expuz.ai.domain.UrlPolicy
import com.expuz.ai.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val urlPolicy: UrlPolicy = UrlPolicy(BuildConfig.DEBUG)

    val serverStore: ServerStore = DataStoreServerStore(context.applicationContext)

    private val serverProbe: ServerProbe = OkHttpServerProbe()

    val serverManager: ServerManager = ServerManager(
        store = serverStore,
        probe = serverProbe,
        urlPolicy = urlPolicy,
    )

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val updateManager: UpdateManager = UpdateManager(updateScope)
}
