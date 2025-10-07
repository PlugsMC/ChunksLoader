#!/usr/bin/env bash
set -euo pipefail

# Builds the plugin for every supported Minecraft version and copies
# the resulting jars into the assets directory.

if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven (mvn) is required to run this script." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/assets"
VERSIONS_FILE="$PROJECT_DIR/supported-versions.txt"
RESOLVE_SCRIPT="$SCRIPT_DIR/resolve-spigot-version.sh"

if [[ ! -f "$VERSIONS_FILE" ]]; then
  echo "Unable to locate supported versions file at $VERSIONS_FILE" >&2
  exit 1
fi

if [[ ! -x "$RESOLVE_SCRIPT" ]]; then
  echo "Unable to locate spigot resolution helper at $RESOLVE_SCRIPT" >&2
  exit 1
fi

readarray -t VERSIONS < <(grep -vE "^#|^$" "$VERSIONS_FILE")

if [[ ${#VERSIONS[@]} -eq 0 ]]; then
  echo "No supported Minecraft versions listed in $VERSIONS_FILE" >&2
  exit 1
fi

mkdir -p "$ASSETS_DIR"
rm -f "$ASSETS_DIR"/ChunksLoader-*.jar

PLUGIN_VERSION=$(mvn -B -q -DforceStdout help:evaluate -Dexpression=project.version)

if [[ -z "$PLUGIN_VERSION" ]]; then
  echo "Unable to resolve plugin version from Maven project" >&2
  exit 1
fi

for version in "${VERSIONS[@]}"; do
  if ! spigot_version="$("$RESOLVE_SCRIPT" "$version")"; then
    echo "No Spigot API mapping configured for Minecraft $version" >&2
    exit 1
  fi

  echo "\n=== Building for Minecraft $version using Spigot API $spigot_version ==="
  mvn -B -DskipTests \
      -Dmc.version="$version" \
      -Dspigot.api.version="$spigot_version" \
      clean package

  jar_name="ChunksLoader-${version}-v${PLUGIN_VERSION}.jar"
  jar_path="$PROJECT_DIR/target/$jar_name"
  asset_path="$ASSETS_DIR/$jar_name"

  if [[ ! -f "$asset_path" ]]; then
    if [[ ! -f "$jar_path" ]]; then
      echo "Expected jar $jar_path not found" >&2
      exit 1
    fi
    cp "$jar_path" "$asset_path"
  fi

done

echo "\nAll builds complete. Assets are available in $ASSETS_DIR"
