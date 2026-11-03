package dev.rboza.android.opensyncforproton.mapping

import com.github.mangstadt.vinnie.SyntaxStyle
import com.github.mangstadt.vinnie.VObjectParameters
import com.github.mangstadt.vinnie.VObjectProperty
import com.github.mangstadt.vinnie.io.Context
import com.github.mangstadt.vinnie.io.SyntaxRules
import com.github.mangstadt.vinnie.io.VObjectDataAdapter
import com.github.mangstadt.vinnie.io.VObjectPropertyValues
import com.github.mangstadt.vinnie.io.VObjectReader
import com.github.mangstadt.vinnie.io.VObjectWriter
import ezvcard.VCardDataType
import ezvcard.VCardVersion
import ezvcard.io.ParseContext
import ezvcard.io.scribe.VCardPropertyScribe
import ezvcard.io.text.WriteContext
import ezvcard.parameter.VCardParameters
import ezvcard.property.VCardProperty
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale

// The only file that calls vinnie directly, so replacing it stays a small change.

/** A property ez-vcard didn't recognise (an `X-` one) keeps its escapes; this removes them. */
internal fun unescapedText(raw: String): String = VObjectPropertyValues.unescape(raw)

/**
 * One property's line as written, unfolded: its name in upper case and as written, its group, its parameters (names in
 * upper case, in order), and its value as written, escapes included.
 */
internal class PropertyLine(
    val name: String,
    val line: String,
    val group: String?,
    val parameters: List<Pair<String, List<String>>>,
    val value: String,
    val writtenName: String,
)

/** A card's `VERSION` and its property lines. */
internal class CardLines(val version: String, val lines: List<PropertyLine>)

/**
 * The property lines of a card, in order, read as ez-vcard reads them (with vinnie, under vCard syntax rules), so its
 * properties of one name come from these lines in the same order. The mapper only reads a card that is exactly one
 * vCard, so every property is in it; vinnie gives `VERSION` apart from the properties.
 */
internal fun cardLines(vcard: String): CardLines {
    var version = ""
    val lines = buildList {
        VObjectReader(StringReader(vcard), SyntaxRules.vcard()).use { reader ->
            reader.parse(
                object : VObjectDataAdapter() {
                    override fun onVersion(value: String, context: Context) {
                        version = value
                    }

                    override fun onProperty(property: VObjectProperty, context: Context) {
                        // A parameter without a name (vCard 2.1's bare `CELL`) is a TYPE value, as ez-vcard reads it.
                        val parameters = LinkedHashMap<String, List<String>>()
                        property.parameters.forEach { (name, values) -> parameters.merge(name ?: "TYPE", values.toList(), List<String>::plus) }
                        add(PropertyLine(property.name.uppercase(Locale.ROOT), context.unfoldedLine, property.group, parameters.toList(), property.value, property.name))
                    }
                },
            )
        }
    }
    return CardLines(version, lines)
}

/**
 * A property's line, unfolded, written as vinnie writes it for a card of [version] (2.1's own syntax has no lists of
 * parameter values); [value] must already be escaped. A parameter
 * without values isn't written. Parameter values use RFC 6868's caret encoding, which vinnie decodes when it reads, so
 * a label with a quote or a line break is written instead of refused.
 */
internal fun propertyLine(
    group: String?,
    name: String,
    parameters: List<Pair<String, List<String>>>,
    value: String,
    version: String = "4.0",
): String {
    val text = StringWriter()
    VObjectWriter(text, if (version == "2.1") SyntaxStyle.OLD else SyntaxStyle.NEW).run {
        foldedLineWriter.lineLength = null
        isCaretEncodingEnabled = true
        writeProperty(group, name, VObjectParameters().apply { parameters.forEach { (key, values) -> putAll(key, *values.toTypedArray()) } }, value)
        flush()
    }
    return text.toString().removeSuffix("\r\n")
}

/** vCard text with backslashes, `,`, `;`, and line breaks escaped. */
internal fun escapedText(text: String): String = VObjectPropertyValues.escape(text)

/** A URI as it is, but for its backslashes, which ez-vcard reads as escapes even in a URI. */
internal fun escapedUri(uri: String): String = uri.replace("\\", "\\\\")

/** A structured value (`N`, `ADR`): its components, each a list of values. */
internal fun structuredValue(raw: String): List<List<String>> = VObjectPropertyValues.parseStructured(raw)

internal fun structuredText(components: List<List<String>>): String = VObjectPropertyValues.writeStructured(components, true)

/** A value split at `;` only (`ORG`). */
internal fun semiStructuredValue(raw: String): List<String> = VObjectPropertyValues.parseSemiStructured(raw)

internal fun semiStructuredText(values: List<String>): String = VObjectPropertyValues.writeSemiStructured(values, true, true)

/** A value split at `,` (`NICKNAME`). */
internal fun listValue(raw: String): List<String> = VObjectPropertyValues.parseList(raw)

internal fun listText(values: List<String>): String = VObjectPropertyValues.writeList(values)

/** `N` with every value of each component: family, given, middle, prefixes, suffixes. */
internal class NameComponents(val components: List<List<String>>) : VCardProperty()

/** Reads `N` in place of ez-vcard's StructuredName, which keeps only the first family and given name. */
internal object NameComponentsScribe : VCardPropertyScribe<NameComponents>(NameComponents::class.java, "N") {
    override fun _defaultDataType(version: VCardVersion) = VCardDataType.TEXT

    override fun _parseText(
        value: String,
        dataType: VCardDataType?,
        parameters: VCardParameters,
        context: ParseContext,
    ) = NameComponents(
        // vCard 2.1 has no value lists: a comma there is text.
        if (context.version == VCardVersion.V2_1) {
            VObjectPropertyValues.parseSemiStructured(value).map { listOf(it) }
        } else {
            VObjectPropertyValues.parseStructured(value)
        },
    )

    override fun _writeText(property: NameComponents, context: WriteContext): String =
        throw UnsupportedOperationException("the mapper only reads vCards")
}
