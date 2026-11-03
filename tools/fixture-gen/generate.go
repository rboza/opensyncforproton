package fixturegen

import (
	"bytes"
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"

	"github.com/ProtonMail/go-crypto/openpgp/armor"
	"github.com/ProtonMail/go-crypto/openpgp/packet"
	"github.com/ProtonMail/go-srp"
	"github.com/ProtonMail/gopenpgp/v3/crypto"
	"github.com/ProtonMail/gopenpgp/v3/profile"
)

// fixedTime is every key's creation time and every signature's time:
// 2026-01-01T00:00:00Z.
const fixedTime = 1767225600

const testOnly = "TEST-ONLY data from tools/fixture-gen. Never use these keys or passwords for anything real."

const loginPassword = "test-only pässwörd 1"

//go:embed official/*.asc
var official embed.FS

var pgp = crypto.PGPWithProfile(profile.Default())

// Generate returns every fixture file by name. Its randomness comes from
// crypto/rand, which the tests replace with a seeded source.
func Generate() (map[string][]byte, error) {
	srp.RandReader = rand.Reader

	files := map[string][]byte{}
	add := func(name string, v any) error {
		var buf bytes.Buffer
		enc := json.NewEncoder(&buf)
		enc.SetEscapeHTML(false)
		enc.SetIndent("", "  ")
		if err := enc.Encode(v); err != nil {
			return fmt.Errorf("%s: %w", name, err)
		}
		files[name] = buf.Bytes()
		return nil
	}

	srpFixture, err := generateSrp()
	if err != nil {
		return nil, fmt.Errorf("srp: %w", err)
	}
	keys, userKey, addressKey, err := generateKeys()
	if err != nil {
		return nil, fmt.Errorf("keys: %w", err)
	}
	srpFixture.KeyPassphrase = KeyPassphraseVector{
		Password:   loginPassword,
		KeySalt:    keys.KeySalts[0].KeySalt,
		Passphrase: mustPassphrase(loginPassword, keys.KeySalts[0].KeySalt),
	}
	contacts, err := generateContacts(userKey, addressKey)
	if err != nil {
		return nil, fmt.Errorf("contacts: %w", err)
	}
	unsupported, err := unsupportedKeys()
	if err != nil {
		return nil, fmt.Errorf("unsupported keys: %w", err)
	}

	for name, v := range map[string]any{
		"srp.json":              srpFixture,
		"keys.json":             keys,
		"contacts.json":         contacts,
		"unsupported-keys.json": unsupported,
	} {
		if err := add(name, v); err != nil {
			return nil, err
		}
	}
	files["README.md"] = []byte(readme)
	return files, nil
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		panic(err)
	}
	return b
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// passphrase is go-srp's MailboxPassword output after the "$2y$10$<salt>"
// prefix (docs/protocol.md § Keys).
func passphrase(password, b64Salt string) (string, error) {
	salt, err := base64.StdEncoding.DecodeString(b64Salt)
	if err != nil {
		return "", err
	}
	hashed, err := srp.MailboxPassword([]byte(password), salt)
	if err != nil {
		return "", err
	}
	return string(hashed[29:]), nil
}

func mustPassphrase(password, b64Salt string) string {
	p, err := passphrase(password, b64Salt)
	if err != nil {
		panic(err)
	}
	return p
}

// recordingReader remembers every byte it hands out.
type recordingReader struct {
	r   io.Reader
	got bytes.Buffer
}

func (rr *recordingReader) Read(p []byte) (int, error) {
	n, err := rr.r.Read(p)
	rr.got.Write(p[:n])
	return n, err
}

func signedModulus() (string, error) {
	b, err := official.ReadFile("official/go-srp-test-modulus.asc")
	if err != nil {
		return "", err
	}
	// The clear-signed message must end at its last line; anything after it
	// is rejected.
	return strings.TrimRight(string(b), "\n"), nil
}

func generateSrp() (SrpFixture, error) {
	const bits = 2048
	const username = "test-only-user"
	modulus, err := signedModulus()
	if err != nil {
		return SrpFixture{}, err
	}
	salt := randomBytes(10)

	verifierAuth, err := srp.NewAuthForVerifier([]byte(loginPassword), modulus, salt)
	if err != nil {
		return SrpFixture{}, err
	}
	verifier, err := verifierAuth.GenerateVerifier(bits)
	if err != nil {
		return SrpFixture{}, err
	}
	modulusBytes, err := base64.StdEncoding.DecodeString(strings.Split(modulus, "\n")[3])
	if err != nil {
		return SrpFixture{}, err
	}
	hashedPassword, err := srp.HashPassword(4, []byte(loginPassword), username, salt, modulusBytes)
	if err != nil {
		return SrpFixture{}, err
	}
	serverSecret := randomBytes(bits / 8)
	server, err := srp.NewServerWithSecret(modulusBytes, verifier, serverSecret, bits)
	if err != nil {
		return SrpFixture{}, err
	}
	serverEphemeral, err := server.GenerateChallenge()
	if err != nil {
		return SrpFixture{}, err
	}

	auth, err := srp.NewAuth(4, username, []byte(loginPassword), b64(salt), modulus, b64(serverEphemeral))
	if err != nil {
		return SrpFixture{}, err
	}
	rec := &recordingReader{r: rand.Reader}
	srp.RandReader = rec
	proofs, err := auth.GenerateProofs(bits)
	srp.RandReader = rand.Reader
	if err != nil {
		return SrpFixture{}, err
	}
	serverProof, err := server.VerifyProofs(proofs.ClientEphemeral, proofs.ClientProof)
	if err != nil {
		return SrpFixture{}, fmt.Errorf("the server rejected the client proof: %w", err)
	}
	if !bytes.Equal(serverProof, proofs.ExpectedServerProof) {
		return SrpFixture{}, errors.New("server proof differs from the client's expectation")
	}

	// Flip one bit of the first modulus character: 'W' (010110) → 'X' (010111).
	firstLine := strings.Split(modulus, "\n")[3]
	if firstLine[0] != 'W' {
		return SrpFixture{}, errors.New("unexpected modulus text")
	}
	tampered := strings.Replace(modulus, firstLine, "X"+firstLine[1:], 1)

	return SrpFixture{
		TestOnly:              true,
		Comment:               testOnly,
		Version:               4,
		Username:              username,
		Password:              loginPassword,
		Salt:                  b64(salt),
		SignedModulus:         modulus,
		ModulusSource:         "Proton-signed test modulus copied from github.com/ProtonMail/go-srp v0.0.7 srp_test.go (testModulusClearSign)",
		TamperedSignedModulus: tampered,
		Modulus:               b64(modulusBytes),
		HashedPassword:        b64(hashedPassword),
		Verifier:              b64(verifier),
		ServerSecret:          b64(serverSecret),
		ServerEphemeral:       b64(serverEphemeral),
		ClientRandom:          b64(rec.got.Bytes()),
		ClientEphemeral:       b64(proofs.ClientEphemeral),
		ClientProof:           b64(proofs.ClientProof),
		ExpectedServerProof:   b64(proofs.ExpectedServerProof),
	}, nil
}

func newKey(name string) (*crypto.Key, error) {
	return pgp.KeyGeneration().
		AddUserId("Test Only "+name, name+"@example.invalid").
		GenerationTime(fixedTime).
		New().
		GenerateKey()
}

func lockedArmored(key *crypto.Key, passphrase string) (string, error) {
	locked, err := pgp.LockKey(key, []byte(passphrase))
	if err != nil {
		return "", err
	}
	return locked.Armor()
}

func encrypt(to *crypto.Key, text string) (string, error) {
	enc, err := pgp.Encryption().Recipient(to).EncryptionTime(fixedTime).New()
	if err != nil {
		return "", err
	}
	msg, err := enc.Encrypt([]byte(text))
	if err != nil {
		return "", err
	}
	return msg.Armor()
}

// signText makes a detached text-mode signature, as Proton's clients do for
// contact cards and address-key tokens.
func signText(by *crypto.Key, text string) (string, error) {
	signer, err := pgp.Sign().SigningKey(by).Detached().Utf8().SignTime(fixedTime).New()
	if err != nil {
		return "", err
	}
	sig, err := signer.Sign([]byte(text), crypto.Armor)
	return string(sig), err
}

func generateKeys() (KeysFixture, *crypto.Key, *crypto.Key, error) {
	fail := func(err error) (KeysFixture, *crypto.Key, *crypto.Key, error) {
		return KeysFixture{}, nil, nil, err
	}
	salts := []KeySalt{
		{ID: "user-key-1", KeySalt: b64(randomBytes(16))},
		{ID: "user-key-2", KeySalt: b64(randomBytes(16))},
		{ID: "address-key-legacy", KeySalt: b64(randomBytes(16))},
	}
	saltOf := map[string]string{}
	for _, s := range salts {
		saltOf[s.ID] = s.KeySalt
	}

	var userKeys []UserKey
	var primaryUserKey *crypto.Key
	for _, uk := range []struct {
		id              string
		active, primary bool
	}{{"user-key-1", true, true}, {"user-key-2", false, false}} {
		key, err := newKey(uk.id)
		if err != nil {
			return fail(err)
		}
		armored, err := lockedArmored(key, mustPassphrase(loginPassword, saltOf[uk.id]))
		if err != nil {
			return fail(err)
		}
		if uk.primary {
			primaryUserKey = key
		}
		userKeys = append(userKeys, UserKey{ID: uk.id, Active: uk.active, Primary: uk.primary, ArmoredPrivateKey: armored})
	}

	// Token form: a random hex token, encrypted to and signed by the user key,
	// is the address key's passphrase.
	tokenKey, err := newKey("address-key-1")
	if err != nil {
		return fail(err)
	}
	tokenAddress, err := tokenAddressKey("address-key-1", "address-1", tokenKey, primaryUserKey)
	if err != nil {
		return fail(err)
	}

	legacyKey, err := newKey("address-key-legacy")
	if err != nil {
		return fail(err)
	}
	legacyArmored, err := lockedArmored(legacyKey, mustPassphrase(loginPassword, saltOf["address-key-legacy"]))
	if err != nil {
		return fail(err)
	}

	broken, err := brokenAddressKeys(primaryUserKey)
	if err != nil {
		return fail(err)
	}

	return KeysFixture{
		TestOnly:      true,
		Comment:       testOnly,
		LoginPassword: loginPassword,
		KeySalts:      salts,
		UserKeys:      userKeys,
		AddressKeys: []AddressKey{
			tokenAddress,
			{ID: "address-key-legacy", AddressID: "address-2", Form: "legacy", Active: true, Primary: true, ArmoredPrivateKey: legacyArmored},
		},
		BrokenAddressKeys: broken,
	}, primaryUserKey, tokenKey, nil
}

func tokenAddressKey(id, addressID string, key, userKey *crypto.Key) (AddressKey, error) {
	token := hex.EncodeToString(randomBytes(32))
	armored, err := lockedArmored(key, token)
	if err != nil {
		return AddressKey{}, err
	}
	encToken, err := encrypt(userKey, token)
	if err != nil {
		return AddressKey{}, err
	}
	sig, err := signText(userKey, token)
	if err != nil {
		return AddressKey{}, err
	}
	return AddressKey{ID: id, AddressID: addressID, Form: "token", Active: true, Primary: true,
		ArmoredPrivateKey: armored, Token: encToken, Signature: sig}, nil
}

func brokenAddressKeys(userKey *crypto.Key) ([]BrokenAddressKey, error) {
	var out []BrokenAddressKey
	for _, c := range []string{"token_without_signature", "signature_without_token", "signature_over_other_token"} {
		key, err := newKey("broken-" + c)
		if err != nil {
			return nil, err
		}
		good, err := tokenAddressKey("broken-"+c, "address-broken", key, userKey)
		if err != nil {
			return nil, err
		}
		b := BrokenAddressKey{ID: good.ID, Case: c, ArmoredPrivateKey: good.ArmoredPrivateKey}
		switch c {
		case "token_without_signature":
			b.Token = good.Token
		case "signature_without_token":
			b.Signature = good.Signature
		case "signature_over_other_token":
			b.Token = good.Token
			if b.Signature, err = signText(userKey, hex.EncodeToString(randomBytes(32))); err != nil {
				return nil, err
			}
		}
		out = append(out, b)
	}
	return out, nil
}

// stripTrailingSpaces removes spaces and tabs at the end of every line, as
// Proton's clients do before signing a type 2 card.
func stripTrailingSpaces(text string) string {
	lines := strings.Split(text, "\n")
	for i, l := range lines {
		cr := strings.HasSuffix(l, "\r")
		l = strings.TrimRight(strings.TrimSuffix(l, "\r"), " \t")
		if cr {
			l += "\r"
		}
		lines[i] = l
	}
	return strings.Join(lines, "\n")
}

func vcard(lines ...string) string {
	return "BEGIN:VCARD\r\nVERSION:4.0\r\n" + strings.Join(lines, "\r\n") + "\r\nEND:VCARD\r\n"
}

func uuidURN() string {
	b := randomBytes(16)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("urn:uuid:%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func generateContacts(userKey, addressKey *crypto.Key) (ContactsFixture, error) {
	unrelated, err := newKey("unrelated")
	if err != nil {
		return ContactsFixture{}, err
	}
	const prodid = "PRODID:-//Test Only//fixture-gen//EN"

	clear := func(text string) Card { return Card{Type: 0, Data: text} }
	encrypted := func(to *crypto.Key, text string) (Card, error) {
		data, err := encrypt(to, text)
		return Card{Type: 1, Data: data}, err
	}
	signed := func(by *crypto.Key, text string) (Card, error) {
		sig, err := signText(by, stripTrailingSpaces(text))
		return Card{Type: 2, Data: text, Signature: sig}, err
	}
	encryptedSigned := func(to, by *crypto.Key, text string) (Card, error) {
		data, err := encrypt(to, text)
		if err != nil {
			return Card{}, err
		}
		sig, err := signText(by, text)
		return Card{Type: 3, Data: data, Signature: sig}, err
	}

	var contacts []Contact
	n := 0
	addContact := func(name, expect string, plaintexts []string, cards ...func() (Card, error)) error {
		n++
		c := Contact{Name: name, ID: fmt.Sprintf("test-only-contact-%d", n), Expect: expect, Plaintexts: plaintexts}
		for _, mk := range cards {
			card, err := mk()
			if err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
			c.Cards = append(c.Cards, card)
		}
		contacts = append(contacts, c)
		return nil
	}

	clearText := vcard(prodid, "CATEGORIES:test-only")
	signedText := vcard(prodid, "FN:Test Only Person", "UID:"+uuidURN(), "ITEM1.EMAIL;TYPE=work:test-only@example.org")
	trailingText := vcard(prodid, "FN:Test Only Spaces  ", "UID:"+uuidURN())
	legacyText := vcard("NICKNAME:tester")
	secretText := vcard("N:Person;Test Only;;;", "TEL;TYPE=cell:+1 555 0100", "NOTE:test-only note")

	steps := []error{
		addContact("all_types", "ok", []string{clearText, legacyText, signedText, secretText},
			func() (Card, error) { return clear(clearText), nil },
			func() (Card, error) { return encrypted(userKey, legacyText) },
			func() (Card, error) { return signed(userKey, signedText) },
			func() (Card, error) { return encryptedSigned(userKey, userKey, secretText) },
		),
		addContact("signed_only", "ok", []string{signedText},
			func() (Card, error) { return signed(userKey, signedText) },
		),
		addContact("type2_trailing_spaces", "ok", []string{trailingText},
			func() (Card, error) { return signed(userKey, trailingText) },
		),
		addContact("type2_data_changed_after_signing", "rejected", nil,
			func() (Card, error) {
				c, err := signed(userKey, signedText)
				c.Data = strings.Replace(c.Data, "FN:Test Only Person", "FN:Someone Else", 1)
				return c, err
			},
		),
		addContact("type3_signed_by_unrelated_key", "rejected", nil,
			func() (Card, error) { return encryptedSigned(userKey, unrelated, secretText) },
		),
		addContact("type3_encrypted_to_unrelated_key", "rejected", nil,
			func() (Card, error) { return encryptedSigned(unrelated, userKey, secretText) },
		),
		addContact("type3_corrupt_data", "rejected", nil,
			func() (Card, error) {
				c, err := encryptedSigned(userKey, userKey, secretText)
				c.Data = corruptArmor(c.Data)
				return c, err
			},
		),
		addContact("type3_signed_by_address_key", "verifies_with_address_key_only", nil,
			func() (Card, error) { return encryptedSigned(userKey, addressKey, secretText) },
		),
		// Messages that hold only literal data: a library may "decrypt" them,
		// but nothing was encrypted or integrity-checked.
		addContact("type1_not_encrypted", "rejected_not_encrypted", nil,
			func() (Card, error) {
				data, err := literalOnly(legacyText)
				return Card{Type: 1, Data: data}, err
			},
		),
		addContact("type3_not_encrypted", "rejected_not_encrypted", nil,
			func() (Card, error) {
				data, err := literalOnly(secretText)
				if err != nil {
					return Card{}, err
				}
				sig, err := signText(userKey, secretText)
				return Card{Type: 3, Data: data, Signature: sig}, err
			},
		),
	}
	if err := errors.Join(steps...); err != nil {
		return ContactsFixture{}, err
	}
	return ContactsFixture{TestOnly: true, Comment: testOnly, Contacts: contacts}, nil
}

// literalOnly armors text as a PGP MESSAGE holding only a Literal Data packet:
// no session key, no encryption, no integrity check.
func literalOnly(text string) (string, error) {
	var buf bytes.Buffer
	w, err := armor.Encode(&buf, "PGP MESSAGE", nil)
	if err != nil {
		return "", err
	}
	lit, err := packet.SerializeLiteral(w, false, "", uint32(fixedTime))
	if err != nil {
		return "", err
	}
	if _, err := lit.Write([]byte(text)); err != nil {
		return "", err
	}
	// Closing the literal writer closes the armor writer under it, which writes the footer.
	if err := lit.Close(); err != nil {
		return "", err
	}
	return buf.String(), nil
}

// corruptArmor flips one character in the middle of the armored body, so the
// armor still parses but the packets don't.
func corruptArmor(armored string) string {
	lines := strings.Split(armored, "\n")
	for i := len(lines) / 2; i < len(lines); i++ {
		l := lines[i]
		if len(l) > 20 && !strings.HasPrefix(l, "-----") && !strings.Contains(l, ":") {
			c := l[10]
			r := byte('A')
			if c == 'A' {
				r = 'B'
			}
			lines[i] = l[:10] + string(r) + l[11:]
			return strings.Join(lines, "\n")
		}
	}
	panic("no body line to corrupt")
}

func unsupportedKeys() (UnsupportedKeysFixture, error) {
	keys := []UnsupportedKey{
		{Name: "v6_ed25519_rfc9580", Kind: "v6",
			Source: "RFC 9580 Appendix A.3 sample v6 secret key (copied from github.com/ProtonMail/go-crypto v1.5.2 openpgp/v2/read_write_test_data.go)"},
		{Name: "v4_eddsa_mlkem768_pqc_draft", Kind: "post-quantum",
			Source: "draft-ietf-openpgp-pqc sample v4 EdDSA key with ML-KEM-768+X25519 subkey (copied from github.com/ProtonMail/go-crypto v1.5.2 openpgp/test_data/pqc/v4-eddsa-sample-sk.asc)"},
		{Name: "v6_mldsa65_mlkem768_pqc_draft", Kind: "v6, post-quantum",
			Source: "draft-ietf-openpgp-pqc sample v6 ML-DSA-65 key with ML-KEM-768 subkey (copied from github.com/ProtonMail/go-crypto v1.5.2 openpgp/test_data/pqc/v6-mldsa-65-sample-sk.asc)"},
	}
	files := map[string]string{
		"v6_ed25519_rfc9580":            "official/rfc9580-a3-v6-ed25519-sk.asc",
		"v4_eddsa_mlkem768_pqc_draft":   "official/pqc-draft-v4-eddsa-mlkem768-sk.asc",
		"v6_mldsa65_mlkem768_pqc_draft": "official/pqc-draft-v6-mldsa65-mlkem768-sk.asc",
	}
	for i := range keys {
		b, err := official.ReadFile(files[keys[i].Name])
		if err != nil {
			return UnsupportedKeysFixture{}, err
		}
		keys[i].ArmoredPrivateKey = string(b)
	}
	return UnsupportedKeysFixture{TestOnly: true, Comment: testOnly, Keys: keys}, nil
}

const readme = `# Crypto fixtures (test-only)

Generated by ` + "`tools/fixture-gen`" + `; don't edit by hand. Every key, password,
and ciphertext here is test-only and must never be used for real data.

Regenerate from ` + "`tools/fixture-gen`" + ` with ` + "`go test ./... -update`" + `; a plain
` + "`go test ./...`" + ` fails if these files are stale. The output is reproducible
for the pinned Go and module versions (testing/cryptotest seeds every random
choice); upgrading either may change bytes, which then show up in review.

| File | Contents |
| --- | --- |
| ` + "`srp.json`" + ` | One SRP login vector (Proton-signed test modulus from go-srp), a tampered modulus, and a key-passphrase vector |
| ` + "`keys.json`" + ` | A test account: key salts by key ID, two user keys (one inactive), a token-form and a legacy address key, and three broken address keys |
| ` + "`contacts.json`" + ` | Contacts with cards of types 0–3, plus rejected variants and one signed by an address key, which is rejected because cards verify with user keys only |
| ` + "`unsupported-keys.json`" + ` | v6 and post-quantum keys from RFC 9580 and draft-ietf-openpgp-pqc, which the app refuses |
`
