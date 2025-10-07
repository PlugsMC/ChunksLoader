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

if [[ "${GITHUB_REF_TYPE:-}" == "tag" && -n "${GITHUB_REF_NAME:-}" ]]; then
  raw_tag="$GITHUB_REF_NAME"
elif [[ "${GITHUB_REF:-}" == refs/tags/* ]]; then
  raw_tag="${GITHUB_REF#refs/tags/}"
elif [[ -n "${CI_COMMIT_TAG:-}" ]]; then
  raw_tag="$CI_COMMIT_TAG"
elif git -C "$PROJECT_DIR" describe --tags --exact-match >/dev/null 2>&1; then
  raw_tag="$(git -C "$PROJECT_DIR" describe --tags --exact-match)"
elif git -C "$PROJECT_DIR" tag --points-at HEAD >/dev/null 2>&1 && \
     [[ -n "$(git -C "$PROJECT_DIR" tag --points-at HEAD)" ]]; then
  raw_tag="$(git -C "$PROJECT_DIR" tag --points-at HEAD | head -n1)"
elif git -C "$PROJECT_DIR" describe --tags --abbrev=0 >/dev/null 2>&1; then
  raw_tag="$(git -C "$PROJECT_DIR" describe --tags --abbrev=0)"
fi

if [[ -n "${raw_tag:-}" ]]; then
  RELEASE_TAG="$raw_tag"
  PLUGIN_VERSION="${raw_tag#v}"
else
  PLUGIN_VERSION=$(mvn -B -q -DforceStdout help:evaluate -Dexpression=project.version)
  RELEASE_TAG="v${PLUGIN_VERSION}"
fi

if [[ -z "${PLUGIN_VERSION:-}" || -z "${RELEASE_TAG:-}" ]]; then
  echo "Unable to resolve plugin version from Git tags or Maven project" >&2
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
      -Drevision="$PLUGIN_VERSION" \
      -Drelease.tag="$RELEASE_TAG" \
      clean package

  jar_name="ChunksLoader-${version}-${RELEASE_TAG}.jar"
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
