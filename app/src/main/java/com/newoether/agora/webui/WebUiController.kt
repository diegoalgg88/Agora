package com.newoether.agora.webui

import android.content.Context
import com.newoether.agora.util.DebugLog
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Si el servidor del WebUI está escuchando. */
internal sealed interface WebUiStatus {
    data object Stopped : WebUiStatus
    data object Starting : WebUiStatus
    data class Running(val port: Int) : WebUiStatus
    /** El servidor no pudo arrancar, típicamente porque el puerto está en uso. */
    data class Failed(val message: String) : WebUiStatus
}

/**
 * Bindea y arranca el servidor, retornando cuando ya escucha.
 *
 * Función plana a propósito: dentro del cuerpo de una coroutine, `embeddedServer` resuelve a la
 * sobrecarga `CoroutineScope.embeddedServer` de Ktor, que hace del servidor un hijo de esa
 * coroutine — el `withContext` que la envuelve nunca retornaría mientras el servidor corre y el
 * status quedaba pegado en Starting aunque ya respondiera (fix upstream 5e0f1db2).
 */
internal fun startWebUiEngine(port: Int, routes: WebUiServer): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port, host = WebUiController.ANY_HOST) {
        routes.install(this)
    }.start(wait = false)

/**
 * Dueño process-scoped del WebUI: settings, autenticación y el servidor embebido.
 *
 * [WebUiService] mantiene vivo el proceso y llama [startServer]/[stopServer]; la página de
 * Settings llama las funciones `set*`. El servidor escucha en todas las interfaces (LAN,
 * Tailscale), así que solo corre mientras haya una contraseña configurada.
 */
internal class WebUiController(
    private val appContext: Context,
    private val store: WebUiSettingsStore,
    scope: CoroutineScope,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
) {
    @Volatile private var passwordHash: String? = null
    private val auth = WebUiAuth(passwordHash = { passwordHash }, hasher = hasher)
    @Volatile private var theme: WebUiTheme? = null
    private val routes = WebUiServer(
        auth = auth,
        readAsset = ::readAsset,
        themeCss = { theme?.toCss().orEmpty() },
        readAppFont = ::readAppFont,
    )
    private val serverLock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private val _status = MutableStateFlow<WebUiStatus>(WebUiStatus.Stopped)

    val status: StateFlow<WebUiStatus> = _status.asStateFlow()
    val enabled: Flow<Boolean> = store.enabled
    val port: Flow<Int> = store.port
    val hasPassword: Flow<Boolean> = store.passwordHash.map { it != null }

    init {
        scope.launch { store.passwordHash.collect { passwordHash = it } }
    }

    /** Guarda el hash de la nueva contraseña y firma fuera a todo navegador. */
    suspend fun setPassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password is too short" }
        val hash = withContext(Dispatchers.Default) { hasher.hash(password) }
        store.savePasswordHash(hash)
        passwordHash = hash
        auth.revokeAllSessions()
    }

    /** Retorna false cuando se rehúsa habilitar porque no hay contraseña configurada. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && store.passwordHash.first() == null) return false
        store.saveEnabled(enabled)
        if (enabled) WebUiService.start(appContext) else WebUiService.stop(appContext)
        return true
    }

    /** Guarda el puerto y, si el servidor corre, lo mueve al nuevo puerto. */
    suspend fun setPort(port: Int) {
        store.savePort(port)
        if (serverLock.withLock { engine != null }) {
            stopServer()
            startServer()
        }
    }

    /** Llamado con la app en foreground: restaura un servidor que el usuario dejó habilitado. */
    suspend fun startIfEnabled() {
        if (store.enabled.first() && store.passwordHash.first() != null &&
            _status.value !is WebUiStatus.Running
        ) {
            WebUiService.start(appContext)
        }
    }

    suspend fun startServer() = serverLock.withLock {
        if (engine != null) return@withLock
        val port = store.port.first()
        passwordHash = store.passwordHash.first()
        _status.value = WebUiStatus.Starting
        _status.value = withContext(Dispatchers.IO) {
            try {
                engine = startWebUiEngine(port, routes)
                WebUiStatus.Running(port)
            } catch (error: Exception) {
                DebugLog.e(TAG, "WebUI server failed to start on port $port", error)
                engine = null
                WebUiStatus.Failed(error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    suspend fun stopServer() = serverLock.withLock {
        val running = engine ?: return@withLock
        engine = null
        withContext(Dispatchers.IO) {
            runCatching { running.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
                .onFailure { DebugLog.e(TAG, "WebUI server failed to stop cleanly", it) }
        }
        auth.revokeAllSessions()
        _status.value = WebUiStatus.Stopped
    }

    /** `http://<address>:<port>` para cada dirección IPv4 no-loopback que esté activa. */
    fun accessUrls(port: Int): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port" }
            .distinct()
    }.getOrDefault(emptyList())

    /** Lo llama el tema Compose; la próxima carga de página en el navegador lo usa. */
    fun publishTheme(theme: WebUiTheme) {
        this.theme = theme
    }

    private fun readAppFont(): ByteArray? = runCatching {
        when (val font = theme?.font) {
            WebUiFont.AppDefault ->
                appContext.resources.openRawResource(com.newoether.agora.R.font.mioutfit_variable).use { it.readBytes() }
            is WebUiFont.Custom -> java.io.File(font.path).takeIf { it.isFile }?.readBytes()
            WebUiFont.System, null -> null
        }
    }.getOrNull()

    private fun readAsset(path: String): ByteArray? = runCatching {
        appContext.assets.open("$ASSET_ROOT/$path").use { it.readBytes() }
    }.getOrNull()

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val TAG = "WebUi"
        const val ANY_HOST = "0.0.0.0"
        private const val ASSET_ROOT = "webui"
        private const val STOP_GRACE_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
