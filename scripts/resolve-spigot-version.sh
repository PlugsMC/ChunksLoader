#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <minecraft-version>" >&2
  exit 1
fi

version="$1"

case "$version" in
  1.20|1.20.1|1.20.2|1.20.3|1.20.4|1.20.5|1.20.6)
    printf '%s\n' "1.20.6-R0.1-SNAPSHOT"
    ;;
  1.21|1.21.1|1.21.2|1.21.3|1.21.4|1.21.5|1.21.6|1.21.7|1.21.8|1.21.9)
    printf '%s\n' "1.21.1-R0.1-SNAPSHOT"
    ;;
  *)
    echo "Unsupported Minecraft version: $version" >&2
    exit 1
    ;;
esac
