#!/usr/bin/env bash
# Builds build/HytaleOptimizer.jar from your own HytaleServer.jar.
# Needs a JDK 25+ (java, javac, jar), git, unzip and sha256sum.
# Usage: ./build.sh /path/to/HytaleServer.jar
set -euo pipefail

VINEFLOWER_VERSION=1.11.1
VINEFLOWER_SHA256=a615d07ddbbcd489369674f40e42df639c32be95410890b38f173d5c1e2ea39c

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER_JAR="$(realpath "${1:?usage: ./build.sh /path/to/HytaleServer.jar}")"
BUILD="$ROOT/build"
WORK="$BUILD/work"
TOOLS="$ROOT/tools"
TARGETS="$ROOT/patches/targets.properties"

rm -rf "$BUILD"
mkdir -p "$WORK/classes" "$WORK/compiled" "$WORK/plugin" "$TOOLS"

# 1. Vineflower
VINEFLOWER="$TOOLS/vineflower-$VINEFLOWER_VERSION.jar"
if [ ! -f "$VINEFLOWER" ]; then
  echo "Downloading Vineflower $VINEFLOWER_VERSION..."
  curl -fsSL -o "$VINEFLOWER" "https://repo1.maven.org/maven2/org/vineflower/vineflower/$VINEFLOWER_VERSION/vineflower-$VINEFLOWER_VERSION.jar"
fi
if [ "$(sha256sum "$VINEFLOWER" | cut -d' ' -f1)" != "$VINEFLOWER_SHA256" ]; then
  rm -f "$VINEFLOWER"
  echo "Vineflower checksum mismatch, deleted the download. Try again." >&2
  exit 1
fi

# 2. Check the server version and pull out only the classes we patch
while IFS='=' read -r internal rest; do
  [ -z "$internal" ] && continue
  expected="${rest%%;*}"
  if ! unzip -q -o "$SERVER_JAR" "$internal.class" -d "$WORK/classes" 2>/dev/null; then
    echo "$internal.class is missing from $SERVER_JAR. This server version is not supported." >&2
    exit 1
  fi
  if [ "$(sha256sum "$WORK/classes/$internal.class" | cut -d' ' -f1)" != "$expected" ]; then
    echo "$internal differs from the version these patches were made for (HytaleServer 0.7.0, rev 648ebf37). This server version is not supported yet." >&2
    exit 1
  fi
done < "$TARGETS"

# 3. Decompile, patch, compile
echo "Decompiling patch targets..."
java -jar "$VINEFLOWER" -dgs=1 -log=WARN "-e=$SERVER_JAR" "$WORK/classes" "$WORK/src" > /dev/null
echo "Applying patches..."
(cd "$WORK/src" && git apply -p1 --whitespace=nowarn "$ROOT"/patches/*.patch)
echo "Compiling..."
javac --release 25 -nowarn -proc:none -encoding UTF-8 -cp "$SERVER_JAR" -d "$WORK/compiled" $(find "$WORK/src" -name '*.java')
javac --release 25 -nowarn -proc:none -encoding UTF-8 -cp "$SERVER_JAR" -d "$WORK/plugin" $(find "$ROOT/plugin/src" -name '*.java')

# 4. Package: replacement classes + the hashes they are pinned to
(cd "$WORK/compiled" && find . -name '*.class') | while read -r cls; do
  internal="${cls#./}"; internal="${internal%.class}"
  if ! grep -q "^$internal=" "$TARGETS"; then
    echo "Patched sources produced an unexpected class: $internal" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$WORK/plugin/hytaleopt/classes/$internal")"
  cp "$WORK/compiled/$internal.class" "$WORK/plugin/hytaleopt/classes/$internal.class"
done
cp "$TARGETS" "$WORK/plugin/hytaleopt/patches.properties"
cp -r "$ROOT/plugin/resources/." "$WORK/plugin/"

jar --create --file "$BUILD/HytaleOptimizer.jar" -C "$WORK/plugin" .
echo
echo "Done: $BUILD/HytaleOptimizer.jar"
