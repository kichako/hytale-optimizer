#!/usr/bin/env bash
# Builds the self-patching release jar: build/HytaleOptimizer-<version>.jar
# It contains no Hypixel code. On first start it patches the user's own HytaleServer.jar
# (bundled Vineflower + ECJ) and caches the result next to itself.
# Needs a JDK 25+ (javac, jar), curl and sha256sum. Does NOT need HytaleServer.jar.
set -euo pipefail

VINEFLOWER_VERSION=1.11.1
VINEFLOWER_SHA256=a615d07ddbbcd489369674f40e42df639c32be95410890b38f173d5c1e2ea39c
ECJ_VERSION=3.46.100
ECJ_SHA256=74c93172475fee0cd4e6eefb44233516dd40525642da35d009146d97ea598229

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="$(grep -oE 'VERSION = "[^"]+"' "$ROOT/plugin/src/dev/hytaleopt/OptimizerTransformer.java" | cut -d'"' -f2)"
BUILD="$ROOT/build"
WORK="$BUILD/release"
TOOLS="$ROOT/tools"

fetch() { # name url sha256
  local file="$TOOLS/$1"
  if [ ! -f "$file" ]; then
    echo "Downloading $1..."
    curl -fsSL -o "$file" "$2"
  fi
  if [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$3" ]; then
    rm -f "$file"
    echo "$1 checksum mismatch, deleted the download. Try again." >&2
    exit 1
  fi
}

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/jar/hytaleopt/patches" "$WORK/jar/hytaleopt/tools" "$WORK/jar/META-INF/licenses" "$TOOLS"

fetch "vineflower-$VINEFLOWER_VERSION.jar" "https://repo1.maven.org/maven2/org/vineflower/vineflower/$VINEFLOWER_VERSION/vineflower-$VINEFLOWER_VERSION.jar" "$VINEFLOWER_SHA256"
fetch "ecj-$ECJ_VERSION.jar" "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$ECJ_VERSION/ecj-$ECJ_VERSION.jar" "$ECJ_SHA256"

echo "Compiling..."
javac --release 25 -nowarn -encoding UTF-8 -d "$WORK/classes" $(find "$ROOT/plugin/src" "$ROOT/plugin/stubs" -name '*.java')
# The stub only exists to compile against; the server provides the real interface.
rm -rf "$WORK/classes/com"
cp -r "$WORK/classes/." "$WORK/jar/"

cp "$ROOT"/patches/*.patch "$WORK/jar/hytaleopt/patches/"
cp "$ROOT/patches/targets.properties" "$WORK/jar/hytaleopt/patches.properties"
cp "$TOOLS/vineflower-$VINEFLOWER_VERSION.jar" "$TOOLS/ecj-$ECJ_VERSION.jar" "$WORK/jar/hytaleopt/tools/"
cp -r "$ROOT/plugin/resources/." "$WORK/jar/"
cp "$ROOT/LICENSE" "$WORK/jar/META-INF/licenses/hytale-optimizer-LICENSE.txt"
cp "$ROOT"/third-party/* "$WORK/jar/META-INF/licenses/"

JAR="$BUILD/HytaleOptimizer-$VERSION.jar"
jar --create --file "$JAR" -C "$WORK/jar" .
(cd "$BUILD" && sha256sum "$(basename "$JAR")" > "$(basename "$JAR").sha256")
echo
echo "Done: $JAR"
cat "$JAR.sha256"
