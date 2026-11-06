#!/usr/bin/env bash
# Prints the version.properties the app reads (UpdateManifest) for an APK already named as it will be served:
#   scripts/make-version-file.sh <path/signage-kiosk-<code>.apk> <versionCode> <versionName> > version.properties
# Used by CI (release.yml) and by the phone test (plan step 9), so both publish the same format.
set -euo pipefail

apk="${1:?apk path}"
code="${2:?versionCode}"
name="${3:?versionName}"
[[ -f "$apk" ]] || { echo "make-version-file: $apk not found" >&2; exit 1; }
# Same limits as the app's UpdateManifest.parse, so CI fails at publish time instead of every TV rejecting it later.
if [[ ! "$code" =~ ^[1-9][0-9]{0,9}$ ]] || ((code > 2147483647)); then
    echo "make-version-file: bad versionCode '$code'" >&2
    exit 1
fi
[[ "$name" =~ ^[0-9A-Za-z._+-]{1,32}$ ]] || { echo "make-version-file: bad versionName '$name' (1-32 chars of [0-9A-Za-z._+-])" >&2; exit 1; }
base="$(basename "$apk")"
[[ "$base" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.apk$ ]] || { echo "make-version-file: bad apk file name '$base'" >&2; exit 1; }

sha="$(sha256sum "$apk" | cut -d' ' -f1)"
size="$(stat -c %s "$apk")"
((size > 0 && size <= 20971520)) || { echo "make-version-file: size $size outside 1..20 MiB (app limit)" >&2; exit 1; }
printf 'versionCode=%s\nversionName=%s\napk=%s\nsha256=%s\nsize=%s\n' "$code" "$name" "$base" "$sha" "$size"
