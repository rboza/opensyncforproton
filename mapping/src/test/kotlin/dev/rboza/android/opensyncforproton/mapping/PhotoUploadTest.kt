package dev.rboza.android.opensyncforproton.mapping

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The photo on upload, a field Android shows once, as `:sync`'s photo baseline decides it (docs/mapping.md § Writing).
// A card's lines show as they are, `*` before an edited one. Every value is made up for the test.
class PhotoUploadTest {
    private val mapper = EzvcardContactMapper()

    /** What `:sync` gives the patch: the phone's photo, already scaled and re-encoded as JPEG. */
    private val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
    private val base64 = Base64.getEncoder().encodeToString(jpeg)

    private fun card(placement: CardPlacement, version: String, vararg lines: String) = CardFragment(placement, ContactPatchTest.vcard(version, lines.toList()))

    private fun patch(cards: List<CardFragment>, photo: PhotoUpload): ContactPatch {
        val remote = success(mapper.merge(cards))
        return success(mapper.patch(remote, cards, photo))
    }

    private fun shown(patch: ContactPatch) = patch.cards.map { card -> card.placement to card.lines.map { (if (it.edited) "*" else "") + it.text } }

    private fun <T> success(result: MappingResult<T>): T = assertInstanceOf(MappingResult.Success::class.java, result).value.let {
        @Suppress("UNCHECKED_CAST")
        it as T
    }

    @Test
    fun `kept, the photo lines stay as they are`() {
        val cards = listOf(card(CardPlacement.ENCRYPTED, "4.0", "FN:Ann", "PHOTO:data:image/png;base64,iVBO"))

        val patch = patch(cards, PhotoUpload.Keep)

        assertFalse(patch.changed)
        assertEquals(listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "PHOTO:data:image/png;base64,iVBO")), shown(patch))
    }

    @Test
    fun `a changed photo replaces the first embedded PHOTO's line in its card, keeping its group but not its parameters`() {
        val cards = listOf(
            card(CardPlacement.SIGNED, "4.0", "FN:Ann"),
            card(
                CardPlacement.ENCRYPTED, "4.0",
                // A URL is never mapped, so it stays, even before the embedded photo.
                "PHOTO:https://example.test/ann.png", "item1.PHOTO;PREF=1;MEDIATYPE=image/png:data:image/png;base64,iVBO",
                "item1.X-ABLabel:Face", "PHOTO:data:image/gif;base64,R0lG",
            ),
        )

        val patch = patch(cards, PhotoUpload.Replace(jpeg))

        assertTrue(patch.changed)
        assertEquals(
            listOf(
                CardPlacement.SIGNED to listOf("FN:Ann"),
                CardPlacement.ENCRYPTED to listOf(
                    "PHOTO:https://example.test/ann.png", "*item1.PHOTO:data:image/jpeg;base64,$base64", "item1.X-ABLabel:Face",
                    // A later embedded photo is a hidden part: it stays behind the phone's.
                    "PHOTO:data:image/gif;base64,R0lG",
                ),
            ),
            shown(patch),
        )
        val read = success(mapper.merge(patch.fragments())).graph.photo!!
        assertArrayEquals(jpeg, read.bytes)
        assertEquals("image/jpeg", read.mediaType)
    }

    @Test
    fun `a changed photo is written in its card's own syntax`() {
        for ((version, old, new) in listOf(
            Triple("3.0", "PHOTO;ENCODING=b;TYPE=PNG:iVBO", "*PHOTO;ENCODING=b;TYPE=JPEG:$base64"),
            Triple("2.1", "PHOTO;ENCODING=BASE64;TYPE=PNG:iVBO", "*PHOTO;ENCODING=BASE64;TYPE=JPEG:$base64"),
            // Proton keeps a 3.0 import's encoding in its 4.0 card; the line is rewritten as a data: URL.
            Triple("4.0", "PHOTO;ENCODING=b;TYPE=PNG:iVBO", "*PHOTO:data:image/jpeg;base64,$base64"),
        )) {
            val cards = listOf(card(CardPlacement.ENCRYPTED, version, "FN:Ann", old))

            val patch = patch(cards, PhotoUpload.Replace(jpeg))

            assertEquals(listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", new)), shown(patch), version)
            assertArrayEquals(jpeg, success(mapper.merge(patch.fragments())).graph.photo!!.bytes, version)
        }
    }

    @Test
    fun `a photo the remote doesn't have is a new line, last in the encrypted card`() {
        val cards = listOf(
            card(CardPlacement.SIGNED, "4.0", "FN:Ann"),
            card(CardPlacement.ENCRYPTED, "3.0", "NOTE:hi", "PHOTO:https://example.test/ann.png"),
        )

        val patch = patch(cards, PhotoUpload.Replace(jpeg))

        assertEquals(
            listOf(
                CardPlacement.SIGNED to listOf("FN:Ann"),
                CardPlacement.ENCRYPTED to listOf("NOTE:hi", "PHOTO:https://example.test/ann.png", "*PHOTO;ENCODING=b;TYPE=JPEG:$base64"),
            ),
            shown(patch),
        )

        val signedOnly = patch(listOf(card(CardPlacement.SIGNED, "4.0", "FN:Ann")), PhotoUpload.Replace(jpeg))
        assertEquals(
            listOf(CardPlacement.SIGNED to listOf("FN:Ann"), CardPlacement.ENCRYPTED to listOf("*PHOTO:data:image/jpeg;base64,$base64")),
            shown(signedOnly),
            "a new 4.0 card when the contact has no encrypted one",
        )
    }

    @Test
    fun `a photo removed on the phone takes every embedded PHOTO, with its group, and leaves URLs`() {
        val cards = listOf(
            card(CardPlacement.SIGNED, "4.0", "FN:Ann"),
            card(
                CardPlacement.ENCRYPTED, "4.0",
                "item1.PHOTO:data:image/png;base64,iVBO", "item1.X-ABLabel:Face", "PHOTO:https://example.test/ann.png",
                "PHOTO:data:image/gif;base64,R0lG",
            ),
        )

        val patch = patch(cards, PhotoUpload.Remove)

        assertEquals(
            listOf(CardPlacement.SIGNED to listOf("FN:Ann"), CardPlacement.ENCRYPTED to listOf("PHOTO:https://example.test/ann.png")),
            shown(patch),
        )
        assertNull(success(mapper.merge(patch.fragments())).graph.photo)
        assertFalse(patch(listOf(card(CardPlacement.SIGNED, "4.0", "FN:Ann")), PhotoUpload.Remove).changed, "nothing to remove")
    }

    @Test
    fun `nothing of the phone's goes in a clear card, though a photo there may go`() {
        val cards = listOf(card(CardPlacement.CLEAR, "4.0", "PHOTO:data:image/png;base64,iVBO"), card(CardPlacement.SIGNED, "4.0", "FN:Ann"))
        val remote = success(mapper.merge(cards))

        assertEquals(MappingResult.Invalid(listOf(MappingIssue(MappingIssueCode.UNENCRYPTED_PROPERTY))), mapper.patch(remote, cards, PhotoUpload.Replace(jpeg)))
        assertEquals(listOf(CardPlacement.CLEAR to emptyList<String>(), CardPlacement.SIGNED to listOf("FN:Ann")), shown(patch(cards, PhotoUpload.Remove)))
    }

    @Test
    fun `an upload's toString shows no photo`() {
        assertEquals("PhotoUpload.Replace(size=6)", PhotoUpload.Replace(jpeg).toString())
    }
}
