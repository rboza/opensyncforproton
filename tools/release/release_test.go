package main

// Every keystore here is made by the test with keytool: test-only.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const password = "test-only-Ke7vQ2pass"

// testAPK is a minimal APK made once with the pinned aapt2.
var testAPK string

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "release-test-")
	if err != nil {
		panic(err)
	}
	testAPK, err = makeUnsignedAPK(dir)
	if err != nil {
		os.RemoveAll(dir)
		panic(err)
	}
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

func makeUnsignedAPK(dir string) (string, error) {
	tools, err := buildToolsDir("../..")
	if err != nil {
		return "", err
	}
	manifest := filepath.Join(dir, "AndroidManifest.xml")
	err = os.WriteFile(manifest, []byte(`<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.example.releasetest"><application/></manifest>`), 0o600)
	if err != nil {
		return "", err
	}
	apk := filepath.Join(dir, "unsigned.apk")
	jar := filepath.Join(os.Getenv("ANDROID_HOME"), "platforms", "android-37.0", "android.jar")
	out, err := exec.Command(filepath.Join(tools, "aapt2"), "link", "-o", apk, "--manifest", manifest, "-I", jar,
		"--min-sdk-version", "37", "--target-sdk-version", "37").CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("aapt2: %v\n%s", err, out)
	}
	return apk, nil
}

// keystore makes a PKCS12 keystore in dir and returns its path and the certificate SHA-256 of its key "osp". A
// second key, "decoy", makes the alias matter.
func keystore(t *testing.T, dir, name string) (string, string) {
	t.Helper()
	path := filepath.Join(dir, name+".p12")
	for _, alias := range []string{"decoy", "osp"} {
		run(t, "keytool", "-genkeypair", "-keystore", path, "-storetype", "PKCS12", "-alias", alias,
			"-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=test-only "+name+" "+alias,
			"-storepass", password, "-keypass", password)
	}
	der := run(t, "keytool", "-exportcert", "-keystore", path, "-alias", "osp", "-storepass", password)
	sum := sha256.Sum256(der)
	return path, hex.EncodeToString(sum[:])
}

func run(t *testing.T, name string, args ...string) []byte {
	t.Helper()
	cmd := exec.Command(name, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("%s %v: %v\n%s", name, args, err, stderr.String())
	}
	return out
}

const fakeGradlew = `#!/bin/sh
# Records what it was asked for and the OSP_RELEASE_* variables it can see, then acts like an unsigned release build.
printf '%s\n' "$@" > "$FAKE_GRADLE_LOG"
env | grep '^OSP_RELEASE_' >> "$FAKE_GRADLE_LOG"
out=app/build/outputs/apk/release
mkdir -p "$out"
meta=$FAKE_METADATA
[ -n "$meta" ] || meta='{"elements":[{"versionName":"0.1.0"}]}'
printf '%s' "$meta" > "$out/output-metadata.json"
[ -n "$FAKE_GRADLE_NO_APK" ] || cp "$FAKE_UNSIGNED_APK" "$out/app-release-unsigned.apk"
# A later task failing, after the APK is written.
[ -z "$FAKE_GRADLE_FAIL" ] || exit 1
`

type fixture struct {
	root, keys, log string
	cert            string // the recorded certificate's SHA-256
	keystore        string // the key whose certificate is recorded
}

// newFixture makes a committed repository with the real .gitignore and version catalog, a fake gradlew, the
// recorded certificate (unless record is false), and sets the OSP_RELEASE_* variables for the recorded key.
func newFixture(t *testing.T, record bool) fixture {
	t.Helper()
	f := fixture{root: t.TempDir(), keys: t.TempDir()}
	f.log = filepath.Join(f.keys, "gradle.log")
	f.keystore, f.cert = keystore(t, f.keys, "release")
	write(t, f.root, "gradlew", fakeGradlew, 0o755)
	write(t, f.root, ".gitignore", read(t, "../../.gitignore"), 0o644)
	write(t, f.root, "gradle/libs.versions.toml", read(t, "../../gradle/libs.versions.toml"), 0o644)
	if record {
		write(t, f.root, certFile, f.cert+"\n", 0o644)
	}
	git(t, f.root, "init", "-q")
	git(t, f.root, "add", ".")
	git(t, f.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "fixture")
	t.Setenv("FAKE_GRADLE_LOG", f.log)
	t.Setenv("FAKE_UNSIGNED_APK", testAPK)
	t.Setenv("FAKE_GRADLE_FAIL", "")
	t.Setenv("FAKE_GRADLE_NO_APK", "")
	t.Setenv("FAKE_METADATA", "")
	t.Setenv("OSP_RELEASE_KEYSTORE", f.keystore)
	t.Setenv("OSP_RELEASE_KEY_ALIAS", "osp")
	t.Setenv("OSP_RELEASE_KS_PASS", password)
	return f
}

func write(t *testing.T, root, rel, content string, mode os.FileMode) {
	t.Helper()
	path := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatal(err)
	}
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func git(t *testing.T, root string, args ...string) {
	t.Helper()
	run(t, "git", append([]string{"-C", root}, args...)...)
}

func metadata(versionName string) string {
	return `{"elements":[{"versionName":"` + versionName + `"}]}`
}

// build runs the script and returns its error and everything it printed.
func (f fixture) build(t *testing.T) (error, string) {
	t.Helper()
	var out bytes.Buffer
	err := build(f.root, &out)
	if strings.Contains(out.String(), password) || (err != nil && strings.Contains(err.Error(), password)) {
		t.Errorf("the password appears in the script's output")
	}
	return err, out.String()
}

func (f fixture) gradleRan() bool {
	_, err := os.Stat(f.log)
	return err == nil
}

// refused checks the script failed and left nothing in build/release, nor its signing directory.
func (f fixture) refused(t *testing.T, err error, out string) {
	t.Helper()
	if err == nil {
		t.Fatalf("not refused; output:\n%s", out)
	}
	if left, _ := os.ReadDir(filepath.Join(f.root, "build", "release")); len(left) != 0 {
		t.Errorf("refused (%v) but left %v", err, left)
	}
	f.noSigningDir(t)
}

func (f fixture) noSigningDir(t *testing.T) {
	t.Helper()
	if left, _ := filepath.Glob(filepath.Join(f.root, "build", "release-signing-*")); len(left) != 0 {
		t.Errorf("left %v", left)
	}
}

func signers(t *testing.T, apk string) []string {
	t.Helper()
	tools, err := buildToolsDir("../..")
	if err != nil {
		t.Fatal(err)
	}
	out := string(run(t, filepath.Join(tools, "apksigner"), "verify", "--print-certs", apk))
	var digests []string
	for _, line := range strings.Split(out, "\n") {
		if _, digest, ok := strings.Cut(line, " certificate SHA-256 digest: "); ok {
			digests = append(digests, strings.TrimSpace(digest))
		}
	}
	return digests
}

// loggedApksigner points ANDROID_HOME at a copy of the build-tools path whose apksigner logs its arguments, one
// per line, then runs the real one. It returns the log's path.
func loggedApksigner(t *testing.T) string {
	t.Helper()
	real, err := buildToolsDir("../..")
	if err != nil {
		t.Fatal(err)
	}
	sdk := t.TempDir()
	log := filepath.Join(sdk, "apksigner.log")
	rel, err := filepath.Rel(os.Getenv("ANDROID_HOME"), real)
	if err != nil {
		t.Fatal(err)
	}
	write(t, sdk, filepath.Join(rel, "apksigner"), "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '"+log+"'\nexec '"+
		filepath.Join(real, "apksigner")+"' \"$@\"\n", 0o755)
	t.Setenv("ANDROID_HOME", sdk)
	return log
}

func TestBuildSignsWithTheRecordedKey(t *testing.T) {
	f := newFixture(t, true)
	t.Setenv("FAKE_METADATA", metadata("1.2.3"))
	apkPath := filepath.Join(f.root, "build", "release", "opensyncforproton-1.2.3.apk")
	// An interrupted earlier run's signing directory is cleared too, and nothing else in build/.
	write(t, f.root, "build/release-signing-1/opensyncforproton-1.2.2.apk", "unchecked", 0o644)
	write(t, f.root, "build/reports/kept.txt", "kept", 0o644)
	signerLog := loggedApksigner(t)
	if err, out := f.build(t); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	if _, err := os.Stat(filepath.Join(f.root, "build", "reports", "kept.txt")); err != nil {
		t.Errorf("removed more than signing directories from build/: %v", err)
	}
	args := strings.Split(read(t, signerLog), "\n")
	if strings.Contains(strings.Join(args, "\n"), password) {
		t.Errorf("the password is on apksigner's command line")
	}
	signedIn := ""
	for i, arg := range args {
		if arg == "--out" && i+1 < len(args) {
			signedIn = filepath.Dir(args[i+1])
		}
	}
	if !strings.HasPrefix(signedIn, filepath.Join(f.root, "build", "release-signing-")) {
		t.Errorf("signed in %q, want a directory beside build/release (same filesystem, so the move is a rename)",
			signedIn)
	}
	log := read(t, f.log)
	if log != "-Posp.unsignedRelease=true\n:app:assembleRelease\n" {
		t.Errorf("gradlew was asked for (and saw):\n%s", log)
	}
	if left, _ := os.ReadDir(filepath.Join(f.root, "build", "release")); len(left) != 2 {
		t.Errorf("build/release holds %v, want the APK and its .sha256", left)
	}
	f.noSigningDir(t)
	if got := signers(t, apkPath); len(got) != 1 || got[0] != f.cert {
		t.Errorf("signers %v, want only %s", got, f.cert)
	}
	apk, err := os.ReadFile(apkPath)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(apk)
	want := hex.EncodeToString(sum[:]) + "  opensyncforproton-1.2.3.apk\n"
	if got := read(t, apkPath+".sha256"); got != want {
		t.Errorf(".sha256 is %q, want %q", got, want)
	}
}

func TestBuildsInATreeWithNoBuildDirectory(t *testing.T) {
	f := newFixture(t, true)
	if err, out := f.build(t); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
}

func TestRefusesAKeyWhoseCertificateIsNotRecorded(t *testing.T) {
	f := newFixture(t, true)
	other, _ := keystore(t, f.keys, "other")
	t.Setenv("OSP_RELEASE_KEYSTORE", other)
	err, out := f.build(t)
	f.refused(t, err, out)
}

func TestRefusesTheConfiguredAliasWhenItsCertificateIsNotRecorded(t *testing.T) {
	f := newFixture(t, true)
	t.Setenv("OSP_RELEASE_KEY_ALIAS", "decoy")
	err, out := f.build(t)
	f.refused(t, err, out)
}

func TestRefusesAWrongPassword(t *testing.T) {
	f := newFixture(t, true)
	t.Setenv("OSP_RELEASE_KS_PASS", password+"-wrong")
	err, out := f.build(t)
	f.refused(t, err, out)
	if strings.Contains(out, password+"-wrong") || strings.Contains(err.Error(), password+"-wrong") {
		t.Errorf("the wrong password appears in the script's output")
	}
}

func TestRefusesWhenGradleMakesNoUnsignedAPK(t *testing.T) {
	f := newFixture(t, true)
	// A stale unsigned APK from an earlier build (ignored by git) must not be signed.
	write(t, f.root, "app/build/outputs/apk/release/app-release-unsigned.apk", read(t, testAPK), 0o644)
	t.Setenv("FAKE_GRADLE_NO_APK", "1")
	err, out := f.build(t)
	f.refused(t, err, out)
}

func TestRefusesWhenGradleFails(t *testing.T) {
	f := newFixture(t, true)
	t.Setenv("FAKE_GRADLE_FAIL", "1")
	err, out := f.build(t)
	f.refused(t, err, out)
}

func TestAFailedBuildRemovesAnEarlierOutput(t *testing.T) {
	f := newFixture(t, true)
	write(t, f.root, "build/release/opensyncforproton-0.1.0.apk", "stale", 0o644)
	write(t, f.root, "build/release/opensyncforproton-0.0.9.apk", "older", 0o644)
	t.Setenv("FAKE_GRADLE_FAIL", "1")
	err, out := f.build(t)
	f.refused(t, err, out)
}

func TestRefusesOutputMetadataWithoutOneXYZVersionName(t *testing.T) {
	cases := map[string]string{
		"parent directory":    metadata("../0.1.0"),
		"through a directory": metadata("0.1.0/../../x"),
		"suffix":              metadata("0.1.0-rc1"),
		"no APK":              `{"elements":[]}`,
		"two APKs":            `{"elements":[{"versionName":"0.1.0"},{"versionName":"0.1.0"}]}`,
		"not JSON":            `elements`,
	}
	for name, meta := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t, true)
			t.Setenv("FAKE_METADATA", meta)
			err, out := f.build(t)
			f.refused(t, err, out)
			if apks, _ := filepath.Glob(filepath.Join(f.root, "*", "*.apk")); len(apks) != 0 {
				t.Errorf("wrote %v", apks)
			}
		})
	}
}

func TestRefusesAKeystoreInsideTheRepositoryReachedThroughALinkedRoot(t *testing.T) {
	f := newFixture(t, true)
	write(t, f.root, "build/release.p12", read(t, f.keystore), 0o600)
	t.Setenv("OSP_RELEASE_KEYSTORE", filepath.Join(f.root, "build", "release.p12"))
	link := filepath.Join(t.TempDir(), "repo")
	if err := os.Symlink(f.root, link); err != nil {
		t.Fatal(err)
	}
	linked := f
	linked.root = link
	err, out := linked.build(t)
	f.refused(t, err, out)
	if f.gradleRan() {
		t.Errorf("gradlew ran")
	}
}

func TestRefusesBeforeGradle(t *testing.T) {
	cases := map[string]func(t *testing.T, f fixture){
		"keystore unset": func(t *testing.T, f fixture) { t.Setenv("OSP_RELEASE_KEYSTORE", "") },
		"alias unset":    func(t *testing.T, f fixture) { t.Setenv("OSP_RELEASE_KEY_ALIAS", "") },
		"password unset": func(t *testing.T, f fixture) { t.Setenv("OSP_RELEASE_KS_PASS", "") },
		"keystore missing": func(t *testing.T, f fixture) {
			t.Setenv("OSP_RELEASE_KEYSTORE", filepath.Join(f.keys, "missing.p12"))
		},
		"keystore inside the repository": func(t *testing.T, f fixture) {
			// Ignored by git, so only the location refuses it.
			write(t, f.root, "build/release.p12", read(t, f.keystore), 0o600)
			t.Setenv("OSP_RELEASE_KEYSTORE", filepath.Join(f.root, "build", "release.p12"))
		},
		"keystore inside the repository in a directory named ..keys": func(t *testing.T, f fixture) {
			write(t, f.root, ".git/info/exclude", "/..keys/\n", 0o644)
			write(t, f.root, "..keys/release.p12", read(t, f.keystore), 0o600)
			t.Setenv("OSP_RELEASE_KEYSTORE", filepath.Join(f.root, "..keys", "release.p12"))
		},
		"keystore linked from outside to inside the repository": func(t *testing.T, f fixture) {
			write(t, f.root, "build/release.p12", read(t, f.keystore), 0o600)
			link := filepath.Join(f.keys, "linked.p12")
			if err := os.Symlink(filepath.Join(f.root, "build", "release.p12"), link); err != nil {
				t.Fatal(err)
			}
			t.Setenv("OSP_RELEASE_KEYSTORE", link)
		},
		"keystore given as a relative path": func(t *testing.T, f fixture) {
			// `mise run release` runs in tools/release, so a relative path wouldn't mean what the user typed.
			t.Chdir(f.keys)
			t.Setenv("OSP_RELEASE_KEYSTORE", filepath.Base(f.keystore))
		},
		"ANDROID_HOME unset": func(t *testing.T, f fixture) { t.Setenv("ANDROID_HOME", "") },
		"certificate not recorded": func(t *testing.T, f fixture) {
			git(t, f.root, "rm", "-q", certFile)
			git(t, f.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "no cert")
		},
		"certificate malformed": func(t *testing.T, f fixture) {
			write(t, f.root, certFile, strings.ToUpper(f.cert)+"\n", 0o644)
			git(t, f.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-am", "upper")
		},
		"certificate as sha256sum prints it": func(t *testing.T, f fixture) {
			write(t, f.root, certFile, f.cert+"  -\n", 0o644)
			git(t, f.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-am", "dash")
		},
		"keystore given as a relative path to a symlink": func(t *testing.T, f fixture) {
			if err := os.Symlink(f.keystore, filepath.Join(f.keys, "link.p12")); err != nil {
				t.Fatal(err)
			}
			t.Chdir(f.keys)
			t.Setenv("OSP_RELEASE_KEYSTORE", "link.p12")
		},
		"changed file": func(t *testing.T, f fixture) {
			write(t, f.root, "gradle/libs.versions.toml", read(t, "../../gradle/libs.versions.toml")+"\n", 0o644)
		},
		"untracked file": func(t *testing.T, f fixture) { write(t, f.root, "notes.txt", "x", 0o644) },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t, true)
			change(t, f)
			err, out := f.build(t)
			f.refused(t, err, out)
			if f.gradleRan() {
				t.Errorf("gradlew ran")
			}
		})
	}
}

func TestSignerRefusesTwoSigners(t *testing.T) {
	keys := t.TempDir()
	a, _ := keystore(t, keys, "a")
	b, _ := keystore(t, keys, "b")
	tools, err := buildToolsDir("../..")
	if err != nil {
		t.Fatal(err)
	}
	apk := filepath.Join(keys, "two.apk")
	pass := "pass:" + password
	run(t, filepath.Join(tools, "apksigner"), "sign", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "false",
		"--v4-signing-enabled", "false", "--ks", a, "--ks-key-alias", "osp", "--ks-pass", pass,
		"--next-signer", "--ks", b, "--ks-key-alias", "osp", "--ks-pass", pass,
		"--out", apk, testAPK)
	if n := len(signers(t, apk)); n != 2 {
		t.Fatalf("the test APK has %d signers", n)
	}
	if cert, err := signer(tools, apk); err == nil {
		t.Errorf("signer accepted two signers, returning %s", cert)
	}
}
