#!/usr/bin/env bash
# Prints "<versionCode> <versionName>" for a release tag vX.Y.Z (versionCode = X*10000 + Y*100 + Z).
# Y and Z must stay below 100 so codes never collide; anything else exits 1. Used by CI and kiosk-pull.sh.
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v([0-9]{1,3})\.([0-9]{1,2})\.([0-9]{1,2})$ ]]; then
    echo "version-from-tag: '$tag' is not vX.Y.Z (Y, Z < 100)" >&2
    exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
code=$((major * 10000 + minor * 100 + patch))
if ((code <= 0)); then
    echo "version-from-tag: '$tag' gives versionCode 0" >&2
    exit 1
fi
echo "$code $major.$minor.$patch"
