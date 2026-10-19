package com.newoether.agora.webui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WebUiEngineStartTest {
    /**
     * Regresión: cuando el servidor se construía dentro de la coroutine, el
     * `CoroutineScope.embeddedServer` de Ktor lo hacía job hijo, así que el `withContext`
     * jamás retornaba y el status de Settings quedaba en Starting mientras el servidor
     * ya respondía requests.
     */
    @Test
    fun startReturnsInsideCoroutineContext(): Unit = runBlocking {
        val routes = WebUiServer(
            auth = WebUiAuth(passwordHash = { null }, hasher = WebUiPasswordHasher()),
            readAsset = { null },
            syncSession = { _, _ -> },
        )
        val engine = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) { startWebUiEngine(port = 0, routes = routes) }
        }
        assertNotNull("server start did not return", engine)
        engine?.stop(100, 500)
    }
}
