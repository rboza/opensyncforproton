// Package fixturegen writes the test-only crypto fixtures in fixtures/crypto.
// It runs as a Go test so that testing/cryptotest can make every random
// choice reproducible; nothing here is ever linked into the app.
package fixturegen

// SrpFixture is one SRP login vector plus a key-passphrase vector.
type SrpFixture struct {
	TestOnly bool   `json:"testOnly"`
	Comment  string `json:"comment"`
	Version  int    `json:"version"`
	Username string `json:"username"`
	Password string `json:"password"`
	// Salt is the SRP salt, standard Base64.
	Salt string `json:"salt"`
	// SignedModulus is Proton-signed; ModulusSource says where it was copied from.
	SignedModulus         string `json:"signedModulus"`
	ModulusSource         string `json:"modulusSource"`
	TamperedSignedModulus string `json:"tamperedSignedModulus"`
	// Modulus is the signed modulus's content: the raw modulus, standard Base64.
	Modulus string `json:"modulus"`
	// HashedPassword is go-srp HashPassword's output for this version.
	HashedPassword string `json:"hashedPassword"`
	Verifier       string `json:"verifier"`
	// ServerSecret lets a test rebuild the server (go-srp NewServerWithSecret)
	// and check proofs from a client whose random source can't be replaced.
	ServerSecret    string `json:"serverSecret"`
	ServerEphemeral string `json:"serverEphemeral"`
	// ClientRandom is every byte the client read from its random source while
	// computing the proofs; a client fed these bytes must reproduce them.
	ClientRandom        string              `json:"clientRandom"`
	ClientEphemeral     string              `json:"clientEphemeral"`
	ClientProof         string              `json:"clientProof"`
	ExpectedServerProof string              `json:"expectedServerProof"`
	KeyPassphrase       KeyPassphraseVector `json:"keyPassphrase"`
}

// KeyPassphraseVector is go-srp MailboxPassword's output after the
// 29-character bcrypt prefix.
type KeyPassphraseVector struct {
	Password   string `json:"password"`
	KeySalt    string `json:"keySalt"`
	Passphrase string `json:"passphrase"`
}

// KeysFixture is a test account's key material, shaped after U1, U3, and
// Proton's addresses reply.
type KeysFixture struct {
	TestOnly          bool               `json:"testOnly"`
	Comment           string             `json:"comment"`
	LoginPassword     string             `json:"loginPassword"`
	KeySalts          []KeySalt          `json:"keySalts"`
	UserKeys          []UserKey          `json:"userKeys"`
	AddressKeys       []AddressKey       `json:"addressKeys"`
	BrokenAddressKeys []BrokenAddressKey `json:"brokenAddressKeys"`
}

type KeySalt struct {
	ID      string `json:"id"`
	KeySalt string `json:"keySalt"`
}

type UserKey struct {
	ID                string `json:"id"`
	Active            bool   `json:"active"`
	Primary           bool   `json:"primary"`
	ArmoredPrivateKey string `json:"armoredPrivateKey"`
}

// AddressKey is either the token form (Token and Signature both set) or the
// legacy form (neither set; locked with its own salt's passphrase).
type AddressKey struct {
	ID                string `json:"id"`
	AddressID         string `json:"addressId"`
	Form              string `json:"form"`
	Active            bool   `json:"active"`
	Primary           bool   `json:"primary"`
	ArmoredPrivateKey string `json:"armoredPrivateKey"`
	Token             string `json:"token,omitempty"`
	Signature         string `json:"signature,omitempty"`
}

// BrokenAddressKey must be rejected: Case says why.
type BrokenAddressKey struct {
	ID                string `json:"id"`
	Case              string `json:"case"`
	ArmoredPrivateKey string `json:"armoredPrivateKey"`
	Token             string `json:"token,omitempty"`
	Signature         string `json:"signature,omitempty"`
}

// ContactsFixture holds contacts as C2/C3 return them: cards of types 0–3.
type ContactsFixture struct {
	TestOnly bool      `json:"testOnly"`
	Comment  string    `json:"comment"`
	Contacts []Contact `json:"contacts"`
}

type Contact struct {
	Name   string `json:"name"`
	ID     string `json:"id"`
	Expect string `json:"expect"`
	// Plaintexts is each card's text after decryption, for "ok" contacts.
	Plaintexts []string `json:"plaintexts,omitempty"`
	Cards      []Card   `json:"cards"`
}

// Card mirrors docs/protocol.md § Contact cards.
type Card struct {
	Type      int    `json:"type"`
	Data      string `json:"data"`
	Signature string `json:"signature,omitempty"`
}

// UnsupportedKeysFixture holds keys from official test corpora that the app
// must refuse.
type UnsupportedKeysFixture struct {
	TestOnly bool             `json:"testOnly"`
	Comment  string           `json:"comment"`
	Keys     []UnsupportedKey `json:"keys"`
}

type UnsupportedKey struct {
	Name              string `json:"name"`
	Kind              string `json:"kind"`
	Source            string `json:"source"`
	ArmoredPrivateKey string `json:"armoredPrivateKey"`
}
