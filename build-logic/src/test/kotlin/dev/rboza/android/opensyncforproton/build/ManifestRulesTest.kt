package dev.rboza.android.opensyncforproton.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.xml.sax.SAXParseException

class ManifestRulesTest {
    private val domains = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private val secureApplication =
        """android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules" """ +
            """android:networkSecurityConfig="@xml/network_security_config" android:memtagMode="sync""""

    private fun manifest(application: String = secureApplication, components: String = "", top: String = "") = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.example">
            <uses-sdk android:minSdkVersion="37" android:targetSdkVersion="37" />
            $top
            <application $application>
                $components
            </application>
        </manifest>
    """.trimIndent()

    private fun section(name: String, excluded: List<String> = domains, extra: String = "", attributes: String = "") =
        "<$name$attributes>" + excluded.joinToString("") { """<exclude domain="$it" path="."/>""" } + extra + "</$name>"

    private fun ios(excluded: List<String> = domains, extra: String = "") =
        section("cross-platform-transfer", excluded, extra, attributes = """ platform="ios"""")

    private fun extractionRules(
        vararg sections: String = arrayOf(section("cloud-backup"), section("device-transfer"), ios()),
    ) = "<data-extraction-rules>" + sections.joinToString("") + "</data-extraction-rules>"

    private fun networkConfig(inner: String = """<base-config cleartextTrafficPermitted="false"/>""") =
        "<network-security-config>$inner</network-security-config>"

    private fun violations(
        manifest: String = manifest(),
        extraction: String? = extractionRules(),
        network: String? = networkConfig(),
        allowed: List<String> = emptyList(),
        versions: List<String> = listOf(""),
    ): List<String> {
        val resources = mapOf("data_extraction_rules" to extraction, "network_security_config" to network)
        return manifestViolations(manifest, { name -> resources[name]?.let { XmlResource(versions, it) } }, allowed)
    }

    @Test
    fun `a manifest with backup off, no cleartext, and nothing else passes`() {
        assertEquals(emptyList<String>(), violations())
    }

    @Test
    fun `backup must be off`() {
        val expected = listOf("application must set android:allowBackup=\"false\"")
        assertEquals(expected, violations(manifest(secureApplication.replace("\"false\"", "\"true\""))))
        assertEquals(expected, violations(manifest(secureApplication.replace("android:allowBackup=\"false\"", ""))))
    }

    @Test
    fun `memory tagging is requested in sync mode`() {
        val expected = listOf("application must set android:memtagMode=\"sync\"")
        assertEquals(expected, violations(manifest(secureApplication.replace("android:memtagMode=\"sync\"", ""))))
        listOf("async", "default", "off", "@string/m").forEach { mode ->
            assertEquals(expected, violations(manifest(secureApplication.replace("\"sync\"", "\"$mode\""))), mode)
        }
    }

    @Test
    fun `data extraction rules must exist`() {
        assertEquals(
            listOf("application must set android:dataExtractionRules to an @xml resource"),
            violations(manifest(secureApplication.replace("android:dataExtractionRules=\"@xml/data_extraction_rules\"", ""))),
        )
        assertEquals(listOf("@xml/data_extraction_rules is not in the APK"), violations(extraction = null))
    }

    @Test
    fun `each resource ships in exactly one version, the default`() {
        assertEquals(
            listOf(
                "@xml/data_extraction_rules must ship in one version, the default; found default, v38",
                "@xml/network_security_config must ship in one version, the default; found default, v38",
            ),
            violations(versions = listOf("", "v38")),
        )
        assertEquals(
            listOf(
                "@xml/data_extraction_rules must ship in one version, the default; found night",
                "@xml/network_security_config must ship in one version, the default; found night",
            ),
            violations(versions = listOf("night")),
        )
    }

    @Test
    fun `cloud backup, device transfer, and iOS transfer each exclude every domain`() {
        assertEquals(
            listOf("data extraction rules: cloud-backup must exclude domain external"),
            violations(extraction = extractionRules(section("cloud-backup", domains - "external"), section("device-transfer"), ios())),
        )
        assertEquals(
            listOf("data extraction rules: cross-platform-transfer platform=ios must exclude domain device_file"),
            violations(extraction = extractionRules(section("cloud-backup"), section("device-transfer"), ios(domains - "device_file"))),
        )
        assertEquals(
            listOf("data extraction rules: device-transfer is missing"),
            violations(extraction = extractionRules(section("cloud-backup"), ios())),
        )
        assertEquals(
            listOf("data extraction rules: cross-platform-transfer platform=ios is missing"),
            violations(extraction = extractionRules(section("cloud-backup"), section("device-transfer"))),
        )
        val partial = section("cloud-backup").replace("""domain="root" path="."""", """domain="root" path="cache"""")
        assertEquals(
            listOf("data extraction rules: cloud-backup must exclude domain root"),
            violations(extraction = extractionRules(partial, section("device-transfer"), ios())),
        )
    }

    @Test
    fun `no section includes anything`() {
        val include = """<include domain="file" path="."/>"""
        assertEquals(
            listOf("data extraction rules: device-transfer must not include anything"),
            violations(extraction = extractionRules(section("cloud-backup"), section("device-transfer", extra = include), ios())),
        )
        assertEquals(
            listOf("data extraction rules: cross-platform-transfer platform=ios must not include anything"),
            violations(extraction = extractionRules(section("cloud-backup"), section("device-transfer"), ios(extra = include))),
        )
    }

    @Test
    fun `cleartext is forbidden everywhere`() {
        assertEquals(
            listOf("application must set android:networkSecurityConfig to an @xml resource"),
            violations(manifest(secureApplication.replace("android:networkSecurityConfig=\"@xml/network_security_config\"", ""))),
        )
        assertEquals(
            listOf("network security config: base-config must set cleartextTrafficPermitted=\"false\""),
            violations(network = networkConfig("""<base-config cleartextTrafficPermitted="true"/>""")),
        )
        assertEquals(
            listOf("network security config: base-config must set cleartextTrafficPermitted=\"false\""),
            violations(network = networkConfig("")),
        )
        val domain = """<domain-config cleartextTrafficPermitted="true"><domain>example.org</domain></domain-config>"""
        assertEquals(
            listOf("network security config: domain-config permits cleartext"),
            violations(network = networkConfig("""<base-config cleartextTrafficPermitted="false"/>$domain""")),
        )
        assertEquals(
            listOf("application must not set android:usesCleartextTraffic=\"true\""),
            violations(manifest("$secureApplication android:usesCleartextTraffic=\"true\"")),
        )
    }

    @Test
    fun `only the system certificate store is trusted`() {
        val system = """<trust-anchors><certificates src="system"/></trust-anchors>"""
        assertEquals(
            emptyList<String>(),
            violations(network = networkConfig("""<base-config cleartextTrafficPermitted="false">$system</base-config>""")),
        )
        val others = """<trust-anchors><certificates src="system"/><certificates src="user"/>""" +
            """<certificates src="@0x7f010000"/></trust-anchors>"""
        assertEquals(
            listOf(
                "network security config: trusts certificates from @0x7f010000",
                "network security config: trusts certificates from user",
            ),
            violations(network = networkConfig("""<base-config cleartextTrafficPermitted="false">$others</base-config>""")),
        )
    }

    @Test
    fun `pins, debug overrides, and turning off certificate transparency are refused`() {
        val base = """<base-config cleartextTrafficPermitted="false"><certificateTransparency enabled="false"/></base-config>"""
        val domain = """<domain-config><domain>example.org</domain>""" +
            """<pin-set><pin digest="SHA-256">AAAA</pin></pin-set></domain-config>"""
        assertEquals(
            listOf(
                "network security config: certificateTransparency must not be turned off",
                "network security config: debug-overrides is not allowed",
                "network security config: pin-set is not allowed",
            ),
            violations(network = networkConfig("$base$domain<debug-overrides/>")),
        )
    }

    @Test
    fun `security flags are literal true or false`() {
        val service = """<service android:name="dev.example.S" android:exported="@bool/t" android:isolatedProcess="@bool/t" />"""
        assertEquals(
            listOf(
                "application sets android:usesCleartextTraffic to @bool/t; it must be literally true or false",
                "dev.example.S sets android:exported to @bool/t; it must be literally true or false",
                "dev.example.S sets android:isolatedProcess to @bool/t; it must be literally true or false",
            ),
            violations(
                manifest("$secureApplication android:usesCleartextTraffic=\"@bool/t\"", components = service),
                allowed = listOf("service dev.example.S exported=@bool/t permission=none"),
            ),
        )
    }

    @Test
    fun `a domain allows cleartext unless the flag is literally false`() {
        // As aapt2 prints them: int 1, hex 0x1, a string, and a resource reference all turn cleartext on.
        listOf("1", "0x00000001", "yes", "@0x7f010000").forEach { value ->
            val domain = """<domain-config cleartextTrafficPermitted="$value"><domain>example.org</domain></domain-config>"""
            assertEquals(
                listOf("network security config: domain-config permits cleartext"),
                violations(network = networkConfig("""<base-config cleartextTrafficPermitted="false"/>$domain""")),
                value,
            )
        }
        val literal = """<domain-config cleartextTrafficPermitted="false"><domain includeSubdomains="1">example.org</domain></domain-config>"""
        assertEquals(
            listOf("network security config: domain includeSubdomains must be literally true or false"),
            violations(network = networkConfig("""<base-config cleartextTrafficPermitted="false"/>$literal""")),
        )
    }

    @Test
    fun `network settings not on the allow-list fail`() {
        val base = """<base-config cleartextTrafficPermitted="false" foo="bar"><trust-anchors>""" +
            """<certificates src="system" overridePins="true"/></trust-anchors><domainEncryption mode="off"/></base-config>"""
        assertEquals(
            listOf(
                "network security config: base-config foo is not allowed",
                "network security config: certificates overridePins is not allowed",
                "network security config: domainEncryption is not allowed",
            ),
            violations(network = networkConfig(base)),
        )
    }

    @Test
    fun `isolated processes, app zygotes, and backup agents are refused`() {
        val service = """<service android:name="dev.example.S" android:isolatedProcess="true" android:useAppZygote="true" />"""
        assertEquals(
            listOf(
                "application must not set android:backupAgent",
                "dev.example.S must not run in an isolated process",
                "dev.example.S must not use an app zygote",
            ),
            violations(
                manifest("$secureApplication android:backupAgent=\"lib.Agent\"", components = service),
                allowed = listOf("service dev.example.S exported=false permission=none"),
            ),
        )
    }

    @Test
    fun `components, permissions, and other entries must be listed exactly`() {
        val service = """<service android:name="dev.example.SyncService" android:exported="true"
            android:permission="android.permission.ACCOUNT_MANAGER" />"""
        val receiver = """<receiver android:name="lib.Receiver" />"""
        val metaData = """<meta-data android:name="lib.key" android:value="x" />"""
        val top = """
            <uses-permission android:name="android.permission.INTERNET" />
            <permission android:name="dev.example.LIB_PERMISSION" android:protectionLevel="signature" />
            <queries />
        """
        val listed = listOf(
            "service dev.example.SyncService exported=true permission=android.permission.ACCOUNT_MANAGER",
            "receiver lib.Receiver exported=false permission=none",
            "meta-data lib.key",
            "uses-permission android.permission.INTERNET",
            "permission dev.example.LIB_PERMISSION protectionLevel=signature",
            "queries",
        )
        val full = manifest(components = service + receiver + metaData, top = top)

        assertEquals(emptyList<String>(), violations(full, allowed = listed))
        assertEquals(listed.sorted().map { "unexpected manifest entry: $it" }, violations(full))
    }

    @Test
    fun `a listed entry that changed or vanished fails`() {
        val service = """<service android:name="dev.example.SyncService" android:exported="true" />"""
        val listed = "service dev.example.SyncService exported=true permission=android.permission.ACCOUNT_MANAGER"
        assertEquals(
            listOf(
                "listed entry is not in the manifest: $listed",
                "unexpected manifest entry: service dev.example.SyncService exported=true permission=none",
            ),
            violations(manifest(components = service), allowed = listOf(listed)),
        )
    }

    @Test
    fun `foreground service permissions are never allowed`() {
        val top = """<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />"""
        assertEquals(
            listOf("android.permission.FOREGROUND_SERVICE_DATA_SYNC is never allowed"),
            violations(manifest(top = top), allowed = listOf("uses-permission android.permission.FOREGROUND_SERVICE_DATA_SYNC")),
        )
    }

    @Test
    fun `play services and firebase entries are never allowed`() {
        val metaData = """<meta-data android:name="com.google.android.gms.version" android:value="1" />"""
        assertEquals(
            listOf("com.google.android.gms.version is a Play Services or Firebase entry"),
            violations(manifest(components = metaData), allowed = listOf("meta-data com.google.android.gms.version")),
        )
    }

    @Test
    fun `everything runs in the main process`() {
        val service = """<service android:name="dev.example.S" android:process=":sync" />"""
        assertEquals(
            listOf("dev.example.S must run in the main process, not :sync"),
            violations(manifest(components = service), allowed = listOf("service dev.example.S exported=false permission=none")),
        )
        assertEquals(
            listOf("application must run in the main process, not :other"),
            violations(manifest("$secureApplication android:process=\":other\"")),
        )
    }

    @Test
    fun `the release APK is neither debuggable nor test-only`() {
        fun apk(attributes: String) =
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application $attributes/></manifest>"""
        assertEquals(emptyList<String>(), releaseApkManifestViolations(apk("")))
        assertEquals(emptyList<String>(), releaseApkManifestViolations(apk("""android:debuggable="false" android:testOnly="false"""")))
        assertEquals(
            listOf("the release APK is debuggable", "the release APK is test-only"),
            releaseApkManifestViolations(apk("""android:debuggable="true" android:testOnly="true"""")),
        )
        assertEquals(listOf("the release APK is debuggable"), releaseApkManifestViolations(apk("""android:debuggable="@0x7f010000"""")))
    }

    @Test
    fun `the release APK carries native code for arm64-v8a only, the crypto library included`() {
        assertEquals(emptyList<String>(), releaseApkNativeCodeViolations(listOf("classes.dex", "lib/arm64-v8a/libgojni.so")))
        assertEquals(
            listOf("the release APK has no lib/arm64-v8a/libgojni.so (the crypto library)"),
            releaseApkNativeCodeViolations(listOf("classes.dex")),
        )
        assertEquals(
            listOf("the release APK has native code for another ABI: lib/x86/libgojni.so", "the release APK has native code for another ABI: lib/x86_64/libgojni.so"),
            releaseApkNativeCodeViolations(listOf("lib/arm64-v8a/libgojni.so", "lib/x86_64/libgojni.so", "lib/x86/libgojni.so")),
        )
        assertEquals(
            listOf(
                "the release APK has native code for another ABI: lib/armeabi-v7a/a.so",
                "the release APK has no lib/arm64-v8a/libgojni.so (the crypto library)",
            ),
            releaseApkNativeCodeViolations(listOf("lib/armeabi-v7a/a.so")),
        )
    }

    @Test
    fun `a document type declaration is refused`() {
        assertThrows<SAXParseException> {
            violations(extraction = """<!DOCTYPE x [<!ENTITY e "y">]><data-extraction-rules/>""")
        }
    }
}
