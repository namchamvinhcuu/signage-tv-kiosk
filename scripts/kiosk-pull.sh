#!/usr/bin/env bash
# Mirrors the kiosk APK releases of a GitHub repo into the signage server's static directory, where the
# app finds them at <server>/kiosk/<channel>/version.properties (plan kiosk-boot-autoupdate B4, D5, D10).
#   canary = the non-draft release (prerelease or not) with the highest versionCode (from its vX.Y.Z tag)
#   stable = releases/latest (GitHub never returns a prerelease there; the `promote` workflow sets it)
# Each channel: version.properties is checked, the APK is downloaded to a hidden temp file in the same directory,
# its size + sha256 are checked, then the APK and finally version.properties are renamed into place. A failed
# download never replaces a good file. The previous APK is kept for one release.
#
# Usage: kiosk-pull.sh --dest DIR --repo OWNER/REPO [--token-file FILE]
#                      [--api-base URL] [--log FILE] [--lock FILE]
# Cron on the signage server (no flock wrapper — the lock is taken here):
#   */10 * * * * ~/kiosk-pull/kiosk-pull.sh --dest <server>/server/public/kiosk --repo OWNER/REPO
# Deploy version-from-tag.sh into the same directory; the run fails (rc 1, logged) without it.
# A public repo needs no token. For a private one, --token-file holds a fine-grained token (Contents: read-only,
# one repo); it is passed to curl through a mode-600 header file, never on a command line, and never logged.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dest="" token_file="" repo=""
api_base="https://api.github.com"
log="$HOME/kiosk-pull/kiosk-pull.log"
lock="$HOME/kiosk-pull/.lock"

while (($#)); do
    case "$1" in
        --dest) dest="$2"; shift 2 ;;
        --token-file) token_file="$2"; shift 2 ;;
        --repo) repo="$2"; shift 2 ;;
        --api-base) api_base="${2%/}"; shift 2 ;;
        --log) log="$2"; shift 2 ;;
        --lock) lock="$2"; shift 2 ;;
        *) echo "kiosk-pull: unknown argument $1" >&2; exit 2 ;;
    esac
done
[[ -n "$dest" && -n "$repo" ]] || { echo "kiosk-pull: --dest and --repo are required" >&2; exit 2; }

mkdir -p "$(dirname "$log")" "$(dirname "$lock")" "$dest"
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$log"; }

# A missing tool or helper must fail loudly: otherwise flock (rc 127) looks like "lock held" and every tag looks
# invalid ("no published release"), both with rc 0.
for t in curl jq flock sha256sum; do
    command -v "$t" >/dev/null || { log "missing tool $t"; exit 1; }
done
[[ -x "$here/version-from-tag.sh" ]] || { log "missing $here/version-from-tag.sh"; exit 1; }

exec 9>"$lock"
if ! flock -n 9; then
    log "skip: another run holds $lock"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# Without --token-file the requests are anonymous (public repo; GitHub allows 60 API requests/h per IP, a run uses ~4).
hdr="$work/auth.hdr"
(umask 077; : > "$hdr")
if [[ -n "$token_file" ]]; then
    [[ -r "$token_file" ]] || { log "auth_failed: token file $token_file not readable"; exit 1; }
    (umask 077; printf 'Authorization: Bearer %s\n' "$(tr -d '\r\n' < "$token_file")" > "$hdr")
fi

# Prints the HTTP status ("000" on a transport error). curl only sends the Authorization header to the original host:
# the asset URL redirects to a storage host, which authenticates by its signed URL instead.
api_get() {
    # On a transport error curl still prints 000 through -w; `|| true` only keeps errexit from stopping here.
    curl -sS --max-time 60 -o "$2" -w '%{http_code}' -H @"$hdr" -H 'Accept: application/vnd.github+json' "$1" 2>>"$log" || true
}
asset_get() {
    # 20 MiB = the app's UpdateManifest.MAX_APK_BYTES: nothing larger is ever written into the public directory.
    curl -sS --max-time 600 --max-filesize 20971520 -L -o "$2" -w '%{http_code}' -H @"$hdr" \
        -H 'Accept: application/octet-stream' "$1" 2>>"$log" || true
}
check_auth() {
    if [[ "$1" == 401 || "$1" == 403 ]]; then
        log "auth_failed: http $1 from $2 (token expired or lacks Contents read?)"
        exit 1
    fi
}

# Last occurrence wins, as in java.util.Properties (what the app reads), so mirror and TV agree on a duplicate key.
prop() { sed -n "s/^$1=//p" "$2" | tail -n 1 | tr -d '\r'; }

# mirror <channel> <release.json>; returns 1 on any failure (the channel directory is left as it was).
mirror() {
    local ch="$1" rel="$2" dir="$dest/$1"
    local tag vp_url code vc apk sha size cur old_apk apk_url part got_size got_sha
    tag="$(jq -r '.tag_name' "$rel")"
    vp_url="$(jq -r '.assets[] | select(.name == "version.properties") | .url' "$rel" | head -n 1)"
    if [[ -z "$vp_url" ]]; then
        log "$ch: $tag has no version.properties asset"
        return 1
    fi
    code="$(asset_get "$vp_url" "$work/$ch.vp")"
    check_auth "$code" "$ch version.properties"
    if [[ "$code" != 200 ]]; then
        log "$ch: version.properties of $tag -> http $code"
        return 1
    fi
    vc="$(prop versionCode "$work/$ch.vp")"
    apk="$(prop apk "$work/$ch.vp")"
    sha="$(prop sha256 "$work/$ch.vp")"
    size="$(prop size "$work/$ch.vp")"
    if [[ ! "$vc" =~ ^[1-9][0-9]*$ || ! "$apk" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.apk$ ||
          ! "$sha" =~ ^[0-9a-f]{64}$ || ! "$size" =~ ^[1-9][0-9]*$ ]]; then
        log "$ch: invalid version.properties in $tag"
        return 1
    fi

    cur=""
    old_apk=""
    if [[ -f "$dir/version.properties" ]]; then
        cur="$(prop versionCode "$dir/version.properties")"
        old_apk="$(prop apk "$dir/version.properties")"
    fi
    if [[ "$cur" == "$vc" && -f "$dir/$apk" ]] && [[ "$(sha256sum "$dir/$apk" | cut -d' ' -f1)" == "$sha" ]]; then
        log "$ch: up_to_date $vc ($tag)"
        return 0
    fi

    apk_url="$(jq -r --arg n "$apk" '.assets[] | select(.name == $n) | .url' "$rel" | head -n 1)"
    if [[ -z "$apk_url" ]]; then
        log "$ch: $tag has no asset named $apk"
        return 1
    fi
    mkdir -p "$dir"
    # Dot-file: express.static does not serve dotfiles, so a partial download is never visible.
    part="$dir/.$apk.part"
    code="$(asset_get "$apk_url" "$part")"
    check_auth "$code" "$ch $apk"
    if [[ "$code" != 200 ]]; then
        rm -f "$part"
        log "$ch: download of $apk ($tag) -> http $code"
        return 1
    fi
    # curl aborts (exit 63) without creating the file when the asset is over --max-filesize, yet -w still says 200.
    if [[ ! -f "$part" ]]; then
        log "$ch: download of $apk ($tag) aborted, nothing received (over 20 MiB?)"
        return 1
    fi
    got_size="$(stat -c %s "$part")"
    got_sha="$(sha256sum "$part" | cut -d' ' -f1)"
    if [[ "$got_size" != "$size" || "$got_sha" != "$sha" ]]; then
        rm -f "$part"
        log "$ch: verify_failed $apk ($tag): size $got_size/$size sha256 match=$([[ "$got_sha" == "$sha" ]] && echo yes || echo no)"
        return 1
    fi
    # errexit is off inside a function called as `mirror ... || failed=1`, so every write is checked explicitly.
    if ! { chmod 644 "$part" && mv -f "$part" "$dir/$apk"; }; then
        rm -f "$part"
        log "$ch: could not move $apk into $dir"
        return 1
    fi
    if ! { cp "$work/$ch.vp" "$dir/.version.properties.tmp" && chmod 644 "$dir/.version.properties.tmp" &&
           mv -f "$dir/.version.properties.tmp" "$dir/version.properties"; }; then
        log "$ch: could not write $dir/version.properties"
        return 1
    fi
    # Keep the current and the previous APK only.
    local f
    for f in "$dir"/*.apk; do
        [[ -e "$f" ]] || continue
        case "$(basename "$f")" in
            "$apk" | "$old_apk") ;;
            *) rm -f "$f" ;;
        esac
    done
    log "$ch: mirrored $vc ($tag) $apk"
}

failed=0

code="$(api_get "$api_base/repos/$repo/releases?per_page=50" "$work/releases.json")"
check_auth "$code" "releases"
if [[ "$code" == 404 ]]; then
    # GitHub answers 404 (not 401) to an anonymous request for a private repo.
    log "canary: release list -> http 404 (repo not found, or private and no --token-file)"
    failed=1
elif [[ "$code" != 200 ]]; then
    log "canary: release list -> http $code"
    failed=1
elif ! jq -e 'type == "array"' "$work/releases.json" >/dev/null 2>&1; then
    # A proxy/captive page or a changed API answering 200 must not look like "no releases".
    log "canary: release list is not a JSON array"
    failed=1
else
    best_code=0
    best_tag=""
    while IFS= read -r tag; do
        if v="$("$here/version-from-tag.sh" "$tag" 2>/dev/null)"; then
            c="${v%% *}"
            if ((c > best_code)); then
                best_code="$c"
                best_tag="$tag"
            fi
        fi
    done < <(jq -r '.[] | select(.draft == false) | .tag_name' "$work/releases.json")
    if [[ -z "$best_tag" ]]; then
        log "canary: no published vX.Y.Z release"
    else
        jq --arg t "$best_tag" '.[] | select(.tag_name == $t)' "$work/releases.json" > "$work/canary.json"
        mirror canary "$work/canary.json" || failed=1
    fi
fi

code="$(api_get "$api_base/repos/$repo/releases/latest" "$work/latest.json")"
check_auth "$code" "releases/latest"
if [[ "$code" == 404 ]]; then
    log "stable: no published non-prerelease release yet (404), left as is"
elif [[ "$code" != 200 ]]; then
    log "stable: releases/latest -> http $code"
    failed=1
else
    mirror stable "$work/latest.json" || failed=1
fi

exit "$failed"
