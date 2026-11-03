package dev.rboza.android.opensyncforproton

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.SyncResult
import android.os.Bundle
import android.os.Looper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.provider.ContactsContract
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The contacts sync service and its XML (docs/architecture.md § Android account): the service hands Android the one
 * adapter, with `sync_contacts.xml` and `contacts.xml`, whose edit schema lists exactly the data kinds of
 * docs/mapping.md § Field table with the types it carries both ways, and obeys the rules of the Contacts app's parser
 * (GrapheneOS platform_packages_apps_Contacts 5db1ee1, `BaseAccountType`, `ExternalAccountType`, `AccountTypeManager`):
 * a schema it rejects makes it drop the account's type and edit its contacts with a generic schema of every kind and
 * type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OspApp::class)
class SyncServiceTest {
    private val app = RuntimeEnvironment.getApplication() as OspApp

    @Test
    fun `Android finds the sync service, with both XML files`() {
        val services = app.packageManager.queryIntentServices(
            Intent(SYNC_ADAPTER).setPackage(app.packageName), PackageManager.GET_META_DATA,
        )
        val service = services.single().serviceInfo
        assertEquals(ContactsSyncService::class.java.name, service.name)
        assertEquals(R.xml.sync_contacts, service.metaData.getInt(SYNC_ADAPTER))
        // The Contacts app reads contacts.xml only from the sync service's meta-data.
        assertEquals(R.xml.contacts, service.metaData.getInt(CONTACTS_STRUCTURE))
    }

    @Test
    fun `the sync service binds the one adapter of the process`() {
        val bind = Intent(SYNC_ADAPTER)
        val first = Robolectric.buildService(ContactsSyncService::class.java, bind).create().get()
        val second = Robolectric.buildService(ContactsSyncService::class.java, bind).create().get()
        assertNotNull(first.onBind(bind))
        assertSame(first.onBind(bind), second.onBind(bind))
    }

    @Test
    fun `the adapter reconciles before its run`() = runBlocking {
        recordAtStart("a1", NAME)
        app.setSyncMode(AccountId("a1"), SyncMode.ON)
        addAndroidAccount("a2", OTHER_NAME)

        val result = SyncResult()
        app.contactsSyncAdapter.onPerformSync(Account(NAME, app.packageName), Bundle(), ContactsContract.AUTHORITY, null, result)

        assertEquals(setOf("a1", "a2"), app.accounts().map { it.accountId }.toSet())
        // No credentials: the run itself stops signed out, which Android hears as an auth error.
        assertEquals(1L, result.stats.numAuthExceptions)
    }

    @Test
    fun `the contacts adapter uploads too, one sync at a time, and isn't syncable until sync is on`() {
        val adapter = element(R.xml.sync_contacts)
        assertEquals("sync-adapter", adapter.tag)
        assertEquals(ContactsContract.AUTHORITY, adapter.android["contentAuthority"])
        assertEquals(app.packageName, adapter.android["accountType"])
        assertEquals("true", adapter.android["userVisible"])
        // Android asks for an upload after a phone edit.
        assertEquals("true", adapter.android["supportsUploading"])
        assertEquals("false", adapter.android["allowParallelSyncs"])
        assertEquals("false", adapter.android["isAlwaysSyncable"])
        assertEquals(setOf("contentAuthority", "accountType", "userVisible", "supportsUploading", "allowParallelSyncs", "isAlwaysSyncable"), adapter.android.keys)
    }

    @Test
    fun `nothing about calendars`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.any { it == Manifest.permission.READ_CALENDAR || it == Manifest.permission.WRITE_CALENDAR })
        val adapters = app.packageManager.queryIntentServices(Intent(SYNC_ADAPTER).setPackage(app.packageName), PackageManager.GET_META_DATA)
        assertEquals(listOf(ContactsContract.AUTHORITY), adapters.map { element(it.serviceInfo.metaData.getInt(SYNC_ADAPTER)).android["contentAuthority"] })
    }

    @Test
    fun `the edit schema obeys the Contacts app's rules`() {
        assertEquals(emptyList<String>(), contactsAppViolations(schema()))
    }

    @Test
    fun `the edit schema lists exactly mapping md's data kinds, with the types mapping md carries`() {
        assertEquals(EXPECTED, schema())
    }

    private fun addAndroidAccount(protonId: String, name: String) {
        AccountManager.get(app).addAccountExplicitly(Account(name, app.packageName), null, Bundle().apply { putString("proton_user_id", protonId) })
    }

    /** Adds an Android account the app has no record of, starts the app again, and waits for that reconciliation. */
    private suspend fun recordAtStart(protonId: String, name: String) {
        awaitReconciliation()
        addAndroidAccount(protonId, name)
        app.onCreate()
        awaitReconciliation()
        assertTrue("no record after start-up reconciliation", app.accounts().any { it.accountId == protonId })
    }

    private fun awaitReconciliation() {
        val job = checkNotNull(app.reconciling)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("reconciliation still running", job.isCompleted)
    }

    /** One XML element: its tag, its android: attributes (resolved), its plain attributes, and its child elements. */
    private data class Element(val tag: String, val android: Map<String, String>, val plain: Map<String, String>, val children: List<Element>)

    private fun element(xml: Int): Element {
        val parser = app.resources.getXml(xml)
        while (parser.next() != XmlPullParser.START_TAG) Unit
        return read(parser)
    }

    private fun read(parser: XmlResourceParser): Element {
        val tag = parser.name
        val android = mutableMapOf<String, String>()
        val plain = mutableMapOf<String, String>()
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            when (parser.getAttributeNamespace(i)) {
                ANDROID -> android[name] = parser.getAttributeResourceValue(i, 0).let { if (it != 0) app.getString(it) else parser.getAttributeValue(i) }
                "" -> plain[name] = parser.getAttributeValue(i)
                else -> error("unexpected namespace on $tag")
            }
        }
        val children = mutableListOf<Element>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> children += read(parser)
                XmlPullParser.END_TAG -> return Element(tag, android, plain, children)
                XmlPullParser.TEXT -> check(parser.isWhitespace) { "text in $tag" }
            }
        }
    }

    /** The edit schema as `kind → (its attributes, its types → their attributes)`, after checking the outer tags. */
    private data class Kind(val attributes: Map<String, String>, val types: Map<String, Map<String, String>>)

    private fun schema(): Map<String, Kind> {
        val root = element(R.xml.contacts)
        assertEquals("ContactsAccountType", root.tag)
        assertTrue(root.android.isEmpty() && root.plain.isEmpty())
        val editSchema = root.children.single()
        assertEquals("EditSchema", editSchema.tag)
        val kinds = linkedMapOf<String, Kind>()
        for (dataKind in editSchema.children) {
            assertEquals("DataKind", dataKind.tag)
            assertTrue(dataKind.android.isEmpty())
            val name = checkNotNull(dataKind.plain["kind"]) { "DataKind without kind" }
            check(name !in kinds) { "$name listed twice" }
            val types = linkedMapOf<String, Map<String, String>>()
            for (type in dataKind.children) {
                assertEquals("Type", type.tag)
                val typeName = checkNotNull(type.plain["type"]) { "Type without type in $name" }
                check(typeName !in types) { "$name type $typeName listed twice" }
                types[typeName] = type.plain - "type"
            }
            kinds[name] = Kind(dataKind.plain - "kind", types)
        }
        return kinds
    }

    /**
     * The checks the Contacts app makes before it accepts the edit schema (on any failure it uses a generic one): known
     * kinds and types; the name kind with every part supported; a photo kind; one of each of the single kinds; at least
     * one type for a typed kind and none for the others. Stricter than the app: only attributes it reads (it ignores
     * others), so a misspelt one is caught.
     */
    private fun contactsAppViolations(kinds: Map<String, Kind>): List<String> {
        val found = mutableListOf<String>()
        for ((name, kind) in kinds) {
            val allowedTypes = CONTACTS_APP_TYPES[name]
            if (name !in CONTACTS_APP_KINDS) {
                found += "unknown kind $name"
                continue
            }
            if (name in SINGLE_KINDS && kind.attributes["maxOccurs"] != "1") found += "$name must have maxOccurs=1"
            if (allowedTypes == null) {
                if (kind.types.isNotEmpty()) found += "$name can't have types"
            } else {
                if (kind.types.isEmpty()) found += "$name needs a type"
                (kind.types.keys - allowedTypes).forEach { found += "$name has unknown type $it" }
            }
            val known = setOf("maxOccurs") + KIND_ATTRIBUTES[name].orEmpty()
            (kind.attributes.keys - known).forEach { found += "$name has unknown attribute $it" }
            kind.types.forEach { (type, attributes) ->
                (attributes.keys - setOf("maxOccurs") - TYPE_ATTRIBUTES[name].orEmpty()).forEach { found += "$name type $type has unknown attribute $it" }
            }
        }
        val nameKind = kinds["name"]
        if (nameKind == null) {
            found += "name must be supported"
        } else {
            NAME_PARTS.filter { nameKind.attributes[it] != "true" }.forEach { found += "name must set $it=true" }
        }
        if ("photo" !in kinds) found += "photo must be supported"
        return found
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val NAME = "someone.made.up@example.test"
        const val OTHER_NAME = "someone.else.made.up@example.test"
        const val SYNC_ADAPTER = "android.content.SyncAdapter"
        const val CONTACTS_STRUCTURE = "android.provider.CONTACTS_STRUCTURE"

        val NAME_PARTS = listOf(
            "supportsPrefix", "supportsMiddleName", "supportsSuffix",
            "supportsPhoneticFamilyName", "supportsPhoneticMiddleName", "supportsPhoneticGivenName",
        )

        /** The Contacts app's kind parsers (`BaseAccountType.KindParser`). */
        val CONTACTS_APP_KINDS = setOf(
            "name", "nickname", "phone", "email", "postal", "im", "organization", "photo", "note", "website",
            "sip_address", "group_membership", "event", "relationship",
        )

        /** Kinds it allows only once (`throwIfList`). */
        val SINGLE_KINDS = setOf("name", "nickname", "organization", "photo", "note", "sip_address", "group_membership")

        /** The typed kinds and the type names each one knows. */
        val CONTACTS_APP_TYPES = mapOf(
            "phone" to setOf(
                "home", "mobile", "work", "fax_work", "fax_home", "pager", "other", "callback", "car", "company_main",
                "isdn", "main", "other_fax", "radio", "telex", "tty_tdd", "work_mobile", "work_pager", "assistant", "mms",
                "custom",
            ),
            "email" to setOf("home", "work", "other", "mobile", "custom"),
            "postal" to setOf("home", "work", "other", "custom"),
            "im" to setOf("aim", "msn", "yahoo", "skype", "qq", "google_talk", "icq", "jabber", "custom"),
            "event" to setOf("birthday", "anniversary", "other", "custom"),
            "relationship" to setOf(
                "assistant", "brother", "child", "domestic_partner", "father", "friend", "manager", "mother", "parent",
                "partner", "referred_by", "relative", "sister", "spouse", "custom",
            ),
        )

        /** Attributes it reads on a kind, beyond maxOccurs. */
        val KIND_ATTRIBUTES = mapOf("name" to NAME_PARTS.toSet(), "postal" to setOf("needsStructured"), "event" to setOf("dateWithTime"))

        /** Attributes it reads on a type, beyond maxOccurs. */
        val TYPE_ATTRIBUTES = mapOf("event" to setOf("yearOptional"))

        private fun kind(vararg attributes: Pair<String, String>, types: Map<String, Map<String, String>> = emptyMap()) =
            Kind(mapOf(*attributes), types)

        private fun types(vararg names: String) = names.associateWith { emptyMap<String, String>() }

        /**
         * docs/mapping.md § Field table's kinds, with the types its tables carry both ways: no phone type that uploads
         * without its TYPE, no e-mail MOBILE, no custom address (it reads back as OTHER); events and the first birthday
         * and anniversary as docs/mapping.md reads them, with or without a year.
         */
        val EXPECTED = mapOf(
            "name" to kind("maxOccurs" to "1", *NAME_PARTS.map { it to "true" }.toTypedArray()),
            "photo" to kind("maxOccurs" to "1"),
            "phone" to kind(types = types("home", "mobile", "work", "fax_work", "fax_home", "pager", "other", "main", "custom")),
            "email" to kind(types = types("home", "work", "other", "custom")),
            "postal" to kind("needsStructured" to "true", types = types("home", "work", "other")),
            "organization" to kind("maxOccurs" to "1"),
            "event" to kind(
                types = mapOf(
                    "birthday" to mapOf("maxOccurs" to "1", "yearOptional" to "true"),
                    "anniversary" to mapOf("maxOccurs" to "1", "yearOptional" to "true"),
                    "other" to mapOf("yearOptional" to "true"),
                    "custom" to mapOf("yearOptional" to "true"),
                ),
            ),
            "note" to kind("maxOccurs" to "1"),
            "website" to kind(),
            "nickname" to kind("maxOccurs" to "1"),
            "relationship" to kind(
                types = types(
                    "assistant", "brother", "child", "domestic_partner", "father", "friend", "manager", "mother", "parent",
                    "partner", "referred_by", "relative", "sister", "spouse", "custom",
                ),
            ),
            "sip_address" to kind("maxOccurs" to "1"),
        )
    }
}
