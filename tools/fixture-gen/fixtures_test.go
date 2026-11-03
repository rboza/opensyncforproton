package fixturegen

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"testing/cryptotest"

	"github.com/ProtonMail/go-crypto/openpgp/armor"
	"github.com/ProtonMail/go-crypto/openpgp/packet"
	"github.com/ProtonMail/go-srp"
	"github.com/ProtonMail/gopenpgp/v3/crypto"
)

// Regenerate with: go test ./... -update (from tools/fixture-gen).
var update = flag.Bool("update", false, "rewrite the committed fixtures")

const outDir = "../../fixtures/crypto"

// seed makes every random choice in Generate reproducible. Changing it
// regenerates every key and ciphertext.
const seed = 20260929

func generate(t *testing.T) map[string][]byte {
	t.Helper()
	cryptotest.SetGlobalRandom(t, seed)
	files, err := Generate()
	if err != nil {
		t.Fatal(err)
	}
	return files
}

func TestGenerateIsDeterministic(t *testing.T) {
	first := generate(t)
	second := generate(t)
	for name, data := range first {
		if !bytes.Equal(data, second[name]) {
			t.Errorf("%s differs between two runs with the same seed", name)
		}
	}
}

func TestCommittedFixturesAreCurrent(t *testing.T) {
	files := generate(t)
	if *update {
		if err := os.MkdirAll(outDir, 0o755); err != nil {
			t.Fatal(err)
		}
		for name, data := range files {
			if err := os.WriteFile(filepath.Join(outDir, name), data, 0o644); err != nil {
				t.Fatal(err)
			}
		}
		return
	}
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		committed, err := os.ReadFile(filepath.Join(outDir, name))
		if err != nil {
			t.Fatalf("%s: %v (run go test ./... -update)", name, err)
		}
		if !bytes.Equal(committed, files[name]) {
			t.Errorf("%s is stale (run go test ./... -update)", name)
		}
	}
}

func load[T any](t *testing.T, files map[string][]byte, name string) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(files[name], &v); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return v
}

func unb64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestSrpVector(t *testing.T) {
	v := load[SrpFixture](t, generate(t), "srp.json")

	auth, err := srp.NewAuth(v.Version, v.Username, []byte(v.Password), v.Salt, v.SignedModulus, v.ServerEphemeral)
	if err != nil {
		t.Fatal(err)
	}
	srp.RandReader = bytes.NewReader(unb64(t, v.ClientRandom))
	proofs, err := auth.GenerateProofs(2048)
	if err != nil {
		t.Fatal(err)
	}
	if got := base64.StdEncoding.EncodeToString(proofs.ClientEphemeral); got != v.ClientEphemeral {
		t.Error("client ephemeral doesn't match the vector")
	}
	if got := base64.StdEncoding.EncodeToString(proofs.ClientProof); got != v.ClientProof {
		t.Error("client proof doesn't match the vector")
	}
	if got := base64.StdEncoding.EncodeToString(proofs.ExpectedServerProof); got != v.ExpectedServerProof {
		t.Error("expected server proof doesn't match the vector")
	}

	if _, err := srp.NewAuth(v.Version, v.Username, []byte(v.Password), v.Salt, v.TamperedSignedModulus, v.ServerEphemeral); err == nil {
		t.Error("a tampered modulus was accepted")
	}

	// Deterministic parts, for clients whose random source can't be replaced.
	hashed, err := srp.HashPassword(v.Version, []byte(v.Password), v.Username, unb64(t, v.Salt), unb64(t, v.Modulus))
	if err != nil {
		t.Fatal(err)
	}
	if got := base64.StdEncoding.EncodeToString(hashed); got != v.HashedPassword {
		t.Error("hashed password doesn't match the vector")
	}
	server, err := srp.NewServerWithSecret(unb64(t, v.Modulus), unb64(t, v.Verifier), unb64(t, v.ServerSecret), 2048)
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := server.GenerateChallenge()
	if err != nil {
		t.Fatal(err)
	}
	if got := base64.StdEncoding.EncodeToString(challenge); got != v.ServerEphemeral {
		t.Error("the server secret doesn't give the vector's server ephemeral")
	}
	srp.RandReader = rand.Reader
	fresh, err := auth.GenerateProofs(2048)
	if err != nil {
		t.Fatal(err)
	}
	serverProof, err := server.VerifyProofs(fresh.ClientEphemeral, fresh.ClientProof)
	if err != nil {
		t.Fatalf("the server rejected a fresh client's proof: %v", err)
	}
	if !bytes.Equal(serverProof, fresh.ExpectedServerProof) {
		t.Error("server proof differs from the fresh client's expectation")
	}

	mailbox, err := srp.MailboxPassword([]byte(v.KeyPassphrase.Password), unb64(t, v.KeyPassphrase.KeySalt))
	if err != nil {
		t.Fatal(err)
	}
	if got := string(mailbox[29:]); got != v.KeyPassphrase.Passphrase {
		t.Error("key passphrase doesn't match the vector")
	}
}

func unlock(t *testing.T, armored, passphrase string) *crypto.Key {
	t.Helper()
	locked, err := crypto.NewKeyFromArmored(armored)
	if err != nil {
		t.Fatal(err)
	}
	key, err := locked.Unlock([]byte(passphrase))
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func TestKeysUnlock(t *testing.T) {
	k := load[KeysFixture](t, generate(t), "keys.json")
	salts := map[string]string{}
	for _, s := range k.KeySalts {
		salts[s.ID] = s.KeySalt
	}
	passphrase := func(id string) string {
		salt, ok := salts[id]
		if !ok {
			t.Fatalf("no salt for %s", id)
		}
		hashed, err := srp.MailboxPassword([]byte(k.LoginPassword), unb64(t, salt))
		if err != nil {
			t.Fatal(err)
		}
		return string(hashed[29:])
	}

	userKeys := map[string]*crypto.Key{}
	for _, uk := range k.UserKeys {
		userKeys[uk.ID] = unlock(t, uk.ArmoredPrivateKey, passphrase(uk.ID))
	}
	if len(userKeys) != 2 {
		t.Fatalf("want 2 user keys, got %d", len(userKeys))
	}
	userRing, err := crypto.NewKeyRing(userKeys[k.UserKeys[0].ID])
	if err != nil {
		t.Fatal(err)
	}

	for _, ak := range k.AddressKeys {
		switch ak.Form {
		case "token":
			token := decryptAndVerify(t, userRing, ak.Token, ak.Signature)
			unlock(t, ak.ArmoredPrivateKey, token)
		case "legacy":
			if ak.Token != "" || ak.Signature != "" {
				t.Errorf("%s: a legacy key has no token or signature", ak.ID)
			}
			unlock(t, ak.ArmoredPrivateKey, passphrase(ak.ID))
		default:
			t.Errorf("%s: unknown form %q", ak.ID, ak.Form)
		}
	}

	for _, bk := range k.BrokenAddressKeys {
		ok := bk.Token != "" && bk.Signature != "" && verifies(t, userRing, bk.Token, bk.Signature)
		if ok {
			t.Errorf("%s (%s) should be rejected", bk.ID, bk.Case)
		}
	}
}

func decryptAndVerify(t *testing.T, ring *crypto.KeyRing, armoredMessage, armoredSignature string) string {
	t.Helper()
	dec, err := crypto.PGP().Decryption().DecryptionKeys(ring).New()
	if err != nil {
		t.Fatal(err)
	}
	res, err := dec.Decrypt([]byte(armoredMessage), crypto.Armor)
	if err != nil {
		t.Fatal(err)
	}
	ver, err := crypto.PGP().Verify().VerificationKeys(ring).New()
	if err != nil {
		t.Fatal(err)
	}
	vr, err := ver.VerifyDetached(res.Bytes(), []byte(armoredSignature), crypto.Armor)
	if err != nil {
		t.Fatal(err)
	}
	if err := vr.SignatureError(); err != nil {
		t.Fatalf("signature: %v", err)
	}
	return res.String()
}

func verifies(t *testing.T, ring *crypto.KeyRing, armoredMessage, armoredSignature string) bool {
	t.Helper()
	dec, err := crypto.PGP().Decryption().DecryptionKeys(ring).New()
	if err != nil {
		t.Fatal(err)
	}
	res, err := dec.Decrypt([]byte(armoredMessage), crypto.Armor)
	if err != nil {
		return false
	}
	ver, err := crypto.PGP().Verify().VerificationKeys(ring).New()
	if err != nil {
		t.Fatal(err)
	}
	vr, err := ver.VerifyDetached(res.Bytes(), []byte(armoredSignature), crypto.Armor)
	return err == nil && vr.SignatureError() == nil
}

// encryptedToKeys checks an encrypted card's packets: one or more public-key
// session key packets, then integrity-protected encrypted data. It is coarser
// than :crypto's isEncryptedToKeys (go-crypto's reader skips marker packets,
// and this doesn't look past the data); it only has to show that each
// fixture's message is what its expectation says.
func encryptedToKeys(armored string) error {
	block, err := armor.Decode(strings.NewReader(armored))
	if err != nil {
		return err
	}
	r := packet.NewReader(block.Body)
	sawKey := false
	for {
		p, err := r.Next()
		if err != nil {
			return fmt.Errorf("no encrypted data: %w", err)
		}
		switch p := p.(type) {
		case *packet.EncryptedKey:
			sawKey = true
		case *packet.SymmetricallyEncrypted:
			if !sawKey || !p.IntegrityProtected {
				return errors.New("not encrypted to a key with integrity protection")
			}
			return nil
		default:
			return fmt.Errorf("unexpected %T before the encrypted data", p)
		}
	}
}

// readContact applies the rule in docs/protocol.md § Contact cards:
// every card must decrypt and verify, or the whole contact is rejected.
func readContact(ring *crypto.KeyRing, cards []Card) ([]string, error) {
	var out []string
	for _, c := range cards {
		data := []byte(c.Data)
		if c.Type&1 == 1 {
			if err := encryptedToKeys(c.Data); err != nil {
				return nil, err
			}
			dec, err := crypto.PGP().Decryption().DecryptionKeys(ring).New()
			if err != nil {
				return nil, err
			}
			res, err := dec.Decrypt(data, crypto.Armor)
			if err != nil {
				return nil, err
			}
			data = res.Bytes()
		}
		if c.Type&2 == 2 {
			ver, err := crypto.PGP().Verify().VerificationKeys(ring).New()
			if err != nil {
				return nil, err
			}
			signed := data
			if c.Type == 2 {
				// Type 2 signatures cover the text with trailing spaces stripped.
				signed = []byte(stripTrailingSpaces(string(data)))
			}
			vr, err := ver.VerifyDetached(signed, []byte(c.Signature), crypto.Armor)
			if err != nil {
				return nil, err
			}
			if err := vr.SignatureError(); err != nil {
				return nil, err
			}
		}
		out = append(out, string(data))
	}
	return out, nil
}

func TestContactCards(t *testing.T) {
	files := generate(t)
	k := load[KeysFixture](t, files, "keys.json")
	c := load[ContactsFixture](t, files, "contacts.json")

	hashed, err := srp.MailboxPassword([]byte(k.LoginPassword), unb64(t, k.KeySalts[0].KeySalt))
	if err != nil {
		t.Fatal(err)
	}
	userKey := unlock(t, k.UserKeys[0].ArmoredPrivateKey, string(hashed[29:]))
	ring, err := crypto.NewKeyRing(userKey)
	if err != nil {
		t.Fatal(err)
	}

	types := map[int]bool{}
	for _, contact := range c.Contacts {
		t.Run(contact.Name, func(t *testing.T) {
			plain, err := readContact(ring, contact.Cards)
			switch contact.Expect {
			case "ok":
				if err != nil {
					t.Fatalf("rejected: %v", err)
				}
				for i, p := range plain {
					if p != contact.Plaintexts[i] {
						t.Errorf("card %d plaintext differs", i)
					}
					if !strings.HasPrefix(p, "BEGIN:VCARD\r\n") || !strings.HasSuffix(p, "END:VCARD\r\n") {
						t.Errorf("card %d isn't a CRLF vCard", i)
					}
				}
				for _, card := range contact.Cards {
					types[card.Type] = true
				}
			case "rejected":
				if err == nil {
					t.Fatal("accepted a contact that must be rejected")
				}
			case "rejected_not_encrypted":
				if err == nil {
					t.Fatal("accepted a card that isn't encrypted")
				}
				for _, card := range contact.Cards {
					if encryptedToKeys(card.Data) == nil {
						t.Fatal("the card is encrypted after all")
					}
				}
			case "verifies_with_address_key_only":
				if err == nil {
					t.Fatal("verified with user keys alone")
				}
			default:
				t.Fatalf("unknown expectation %q", contact.Expect)
			}
		})
	}
	var trailing *Contact
	for i := range c.Contacts {
		if c.Contacts[i].Name == "type2_trailing_spaces" {
			trailing = &c.Contacts[i]
		}
	}
	if trailing == nil || trailing.Expect != "ok" || !strings.Contains(trailing.Cards[0].Data, "  \r\n") {
		t.Error("want an ok type 2 card whose Data keeps trailing spaces")
	}
	for typ := 0; typ <= 3; typ++ {
		if !types[typ] {
			t.Errorf("no valid contact has a type %d card", typ)
		}
	}
}

func TestUnsupportedKeys(t *testing.T) {
	u := load[UnsupportedKeysFixture](t, generate(t), "unsupported-keys.json")
	want := map[string]bool{"v6_ed25519_rfc9580": true, "v4_eddsa_mlkem768_pqc_draft": true, "v6_mldsa65_mlkem768_pqc_draft": true}
	for _, k := range u.Keys {
		delete(want, k.Name)
		if k.Source == "" || !strings.Contains(k.ArmoredPrivateKey, "BEGIN PGP PRIVATE KEY BLOCK") {
			t.Errorf("%s: missing source or key", k.Name)
		}
		if _, err := crypto.NewKeyFromArmored(k.ArmoredPrivateKey); err != nil {
			t.Errorf("%s: GopenPGP can't parse it: %v", k.Name, err)
		}
	}
	for name := range want {
		t.Errorf("missing %s", name)
	}
}

func TestEveryFileIsLabelledTestOnly(t *testing.T) {
	for name, data := range generate(t) {
		if strings.HasSuffix(name, ".json") && !bytes.Contains(data, []byte(`"testOnly": true`)) {
			t.Errorf("%s isn't labelled test-only", name)
		}
	}
}
