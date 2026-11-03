package dev.rboza.android.opensyncforproton.proton

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** DTOs ignore fields they don't read and fail on missing required ones. */
internal val protonJson = Json { ignoreUnknownKeys = true }

// Every DTO that carries a token, an SRP value, a key, or an address shows none of it in toString.
private const val REDACTED = "(redacted)"

/**
 * A2 and refresh send the session UID as both `UID` and `Uid`, with equal values. Either is accepted; both missing, a
 * blank one, or two different values fail. The message names no value.
 */
private fun sessionUid(upper: String?, lower: String?): String {
    require(upper == null || lower == null || upper == lower) { "UID and Uid differ" }
    return requireNotNull(upper ?: lower) { "UID missing" }.also { require(it.isNotBlank()) { "UID blank" } }
}

@Serializable
data class CodeDto(@SerialName("Code") val code: Int)

@Serializable
data class AuthInfoDto(
    @SerialName("Code") val code: Int,
    @SerialName("Modulus") val modulus: String,
    @SerialName("ServerEphemeral") val serverEphemeral: String,
    @SerialName("Version") val version: Int,
    @SerialName("Salt") val salt: String,
    @SerialName("SRPSession") val srpSession: String,
) {
    override fun toString() = "AuthInfoDto$REDACTED"
}

@Serializable
data class TwoFactorDto(@SerialName("Enabled") val enabled: Int, @SerialName("TOTP") val totp: Int)

enum class SecondFactor { NONE, TOTP, FIDO2_ONLY, UNSUPPORTED }

/**
 * The second factor a login needs. `Enabled` is go-proton-api's TwoFAStatus (1 TOTP, 2 FIDO2, 3 both), and `TOTP`
 * must agree with it: 0 is no second factor, 1 or 3 with `TOTP` 1 offers TOTP, 2 with `TOTP` 0 is FIDO2 only
 * (refused). Anything contradictory or unknown fails closed.
 */
fun TwoFactorDto.secondFactor(): SecondFactor = when {
    enabled == 0 -> SecondFactor.NONE
    (enabled == 1 || enabled == 3) && totp == 1 -> SecondFactor.TOTP
    enabled == 2 && totp == 0 -> SecondFactor.FIDO2_ONLY
    else -> SecondFactor.UNSUPPORTED
}

@Serializable
data class AuthDto(
    @SerialName("Code") val code: Int,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("TokenType") val tokenType: String,
    @SerialName("UID") private val uidUpper: String? = null,
    @SerialName("Uid") private val uidLower: String? = null,
    @SerialName("UserID") val userId: String,
    @SerialName("ServerProof") val serverProof: String,
    @SerialName("PasswordMode") val passwordMode: Int = 1,
    @SerialName("EventID") val eventId: String? = null,
    @SerialName("ExpiresIn") val expiresIn: Int,
    @SerialName("2FA") val twoFactor: TwoFactorDto,
) {
    @Transient
    val uid: String = sessionUid(uidUpper, uidLower)

    override fun toString() = "AuthDto$REDACTED"
}

/** The refresh's body: every field is sent (none rely on a default). */
@Serializable
internal data class RefreshRequestDto(
    @SerialName("UID") val uid: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("ResponseType") val responseType: String,
    @SerialName("GrantType") val grantType: String,
    @SerialName("RedirectURI") val redirectUri: String,
    @SerialName("State") val state: String,
) {
    override fun toString() = "RefreshRequestDto$REDACTED"
}

@Serializable
data class RefreshDto(
    @SerialName("Code") val code: Int,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("UID") private val uidUpper: String? = null,
    @SerialName("Uid") private val uidLower: String? = null,
) {
    @Transient
    val uid: String = sessionUid(uidUpper, uidLower)

    override fun toString() = "RefreshDto$REDACTED"
}

@Serializable
internal data class UserResponseDto(@SerialName("User") val user: UserDto) {
    override fun toString() = "UserResponseDto$REDACTED"
}

@Serializable
data class UserDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Email") val email: String,
    @SerialName("DisplayName") val displayName: String,
    @SerialName("Keys") val keys: List<UserKeyDto>,
) {
    override fun toString() = "UserDto$REDACTED"
}

@Serializable
data class UserKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("PrivateKey") val privateKey: String,
    @SerialName("Active") val active: Int,
    @SerialName("Primary") val primary: Int,
    @SerialName("Fingerprint") val fingerprint: String,
    @SerialName("Version") val version: Int,
) {
    override fun toString() = "UserKeyDto$REDACTED"
}

@Serializable
internal data class KeySaltsResponseDto(@SerialName("KeySalts") val keySalts: List<KeySaltDto>) {
    override fun toString() = "KeySaltsResponseDto$REDACTED"
}

@Serializable
data class KeySaltDto(@SerialName("ID") val id: String, @SerialName("KeySalt") val keySalt: String?) {
    override fun toString() = "KeySaltDto$REDACTED"
}

/** A2's body; the proofs come from go-srp. */
@Serializable
class SrpAuthDto(
    @SerialName("Username") val username: String,
    @SerialName("ClientEphemeral") val clientEphemeral: String,
    @SerialName("ClientProof") val clientProof: String,
    @SerialName("SRPSession") val srpSession: String,
) {
    override fun toString() = "SrpAuthDto$REDACTED"
}

/** A1's body. `Intent` has no default, so it is always encoded. */
@Serializable
internal class AuthInfoRequestDto(@SerialName("Username") val username: String, @SerialName("Intent") val intent: String) {
    override fun toString() = "AuthInfoRequestDto$REDACTED"
}

/** A3's body. */
@Serializable
internal class TotpRequestDto(@SerialName("TwoFactorCode") val twoFactorCode: String) {
    override fun toString() = "TotpRequestDto$REDACTED"
}

@JvmInline value class ProtonContactId(val value: String) {
    override fun toString() = "ProtonContactId$REDACTED"
}

/** C3's page: `Total` and no `More`. */
@Serializable
data class ContactExportPageDto(
    @SerialName("Contacts") val contacts: List<ContactDto>,
    @SerialName("Total") val total: Int? = null,
) {
    override fun toString() = "ContactExportPageDto$REDACTED"
}

/** C1's page: only the IDs, and each one's `UID` for a create's lookup, are read. */
@Serializable
data class ContactListPageDto(
    @SerialName("Contacts") val contacts: List<ContactIdDto>,
    @SerialName("Total") val total: Int? = null,
) {
    override fun toString() = "ContactListPageDto$REDACTED"
}

@Serializable
data class ContactIdDto(@SerialName("ID") val id: String, @SerialName("UID") val uid: String? = null) {
    override fun toString() = "ContactIdDto$REDACTED"
}

/** C2's `{Contact}`. */
@Serializable
internal data class ContactResponseDto(@SerialName("Contact") val contact: ContactDto) {
    override fun toString() = "ContactResponseDto$REDACTED"
}

/** A contact as C3 lists it and C2 returns it: the ID and cards, which are all a fingerprint and mapping need. */
@Serializable
data class ContactDto(@SerialName("ID") val id: String, @SerialName("Cards") val cards: List<CardDto>) {
    override fun toString() = "ContactDto$REDACTED"
}

/**
 * A contact card as C2 and C3 return it, and as C4 and C5 send it; `Signature` is absent on unsigned cards.
 */
@Serializable
data class CardDto(
    @SerialName("Type") val type: Int,
    @SerialName("Data") val data: String,
    @SerialName("Signature") val signature: String? = null,
) {
    override fun toString() = "CardDto$REDACTED"
}

/** A card as C4 and C5 send it: an unsigned card's `Signature` is `null`, as WebClients sends it. */
@Serializable
internal class CardUploadDto(
    @SerialName("Type") val type: Int,
    @SerialName("Data") val data: String,
    @SerialName("Signature") val signature: String?,
) {
    override fun toString() = "CardUploadDto$REDACTED"
}

private fun List<CardDto>.forUpload() = map { CardUploadDto(it.type, it.data, it.signature) }

/** C4's body: one contact; `Overwrite` is never 1 (that would replace a contact with the same `UID`). */
@Serializable
internal class CreateContactsRequestDto(
    @SerialName("Contacts") val contacts: List<CardsRequestDto>,
    @SerialName("Overwrite") val overwrite: Int,
    @SerialName("Labels") val labels: Int,
    @SerialName("Import") val import: Int,
) {
    override fun toString() = "CreateContactsRequestDto$REDACTED"

    companion object {
        fun of(cards: List<CardDto>) =
            CreateContactsRequestDto(listOf(CardsRequestDto.of(cards)), overwrite = 0, labels = 0, import = 0)
    }
}

/** C5's body, and each contact in C4's. */
@Serializable
internal class CardsRequestDto(@SerialName("Cards") val cards: List<CardUploadDto>) {
    override fun toString() = "CardsRequestDto$REDACTED"

    companion object {
        fun of(cards: List<CardDto>) = CardsRequestDto(cards.forUpload())
    }
}

/** C6's body: one ID. */
@Serializable
internal class DeleteContactsRequestDto(@SerialName("IDs") val ids: List<String>) {
    override fun toString() = "DeleteContactsRequestDto$REDACTED"

    companion object {
        fun of(id: ProtonContactId) = DeleteContactsRequestDto(listOf(id.value))
    }
}

/** C4's reply (`Code` 1001): one item per contact sent. */
@Serializable
internal class CreateContactsResponseDto(@SerialName("Responses") val responses: List<CreateItemDto>) {
    override fun toString() = "CreateContactsResponseDto$REDACTED"
}

@Serializable
internal class CreateItemDto(@SerialName("Index") val index: Int, @SerialName("Response") val response: CreateItemResponseDto) {
    override fun toString() = "CreateItemDto$REDACTED"
}

/** An item's `Code`, and on 1000 the new contact; only its ID is read (the reply carries no cards). */
@Serializable
internal class CreateItemResponseDto(
    @SerialName("Code") val code: Int,
    @SerialName("Contact") val contact: ContactIdDto? = null,
) {
    override fun toString() = "CreateItemResponseDto$REDACTED"
}

/** C6's reply (`Code` 1001): one item per ID sent. */
@Serializable
internal class DeleteContactsResponseDto(@SerialName("Responses") val responses: List<DeleteItemDto>) {
    override fun toString() = "DeleteContactsResponseDto$REDACTED"
}

@Serializable
internal class DeleteItemDto(@SerialName("ID") val id: String, @SerialName("Response") val response: CodeDto) {
    override fun toString() = "DeleteItemDto$REDACTED"
}

/** C4's answer for the one contact sent; any other item `Code` is an error. */
sealed interface CreateReply {
    /** Item `Code` 1000. */
    class Created(val id: ProtonContactId) : CreateReply {
        override fun toString() = "CreateReply.Created$REDACTED"
    }

    /** Item `Code` 2500: a contact with this `UID` already exists; nothing was created. */
    data object UidExists : CreateReply {
        override fun toString() = "CreateReply.UidExists"
    }

    /** Item `Code` 2001: the server refused a card; nothing was created. */
    data object CardRefused : CreateReply {
        override fun toString() = "CreateReply.CardRefused"
    }
}

/** An event-loop cursor: E1 gives the first, and each E2 page the next. */
@JvmInline value class EventId(val value: String) {
    override fun toString() = "EventId$REDACTED"
}

/** E1's `{EventID}`. */
@Serializable
internal class LatestEventDto(@SerialName("EventID") private val id: String) {
    @Transient
    val eventId = EventId(id.also { require(it.isNotEmpty()) { "EventID empty" } })

    override fun toString() = "LatestEventDto$REDACTED"
}

/**
 * E2's page: the next cursor, `More`, `Refresh` as sent (a bit field), the contact entries, and whether `User` or
 * `Addresses` changed. Entries keep their cards in the order sent.
 */
class EventPageDto(
    val eventId: EventId,
    val more: Boolean,
    val refresh: Int,
    val contacts: List<ContactEventDto>,
    val contactEmails: List<ContactEmailEventDto>,
    val userChanged: Boolean,
    val addressesChanged: Boolean,
) {
    override fun toString() = "EventPageDto$REDACTED"
}

/**
 * E2's page as sent: `More` is 0 or 1 (anything else fails), and `User` changed when present and not null,
 * `Addresses` when not empty, as go-proton-api reads them. Every other field is ignored.
 */
@Serializable
internal class EventPageResponseDto(
    @SerialName("EventID") private val id: String,
    @SerialName("More") private val more: Int,
    @SerialName("Refresh") private val refresh: Int,
    @SerialName("Contacts") private val contacts: List<ContactEventDto> = emptyList(),
    @SerialName("ContactEmails") private val contactEmails: List<ContactEmailEventDto> = emptyList(),
    @SerialName("User") private val user: JsonObject? = null,
    @SerialName("Addresses") private val addresses: JsonArray? = null,
) {
    @Transient
    val page = EventPageDto(
        eventId = EventId(id.also { require(it.isNotEmpty()) { "EventID empty" } }),
        more = when (more) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("More not 0 or 1")
        },
        refresh = refresh,
        contacts = contacts,
        contactEmails = contactEmails,
        userChanged = user != null,
        addressesChanged = !addresses.isNullOrEmpty(),
    )

    override fun toString() = "EventPageResponseDto$REDACTED"
}

/** A `Contacts` entry: `Contact` is the whole contact with its latest cards, and absent on a delete. */
@Serializable
class ContactEventDto(
    @SerialName("ID") val id: String,
    @SerialName("Action") val action: Int,
    @SerialName("Contact") val contact: ContactDto? = null,
) {
    override fun toString() = "ContactEventDto$REDACTED"
}

/** A `ContactEmails` entry; its `ContactEmail` isn't read. */
@Serializable
class ContactEmailEventDto(@SerialName("ID") val id: String, @SerialName("Action") val action: Int) {
    override fun toString() = "ContactEmailEventDto$REDACTED"
}

/** E2's answer: a page, or the cursor refused (HTTP 400, `Code` 2061). */
sealed interface EventsReply {
    class Page(val page: EventPageDto) : EventsReply {
        override fun toString() = "EventsReply.Page$REDACTED"
    }

    data object CursorRejected : EventsReply {
        override fun toString() = "EventsReply.CursorRejected"
    }
}
