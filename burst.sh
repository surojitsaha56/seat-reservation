#!/usr/bin/env bash
# One-command burst: ./burst.sh <BASE_URL> [options]   (see the header of burst/Burst.java for options)
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "usage: ./burst.sh <BASE_URL> [options]   e.g. ./burst.sh http://localhost:8080" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "java not found. Install a JDK 21+ (e.g. https://adoptium.net) and make sure 'java' is on PATH." >&2
  exit 2
fi
# "java -version" prints e.g.: openjdk version "21.0.12.1" 2026-08-18  (or "1.8.0_x" for Java 8)
ver=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\)[."].*/\1/p' | head -n1)
if [ -z "$ver" ] || [ "$ver" -lt 21 ]; then
  echo "Java 21+ is required (found: ${ver:-unknown}). Install JDK 21 from https://adoptium.net" >&2
  exit 2
fi

cd "$(dirname "$0")"
exec java burst/Burst.java "$@"
