# Contact mapping

How a Proton contact (a vCard split across cards) becomes Android contact data
and back. The vCard logic lives in `:mapping`, with no Android imports;
Android columns live in `:sync`'s column mapper.

## Reading

Each card's plaintext is parsed with ez-vcard and the properties merged into
one contact, in card order: `FN` and `N` from the first card with a non-blank
one, list fields concatenated.

- The whole contact is invalid (nothing inserted, an existing row left alone)
  when any card fails to parse, or cards disagree on a non-blank `UID`
  (trimmed, case-sensitive). A card fails to parse when it isn't exactly one
  `BEGIN:VCARD` … `END:VCARD` with nothing around it, when ez-vcard skips or
  can't decode a line, or when it has no `VERSION`. A single value that
  doesn't parse (a `BDAY` that isn't a date) only drops that field.
- A `UID` in an encrypted card is ignored, as Proton's web app strips them.
- A vCard with nothing but `VERSION`, `PRODID`, and `UID` is invalid.
- Properties Android can't show aren't mapped; uploads re-fetch the remote
  contact, so nothing needs to be kept between syncs.

## Field table

No row sets `IS_PRIMARY`. Blank values are dropped everywhere.

| vCard | Android data kind | Notes |
| --- | --- | --- |
| `FN`, `N` | `StructuredName`: `data1` display, `data2`–`data6` given, family, prefix, middle, suffix | `data1` is `FN`. Android shows a name joined from the parts, so an `FN` unlike `N` is kept but not shown (search still finds it). Extra values in an `N` component are joined with a space. |
| `X-PHONETIC-FIRST-NAME` / `-MIDDLE-NAME` / `-LAST-NAME` | `StructuredName` `data7`–`data9` | First non-blank of each; only with a name from `FN` or `N`. |
| `EMAIL` | `Email` | Value as-is. Types below. |
| `TEL` | `Phone` | A `tel:` URI gives its number, percent-decoded, plus `;ext=`. Android types without a vCard type upload without `TYPE` (lossy). |
| `ADR` | `StructuredPostal`: street `data4`, PO box `data5`, city `data7`, region `data8`, postcode `data9`, country `data10` | No `data1`: the provider composes it. Extended address not shown. Street lines joined with a line break, other multi-values with `, `. |
| `ORG`, `TITLE`, `ROLE` | `Organization` (WORK): company `data1`, units `data5` joined with `;`, title `data4`, role `data6` | First of each. |
| `PHOTO` | `Photo` `data15` | [Photos](#photos). |
| `BDAY`, `ANNIVERSARY` | `Event` BIRTHDAY / ANNIVERSARY, `YYYY-MM-DD` or `--MM-DD` | First with a month and day, any date form. No year when the vCard gives none, the year is 0, or `X-APPLE-OMIT-YEAR` names it. Text values and impossible dates aren't mapped. |
| `X-ABDATE` + `X-ABLabel` | `Event`: custom with label in `data3`, or OTHER | Label from the `X-ABLabel` in the same group, Apple's `_$!<…>!$_` wrapper removed. |
| `NOTE`, `NICKNAME` | `Note`, `Nickname` (DEFAULT) | First non-blank. |
| `URL` | `Website` (OTHER) | |
| `RELATED` | `Relation` | Android's type is kept in `X-ANDROID-TYPE` (lower-case type name, or `custom`), so every relation type round-trips. A name is text, or a URI kept as written. |
| `X-SIP` | `SipAddress` (OTHER) | A leading `sip:` is dropped. |
| `CATEGORIES`, `IMPP`, `KEY`, `X-PM-*`, `GENDER`, `REV`, `PRODID`, `VERSION`, others | — | Not mapped; kept on upload. |
| `UID` | — | Generated (a random UUID URN) for a contact created on the phone. |

`contacts.xml`'s edit schema lists only these kinds, with only the types this
table carries both ways, so the Contacts app edits synced contacts in place
([architecture.md § Android account](architecture.md#android-account)).

## Types

`TYPE` values compare case-insensitively; `pref`, `internet`, and `voice` on
`TEL` never decide a type. The first value that matches wins; with none, the
first unmatched value is a custom label (its case kept). The app's own
`X-ANDROID-LABEL` comes first ([Custom labels](#custom-labels)).

| vCard | Android type |
| --- | --- |
| `EMAIL` | `home`, `work`, `other`; none → OTHER; else custom. MOBILE reads back as OTHER. |
| `TEL` | `home`, `cell` MOBILE, `work`, `fax`+`home` FAX_HOME, `fax` FAX_WORK, `pager`, `main`, `other`; none → OTHER; else custom |
| `ADR` | `home`, `work`; anything else → OTHER |
| `RELATED` | `X-ANDROID-TYPE`, then `X-ANDROID-LABEL`, then a `TYPE` naming an Android relation; else custom with that label; none → custom without one |

These cover every type Proton's web app writes.

## Missing names

With neither `FN` nor `N`, no `StructuredName` row is written, and Android
derives the display name from other data. The app never writes a made-up
name, so a placeholder can't be uploaded as a real one.

## Photos

Only the first `PHOTO` with embedded data is mapped (a `data:` URL, or
`ENCODING=b`/`BASE64`, decoded strictly). A URL is never fetched. The media
type is only a hint.

`:sync` decodes every photo with `ImageDecoder` (sampled down), turns it
upright per EXIF, scales it so neither side exceeds the provider's maximum
display size (720 px), draws it on white, and re-encodes it as JPEG at
quality 90. A JPEG that needs none of that and is at most 512 KiB goes as it
is. One that can't be decoded, or is still over 512 KiB, rejects that
contact's write, and the existing row stays. This keeps one contact's write
within a single Binder transaction (about 1 MiB).

## Writing

**Updating** patches the remote vCard; it is never re-serialized through
ez-vcard, which changes untouched properties.

1. Re-fetch and decrypt the remote contact; map it, and read each card's
   lines as written (with vinnie).
2. Pair each phone row with its remote property by the [key](#keys) in its
   `Data.SYNC1`. A row without a key, or whose key names no remote property
   (the property changed on Proton), pairs with an unpaired property of the
   same kind and equal shown value; otherwise it is a new property.
3. For each field that differs, rewrite only the shown parts of its line,
   which stays in place in its card. Remove the lines of a property no row
   pairs with (with its group's unmapped lines), and add a line for a new one.
   Every other line stays exactly as written. List order doesn't count.
4. A new line goes last in the first card of its placement (`FN` and `EMAIL`
   signed, everything else encrypted), or a new `VERSION:4.0` card. An edited
   line stays in its own card. Nothing of the phone's is written into a clear
   card: a changed value whose property is there fails closed
   (`UNENCRYPTED_PROPERTY`); re-saving the contact in Proton's web app moves
   it. A card left empty isn't sent. Lines end in CRLF.

**Hidden parts** stay: an edit changes only what Android shows. A property's
parameters, its group and grouped properties (Apple's `X-ABLabel`),
components Android doesn't show, values after the first in a one-value field,
and parts of a date Android doesn't hold (time, offset, placeholder year)
are kept. Two exceptions: an e-mail whose address changes or is removed loses
its grouped `KEY` and `X-PM-*` (its encryption settings); and a part that held
several values uploads as one value when the phone changes it.

**Writing lines:** an edited line keeps its group and parameters, in its
card's vCard version. A type change rewrites only the type-deciding `TYPE`
values. Values are escaped vCard text; `N` and `ADR` component by component.
A `URL` and a URI `RELATED` are written as they are (backslashes doubled).
Parameter values use RFC 6868 encoding. An edited phone number is written as
text. An edited date keeps its form and time, with the date rewritten. A new
`EMAIL` gets a new group `itemN`; a new custom date with a label gets one too,
with its `X-ABLabel`. A value whose line can't be found (ez-vcard and vinnie
disagree about that property) fails closed (`UNKEYED_PROPERTY`).

**Photo:** compared by its baseline ([sync.md § Photos](sync.md#photos)).
Changed on the phone, the phone's display photo (scaled per
[Photos](#photos)) replaces the first embedded `PHOTO`'s line, as
`PHOTO:data:image/jpeg;base64,…` in vCard 4.0. Removed on the phone, every
embedded `PHOTO` goes. A URL photo always stays.

### Custom labels

`TYPE` alone can't carry every label (one spelling a type name reads as that
type; ez-vcard splits at commas). So an `EMAIL`, `TEL`, or `RELATED` custom
label is written as its `TYPE` value, which Proton's web app shows, and again
whole in `X-ANDROID-LABEL`, which Proton's web app keeps:
`EMAIL;TYPE="Home, Gym";X-ANDROID-LABEL="Home, Gym":…`. When reading,
`X-ANDROID-LABEL` wins only while `TYPE` still spells it; otherwise another
app changed the type, and [Types](#types) decides. A label a line can't hold
(a carriage return; in vCard 2.1 a colon, a line break, or leading
whitespace) fails closed (`UNWRITABLE_LABEL`).

### Creating a contact

No clear card. The signed card holds `VERSION:4.0`, `PRODID` (D4), `UID`,
`FN`, and the `EMAIL`s (each in its own `itemN` group), in that order;
everything else goes in an encrypted `VERSION:4.0` card, sent only when not
empty. The phone's photo is a new line last in the encrypted card. The `UID`
is a fresh `urn:uuid:` per create, saved before the create is sent
([sync.md § Writes](sync.md#writes)).

### `FN` on upload

Every upload carries an `FN`: the phone's display name, else one built from
the structured name parts, else the first e-mail address; otherwise the
upload is held with "add a name or e-mail" (`NO_NAME_OR_EMAIL`). A phone
number is never used as a name. An update is settled by comparing shown
values, never by whether the patch changed text.

## Keys

A property's key is its name in upper case, a dot, and the first 16 lowercase
hex digits of SHA-256 over the UTF-8 text `<placement>\n<line>`, where
placement is `CLEAR`, `SIGNED`, or `ENCRYPTED` and the line is as written in
its card, unfolded, without its line break. A repeat of the same placement and
line gets `~2`, `~3`, …. `Data.SYNC1` holds a row's keys separated by spaces:
`FN`, `N`, and the shown phonetic names for the name; `ORG`, `TITLE`, `ROLE`
for the organization; otherwise one. Anything else in `Data.SYNC1` counts as no
key. Any change to a line gives another key. Lines are matched to ez-vcard's
properties of the same name in order; where the counts differ, that name has
no keys in that card.

## Reading rows back

`:sync`'s column mapper turns a contact's data rows, in row-ID order, back
into the contact they were written from, with each value's key, so contact →
rows → contact is exact. The provider fills in what the vCard lacked (it
splits a display name into parts, or builds one from them), so a name row
reads only the parts its key names. The photo row gives the provider's
thumbnail, so photos are compared by baseline. An `ORG` unit holding an
escaped semicolon doesn't round-trip, so organizations compare by their
department text.

Rows only other apps write (a second row of a one-value kind, an event in
another date form, a kind the table doesn't name, such as IM or a group
membership) are unmapped. An upload would lose them, so a dirty row holding
them is never uploaded: it is "can't be synced".
