package com.newoether.agora.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiAuthTest {
    // Low cost keeps the suite fast; the production default is checked separately.
    private val hasher = WebUiPasswordHasher(iterations = 1_000)

    @Test
    fun hashVerifiesOnlyTheOriginalPasswordAndUsesAFreshSalt() {
        val first = hasher.hash("correct horse")
        val second = hasher.hash("correct horse")

        assertTrue(hasher.verify("correct horse", first))
        assertFalse(hasher.verify("correct horsf", first))
        assertNotEquals(first, second)
        assertFalse(first.contains("correct horse"))
    }

    @Test
    fun malformedStoredHashesNeverVerify() {
        listOf(
            "",
            "plain-text",
            "pbkdf2-sha256\$abc\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256\$1000\$\$aGFzaA",
            "md5\$1000\$c2FsdA\$aGFzaA",
        ).forEach { stored -> assertFalse(stored, hasher.verify("x", stored)) }
    }

    @Test
    fun productionCostIsAtLeast100kIterations() {
        assertTrue(WebUiPasswordHasher.DEFAULT_ITERATIONS >= 100_000)
    }

    @Test
    fun correctPasswordCreatesAValidSessionUntilLogout() {
        val auth = auth(hash = hasher.hash("pw"))

        val result = auth.login("pw") as WebUiLoginResult.Success

        assertTrue(auth.isValidSession(result.sessionToken))
        assertFalse(auth.isValidSession("forged"))
        assertFalse(auth.isValidSession(null))
        auth.logout(result.sessionToken)
        assertFalse(auth.isValidSession(result.sessionToken))
    }

    @Test
    fun missingPasswordRefusesEveryLogin() {
        assertEquals(WebUiLoginResult.NotConfigured, auth(hash = null).login("anything"))
    }

    @Test
    fun tenFailuresLockEveryAttemptForFiveMinutesThenTheCounterRestarts() {
        var now = 1_000L
        val auth = auth(hash = hasher.hash("pw"), clock = { now })

        repeat(9) { index ->
            assertEquals(WebUiLoginResult.WrongPassword(9 - index), auth.login("bad"))
        }
        val locked = auth.login("bad") as WebUiLoginResult.LockedOut
        assertEquals(1_000L + 5 * 60 * 1000L, locked.untilMillis)
        // A correct password is refused too while locked.
        assertEquals(locked, auth.login("pw"))

        now = locked.untilMillis
        assertEquals(WebUiLoginResult.WrongPassword(9), auth.login("bad"))
        assertTrue(auth.login("pw") is WebUiLoginResult.Success)
        // A success resets the count.
        assertEquals(WebUiLoginResult.WrongPassword(9), auth.login("bad"))
    }

    @Test
    fun revokingAllSessionsSignsEveryBrowserOut() {
        val auth = auth(hash = hasher.hash("pw"))
        val first = (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        val second = (auth.login("pw") as WebUiLoginResult.Success).sessionToken

        assertNotEquals(first, second)
        auth.revokeAllSessions()

        assertFalse(auth.isValidSession(first))
        assertFalse(auth.isValidSession(second))
    }

    private fun auth(hash: String?, clock: () -> Long = { 0L }) =
        WebUiAuth(passwordHash = { hash }, hasher = hasher, clock = clock)
}
