#!/usr/bin/env bash
# Builds :tool and installs it on a real LP3 over Wi-Fi via the Tool Manager.
# This is the device-side counterpart to run-emulator.sh — there is no ADB on
# production LP3 hardware (it exposes only an MTP interface), so the Tool
# Manager's HTTP API is the only sideload path.
#
# Usage:
#   scripts/upload-to-device.sh 'https://192-168-15-151.my.local-ip.co:54449/#<64-hex>'
#   scripts/upload-to-device.sh --no-build 'https://...#<token>'
#
# Paste the URL straight off the phone's QR code. A fresh token is minted every
# time the Tool Manager restarts, so this argument is never reusable.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

BUILD=1
[[ "${1:-}" == "--no-build" ]] && { BUILD=0; shift; }
RAW_URL="${1:-}"
[[ -n "$RAW_URL" ]] || { echo "usage: $0 [--no-build] '<tool-manager-url-with-#token>'" >&2; exit 2; }

TOML="tool/lighttool.toml"
APK="tool/build/outputs/apk/debug/tool-debug.apk"
CA_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/light-toolmanager-ca.pem"
REMOTE_NAME="ponto.apk"
INBOX="Tool%20Inbox"

# ---- parse the URL -------------------------------------------------------
TOKEN="${RAW_URL##*#}"
[[ "$TOKEN" =~ ^[0-9a-fA-F]{64}$ ]] || {
    echo "error: no 64-char hex token found after '#' in the URL." >&2
    echo "       got: '${TOKEN:0:40}'" >&2
    exit 2
}
HOSTPORT="${RAW_URL#*://}"; HOSTPORT="${HOSTPORT%%/*}"; HOSTPORT="${HOSTPORT%%#*}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"; [[ "$PORT" == "$HOST" ]] && PORT=54449

# The Tool Manager prints a DOTTED host (10.0.0.5.my.local-ip.co) that its own
# *.my.local-ip.co wildcard certificate can never match — a wildcard covers a
# single DNS label. Rewrite the IP portion to dashes, which is one label.
if [[ "$HOST" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.(my\.local-ip\.co)$ ]]; then
    HOST="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]}-${BASH_REMATCH[4]}.${BASH_REMATCH[5]}"
    echo "note: rewrote dotted host to dashed form so it matches the wildcard cert"
fi
BASE="https://${HOST}:${PORT}"

# ---- CA bundle -----------------------------------------------------------
# The device serves only its leaf certificate, omitting the GlobalSign
# intermediate. Browsers paper over this by fetching it via the cert's AIA
# extension; curl does not, so append it to a local trust bundle once.
if [[ ! -s "$CA_CACHE" ]]; then
    echo "Fetching missing intermediate certificate..."
    mkdir -p "$(dirname "$CA_CACHE")"
    tmp_der=$(mktemp)
    curl -fsS --max-time 30 \
        http://secure.globalsign.com/cacert/gsgccr6alphasslca2025.crt -o "$tmp_der"
    { cat /etc/ssl/certs/ca-certificates.crt
      openssl x509 -inform DER -in "$tmp_der"; } > "$CA_CACHE"
    rm -f "$tmp_der"
fi
api() { curl -fsS --cacert "$CA_CACHE" -H "Authorization: Bearer $TOKEN" "$@"; }

# ---- preflight -----------------------------------------------------------
echo "Checking $BASE ..."
api --max-time 15 "$BASE/api/root" >/dev/null 2>&1 || {
    echo "error: cannot reach the Tool Manager at $BASE" >&2
    echo "       Is it still open on the phone? Restarting it mints a NEW token." >&2
    exit 1
}

# ---- build ---------------------------------------------------------------
# A real LP3 talks to com.lightos; the emulator talks to the emulator package.
# Flip for the build and always restore, so the emulator workflow is never left
# broken by a device deploy.
if (( BUILD )); then
    cp "$TOML" "$TOML.bak"
    trap 'mv -f "$TOML.bak" "$TOML" 2>/dev/null || true' EXIT
    sed -i 's|^# *serverPackage = "com.lightos"|serverPackage = "com.lightos"|; \
            s|^serverPackage = "com.thelightphone.sdk.emulator"|# serverPackage = "com.thelightphone.sdk.emulator"|' "$TOML"
    grep -q '^serverPackage = "com.lightos"' "$TOML" || { echo "error: could not set serverPackage" >&2; exit 1; }

    echo "Building :tool for com.lightos ..."
    ./gradlew --console=plain -q :tool:assembleDebug

    mv -f "$TOML.bak" "$TOML"; trap - EXIT
    echo "Restored $TOML (emulator settings)."
fi

[[ -f "$APK" ]] || { echo "error: $APK not found — run without --no-build" >&2; exit 1; }
SIZE=$(stat -c%s "$APK")

# ---- upload --------------------------------------------------------------
# /api/upload writes the request body VERBATIM to disk — it does not parse
# multipart. Sending with curl -F silently stores the MIME envelope (boundary
# and headers) as the file's contents, producing an unusable APK. Raw body only.
echo "Uploading $APK ($SIZE bytes) ..."
api --max-time 900 -o /dev/null \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK" \
    "$BASE/api/upload/$INBOX/$REMOTE_NAME"

# Note: /api/files 500s if given the page/size/sortBy/sortOrder query params
# that the web app itself sends. Bare path only.
listing() { api --max-time 20 "$BASE/api/files/$INBOX"; }
REMOTE_SIZE=$(listing | python3 -c \
    "import sys,json;print(next((x['size'] for x in json.load(sys.stdin)['data'] if x['title']=='$REMOTE_NAME'),0))")
[[ "$REMOTE_SIZE" == "$SIZE" ]] || {
    echo "error: size mismatch — local $SIZE, on device $REMOTE_SIZE" >&2; exit 1; }
echo "Verified $REMOTE_SIZE bytes on device."

# ---- install -------------------------------------------------------------
# Uploading alone does nothing. The notify call is what wakes
# LightApkInboxScanWorker, which consumes the APK and installs it.
echo "Notifying installer ..."
api --max-time 60 -o /dev/null -X POST "$BASE/api/notify/$INBOX"

echo -n "Waiting for the installer to consume it "
for _ in $(seq 1 30); do
    sleep 5
    if ! listing | grep -q "\"$REMOTE_NAME\""; then
        echo; echo "Installed. Launch Ponto from the Toolbox."
        echo "(No status endpoint exists — consumption means accepted, not verified.)"
        exit 0
    fi
    echo -n "."
done
echo
echo "warning: $REMOTE_NAME is still in the inbox after 150s." >&2
echo "         The installer may have rejected it — most likely the device's" >&2
echo "         filter level refused an APK with no matching developer key." >&2
exit 1
