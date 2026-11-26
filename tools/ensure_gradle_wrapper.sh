#!/usr/bin/env bash
# Creates ./gradlew and gradle/wrapper/gradle-wrapper.jar if they are missing.
# (The wrapper jar is a binary that cannot be hand-written, so CI generates it.)
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_VERSION="8.9"

if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -f gradlew ]; then
  chmod +x gradlew
  echo "Gradle wrapper already present."
  exit 0
fi

if command -v gradle >/dev/null 2>&1; then
  GRADLE_BIN="gradle"
else
  echo "Gradle not found on PATH; downloading Gradle ${GRADLE_VERSION} ..."
  TMP="$(mktemp -d)"
  curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP/gradle.zip"
  unzip -q "$TMP/gradle.zip" -d "$TMP"
  GRADLE_BIN="$TMP/gradle-${GRADLE_VERSION}/bin/gradle"
fi

WORK="$(mktemp -d)"
( cd "$WORK" && "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin --no-daemon )
cp "$WORK/gradlew" "$WORK/gradlew.bat" .
mkdir -p gradle/wrapper
cp "$WORK/gradle/wrapper/"* gradle/wrapper/
chmod +x gradlew
echo "Gradle wrapper created (Gradle ${GRADLE_VERSION})."
