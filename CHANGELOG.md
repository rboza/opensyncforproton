# Changelog

Each release's user-visible changes. Versions before 1.0.0 were installed on
the author's phone only and never published.

## 1.0.0 — 2026-11-03

- First public release.
- On phones with hardware memory tagging, when the OS turns it on, a
  memory-tag mismatch crashes the app at the faulting access.

## 0.4.1 — 2026-10-28

- The two-factor code is one field drawn as six boxes. It takes only digits,
  so a pasted `123 456` fills all six.

## 0.4.0 — 2026-10-28

- **Import from this phone** (Settings): copies contacts from the phone's own
  contacts into the app's account, and the next sync sends them to Proton.
  Contacts imported before, or that look like one already in Proton, are
  marked and left out of **Select all**.
- **Merge**: a phone contact that matches exactly one Proton contact can be
  combined with it, replace it, be kept as a separate contact, or be left as
  Proton has it.
- **Remove originals**: deletes imported originals whose copies on Proton
  hold everything they do.

## 0.3.0 — 2026-10-26

- **Keep a copy on this phone** (Settings): copies the synced contacts into
  the phone's own contacts, so they stay after the app is uninstalled. Needs
  no network.

## 0.2.0 — 2026-10-26

- Two-way sync: contacts created, edited, or deleted on the phone are sent to
  Proton, keeping the fields the app doesn't map.
- A contact changed on both sides is held until you choose which copy to
  keep.
- Many deletions at once, in either direction, wait for your confirmation.
- Changes on Proton arrive within minutes through Proton's event feed, with a
  full comparison every hour. Both intervals can be set, or **Manual** leaves
  only **Sync now**.
- Upgrading from 0.1.0 switches sync back to dry run once, so what would be
  sent to Proton is seen first.

## 0.1.0 — 2026-10-13

- Login to one Proton account: password, two-factor code, mailbox password,
  and captcha.
- Dry run by default: **Sync now** reports what a sync would change.
- With sync on, Proton's contacts are downloaded into the app's Android
  account; phone edits are kept but not uploaded.
- Log out, remove the contacts from the phone, export diagnostic logs.
