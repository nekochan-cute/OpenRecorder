#!/bin/sh
# Restore the unmodified Gradle Wrapper JAR from its text transport representation.
set -eu
wrapper_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../gradle/wrapper" && pwd)
jar="$wrapper_dir/gradle-wrapper.jar"
expected=$(cat "$jar.sha256")
checksum() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{ print $1 }'
    else echo "SHA-256 verification requires sha256sum or shasum" >&2; return 1
    fi
}
if [ ! -f "$jar" ]; then
    temporary="$jar.tmp.$$"
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    umask 077
    base64 -d < "$jar.base64" > "$temporary"
    [ "$(checksum "$temporary")" = "$expected" ] || { echo "Gradle Wrapper checksum mismatch" >&2; exit 1; }
    mv "$temporary" "$jar"
    trap - EXIT HUP INT TERM
fi
[ "$(checksum "$jar")" = "$expected" ] || { echo "Gradle Wrapper checksum mismatch" >&2; exit 1; }
