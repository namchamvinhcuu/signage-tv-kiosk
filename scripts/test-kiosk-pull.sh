#!/usr/bin/env bash
# Offline tests for scripts/kiosk-pull.sh against scripts/test-fixtures/fake-github.py (plan kiosk-boot-autoupdate
# step 7, AC5). Needs bash, curl, jq, sha256sum, flock, python3. Everything lives in a temp dir removed on exit;
# nothing is written under $HOME. Run: scripts/test-kiosk-pull.sh (exit 0 = all pass).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
server_pid=""
cleanup() {
    [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null
    rm -rf "$tmp"
}
trap cleanup EXIT

home_had_dir="$(test -e "$HOME/kiosk-pull" && echo yes || echo no)"
fails=0 total=0
check() { # check <description> <command...>
    total=$((total + 1))
    if "${@:2}"; then return; fi
    echo "FAIL: $1"
    fails=$((fails + 1))
}

state_dir="$tmp/state"
dest="$tmp/dest"
blobs="$tmp/blobs"
mkdir -p "$state_dir" "$blobs"
# Token values that never occur in a log message, so "not in the log" checks cannot match by accident.
good_token="tok-GOOD-7a1c"
bad_token="tok-REJECTED-9f3e"
printf '%s' "$good_token" > "$tmp/token"

python3 "$here/test-fixtures/fake-github.py" "$state_dir" &
server_pid=$!
for _ in $(seq 50); do [[ -f "$state_dir/ports" ]] && break; sleep 0.1; done
read -r api_port _ < "$state_dir/ports" || { echo "fixture did not start"; exit 1; }
api="http://127.0.0.1:$api_port"

# make_release <code> <name>: real APK-like blob + its version.properties; prints nothing.
make_release() {
    head -c $((200000 + $1)) /dev/urandom > "$blobs/signage-kiosk-$1.apk"
    "$here/make-version-file.sh" "$blobs/signage-kiosk-$1.apk" "$1" "$2" > "$blobs/vp-$1"
}
# release_json <tag> <code> <prerelease> [apk blob override] -> GitHub-like release object
release_json() {
    local apk_blob="${4:-$blobs/signage-kiosk-$2.apk}"
    jq -n --arg tag "$1" --argjson pre "$3" --arg api "$api" --arg code "$2" --arg apk "$apk_blob" --arg vp "$blobs/vp-$2" '
        {tag_name: $tag, draft: false, prerelease: $pre,
         assets: [{id: ($code + "1"), name: "version.properties", url: ($api + "/repos/o/r/releases/assets/" + $code + "1")},
                  {id: ($code + "2"), name: ("signage-kiosk-" + $code + ".apk"), url: ($api + "/repos/o/r/releases/assets/" + $code + "2")}],
         _blobs: {(($code + "1")): $vp, (($code + "2")): $apk}}'
}
# set_state <latest tag|null> <release json>... (newest first, as GitHub lists them)
# $override (JSON object, API path -> HTTP status) forces error answers; $raw (API path -> literal body) forces a
# 200 with that body. Reset both to '{}' after use. public=true makes the fixture a public repo (no token needed).
override='{}'
raw='{}'
public=false
set_state() {
    local latest="$1"
    shift
    printf '%s\n' "$@" | jq -s --arg latest "$latest" --arg token "$good_token" --argjson ov "$override" --argjson rw "$raw" \
        --argjson pub "$public" '{
        token: $token,
        public: $pub,
        latest: (if $latest == "null" then null else $latest end),
        releases: map(del(._blobs)),
        blobs: (map(._blobs) | add),
        override: $ov,
        raw: $rw}' > "$state_dir/state.json.tmp"
    mv "$state_dir/state.json.tmp" "$state_dir/state.json"
}
run_pull() {
    "$here/kiosk-pull.sh" --dest "$dest" --token-file "$tmp/token" --repo o/r --api-base "$api" \
        --log "$tmp/pull.log" --lock "$tmp/.lock"
}
vc_of() { sed -n 's/^versionCode=//p' "$dest/$1/version.properties" 2>/dev/null; }
apk_ok() { # apk_ok <channel> <code>: served APK equals the release blob
    cmp -s "$dest/$1/signage-kiosk-$2.apk" "$blobs/signage-kiosk-$2.apk"
}
log_has() { grep -q -- "$1" "$tmp/pull.log"; }
log_ends() { grep -q -- "$1\$" "$tmp/pull.log"; } # a log line ends exactly with $1 (regex)
log_line_is() { # a log line, timestamp removed, equals $1 literally
    awk -v t="$1" '{ sub(/^[^ ]+ /, "") } $0 == t { f = 1 } END { exit !f }' "$tmp/pull.log"
}
log_count() { grep -c -- "$1" "$tmp/pull.log"; }
no_part_files() { ! find "$dest" -name '.*' -type f | grep -q .; }

for c in 200 201 202 203 204 205 206 207 208 209 210 211 212 213 214; do make_release "$c" "0.2.$((c - 200))"; done
for c in 300 301 302; do make_release "$c" "0.3.$((c - 300))"; done
r200="$(release_json v0.2.0 200 false)"
r201="$(release_json v0.2.1 201 true)"

# A: first run mirrors both channels (canary = highest code even if it is a prerelease).
set_state v0.2.0 "$r201" "$r200"
run_pull; rc=$?
check "A rc 0" test "$rc" -eq 0
check "A canary 201" test "$(vc_of canary)" = 201
check "A stable 200" test "$(vc_of stable)" = 200
check "A canary apk bytes" apk_ok canary 201
check "A stable apk bytes" apk_ok stable 200
check "A version.properties served as published" cmp -s "$dest/canary/version.properties" "$blobs/vp-201"
check "A no partial files" no_part_files
check "A files world-readable" test "$(stat -c %a "$dest/canary/signage-kiosk-201.apk")" = 644

# B: a re-run is a no-op.
before="$(stat -c %Y.%i "$dest/canary/signage-kiosk-201.apk") $(stat -c %Y.%i "$dest/stable/version.properties")"
sleep 1
run_pull; rc=$?
after="$(stat -c %Y.%i "$dest/canary/signage-kiosk-201.apk") $(stat -c %Y.%i "$dest/stable/version.properties")"
check "B rc 0" test "$rc" -eq 0
check "B files untouched" test "$before" = "$after"
check "B canary up_to_date logged" log_has "canary: up_to_date 201"
check "B stable up_to_date logged" log_has "stable: up_to_date 200"

# C: an APK whose sha256 does not match its version.properties never replaces the good one.
head -c "$(stat -c %s "$blobs/signage-kiosk-202.apk")" /dev/urandom > "$blobs/wrong-202.apk"
set_state v0.2.0 "$(release_json v0.2.2 202 true "$blobs/wrong-202.apk")" "$r201" "$r200"
run_pull; rc=$?
check "C rc 1" test "$rc" -eq 1
check "C canary still 201" test "$(vc_of canary)" = 201
check "C canary apk intact" apk_ok canary 201
check "C verify_failed logged" log_has "canary: verify_failed signage-kiosk-202.apk"
check "C no partial files" no_part_files
check "C stable unaffected" test "$(vc_of stable)" = 200

# D: a truncated download is rejected too.
head -c 1000 "$blobs/signage-kiosk-203.apk" > "$blobs/short-203.apk"
set_state v0.2.0 "$(release_json v0.2.3 203 true "$blobs/short-203.apk")" "$r201" "$r200"
run_pull; rc=$?
check "D rc 1" test "$rc" -eq 1
check "D canary still 201" test "$(vc_of canary)" = 201
check "D size mismatch logged" log_has "size 1000/"

# E: no stable release (404 on releases/latest) leaves stable untouched with one log line.
: > "$tmp/pull.log"
set_state null "$r201" "$r200"
run_pull; rc=$?
check "E rc 0" test "$rc" -eq 0
check "E stable untouched" test "$(vc_of stable)" = 200
check "E one 404 line" test "$(log_count 'stable: no published')" -eq 1

# F: a rejected token logs auth_failed and changes nothing.
printf '%s' "$bad_token" > "$tmp/token"
: > "$tmp/pull.log"
run_pull; rc=$?
check "F rc 1" test "$rc" -eq 1
check "F auth_failed logged" log_has "auth_failed: http 401"
check "F canary untouched" test "$(vc_of canary)" = 201
check "F token not in log" bash -c "! grep -q -e '$bad_token' -e '$good_token' '$tmp/pull.log'"
printf '%s' "$good_token" > "$tmp/token"

# G: the token never reaches the storage host after the redirect (H6).
check "G storage was hit" test -s "$state_dir/storage-headers.log"
check "G no Authorization at the storage host" bash -c "! grep -qi 'authorization' '$state_dir/storage-headers.log'"

# H: the previous APK is kept for one release, older ones are removed.
set_state v0.2.0 "$(release_json v0.2.4 204 true)" "$r201" "$r200"
run_pull
check "H canary 204" test "$(vc_of canary)" = 204
check "H keeps previous 201" test -f "$dest/canary/signage-kiosk-201.apk"
set_state v0.2.0 "$(release_json v0.2.5 205 true)" "$(release_json v0.2.4 204 true)" "$r201" "$r200"
run_pull
check "H canary 205" test "$(vc_of canary)" = 205
check "H keeps previous 204" test -f "$dest/canary/signage-kiosk-204.apk"
check "H removed 201" test ! -e "$dest/canary/signage-kiosk-201.apk"

# I: promoting a release moves stable.
set_state v0.2.1 "$(release_json v0.2.5 205 true)" "$(release_json v0.2.1 201 false)" "$r200"
run_pull
check "I stable 201 after promote" test "$(vc_of stable)" = 201
check "I stable apk bytes" apk_ok stable 201

# ---- Added by the independent test gate (L-V): selection rules, refused inputs, server errors, write order. ----
r205="$(release_json v0.2.5 205 true)"
r206="$(release_json v0.2.6 206 true)"
r206s="$(release_json v0.2.6 206 false)"
r201s="$(release_json v0.2.1 201 false)"
canary_is_206() { test "$(vc_of canary)" = 206 && apk_ok canary 206; }

# L: canary = highest code among published vX.Y.Z releases, whatever the list order; drafts and other tags ignored.
draft300="$(release_json v0.3.0 300 true | jq -c '.draft = true')"
nightly="$(release_json nightly 301 true)"
rc302="$(release_json v0.3.1-rc1 302 true)"
: > "$tmp/pull.log"
set_state v0.2.1 "$draft300" "$nightly" "$rc302" "$r205" "$r206" "$r201s" "$r200"
run_pull; rc=$?
check "L rc 0" test "$rc" -eq 0
check "L canary 206 (highest code, not the first listed)" canary_is_206
check "L draft 300 never mirrored" test ! -e "$dest/canary/signage-kiosk-300.apk"
check "L non-vX.Y.Z tags never mirrored" test ! -e "$dest/canary/signage-kiosk-301.apk" -a ! -e "$dest/canary/signage-kiosk-302.apk"
check "L stable up_to_date 201" log_has "stable: up_to_date 201"

# M: a release without a version.properties asset leaves the channel as it was.
no_vp="$(release_json v0.2.7 207 true | jq -c '.assets |= map(select(.name != "version.properties"))')"
: > "$tmp/pull.log"
set_state v0.2.1 "$no_vp" "$r206" "$r201s" "$r200"
run_pull; rc=$?
check "M rc 1" test "$rc" -eq 1
check "M canary still 206" canary_is_206
check "M missing asset logged" log_has "canary: v0.2.7 has no version.properties asset"
check "M nothing of 207 written" test ! -e "$dest/canary/signage-kiosk-207.apk"

# N: an invalid version.properties is refused before any download.
cp "$blobs/vp-208" "$blobs/vp-208.orig"
refused_208() { test "$1" -eq 1 && log_has "canary: invalid version.properties in v0.2.8" && canary_is_206 &&
    test ! -e "$dest/canary/signage-kiosk-208.apk" && no_part_files; }
for bad in versionCode=0 versionCode=2O8 size=0 size=-5 size=12k apk=signage-kiosk-208.zip apk=.signage-kiosk-208.apk \
    "sha256=$(printf 'a%.0s' $(seq 63))" "sha256=$(sed -n 's/^sha256=//p' "$blobs/vp-208.orig" | tr a-f A-F)"; do
    sed "s|^${bad%%=*}=.*|$bad|" "$blobs/vp-208.orig" > "$blobs/vp-208"
    : > "$tmp/pull.log"
    set_state v0.2.1 "$(release_json v0.2.8 208 true)" "$r206" "$r201s" "$r200"
    run_pull; rc=$?
    check "N refuses $bad" refused_208 "$rc"
done
# An apk name that climbs out of the channel directory never touches another channel.
sed 's|^apk=.*|apk=../stable/signage-kiosk-208.apk|' "$blobs/vp-208.orig" > "$blobs/vp-208"
stable_before="$(ls -A "$dest/stable")"
: > "$tmp/pull.log"
set_state v0.2.1 "$(release_json v0.2.8 208 true)" "$r206" "$r201s" "$r200"
run_pull; rc=$?
check "N traversal refused" refused_208 "$rc"
check "N traversal left stable alone" test "$(ls -A "$dest/stable")" = "$stable_before"
# A size that disagrees with the file is refused even when the sha256 matches (the app checks the size too).
size_208="$(sed -n 's/^size=//p' "$blobs/vp-208.orig")"
sed "s|^size=.*|size=$((size_208 + 1))|" "$blobs/vp-208.orig" > "$blobs/vp-208"
: > "$tmp/pull.log"
set_state v0.2.1 "$(release_json v0.2.8 208 true)" "$r206" "$r201s" "$r200"
run_pull; rc=$?
check "N wrong size rc 1" test "$rc" -eq 1
check "N wrong size logged with sha match" log_has "canary: verify_failed signage-kiosk-208.apk (v0.2.8): size $size_208/$((size_208 + 1)) sha256 match=yes"
check "N wrong size not mirrored" canary_is_206
check "N wrong size leaves no partial file" no_part_files
cp "$blobs/vp-208.orig" "$blobs/vp-208"

# O: version.properties names an APK the release does not carry.
cp "$blobs/signage-kiosk-209.apk" "$blobs/other-209.apk"
"$here/make-version-file.sh" "$blobs/other-209.apk" 209 0.2.9 > "$blobs/vp-209"
: > "$tmp/pull.log"
set_state v0.2.1 "$(release_json v0.2.9 209 true)" "$r206" "$r201s" "$r200"
run_pull; rc=$?
check "O rc 1" test "$rc" -eq 1
check "O missing apk asset logged" log_has "canary: v0.2.9 has no asset named other-209.apk"
check "O canary still 206" canary_is_206

# P: http 500 on the release list fails canary only; stable is still mirrored.
override='{"/repos/o/r/releases": 500}'
set_state v0.2.6 "$(release_json v0.2.10 210 true)" "$r206s" "$r201s" "$r200"
override='{}'
: > "$tmp/pull.log"
run_pull; rc=$?
check "P rc 1" test "$rc" -eq 1
check "P list error logged" log_has "canary: release list -> http 500"
check "P canary still 206" canary_is_206
check "P stable mirrored 206" test "$(vc_of stable)" = 206
check "P stable apk bytes" apk_ok stable 206

# Q: http 500 on releases/latest fails stable only (it would otherwise move back to 201).
override='{"/repos/o/r/releases/latest": 500}'
set_state v0.2.1 "$r206" "$r201s" "$r200"
override='{}'
: > "$tmp/pull.log"
run_pull; rc=$?
check "Q rc 1" test "$rc" -eq 1
check "Q latest error logged" log_has "stable: releases/latest -> http 500"
check "Q stable still 206" test "$(vc_of stable)" = 206
check "Q canary still checked" log_has "canary: up_to_date 206"

# R: 403 on an asset is an auth failure: distinct log line, nothing replaced.
override='{"/repos/o/r/releases/assets/2101": 403}'
set_state v0.2.6 "$(release_json v0.2.10 210 true)" "$r206s" "$r201s" "$r200"
override='{}'
: > "$tmp/pull.log"
run_pull; rc=$?
check "R rc 1" test "$rc" -eq 1
check "R auth_failed 403 logged" log_has "auth_failed: http 403"
check "R canary still 206" canary_is_206
check "R nothing of 210 written" test ! -e "$dest/canary/signage-kiosk-210.apk"

# S: the storage host answers 404 for the APK: nothing replaced, no partial file left.
set_state v0.2.6 "$(release_json v0.2.11 211 true | jq -c 'del(._blobs["2112"])')" "$r206s" "$r201s" "$r200"
: > "$tmp/pull.log"
run_pull; rc=$?
check "S rc 1" test "$rc" -eq 1
check "S download error logged" log_has "canary: download of signage-kiosk-211.apk (v0.2.11) -> http 404"
check "S canary still 206" canary_is_206
check "S no partial files" no_part_files
check "S nothing of 211 written" test ! -e "$dest/canary/signage-kiosk-211.apk"

# T: a transport error (nothing listening) fails both channels without an auth_failed line.
: > "$tmp/pull.log"
"$here/kiosk-pull.sh" --dest "$dest" --token-file "$tmp/token" --repo o/r --api-base "http://127.0.0.1:1" \
    --log "$tmp/pull.log" --lock "$tmp/.lock"; rc=$?
check "T rc 1" test "$rc" -eq 1
check "T list transport error logged" log_has "canary: release list -> http 000"
check "T latest transport error logged" log_has "stable: releases/latest -> http 000"
check "T no auth_failed" bash -c "! grep -q auth_failed '$tmp/pull.log'"
# Regression D4: the status is "000" exactly, not curl's "000" plus a fallback "000" ("http 000000").
check "T list transport error is exactly http 000 (D4)" log_ends "canary: release list -> http 000"
check "T latest transport error is exactly http 000 (D4)" log_ends "stable: releases/latest -> http 000"
check "T canary still 206" canary_is_206
check "T stable still 206" test "$(vc_of stable)" = 206

# U: write order and temp names, observed through an mv wrapper on PATH.
shim="$tmp/shim"
mkdir -p "$shim"
real_mv="$(command -v mv)"
# shellcheck disable=SC2016,SC2028  # the wrapper body is written literally
{
    echo '#!/usr/bin/env bash'
    echo 'printf "%s\n" "$*" >> "$MV_LOG"'
    echo 'if [[ -n "${MV_FAIL_APK:-}" && "${!#}" == *.apk ]]; then exit 1; fi'
    echo "exec $real_mv \"\$@\""
} > "$shim/mv"
chmod +x "$shim/mv"
run_pull_mv() { # run_pull_mv [MV_FAIL_APK=1]
    env PATH="$shim:$PATH" MV_LOG="$tmp/mv.log" "$@" "$here/kiosk-pull.sh" --dest "$dest" --token-file "$tmp/token" \
        --repo o/r --api-base "$api" --log "$tmp/pull.log" --lock "$tmp/.lock"
}
mv_line() { awk -v t="$1" '$NF == t {print NR; exit}' "$tmp/mv.log"; }
mv_hidden_same_dir() { # the rename into <target> came from a dotfile in the same directory
    local src
    src="$(awk -v t="$1" '$NF == t {print $(NF-1); exit}' "$tmp/mv.log")"
    [[ -n "$src" && "$(dirname "$src")" == "$(dirname "$1")" && "$(basename "$src")" == .* ]]
}
r212="$(release_json v0.2.12 212 true)"
set_state v0.2.6 "$r212" "$r206s" "$r201s" "$r200"
# U1: the APK cannot be moved into place -> version.properties must not advance.
: > "$tmp/pull.log"
: > "$tmp/mv.log"
run_pull_mv MV_FAIL_APK=1; rc=$?
check "U1 rc 1" test "$rc" -eq 1
check "U1 move failure logged" log_has "canary: could not move signage-kiosk-212.apk"
check "U1 version.properties not advanced" canary_is_206
check "U1 no partial files" no_part_files
# U2: normal run -> APK renamed first from a hidden temp file in the same directory, then version.properties.
: > "$tmp/pull.log"
: > "$tmp/mv.log"
run_pull_mv; rc=$?
check "U2 rc 0" test "$rc" -eq 0
check "U2 canary 212" test "$(vc_of canary)" = 212
check "U2 apk bytes" apk_ok canary 212
check "U2 apk temp is a dotfile in the channel dir" mv_hidden_same_dir "$dest/canary/signage-kiosk-212.apk"
check "U2 version.properties temp is a dotfile in the channel dir" mv_hidden_same_dir "$dest/canary/version.properties"
apk_at="$(mv_line "$dest/canary/signage-kiosk-212.apk")"
vp_at="$(mv_line "$dest/canary/version.properties")"
check "U2 APK renamed before version.properties" test "${apk_at:-0}" -gt 0 -a "${vp_at:-0}" -gt "${apk_at:-0}"

# V: a served APK whose bytes changed on disk is fetched again (up_to_date also needs the sha256).
served_size="$(stat -c %s "$dest/canary/signage-kiosk-212.apk")"
head -c "$served_size" /dev/urandom > "$dest/canary/signage-kiosk-212.apk"
: > "$tmp/pull.log"
run_pull; rc=$?
check "V rc 0" test "$rc" -eq 0
check "V apk restored" apk_ok canary 212
check "V mirrored again, not up_to_date" log_has "canary: mirrored 212"

# ---- Regression tests for the r3 findings D1, D3, D4 (asset path), D6 (test gate r4). ----
canary_is() { test "$(vc_of canary)" = "$1" && apk_ok canary "$1"; }
r206s_latest() { set_state v0.2.6 "$@" "$r206s" "$r201s" "$r200"; }

# X (D1): a 200 release list that is not a JSON array (captive/proxy page, changed API) fails canary loudly instead
# of reading as "no releases"; the canary channel is untouched and stable is still handled.
for body in '<html><body>Sign in to the proxy</body></html>' '{"message":"x"}' 'null' ''; do
    raw="$(jq -cn --arg b "$body" '{"/repos/o/r/releases": $b}')"
    r206s_latest "$(release_json v0.2.13 213 true)"
    raw='{}'
    : > "$tmp/pull.log"
    run_pull; rc=$?
    check "X rc 1 for list body '${body:0:12}'" test "$rc" -eq 1
    check "X not-an-array logged for '${body:0:12}'" log_has "canary: release list is not a JSON array"
    check "X not read as no releases for '${body:0:12}'" bash -c "! grep -q 'canary: no published' '$tmp/pull.log'"
    check "X canary untouched for '${body:0:12}'" canary_is 212
    check "X stable still handled for '${body:0:12}'" log_has "stable: up_to_date 206"
done
check "X nothing of 213 written" test ! -e "$dest/canary/signage-kiosk-213.apk"

# W (D4, asset path): a transport error on an asset download is logged as http 000 exactly.
r206s_latest "$(release_json v0.2.13 213 true | jq -c '.assets[0].url = "http://127.0.0.1:1/asset"')"
: > "$tmp/pull.log"
run_pull; rc=$?
check "W rc 1" test "$rc" -eq 1
check "W asset transport error is exactly http 000 (D4)" log_ends "canary: version.properties of v0.2.13 -> http 000"
check "W canary untouched" canary_is 212

# Y (D3): a duplicate key in version.properties resolves like java.util.Properties (last one wins), so the mirror
# and the TV agree. Y1: a wrong sha256 first, the right one last -> mirrored, and the next run is up_to_date.
wrong_sha="$(printf 'b%.0s' $(seq 64))"
cp "$blobs/vp-213" "$blobs/vp-213.orig"
{ printf 'sha256=%s\n' "$wrong_sha"; cat "$blobs/vp-213.orig"; } > "$blobs/vp-213"
r206s_latest "$(release_json v0.2.13 213 true)"
: > "$tmp/pull.log"
run_pull; rc=$?
check "Y1 rc 0 (last sha256 wins)" test "$rc" -eq 0
check "Y1 canary 213 mirrored" canary_is 213
check "Y1 mirrored logged" log_has "canary: mirrored 213"
: > "$tmp/pull.log"
run_pull; rc=$?
check "Y1 re-run rc 0" test "$rc" -eq 0
check "Y1 re-run up_to_date (served file read last-wins too)" log_has "canary: up_to_date 213"
# Y2: the right sha256 first, a wrong one last -> verify_failed, canary stays 213.
{ cat "$blobs/vp-214"; printf 'sha256=%s\n' "$wrong_sha"; } > "$blobs/vp-214.dup"
mv "$blobs/vp-214.dup" "$blobs/vp-214"
r206s_latest "$(release_json v0.2.14 214 true)" "$(release_json v0.2.13 213 true)"
: > "$tmp/pull.log"
run_pull; rc=$?
check "Y2 rc 1 (last sha256 is wrong)" test "$rc" -eq 1
check "Y2 verify_failed logged" log_has "canary: verify_failed signage-kiosk-214.apk (v0.2.14): size [0-9]*/[0-9]* sha256 match=no"
check "Y2 canary still 213" canary_is 213
check "Y2 nothing of 214 written" test ! -e "$dest/canary/signage-kiosk-214.apk"
check "Y2 no partial files" no_part_files

# Z (D6): an asset larger than the app's 20 MiB limit is never written into the public directory, even when its
# version.properties declares that size and the right sha256; exactly 20 MiB is still mirrored. Sparse blobs.
truncate -s 20971521 "$blobs/signage-kiosk-215.apk"
printf 'versionCode=215\nversionName=0.2.15\napk=signage-kiosk-215.apk\nsha256=%s\nsize=20971521\n' \
    "$(sha256sum "$blobs/signage-kiosk-215.apk" | cut -d' ' -f1)" > "$blobs/vp-215"
r206s_latest "$(release_json v0.2.15 215 true)" "$(release_json v0.2.13 213 true)"
: > "$tmp/pull.log"
run_pull 2> "$tmp/z1.err"; rc=$?
check "Z1 rc 1 (20 MiB + 1 refused)" test "$rc" -eq 1
check "Z1 nothing of 215 written" test ! -e "$dest/canary/signage-kiosk-215.apk"
check "Z1 no partial files" no_part_files
check "Z1 canary still 213" canary_is 213
check "Z1 exact abort line logged" log_line_is "canary: download of signage-kiosk-215.apk (v0.2.15) aborted, nothing received (over 20 MiB?)"
check "Z1 not reported as verify_failed" bash -c "! grep -q 'verify_failed signage-kiosk-215' '$tmp/pull.log'"
check "Z1 stderr empty (no stat/sha256sum noise)" test ! -s "$tmp/z1.err"
truncate -s 20971520 "$blobs/signage-kiosk-216.apk"
"$here/make-version-file.sh" "$blobs/signage-kiosk-216.apk" 216 0.2.16 > "$blobs/vp-216"
r206s_latest "$(release_json v0.2.16 216 true)" "$(release_json v0.2.13 213 true)"
: > "$tmp/pull.log"
run_pull; rc=$?
check "Z2 rc 0 (exactly 20 MiB accepted)" test "$rc" -eq 0
check "Z2 canary 216" canary_is 216
rm -f "$blobs/signage-kiosk-215.apk" "$blobs/signage-kiosk-216.apk" # big blobs go early; the trap removes the rest

# AN: public repo, no --token-file -> anonymous requests; both channels mirror into a fresh directory and no
# Authorization header reaches the API or the storage host.
anon_dest="$tmp/dest-anon"
run_pull_anon() { # run_pull_anon [extra args...]
    "$here/kiosk-pull.sh" --dest "$anon_dest" --repo o/r --api-base "$api" --log "$tmp/pull.log" --lock "$tmp/.lock" "$@"
}
anon_vc() { sed -n 's/^versionCode=//p' "$anon_dest/$1/version.properties" 2>/dev/null; }
public=true
set_state v0.2.0 "$r201" "$r200"
public=false
: > "$tmp/pull.log"
: > "$state_dir/api-headers.log"
: > "$state_dir/storage-headers.log"
run_pull_anon; rc=$?
check "AN rc 0 without --token-file" test "$rc" -eq 0
check "AN canary 201 mirrored" test "$(anon_vc canary)" = 201
check "AN stable 200 mirrored" test "$(anon_vc stable)" = 200
check "AN canary apk bytes" cmp -s "$anon_dest/canary/signage-kiosk-201.apk" "$blobs/signage-kiosk-201.apk"
check "AN stable apk bytes" cmp -s "$anon_dest/stable/signage-kiosk-200.apk" "$blobs/signage-kiosk-200.apk"
check "AN API server was hit" test -s "$state_dir/api-headers.log"
check "AN no Authorization at the API server" bash -c "! grep -qi 'authorization' '$state_dir/api-headers.log'"
check "AN no Authorization at the storage host" bash -c "! grep -qi 'authorization' '$state_dir/storage-headers.log'"
check "AN no auth_failed" bash -c "! grep -q auth_failed '$tmp/pull.log'"
# AN2: the same public repo with --token-file still works and still sends the token to the API host only.
: > "$tmp/pull.log"
: > "$state_dir/api-headers.log"
run_pull_anon --token-file "$tmp/token"; rc=$?
check "AN2 rc 0 with --token-file on a public repo" test "$rc" -eq 0
check "AN2 canary up_to_date 201" log_has "canary: up_to_date 201"
check "AN2 token sent to the API" grep -q "Bearer $good_token" "$state_dir/api-headers.log"
# AN3: a private repo answered anonymously -> GitHub's 404 fails the run with a hint (the anonymous mode is really anonymous).
set_state v0.2.0 "$r201" "$r200"
: > "$tmp/pull.log"
run_pull_anon; rc=$?
check "AN3 rc 1 anonymous on a private repo" test "$rc" -eq 1
check "AN3 404 hint logged" log_has "canary: release list -> http 404 (repo not found, or private and no --token-file)"
check "AN3 canary untouched" test "$(anon_vc canary)" = 201
# AN4: an unreadable --token-file is still an auth failure before any request, never a silent anonymous run.
chmod 000 "$tmp/token"
for tf in "$tmp/no-such-token" "$tmp/token"; do
    : > "$tmp/pull.log"
    : > "$state_dir/api-headers.log"
    run_pull_anon --token-file "$tf"; rc=$?
    check "AN4 rc 1 for unreadable ${tf##*/}" test "$rc" -eq 1
    check "AN4 auth_failed logged for ${tf##*/}" log_line_is "auth_failed: token file $tf not readable"
    check "AN4 no request made for ${tf##*/}" test ! -s "$state_dir/api-headers.log"
done
chmod 600 "$tmp/token"
check "AN4 anon dest untouched" test "$(anon_vc canary)" = 201 -a "$(anon_vc stable)" = 200

# ---- Regression tests for the step-10 r1 finding: a missing helper or tool fails loudly (rc 1 + one log line) before
# the lock and before any request, instead of reading as "no published release" / "lock held" with rc 0. ----
dest_sig() { find "$dest" "$anon_dest" -printf '%p %s %T@ %i %m\n' | sort; }
reset_logs() { : > "$tmp/pull.log"; : > "$state_dir/api-headers.log"; : > "$state_dir/storage-headers.log"; }
no_request() { test ! -s "$state_dir/api-headers.log" -a ! -s "$state_dir/storage-headers.log"; }
set_state v0.2.0 "$r201" "$r200"

# PF: kiosk-pull.sh deployed without version-from-tag.sh next to it (PF1), or with a non-executable copy (PF2, e.g. a
# copy that lost its mode bits); PF3 = the same directory with the helper executable works (positive control).
alone="$tmp/alone"
mkdir -p "$alone"
cp "$here/kiosk-pull.sh" "$alone/"
alone_dir="$(cd "$alone" && pwd)"
run_alone() {
    "$alone/kiosk-pull.sh" --dest "$anon_dest" --token-file "$tmp/token" --repo o/r --api-base "$api" \
        --log "$tmp/pull.log" --lock "$tmp/alone.lock"
}
for pf in PF1 PF2; do
    [[ "$pf" == PF2 ]] && cp "$here/version-from-tag.sh" "$alone/" && chmod 644 "$alone/version-from-tag.sh"
    sig_before="$(dest_sig)"
    reset_logs
    run_alone 2> "$tmp/pf.err"; rc=$?
    check "$pf rc 1" test "$rc" -eq 1
    check "$pf exact missing-helper line" log_line_is "missing $alone_dir/version-from-tag.sh"
    check "$pf only that line logged" test "$(wc -l < "$tmp/pull.log")" -eq 1
    check "$pf no request made" no_request
    check "$pf lock not taken (checked before flock)" test ! -e "$tmp/alone.lock"
    check "$pf dest untouched" test "$(dest_sig)" = "$sig_before"
    check "$pf stderr empty" test ! -s "$tmp/pf.err"
done
chmod 755 "$alone/version-from-tag.sh"
reset_logs
run_alone; rc=$?
check "PF3 rc 0 once the helper is executable" test "$rc" -eq 0
check "PF3 canary up_to_date" log_line_is "canary: up_to_date 201 (v0.2.1)"
check "PF3 stable up_to_date" log_line_is "stable: up_to_date 200 (v0.2.0)"
check "PF3 no missing line" bash -c "! grep -q missing '$tmp/pull.log'"

# PT: a required tool absent from PATH. The bin dir holds symlinks to every command the script runs, minus one tool;
# PT0 (nothing left out) mirrors both channels into a fresh directory, so the bin dir itself is proven sufficient.
needed=(bash env dirname mkdir date curl jq flock sha256sum mktemp rm tr sed tail head cut stat chmod mv cp basename)
make_bin() { # make_bin <dir> [tool to leave out]
    local t p
    mkdir -p "$1"
    for t in "${needed[@]}"; do
        [[ "$t" == "${2:-}" ]] && continue
        p="$(command -v "$t")" || { echo "make_bin: $t not on PATH"; return 1; }
        ln -s "$p" "$1/$t"
    done
}
run_pull_path() { # run_pull_path <bin dir> <dest> <lock>
    env PATH="$1" "$here/kiosk-pull.sh" --dest "$2" --token-file "$tmp/token" --repo o/r --api-base "$api" \
        --log "$tmp/pull.log" --lock "$3"
}
make_bin "$tmp/bin-all"
reset_logs
run_pull_path "$tmp/bin-all" "$tmp/dest-pathbin" "$tmp/pt0.lock" 2> "$tmp/pt.err"; rc=$?
check "PT0 rc 0 with the full bin dir" test "$rc" -eq 0
check "PT0 canary 201 mirrored" cmp -s "$tmp/dest-pathbin/canary/signage-kiosk-201.apk" "$blobs/signage-kiosk-201.apk"
check "PT0 stable 200 mirrored" cmp -s "$tmp/dest-pathbin/stable/signage-kiosk-200.apk" "$blobs/signage-kiosk-200.apk"
check "PT0 stderr empty" test ! -s "$tmp/pt.err"
for t in curl jq flock sha256sum; do
    make_bin "$tmp/bin-no-$t" "$t"
    check "PT $t really absent from its bin dir" test ! -e "$tmp/bin-no-$t/$t" -a -e "$tmp/bin-no-$t/bash"
    sig_before="$(dest_sig)"
    reset_logs
    run_pull_path "$tmp/bin-no-$t" "$dest" "$tmp/pt-$t.lock" 2> "$tmp/pt.err"; rc=$?
    check "PT rc 1 without $t" test "$rc" -eq 1
    check "PT exact line without $t" log_line_is "missing tool $t"
    check "PT only that line without $t" test "$(wc -l < "$tmp/pull.log")" -eq 1
    check "PT no request without $t" no_request
    check "PT lock not taken without $t" test ! -e "$tmp/pt-$t.lock"
    check "PT dest untouched without $t" test "$(dest_sig)" = "$sig_before"
    check "PT stderr empty without $t" test ! -s "$tmp/pt.err"
done

# NF: the 404 hint belongs to the release LIST only. NF1: with a valid token the list answers 404 (repo renamed or
# deleted, wrong --repo) -> same hint, rc 1, stable still handled. NF2: only releases/latest answers 404 -> stable keeps
# its own message and rc 0. NF3: anonymous on a private repo -> both lines, each with its own wording.
hint="canary: release list -> http 404 (repo not found, or private and no --token-file)"
stable404="stable: no published non-prerelease release yet (404), left as is"
override='{"/repos/o/r/releases": 404}'
set_state v0.2.0 "$r201" "$r200"
override='{}'
reset_logs
run_pull_anon --token-file "$tmp/token"; rc=$?
check "NF1 rc 1 (list 404 with a token)" test "$rc" -eq 1
check "NF1 exact hint line" log_line_is "$hint"
check "NF1 one release-list line" test "$(log_count 'canary: release list')" -eq 1
check "NF1 the token was sent" grep -q "Bearer $good_token" "$state_dir/api-headers.log"
check "NF1 no auth_failed" bash -c "! grep -q auth_failed '$tmp/pull.log'"
check "NF1 stable still handled" log_line_is "stable: up_to_date 200 (v0.2.0)"
check "NF1 canary untouched" test "$(anon_vc canary)" = 201
override='{"/repos/o/r/releases/latest": 404}'
set_state v0.2.0 "$r201" "$r200"
override='{}'
reset_logs
run_pull_anon --token-file "$tmp/token"; rc=$?
check "NF2 rc 0 (only latest 404)" test "$rc" -eq 0
check "NF2 stable keeps its own 404 line" log_line_is "$stable404"
check "NF2 no hint line" bash -c "! grep -q 'repo not found' '$tmp/pull.log'"
check "NF2 canary still checked" log_line_is "canary: up_to_date 201 (v0.2.1)"
set_state v0.2.0 "$r201" "$r200"
reset_logs
run_pull_anon; rc=$?
check "NF3 rc 1 anonymous on a private repo" test "$rc" -eq 1
check "NF3 canary hint line" log_line_is "$hint"
check "NF3 stable keeps its own 404 line" log_line_is "$stable404"
check "NF3 hint logged once" test "$(log_count 'repo not found')" -eq 1
check "NF3 no Authorization sent" bash -c "! grep -qi authorization '$state_dir/api-headers.log'"
check "NF3 anon dest untouched" test "$(anon_vc canary)" = 201 -a "$(anon_vc stable)" = 200

# J: a second run while one holds the lock exits 0 without touching anything.
: > "$tmp/pull.log"
flock "$tmp/.lock" sleep 3 &
holder=$!
sleep 0.5
run_pull; rc=$?
wait "$holder"
check "J rc 0 while locked" test "$rc" -eq 0
check "J skip logged" log_has "skip: another run holds"
check "J nothing else done" test "$(wc -l < "$tmp/pull.log")" -eq 1

# K: nothing was written under $HOME by the tests (--log/--lock point into the temp dir).
check "K ~/kiosk-pull not created" test "$(test -e "$HOME/kiosk-pull" && echo yes || echo no)" = "$home_had_dir"

echo "kiosk-pull: $((total - fails))/$total checks passed"
((fails == 0))
