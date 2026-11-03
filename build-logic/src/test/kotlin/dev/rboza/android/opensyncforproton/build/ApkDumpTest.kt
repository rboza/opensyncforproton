package dev.rboza.android.opensyncforproton.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Inputs are real `aapt2` 37.0.0 output from release builds of this app, with extra test XML. */
class ApkDumpTest {
    @Test
    fun `the resource table gives every version of each xml resource`() {
        val table = """
            Binary APK
            Package name=dev.rboza.android.opensyncforproton id=7f
              type raw id=01 entryCount=1
                resource 0x7f010000 raw/ca
                  () (file) res/Np.pem
              type string id=02 entryCount=1
                resource 0x7f020000 string/app_name
                  () "OpenSync for Proton"
              type xml id=03 entryCount=2
                resource 0x7f030000 xml/data_extraction_rules
                  (night) (file) res/x4.xml type=XML
                resource 0x7f030001 xml/network_security_config
                  () (file) res/8G.xml type=XML
                  (v38) (file) res/9S.xml type=XML
        """.trimIndent()

        assertEquals(
            mapOf(
                "data_extraction_rules" to listOf(ResourceFile("night", "res/x4.xml")),
                "network_security_config" to listOf(ResourceFile("", "res/8G.xml"), ResourceFile("v38", "res/9S.xml")),
            ),
            xmlResourceFiles(table),
        )
    }

    @Test
    fun `an alias is a version without a file`() {
        val table = """
            Binary APK
            Package name=dev.rboza.android.opensyncforproton id=7f
              type bool id=01 entryCount=1
                resource 0x7f010000 bool/t
                  () true
              type xml id=03 entryCount=2
                resource 0x7f030001 xml/evil_nsc
                  () (file) res/Wv.xml type=XML
                resource 0x7f030002 xml/network_security_config
                  () (file) res/8G.xml type=XML
                  (night) @xml/evil_nsc
        """.trimIndent()

        assertEquals(
            listOf(ResourceFile("", "res/8G.xml"), ResourceFile("night", null)),
            xmlResourceFiles(table)["network_security_config"],
        )
    }

    @Test
    fun `an unrecognised line under an xml resource fails`() {
        val table = """
            type xml id=03 entryCount=1
              resource 0x7f030002 xml/network_security_config
                () (file) res/8G.xml type=XML
                (night) "not a file or an alias"
        """.trimIndent()
        assertThrows<IllegalArgumentException> { xmlResourceFiles(table) }
    }

    @Test
    fun `an attribute value is taken only when it matches its raw form`() {
        assertEquals("""<a path="a\b"/>""", xmltreeToXml("""E: a (line=1)""" + "\n" + """  A: path="a\b" (Raw: "a\b")"""))
        // The whole value is `user" (Raw: "system`; it must not be read as `system`.
        val tricky = """  A: src="user" (Raw: "system" (Raw: "user" (Raw: "system")"""
        assertEquals("""<a src="user&quot; (Raw: &quot;system"/>""", xmltreeToXml("E: a (line=1)\n$tricky"))
        assertThrows<IllegalArgumentException> { xmltreeToXml("E: a (line=1)\n  A: src=\"user\" (Raw: \"system\")") }
        assertThrows<IllegalArgumentException> { xmltreeToXml("E: a (line=1)\n  A: src=\"user\"") }
    }

    @Test
    fun `an xmltree dump becomes the same XML`() {
        val dump = """
            E: network-security-config (line=2)
                E: base-config (line=3)
                  A: cleartextTrafficPermitted=false
                    E: trust-anchors (line=4)
                        E: certificates (line=5)
                          A: src="system" (Raw: "system")
                        E: certificates (line=6)
                          A: overridePins=true
                          A: src="user" (Raw: "user")
                        E: certificates (line=7)
                          A: src=@0x7f010000
                    E: certificateTransparency (line=9)
                      A: enabled=false
                E: domain-config (line=11)
                  A: cleartextTrafficPermitted=true
                    E: domain (line=12)
                      A: includeSubdomains=true
                        T: 'example.org & \"q\" <x>'
                    E: pin-set (line=13)
                      A: expiration="2030-01-01" (Raw: "2030-01-01")
                        E: pin (line=14)
                          A: digest="SHA-256" (Raw: "SHA-256")
                            T: '7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y='
                E: debug-overrides (line=17)
        """.trimIndent()

        assertEquals(
            """<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors>""" +
                """<certificates src="system"/><certificates overridePins="true" src="user"/>""" +
                """<certificates src="@0x7f010000"/></trust-anchors><certificateTransparency enabled="false"/>""" +
                """</base-config><domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="true">""" +
                """example.org &amp; "q" &lt;x&gt;</domain><pin-set expiration="2030-01-01"><pin digest="SHA-256">""" +
                """7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y=</pin></pin-set></domain-config><debug-overrides/>""" +
                """</network-security-config>""",
            xmltreeToXml(dump),
        )
    }

    @Test
    fun `android attributes keep their namespace`() {
        val dump = """
            N: android=http://schemas.android.com/apk/res/android (line=2)
              E: manifest (line=2)
                A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="0.1.0" (Raw: "0.1.0")
                A: package="dev.example" (Raw: "dev.example")
                  E: application (line=12)
                    A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        """.trimIndent()

        assertEquals(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="0.1.0" """ +
                """package="dev.example"><application android:allowBackup="false"/></manifest>""",
            xmltreeToXml(dump),
        )
    }

    @Test
    fun `anything unexpected in a dump fails`() {
        assertThrows<IllegalArgumentException> { xmltreeToXml("E: a (line=1)\n  X: something") }
        assertThrows<IllegalArgumentException> { xmltreeToXml("E: a (line=1)\n  A: http://example.org/ns:b(0x01)=1") }
        assertThrows<IllegalArgumentException> { xmltreeToXml("E: a (line=1)\nE: b (line=2)") }
    }
}
