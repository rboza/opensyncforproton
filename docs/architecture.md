# Architecture

How the app is built: modules, storage, threading, and security controls.
Behaviour is in [sync.md](sync.md) and [protocol.md](protocol.md).

## Modules

| Module | Kind | Owns | May depend on |
| --- | --- | --- | --- |
| `:app` | Android app | Compose UI, captcha WebView, notifications, manifest, diagnostics log, composition root | every module |
| `:sync` | Android library | Planner, engine, coordinator, account lifecycle, SyncAdapter, WorkManager job, provider I/O, column mapper, Room | `:account`, `:proton`, `:crypto`, `:mapping` |
| `:account` | Android library | Login flow, credential store, AccountManager access, authenticator | `:proton`, `:crypto` |
| `:proton` | JVM library | HTTP client, DTOs, token refresh, retries, inventory, fingerprints | — |
| `:crypto` | Android library | Thin wrapper over Proton's crypto: SRP, key unlock, cards, SecretBox | — |
| `:mapping` | JVM library | vCard parsing and patching (ez-vcard, vinnie) | — |

`checkModuleRules` (from the `build-logic` included build) enforces this
table on every module's classpaths and sources. It fails the build if a
module depends on one not listed for it, isn't in the table, or (for
`:proton` and `:mapping`) applies an Android plugin, has Android jars on its
classpath, or mentions `android.*`; and if anything depends on Google Play,
Play Services, Firebase, ML Kit, or Proton Core's auth, account, or network
modules, on a file dependency, or calls `AccountManager.renameAccount`
(Android treats a rename as a removal and deletes the account's rows).

Stack: OkHttp, Retrofit, kotlinx.serialization; proton-core `crypto-android`;
ez-vcard; Room; WorkManager; Jetpack Compose with Material 3 and the app's own
violet scheme; manual constructor injection; AndroidKeyStore AES-256-GCM for
secrets. Versions are pinned (D24).

## Storage

Room database `sync.db`, schema exported under `sync/schemas/`, migrations
tested from every released version. Tables hold IDs, fingerprints, settings,
and counts; never contact content or names.

| Table | Holds |
| --- | --- |
| `account_record` | `User.ID`, Android account name, `login_state` |
| `settings` | Sync mode, full-sync and check intervals, Manual, upgrade notice |
| `last_report` | The last report's counts per side, blocker, what started it, times |
| `contact_map` | Proton ID → raw contact ID, fingerprint, `rows_keyed`, photo and thumbnail baselines |
| `pending_contact_create` | Raw contact ID, UID, `VERSION` of a create not yet bound |
| `conflict_hold` | A held contact's kind, the state it was held in, and the stored choice |
| `event_cursor` | Event-loop cursor and `last_full_sync_at` |
| `import_record` | Original's raw ID and `VERSION`, copy's raw ID, whether the photo was copied |

The credential envelope (D22) and the diagnostics log are files in no-backup
storage.

## Threading

Runs go through the per-account coordinator on `Dispatchers.IO`; SRP and
bcrypt run on `Default`; nothing blocking runs on `Main`.
`SyncAdapter.onSyncCanceled` and `Worker.onStopped` cancel their request; a
run stops once no caller waits for it. `CancellationException` is never
swallowed, and unlocked keys close through `use {}` on every path. Logout,
turning sync off, and removal change the account while no run can start.

## Security

| Asset | Risk | Control |
| --- | --- | --- |
| Synced contacts | Plaintext in the Contacts provider, readable and editable by apps with contacts permission (their edits upload) | Phone lock; the app's own account; grant contacts permission only to trusted apps; GrapheneOS Contact Scopes for others |
| Tokens, locked keys, passphrases | Extraction from a compromised, unlocked phone | Keystore-sealed envelope in no-backup storage; no backup or transfer; unlocked keys never stored |
| Typed passwords, code, captcha token | Memory, screenshots | Never saved (no saved state or navigation arguments); cleared on every login step; password arrays zeroed; `FLAG_SECURE` |
| Network | Man-in-the-middle | TLS with platform CAs and Certificate Transparency; SRP signed modulus and server proof; no redirects; no cleartext |
| Release APK | Malicious update | Signed locally; the key is never on GitHub (D2) |

**Keystore key:** AES-256-GCM, with every IV picked by the Keystore. It needs
neither user authentication nor an unlocked device, so scheduled syncs run
while the phone is locked (after the first unlock since boot). A sealed
secret is a 12-byte IV, then the ciphertext and its 128-bit tag; data that
doesn't authenticate fails, and nothing unauthenticated is returned.

**Memory:** the app zeroes the password arrays, key passphrases, and unlocked
key bytes it holds. Proton's Go libraries receive their own copies:
`crypto-android`'s decrypt and sign calls take the unlocked key as bytes,
which Go parses on every call. The library clears each parsed key afterwards,
but the bytes Go received, and the buffers it makes from them, can't be
wiped.

**Manifest**, checked by `mise run check` against the list in
`app/build.gradle.kts` (every component, permission, and entry, library ones
included; an unexplained or missing entry fails the build), together with the
backup rules and network security config as they ship in the release APK:

- `allowBackup="false"`, and `dataExtractionRules` excluding everything from
  cloud backup, device transfer, and transfer to iOS.
- A network security config allowing only `cleartextTrafficPermitted="false"`
  (never true in any `domain-config`), system trust anchors, and Certificate
  Transparency.
- Exported: the launcher activity; the authenticator and sync services (both
  require `android.permission.ACCOUNT_MANAGER`); WorkManager's
  `SystemJobService`; and the receiver for the system-only `ACCOUNT_REMOVED`
  broadcast, which only starts reconciliation. Everything else is not
  exported; library components that stay are Room's
  `MultiInstanceInvalidationService` and WorkManager's `RescheduleReceiver`
  and `ForceStopRunnable$BroadcastReceiver`. Unused library entries are
  removed: WorkManager's `SystemForegroundService`, `DiagnosticsReceiver`, and
  `FOREGROUND_SERVICE` permission, AndroidX Startup's
  `InitializationProvider`, the profile installer's receiver, and the window
  extensions. The full list is in `app/build.gradle.kts`.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_CONTACTS`,
  `WRITE_CONTACTS`, `READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`,
  `POST_NOTIFICATIONS`, plus WorkManager's `WAKE_LOCK` and
  `RECEIVE_BOOT_COMPLETED` and androidx.core's signature-level
  `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No
  `GET_ACCOUNTS`, storage, or location.
- `memtagMode="sync"`: where the phone has memory tagging and the OS turns
  it on, a tag mismatch crashes the app at the faulting access.
- No `backupAgent`; no `android:process`, `isolatedProcess`, or
  `useAppZygote`; `exported`, `allowBackup`, `usesCleartextTraffic`,
  `isolatedProcess`, and `useAppZygote` are literal `true` or `false`, never a
  resource reference; no Play Services or Firebase entries; no
  `FOREGROUND_SERVICE*` permission.
- One process; the release APK is neither debuggable nor test-only. The
  authenticator never hands out a token.

### Captcha WebView

- A WebView per captcha shown, with JavaScript and DOM storage on; file and
  content access, mixed content, new windows, geolocation, downloads, and
  file choosers off. Console messages (which would log the token's URL) are
  dropped, and page dialogs are cancelled.
- The page's answer arrives only through `WebViewCompat.addWebMessageListener`
  (object `OspVerify`), registered for the exact origin of the checked
  `WebUrl`; a document-start script for that origin defines
  `window.AndroidInterface = { dispatch: m => OspVerify.postMessage(m) }`.
  `addJavascriptInterface` is never used; without both WebView features the
  login fails closed ("update Android System WebView").
- A message counts only from the main frame of that origin, and only as
  `{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"type":"captcha","token":…}}`;
  the token goes straight to the login, never into UI state or logs. A forged
  one only makes Proton ask again.
- Main-frame navigation to another origin is refused, and a main frame that
  leaves the origin anyway drops the WebView. A page that fails to load
  offers Reload and Cancel.
- Afterwards the listener is removed; history, cache, form data, cookies, and
  DOM storage are cleared; the WebView is destroyed.

### Diagnostics

- Only typed records reach the log, never logcat: time, an operation enum, an
  outcome enum, counts keyed by enums, a duration, and an account pseudonym.
  The logging API takes no strings, `Throwable`s, DTOs, or contacts, and no
  HTTP logging interceptor exists in any build.
- A type that holds contact content shows none of it in `toString`: only
  which fields are set, sizes and counts, and enum and Boolean values, so no
  contact content can reach an exception message or a crash report.
- Pseudonym: lowercase hex of the first 12 bytes of `HMAC-SHA256(installKey,
  domain ‖ 0x00 ‖ id)`; `installKey` is 32 random bytes in a no-backup file,
  regenerated if missing or corrupt.
- Two JSON-lines files (`current.jsonl`, `previous.jsonl`) of at most
  256 KiB each; a record over 4 KiB, or one that fails to write, is dropped,
  so logging never fails what it describes.
- Recorded: every run (kind, outcome or blocker, counts, duration), each login
  step's outcome, and each screen action (Sync now, mode, timing, logout,
  removal, Keep a copy, import, merge, remove originals) with its counts.
- **Export logs** writes both files through the system save dialog.

## Android account

- One authenticator for the account type (D4), labelled "OpenSync for
  Proton". `addAccount` opens the app's login screen; every other request
  (tokens, credentials, properties) is refused as unsupported, so nothing it
  returns holds a secret.
- Accounts are added with `addAccountExplicitly(account, null, userdata)`,
  storing only `proton_user_id`. The app never renames its account; if the
  Proton address changes, the old name stays.
- `sync_contacts.xml`: authority `com.android.contacts`, user-visible, no
  parallel syncs, not always syncable (the app marks it syncable only with
  sync on), `supportsUploading` true.
- `contacts.xml` has an edit schema listing exactly the kinds and types
  [mapping.md](mapping.md#field-table) carries both ways, so the Contacts app
  edits synced contacts in place. It follows the Contacts app's parser
  strictly (a schema that fails to parse makes that app fall back to a
  generic schema that would upload lossily), which a test checks.

## GrapheneOS notes

- The per-app Network permission, when off, looks like being offline, and
  sync-adapter runs don't arrive.
- The crypto library works under GrapheneOS's hardened memory allocator with
  memory tagging (`memtagMode="sync"`).
- Contact Scopes hides the app's own rows and fails its writes
  ([sync.md § Sync mode](sync.md#sync-mode)).
- No Google Play services are needed or used.
