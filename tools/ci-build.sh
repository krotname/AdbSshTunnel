#!/usr/bin/env bash
set -euo pipefail
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
gradle_version=8.11.1
gradle_root="$ARC_CACHE_DIR/toolchain/gradle"
mkdir -p "$gradle_root"
if [[ ! -x "$gradle_root/gradle-$gradle_version/bin/gradle" ]]; then
  curl -fsSL "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip" -o "$RUNNER_TEMP/gradle.zip"
  curl -fsSL "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip.sha256" -o "$RUNNER_TEMP/gradle.sha256"
  (cd "$RUNNER_TEMP" && printf '%s  gradle.zip\n' "$(cat gradle.sha256)" | sha256sum -c -)
  unzip -q "$RUNNER_TEMP/gradle.zip" -d "$gradle_root"
fi
arc-cache gradle-prepare --user-home "$GRADLE_USER_HOME"
"$gradle_root/gradle-$gradle_version/bin/gradle" --no-daemon --console plain testReleaseUnitTest lintRelease assembleRelease
