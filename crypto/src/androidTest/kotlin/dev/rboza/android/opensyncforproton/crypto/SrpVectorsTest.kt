package dev.rboza.android.opensyncforproton.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proton.gopenpgp.srp.Srp
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real library against `fixtures/crypto/srp.json` (tools/fixture-gen, go-srp v0.0.7).
 * The client's random source can't be replaced, so its proofs are checked by a go-srp server rebuilt from the
 * vector's server secret rather than compared byte for byte.
 */
@RunWith(AndroidJUnit4::class)
class SrpVectorsTest {
    private val vector = InstrumentationRegistry.getInstrumentation().context.assets.open("srp.json")
        .bufferedReader().use { JSONObject(it.readText()) }
    private val client = GoSrpClient()

    private fun string(name: String) = vector.getString(name)
    private fun bytes(name: String) = Base64.getDecoder().decode(string(name))
    private fun password() = string("password").toCharArray()

    private fun proofs(password: CharArray = password(), signedModulus: String = string("signedModulus")) = client.proofs(
        vector.getInt("version"), string("username"), password, string("salt"), signedModulus, string("serverEphemeral"),
    )

    /** go-srp's server holding the vector's verifier and secret, so its challenge is the vector's ephemeral. */
    private fun server() = Srp.newServerWithSecret(bytes("modulus"), bytes("verifier"), bytes("serverSecret"), 2048).also {
        assertArrayEquals(bytes("serverEphemeral"), it.generateChallenge())
    }

    @Test
    fun srp_matches_go_srp_vectors() {
        val auth = Srp.newAuth(
            vector.getInt("version").toLong(), string("username"), string("password").encodeToByteArray(),
            string("salt"), string("signedModulus"), string("serverEphemeral"),
        )
        assertArrayEquals("hashed password", bytes("hashedPassword"), auth.hashedPassword)

        val proofs = proofs()
        val serverProof = server().verifyProofs(
            Base64.getDecoder().decode(proofs.clientEphemeral), Base64.getDecoder().decode(proofs.clientProof),
        )

        assertEquals(proofs.expectedServerProof, Base64.getEncoder().encodeToString(serverProof))
    }

    @Test
    fun srp_proofs_from_another_password_fail_the_server() {
        val proofs = proofs(password = "test-only wrong password".toCharArray())
        val server = server()

        assertThrows(Exception::class.java) {
            server.verifyProofs(Base64.getDecoder().decode(proofs.clientEphemeral), Base64.getDecoder().decode(proofs.clientProof))
        }
    }

    @Test
    fun srp_modulus_tamper_rejected() {
        assertThrows(SrpException::class.java) { proofs(signedModulus = string("tamperedSignedModulus")) }
        // The tampered modulus would also fail go-srp's parameter checks in generateProofs; newAuth runs only
        // the signature check, so its failure shows the signature is what rejects it, before any proof.
        assertThrows(Exception::class.java) {
            Srp.newAuth(
                vector.getInt("version").toLong(), string("username"), string("password").encodeToByteArray(),
                string("salt"), string("tamperedSignedModulus"), string("serverEphemeral"),
            )
        }
    }

    @Test
    fun key_passphrase_matches_go_srp() {
        val vector = vector.getJSONObject("keyPassphrase")
        val password = vector.getString("password").toCharArray()

        val passphrase = client.keyPassphrase(password, vector.getString("keySalt"))

        assertEquals(vector.getString("passphrase"), passphrase.decodeToString())
        assertTrue("the caller's array is left alone", password.contentEquals(vector.getString("password").toCharArray()))
    }
}
