#!/usr/bin/env bash
# Runs Maven inside the official maven:3.9 / JDK 21 image, so only Docker is needed (no local JDK or Maven).
# Usage: ./scripts/mvn-docker.sh test | package | verify | <any maven goals>
# A named Docker volume caches ~/.m2 between runs.
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker run --rm \
  -v "$(pwd)":/workspace -w /workspace \
  -v retail-api-m2:/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -B "${@:-test}"
