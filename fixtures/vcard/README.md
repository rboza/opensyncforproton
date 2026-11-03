# vCard fixtures (test-only)

Hand-written, fictional contacts: every name, address, and number is made up
(`example.org` addresses, UK phone numbers from Ofcom's range for drama), and
must never be replaced with real data. `:mapping`'s tests read them from the
classpath (`mapping/build.gradle.kts` adds this directory as a test resource
directory).

| File | Contents |
| --- | --- |
| `full.vcf` | One contact shaped like a Proton contact — a signed card (`FN`, `EMAIL`, `UID`), then an encrypted card with everything else — covering every field in docs/mapping.md § Field table and the unmapped properties it lists (ContactMapperTest, "the full fixture maps every field") |

Other `:mapping` cases stay inline in the tests, next to their assertions.
