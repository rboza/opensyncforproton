# Proton protocol

What the app sends to Proton and what it expects back. Wire names are copied
from Proton's open-source clients (`go-proton-api`, `go-srp`, `gopenpgp`,
`WebClients`, `protoncore_android`) because interoperability requires them,
and were checked against the live server with a throwaway account. Anything
not confirmed is marked **UNKNOWN**; code never guesses those values.

## Conventions

| Item | Value |
| --- | --- |
| Base URL | `https://mail-api.proton.me/` |
| Success | JSON `Code` 1000. HTTP 200 with any other `Code` is an error. |
| Batch replies | C4 and C6 answer `Code` 1001 with one item per contact in `Responses[]`: C4's `{Index, Response{Code, Contact?}}`, C6's `{ID, Response{Code}}` |
| Times, booleans | Unix seconds; `0` / `1` |
| Paging | `Page` from 0; `PageSize` at most 50 (export refuses 100 with `Code` 2003) |
| Pacing | At most one request every 100 ms per account, counting every request sent |

Headers on every request: `x-pm-apiversion: 3`, `Accept:
application/vnd.protonmail.v1+json`, `x-pm-appversion: Other` (D4),
`Authorization: Bearer <AccessToken>` (not on A1, A2, or refresh), and
`x-pm-uid` (not on A1 or A2; refresh sends it). The `User-Agent` stays
OkHttp's own: Proton limited a login sent with Go's default one as "unusual
activity" (`Code` 2028).

One HTTP client per account, and a separate one per login attempt. Redirects
are never followed: a 3xx is an error.

## Routes

| ID | Method | Path (current / old) | Body or query | Response |
| --- | --- | --- | --- | --- |
| A1 | POST | `core/v4/auth/info` | `{Username, Intent: "Proton"}` | `AuthInfo` |
| A2 | POST | `core/v4/auth` (+ captcha headers) | `{Username, ClientEphemeral, ClientProof, SRPSession}` | `Auth` |
| A3 | POST | `core/v4/auth/2fa` | `{TwoFactorCode}` | `{Code}` |
| A4 | POST | `core/v4/auth/refresh` | [Token refresh](#token-refresh) | tokens |
| A5 | DELETE | `core/v4/auth` | — | `{Code}` |
| U1 | GET | `core/v4/users` / `users` | — | `{User}` |
| U3 | GET | `core/v4/keys/salts` | — | `{KeySalts}` |
| C1 | GET | `contacts/v4/contacts` / `contacts/v4` | `Page`, `PageSize` | `{Contacts, Total}` |
| C2 | GET | `contacts/v4/contacts/{id}` / `contacts/v4/{id}` | — | `{Contact}` |
| C3 | GET | `contacts/v4/contacts/export` / `contacts/v4/export` | `Page`, `PageSize` | `{Contacts: [{ID, Cards}], Total}` |
| C4 | POST | `contacts/v4/contacts` | `{Contacts: [{Cards}], Overwrite: 0, Labels: 0, Import: 0}` | `{Responses[]}` |
| C5 | PUT | `contacts/v4/contacts/{id}` | `{Cards}` | `{Contact?}` |
| C6 | PUT | `contacts/v4/contacts/delete` | `{IDs}` | `{Responses[]}` |
| E1 | GET | `core/v4/events/latest` | — | `{EventID}` |
| E2 | GET | `core/v5/events/{eventId}` | — | event page |

Login routes and writes use the current spelling only. Reads (U1, C1–C3) try
the current spelling and fall back to the old one on 404; the spelling that
answered is kept until it fails to answer (a lost connection or a 404), and
the next request tries the current one again. A contact that doesn't exist
answers HTTP 422 `Code` 2501 and an invalid ID HTTP 400 `Code` 2061; neither
triggers the fallback. Address routes, label routes, mail routes, and the
contacts-only `contacts/v6/events` loop aren't used: no feature needs address
keys, and the core loop also reports key changes.

## JSON

DTOs read only the fields below, ignore unknown ones, fail on missing required
ones, and never show a value in `toString`.

- **AuthInfo:** `Modulus, ServerEphemeral, Version, Salt, SRPSession`.
- **Auth:** `AccessToken, RefreshToken, UID` or `Uid` (equal if both present;
  neither fails), `ServerProof, PasswordMode` (missing is 1), `2FA{Enabled,
  TOTP}`. No key salt: salts come from U3.
- **Refresh:** `AccessToken, RefreshToken, UID`/`Uid`.
- **User:** `ID, Keys[]`; **UserKey:** `ID, PrivateKey, Active, Primary`.
- **KeySalt:** `ID, KeySalt` (`null` for a key that doesn't use one).
- **Contact:** C1 gives `ID` and `UID` (absent is none); C2 adds
  `Cards[{Type, Data, Signature}]`; C3 entries carry only `ID` and `Cards`.
- **Card (C4, C5):** `{Type, Data, Signature}`, every key sent; an unsigned
  card sends `"Signature": null`. C4 and C6 carry one contact each.
- **C4 reply item:** `Index` 0 and `Response{Code, Contact?}`; a 1000 without
  `Contact.ID` is a bad response. **C6 reply item:** the `ID` sent and
  `Response{Code}`. Not exactly one item is a bad response.
- **Event page:** `EventID` (required, non-empty: it goes into E2's path),
  `More` (required, 0 or 1), `Refresh` (required; any nonzero value means
  "resync everything"), `Contacts` / `ContactEmails` entries `{ID, Action}`
  (absent is none; an entry without both is a bad response), and whether
  `User` is present and not `null` or `Addresses` is non-empty (keys may have
  changed). Entries are summed per item since the cursor; card order within
  a contact isn't fixed. Everything else (mail, calendar, labels) is ignored.

## Login

```
credentials ─ A1 → SRP proofs (go-srp) → A2
   ├─ Code 9001 ─────────────────► captcha → restart at A1, A2 with captcha headers
   ├─ 2FA required, TOTP offered ─► six-digit code → A3
   ├─ 2FA required, no TOTP ──────► refuse
   ├─ PasswordMode 2 ─────────────► ask for the mailbox password
   └─ ready ──────────────────────► U1, U3 → unlock keys → hand the session to the app
```

1. **A1** with the username as typed. A1 is where the server checks
   `x-pm-appversion`.
2. **Proofs:** go-srp verifies the signed modulus (tampered aborts), then
   `NewAuth(version, username, password, salt, modulus, serverEphemeral)` and
   `GenerateProofs(2048)`, sent as standard Base64.
3. **A2:** a `ServerProof` that doesn't match go-srp's expected value aborts.
   From here the tokens live in memory for this attempt only.
4. **2FA:** `Enabled` is a bit field (1 TOTP, 2 FIDO2, 3 both) and `TOTP` must
   agree. TOTP is offered when `Enabled` is 1 or 3 and `TOTP` is 1: spaces are
   dropped and anything but six ASCII digits is refused without a request,
   since each send uses up a try. `Enabled` 2 with `TOTP` 0 is refused; any
   other combination fails closed.
5. **Password mode:** 1, the login password unlocks the keys; 2, a separate
   mailbox password does (a wrong one keeps the session and asks again); any
   other value fails closed.
6. **Keys:** U1 and U3, then [unlock](#keys). Only then is the session handed
   to the app.
7. **Cancel or failure after A2:** one best-effort A5, then the tokens are
   discarded.

Login calls are retried only after a 429: at most 3 tries, each after its
`Retry-After` (over 30 s, or none, is shown instead). A 429 on A1 or A2
restarts at A1, so every try uses a fresh SRP session; A3 has its own 3
tries. A 401 on A3 means the code was wrong, and the user retypes it.

### Captcha (Code 9001)

The error details carry `HumanVerificationToken`, `WebUrl`, and
`HumanVerificationMethods`. Without `captcha` among the methods, or on any
login call but A2, login stops with "verify in Proton's web app". `WebUrl` is
opened only if it is `https` on `proton.me`, `protonmail.com`, or a subdomain,
and is loaded with `embed=true` added (replacing any `embed`), as Proton's
Android library does. Embedded, the page doesn't redeem the captcha; it calls
`window.AndroidInterface.dispatch` with
`{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"type":"captcha","token":…}}`
([architecture.md § Captcha WebView](architecture.md#captcha-webview)).

The login then restarts at A1 and sends A2 with
`x-pm-human-verification-token: <the page's token>` and
`x-pm-human-verification-token-type: captcha`. The token is used only if it
is 1 to 4096 printable ASCII characters, kept in memory for that one retry,
and never saved or logged. It works once: `Code` 12087 means "solve a new
captcha" and carries no new token, so the app restarts at A1 without captcha
headers. A 9001 during a background sync ends the run with the blocker
"captcha".

## Token refresh

On HTTP 401 from an authenticated route:

1. **One refresh per account at a time.** Concurrent requests wait for it and
   retry with its result; a request that waited on a failed refresh gets that
   refresh's error.
2. **Request:** `POST core/v4/auth/refresh` with `x-pm-uid` and no Bearer
   token, body `{"UID", "RefreshToken", "ResponseType": "token", "GrantType":
   "refresh_token", "RedirectURI": "https://protonmail.ch", "State": <32
   random hex characters>}`. Every response rotates the refresh token and
   keeps the UID.
3. **Success:** save the new tokens durably **before** resending the original
   request, once. A failed save resends nothing and ends the run as
   retryable. A response with another UID is a bad response.
4. **Rejection** (`Code` 10013, with HTTP 400 or 422): the session is over.
   Delete the stored credentials if they still hold that session (a newer
   login's are kept) and send nothing more until the user logs in, even if
   deleting fails. A rejection waits for a refresh in progress, so that
   refresh can't save tokens after the credentials are deleted. Synced data is
   untouched.
5. **Anything else** (timeout, lost connection, 5xx, 429, cancel): keep the
   stored tokens and end the run as retryable. Proton accepts a used refresh
   token again, so the next run's refresh works whether or not the lost
   response had rotated it.
6. A 401 from A1, A2, A3, A5, or the refresh never refreshes. A 401 on the
   resend counts as a rejection; one on a later retry of that resend fails
   the call without deleting anything.

Keys that no longer unlock with the stored passphrases (the password changed
elsewhere) stop every run with "keys don't unlock" until the user logs in.

## Errors and retries

| Response | Handling |
| --- | --- |
| `Code` 5001 / 5002 / 5003, 2064 | App version refused: the client sends nothing more; tell the user |
| `Code` 9001 | Captcha: see above |
| `Code` 10013 | Session over |
| `Code` 2028 (A2) | Proton limited this client as "unusual activity": stop; tell the user |
| `Code` 2501 / 2061 | Contact doesn't exist / invalid ID |
| `Code` 2500 (C4 item) | A contact with this UID exists: nothing created |
| `Code` 2001 (C4 item) | Proton refuses a card: nothing created |
| HTTP 429 | Honour `Retry-After` (seconds) |
| Other | Typed error with HTTP status and `Code` |

A `Code` takes precedence over the HTTP status. Proton's `Error` text never
reaches logs, notifications, exception messages, or storage. Retry is decided
per operation, never by an interceptor; OkHttp's `retryOnConnectionFailure`
is off.

- **Reads:** at most 5 sends for a 429, a lost connection, or a 5xx. The delay
  before retry *n* is a valid `Retry-After`, else `min(min(2^n, 16) +
  random(0..min(2^n, 16)), 30)` seconds. A `Retry-After` over 30 s isn't
  waited out (that would keep keys unlocked): the run ends as `RateLimited`,
  and the client sends nothing to Proton until that time, which a later,
  shorter `Retry-After` never moves earlier. A stopped client (session or app
  version refused) sends nothing.
- **Writes (C4–C6):** sent once. A 429 fails as `RateLimited` and stops
  sending ([sync.md § Writes](sync.md#writes)), and a `Retry-After` over 30 s
  on a 429 or 5xx blocks the client as for reads. A 401 refreshes and resends
  once (the server acted on nothing). C4 and C6 succeed with 1001 (any other
  reply `Code` is an error), C5 with 1000. An item's unexpected `Code` is a typed error that
  never stops the client. Replies carry **no cards**, and Proton doesn't store
  the sent card bytes exactly, so the new state comes from re-reading C2 and
  is compared by decrypted content, never bytes. A lost connection, a 5xx, or
  a 2xx that doesn't decode leaves the outcome unknown, which the next run
  resolves ([sync.md § Writes](sync.md#writes)): an update or delete is
  re-fetched before anything is sent again, and a create is sent again with
  the same UID, then looked up with C1 if refused with 2500 or 2001 (one
  whose row was deleted meanwhile is only looked up). A repeated C4 with the
  same UID is refused with 2500 (`Overwrite: 1` would replace; never sent),
  and a repeated C6 succeeds, so both are safe to repeat.

## Contact inventory

An inventory is **complete** only when every page succeeded and either the
unique IDs equal a `Total` that is the same on every page, or, with no
`Total`, a short or empty page ended it. Reaching the `Total` on a full page
needs one more page that comes back empty. It is **incomplete** on a failed
page, a repeated page (non-empty, no new ID), a `Total` that is exceeded,
changes, or isn't reached by a short page, 1,000 pages without an end, or any
failed C2. Contacts that arrived may still be applied; nothing that depends on
absence (deletion) may happen.

C3 export is preferred (it includes cards). Only a 404 that fails export's
first page switches to C1 pages plus a C2 per ID. There, a C2 answering 2501
or 2061 skips that contact; any other failure, or a C2 with another contact's
`ID`, stops fetching; both leave the inventory incomplete.

## Keys

| Data | Readable by Proton? |
| --- | --- |
| Login password, mailbox password, key passphrases | Never (SRP proofs only) |
| Private keys | Only encrypted |
| Contact name and e-mail addresses | Yes: the signed card isn't encrypted |
| Other contact fields | No |

- **Passphrase:** go-srp `MailboxPassword(password, KeySalt)` per user key,
  salts matched to keys by ID (never one salt for all); the passphrase is the
  bcrypt hash after its 29-character `$2y$10$<salt>` prefix.
- **Unlocking:** only user keys. Exactly one active primary user key must
  exist and unlock, or the unlock fails. Another key that doesn't unlock is
  left out with a typed reason. Before the library sees a key, its packets
  are read: version 6, or a post-quantum algorithm (IDs 30–36: ML-DSA,
  SLH-DSA, ML-KEM) on any key or subkey, is refused with its own reason;
  another version than 4 or 6 is "unsupported"; malformed packets are
  "corrupt". Then a key the library can't parse (checked without the
  passphrase) is "unsupported", never a wrong passphrase. Inactive keys never
  encrypt or sign but still decrypt.
- **Stored:** the passphrase per user key ID and the locked key bundle, in the
  credential envelope (D22). Keys are unlocked at the start of a run and
  wiped when it ends. The event loop fetches the bundle again with U1 after a
  key change; passphrases carry over by key ID, so a new key has none until
  the next login.
- **Address keys** aren't unlocked: no feature uses them. When one does, its
  `Token` is decrypted with the user key and its `Signature` verified before
  use; with both absent, the key passphrase unlocks it (the legacy form); one
  without the other, or a bad signature, rejects that key.
- **Contact cards** use user keys only: read with all of them, encrypt to and
  sign with the active primary. Missing or ambiguous → hold the write.

## Contact cards

`Type` is a bit field: encrypted = 1, signed = 2.

| Type | Content | Signature |
| --- | --- | --- |
| 0 clear | Cleartext properties (usually `CATEGORIES`) | none |
| 1 encrypted (legacy) | Armored ciphertext | none; decryption is integrity-checked |
| 2 signed | `FN`, `EMAIL`, `UID`, `VERSION`, `PRODID`, and any `KEY` and `X-PM-*` | detached text-mode signature over the text with trailing spaces and tabs stripped from each line |
| 3 encrypted + signed | Every other property, encrypted to the primary user key | detached text-mode signature over the plaintext, no stripping |

**Reading:** every card is decrypted and verified. Any failure (bad
signature, corrupt data, wrong key, unsupported algorithm) rejects the
**whole contact**: an existing row stays as it is and a new one isn't
inserted. A `Signature` on type 0 or 1 is ignored; a type 2 or 3 without one,
or an unknown type, fails. An encrypted card must be public-key session key
packets followed by one integrity-protected data packet (tag 18) and nothing
after it. Signatures verify against any unlocked user key, ignoring key
expiry. (Proton's web app shows a contact whose signature fails, with a
warning; this app doesn't, D8.)

**Writing:** an existing contact's cards keep their lines, and new lines go
into types 2 and 3 ([mapping.md § Writing](mapping.md#writing)). The app
writes types 0, 2, and 3 only, so a legacy type 1 card comes back as type 3. A
failed encryption or signature holds the write; there is never a cleartext
fallback. Every new `EMAIL` gets a vCard group (`itemN`), as Proton's web app
writes one: Proton refused a create whose signed card held an ungrouped
`EMAIL`.

## Content fingerprints

`contact-fp-v1` is SHA-256 over, in order: the ASCII tag `contact-fp-v1`, the
contact ID, then for each card in response order its `Type` (8-byte
big-endian), its `Data`, and its `Signature` (a presence byte, `0x01` followed
by the value or `0x00` alone). Each string, tag and ID included, is an 8-byte
big-endian length followed by its UTF-8 bytes. Stored as
`contact-fp-v1:<64 hex digits>`.

Known answer: ID `id-1` with cards `(2, "ab", "sig")` and `(0, "c", absent)`
is 81 bytes of input and gives
`contact-fp-v1:ebf802db1d0e3db7e30ae959c445c8944aa9246556f89562f0aced6c5844f2f6`.

C2 and C3 return identical cards, so switching between them isn't a change.
Never hash re-serialized JSON or decrypted text. Changing what is hashed
needs a new tag and a one-time re-baseline.

## Unknown

- **UNKNOWN:** how E2 answers a cursor that has expired. An unknown or
  malformed cursor answers HTTP 400 `Code` 2061, which restarts the loop; any
  other failure is a failed poll, and the scheduled full syncs go on.
- **UNKNOWN:** an identifier Proton accepts for an honest third-party
  contacts client. Self-named ones are refused (`Code` 2064 or 5002), so the
  app sends `Other` (D4).
