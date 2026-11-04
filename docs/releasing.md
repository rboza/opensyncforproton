# Releasing

Releases are built and signed on the maintainer's machine, never in CI (D2),
and installed through Obtainium from this repository's GitHub releases. Every
step that tags, publishes, or touches the key needs explicit approval
([AGENTS.md § Owner approval](../AGENTS.md#owner-approval)).

## Steps

1. **Version.** In a branch of its own, raise `versionCode` (always higher)
   and set `versionName` (`X.Y.Z`) in `app/build.gradle.kts`, and add the
   version's entry to [`CHANGELOG.md`](../CHANGELOG.md). Merge it before the
   Pixel check, so the APK checked is the one released.
2. **Proton's terms.** Re-read Proton's Terms of Service and its published
   rules for third-party clients; note anything that conflicts with how the
   app talks to Proton ([protocol.md](protocol.md)).
3. **Check.** `main` is clean and `mise run check` passes.
4. **Build.** `mise run release` (`tools/release`). It refuses unless the
   tree is clean, the environment names the keystore (an absolute path outside
   the repository), its alias, and its password, and the certificate's
   SHA-256 matches `tools/release/release-certificate.sha256`. It builds
   `assembleRelease` unsigned with R8 (the key never goes into Gradle), signs
   with `apksigner`, verifies exactly one signer with the recorded
   certificate, and writes `build/release/opensyncforproton-X.Y.Z.apk` and its
   SHA-256.

   ```sh
   read -rsp 'Keystore password: ' pw && echo && OSP_RELEASE_KEYSTORE=<absolute path>.p12 \
     OSP_RELEASE_KEY_ALIAS=<alias> OSP_RELEASE_KS_PASS="$pw" mise run release; unset pw
   ```

   The password goes to that one command only, never into a shell that
   starts Gradle daemons.
5. **Pixel check.** Install the APK over the previous version and run the
   [Pixel check](#pixel-check). A failure becomes a fix, and the release
   starts again from step 3 with the same version, since nothing was
   published.
6. **Publish.** Tag `vX.Y.Z` on the released commit and run
   `gh release create vX.Y.Z` with exactly one APK asset and the version's
   changelog entry. Obtainium then offers the update.

A bad release is fixed by a newer version, never by moving a tag.

## Signing key

Generated for this app only, kept outside the repository with one offline
backup:

```sh
# keytool asks for the password, so it stays out of shell history.
keytool -genkeypair -keystore <file>.p12 -storetype PKCS12 -alias <alias> \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=OpenSync for Proton"
chmod 600 <file>.p12
```

Its certificate's SHA-256 is committed, in a reviewed commit of its own, as
64 lowercase hex digits and nothing else:

```sh
keytool -exportcert -keystore <file>.p12 -alias <alias> | sha256sum | cut -d' ' -f1 \
  > tools/release/release-certificate.sha256
```

Losing the key means the next version needs an uninstall, which deletes the
synced contacts: use **Keep a copy on this phone** first.

## Pixel check

The tests can't see everything: the real Contacts app, GrapheneOS, Proton's
live server, and how screens look on the phone. Each release is checked on
the Pixel with a throwaway Proton account, never the maintainer's real one
without approval. Write the check down before running it, from this
template, and record the result in the release's pull request.

**Before:**

- **Phone state:** the version installed, the account signed in, the mode
  and timing.
- **APK:** built by step 4 from `main`; its SHA-256, and checked before
  install: `versionCode` and `versionName`, one signer with the recorded
  certificate, and the permissions and exported components compared with the
  last release (any change named and approved).
- **Live requests:** which Proton requests the check makes (reads, writes,
  login, logout), and how anything it creates on Proton is cleaned up.
- **Stop rules:** what result stops the check (for example, a report that
  counts anything unexpected: turn sync off at once), and what is only
  recorded for the maintainer to decide.
- **Counts before:** contacts in Proton's web app and in the phone's Contacts
  app; **Sync now** shows every line 0.

**Steps:** numbered, each with what to do and the exact result expected (the
words on screen, the counts). Always start with the upgrade:

1. Install over the previous version. App info shows the new version; the
   app opens with its account, mode, and timing kept; a screenshot is refused
   and the Recents thumbnail is blank (`FLAG_SECURE`); **Sync now** shows
   every line 0; the Contacts app's count is unchanged.
2. Then a step for each change the release makes that the tests can't see.
3. End with **Sync now** and the counts from "before", unchanged unless a
   step changed them on purpose.

**After:** the state the phone is left in, the clean-up done, and each
step's result (passed as written, or what was seen instead).

## GitHub settings

The repository is shared for forking, not maintained for others (README §
Contributing):

- Pull requests only from collaborators; CI runs `mise run check` on each
  one and on every push to `main`.
- Issues, discussions, wiki, and projects off. Open questions and deferred
  features are kept in the maintainer's private notes.
- **Private vulnerability reporting** on (Settings → Security), for
  [`SECURITY.md`](../SECURITY.md).
