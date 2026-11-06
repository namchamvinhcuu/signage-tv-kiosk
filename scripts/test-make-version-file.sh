#!/usr/bin/env bash
# Tests scripts/make-version-file.sh. Run: scripts/test-make-version-file.sh (exit 0 = all pass).
set -uo pipefail
script="$(cd "$(dirname "$0")" && pwd)/make-version-file.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fails=0 total=0
check() { # check <description> <command...>
    total=$((total + 1))
    if "${@:2}"; then return; fi
    echo "FAIL: $1"
    fails=$((fails + 1))
}
rejects() { # rejects <args...>: non-zero exit and nothing on stdout
    local out
    if out="$("$script" "$@" 2>/dev/null)"; then return 1; fi
    [[ -z "$out" ]]
}

mkdir -p "$tmp/dir with space"
apk="$tmp/dir with space/signage-kiosk-201.apk"
head -c 123457 /dev/urandom > "$apk"
want_sha="$(sha256sum "$apk" | cut -d' ' -f1)"

# Happy path: exactly the five lines the app (UpdateManifest.parse) and kiosk-pull.sh read, apk = bare file name.
out="$("$script" "$apk" 201 0.2.1)"; rc=$?
expected="$(printf 'versionCode=201\nversionName=0.2.1\napk=signage-kiosk-201.apk\nsha256=%s\nsize=123457' "$want_sha")"
check "valid rc 0" test "$rc" -eq 0
check "valid output exact" test "$out" = "$expected"
check "sha256 is 64 lowercase hex" bash -c "[[ '$want_sha' =~ ^[0-9a-f]{64}$ ]]"

# Relative path from another directory still yields the bare name.
out_rel="$(cd "$tmp" && "$script" "dir with space/signage-kiosk-201.apk" 201 0.2.1)"
check "relative path gives bare apk name" test "$out_rel" = "$expected"

# Rejections.
check "missing file" rejects "$tmp/nope.apk" 201 0.2.1
check "directory instead of file" rejects "$tmp" 201 0.2.1
for code in 0 -1 01 abc "" 2.1 " 201" "201 "; do
    check "bad versionCode '$code'" rejects "$apk" "$code" 0.2.1
done
check "missing versionName" rejects "$apk" 201
check "empty versionName" rejects "$apk" 201 ""
check "no arguments" rejects
for name in .hidden.apk "a b.apk" signage.zip "-x.apk" "$(printf 'a%.0s' $(seq 101)).apk" "x.apk.part"; do
    f="$tmp/$name"
    head -c 10 /dev/urandom > "$f"
    check "bad apk file name '${name:0:20}'" rejects "$f" 201 0.2.1
done
long_ok="$(printf 'a%.0s' $(seq 100)).apk"
head -c 10 /dev/urandom > "$tmp/$long_ok"
check "100-char apk base name accepted" "$script" "$tmp/$long_ok" 201 0.2.1 > /dev/null


# Regression D2: the limits of the app's UpdateManifest.parse (Int versionCode, versionName <= 32 chars, size <= 20 MiB)
# plus a versionName charset that cannot break the properties format, so CI fails instead of every TV.
accepts() { # accepts <apk> <code> <name> <expected size>: rc 0 and the exact five lines
    local out sha
    out="$("$script" "$1" "$2" "$3")" || return 1
    sha="$(sha256sum "$1" | cut -d' ' -f1)"
    [[ "$out" == "$(printf 'versionCode=%s\nversionName=%s\napk=%s\nsha256=%s\nsize=%s' "$2" "$3" "$(basename "$1")" "$sha" "$4")" ]]
}
name32="$(printf 'n%.0s' $(seq 32))"
check "versionName of 32 chars accepted" accepts "$apk" 201 "$name32" 123457
check "versionName of 33 chars rejected" rejects "$apk" 201 "${name32}x"
check "versionName with every allowed char class accepted" accepts "$apk" 201 "1.0.0-rc.1+Build_9" 123457
for name in "0.2 1" "a=b" 'a\b' "$(printf 'a\nb')" "a:b" "a#b" "0.2.1 "; do
    check "bad versionName '$(printf '%q' "$name")'" rejects "$apk" 201 "$name"
done
check "versionCode 2147483647 (Int max) accepted" accepts "$apk" 2147483647 0.2.1 123457
for code in 2147483648 9999999999 10000000000 99999999999 123456789012345678901; do
    check "versionCode $code rejected" rejects "$apk" "$code" 0.2.1
done
big="$tmp/signage-kiosk-202.apk"
truncate -s 20971520 "$big"
check "APK of exactly 20 MiB accepted" accepts "$big" 202 0.2.2 20971520
truncate -s 20971521 "$big"
check "APK of 20 MiB + 1 rejected" rejects "$big" 202 0.2.2
: > "$big"
check "empty APK rejected" rejects "$big" 202 0.2.2
rm -f "$big"

echo "make-version-file: $((total - fails))/$total passed"
((fails == 0))
