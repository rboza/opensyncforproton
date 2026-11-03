# HTTP fixtures (structure only)

Captured from a throwaway Proton account and redacted before saving. In the
login, key, and read replies, every string is replaced by a same-length
`GENERATED-…` placeholder (armored blocks keep only their header), numbers are
zeroed except codes, flags, and small enums (`Code`, `PasswordMode`, `Version`,
`Active`, `Primary`, `Flags`, `Type`, `Status`, `Order`, `Send`, `Receive`,
`Total`, …), and `…Time` fields become 1767225600. Scope names and captcha
method names are kept. A secret scan ran before anything was written.

The write and event replies (`contact-create*`, `contact-update*`,
`contacts-delete*`, `events*`; the `events-update` page comes from a
follow-up run): strings replaced, numbers zeroed except
`Code`, `Action`, `Index`, `Type`, `Defaults`, `IsProton`, `Order`, `More`,
and `Refresh`, times replaced, and both a secret scan and a second check
that every string is a placeholder.

Each file is `{route, status, retryAfter, body}`. They pin the response
**shapes** for DTO and client tests; real key material and contacts for
crypto tests come from `fixtures/crypto/`.
