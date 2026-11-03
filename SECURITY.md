# Security

OpenSync for Proton holds a Proton session, key passphrases, and the user's
contacts, so security reports are welcome.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: this repository's **Security**
tab → **Report a vulnerability**. Please don't open a public issue, and don't
include real credentials or contact data in the report.

This is a personal project: there is no bounty and no promised response time.
Only the latest release gets fixes.

## Scope

In scope: anything that could expose the Proton session, keys, or passwords;
send contact data anywhere but Proton; change contacts on Proton or the
phone in ways [`docs/sync.md`](docs/sync.md) doesn't describe; or let another
app use the app's account or components.

Out of scope: Proton's own servers and apps (report those to Proton), and
other apps reading synced contacts through Android's contacts permission,
which is how Android contacts work. How the app protects each asset is in
[`docs/architecture.md`](docs/architecture.md#security).
