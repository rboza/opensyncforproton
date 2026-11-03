# Sync behaviour

What the app does with the account and its contacts. Wire details are in
[protocol.md](protocol.md), field mapping in [mapping.md](mapping.md).

Syncs and dry runs read and write only contacts in the app's own Android
account. Two explicit actions reach the phone's **local** contacts and nothing
else does: [Import from this phone](#import-from-this-phone) reads them (and,
only if asked, removes imported originals), and [Keep a
copy](#keep-a-copy) writes into them. Other apps' accounts are never touched.

## Account states

| Record | Holds |
| --- | --- |
| Android account | Its name; userdata `proton_user_id` |
| Room `account_record`, `settings` | `User.ID`, `login_state` (`ACTIVE` / `SIGNED_OUT`), sync mode, timing |
| Credential envelope (D22) | Tokens, locked keys, key passphrases |

**Signed in** (`ACTIVE`, credentials readable); **session expired**
(`ACTIVE`, credentials missing or unreadable); **signed out** (`SIGNED_OUT`).
Only a signed-in account makes requests. Keys that no longer unlock leave the
account signed in, but every run stops with "keys don't unlock" until the
user logs in. In every state the phone's contacts and edits stay as they are.

**Reconciliation** runs at app start, before every run, and on
`ACCOUNT_REMOVED`, without the network. Room row with no Android account: the
account was removed (or a first login crashed), so its running sync is
stopped, its credentials, job, and Room rows deleted, and any provider rows
left under its name deleted as the sync adapter. Android account with no Room
row (storage cleared): the row is recreated as `ACTIVE` in dry run. A signed-out
row with credentials left: they are deleted. It also corrects the syncable
flag and periodic job to match the mode. An account of the app's type without
a readable `proton_user_id` is never deleted, and if one holds a gone
record's account name, nothing of that record is deleted (ambiguous). Provider
rows are deleted only under names no account of the app's type holds. Cleanup
that can't reach the provider waits for the next reconciliation.

Login commits, mode changes, logout, removal, and reconciliation run one at a
time, and once started run to their end even if the screen goes away.

## Login

After the [protocol login](protocol.md#login):

- **Same `User.ID` as an existing account:** replace its credentials and set
  `ACTIVE`, keeping its Android account, Room rows, mode, and contacts.
- **Another `User.ID` while one is `ACTIVE`:** refused ("log out of the other
  account first"). This is also what stops a second account added from
  Android Settings.
- **New account:** Room row (`ACTIVE`, dry run) → credentials → Android
  account (contacts authority not syncable), in that order, so every crash
  point leaves a Room row without an Android account, which reconciliation
  deletes. A failed commit is undone and its server session ended with one A5.

A signed-out account stays frozen beside a newer one until it is removed.

## Sync mode

| | Dry run (default) | Sync on |
| --- | --- | --- |
| Automatic runs | None | Periodic job + SyncAdapter ([Scheduling](#scheduling)) |
| **Sync now** | Dry run | Full sync |
| Writes to the phone or Proton | Never | Yes |

**Dry run** does everything a sync does up to writing: credentials, key
unlock, permission, a complete inventory, decrypting and mapping every
contact, reading the phone's rows, preparing every upload's cards, and
computing [the plan](#the-plan). It writes nothing but its report. Its client
uses the account's session, so a token refresh is saved, and a rejected one
deletes the credentials (session expired).

**The report** says whether a sync could run now or names one blocker
(offline, signed out, session expired, permission missing, contacts hidden
from the app, keys don't unlock, captcha, Proton unavailable, app version
refused, contacts storage not answering, inventory incomplete), and counts:
from Proton (new, changed, deleted), to Proton (new, changed, deleted on the
phone), needs you (conflicts, and whether the [bulk guard](#deletion) stops),
and can't be synced (with a reason per contact: decryption, signature, parse,
fields the app can't upload, a refused write, a photo, a label, no name or
e-mail, and so on), duplicate rows, and missing rows. Counts can be expanded
to names, but names are only ever held in memory for the screen's own **Sync
now**: never logged or stored.

Each report records what started it. A full sync counts both sides; an
upload-only sync counts only the phone's side and keeps the other side's
counts and time from the run that last counted them. A side with nothing to
do keeps the earlier counts if they only showed changes, so a quiet follow-up
run doesn't hide what the sync before it did. The screen shows each line as
current, earlier but still needing the user, or earlier.

**Turning sync on** asks for `READ_CONTACTS` and `WRITE_CONTACTS` if missing,
runs a fresh dry run, and, only if it is ready, asks "Start syncing?" with its
counts. **Start** saves the mode, marks the authority syncable, starts the
periodic job, and runs the first sync. **Turning sync off** is immediate: job
stopped, authority not syncable, running sync cancelled; contacts and pending
edits stay. A database upgrade from a download-only version puts sync back in
dry run once, with a notice, so uploads are reviewed before anything is sent.

**Permission and Contact Scopes:** with the contacts permission revoked, sync
pauses and the main screen offers **Grant**. GrapheneOS's Contact Scopes hides
the app's own rows and fails its writes while the permission still reads as
granted. A run that finds none of its rows while Room has maps, or whose write
fails with an operation error, stops with "contacts hidden from the app".

## Logout and removal

**Logout** never deletes data: stop the account's runs, job, and syncable
flag; set `SIGNED_OUT`; send one A5 if a session is readable (a failure is
reported, since the session may need ending in Proton's web settings); delete
the credentials. Contacts, edits, mode, maps, and import records stay.

**Remove from this phone** (confirmed with the count of contacts and of those
with unsent edits) logs out, removes the Android account, deletes its rows as
the sync adapter (never under a name another account of the app's type now
holds), and deletes everything Room keeps for it. Removing the
account in Android Settings ends the same way through reconciliation.
Uninstalling removes the account and its contacts; see [Keep a
copy](#keep-a-copy). A broadcast alone never deletes anything.

## The plan

A dry run and a sync compute the same plan (D25):

1. Unlock the keys.
2. With a network, fetch the inventory, then decrypt, verify, and map each
   contact. One that fails is "can't be synced": its row stays, a new one
   isn't inserted.
3. Read the account's rows. **Map repair:** a row with a `SOURCE_ID` but no
   map is treated as mapped with an empty baseline.
4. Pair remote contacts and rows by `SOURCE_ID` (a map only supplies the
   baseline for the row it names). Several rows with one `SOURCE_ID`:
   duplicate rows, held. A map whose row is gone: a missing row, held, never
   recreated on its own. Neither map nor row: **new from Proton**.
5. Classify. Remote **changed** if the fingerprint differs from the baseline
   or the baseline is empty. A dirty row is **settled** when its map has keys
   and the values Android shows, and the photo by baseline, equal the
   remote's (not counted; a sync clears `DIRTY`); otherwise a **conflict** if
   the remote changed, is gone, or the baseline is empty; otherwise **edited
   before keys** (a conflict) if its map has no keys; otherwise **changed on
   the phone**. A tombstone (`DELETED`) with a `SOURCE_ID` is **deleted on the
   phone**, or a conflict if the remote changed. A clean row missing from a
   **complete** inventory is **deleted on Proton** (a conflict if its
   baseline is empty). A row holding data the app can't read back is "can't
   be synced", never uploaded.
6. Rows without a `SOURCE_ID` are **new on the phone**; their cards are made
   now, so whatever stops one shows in a dry run.

After an incomplete inventory only the phone's side is planned; nothing is
classified as deleted on Proton.

## Writes

A sync uploads first (keys unlocked again for it), then applies downloads.
Nothing uploads while offline or after a failed inventory request. Any
failure not about one contact (network, 5xx, rate limit, captcha, session,
app version) stops sending; the downloads still apply.

- **Download:** written as the sync adapter (so not marked dirty), with
  `SOURCE_ID`, replacing all the contact's data rows in one batch guarded by
  the `VERSION` read when planning; then its map (raw ID, fingerprint, photo
  baseline) is saved. A write the phone made stale is planned again next run;
  one the provider refuses is "can't be synced" with its reason. Per-row
  choices made on the phone (a default number) reset; the star and ringtone
  stay.
- **Update** (dirty row with a Proton ID): re-fetch with C2. Fingerprint
  still the baseline: send C5 with the patched cards. Moved since: settled if
  the shown values match, else a conflict. A C2 or C5 answering 2501 (gone)
  is a conflict. Settled: send nothing and clear
  `DIRTY`; if the remote moved, rewrite the row from it in the same batch.
- **Create** (row without `SOURCE_ID`): save a pending-create record (row,
  UID, `VERSION`) **before** C4. On success, bind: re-read with C2, write the
  row from it with its `SOURCE_ID` (or only the `SOURCE_ID` if the phone
  changed it meanwhile), save the map, delete the record.
- **Delete** (tombstone with a Proton ID): re-fetch. Changed on Proton since
  the baseline: conflict. Already gone (2501): finish. Otherwise send C6, then
  remove the tombstone and the map.
- **After a create or update:** re-read with C2 (replies carry no cards),
  store its fingerprint, and write the row from it as a download, which sets
  fresh keys and clears `DIRTY`, unless the row's `VERSION` moved.
- **Unknown outcome** (D18). *Create:* the next run sends the same UID again;
  2500 means it exists, so C1 is paged for that UID: one match is bound;
  none or several are held ("Proton can't confirm it was added") and tried
  again. A 2001 on a resend is looked up the same way. A pending record whose
  row was deleted meanwhile is looked up and either dropped or bound to the
  tombstone, which then becomes a phone deletion. *Update:* the next run's
  re-fetch decides. *Delete:* re-fetch; gone is success.
- **A missing row** (a mapped row gone without a tombstone) is held and
  offers only **Restore here**; absence on the phone is never sent as a
  deletion.

**Rows without keys.** A map rebuilt from `SOURCE_ID`, written by a version
before keys, or with an unknown photo baseline has `rows_keyed` false. A clean
such row is rewritten from Proton by the next sync; a dirty one is held as
"edited before keys" so the user chooses, and *Use phone* uploads it pairing
rows by equal value.

## Photos

Android re-encodes every photo, so a photo written from Proton never matches
Proton's bytes. After a write the map stores a **photo baseline**, the SHA-256
of the display photo read back (or the thumbnail when the provider keeps no
display photo), and a thumbnail baseline. A different photo on the phone means
it changed there and uploads; an unchanged baseline keeps Proton's photo; an
unknown baseline (unreadable, or the row changed before the read-back) keeps
Proton's photo and marks the map as without keys. A write whose photo equals
the row's current photo byte for byte leaves that photo row alone, so it isn't
compressed again.

The provider's daily photo clean-up can delete a photo stored while it runs
and later clear its row to the thumbnail, marking it dirty. A dirty row whose
photo is only the thumbnail the app itself wrote is not treated as a new
photo, so Proton's photo is never replaced by a thumbnail.

## Conflicts

A conflict is a contact edited or deleted on the phone **and** changed or
deleted on Proton since its baseline (D17), a dirty row or tombstone with an
empty baseline, or a clean row with an empty baseline that a complete
inventory left out. Deleted on both sides isn't a conflict: the removal
completes, unless the bulk guard holds back deletions on this phone, since it
acts on Proton's absence.

- Nothing is uploaded or overwritten. A sync keeps each held contact in
  `conflict_hold`; the main screen lists them ("Held contacts (N)") with
  **Use phone**, **Use Proton**, or **Skip**; a missing row offers **Restore
  here** and **Skip**. One notification gives the count, without names.
- A choice is saved with the phone `VERSION` and remote fingerprint it was
  made for, and carried out only by a full sync that still finds both sides
  as they were; otherwise it is dropped and the user asked again. It never
  resolves itself after a timeout.
- *Use phone* uploads what the row shows (only fields Android shows change;
  remote-only properties stay), recreates a contact deleted on Proton with a
  fresh UID, or deletes it on Proton. *Use Proton* writes Proton's version
  over the row, deletes the row, or restores a contact deleted on the phone.

## Deletion

- A live row is **never** deleted because its contact is missing after an
  incomplete inventory, while offline, or when it is dirty or has an empty
  baseline (D19).
- **Deleted on Proton:** after a complete inventory, a clean row whose contact
  is missing is deleted as the sync adapter, guarded by its `VERSION`, then
  its map. A clean row holds nothing Proton lacks, so a mistake is undone by
  the next sync.
- **Deleted on the phone:** sent as C6, each re-fetched first.
- **Bulk guard**, each direction on its own: 10 or more contacts, or at least
  20 % of 20 or more mapped contacts, or an empty inventory while mapped rows
  exist, stops that direction's deletions and asks ("Delete N contacts on
  Proton?" / "… on this phone?"). The confirmation isn't stored: it starts
  one run carrying the count the user saw, which acts only if no more are
  waiting than that. One direction's confirmation never lifts the other's
  guard; merged confirmations carry the smallest count of each.

## Event loop

Used by the periodic job with sync on, through E1/E2 (core events):

1. Load credentials and unlock keys; if a login is needed, stop.
2. No cursor: get E1, fetch the keys again (U1), run a full sync, then store
   the cursor.
3. Otherwise read E2 pages while `More` is 1, up to 50, then act once:
   `Refresh` nonzero, or `User` / `Addresses` → fetch keys, then full sync;
   `Contacts` / `ContactEmails` → full sync; anything else → nothing. A full
   sync also runs when the full-sync interval has passed.
4. Store the last `EventID` only after that work ends, and not when it ended
   on a retryable blocker, so a retry reads the same pages. Keys that can't be
   fetched or stored are a failed feed and leave the cursor where it was, even
   past a lasting blocker, so a key change is never skipped.
5. A cursor refused with 2061 is dropped and the loop starts over at step 2.
   Any other feed failure still runs a full sync that is due.
6. With no full sync triggered, dirty rows still upload (an upload-only
   sync).

Keys are fetched only here; passphrases carry over by key ID, so a new
primary key needs a login.

## Scheduling

- **Dry run:** no automatic runs; only **Sync now**.
- **Sync on:** one WorkManager periodic job per account (network required),
  every "Check Proton for changes" interval (default 15 minutes), running the
  [event loop](#event-loop); a full sync every "Full sync" interval (15
  minutes to 24 hours, default 1 hour). The check is never longer than the
  full sync. **Manual** turns off the job and Android's automatic sync, so
  only **Sync now** (or Android Settings) syncs. The job respects Android's
  global and per-account sync switches.
- **SyncAdapter:** syncs started from Android Settings, and an upload-only
  sync after phone edits (no inventory; re-fetch, upload, create, delete).
- **One run at a time per account.** A trigger that arrives during a run
  merges into one follow-up (full beats dry run beats incremental beats
  upload-only), and everyone waiting gets its report.
- **Offline:** a run asks the device for a network before its first request,
  so it reports offline at once. Retryable blockers (offline, Proton
  unavailable, contacts storage not answering) back off exponentially from 5
  minutes; others wait for the next period. Nothing is deleted because
  something is unreachable. Pending creates, holds, and the cursor are in
  Room and survive restarts.
- **While a login is needed** every run checks credentials and keys first and
  stops there; edits wait as `DIRTY` and upload after the login.

## Keep a copy

An opt-in action for before uninstalling, available in every state and
without network. It copies every live row of the app's account (edits not yet
uploaded included) into the phone's local account (the one
`RawContacts.getLocalAccountName/Type` names; the null account on
GrapheneOS), as an ordinary app, without Proton IDs: the mapped data rows,
the display photo (else the thumbnail), and the star. Each contact is its own
batch; a failure counts as not copied and the others go on. Each use adds
another copy. It runs beside syncs, not through the coordinator. Until the
app is uninstalled, the Contacts app may merge a copy with its synced contact,
so an edit can land on the copy, which doesn't sync; the question says so.

## Import from this phone

An opt-in screen, active only while signed in with sync on, that copies local
contacts into the app's account; the next sync uploads them (D26). It never
contacts Proton itself, and its writes are guarded by the `VERSION`s it read.
It reads only the local account: never the app's own contacts, other apps'
accounts, or SIM accounts.

**Matching:** a local contact matches a contact in Proton (a live row of the
app's account with a Proton ID) when Android shows them as one contact, they
share an e-mail address (trimmed, case-insensitive), they share a phone number
(normalized, else the same digits), or they have the same name built from a
name row. Each local contact is then **imported before** (an import record
names it), a **pair** (it matches exactly one clean, unheld contact that no
other local contact matches), **looks like it's already in Proton** (any other
match: never merged), or **on this phone only**.

**Import** copies the ticked contacts (**Select all** skips marked ones). For
each: save an import record (original's ID and `VERSION`) first, so a crash
can't make **Select all** copy it twice; insert a copy into the app's account
as an ordinary app (no `SOURCE_ID`, dirty), guarded by the original's
`VERSION`; record the copy's ID. Then a full sync is requested. Originals are
untouched. The question counts contacts with fields a copy can't take (IM,
groups, a ringtone, a default).

**Merge** lists each pair with four choices: **Combine both** (Proton's
contact gains every value the phone's has that it lacks; one-value fields
keep Proton's), **Keep Proton's** (nothing written), **Keep the phone's**
(Proton's shown values become the phone's; values only Proton had are lost,
which the question counts; hidden parts of values kept stay), and **Keep
both** (imported as a new contact). A write goes into Proton's row as an
ordinary app's edit, guarded by both rows' `VERSION`s, keeping each kept
value's `Data.SYNC1` key; the next sync uploads it as any phone edit, held as
a conflict if Proton changed meanwhile. Each applied pair gets an import
record.

**Remove originals** deletes, in one batch each, originals still at their
import record's `VERSION`, holding nothing a copy can't take (a ringtone, send
to voicemail, a default on any row but the name and photo, which the Contacts
editor sets by itself, rows or values the column mapper doesn't read), and
whose copy is a live, clean, unheld row with a Proton ID that shows every
value the original shows (e-mail and phone by the matching rules and as
dialled, types and labels, the star, the photo if the copy received it).
Others stay, with the reason. It is off while the bulk guard holds back
deletions on this phone (contacts missing from Proton), since a copy may be
gone from Proton though its row still shows it.

The Contacts app may show an original and its copy as one contact: deleting
it there deletes both, and the copy's deletion uploads. The screen warns
about this. Import records hold row IDs and a `VERSION`, never content, and
are deleted only with their original, a refused copy, or the account.

## Screens and notifications

Every screen sets `FLAG_SECURE` except in the emulator-only debug build.
Questions are dialogs; Back cancels them.

- **Login:** a third-party notice above the fields; username and password,
  then the six-digit code (one field drawn as six boxes, taking only digits),
  mailbox password, or captcha as Proton asks. Nothing typed is saved, and
  each step clears the fields.
- **Main:** the account and its state with **Log in** when needed; the mode
  switch; **Sync now**; the last report; held contacts; bulk-guard questions;
  notes about Android's sync switch and the upgrade notice.
- **Settings:** timing (Manual, full sync, check); **Import from this
  phone**, **Keep a copy on this phone**, **Log out**, **Remove from this
  phone**; **Export logs** (the system save dialog; nothing sent anywhere);
  about, with version, "Not affiliated with Proton AG", licence, source, and
  third-party licences.

One notification channel, "Sync problems", only while signed in with sync on:
"Log in to resume" (session expired or keys don't unlock), "Open the app to
verify" (captcha), duplicate rows, permission revoked, and held contacts. Each
is posted when a run first shows the problem and cleared when one shows it
gone; no account or contact names, and only "OSP: Sync problem" on the lock
screen. Everything a notification says is also on the main screen.
