#!/usr/bin/env bash
# Tests scripts/version-from-tag.sh. Run: scripts/test-version-from-tag.sh (exit 0 = all pass).
set -uo pipefail
script="$(cd "$(dirname "$0")" && pwd)/version-from-tag.sh"
fails=0 total=0

ok() { # ok <tag> <expected output>
    total=$((total + 1))
    local got
    if got="$("$script" "$1" 2>/dev/null)" && [[ "$got" == "$2" ]]; then return; fi
    echo "FAIL: '$1' -> '${got:-<error>}', expected '$2'"
    fails=$((fails + 1))
}
bad() { # bad <tag>
    total=$((total + 1))
    if "$script" "$1" >/dev/null 2>&1; then
        echo "FAIL: '$1' accepted, expected rejection"
        fails=$((fails + 1))
    fi
}

ok v0.2.0 "200 0.2.0"
ok v0.2.1 "201 0.2.1"
ok v1.0.0 "10000 1.0.0"
ok v0.0.1 "1 0.0.1"
ok v0.99.99 "9999 0.99.99"
ok v12.3.45 "120345 12.3.45"
ok v0.02.07 "207 0.2.7"   # leading zeros are decimal, not octal
ok v999.99.99 "9999999 999.99.99"
bad v0.0.0          # versionCode 0
bad v0.100.0        # minor >= 100 would collide
bad v0.1.100
bad 0.2.0           # no v
bad v0.2
bad v0.2.0-rc1
bad "v0.2.0 "
bad V0.2.0
bad v1000.0.0
bad ""
# Added by the independent test gate: 08/09 are invalid octal, 010 would be 8 in octal.
ok v0.08.09 "809 0.8.9"
ok v010.0.1 "100001 10.0.1"
ok v0.1.0 "100 0.1.0"
bad $'v0.2.0\n'
bad "v0.2.0.1"
bad "v-1.2.3"
bad "v0..1"
bad "xv0.2.0"
bad "refs/tags/v0.2.0"
bad "v٠.2.0"      # Arabic-Indic digit zero

echo "version-from-tag: $((total - fails))/$total passed"
((fails == 0))
