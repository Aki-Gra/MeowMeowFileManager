#!/usr/bin/env sh
# Fetch Gradle's published wrapper jar on first use, then verify its checksum.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
URL="https://services.gradle.org/distributions/gradle-9.5.0-wrapper.jar"

check_hash() {
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL=$(sha256sum "$1" | cut -d ' ' -f 1)
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL=$(shasum -a 256 "$1" | cut -d ' ' -f 1)
    else
        echo "SHA-256 verifier is required (sha256sum or shasum)." >&2
        return 1
    fi
    [ "$ACTUAL" = "$EXPECTED" ] || { echo "Gradle Wrapper checksum mismatch" >&2; return 1; }
}

if [ -f "$DEST" ]; then
    check_hash "$DEST"
    exit 0
fi
TMP="$DEST.tmp.$$"
trap 'rm -f "$TMP"' EXIT HUP INT TERM
if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$URL" --output "$TMP"
elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$TMP"
else
    echo "curl or wget is required to fetch the Gradle Wrapper." >&2
    exit 1
fi
check_hash "$TMP"
mv "$TMP" "$DEST"
echo "Verified Gradle Wrapper 9.5.0"
