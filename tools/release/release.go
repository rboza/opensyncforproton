// The local release script (docs/releasing.md, step 4): builds the release APK unsigned, signs it with the
// release key outside Gradle, and refuses anything not signed by the recorded release certificate.
//
//	mise run release    # go run . <repository>
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	certFile    = "tools/release/release-certificate.sha256"
	gradleOut   = "app/build/outputs/apk/release"
	unsignedAPK = gradleOut + "/app-release-unsigned.apk"
	outDir      = "build/release"
	// apksigner reads the password from this variable itself, so it is never on a command line.
	passwordEnv = "OSP_RELEASE_KS_PASS"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: release <repository>")
		os.Exit(2)
	}
	if err := build(os.Args[1], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "release:", err)
		os.Exit(1)
	}
}

func build(root string, out io.Writer) error {
	root, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	keystore, alias := os.Getenv("OSP_RELEASE_KEYSTORE"), os.Getenv("OSP_RELEASE_KEY_ALIAS")
	if keystore == "" || alias == "" || os.Getenv(passwordEnv) == "" {
		return errors.New("set OSP_RELEASE_KEYSTORE, OSP_RELEASE_KEY_ALIAS, and " + passwordEnv)
	}
	if keystore, err = outside(root, keystore); err != nil {
		return err
	}
	recorded, err := recordedCert(root)
	if err != nil {
		return err
	}
	tools, err := buildToolsDir(root)
	if err != nil {
		return err
	}
	if err := clean(root); err != nil {
		return err
	}

	// An earlier output, and what an interrupted run left in its signing directory.
	stale := []string{filepath.Join(root, outDir)}
	entries, err := os.ReadDir(filepath.Join(root, "build"))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "release-signing-") {
			stale = append(stale, filepath.Join(root, "build", entry.Name()))
		}
	}
	for _, path := range stale {
		if err := os.RemoveAll(path); err != nil {
			return err
		}
	}
	if err := os.Remove(filepath.Join(root, unsignedAPK)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	gradle := exec.Command("./gradlew", "-Posp.unsignedRelease=true", ":app:assembleRelease")
	gradle.Dir, gradle.Stdout, gradle.Stderr = root, out, out
	gradle.Env = withoutReleaseVars(os.Environ())
	if err := gradle.Run(); err != nil {
		return fmt.Errorf("gradlew: %w", err)
	}
	version, err := versionName(root)
	if err != nil {
		return err
	}

	name := "opensyncforproton-" + version + ".apk"
	apk := filepath.Join(root, outDir, name)
	// Signed beside build/release, so an interrupted run never leaves an unchecked APK in it.
	if err := os.MkdirAll(filepath.Join(root, "build"), 0o755); err != nil {
		return err
	}
	work, err := os.MkdirTemp(filepath.Join(root, "build"), "release-signing-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(work)
	tmp := filepath.Join(work, name)
	// apksigner uses the store's password for the key too (one password in PKCS12).
	sign := exec.Command(filepath.Join(tools, "apksigner"), "sign", "--ks", keystore, "--ks-key-alias", alias,
		"--ks-pass", "env:"+passwordEnv, "--out", tmp, filepath.Join(root, unsignedAPK))
	sign.Stdout, sign.Stderr = out, out
	if err := sign.Run(); err != nil {
		return fmt.Errorf("apksigner sign: %w", err)
	}
	cert, err := signer(tools, tmp)
	if err != nil {
		return err
	}
	if cert != recorded {
		return fmt.Errorf("signed by certificate %s, not the recorded %s", cert, recorded)
	}
	if err := os.MkdirAll(filepath.Dir(apk), 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, apk); err != nil {
		return err
	}
	contents, err := os.ReadFile(apk)
	if err != nil {
		return err
	}
	sum := sha256.Sum256(contents)
	digest := hex.EncodeToString(sum[:])
	if err := os.WriteFile(apk+".sha256", []byte(digest+"  "+name+"\n"), 0o644); err != nil {
		os.Remove(apk)
		return err
	}
	fmt.Fprintf(out, "\n%s\nSHA-256:     %s\ncertificate: %s\n", apk, digest, cert)
	return nil
}

// outside resolves the keystore and refuses one that is relative, missing, or inside the repository.
func outside(root, keystore string) (string, error) {
	if !filepath.IsAbs(keystore) {
		return "", errors.New("OSP_RELEASE_KEYSTORE must be an absolute path")
	}
	path, err := filepath.EvalSymlinks(keystore)
	if err != nil {
		return "", fmt.Errorf("keystore: %w", err)
	}
	repo, err := filepath.EvalSymlinks(root)
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(repo, path)
	if err != nil {
		return "", err
	}
	if rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", errors.New("the keystore must be outside the repository")
	}
	return path, nil
}

func recordedCert(root string) (string, error) {
	b, err := os.ReadFile(filepath.Join(root, certFile))
	if err != nil {
		return "", fmt.Errorf("no recorded release certificate: %w", err)
	}
	cert := strings.TrimSuffix(string(b), "\n")
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(cert) {
		return "", fmt.Errorf("%s must hold one SHA-256 in lowercase hex", certFile)
	}
	return cert, nil
}

func buildToolsDir(root string) (string, error) {
	catalog, err := os.ReadFile(filepath.Join(root, "gradle", "libs.versions.toml"))
	if err != nil {
		return "", err
	}
	m := regexp.MustCompile(`(?m)^buildTools = "([0-9.]+)"$`).FindSubmatch(catalog)
	if m == nil {
		return "", errors.New("no buildTools version in the version catalog")
	}
	sdk := os.Getenv("ANDROID_HOME")
	if !filepath.IsAbs(sdk) {
		return "", errors.New("ANDROID_HOME must be an absolute path (mise sets it)")
	}
	return filepath.Join(sdk, "build-tools", string(m[1])), nil
}

// clean refuses a tree with changed or untracked files, so the APK is the commit it is built from.
func clean(root string) error {
	status, err := exec.Command("git", "-C", root, "status", "--porcelain", "--untracked-files=all").Output()
	if err != nil {
		return fmt.Errorf("git status: %w", err)
	}
	if len(status) != 0 {
		return errors.New("the tree has changed or untracked files; commit or remove them first")
	}
	return nil
}

func withoutReleaseVars(env []string) []string {
	var kept []string
	for _, v := range env {
		if !strings.HasPrefix(v, "OSP_RELEASE_") {
			kept = append(kept, v)
		}
	}
	return kept
}

func versionName(root string) (string, error) {
	b, err := os.ReadFile(filepath.Join(root, gradleOut, "output-metadata.json"))
	if err != nil {
		return "", err
	}
	var meta struct {
		Elements []struct{ VersionName string }
	}
	if err := json.Unmarshal(b, &meta); err != nil {
		return "", fmt.Errorf("output-metadata.json: %w", err)
	}
	if len(meta.Elements) != 1 || !regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`).MatchString(meta.Elements[0].VersionName) {
		return "", errors.New("output-metadata.json: expected one APK with an X.Y.Z versionName")
	}
	return meta.Elements[0].VersionName, nil
}

// signer verifies the APK and returns its one signer's certificate SHA-256.
func signer(tools, apk string) (string, error) {
	var stderr bytes.Buffer
	verify := exec.Command(filepath.Join(tools, "apksigner"), "verify", "--print-certs", apk)
	verify.Stderr = &stderr
	printed, err := verify.Output()
	if err != nil {
		return "", fmt.Errorf("apksigner verify: %w\n%s", err, stderr.Bytes())
	}
	var certs []string
	for _, line := range strings.Split(string(printed), "\n") {
		if _, digest, ok := strings.Cut(line, " certificate SHA-256 digest: "); ok {
			certs = append(certs, strings.TrimSpace(digest))
		}
	}
	if len(certs) != 1 {
		return "", fmt.Errorf("expected one signer, found %d", len(certs))
	}
	return certs[0], nil
}
