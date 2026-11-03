#!/usr/bin/env bash
# Gradle build kept under ~12 GB RAM: Gradle 3 GB + Kotlin daemon 2 GB + <=6 workers.
# usage: scripts/odysseus/build.sh assembleDebug
set -euo pipefail
ENV="${ANDROID_ENV:-$HOME/tools/android-env.sh}"
# shellcheck disable=SC1090
. "$ENV"
cd "$(dirname "$0")/../.."
exec ./gradlew --max-workers=6 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8" \
  -Pkotlin.daemon.jvmargs="-Xmx2g" "$@"
