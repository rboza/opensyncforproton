# Decisions

Each entry is a choice that still holds and the reason for it. IDs are stable:
code and commits refer to them, and the ID of a decision no longer in force
(D15) isn't reused. Behaviour that follows from a decision is specified in
the file that owns it.

## Platform and distribution

**D1 — One device class, on the current Android.** `minSdk`, `targetSdk`,
and `compileSdk` 37 (Android 17), the version current GrapheneOS is built on;
native code for `arm64-v8a`, plus `x86_64` only for emulator tests. One JDK,
25, runs the build and the tests; the code compiles to Java 17 bytecode, and
the plain JVM modules may call only Java 17's library (`-Xjdk-release=17`).
When GrapheneOS moves to a new Android version, the next release raises all
three SDK levels after checking that version's behaviour changes. *Why:* the
only target is a Pixel on the latest GrapheneOS; older API levels add testing
cost and no value.

**D2 — Obtainium from GitHub releases, signed locally.** A local script
builds and signs each release ([releasing.md](releasing.md)); it is published
by hand. The signing key never leaves the maintainer's machine and has one
offline backup. No Google Play or its libraries, Play Services, Firebase,
ML Kit, or crash/analytics SDKs. *Why:* the simplest update path for one
phone; a key stored on GitHub would let a compromised account push an update
to a phone that holds contacts and a Proton session.

**D3 — GPL-3.0-or-later.** *Why:* the crypto library (`crypto-android`) is
GPL. The app carries the GPL's text and every bundled library's licence.

**D4 — Identity.**

| Item | Value | Can change? |
| --- | --- | --- |
| App ID | `dev.rboza.android.opensyncforproton` | **No**: a new ID is a different app |
| Android account type | `dev.rboza.android.opensyncforproton` | **No**: synced rows belong to it |
| App name / launcher label | OpenSync for Proton / OSP | Yes |
| vCard `PRODID` of contacts it creates | `-//OpenSync for Proton//Contacts <versionName>//EN` | Yes |
| `x-pm-appversion` | `Other`: the one non-impersonating value Proton accepts; replaced if Proton names an identifier for third-party clients | Yes |
| Debug and test builds | App ID and account type get the suffix `.debug` | Yes |

Debug builds run only on the emulator, and only they allow screenshots.
Instrumented tests use only the `.debug` account type, except tests of the
phone's local account, which create their own contacts there and delete
exactly those by ID. The app and README say "not affiliated with Proton AG"
and never use Proton's logo or brand colours; the login screen says it is a
third-party app; the client ID is never an official Proton app's. *Why:*
impersonating an official client puts the user's Proton account at risk.

**D27 — Developer tools through mise.** `mise.toml` pins exact versions of
the JDK, the Android SDK command-line tools, and Go; a mise task installs the
SDK packages at pinned versions; Gradle comes from the wrapper. Nothing is
installed globally. *Why:* one command sets up a machine, CI matches it, and
tool versions change only in review.

## Protocol and crypto

**D5 — Proton's HTTP API, not DAV.** Host `https://mail-api.proton.me/`.
*Why:* Proton offers no CardDAV.

**D6 — Reuse Proton's crypto; never reimplement it.** proton-core
`crypto-android` (GopenPGP and go-srp) works standalone for SRP, key unlock,
and contact cards. Not proton-core `key`, which pulls in Proton's account,
network, and payment modules and Hilt. If `crypto-android` ever fails, the
fallback is a thin gomobile library over pinned GopenPGP v3 and go-srp; never
a Kotlin port or a non-Proton OpenPGP stack. *Why:* the crypto must match
Proton's exactly, and the official code is tested against it.

**D7 — Own HTTP client; no Proton Core app framework.** OkHttp, Retrofit,
kotlinx.serialization. *Why:* Proton Core brings a whole app framework (DI
graph, account UI) for about twenty endpoints.

**D8 — Fail closed on security.** Signature, SRP modulus, server proof, and
Keystore failures reject the operation; there is no plaintext, unsigned, or
"best effort" fallback. Second factor is TOTP only (FIDO2-only accounts are
refused). v6 and post-quantum keys are refused. TLS uses the platform CA store
with Certificate Transparency, and no custom pins. *Why:* never trade data or
credentials for convenience.

## Structure

**D9 — Six Gradle modules** with enforced one-way dependencies
([architecture.md § Modules](architecture.md#modules)). `:proton` never
depends on `:crypto`; `:mapping` and `:proton` are plain JVM. *Why:* mapping
and HTTP tests run fast on the JVM, and a token refresh can never touch
private keys.

**D10 — Manual constructor injection** from one composition root in `:app`;
no Hilt. *Why:* few entry points, and never two DI systems.

## Data and sync

**D11 — Android's Contacts provider is the local store.** Contacts live in
`ContactsContract` under the app's own account type. Room holds only sync
metadata. *Why:* every app on the phone can use the contacts, and offline
use works by default.

**D12 — One Android account per Proton account,** keyed by Proton `User.ID`;
one signed in at a time. *Why:* one place to see and remove the data, and
`User.ID` doesn't change when a username or address does.

**D13 — Provider rows carry the Proton ID.** `RawContacts.SOURCE_ID` holds
it, so a crash between a provider write and a Room write is repaired without
duplicates. *Why:* provider and Room writes can't share a transaction.

**D14 — Change detection.** Remote change: the contact's content fingerprint
differs from Room's baseline (Proton's export has no modify time). Local
change: provider `DIRTY` / `DELETED`. `VERSION` only guards against an edit
during a write. Clocks are never compared. *Why:* server times have one-second
resolution, and device clocks drift.

**D16 — Non-destructive by default.** Logging out and turning sync off freeze
the phone's contacts. Contacts leave the phone only through the confirmed
"Remove from this phone", or by removing the account in Android Settings.

**D17 — Hold and ask on conflict.** No last-writer-wins and no automatic
merge of edits made on both sides. *Why:* the simplest safe rule.

**D18 — Never resend a write automatically.** No HTTP-level retry of a
request that changes Proton data. An ambiguous create is resolved through a
pending-create record holding the contact's UID; an ambiguous update or
delete by reading the contact again. *Why:* blind retries duplicate or lose
data.

**D19 — An incomplete inventory never deletes anything.** *Why:* a failed
page must not look like remote deletions.

**D20 — Scheduling.** Automatic runs happen only with sync on. A WorkManager
periodic job (network required) is the automatic trigger; the SyncAdapter
registers the contacts authority, runs syncs started from Android Settings,
and uploads after phone edits. Every trigger goes through one per-account
coordinator that runs one run at a time. No foreground service, no exact
alarms. *Why:* WorkManager copes with Doze, and the SyncAdapter makes Android
treat the app as a sync account.

**D21 — Contact groups and Proton labels are out of scope.** `CATEGORIES` is
kept on upload but not mapped. *Why:* the label lifecycle isn't specified.

**D25 — Dry run first, one plan for both modes.** After login the account is
in dry run. A dry run and a real sync compute the same plan
([sync.md § The plan](sync.md#the-plan)); a sync carries it out, a dry run
only reports it. *Why:* the dry-run report is exactly what a sync would do,
with one code path to test.

**D26 — Import copies into the app's account and reuses the upload path.**
Importing inserts a copy of a phone contact into the app's account as a new
phone contact, and a Merge choice is written into the matching row as an
ordinary app's edit; the normal sync uploads both. Originals are never
modified, and are deleted only by the confirmed "Remove originals". *Why:*
no second way of writing to Proton, so import gets pending-create recovery
and conflict handling for free.

**D28 — Each data row names the remote property it came from.** Every data
row the app writes holds, in `Data.SYNC1`, a key of the vCard property (or
properties) it was mapped from, so an upload pairs each phone row with its
property and keeps that property's hidden parts
([mapping.md § Keys](mapping.md#keys)). *Why:* an edit in place keeps the
column, a row added on the phone has none, and a removed row is gone, so
pairing needs no Room table and doesn't depend on row order.

## Storage and privacy

**D22 — Credentials in one Keystore-encrypted file per account.** An
AES-256-GCM envelope (AndroidKeyStore key) in no-backup storage holds the
tokens, key salts, the locked key bundle, and the derived key passphrases;
never the typed password and never unlocked keys. Backup and device transfer
are off. *Why:* background sync needs the passphrases, and nothing secret
should leave the phone.

**D23 — Diagnostics without personal data.** Logs are typed records (enums,
counts, durations) with IDs replaced by a per-install keyed hash; no free
text, bodies, or exception messages. Stored on the phone; exported with the
system file picker
([architecture.md § Diagnostics](architecture.md#diagnostics)). *Why:* logs
must be safe to share while debugging.

**D24 — Pinned dependencies.** Exact versions in `gradle/libs.versions.toml`,
strict Gradle dependency locking for every configuration (`build-logic`
included), and dependency verification metadata; only stable releases. The
build fails on a settings classpath holding anything but `build-logic`, a
`buildSrc` directory, a script plugin, or a file dependency, since Gradle
can't lock those. After an approved change, `mise run lock` rewrites the locks
and checksums; it runs in a fresh clone with an empty `GRADLE_USER_HOME`, so
the checksums cover what a cold cache resolves. *Why:* the app holds credentials, so dependency changes must
show up in review.
