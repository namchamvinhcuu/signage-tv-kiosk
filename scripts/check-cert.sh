#!/usr/bin/env bash
# Fails unless <apk> verifies and is signed by exactly one certificate whose SHA-256 is <sha256> (64 lowercase hex):
#   scripts/check-cert.sh <apk> <sha256>
# CI (release.yml) runs it before publishing, so an APK signed with any other key never reaches a TV (the TVs would
# refuse the update anyway, but only after downloading it). apksigner: $APKSIGNER, else the newest
# $ANDROID_HOME/build-tools/*/apksigner.
set -euo pipefail

apk="${1:?apk path}"
want="${2:?expected certificate sha256}"
[[ -f "$apk" ]] || { echo "check-cert: $apk not found" >&2; exit 1; }
[[ "$want" =~ ^[0-9a-f]{64}$ ]] || { echo "check-cert: expected sha256 must be 64 lowercase hex" >&2; exit 1; }

signer="${APKSIGNER:-}"
if [[ -z "$signer" ]]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    signer="$(printf '%s\n' "$sdk"/build-tools/*/apksigner | sort -V | tail -n 1)"
fi
[[ -x "$signer" ]] || { echo "check-cert: apksigner not found (set APKSIGNER or ANDROID_HOME)" >&2; exit 1; }

if ! out="$("$signer" verify --print-certs "$apk" 2>&1)"; then
    echo "check-cert: $apk does not verify:" >&2
    echo "$out" >&2
    exit 1
fi
mapfile -t got < <(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<< "$out")
if ((${#got[@]} != 1)); then
    echo "check-cert: expected exactly one signer, found ${#got[@]}" >&2
    exit 1
fi
if [[ "${got[0]}" != "$want" ]]; then
    echo "check-cert: certificate ${got[0]} is not the expected $want" >&2
    exit 1
fi
echo "check-cert: OK $want"
