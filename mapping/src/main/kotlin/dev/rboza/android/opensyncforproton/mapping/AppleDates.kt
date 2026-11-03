package dev.rboza.android.opensyncforproton.mapping

import ezvcard.io.scribe.DateOrTimePropertyScribe
import ezvcard.property.DateOrTimeProperty
import java.time.temporal.Temporal
import ezvcard.util.PartialDate as EzPartialDate

/** Apple's `X-ABDATE`, read with ez-vcard's own date parsing instead of as raw text. */
internal class AppleDate : DateOrTimeProperty {
    constructor(text: String) : super(text)
    constructor(date: Temporal) : super(date)
    constructor(date: EzPartialDate) : super(date)
}

internal object AppleDateScribe : DateOrTimePropertyScribe<AppleDate>(AppleDate::class.java, "X-ABDATE") {
    override fun newInstance(text: String) = AppleDate(text)

    override fun newInstance(date: Temporal) = AppleDate(date)

    override fun newInstance(date: EzPartialDate) = AppleDate(date)
}

/**
 * Apple writes its built-in labels as `_$!<Anniversary>!$_`; the name inside is the label, except
 * Apple's "Other", which is no label at all (OTHER on Android).
 */
internal fun appleLabel(label: String): String? {
    val builtIn = APPLE_BUILT_IN_LABEL.matchEntire(label)?.groupValues?.get(1) ?: return label
    return builtIn.takeUnless { it.equals("Other", ignoreCase = true) }
}

private val APPLE_BUILT_IN_LABEL = Regex("_\\\$!<(.*)>!\\\$_")
