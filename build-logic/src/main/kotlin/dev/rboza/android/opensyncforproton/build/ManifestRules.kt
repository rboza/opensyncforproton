package dev.rboza.android.opensyncforproton.build

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
// Security flags must be literal: a resource reference (`@bool/x`) would hide the real value from this check.
private val literalFlags = listOf("allowBackup", "usesCleartextTraffic", "exported", "isolatedProcess", "useAppZygote")

// D8: the only network security config elements and attributes allowed; anything else fails.
private val networkConfigAttributes = mapOf(
    "network-security-config" to emptySet(),
    "base-config" to setOf("cleartextTrafficPermitted"),
    "domain-config" to setOf("cleartextTrafficPermitted"),
    "domain" to setOf("includeSubdomains"),
    "trust-anchors" to emptySet(),
    "certificates" to setOf("src"),
    "certificateTransparency" to setOf("enabled"),
)

private val requiredSections = listOf("cloud-backup", "device-transfer", "cross-platform-transfer platform=ios")
private val backupDomains = listOf(
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
)

/** An `@xml` resource as it ships: the versions it has (the default is "") and the default's text. */
data class XmlResource(val versions: List<String>, val text: String?)

/**
 * The manifest rules (docs/architecture.md § Security), checked on the merged release manifest and the resources in
 * the release APK.
 * [allowedEntries] lists every component, permission, and other manifest or application child, library
 * entries included, in the form [entry] renders them; the list must match the manifest exactly.
 */
fun manifestViolations(manifest: String, xmlResource: (String) -> XmlResource?, allowedEntries: List<String>): List<String> {
    val root = parseXml(manifest)
    val application = root.childElements().singleOrNull { it.tagName == "application" }
        ?: return listOf("manifest must have one application element")
    val found = mutableListOf<String>()

    if (application.android("allowBackup") != "false") found += "application must set android:allowBackup=\"false\""
    found += xmlResourceViolations(application, "dataExtractionRules", xmlResource, ::extractionRuleViolations)
    found += xmlResourceViolations(application, "networkSecurityConfig", xmlResource, ::networkConfigViolations)
    if (application.android("usesCleartextTraffic") == "true") {
        found += "application must not set android:usesCleartextTraffic=\"true\""
    }
    if (application.android("backupAgent") != null) found += "application must not set android:backupAgent"
    if (application.android("memtagMode") != "sync") found += "application must set android:memtagMode=\"sync\""

    root.allElements().forEach { element ->
        val name = element.android("name")
        val label = if (element === application) "application" else name
        literalFlags.forEach { flag ->
            element.android(flag)?.takeIf { it != "true" && it != "false" }?.let {
                found += "$label sets android:$flag to $it; it must be literally true or false"
            }
        }
        element.android("process")?.let { found += "$label must run in the main process, not $it" }
        if (element.android("isolatedProcess") == "true") found += "$label must not run in an isolated process"
        if (element.android("useAppZygote") == "true") found += "$label must not use an app zygote"
        if (name != null && (name.startsWith("com.google.android.gms.") || name.startsWith("com.google.firebase."))) {
            found += "$name is a Play Services or Firebase entry"
        }
        if (element.tagName.startsWith("uses-permission") && name.orEmpty().startsWith("android.permission.FOREGROUND_SERVICE")) {
            found += "$name is never allowed"
        }
    }

    val entries = root.childElements().filter { it.tagName != "uses-sdk" && it !== application }.map(::entry) +
        application.childElements().map(::entry)
    (allowedEntries - entries.toSet()).forEach { found += "listed entry is not in the manifest: $it" }
    (entries - allowedEntries.toSet()).forEach { found += "unexpected manifest entry: $it" }
    return found.sorted()
}

/** The manifest inside the release APK, as `aapt2` shows it: neither debuggable nor test-only. */
fun releaseApkManifestViolations(apkManifest: String): List<String> {
    val application = parseXml(apkManifest).childElements().singleOrNull { it.tagName == "application" }
        ?: return listOf("the release APK has no application element")
    val found = mutableListOf<String>()
    if ((application.android("debuggable") ?: "false") != "false") found += "the release APK is debuggable"
    if ((application.android("testOnly") ?: "false") != "false") found += "the release APK is test-only"
    return found
}

private const val CRYPTO_LIBRARY = "lib/arm64-v8a/libgojni.so"

/**
 * D1: the release APK's native code (`lib/<abi>/...` entries) is for `arm64-v8a` only, and it includes the
 * crypto library, so a wrong ABI filter can't ship an app that fails at its first login.
 */
fun releaseApkNativeCodeViolations(apkEntries: List<String>): List<String> = apkEntries
    .filter { it.startsWith("lib/") && !it.startsWith("lib/arm64-v8a/") }
    .sorted()
    .map { "the release APK has native code for another ABI: $it" } +
    listOfNotNull("the release APK has no $CRYPTO_LIBRARY (the crypto library)".takeIf { CRYPTO_LIBRARY !in apkEntries })

private fun entry(element: Element): String {
    val tag = element.tagName
    val name = element.android("name")
    return when (tag) {
        in componentTags -> "$tag $name exported=${element.android("exported") ?: "false"} " +
            "permission=${element.android("permission") ?: "none"}"
        "permission" -> "$tag $name protectionLevel=${element.android("protectionLevel") ?: "normal"}"
        else -> listOfNotNull(tag, name).joinToString(" ")
    }
}

private fun xmlResourceViolations(
    application: Element,
    attribute: String,
    xmlResource: (String) -> XmlResource?,
    rules: (Element) -> List<String>,
): List<String> {
    val reference = application.android(attribute)
    if (reference == null || !reference.startsWith("@xml/") || reference.length == "@xml/".length) {
        return listOf("application must set android:$attribute to an @xml resource")
    }
    val resource = xmlResource(reference.removePrefix("@xml/")) ?: return listOf("$reference is not in the APK")
    if (resource.versions != listOf("") || resource.text == null) {
        val versions = resource.versions.joinToString { it.ifEmpty { "default" } }
        return listOf("$reference must ship in one version, the default; found $versions")
    }
    return rules(parseXml(resource.text))
}

/** Every section excludes every domain and includes nothing; cloud backup, device transfer, and iOS transfer must be present. */
private fun extractionRuleViolations(rules: Element): List<String> {
    val found = mutableListOf<String>()
    val sections = rules.childElements().associateBy { section ->
        section.tagName + section.getAttribute("platform").let { if (it.isEmpty()) "" else " platform=$it" }
    }
    (requiredSections - sections.keys).forEach { found += "data extraction rules: $it is missing" }
    sections.forEach { (label, section) ->
        val children = section.childElements()
        if (children.any { it.tagName == "include" }) found += "data extraction rules: $label must not include anything"
        val excluded = children.filter { it.tagName == "exclude" && it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
        (backupDomains - excluded.toSet()).forEach { found += "data extraction rules: $label must exclude domain $it" }
    }
    return found
}

/**
 * D8, as an allow-list: no cleartext, only the system certificate store, and certificate transparency on.
 * Pins, debug overrides, and any element or attribute not listed above fail.
 */
private fun networkConfigViolations(config: Element): List<String> {
    val found = mutableListOf<String>()
    val base = config.childElements().filter { it.tagName == "base-config" }
    if (base.size != 1 || base.single().getAttribute("cleartextTrafficPermitted") != "false") {
        found += "network security config: base-config must set cleartextTrafficPermitted=\"false\""
    }
    fun visit(element: Element) {
        val tag = element.tagName
        val attributes = networkConfigAttributes[tag]
        if (attributes == null) {
            found += "network security config: $tag is not allowed"
            return
        }
        (0 until element.attributes.length).map { element.attributes.item(it).nodeName }
            .filter { it !in attributes }
            .forEach { found += "network security config: $tag $it is not allowed" }
        when {
            // Android reads 1, 0x1, strings, and references as "on" (or the default, on): only literal false is off.
            tag == "domain-config" && element.hasAttribute("cleartextTrafficPermitted") &&
                element.getAttribute("cleartextTrafficPermitted") != "false" ->
                found += "network security config: domain-config permits cleartext"
            tag == "domain" && element.hasAttribute("includeSubdomains") &&
                element.getAttribute("includeSubdomains") !in setOf("true", "false") ->
                found += "network security config: domain includeSubdomains must be literally true or false"
            tag == "certificates" && element.getAttribute("src") != "system" ->
                found += "network security config: trusts certificates from ${element.getAttribute("src")}"
            tag == "certificateTransparency" && element.getAttribute("enabled") != "true" ->
                found += "network security config: certificateTransparency must not be turned off"
        }
        element.childElements().forEach(::visit)
    }
    visit(config)
    return found
}

private fun parseXml(text: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val builder = factory.newDocumentBuilder().apply {
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
    }
    return builder.parse(InputSource(StringReader(text))).documentElement
}

private fun Element.android(attribute: String): String? =
    if (hasAttributeNS(ANDROID_NS, attribute)) getAttributeNS(ANDROID_NS, attribute) else null

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

private fun Element.allElements(): List<Element> =
    listOf(this) + childElements().flatMap { it.allElements() }
