package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.KeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.crypto.SrpClient
import dev.rboza.android.opensyncforproton.crypto.SrpException
import dev.rboza.android.opensyncforproton.crypto.SrpProofs
import dev.rboza.android.opensyncforproton.crypto.UnlockedKeys
import dev.rboza.android.opensyncforproton.proton.AuthDto
import dev.rboza.android.opensyncforproton.proton.AuthInfoDto
import dev.rboza.android.opensyncforproton.proton.CaptchaHeaders
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ContactDto
import dev.rboza.android.opensyncforproton.proton.ContactExportPageDto
import dev.rboza.android.opensyncforproton.proton.ContactListPageDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.KeySaltDto
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.SrpAuthDto
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.TwoFactorDto
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.proton.UserKeyDto
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Set while work runs on the test's stand-in for Dispatchers.Default. */
private val onCpu: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

/** Which SRP and key work ran, and whether it ran there. */
private val cpuWork = mutableListOf<Pair<String, Boolean>>()

// docs/protocol.md § Login and § Captcha (Code 9001).
// Every value is generated for the test; none is a real Proton value.
@OptIn(ExperimentalCoroutinesApi::class)
class LoginStateMachineTest {
    private val calls = mutableListOf<String>()

    init {
        cpuWork.clear()
    }
    private val api = FakeApi()
    private val srp = FakeSrp()
    private val keys = FakeKeys()
    private var store: TokenStore? = null

    /** Stands in for Dispatchers.Default: counts the work sent to it and marks it while it runs. */
    private class CpuDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context) {
                onCpu.set(true)
                try {
                    block.run()
                } finally {
                    onCpu.set(false)
                }
            }
        }
    }

    private lateinit var cpu: CpuDispatcher

    private fun TestScope.interactor(): ProtonLoginInteractor {
        cpu = CpuDispatcher(StandardTestDispatcher(testScheduler))
        return ProtonLoginInteractor(
            newClient = { tokenStore -> store = tokenStore; api },
            srp = srp,
            keys = keys,
            scope = this,
            cpu = cpu,
        )
    }

    private fun password(text: String = "login-password") = text.toCharArray()

    private fun failed(reason: LoginFailure, step: LoginStep) = assertEquals(LoginStep.Failed(reason), step)

    @Test
    fun `empty input sends nothing`() = runTest {
        val login = interactor()
        val blankPassword = CharArray(0)

        failed(LoginFailure.EMPTY_INPUT, login.submitCredentials("  ", password()))
        failed(LoginFailure.EMPTY_INPUT, login.submitCredentials("user", blankPassword))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `one-password login hands over the session`() = runTest {
        val typed = password()

        val step = interactor().submitCredentials("user", typed)

        val session = assertInstanceOf(LoginStep.Success::class.java, step).session
        assertEquals(listOf("A1 user", "A2 user", "U1", "U3"), calls)
        assertEquals(AccountId("user-id-1"), session.accountId)
        assertEquals("user@example.test", session.accountName)
        val credentials = session.credentials
        assertEquals(listOf("session-uid-1", "access-token-1", "refresh-token-1"), listOf(credentials.tokens.uid, credentials.tokens.access, credentials.tokens.refresh))
        assertEquals(PasswordMode.ONE, credentials.passwordMode)
        assertEquals(mapOf("key-id-1" to "salt-1", "key-id-2" to "salt-2", "address-key-id-1" to null), credentials.keySalts)
        assertEquals(
            listOf(listOf("key-id-1", "armored-key-1", true, true), listOf("key-id-2", "armored-key-2", false, false)),
            credentials.keys.userKeys.map { listOf(it.id, it.armoredPrivateKey, it.active, it.primary) },
        )
        assertArrayEquals("passphrase(login-password,salt-1)".toByteArray(), credentials.passphrases["key-id-1"])
        assertArrayEquals("passphrase(login-password,salt-2)".toByteArray(), credentials.passphrases["key-id-2"])
        assertTrue(keys.closed, "the check's unlocked keys are closed")
        assertTrue(typed.all { it == '\u0000' }, "the typed password is zeroed")
        assertTrue(srp.passwordsSeen.all { copy -> copy.all { it == '\u0000' } }, "every copy is zeroed")
    }

    @Test
    fun `SRP and the key check run off the caller's thread`() = runTest {
        interactor().submitCredentials("user", password())

        assertEquals(2, cpu.dispatches, "the proofs, then the passphrases and the unlock")
        assertEquals(listOf("proofs" to true, "passphrase" to true, "passphrase" to true, "unlock" to true), cpuWork)
    }

    @Test
    fun `cancel ends the step, not the caller's coroutine`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())
        api.a3Gate = CompletableDeferred()
        var outcome: Throwable? = null
        var carriedOn = false
        launch {
            outcome = runCatching { login.submitTotp("123456") }.exceptionOrNull()
            delay(1)
            carriedOn = true
        }
        runCurrent()

        login.cancel()
        advanceUntilIdle()

        assertInstanceOf(CancellationException::class.java, outcome)
        assertTrue(carriedOn)
    }

    @Test
    fun `the proofs use what A1 returned and A2 sends them`() = runTest {
        interactor().submitCredentials("user", password())

        assertEquals(listOf("4 user login-password salt-a1 modulus-a1 ephemeral-a1"), srp.proofCalls)
        assertEquals(listOf("user client-ephemeral client-proof srp-session-a1"), api.a2Requests)
    }

    @Test
    fun `salts are matched to keys by ID`() = runTest {
        api.salts = listOf(KeySaltDto("key-id-2", "salt-2"), KeySaltDto("other", "salt-x"), KeySaltDto("key-id-1", "salt-1"))

        interactor().submitCredentials("user", password())

        assertEquals(mapOf("key-id-1" to "passphrase(login-password,salt-1)", "key-id-2" to "passphrase(login-password,salt-2)"), keys.passphrasesSeen)
    }

    @Test
    fun `a key without a salt gets no passphrase`() = runTest {
        api.salts = listOf(KeySaltDto("key-id-1", "salt-1"), KeySaltDto("key-id-2", null))

        interactor().submitCredentials("user", password())

        assertEquals(setOf("key-id-1"), keys.passphrasesSeen.keys)
    }

    @Test
    fun `a ServerProof mismatch is refused and the session ended`() = runTest {
        api.serverProof = "other-proof"

        failed(LoginFailure.SERVER_NOT_TRUSTED, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "A5 access-token-1"), calls)
        assertNull(store!!.current(), "tokens discarded")
    }

    @Test
    fun `a bad signed modulus is refused before A2`() = runTest {
        srp.proofsFailure = SrpException()

        failed(LoginFailure.SERVER_NOT_TRUSTED, interactor().submitCredentials("user", password()))

        assertEquals(listOf("A1 user"), calls)
    }

    @Test
    fun `TOTP is asked for, then the login goes on`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()

        assertEquals(LoginStep.NeedTotp(wrongCode = false), login.submitCredentials("user", password()))
        assertEquals(listOf("A1 user", "A2 user"), calls)
        assertInstanceOf(LoginStep.Success::class.java, login.submitTotp("123456"))

        assertEquals(listOf("A1 user", "A2 user", "A3 123456", "U1", "U3"), calls)
        assertEquals("passphrase(login-password,salt-1)", keys.passphrasesSeen["key-id-1"], "the login password was kept through 2FA")
        assertTrue(srp.passwordsSeen.all { copy -> copy.all { it == '\u0000' } }, "the kept copy is zeroed once used")
    }

    @Test
    fun `a failure after 2FA zeroes the kept password`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        keys.results += CryptoResult.Rejected(RejectReason.UNSUPPORTED_KEY)
        val login = interactor()
        login.submitCredentials("user", password())

        failed(LoginFailure.KEY_UNSUPPORTED, login.submitTotp("123456"))

        assertTrue(srp.passwordsSeen.isNotEmpty())
        assertTrue(srp.passwordsSeen.all { copy -> copy.all { it == '\u0000' } })
    }

    @Test
    fun `a TOTP code that isn't six digits is refused without sending it`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())

        listOf("12345", "1234567", "12345a", "١٢٣٤٥٦").forEach {
            assertEquals(LoginStep.NeedTotp(wrongCode = true), login.submitTotp(it), it)
        }
        assertInstanceOf(LoginStep.Success::class.java, login.submitTotp(" 123 456 "))

        assertEquals(listOf("A1 user", "A2 user", "A3 123456", "U1", "U3"), calls, "spaces are dropped, the rest never sent")
    }

    @Test
    fun `TOTP with both factors enabled is offered`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 3, totp = 1)

        assertEquals(LoginStep.NeedTotp(wrongCode = false), interactor().submitCredentials("user", password()))
    }

    @Test
    fun `a wrong TOTP code asks again and keeps the session`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        api.a3Failures += ProtonError.Api(401, null)
        val login = interactor()
        login.submitCredentials("user", password())

        assertEquals(LoginStep.NeedTotp(wrongCode = true), login.submitTotp("000000"))
        assertInstanceOf(LoginStep.Success::class.java, login.submitTotp("123456"))
        advanceUntilIdle()

        assertFalse(calls.any { it.startsWith("A5") })
    }

    @Test
    fun `an empty TOTP code sends nothing`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())

        failed(LoginFailure.EMPTY_INPUT, login.submitTotp(" "))

        assertEquals(listOf("A1 user", "A2 user"), calls)
    }

    @Test
    fun `FIDO-only is refused and the session ended`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 2, totp = 0)

        failed(LoginFailure.FIDO_ONLY, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "A5 access-token-1"), calls)
    }

    @Test
    fun `contradictory 2FA fails closed`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 2, totp = 1)

        failed(LoginFailure.TWO_FA_UNSUPPORTED, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertTrue("A5 access-token-1" in calls)
    }

    @Test
    fun `two-password mode asks for the mailbox password`() = runTest {
        api.passwordMode = 2
        val login = interactor()
        val mailbox = password("mailbox-password")

        assertEquals(LoginStep.NeedMailboxPassword(wrongPassword = false), login.submitCredentials("user", password()))
        val session = assertInstanceOf(LoginStep.Success::class.java, login.submitMailboxPassword(mailbox)).session

        assertEquals(PasswordMode.TWO, session.credentials.passwordMode)
        assertArrayEquals("passphrase(mailbox-password,salt-1)".toByteArray(), session.credentials.passphrases["key-id-1"])
        assertTrue(mailbox.all { it == '\u0000' })
    }

    @Test
    fun `a wrong mailbox password asks again and keeps the session`() = runTest {
        api.passwordMode = 2
        keys.results += CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)
        val login = interactor()
        login.submitCredentials("user", password())
        val wrong = password("wrong-password")

        assertEquals(LoginStep.NeedMailboxPassword(wrongPassword = true), login.submitMailboxPassword(wrong))
        assertTrue(wrong.all { it == '\u0000' })
        assertTrue(keys.arraysSeen.all { bytes -> bytes.all { it == 0.toByte() } }, "the refused passphrases are zeroed")
        assertInstanceOf(LoginStep.Success::class.java, login.submitMailboxPassword(password("mailbox-password")))
        advanceUntilIdle()

        assertFalse(calls.any { it.startsWith("A5") })
        assertEquals(1, calls.count { it == "U1" }, "what was read is reused for the second try")
    }

    @Test
    fun `an empty mailbox password sends nothing`() = runTest {
        api.passwordMode = 2
        val login = interactor()
        login.submitCredentials("user", password())

        failed(LoginFailure.EMPTY_INPUT, login.submitMailboxPassword(CharArray(0)))

        assertEquals(listOf("A1 user", "A2 user"), calls)
    }

    @Test
    fun `an unknown password mode fails closed`() = runTest {
        api.passwordMode = 3

        failed(LoginFailure.PASSWORD_MODE_UNSUPPORTED, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertTrue("A5 access-token-1" in calls)
    }

    @Test
    fun `key refusals have their own reasons and end the session`() = runTest {
        val cases = mapOf(
            RejectReason.V6_KEY to LoginFailure.KEY_V6,
            RejectReason.POST_QUANTUM_KEY to LoginFailure.KEY_POST_QUANTUM,
            RejectReason.UNSUPPORTED_KEY to LoginFailure.KEY_UNSUPPORTED,
            RejectReason.NO_PRIMARY_KEY to LoginFailure.KEY_UNSUPPORTED,
            RejectReason.NO_PASSPHRASE to LoginFailure.KEY_UNSUPPORTED,
            // In one-password mode the login password must unlock the keys; anything else isn't a typo to retry.
            RejectReason.WRONG_PASSPHRASE to LoginFailure.KEY_UNSUPPORTED,
            RejectReason.CORRUPT_DATA to LoginFailure.KEY_CORRUPT,
            RejectReason.BAD_SIGNATURE to LoginFailure.KEY_CORRUPT,
        )
        cases.forEach { (reject, failure) ->
            calls.clear()
            keys.results += CryptoResult.Rejected(reject)

            failed(failure, interactor().submitCredentials("user", password()))
            advanceUntilIdle()

            assertTrue("A5 access-token-1" in calls, "$reject")
        }
    }

    @Test
    fun `a user that isn't the one A2 signed in fails closed`() = runTest {
        api.userId = "user-id-2"

        failed(LoginFailure.SERVER_ERROR, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertTrue("A5 access-token-1" in calls)
    }

    @Test
    fun `errors before A2 map to reasons and end nothing`() = runTest {
        val cases = listOf(
            ProtonError.Api(422, 8002) to LoginFailure.LOGIN_REFUSED,
            ProtonError.Network() to LoginFailure.NETWORK,
            ProtonError.AppVersionRejected(5003) to LoginFailure.APP_VERSION_REJECTED,
            ProtonError.BadResponse(200) to LoginFailure.SERVER_ERROR,
            ProtonError.HumanVerification(HvDetails("hv-token", "https://verify.proton.me/", listOf("captcha"))) to LoginFailure.VERIFY_IN_WEB_APP,
        )
        cases.forEach { (error, failure) ->
            calls.clear()
            api.a1Failures += error

            failed(failure, interactor().submitCredentials("user", password()))

            assertEquals(listOf("A1 user"), calls, "$error")
        }
    }

    @Test
    fun `an A2 refusal maps the same way`() = runTest {
        api.a2Failures += ProtonError.Api(422, 8002)

        failed(LoginFailure.LOGIN_REFUSED, interactor().submitCredentials("user", password()))

        assertEquals(listOf("A1 user", "A2 user"), calls)
    }

    @Test
    fun `an error after A2 ends the session`() = runTest {
        api.userFailure = ProtonError.Network()

        failed(LoginFailure.NETWORK, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "U1", "A5 access-token-1"), calls)
        assertNull(store!!.current())
    }

    @Test
    fun `an API error after A2 is a server error`() = runTest {
        api.userFailure = ProtonError.Api(500, null)

        failed(LoginFailure.SERVER_ERROR, interactor().submitCredentials("user", password()))
    }

    @Test
    fun `a failing A5 is ignored`() = runTest {
        api.userFailure = ProtonError.Network()
        api.logoutFailure = ProtonError.Network()

        failed(LoginFailure.NETWORK, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertEquals(1, calls.count { it.startsWith("A5") })
    }

    @Test
    fun `a 429 on A2 restarts at A1 after Retry-After`() = runTest {
        api.a2Failures += ProtonError.RateLimited(5.seconds)

        assertInstanceOf(LoginStep.Success::class.java, interactor().submitCredentials("user", password()))

        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user", "U1", "U3"), calls)
        assertEquals(5_000, currentTime)
        assertEquals(2, srp.proofCalls.size, "a fresh SRP session for the retry")
    }

    @Test
    fun `at most 3 tries of A1 then A2`() = runTest {
        repeat(3) { api.a2Failures += ProtonError.RateLimited(1.seconds) }

        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, 1.seconds), interactor().submitCredentials("user", password()))

        assertEquals(3, calls.count { it == "A2 user" })
        assertEquals(3, calls.count { it == "A1 user" })
    }

    @Test
    fun `at most 3 tries of A3`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        repeat(3) { api.a3Failures += ProtonError.RateLimited(1.seconds) }
        val login = interactor()
        login.submitCredentials("user", password())

        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, 1.seconds), login.submitTotp("123456"))

        assertEquals(3, calls.count { it == "A3 123456" })
    }

    @Test
    fun `a 429 on A1 is retried the same way`() = runTest {
        api.a1Failures += ProtonError.RateLimited(2.seconds)

        assertInstanceOf(LoginStep.Success::class.java, interactor().submitCredentials("user", password()))

        assertEquals(listOf("A1 user", "A1 user", "A2 user", "U1", "U3"), calls)
    }

    @Test
    fun `a delay over 30 s, or none, is shown instead of waited`() = runTest {
        api.a2Failures += ProtonError.RateLimited(31.seconds)
        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, 31.seconds), interactor().submitCredentials("user", password()))
        assertEquals(0, currentTime)

        calls.clear()
        api.a2Failures += ProtonError.RateLimited(null)
        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, null), interactor().submitCredentials("user", password()))
        assertEquals(1, calls.count { it == "A2 user" })
    }

    @Test
    fun `a delay of exactly 30 s is waited`() = runTest {
        api.a2Failures += ProtonError.RateLimited(30.seconds)

        assertInstanceOf(LoginStep.Success::class.java, interactor().submitCredentials("user", password()))

        assertEquals(30_000, currentTime)
    }

    @Test
    fun `a key passphrase that can't be derived ends the login`() = runTest {
        srp.failOnSalt = "salt-2"

        failed(LoginFailure.KEY_UNSUPPORTED, interactor().submitCredentials("user", password()))
        advanceUntilIdle()

        assertTrue("A5 access-token-1" in calls)
        assertEquals(1, srp.passphrasesMade.size, "the first key's passphrase was made")
        assertTrue(srp.passphrasesMade.all { bytes -> bytes.all { it == 0.toByte() } }, "and zeroed")
    }

    @Test
    fun `a 429 on A3 is retried`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        api.a3Failures += ProtonError.RateLimited(3.seconds)
        val login = interactor()
        login.submitCredentials("user", password())

        assertInstanceOf(LoginStep.Success::class.java, login.submitTotp("123456"))

        assertEquals(2, calls.count { it == "A3 123456" })
    }

    @Test
    fun `cancel after A2 ends the session once and discards the tokens`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())

        login.cancel()
        login.cancel()
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "A5 access-token-1"), calls)
        assertNull(store!!.current())
        assertTrue(srp.passwordsSeen.all { copy -> copy.all { it == '\u0000' } }, "the kept copy is zeroed")
    }

    @Test
    fun `cancel while A2 is out stops the login`() = runTest {
        api.a2Gate = CompletableDeferred()
        val login = interactor()
        val submit = async { login.submitCredentials("user", password()) }
        runCurrent()

        login.cancel()
        advanceUntilIdle()

        assertTrue(submit.isCancelled)
        assertEquals(listOf("A1 user", "A2 user"), calls, "no session came back, so there's none to end")
    }

    @Test
    fun `a submit its caller cancels after A2 ends the session`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())
        api.a3Gate = CompletableDeferred()
        val submit = launch { login.submitTotp("123456") }
        runCurrent()

        submit.cancel()
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "A3 123456", "A5 access-token-1"), calls)
        assertNull(store!!.current())
    }

    @Test
    fun `steps out of order are a programming error`() = runTest {
        val login = interactor()

        assertInstanceOf(IllegalStateException::class.java, runCatching { login.submitTotp("123456") }.exceptionOrNull())
        assertInstanceOf(IllegalStateException::class.java, runCatching { login.submitMailboxPassword(password()) }.exceptionOrNull())
        assertInstanceOf(IllegalStateException::class.java, runCatching { login.captchaSolved(PAGE_TOKEN) }.exceptionOrNull())
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `cancel while the keys are checked hands over nothing`() = runTest {
        lateinit var login: ProtonLoginInteractor
        keys.onUnlock = { login.cancel() }
        login = interactor()
        val submit = async { login.submitCredentials("user", password()) }
        advanceUntilIdle()

        assertTrue(submit.isCancelled)
        assertEquals(1, calls.count { it.startsWith("A5") })
        assertTrue(keys.arraysSeen.isNotEmpty())
        assertTrue(keys.arraysSeen.all { bytes -> bytes.all { it == 0.toByte() } }, "the passphrases are zeroed")
        assertTrue(srp.passwordsSeen.all { copy -> copy.all { it == '\u0000' } })
        assertNull(store!!.current())
    }

    @Test
    fun `an unexpected error from the key check ends the session`() = runTest {
        keys.failure = IllegalStateException("library")

        assertInstanceOf(IllegalStateException::class.java, runCatching { interactor().submitCredentials("user", password()) }.exceptionOrNull())
        advanceUntilIdle()

        assertEquals(1, calls.count { it.startsWith("A5") })
        assertTrue(keys.arraysSeen.all { bytes -> bytes.all { it == 0.toByte() } })
        assertNull(store!!.current())
    }

    @Test
    fun `an error closing the checked keys ends the session`() = runTest {
        keys.closeFailure = IllegalStateException("library")
        val login = interactor()

        assertInstanceOf(IllegalStateException::class.java, runCatching { login.submitCredentials("user", password()) }.exceptionOrNull())
        advanceUntilIdle()

        assertEquals(1, calls.count { it.startsWith("A5") })
        assertTrue(keys.arraysSeen.all { bytes -> bytes.all { it == 0.toByte() } })
        assertInstanceOf(IllegalStateException::class.java, runCatching { login.submitMailboxPassword(password()) }.exceptionOrNull())
    }

    @Test
    fun `cancel during a step sends one A5`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())
        api.a3Gate = CompletableDeferred()
        api.logoutGate = CompletableDeferred()
        val submit = launch { login.submitTotp("123456") }
        runCurrent()

        login.cancel()
        runCurrent()
        api.logoutGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(submit.isCancelled)
        assertEquals(1, calls.count { it.startsWith("A5") })
    }

    @Test
    fun `cancel before A2 sends nothing`() = runTest {
        val login = interactor()

        login.cancel()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the session handed over owns its tokens, and the login keeps no copy`() = runTest {
        val session = (interactor().submitCredentials("user", password()) as LoginStep.Success).session

        assertNull(store!!.current(), "the in-memory store is cleared once the session is handed over")
        assertEquals("access-token-1", session.credentials.tokens.access)
    }

    @Test
    fun `secret-holding steps redact toString`() = runTest {
        val step = interactor().submitCredentials("user", password())

        val text = step.toString()
        listOf("access-token-1", "refresh-token-1", "session-uid-1", "user@example.test", "user-id-1", "passphrase").forEach {
            assertFalse(text.contains(it), it)
        }
    }

    // Captcha, 12087, and a 9001 off A2 (docs/protocol.md § Captcha (Code 9001)).

    private val captchaUrl = "https://verify.proton.me/?methods=captcha&token=hv-token-1"

    private fun captcha(token: String = "hv-token-1", url: String = captchaUrl, methods: List<String> = listOf("captcha")) =
        ProtonError.HumanVerification(HvDetails(token, url, methods))

    /** What the attempt keeps for the captcha retry, read by reference so a test can check it ends zeroed or gone. */
    private fun ProtonLoginInteractor.kept(field: String): Any? =
        ProtonLoginInteractor::class.java.getDeclaredField(field).apply { isAccessible = true }.get(this)

    private fun ProtonLoginInteractor.keptPassword() = kept("loginPassword") as CharArray?

    /** The page's token and the username, which the attempt doesn't keep once it's done with them. */
    private fun ProtonLoginInteractor.assertCaptchaForgotten() {
        val held = ProtonLoginInteractor::class.java.declaredFields.map { it.apply { isAccessible = true }.get(this) }
        assertFalse(PAGE_TOKEN in held, "the page's token is used once and not kept")
        assertNull(kept("username"))
    }

    private fun zeroed(chars: CharArray) = chars.all { it == '\u0000' }

    @Test
    fun `a 9001 on A2 asks for the captcha`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()

        val step = login.submitCredentials("user", password())
        advanceUntilIdle()

        assertEquals("$captchaUrl&embed=true", assertInstanceOf(LoginStep.NeedCaptcha::class.java, step).webUrl, "embedded")
        assertEquals(listOf("A1 user", "A2 user"), calls, "no session came back, so there's none to end")
    }

    @Test
    fun `captchaSolved restarts at A1 and sends the page's token on A2 only`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        val typed = password()
        login.submitCredentials("user", typed)
        assertTrue(zeroed(typed), "the typed password is zeroed")

        assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user", "U1", "U3"), calls)
        assertEquals(listOf(null, PAGE_TOKEN), api.a2Captcha, "the page's token, never the 9001's")
        assertEquals(2, srp.proofCalls.size, "a fresh SRP session for the retry")
        assertEquals("4 user login-password salt-a1 modulus-a1 ephemeral-a1", srp.proofCalls[1], "the login password was kept")
        assertEquals("passphrase(login-password,salt-1)", keys.passphrasesSeen["key-id-1"])
        assertTrue(srp.passwordsSeen.all(::zeroed), "every copy is zeroed")
        assertEquals(listOf("proofs" to true, "proofs" to true, "passphrase" to true, "passphrase" to true, "unlock" to true), cpuWork)
        login.assertCaptchaForgotten()
    }

    @Test
    fun `the password is kept through the captcha in two-password mode too`() = runTest {
        api.a2Failures += captcha()
        api.passwordMode = 2
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())

        assertEquals(LoginStep.NeedTotp(wrongCode = false), login.captchaSolved(PAGE_TOKEN))
        login.assertCaptchaForgotten()
        assertEquals(LoginStep.NeedMailboxPassword(wrongPassword = false), login.submitTotp("123456"))
        assertInstanceOf(LoginStep.Success::class.java, login.submitMailboxPassword(password("mailbox-password")))

        assertEquals("4 user login-password salt-a1 modulus-a1 ephemeral-a1", srp.proofCalls[1])
        assertEquals("passphrase(mailbox-password,salt-1)", keys.passphrasesSeen["key-id-1"])
        assertTrue(srp.passwordsSeen.all(::zeroed))
        assertNull(login.keptPassword(), "two-password mode keeps nothing once A2 succeeds")
    }

    @Test
    fun `methods without captcha are verified in the web app`() = runTest {
        listOf(listOf("email"), listOf("sms", "email"), emptyList()).forEach { methods ->
            calls.clear()
            api.a2Failures += captcha(methods = methods)
            val login = interactor()

            failed(LoginFailure.VERIFY_IN_WEB_APP, login.submitCredentials("user", password()))

            assertEquals(listOf("A1 user", "A2 user"), calls, "$methods")
            assertNull(login.keptPassword(), "$methods")
        }
    }

    @Test
    fun `methods that include captcha ask for it`() = runTest {
        api.a2Failures += captcha(methods = listOf("email", "captcha"))

        assertInstanceOf(LoginStep.NeedCaptcha::class.java, interactor().submitCredentials("user", password()))
    }

    @Test
    fun `a WebUrl that isn't https on an approved host is refused`() = runTest {
        val refused = listOf(
            "http://verify.proton.me/",
            "HTTPS://verify.proton.me/",
            "https://VERIFY.PROTON.ME/",
            "https://Verify.proton.me/",
            "https://verify.proton.me/\u007f",
            "https://verify.proton.me.example.test/",
            "https://verify.protonmail.com.example.test/",
            "https://evilproton.me/",
            "https://notprotonmail.com/",
            "https://proton.me./",
            "https://example.test/verify.proton.me",
            "https://proton.me@example.test/",
            "https://user@verify.proton.me/",
            "https://verify.proton.me:443/",
            "https://verify.proton.me:8443/",
            "https://verify.proton.me:/",
            "https://example.test\\@verify.proton.me/",
            "https://verify.proton.me\\.example.test/",
            "https://verify.proton.me /",
            "https://verify.proton.me\t/",
            "https://verify.proton.me/\u00e9",
            "https://proton.me%2eexample.test/",
            "https:verify.proton.me",
            "https:///verify.proton.me",
            "https://",
            "javascript:alert(1)//verify.proton.me",
            "//verify.proton.me/",
            "",
        )
        refused.forEach { url ->
            calls.clear()
            api.a2Failures += captcha(url = url)
            val login = interactor()

            failed(LoginFailure.CAPTCHA_URL_REFUSED, login.submitCredentials("user", password()))

            assertEquals(listOf("A1 user", "A2 user"), calls, url)
            assertNull(login.keptPassword(), url)
        }
    }

    @Test
    fun `a WebUrl that is https on proton me or protonmail com, or a subdomain, is accepted`() = runTest {
        val accepted = listOf(
            captchaUrl,
            "https://proton.me/",
            "https://proton.me",
            "https://protonmail.com/verify?token=x#y",
            "https://a.b.protonmail.com/",
            "https://verify.proton.me?methods=captcha",
            "https://verify.proton.me/Path/?methods=captcha&token=AbC-123_xYz",
        )
        accepted.forEach { url ->
            api.a2Failures += captcha(url = url)

            val step = interactor().submitCredentials("user", password())

            // Embedded, and otherwise exactly as sent: upper case in the path or query, and a fragment, are kept.
            assertEquals(embeddedCaptchaUrl(url), assertInstanceOf(LoginStep.NeedCaptcha::class.java, step, url).webUrl, url)
        }
    }

    @Test
    fun `a 12087 on the captcha retry fetches a fresh captcha`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += ProtonError.Api(422, 12087)
        api.a2Failures += captcha(token = "hv-token-2", url = "https://verify.proton.me/?token=hv-token-2")

        val again = login.captchaSolved(PAGE_TOKEN)

        assertEquals("https://verify.proton.me/?token=hv-token-2&embed=true", assertInstanceOf(LoginStep.NeedCaptcha::class.java, again).webUrl)
        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user", "A1 user", "A2 user"), calls)
        assertEquals(listOf(null, PAGE_TOKEN, null), api.a2Captcha, "the used token isn't sent again")

        assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(PAGE_TOKEN_2))
        assertEquals(listOf(null, PAGE_TOKEN, null, PAGE_TOKEN_2), api.a2Captcha)
        assertTrue(srp.passwordsSeen.all(::zeroed))
    }

    @Test
    fun `a 12087 whose fresh A2 succeeds goes on with the login`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += ProtonError.Api(422, 12087)

        assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user", "A1 user", "A2 user", "U1", "U3"), calls)
        assertEquals(listOf(null, PAGE_TOKEN, null), api.a2Captcha)
    }

    @Test
    fun `a 12087 without a captcha token is a refusal`() = runTest {
        api.a2Failures += ProtonError.Api(422, 12087)

        failed(LoginFailure.LOGIN_REFUSED, interactor().submitCredentials("user", password()))

        assertEquals(listOf("A1 user", "A2 user"), calls)
    }

    @Test
    fun `only A2's 12087 fetches a fresh captcha`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a1Failures += ProtonError.Api(422, 12087)

        failed(LoginFailure.LOGIN_REFUSED, login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf("A1 user", "A2 user", "A1 user"), calls)
    }

    @Test
    fun `a 9001 on the captcha retry asks again, and the next answer is sent`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += captcha(token = "hv-token-2", url = "https://verify.proton.me/?token=hv-token-2")

        assertInstanceOf(LoginStep.NeedCaptcha::class.java, login.captchaSolved(PAGE_TOKEN))
        assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(PAGE_TOKEN_2))

        assertEquals(listOf(null, PAGE_TOKEN, PAGE_TOKEN_2), api.a2Captcha)
    }

    @Test
    fun `a refused WebUrl on the captcha retry ends the login`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += captcha(token = "hv-token-2", url = "https://example.test/")

        failed(LoginFailure.CAPTCHA_URL_REFUSED, login.captchaSolved(PAGE_TOKEN))

        assertTrue(srp.passwordsSeen.all(::zeroed))
        assertNull(login.keptPassword())
    }

    @Test
    fun `a 429 on the captcha retry restarts at A1 with the same answer`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += ProtonError.RateLimited(1.seconds)

        assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf(null, PAGE_TOKEN, PAGE_TOKEN), api.a2Captcha)
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `at most 3 tries on the captcha retry, then the kept password is zeroed`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        val kept = checkNotNull(login.keptPassword())
        repeat(3) { api.a2Failures += ProtonError.RateLimited(1.seconds) }

        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, 1.seconds), login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf(null, PAGE_TOKEN, PAGE_TOKEN, PAGE_TOKEN), api.a2Captcha)
        assertTrue(zeroed(kept))
        assertTrue(srp.passwordsSeen.all(::zeroed))
    }

    @Test
    fun `the A2 after a 12087 gets its own 3 tries`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Failures += ProtonError.Api(422, 12087)
        repeat(3) { api.a2Failures += ProtonError.RateLimited(1.seconds) }

        assertEquals(LoginStep.Failed(LoginFailure.RATE_LIMITED, 1.seconds), login.captchaSolved(PAGE_TOKEN))

        assertEquals(listOf(null, PAGE_TOKEN, null, null, null), api.a2Captcha)
        assertTrue(srp.passwordsSeen.all(::zeroed))
    }

    @Test
    fun `a captchaSolved its caller cancels zeroes the kept password and sends nothing`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Gate = CompletableDeferred()
        val solved = launch { login.captchaSolved(PAGE_TOKEN) }
        runCurrent()

        solved.cancel()
        advanceUntilIdle()

        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user"), calls)
        assertTrue(srp.passwordsSeen.all(::zeroed))
        assertNull(login.keptPassword())
        login.assertCaptchaForgotten()
    }

    @Test
    fun `cancel at TOTP after a captcha ends the session and zeroes the kept password`() = runTest {
        api.a2Failures += captcha()
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        val login = interactor()
        login.submitCredentials("user", password())
        assertEquals(LoginStep.NeedTotp(wrongCode = false), login.captchaSolved(PAGE_TOKEN))
        val kept = checkNotNull(login.keptPassword()) { "one-password mode keeps it for the keys" }

        login.cancel()
        advanceUntilIdle()

        assertTrue(zeroed(kept))
        assertEquals(listOf("A5 access-token-1"), calls.filter { it.startsWith("A5") })
    }

    @Test
    fun `a refusal on the captcha retry ends the login and zeroes the kept password`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        val kept = checkNotNull(login.keptPassword())
        api.a2Failures += ProtonError.Api(422, 8002)

        failed(LoginFailure.LOGIN_REFUSED, login.captchaSolved(PAGE_TOKEN))
        advanceUntilIdle()

        assertTrue(zeroed(kept))
        assertTrue(srp.passwordsSeen.all(::zeroed))
        login.assertCaptchaForgotten()
        assertEquals(4, calls.size, "no session, so no A5")
        assertInstanceOf(IllegalStateException::class.java, runCatching { login.captchaSolved(PAGE_TOKEN) }.exceptionOrNull())
    }

    @Test
    fun `cancel while the captcha is shown sends nothing and zeroes the kept password`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        val kept = checkNotNull(login.keptPassword())
        assertEquals("login-password", String(kept))

        login.cancel()
        advanceUntilIdle()

        assertTrue(zeroed(kept))
        assertNull(login.keptPassword())
        login.assertCaptchaForgotten()
        assertEquals(listOf("A1 user", "A2 user"), calls)
        assertInstanceOf(IllegalStateException::class.java, runCatching { login.captchaSolved(PAGE_TOKEN) }.exceptionOrNull())
    }

    @Test
    fun `cancel while the captcha retry's A2 is out stops the login`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        api.a2Gate = CompletableDeferred()
        val solved = async { login.captchaSolved(PAGE_TOKEN) }
        runCurrent()

        login.cancel()
        advanceUntilIdle()

        assertTrue(solved.isCancelled)
        assertEquals(listOf("A1 user", "A2 user", "A1 user", "A2 user"), calls)
        assertTrue(srp.passwordsSeen.all(::zeroed))
        assertNull(login.keptPassword())
    }

    @Test
    fun `cancel while the captcha retry's proofs run leaves their password alone`() = runTest {
        api.a2Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())
        srp.onProofs = { login.cancel() }
        val solved = async { login.captchaSolved(PAGE_TOKEN) }

        advanceUntilIdle()

        assertTrue(solved.isCancelled)
        assertEquals("4 user login-password salt-a1 modulus-a1 ephemeral-a1", srp.proofCalls[1], "zeroed only once the proofs are done")
        assertTrue(srp.passwordsSeen.all(::zeroed))
        assertEquals(listOf("A1 user", "A2 user", "A1 user"), calls)
    }

    @Test
    fun `a 9001 after A2 is verified in the web app and ends the session`() = runTest {
        api.twoFactor = TwoFactorDto(enabled = 1, totp = 1)
        api.a3Failures += captcha()
        val login = interactor()
        login.submitCredentials("user", password())

        failed(LoginFailure.VERIFY_IN_WEB_APP, login.submitTotp("123456"))
        advanceUntilIdle()

        assertTrue("A5 access-token-1" in calls)
    }

    @Test
    fun `the captcha page is loaded embedded`() {
        val cases = mapOf(
            "https://verify.proton.me/?methods=captcha&token=x" to "https://verify.proton.me/?methods=captcha&token=x&embed=true",
            "https://proton.me" to "https://proton.me?embed=true",
            "https://proton.me/" to "https://proton.me/?embed=true",
            "https://protonmail.com/verify?token=x#y" to "https://protonmail.com/verify?token=x&embed=true#y",
            "https://verify.proton.me/?embed=false&token=x" to "https://verify.proton.me/?token=x&embed=true",
            "https://verify.proton.me/?embed&token=x&embed=1" to "https://verify.proton.me/?token=x&embed=true",
            "https://verify.proton.me/?token=x&" to "https://verify.proton.me/?token=x&embed=true",
            "https://verify.proton.me/?Embed=x" to "https://verify.proton.me/?Embed=x&embed=true",
        )
        cases.forEach { (url, embedded) -> assertEquals(embedded, embeddedCaptchaUrl(url), url) }
    }

    @Test
    fun `a captcha answer that isn't 1 to 4096 printable ASCII characters is refused, sends nothing, and zeroes the password`() = runTest {
        val refused = listOf("", "a".repeat(4097), "a b", "a\u0000b", "a\u007Fb", "a\r\nX-Other: 1", "a\tb", "caf\u00e9", "a\u2028b")
        refused.forEach { token ->
            calls.clear()
            api.a2Failures += captcha()
            val login = interactor()
            login.submitCredentials("user", password())
            val kept = checkNotNull(login.keptPassword())
            calls.clear()

            failed(LoginFailure.CAPTCHA_TOKEN_REFUSED, login.captchaSolved(token))

            assertEquals(emptyList<String>(), calls, token)
            assertTrue(zeroed(kept), token)
            assertNull(login.keptPassword(), token)
            assertNull(login.kept("username"), token)
        }
    }

    @Test
    fun `a captcha answer of printable ASCII up to 4096 characters is sent`() = runTest {
        listOf("!", "~", "a".repeat(4096), (33..126).map { it.toChar() }.joinToString("")).forEach { token ->
            api.a2Captcha.clear()
            api.a2Failures += captcha()
            val login = interactor()
            login.submitCredentials("user", password())

            assertInstanceOf(LoginStep.Success::class.java, login.captchaSolved(token), token)

            assertEquals(listOf(null, token), api.a2Captcha, token)
        }
    }

    @Test
    fun `NeedCaptcha redacts toString`() = runTest {
        api.a2Failures += captcha()

        val step = assertInstanceOf(LoginStep.NeedCaptcha::class.java, interactor().submitCredentials("user", password()))

        assertFalse(step.toString().contains("hv-token-1"))
        assertFalse(step.toString().contains("verify.proton.me"))
    }

    private inner class FakeApi : ProtonApi {
        var a1Failures = ArrayDeque<ProtonError>()
        var a2Failures = ArrayDeque<ProtonError>()
        var a3Failures = ArrayDeque<ProtonError>()
        var userFailure: ProtonError? = null
        var logoutFailure: ProtonError? = null
        var serverProof = "server-proof"
        var twoFactor = TwoFactorDto(enabled = 0, totp = 0)
        var passwordMode = 1
        var userId = "user-id-1"
        var salts = listOf(KeySaltDto("key-id-1", "salt-1"), KeySaltDto("key-id-2", "salt-2"), KeySaltDto("address-key-id-1", null))
        val a2Requests = mutableListOf<String>()

        /** The captcha token each A2 carried, null for none. */
        val a2Captcha = mutableListOf<String?>()
        var a2Gate: CompletableDeferred<Unit>? = null
        var a3Gate: CompletableDeferred<Unit>? = null
        var logoutGate: CompletableDeferred<Unit>? = null

        override suspend fun authInfo(username: String): AuthInfoDto {
            calls += "A1 $username"
            a1Failures.removeFirstOrNull()?.let { throw it }
            return AuthInfoDto(1000, "modulus-a1", "ephemeral-a1", 4, "salt-a1", "srp-session-a1")
        }

        override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?): AuthDto {
            calls += "A2 ${request.username}"
            a2Requests += "${request.username} ${request.clientEphemeral} ${request.clientProof} ${request.srpSession}"
            a2Captcha += captcha?.token
            a2Gate?.await()
            a2Failures.removeFirstOrNull()?.let { throw it }
            return AuthDto(
                code = 1000, accessToken = "access-token-1", refreshToken = "refresh-token-1", tokenType = "Bearer",
                uidUpper = "session-uid-1", userId = "user-id-1", serverProof = serverProof, passwordMode = passwordMode,
                expiresIn = 3600, twoFactor = twoFactor,
            ).also { check(store!!.current() == null) { "the client stores tokens, not the API" } }
        }

        override suspend fun submitTotp(code: String) {
            calls += "A3 $code"
            check(store!!.current()?.access == "access-token-1")
            a3Gate?.await()
            a3Failures.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun logout() {
            calls += "A5 ${store!!.current()?.access}"
            logoutGate?.await()
            logoutFailure?.let { throw it }
        }

        override suspend fun user(): UserDto {
            calls += "U1"
            userFailure?.let { throw it }
            return UserDto(
                id = userId, name = "user", email = "user@example.test", displayName = "User",
                keys = listOf(
                    UserKeyDto("key-id-1", "armored-key-1", active = 1, primary = 1, fingerprint = "fp-1", version = 3),
                    UserKeyDto("key-id-2", "armored-key-2", active = 0, primary = 0, fingerprint = "fp-2", version = 3),
                ),
            )
        }

        override suspend fun keySalts(): List<KeySaltDto> {
            calls += "U3"
            return salts
        }

        override suspend fun exportContacts(page: Int, pageSize: Int): ContactExportPageDto = error("not a login call")
        override suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto = error("not a login call")
        override suspend fun contact(id: ProtonContactId): ContactDto = error("not a login call")
        override suspend fun createContact(cards: List<CardDto>): CreateReply = error("not a login call")
        override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>): Unit = error("not a login call")
        override suspend fun deleteContact(id: ProtonContactId): Unit = error("not a login call")
        override suspend fun latestEventId(): EventId = error("not a login call")
        override suspend fun events(cursor: EventId): EventsReply = error("not a login call")
    }

    private class FakeSrp : SrpClient {
        var proofsFailure: SrpException? = null
        var onProofs: () -> Unit = {}
        var failOnSalt: String? = null
        val passphrasesMade = mutableListOf<ByteArray>()
        val proofCalls = mutableListOf<String>()

        /** The arrays the interactor handed over, kept by reference to check they end zeroed. */
        val passwordsSeen = mutableListOf<CharArray>()

        override fun proofs(version: Int, username: String, password: CharArray, salt: String, signedModulus: String, serverEphemeral: String): SrpProofs {
            cpuWork += "proofs" to onCpu.get()
            passwordsSeen += password
            onProofs()
            proofCalls += "$version $username ${String(password)} $salt $signedModulus $serverEphemeral"
            proofsFailure?.let { throw it }
            return SrpProofs("client-ephemeral", "client-proof", "server-proof")
        }

        override fun keyPassphrase(password: CharArray, keySalt: String): ByteArray {
            cpuWork += "passphrase" to onCpu.get()
            passwordsSeen += password
            if (keySalt == failOnSalt) throw SrpException()
            return "passphrase(${String(password)},$keySalt)".toByteArray().also { passphrasesMade += it }
        }
    }

    private class FakeKeys : KeyHierarchy {
        val results = ArrayDeque<CryptoResult<UnlockedKeys>>()
        var passphrasesSeen = emptyMap<String, String>()
        val arraysSeen = mutableListOf<ByteArray>()
        var closed = false
        var onUnlock: () -> Unit = {}
        var failure: Exception? = null
        var closeFailure: Exception? = null

        override fun unlock(bundle: LockedKeyBundle, passphrasesByKeyId: Map<String, ByteArray>): CryptoResult<UnlockedKeys> {
            cpuWork += "unlock" to onCpu.get()
            passphrasesSeen = passphrasesByKeyId.mapValues { String(it.value) }
            arraysSeen += passphrasesByKeyId.values
            onUnlock()
            failure?.let { throw it }
            return results.removeFirstOrNull() ?: CryptoResult.Success(object : UnlockedKeys {
                override fun close() {
                    closed = true
                    closeFailure?.let { throw it }
                }
            })
        }
    }
}

/** What Proton's embedded captcha page hands back when solved (made up). */
private const val PAGE_TOKEN = "page-answer-1"
private const val PAGE_TOKEN_2 = "page-answer-2"
