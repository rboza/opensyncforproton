# OpenSync for Proton

An Android app that syncs one Proton account's contacts with Android's
contacts, so every app on the phone can use them. Proton offers no CardDAV, so
it talks to Proton's API directly and does Proton's encryption (OpenPGP and
the SRP login) on the phone with Proton's own crypto libraries.

**Not affiliated with Proton AG.** It is a third-party app that uses Proton's
unofficial API, which can change at any time and break it. It is made for its
author's own phone (a Pixel on GrapheneOS); the repository is public, but the
app isn't supported as a product. It comes with no warranty.

## Contributing

This is a personal project, shared as it is in case someone else finds it
useful. Issues and pull requests aren't accepted, but you're welcome to fork
it under the GPL (see [Licence](#licence)). Security problems: see
[`SECURITY.md`](SECURITY.md).

## What it does

- Logs in to one Proton account: password, two-factor code, mailbox password,
  and a captcha when Proton asks for one.
- Starts in **dry run**: **Sync now** reports what a sync would change in each
  direction, and changes nothing.
- With sync on, syncs both ways. Proton's contacts appear under the app's
  account; contacts created, edited, or deleted there on the phone go to
  Proton, keeping the fields the app doesn't map. A contact changed on both
  sides is held until you choose a copy, and many deletions at once wait for
  your confirmation.
- Checks Proton for changes every 15 minutes, sends phone edits shortly after
  you make them, and compares every contact every hour; both intervals can be
  changed, or **Manual** leaves only **Sync now**.
- **Import from this phone** copies the phone's own contacts to Proton, with a
  **Merge** step for contacts that look like ones already there, and
  **Remove originals** once their copies hold everything.
- **Keep a copy on this phone**, for just before uninstalling, copies the
  synced contacts into the phone's own contacts, which survive the uninstall.
- Logging out or turning sync off leaves the phone's contacts as they are.
  No telemetry; diagnostic logs stay on the phone and hold no personal data.

Not planned: calendars, CardDAV, several accounts at once, Google Play.

## Install

Android 17 (API 37) or later. Install through
[Obtainium](https://github.com/ImranR98/Obtainium) from this repository's
releases. Every release is signed with the same key; its certificate's
SHA-256 is:

```
0458eea49a58a9d222d885441e715e8c20e8768e509d408f5e0efb1f0c6759c7
```

`apksigner verify --print-certs <file>.apk` shows it as the one signer's
"certificate SHA-256 digest". Changes per version are in
[`CHANGELOG.md`](CHANGELOG.md).

## Build

Tools come from the project's [`mise.toml`](mise.toml): `mise install`, then
`mise run android-sdk` once, then `mise run check` (everything CI runs).
`mise run device-tests` runs the instrumented tests on an API 37 emulator.

## Documentation

How the app works is in [`docs/`](docs/). Each fact lives in one file; if two
disagree, the one that owns it wins.

| File | Owns |
| --- | --- |
| [decisions.md](docs/decisions.md) | Design decisions (`D*`) and why |
| [protocol.md](docs/protocol.md) | Proton's wire contract: login, routes, JSON, errors, retries, keys, cards |
| [sync.md](docs/sync.md) | Behaviour: accounts, dry run, the plan, writes, conflicts, deletion, scheduling, import |
| [mapping.md](docs/mapping.md) | vCard ↔ Android field mapping and how uploads patch the vCard |
| [architecture.md](docs/architecture.md) | Modules, storage, threading, security controls, diagnostics |
| [releasing.md](docs/releasing.md) | Release steps, signing key, Pixel check, GitHub settings |

How changes are made: [`AGENTS.md`](AGENTS.md). Reporting a vulnerability:
[`SECURITY.md`](SECURITY.md).

## Licence

Copyright 2026 rboza. OpenSync for Proton is free software under the GNU
General Public License, version 3 or (at your option) any later version; see
[`LICENSE`](LICENSE). The app lists the third-party software it includes, and
their licences, under Settings → About → Third-party licences.
