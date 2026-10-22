#!/usr/bin/env bash
# Sign an unsigned release APK with the project keystore.
#
# Usage:
#   KEYSTORE=ff-release.jks KS_PASS=... KEY_ALIAS=facefusion KEY_PASS=... \
#     ./sign.sh app-release-unsigned.apk signed.apk
set -euo pipefail
IN="${1:?usage: sign.sh <unsigned.apk> <out.apk>}"
OUT="${2:?usage: sign.sh <unsigned.apk> <out.apk>}"
: "${KEYSTORE:?set KEYSTORE to the .jks path}"
: "${KS_PASS:?set KS_PASS (store password)}"
: "${KEY_ALIAS:?set KEY_ALIAS}"
: "${KEY_PASS:?set KEY_PASS (key password)}"
ALIGNED="$(mktemp -u).apk"
zipalign -p -f 4 "$IN" "$ALIGNED"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --out "$OUT" "$ALIGNED"
apksigner verify --print-certs "$OUT"
