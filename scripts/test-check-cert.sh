#!/usr/bin/env bash
# Tests scripts/check-cert.sh. Run: scripts/test-check-cert.sh (exit 0 = all pass).
# Needs an Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT, else sdk.dir from local.properties) with build-tools
# (aapt2, apksigner) and platforms/android-35, plus keytool and sha256sum. Every fixture (APK, throwaway keystores,
# fake SDKs with stub apksigners) is built in a temp dir removed on exit; no build output and no real keystore is read.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/check-cert.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fails=0 total=0
check() { # check <description> <command...>
    total=$((total + 1))
    if "${@:2}"; then return; fi
    echo "FAIL: $1"
    fails=$((fails + 1))
}
infra() { # infra <reason>: the fixtures cannot be built here — fail loudly, never pass silently
    echo "check-cert: cannot build fixtures: $1" >&2
    echo "check-cert: 0/1 passed"
    exit 1
}

# --- SDK + tools -------------------------------------------------------------------------------------------------------
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f "$here/../local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$here/../local.properties" | tail -n 1)"
fi
[[ -n "$sdk" && -d "$sdk" ]] || infra "no Android SDK (set ANDROID_HOME or sdk.dir in local.properties)"
newest_tool() { printf '%s\n' "$sdk"/build-tools/*/"$1" | sort -V | tail -n 1; }
real_signer="$(newest_tool apksigner)"
aapt2="$(newest_tool aapt2)"
android_jar="$sdk/platforms/android-35/android.jar"
[[ -x "$real_signer" ]] || infra "no apksigner under $sdk/build-tools"
[[ -x "$aapt2" ]] || infra "no aapt2 under $sdk/build-tools"
[[ -f "$android_jar" ]] || infra "no $android_jar"
command -v keytool > /dev/null || infra "no keytool on PATH"

# --- Fixtures: a minimal APK and three throwaway keys ------------------------------------------------------------------
fx="$tmp/fixtures with space"
mkdir -p "$fx"
printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' \
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.certtest"' \
    '    android:versionCode="1" android:versionName="1.0">' \
    '    <application android:hasCode="false" />' \
    '</manifest>' > "$fx/AndroidManifest.xml"
"$aapt2" link -o "$fx/unsigned.apk" -I "$android_jar" --manifest "$fx/AndroidManifest.xml" > "$fx/aapt2.log" 2>&1 ||
    infra "aapt2 link failed: $(cat "$fx/aapt2.log")"

pass=throwaway-pass
for k in a b; do
    keytool -genkeypair -noprompt -keystore "$fx/$k.p12" -storetype PKCS12 -storepass "$pass" -keypass "$pass" \
        -alias "$k" -keyalg RSA -keysize 2048 -validity 2 -dname "CN=check-cert test $k" > "$fx/keytool-$k.log" 2>&1 ||
        infra "keytool -genkeypair $k failed: $(cat "$fx/keytool-$k.log")"
done
# Expected digests computed WITHOUT apksigner: SHA-256 of the DER certificate keytool exports.
cert_sha() { keytool -exportcert -keystore "$fx/$1.p12" -storetype PKCS12 -storepass "$pass" -alias "$1" | sha256sum | cut -d' ' -f1; }
sha_a="$(cert_sha a)"
sha_b="$(cert_sha b)"
# keytool's own fingerprint of key a (AB:CD:… uppercase) — a cross-check here, a malformed input below.
colon_a="$(keytool -list -v -keystore "$fx/a.p12" -storetype PKCS12 -storepass "$pass" -alias a | sed -n 's/^[[:space:]]*SHA256: //p')"
[[ "$sha_a" =~ ^[0-9a-f]{64}$ && "$sha_b" =~ ^[0-9a-f]{64}$ && "$sha_a" != "$sha_b" ]] || infra "bad cert digests '$sha_a' '$sha_b'"
[[ "$(tr -d ':' <<< "$colon_a" | tr 'A-F' 'a-f')" == "$sha_a" ]] || infra "keytool -list SHA256 '$colon_a' != DER sha256 '$sha_a'"

sign() { # sign <out> <key> [<key2>]: apksigner sign with one signer, or two via --next-signer
    local args=(sign --ks "$fx/$2.p12" --ks-pass "pass:$pass" --ks-key-alias "$2")
    # v3 refuses several signers without a rotation lineage, so the two-signer APK is v1+v2 only.
    [[ $# -ge 3 ]] && args+=(--v3-signing-enabled false
        --next-signer --ks "$fx/$3.p12" --ks-pass "pass:$pass" --ks-key-alias "$3")
    "$real_signer" "${args[@]}" --out "$1" "$fx/unsigned.apk" > "$fx/sign.log" 2>&1 && return
    infra "apksigner sign $2 ${3:-} failed: $(cat "$fx/sign.log")"
}
signed_a="$fx/signed a.apk"
signed_b="$fx/signed b.apk"
signed_ab="$fx/signed ab.apk"
sign "$signed_a" a
sign "$signed_b" b
sign "$signed_ab" a b
# Signed by a with a source stamp by b: apksigner then also prints "Source Stamp Signer certificate SHA-256 digest: <b>".
stamped="$fx/stamped.apk"
"$real_signer" sign --ks "$fx/a.p12" --ks-pass "pass:$pass" --stamp-signer --ks "$fx/b.p12" --ks-pass "pass:$pass" \
    --out "$stamped" "$fx/unsigned.apk" > "$fx/sign.log" 2>&1 || infra "apksigner sign --stamp-signer failed: $(cat "$fx/sign.log")"
tampered="$fx/tampered.apk"
cp "$signed_a" "$tampered"
printf 'X' | dd of="$tampered" bs=1 seek=100 conv=notrunc status=none
garbage="$fx/garbage.apk"
head -c 4096 /dev/urandom > "$garbage"

# --- Helpers -----------------------------------------------------------------------------------------------------------
run_cc() { # run_cc [VAR=value...] -- <args...>: runs check-cert.sh in a clean env; sets rc, out, err
    local envs=()
    while [[ $# -gt 0 && "$1" != "--" ]]; do envs+=("$1"); shift; done
    shift
    out="$(env -u APKSIGNER -u ANDROID_HOME -u ANDROID_SDK_ROOT "${envs[@]}" "$script" "$@" 2> "$tmp/err")"
    rc=$?
    err="$(cat "$tmp/err")"
}
ok_with() { # ok_with <sha>: rc 0, stdout exactly the OK line, stderr empty
    [[ $rc -eq 0 && "$out" == "check-cert: OK $1" && -z "$err" ]]
}
fails_with() { # fails_with <substring>: rc 1, nothing on stdout, stderr contains the substring
    [[ $rc -eq 1 && -z "$out" && "$err" == *"$1"* ]]
}
make_stub() { # make_stub <path> <stdout text> [exit code]: a fake apksigner logging "<its path>|<arg>|<arg>|…"
    mkdir -p "$(dirname "$1")"
    # shellcheck disable=SC2016 # $0 / $@ belong to the generated stub, not to this script
    printf '#!/usr/bin/env bash\nprintf "%%s|" "$0" "$@" >> %q\necho >> %q\nprintf "%%s" %q\nexit %s\n' \
        "$tmp/stub.log" "$tmp/stub.log" "$2" "${3:-0}" > "$1"
    chmod +x "$1"
}
stub_called() { # stub_called <expected log line>: exactly one stub call since the last reset, with these args
    [[ "$(cat "$tmp/stub.log" 2> /dev/null)" == "$1" ]]
}
digest_line() { printf 'Signer #%s certificate DN: CN=stub\nSigner #%s certificate SHA-256 digest: %s\n' "$1" "$1" "$2"; }

# --- Real apksigner, discovered from ANDROID_HOME (the CI path) --------------------------------------------------------
run_cc "ANDROID_HOME=$sdk" -- "$signed_a" "$sha_a"
check "right cert: rc 0 + 'check-cert: OK <sha>' (rc=$rc out='$out' err='$err')" ok_with "$sha_a"

run_cc "ANDROID_HOME=$sdk" -- "$signed_b" "$sha_a"
check "other cert: rc 1 + 'certificate <b> is not the expected <a>' (rc=$rc err='$err')" \
    fails_with "check-cert: certificate $sha_b is not the expected $sha_a"

run_cc "ANDROID_HOME=$sdk" -- "$signed_b" "$sha_b"
check "cert b expected b: OK (rc=$rc out='$out')" ok_with "$sha_b"

run_cc "ANDROID_HOME=$sdk" -- "$fx/unsigned.apk" "$sha_a"
check "unsigned APK: rc 1 'does not verify' (rc=$rc err='$err')" fails_with "unsigned.apk does not verify"

run_cc "ANDROID_HOME=$sdk" -- "$tampered" "$sha_a"
check "APK modified after signing: rc 1 'does not verify' (rc=$rc err='$err')" fails_with "does not verify"

run_cc "ANDROID_HOME=$sdk" -- "$garbage" "$sha_a"
check "not a zip: rc 1 'does not verify' (rc=$rc err='$err')" fails_with "does not verify"

for want in "$sha_a" "$sha_b"; do
    run_cc "ANDROID_HOME=$sdk" -- "$signed_ab" "$want"
    check "two signers, expected = one of them (${want:0:8}): rc 1 'exactly one signer, found 2' (rc=$rc err='$err')" \
        fails_with "check-cert: expected exactly one signer, found 2"
done

run_cc "ANDROID_HOME=$sdk" -- "$stamped" "$sha_a"
check "source-stamped APK: the stamp certificate is not counted as a signer (rc=$rc out='$out' err='$err')" ok_with "$sha_a"
run_cc "ANDROID_HOME=$sdk" -- "$stamped" "$sha_b"
check "source-stamped APK: the stamp certificate is never accepted as the signer (rc=$rc err='$err')" \
    fails_with "check-cert: certificate $sha_a is not the expected $sha_b"

run_cc "ANDROID_SDK_ROOT=$sdk" -- "$signed_a" "$sha_a"
check "real apksigner via ANDROID_SDK_ROOT: OK (rc=$rc err='$err')" ok_with "$sha_a"

run_cc "APKSIGNER=$real_signer" -- "$signed_a" "$sha_a"
check "real apksigner via APKSIGNER, no ANDROID_HOME: OK (rc=$rc err='$err')" ok_with "$sha_a"

# --- Argument validation (no apksigner call needed) --------------------------------------------------------------------
run_cc "ANDROID_HOME=$sdk" -- "$fx/nope.apk" "$sha_a"
check "missing file: rc 1 'not found' (rc=$rc err='$err')" fails_with "check-cert: $fx/nope.apk not found"
run_cc "ANDROID_HOME=$sdk" -- "$fx" "$sha_a"
check "directory instead of APK: rc 1 'not found' (rc=$rc err='$err')" fails_with "not found"
run_cc "ANDROID_HOME=$sdk" --
check "no arguments: rc 1, no stdout (rc=$rc)" test "$rc" -eq 1 -a -z "$out"
run_cc "ANDROID_HOME=$sdk" -- "$signed_a"
check "no expected sha: rc 1, no stdout (rc=$rc)" test "$rc" -eq 1 -a -z "$out"
run_cc "ANDROID_HOME=$sdk" -- "$signed_a" ""
check "empty expected sha: rc 1, no stdout (rc=$rc)" test "$rc" -eq 1 -a -z "$out"

upper_a="$(tr 'a-f' 'A-F' <<< "$sha_a")"
[[ "$upper_a" != "$sha_a" ]] || infra "sha_a has no letters, uppercase case would be vacuous"
newline_a="$(printf '%s\nx' "$sha_a")"
for bad in "$upper_a" "$colon_a" "${sha_a:0:63}" "${sha_a}0" "${sha_a:0:63}g" " $sha_a" "$sha_a " "${newline_a%x}" \
    "sha256:$sha_a"; do
    run_cc "ANDROID_HOME=$sdk" -- "$signed_a" "$bad"
    check "malformed expected sha '$(printf '%q' "${bad:0:12}")…' (len ${#bad}) rejected before apksigner (rc=$rc err='$err')" \
        fails_with "expected sha256 must be 64 lowercase hex"
done
# Command substitution strips a trailing newline, so build "<sha>\n" explicitly.
run_cc "ANDROID_HOME=$sdk" -- "$signed_a" "$sha_a"$'\n'
check "expected sha with trailing newline rejected (rc=$rc err='$err')" fails_with "64 lowercase hex"

# --- apksigner lookup with stub apksigners -----------------------------------------------------------------------------
# Distinct synthetic digests: each stub prints its own, so the OK line proves which apksigner ran.
sha_old="$(printf old | sha256sum | cut -d' ' -f1)"
sha_new="$(printf new | sha256sum | cut -d' ' -f1)"
sha_ov="$(printf override | sha256sum | cut -d' ' -f1)"
fake="$tmp/fake sdk"
make_stub "$fake/build-tools/9.0.0/apksigner" "$(digest_line 1 "$sha_old")"
make_stub "$fake/build-tools/10.0.0/apksigner" "$(digest_line 1 "$sha_new")"
mkdir -p "$fake/build-tools/11.0.0" # newest dir but without apksigner: must be skipped
old_only="$tmp/old only"
make_stub "$old_only/build-tools/9.0.0/apksigner" "$(digest_line 1 "$sha_old")"
make_stub "$tmp/override/apksigner" "$(digest_line 1 "$sha_ov")"

rm -f "$tmp/stub.log"
run_cc "ANDROID_HOME=$fake" -- "$signed_a" "$sha_new"
check "several build-tools: newest (10.0.0 over 9.0.0, version sort) wins (rc=$rc out='$out' err='$err')" ok_with "$sha_new"
check "stub called once as 'verify --print-certs <apk>' with the spaced path intact" \
    stub_called "$fake/build-tools/10.0.0/apksigner|verify|--print-certs|$signed_a|"

run_cc "ANDROID_HOME=$fake" -- "$signed_a" "$sha_old"
check "older build-tools digest is not accepted (rc=$rc err='$err')" fails_with "certificate $sha_new is not the expected $sha_old"

run_cc "ANDROID_SDK_ROOT=$fake" -- "$signed_a" "$sha_new"
check "ANDROID_SDK_ROOT used when ANDROID_HOME is unset (rc=$rc err='$err')" ok_with "$sha_new"

run_cc "ANDROID_HOME=$old_only" "ANDROID_SDK_ROOT=$fake" -- "$signed_a" "$sha_old"
check "ANDROID_HOME wins over ANDROID_SDK_ROOT (rc=$rc out='$out' err='$err')" ok_with "$sha_old"

rm -f "$tmp/stub.log"
run_cc "APKSIGNER=$tmp/override/apksigner" "ANDROID_HOME=$fake" -- "$signed_a" "$sha_ov"
check "APKSIGNER overrides ANDROID_HOME discovery (rc=$rc out='$out' err='$err')" ok_with "$sha_ov"
check "only the override stub ran" stub_called "$tmp/override/apksigner|verify|--print-certs|$signed_a|"

run_cc "APKSIGNER=$tmp/nope/apksigner" "ANDROID_HOME=$sdk" -- "$signed_a" "$sha_a"
check "APKSIGNER set to a missing file: rc 1, no fallback to ANDROID_HOME (rc=$rc err='$err')" \
    fails_with "check-cert: apksigner not found"
printf '#!/bin/sh\n' > "$tmp/not-exec"
run_cc "APKSIGNER=$tmp/not-exec" -- "$signed_a" "$sha_a"
check "APKSIGNER not executable: rc 1 'apksigner not found' (rc=$rc err='$err')" fails_with "apksigner not found"

mkdir -p "$tmp/empty sdk/build-tools/35.0.0"
run_cc "ANDROID_HOME=$tmp/empty sdk" -- "$signed_a" "$sha_a"
check "build-tools without apksigner: rc 1 'apksigner not found' (rc=$rc err='$err')" fails_with "apksigner not found"
run_cc -- "$signed_a" "$sha_a"
check "no APKSIGNER/ANDROID_HOME/ANDROID_SDK_ROOT at all: rc 1 'apksigner not found' (rc=$rc err='$err')" \
    fails_with "apksigner not found"
cp -R "$fake" "$tmp/noexec sdk"
chmod -x "$tmp/noexec sdk/build-tools/"*/apksigner
run_cc "ANDROID_HOME=$tmp/noexec sdk" -- "$signed_a" "$sha_new"
check "apksigner present but not executable: rc 1 'apksigner not found' (rc=$rc err='$err')" fails_with "apksigner not found"

# --- Parsing of apksigner's output (stub) ------------------------------------------------------------------------------
make_stub "$tmp/s/fail" "$(printf 'DOES NOT VERIFY\nERROR: boom-7f3\n')" 1
run_cc "APKSIGNER=$tmp/s/fail" -- "$signed_a" "$sha_a"
check "apksigner rc != 0 (even printing nothing matching): 'does not verify' + its output relayed (rc=$rc err='$err')" \
    fails_with "ERROR: boom-7f3"
check "  …and the 'does not verify' header" fails_with "does not verify"

make_stub "$tmp/s/fail-with-digest" "$(digest_line 1 "$sha_a")" 2
run_cc "APKSIGNER=$tmp/s/fail-with-digest" -- "$signed_a" "$sha_a"
check "apksigner rc != 0 but printing the right digest: still rc 1 'does not verify' (rc=$rc out='$out')" \
    fails_with "does not verify"

make_stub "$tmp/s/none" "$(printf 'Verifies\nVerified using v2 scheme: true\n')"
run_cc "APKSIGNER=$tmp/s/none" -- "$signed_a" "$sha_a"
check "verifies but no signer digest line: rc 1 'found 0' (rc=$rc err='$err')" fails_with "exactly one signer, found 0"

make_stub "$tmp/s/two-same" "$(digest_line 1 "$sha_a"; digest_line 2 "$sha_a")"
run_cc "APKSIGNER=$tmp/s/two-same" -- "$signed_a" "$sha_a"
check "two signer lines with the SAME expected digest: rc 1 'found 2' (rc=$rc err='$err')" \
    fails_with "exactly one signer, found 2"

make_stub "$tmp/s/three" "$(digest_line 1 "$sha_a"; digest_line 2 "$sha_b"; digest_line 3 "$sha_old")"
run_cc "APKSIGNER=$tmp/s/three" -- "$signed_a" "$sha_a"
check "three signers: rc 1 'found 3' (rc=$rc err='$err')" fails_with "exactly one signer, found 3"

make_stub "$tmp/s/other-lines" "$(printf 'Verifies\nSigner #1 certificate SHA-1 digest: %s\nSigner #1 public key SHA-256 digest: %s\n' \
    "${sha_b:0:40}" "$sha_b"; digest_line 1 "$sha_a"; printf 'WARNING: META-INF/x not protected\n')"
run_cc "APKSIGNER=$tmp/s/other-lines" -- "$signed_a" "$sha_a"
check "only the 'certificate SHA-256 digest' line counts (SHA-1 / public-key digests ignored) (rc=$rc err='$err')" \
    ok_with "$sha_a"

echo "check-cert: $((total - fails))/$total passed"
((fails == 0))
