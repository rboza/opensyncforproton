package dev.rboza.android.opensyncforproton.proton

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The DTOs, on Proton's redacted responses (fixtures/http). */
class ProtonDtoTest {
    private fun body(fixture: String): JsonObject {
        val text = checkNotNull(javaClass.getResource("/$fixture.json")) { "fixtures/http/$fixture.json is missing" }.readText()
        return Json.parseToJsonElement(text).jsonObject.getValue("body").jsonObject
    }

    private fun <T> decode(strategy: DeserializationStrategy<T>, json: JsonElement): T = protonJson.decodeFromJsonElement(strategy, json)

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    /**
     * The fixtures' redaction gives same-length fields the same GENERATED value (AccessToken, RefreshToken, and UID are
     * identical), which would hide a swapped @SerialName. Each field gets a value of its own; UID and Uid share one.
     */
    private fun JsonObject.with(vararg fields: Pair<String, String>) =
        JsonObject(this + fields.associate { (name, value) -> name to kotlinx.serialization.json.JsonPrimitive(value) })

    private fun JsonObject.withObject(name: String, transform: (JsonObject) -> JsonObject) =
        JsonObject(this + (name to transform(getValue(name).jsonObject)))

    private fun JsonObject.withFirst(name: String, transform: (JsonObject) -> JsonObject) =
        JsonObject(
            this + (name to kotlinx.serialization.json.JsonArray(getValue(name).jsonArray.mapIndexed { i, e -> if (i == 0) transform(e.jsonObject) else e })),
        )

    @Test
    fun `auth info parses`() {
        val json = body("auth-info").with(
            "Modulus" to "v-modulus", "ServerEphemeral" to "v-ephemeral", "Salt" to "v-salt", "SRPSession" to "v-session",
        )
        val dto = decode(AuthInfoDto.serializer(), json)

        assertEquals(1000, dto.code)
        assertEquals("v-modulus", dto.modulus)
        assertEquals("v-ephemeral", dto.serverEphemeral)
        assertEquals(4, dto.version)
        assertEquals("v-salt", dto.salt)
        assertEquals("v-session", dto.srpSession)
    }

    @ParameterizedTest
    @CsvSource("auth-one-password, 1", "auth-two-password, 2")
    fun `auth parses`(fixture: String, passwordMode: Int) {
        val json = body(fixture).with(
            "AccessToken" to "v-access", "RefreshToken" to "v-refresh", "TokenType" to "v-type", "UID" to "v-uid",
            "Uid" to "v-uid", "UserID" to "v-user", "ServerProof" to "v-proof", "EventID" to "v-event",
        )
        val dto = decode(AuthDto.serializer(), json)

        assertEquals(1000, dto.code)
        assertEquals("v-access", dto.accessToken)
        assertEquals("v-refresh", dto.refreshToken)
        assertEquals("v-type", dto.tokenType)
        assertEquals("v-uid", dto.uid)
        assertEquals("v-user", dto.userId)
        assertEquals("v-proof", dto.serverProof)
        assertEquals(passwordMode, dto.passwordMode)
        assertEquals("v-event", dto.eventId)
        assertEquals(1800, dto.expiresIn)
        assertEquals(TwoFactorDto(enabled = 1, totp = 1), dto.twoFactor)
    }

    // C1–C3: export entries carry only ID and Cards; the list carries IDs.
    @Test
    fun `contact export, list, and detail parse`() {
        val ids = listOf("v-id-1", "v-id-2", "v-id-3")
        val export = decode(ContactExportPageDto.serializer(), body("contacts-export").withIds(ids))
        val list = decode(ContactListPageDto.serializer(), body("contacts-list").withIds(ids))
        val detail = decode(ContactResponseDto.serializer(), body("contact").withObject("Contact") { it.with("ID" to "v-id-9") }).contact

        assertEquals(ids, export.contacts.map { it.id })
        assertEquals(3, export.total)
        assertEquals(listOf(3, 2), export.contacts.first().cards.map { it.type })
        // The list page was captured two to a page, so its Total counts one more.
        assertEquals(ids.take(2), list.contacts.map { it.id })
        assertEquals(3, list.total)
        assertEquals("v-id-9", detail.id)
        assertEquals(listOf(3, 2), detail.cards.map { it.type })
    }

    @Test
    fun `a contact ID shows none of its value`() {
        assertEquals("ProtonContactId(redacted)", ProtonContactId("SECRET-id").toString())
    }

    @Test
    fun `a page without Total parses, with no Total`() {
        assertNull(decode(ContactExportPageDto.serializer(), JsonObject(body("contacts-export") - "Total")).total)
        assertNull(decode(ContactListPageDto.serializer(), JsonObject(body("contacts-list") - "Total")).total)
    }

    /** Each entry of `Contacts` gets its own ID: the fixtures' redaction gives them all the same one. */
    private fun JsonObject.withIds(ids: List<String>) = JsonObject(
        this + ("Contacts" to kotlinx.serialization.json.JsonArray(
            getValue("Contacts").jsonArray.mapIndexed { i, entry -> entry.jsonObject.with("ID" to ids[i]) },
        )),
    )

    @Test
    fun `a missing PasswordMode means one password`() {
        val json = JsonObject(body("auth-one-password") - "PasswordMode")

        assertEquals(1, decode(AuthDto.serializer(), json).passwordMode)
    }

    @Test
    fun `second-factor code and logout parse`() {
        assertEquals(1000, decode(CodeDto.serializer(), body("auth-2fa")).code)
        assertEquals(1000, decode(CodeDto.serializer(), body("logout")).code)
    }

    @ParameterizedTest
    @CsvSource("refresh", "refresh-reused-token")
    fun `refresh parses`(fixture: String) {
        val json = body(fixture).with("AccessToken" to "v-access", "RefreshToken" to "v-refresh", "UID" to "v-uid", "Uid" to "v-uid")
        val dto = decode(RefreshDto.serializer(), json)

        assertEquals(1000, dto.code)
        assertEquals("v-access", dto.accessToken)
        assertEquals("v-refresh", dto.refreshToken)
        assertEquals("v-uid", dto.uid)
    }

    @Test
    fun `user and its keys parse`() {
        val json = body("users").withObject("User") { user ->
            user.with("ID" to "v-id", "Name" to "v-name", "Email" to "v-email", "DisplayName" to "v-display")
                .withFirst("Keys") { it.with("ID" to "v-key", "PrivateKey" to "v-private", "Fingerprint" to "v-fingerprint") }
        }
        val user = decode(UserResponseDto.serializer(), json).user

        assertEquals("v-id", user.id)
        assertEquals("v-name", user.name)
        assertEquals("v-email", user.email)
        assertEquals("v-display", user.displayName)
        val key = user.keys.single()
        assertEquals("v-key", key.id)
        assertEquals("v-private", key.privateKey)
        assertEquals(1, key.active)
        assertEquals(1, key.primary)
        assertEquals("v-fingerprint", key.fingerprint)
        assertEquals(3, key.version)
    }

    @Test
    fun `key salts parse, and a salt can be null`() {
        val json = body("key-salts").withFirst("KeySalts") { it.with("ID" to "v-id", "KeySalt" to "v-salt") }
        val salts = decode(KeySaltsResponseDto.serializer(), json).keySalts

        assertEquals("v-id", salts[0].id)
        assertEquals("v-salt", salts[0].keySalt)
        assertNull(salts[1].keySalt)
    }

    // UID and Uid are both accepted, and must agree.
    @Test
    fun `either UID spelling is accepted`() {
        val json = body("auth-one-password")
        val uid = json.string("UID")

        assertEquals(uid, decode(AuthDto.serializer(), JsonObject(json - "Uid")).uid)
        assertEquals(uid, decode(AuthDto.serializer(), JsonObject(json - "UID")).uid)
        assertEquals(uid, decode(RefreshDto.serializer(), JsonObject(body("refresh") - "UID")).uid)
    }

    @Test
    fun `a field named uid can't set the session UID`() {
        val json = body("auth-one-password")
        val withField = JsonObject(json + ("uid" to kotlinx.serialization.json.JsonPrimitive("from-the-response")))

        assertEquals(json.string("UID"), decode(AuthDto.serializer(), withField).uid)
    }

    @Test
    fun `different or missing UIDs fail`() {
        val json = body("auth-one-password")

        assertThrows<IllegalArgumentException> {
            decode(AuthDto.serializer(), JsonObject(json + ("Uid" to kotlinx.serialization.json.JsonPrimitive("another"))))
        }
        assertThrows<IllegalArgumentException> { decode(AuthDto.serializer(), JsonObject(json - "UID" - "Uid")) }
        assertThrows<IllegalArgumentException> { decode(AuthDto.serializer(), json.with("UID" to " ", "Uid" to " ")) }
    }

    @ParameterizedTest
    @CsvSource(
        "auth-info, , Code Modulus ServerEphemeral Version Salt SRPSession",
        "auth-one-password, , Code AccessToken RefreshToken TokenType UserID ServerProof ExpiresIn 2FA",
        "refresh, , Code AccessToken RefreshToken",
        "auth-2fa, , Code",
        "users, User, ID Name Email DisplayName Keys",
        "users, User.Keys, ID PrivateKey Active Primary Fingerprint Version",
        "key-salts, KeySalts, ID KeySalt",
        "contacts-export, , Contacts",
        "contacts-export, Contacts, ID Cards",
        "contacts-export, Contacts.Cards, Type Data",
        "contacts-list, , Contacts",
        "contacts-list, Contacts, ID",
        "contact, , Contact",
        "contact, Contact, ID Cards",
    )
    fun `a missing required field fails`(fixture: String, at: String?, fields: String) {
        val strategy: DeserializationStrategy<*> = when (fixture) {
            "auth-info" -> AuthInfoDto.serializer()
            "auth-one-password" -> AuthDto.serializer()
            "refresh" -> RefreshDto.serializer()
            "auth-2fa" -> CodeDto.serializer()
            "users" -> UserResponseDto.serializer()
            "contacts-export" -> ContactExportPageDto.serializer()
            "contacts-list" -> ContactListPageDto.serializer()
            "contact" -> ContactResponseDto.serializer()
            else -> KeySaltsResponseDto.serializer()
        }
        for (field in fields.split(" ")) {
            val json = removeAt(body(fixture), at?.split(".").orEmpty(), field)
            assertThrows<IllegalArgumentException>("$fixture $at $field") { decode(strategy, json) }
        }
    }

    /**
     * [json] without [field] in the first object found by following [path] (object keys, or the first array
     * element).
     */
    private fun removeAt(json: JsonObject, path: List<String>, field: String): JsonObject {
        if (path.isEmpty()) return JsonObject(json - field)
        val child = json.getValue(path.first())
        val updated = if (child is kotlinx.serialization.json.JsonArray) {
            kotlinx.serialization.json.JsonArray(listOf(removeAt(child.first().jsonObject, path.drop(1), field)) + child.drop(1))
        } else {
            removeAt(child.jsonObject, path.drop(1), field)
        }
        return JsonObject(json + (path.first() to updated))
    }

    // An unknown 2FA shape fails closed.
    @Test
    fun `an unknown 2FA shape fails`() {
        val json = body("auth-one-password")
        for (twoFactor in listOf("""{"TOTP": 1}""", """{"Enabled": 1}""", """{"Enabled": "yes", "TOTP": 1}""", "null")) {
            assertThrows<IllegalArgumentException>(twoFactor) {
                decode(AuthDto.serializer(), JsonObject(json + ("2FA" to Json.parseToJsonElement(twoFactor))))
            }
        }
    }

    // Enabled is go-proton-api's TwoFAStatus (882710f): 1 TOTP, 2 FIDO2, 3 both.
    @ParameterizedTest
    @CsvSource(
        "1, 1, TOTP",
        "3, 1, TOTP",
        "0, 0, NONE",
        "0, 1, NONE",
        "2, 0, FIDO2_ONLY",
        // Contradictory or unknown: fail closed.
        "1, 0, UNSUPPORTED",
        "3, 0, UNSUPPORTED",
        "2, 1, UNSUPPORTED",
        "4, 1, UNSUPPORTED",
        "5, 1, UNSUPPORTED",
        "1, 2, UNSUPPORTED",
        "-1, 1, UNSUPPORTED",
    )
    fun `second factor`(enabled: Int, totp: Int, expected: SecondFactor) {
        assertEquals(expected, TwoFactorDto(enabled, totp).secondFactor())
    }

    @Test
    fun `the captured TOTP account needs a TOTP code`() {
        assertEquals(SecondFactor.TOTP, decode(AuthDto.serializer(), body("auth-one-password")).twoFactor.secondFactor())
    }

    // Tokens, SRP values, keys, contact IDs, and cards never show in a toString.
    @Test
    fun `no DTO toString shows a value`() {
        val dtos = listOf(
            decode(AuthInfoDto.serializer(), body("auth-info")),
            decode(AuthDto.serializer(), body("auth-one-password")),
            decode(RefreshDto.serializer(), body("refresh")),
            decode(UserResponseDto.serializer(), body("users")),
            decode(UserResponseDto.serializer(), body("users")).user,
            decode(UserResponseDto.serializer(), body("users")).user.keys.single(),
            decode(KeySaltsResponseDto.serializer(), body("key-salts")),
            decode(KeySaltsResponseDto.serializer(), body("key-salts")).keySalts.first(),
            decode(ContactExportPageDto.serializer(), body("contacts-export")),
            decode(ContactExportPageDto.serializer(), body("contacts-export")).contacts.first(),
            decode(ContactListPageDto.serializer(), body("contacts-list")),
            decode(ContactListPageDto.serializer(), body("contacts-list")).contacts.first(),
            decode(ContactResponseDto.serializer(), body("contact")),
        )
        for (dto in dtos) {
            assertFalse("GENERATED" in dto.toString() || "BEGIN PGP" in dto.toString(), dto.toString())
            assertEquals("${dto::class.simpleName}(redacted)", dto.toString())
        }
    }
}
