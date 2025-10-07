#!/usr/bin/env bash
set -euo pipefail

# Builds the plugin for every supported Minecraft version and copies
# the resulting jars into the assets directory.

if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven (mvn) is required to run this script." >&2
  exit 1
fi

# Map each Minecraft release to the closest available Spigot API version.
# Spigot publishes API snapshots per major release series rather than for
# every patch version, so several Minecraft versions share the same API.
declare -A SPIGOT_VERSIONS=(
  ["1.20"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.1"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.2"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.3"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.4"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.5"]="1.20.6-R0.1-SNAPSHOT"
  ["1.20.6"]="1.20.6-R0.1-SNAPSHOT"
  ["1.21"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.1"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.2"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.3"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.4"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.5"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.6"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.7"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.8"]="1.21.1-R0.1-SNAPSHOT"
  ["1.21.9"]="1.21.1-R0.1-SNAPSHOT"
)

VERSIONS=(
  1.20
  1.20.1
  1.20.2
  1.20.3
  1.20.4
  1.20.5
  1.20.6
  1.21
  1.21.1
  1.21.2
  1.21.3
  1.21.4
  1.21.5
  1.21.6
  1.21.7
  1.21.8
  1.21.9
)

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/assets"

mkdir -p "$ASSETS_DIR"

for version in "${VERSIONS[@]}"; do
  spigot_version="${SPIGOT_VERSIONS[$version]-}"
  if [[ -z "$spigot_version" ]]; then
    echo "No Spigot API mapping configured for Minecraft $version" >&2
    exit 1
  fi

  echo "\n=== Building for Minecraft $version using Spigot API $spigot_version ==="
  mvn -B -DskipTests \
      -Dmc.version="$version" \
      -Dspigot.api.version="$spigot_version" \
      clean package

done

echo "\nAll builds complete. Assets are available in $ASSETS_DIR"
