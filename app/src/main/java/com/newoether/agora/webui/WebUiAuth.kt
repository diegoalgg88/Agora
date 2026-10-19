package com.newoether.agora.webui

import java.security.SecureRandom
import java.util.Base64

/** Resultado de un intento de login al WebUI. */
internal sealed interface WebUiLoginResult {
    data class Success(val sessionToken: String) : WebUiLoginResult
    /** Quedan [attemptsLeft] fallos antes de que arranque el lockout. */
    data class WrongPassword(val attemptsLeft: Int) : WebUiLoginResult
    /** Todo intento se rechaza hasta [untilMillis], incluso una contraseña correcta. */
    data class LockedOut(val untilMillis: Long) : WebUiLoginResult
    /** No hay contraseña configurada, así el WebUI no puede ingresarse en absoluto. */
    data object NotConfigured : WebUiLoginResult
}

/**
 * Verificación de contraseña, lockout global y sesiones en memoria para el WebUI.
 *
 * El lockout es global, no por dirección: el WebUI tiene un solo usuario, y un contador por
 * dirección dejaría a un atacante rotar direcciones. Tras [maxFailures] fallos consecutivos todo
 * intento se rechaza por [lockoutMillis]; el contador entonces arranca de nuevo. Un éxito lo
 * resetea.
 *
 * Las sesiones viven solo en memoria, así un restart del servicio firma fuera a todo navegador.
 * Cambiar la contraseña debe llamar [revokeAllSessions].
 */
internal class WebUiAuth(
    private val passwordHash: () -> String?,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
) {
    private val lock = Any()
    private var failures = 0
    private var lockedUntil = 0L
    private val sessions = mutableSetOf<String>()

    fun login(password: String): WebUiLoginResult {
        val now = clock()
        synchronized(lock) {
            if (now < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
        }
        val stored = passwordHash() ?: return WebUiLoginResult.NotConfigured
        // Hash fuera del lock: PBKDF2 es lento y no debe bloquear los checks de sesión.
        val matches = hasher.verify(password, stored)
        synchronized(lock) {
            // Otro intento pudo iniciar el lockout mientras este hasheaba.
            if (clock() < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
            if (matches) {
                failures = 0
                val token = newToken()
                sessions += token
                return WebUiLoginResult.Success(token)
            }
            failures += 1
            if (failures >= maxFailures) {
                failures = 0
                lockedUntil = clock() + lockoutMillis
                return WebUiLoginResult.LockedOut(lockedUntil)
            }
            return WebUiLoginResult.WrongPassword(maxFailures - failures)
        }
    }

    fun isValidSession(token: String?): Boolean =
        token != null && synchronized(lock) { token in sessions }

    fun logout(token: String?) {
        if (token == null) return
        synchronized(lock) { sessions -= token }
    }

    fun revokeAllSessions() {
        synchronized(lock) { sessions.clear() }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 10
        const val DEFAULT_LOCKOUT_MILLIS = 5 * 60 * 1000L
        private const val TOKEN_BYTES = 32
    }
}
