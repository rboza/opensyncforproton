package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.LockedUserKey
import dev.rboza.android.opensyncforproton.crypto.SecretBox
import dev.rboza.android.opensyncforproton.proton.Tokens
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** The Proton `User.ID` of a signed-in account. */
@JvmInline value class AccountId(val value: String)

/** A2's `PasswordMode`: 1 is one password, 2 is two-password mode. */
enum class PasswordMode(val code: Int) { ONE(1), TWO(2) }

/**
 * What background sync needs from a login (D22): never the typed password and never unlocked keys. [passphrases]
 * maps a user key ID to its derived key passphrase; [close] zeroes them, so callers use it with `use {}`.
 */
class Credentials(
    val tokens: Tokens,
    val passwordMode: PasswordMode,
    /** U3's salts by key ID; `null` for a key that doesn't use one. */
    val keySalts: Map<String, String?>,
    val keys: LockedKeyBundle,
    val passphrases: Map<String, ByteArray>,
) : AutoCloseable {
    override fun close() = passphrases.values.forEach { it.fill(0) }

    override fun toString() = "Credentials(redacted)"
}

/**
 * A save or delete that failed. It has no message of its own, but its cause can name a path or a Keystore alias, so
 * callers record a typed failure and never the exception (D23).
 */
class CredentialStoreException(cause: Throwable? = null) : Exception(null, cause)

/** One Keystore-sealed envelope per account (D22). Every call blocks on I/O. */
interface CredentialStore {
    /**
     * Atomic replace: returns once the new envelope is durable. Any exception becomes [CredentialStoreException]; the
     * envelope then holds the old credentials, or the new ones if only the final directory sync failed.
     */
    fun save(account: AccountId, credentials: Credentials)

    /** `null` when the envelope is missing or unreadable; never cached. The caller closes the result. */
    fun load(account: AccountId): Credentials?

    /** Removes the envelope, then its key; throws [CredentialStoreException] only if the envelope is still there. */
    fun delete(account: AccountId)

    /**
     * Replaces only the tokens, writing the envelope as [save] does (docs/protocol.md § Token refresh, step 3). With no
     * readable envelope it throws [CredentialStoreException] and makes none, so a delete is never undone; with a stored
     * session whose UID differs from [tokens]' (a late refresh of a session a new login replaced) it throws and changes
     * nothing.
     */
    fun updateTokens(account: AccountId, tokens: Tokens)

    /**
     * Replaces the locked keys with [keys], fetched again by the session [uid] after a key change, writing the
     * envelope as [save] does. Only a login derives passphrases, so the stored ones carry over by key ID to the keys
     * still there, and the rest are dropped. With no readable envelope, a stored session other than [uid], a key ID
     * repeated in [keys], or text with no UTF-8 form, it throws [CredentialStoreException] and changes nothing.
     */
    fun updateKeys(account: AccountId, uid: String, keys: LockedKeyBundle)

    /**
     * [delete], unless the envelope holds a session other than [uid] (a late rejection of a session a new login
     * replaced), which it keeps. A missing or unreadable envelope is deleted.
     */
    fun deleteSession(account: AccountId, uid: String)
}

/**
 * Each account's envelope is the file `<sha256(accountId)>` in [directory] (no-backup storage), holding
 * [SecretBox]'s output under the alias `<applicationId>.creds.<sha256(accountId)>`. Every call blocks on file and
 * Keystore I/O and is safe from any thread. [save], [delete], [updateTokens], [updateKeys], and [deleteSession] share
 * one process-wide lock, so a delete or a save waits for a token or key update that has already read the envelope.
 * [load] takes no lock: an envelope is only ever replaced whole, by an atomic rename, or deleted; a load that races a
 * delete returns `null`.
 */
class FileCredentialStore(
    private val directory: File,
    private val applicationId: String,
    private val box: SecretBox,
) : CredentialStore {
    override fun save(account: AccountId, credentials: Credentials) = synchronized(storeLock) {
        write(sha256Hex(account.value), credentials)
    }

    private fun write(name: String, credentials: Credentials) {
        var temp: File? = null
        try {
            val plaintext = encode(credentials)
            val sealed = try {
                box.seal(alias(name), plaintext)
            } finally {
                plaintext.fill(0)
            }
            // A directory made here is durable only once its parent is.
            if (directory.mkdirs()) sync(checkNotNull(directory.absoluteFile.parentFile))
            temp = File.createTempFile("$name.", ".tmp", directory)
            FileOutputStream(temp).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            Files.move(temp.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temp = null
            // The rename is durable only once the directory is.
            sync(directory)
        } catch (e: Exception) {
            throw CredentialStoreException(e)
        } finally {
            temp?.delete()
        }
    }

    override fun load(account: AccountId): Credentials? {
        val name = sha256Hex(account.value)
        return try {
            val plaintext = box.open(alias(name), File(directory, name).readBytes())
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(account: AccountId) = synchronized(storeLock) {
        val name = sha256Hex(account.value)
        val file = File(directory, name)
        if (!file.delete() && file.exists()) throw CredentialStoreException()
        // An orphaned key is harmless: the envelope is already gone, and a later save reuses the key.
        try {
            box.delete(alias(name))
        } catch (_: Exception) {
        }
    }

    override fun updateTokens(account: AccountId, tokens: Tokens) = synchronized(storeLock) {
        val old = load(account) ?: throw CredentialStoreException()
        old.use {
            if (it.tokens.uid != tokens.uid) throw CredentialStoreException()
            write(sha256Hex(account.value), Credentials(tokens, it.passwordMode, it.keySalts, it.keys, it.passphrases))
        }
    }

    override fun updateKeys(account: AccountId, uid: String, keys: LockedKeyBundle) = synchronized(storeLock) {
        val ids = keys.userKeys.map { it.id }.toSet()
        if (ids.size != keys.userKeys.size) throw CredentialStoreException()
        val old = load(account) ?: throw CredentialStoreException()
        // Closing the old credentials zeroes every passphrase, those carried over included, once they're written.
        old.use {
            if (it.tokens.uid != uid) throw CredentialStoreException()
            write(sha256Hex(account.value), Credentials(it.tokens, it.passwordMode, it.keySalts, keys, it.passphrases.filterKeys { id -> id in ids }))
        }
    }

    override fun deleteSession(account: AccountId, uid: String) = synchronized(storeLock) {
        val stored = load(account)?.use { it.tokens.uid }
        if (stored == null || stored == uid) delete(account)
    }

    private fun alias(name: String) = "$applicationId.creds.$name"
}

// Saves, token and key updates, and deletes take turns, so a delete can't be undone by an update that read the
// envelope before it. The process's lock, so that stores made by different components share it.
private val storeLock = Any()

private fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

private const val ENVELOPE_VERSION = 1

/*
 * The envelope's plaintext, version 1: the version byte, then the tokens (UID, access, refresh), the password mode's
 * code, the salts (count; each a key ID, a presence flag, and the salt if present), the user keys (count; each an ID,
 * the armored key, active, primary), and the passphrases (count; each a key ID and the bytes). A count or a length is
 * a 4-byte big-endian int, a string is UTF-8 bytes, and a flag is one byte, 0 or 1. Written in two passes, sizing then
 * filling, so the one buffer that holds the passphrases is exactly sized and can be zeroed.
 */
private fun encode(credentials: Credentials): ByteArray {
    val sizing = Writer(null)
    sizing.credentials(credentials)
    val filling = Writer(ByteArray(sizing.position))
    filling.credentials(credentials)
    return filling.buffer!!
}

private fun Writer.credentials(credentials: Credentials) {
    byte(ENVELOPE_VERSION)
    string(credentials.tokens.uid)
    string(credentials.tokens.access)
    string(credentials.tokens.refresh)
    int(credentials.passwordMode.code)
    int(credentials.keySalts.size)
    credentials.keySalts.forEach { (id, salt) ->
        string(id)
        flag(salt != null)
        if (salt != null) string(salt)
    }
    int(credentials.keys.userKeys.size)
    credentials.keys.userKeys.forEach {
        string(it.id)
        string(it.armoredPrivateKey)
        flag(it.active)
        flag(it.primary)
    }
    int(credentials.passphrases.size)
    credentials.passphrases.forEach { (id, passphrase) ->
        string(id)
        bytes(passphrase)
    }
}

/** Writes into [buffer], or only counts the bytes when it's `null`. */
private class Writer(val buffer: ByteArray?) {
    var position = 0

    fun byte(value: Int) {
        buffer?.set(position, value.toByte())
        position += 1
    }

    fun flag(value: Boolean) = byte(if (value) 1 else 0)

    fun int(value: Int) {
        for (shift in 24 downTo 0 step 8) byte(value ushr shift)
    }

    fun bytes(value: ByteArray) {
        int(value.size)
        buffer?.let { value.copyInto(it, position) }
        position += value.size
    }

    /** Text without a UTF-8 form (a lone surrogate) throws rather than becoming U+FFFD, which could repeat a key ID. */
    fun string(value: String) = bytes(value.encodeToByteArray(throwOnInvalidSequence = true))
}

/** Any malformed field throws; the store turns it into `null`. */
private fun decode(plaintext: ByteArray): Credentials {
    val reader = Reader(plaintext)
    val passphrases = LinkedHashMap<String, ByteArray>()
    try {
        check(reader.byte() == ENVELOPE_VERSION)
        val tokens = Tokens(uid = reader.string(), access = reader.string(), refresh = reader.string())
        val code = reader.int()
        val passwordMode = PasswordMode.entries.single { it.code == code }
        val keySalts = LinkedHashMap<String, String?>()
        repeat(reader.count()) {
            val id = reader.string()
            check(id !in keySalts)
            keySalts[id] = if (reader.flag()) reader.string() else null
        }
        val keyIds = HashSet<String>()
        val userKeys = List(reader.count()) {
            LockedUserKey(reader.string(), reader.string(), active = reader.flag(), primary = reader.flag())
                .also { check(keyIds.add(it.id)) }
        }
        repeat(reader.count()) {
            val id = reader.string()
            check(id !in passphrases)
            passphrases[id] = reader.bytes()
        }
        check(reader.atEnd())
        return Credentials(tokens, passwordMode, keySalts, LockedKeyBundle(userKeys), passphrases)
    } catch (e: Exception) {
        passphrases.values.forEach { it.fill(0) }
        throw e
    }
}

private class Reader(private val data: ByteArray) {
    private var position = 0

    fun byte(): Int = data[position++].toInt() and 0xff

    fun flag(): Boolean = when (byte()) {
        0 -> false
        1 -> true
        else -> error("flag")
    }

    fun int(): Int = (0 until 4).fold(0) { value, _ -> (value shl 8) or byte() }

    /** A count or length can't exceed the bytes left, so a corrupt one fails before anything is allocated. */
    fun count(): Int = int().also { check(it in 0..data.size - position) }

    fun bytes(): ByteArray {
        val length = count()
        return data.copyOfRange(position, position + length).also { position += length }
    }

    fun string(): String = bytes().decodeToString(throwOnInvalidSequence = true)

    fun atEnd() = position == data.size
}
