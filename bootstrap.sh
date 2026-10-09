#!/usr/bin/env bash
# Fetch the Gradle wrapper jar so ./gradlew works on a fresh clone.
# Run once, then use ./gradlew for everything.

set -euo pipefail

VERSION="8.14.6"
ROOT="$(cd "$(dirname "$0")" && pwd)"
TARGET_JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$TARGET_JAR" ] && unzip -p "$TARGET_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q GradleWrapperMain; then
  echo "Wrapper jar already present and valid at $TARGET_JAR"
  exit 0
fi

# If a bad jar exists, remove it so `gradle wrapper` regenerates cleanly.
rm -f "$TARGET_JAR"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
ZIP="$TMP/gradle-${VERSION}-bin.zip"

echo "Downloading Gradle ${VERSION}..."
curl -fL "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" -o "$ZIP"

echo "Extracting Gradle distribution..."
unzip -q "$ZIP" -d "$TMP"

GRADLE_BIN="$TMP/gradle-${VERSION}/bin/gradle"
chmod +x "$GRADLE_BIN"

echo "Generating wrapper via 'gradle wrapper'..."
cd "$ROOT"
"$GRADLE_BIN" --no-daemon wrapper --gradle-version "$VERSION" --distribution-type bin

chmod +x "$ROOT/gradlew"
echo ""
echo "Done. You can now run: ./gradlew build"
