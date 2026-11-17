#!/usr/bin/env bash
# Odysseus fork: builds noise_fortify_test.c with the NDK against the noise-c source the sendspin
# build fetched (and patched), for 32-bit ARM with _FORTIFY_SOURCE like the app, then runs it on an
# adb device. Exit 0 = every pattern expands; an abort ("FORTIFY: memchr") = the patch is missing.
# Usage: run_noise_fortify_test.sh <noise_c-src dir> [adb serial]
set -euo pipefail
SRC=${1:?noise_c-src dir (sendspin/.cxx/.../_deps/noise_c-src)}
SERIAL=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd)
# NDK: $ANDROID_NDK_HOME, else the newest one under the SDK Gradle uses (local.properties sdk.dir),
# else under $ANDROID_HOME.
SDK=$(sed -n 's/^sdk\.dir=//p' "$HERE/../../../../local.properties" 2>/dev/null || true)
NDK=${ANDROID_NDK_HOME:-$(ls -d "${SDK:-${ANDROID_HOME:-}}"/ndk/* 2>/dev/null | sort -V | tail -1)}
[[ -n "$NDK" && -d "$NDK" ]] || { echo "no NDK found (set ANDROID_NDK_HOME)" >&2; exit 2; }
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang
OUT=${TMPDIR:-/tmp}/noise_fortify_test
P=$SRC/src
"$CC" -O2 -D_FORTIFY_SOURCE=2 -std=c99 -I"$SRC/include" -I"$P" \
  -DNOISE_USE_REFERENCE_BACKEND=1 -DNOISE_USE_AES=0 -DNOISE_USE_LIBSODIUM=0 -DNOISE_USE_CUSTOM_RAND=0 \
  "$HERE/noise_fortify_test.c" \
  $P/protocol/{cipherstate,dhstate,errors,handshakestate,hashstate,internal,names,patterns,rand_os,randstate,signstate,symmetricstate,util}.c \
  $P/backend/ref/{cipher-chachapoly,dh-curve25519,hash-sha256}.c \
  $P/crypto/chacha/chacha.c $P/crypto/donna/poly1305-donna.c $P/crypto/sha2/sha256.c $P/crypto/x25519/x25519.c \
  -o "$OUT" -w
ADB=(adb ${SERIAL:+-s "$SERIAL"})
"${ADB[@]}" push "$OUT" /data/local/tmp/noise_fortify_test >/dev/null
out=$("${ADB[@]}" shell '/data/local/tmp/noise_fortify_test 2>&1; echo "exit=$?"' | tr -d '\r')
"${ADB[@]}" shell rm -f /data/local/tmp/noise_fortify_test
echo "$out"
[[ "$out" == *"exit=0"* ]]
