package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The scheduler (docs/sync.md § Scheduling). Signed in with sync on and Manual off: one periodic job per account at the
 * check's period, network required, and the contacts authority syncable; Manual, dry run, signed out, or no settings:
 * no job. Only an explicit choice changes Android's automatic sync. Every value is made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager, TYPE)
    }

    private fun record(account: String, state: LoginState = LoginState.ACTIVE) = AccountRecordEntity(account, "$account@example.test", state)

    private fun android(account: String = "user-id-1") = Account("$account@example.test", TYPE)

    private fun syncable(account: String = "user-id-1") = ContentResolver.getIsSyncable(android(account), ContactsContract.AUTHORITY)

    private fun automatic(account: String = "user-id-1") = ContentResolver.getSyncAutomatically(android(account), ContactsContract.AUTHORITY)

    private fun jobs(account: String = "user-id-1"): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.workName(AccountId(account))).get().filterNot { it.state.isFinished }

    @Test
    fun syncOnSchedulesOnePeriodicJobAtTheChecksPeriodWhenOnline() {
        // The job is the check, at its own period whatever the full sync's, or the full sync's under Same as full sync.
        for (full in SyncScheduler.INTERVALS) {
            for (check in SyncScheduler.INTERVALS.filter { it <= full } + null) {
                scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = full, checkMinutes = check))

                val job = jobs().single()
                assertEquals(WorkInfo.State.ENQUEUED, job.state)
                assertEquals("$full $check", TimeUnit.MINUTES.toMillis((check ?: full).toLong()), job.periodicityInfo?.repeatIntervalMillis)
                assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
            }
        }
    }

    @Test
    fun theJobIsNamedForItsAccountCarriesItAndBacksOffFromFiveMinutes() {
        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))

        // The stored job, found by its literal name, as WorkManager will run it.
        val job = workManager.getWorkInfosForUniqueWork("sync.periodic.user-id-1").get().single()
        val spec = checkNotNull(WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(job.id.toString()))
        assertEquals("user-id-1", spec.input.getString(SyncWorker.ACCOUNT_ID))
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(5), spec.backoffDelayDuration)
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun choosingTurnsAndroidsAutomaticSyncOnBeforeTheJob() {
        // If the job's step fails (here: an interval that isn't a choice), automatic sync is already on, so a crash
        // can't leave sync on with Android's automatic sync never turned on.
        assertThrows(IllegalArgumentException::class.java) {
            scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 45))
        }

        assertTrue(automatic())
    }

    @Test
    fun choosingAnotherIntervalOrCheckKeepsOneJobAtTheNewPeriod() {
        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
        val before = jobs().single().id

        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 120, checkMinutes = 30))
        assertEquals(before, jobs().single().id)
        assertEquals(TimeUnit.MINUTES.toMillis(30), jobs().single().periodicityInfo?.repeatIntervalMillis)

        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 120, checkMinutes = null))
        val job = jobs().single()
        assertEquals(before, job.id)
        assertEquals(TimeUnit.MINUTES.toMillis(120), job.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun manualDryRunAndNoSettingsCancelTheJob() {
        val off = listOf(
            SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60, manual = true),
            SettingsEntity("user-id-1", SyncMode.DRY_RUN, intervalMinutes = 60),
            null,
        )
        for (settings in off) {
            scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
            assertEquals("$settings: back on after a cancel", 1, jobs().size)

            scheduler.reconcile(record("user-id-1"), settings)

            assertEquals("$settings", emptyList<WorkInfo>(), jobs())
        }
    }

    @Test
    fun eachAccountHasItsOwnJob() {
        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
        scheduler.reconcile(record("user-id-2"), SettingsEntity("user-id-2", SyncMode.ON, intervalMinutes = 60))

        scheduler.reconcile(record("user-id-2"), null)

        assertEquals(1, jobs("user-id-1").size)
        assertEquals(0, jobs("user-id-2").size)
    }

    @Test
    fun signingOutCancelsTheJobAndTheAuthorityIsntSyncable() {
        scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
        assertEquals(1, syncable())

        scheduler.reconcile(record("user-id-1", LoginState.SIGNED_OUT), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))

        assertEquals(emptyList<WorkInfo>(), jobs())
        assertEquals(0, syncable())
    }

    @Test
    fun theAuthorityIsSyncableOnlyWhileSignedInWithSyncOnManualIncluded() {
        val cases = listOf(
            SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60) to 1,
            SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60, manual = true) to 1,
            SettingsEntity("user-id-1", SyncMode.DRY_RUN, intervalMinutes = 60) to 0,
            null to 0,
        )
        for ((settings, expected) in cases) {
            scheduler.reconcile(record("user-id-1"), settings)

            assertEquals("$settings", expected, syncable())
        }
    }

    @Test
    fun reconcilingNeverChangesAndroidsAutomaticSync() {
        for (switch in listOf(false, true)) {
            ContentResolver.setSyncAutomatically(android(), ContactsContract.AUTHORITY, switch)

            scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
            scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60, manual = true))

            assertEquals("$switch", switch, automatic())
        }
    }

    @Test
    fun choosingWithManualOffTurnsAutomaticSyncOnAndManualTurnsItOff() {
        scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60))
        assertEquals(listOf(true, 1, 1), listOf(automatic(), syncable(), jobs().size))

        scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60, manual = true))
        assertEquals(listOf(false, 1, 0), listOf(automatic(), syncable(), jobs().size))

        scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 120, manual = true))
        assertEquals("an interval chosen under Manual", listOf(false, 1, 0), listOf(automatic(), syncable(), jobs().size))

        scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 120))
        assertEquals(listOf(true, 1, 1), listOf(automatic(), syncable(), jobs().size))
    }

    @Test
    fun choosingAnIntervalInDryRunSchedulesNothing() {
        scheduler.choose(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.DRY_RUN, intervalMinutes = 60))

        assertEquals(listOf(true, 0, 0), listOf(automatic(), syncable(), jobs().size))
    }

    @Test
    fun onlyTheListedIntervalsAndACheckNoLongerThanTheFullSyncAreAccepted() {
        for (minutes in listOf(15, 30, 60, 120, 360, 720, 1440)) {
            scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = minutes, checkMinutes = null))
        }
        for (minutes in listOf(0, 14, 45, 2880)) {
            assertThrows("$minutes", IllegalArgumentException::class.java) {
                scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = minutes, checkMinutes = null))
            }
            assertThrows("check $minutes", IllegalArgumentException::class.java) {
                scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 1440, checkMinutes = minutes))
            }
        }
        assertThrows("a check longer than the full sync", IllegalArgumentException::class.java) {
            scheduler.reconcile(record("user-id-1"), SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60, checkMinutes = 120))
        }
    }

    private companion object {
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
