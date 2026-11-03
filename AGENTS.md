# Working on OpenSync for Proton

An Android app that syncs one Proton account's contacts with Android. How it
works is in [`docs/`](docs/); the [README](README.md#documentation) says which
file owns what. Read only the files your change needs.

## How work flows

1. **One change per behaviour,** on its own branch named
   `<area>/<short-name>` (for example `sync/bulk-guard`), and on GitHub its
   own pull request. The branch's first commit message (and the pull
   request's description) names the docs sections it implements and lists
   the tests that prove it.
2. **Small branches.** Small enough to review in one sitting; one that needs
   several unrelated explanations should be split.
3. **Tests first.** Write the smallest failing test, run it, and see it fail
   for the right reason. Then write the least code that makes it pass, then
   tidy up while the tests stay green. The tests are the detailed behaviour
   spec; `docs/` holds the rules and the reasons.
4. **`mise run check` must pass** before a branch is offered for review; it
   runs what CI runs. Never disable, skip, or weaken a test to get green. If a
   test contradicts the docs, stop and ask.
5. **The owner reviews and merges** into `main`. Agents never merge, tag, or
   release unless the owner says so for that specific change.

## Scope rules

- Build only what the change needs: no methods, tables, permissions,
  dependencies, or flags "for later", and no skipped tests for future
  features.
- One implementation per interface; add an interface only when a test needs a
  fake.
- Prefer plain functions and data classes over frameworks, registries, or
  generic base classes.
- When behaviour changes, update the docs file that owns it in the same
  branch. If two docs disagree, the owning file wins; fix the other one.
- If a value the docs mark **UNKNOWN** is needed, stop and ask. Never guess
  wire values.

## Comments

- KDoc says what a declaration does, its threading, and how it fails.
- An inline comment says why, and only when the code can't show it.
- No history in code: no dates, names, milestones, branch names, or "used to".
  History lives in commits and pull requests.
- Link to `docs/` only where a rule is needed to understand the code.

## Owner approval

Ask the owner explicitly, and wait for a yes, before:

1. **Any request to a live Proton server,** with any account. Say which
   account, which requests, and how data will be cleaned up afterwards.
2. **Running code that writes to or deletes from Proton** against the owner's
   real account.
3. **Anything destructive on the owner's phone** (deleting contacts, removing
   accounts). Instrumented tests use only the `.debug` account type
   ([decisions.md](docs/decisions.md) D4), except tests of the phone's local
   account, which run on the emulator only and delete exactly the contacts
   they created, by ID.
4. **Releases:** creating tags or GitHub releases, or touching the signing
   key ([releasing.md](docs/releasing.md)).
5. **Adding a dependency, permission, or exported component.** Also call it
   out in the review note.

An earlier approval doesn't carry over to a new action.

## Changes that need a second review

Auth, crypto, credential storage, token refresh, retries of write requests,
deletion and conflict logic, `SOURCE_ID` and map recovery, Room migrations,
exported components, the captcha WebView, and release signing. A branch
touching these gets a separate review pass: a fresh reviewer (a new agent
session, or the owner) reads the diff before the author's review note and
checks for weakened tests and out-of-scope code.

## Secrets and personal data

- Never commit, or paste into review notes, issues, or pull requests:
  passwords, tokens, keys, salts, real Proton IDs, e-mail addresses, contact
  content, raw HTTP bodies, or device serial numbers.
- Live captures are reduced to their structure: every value is replaced by a
  generated one, then a secret scan runs before anything is saved.
- Test keys come from `tools/fixture-gen` and are labelled test-only.
- Logs are typed records only
  ([architecture.md § Diagnostics](docs/architecture.md#diagnostics)).

## Review note checklist

When a branch is ready, give the owner a review note (on GitHub, the pull
request's description) covering:

- The change and the docs sections it implements.
- The tests added first, and the failing output you saw.
- The commands run and their results (counts, not "all pass").
- New dependencies, permissions, tables, or exported components, or "none".
- What stays disabled or out of scope.
- Docs updated.
