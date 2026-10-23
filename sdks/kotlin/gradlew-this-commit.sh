#!/usr/bin/env bash
# Runs the Kotlin build against this commit's Java SDK: publishes every ankka module the Java SDK
# reaches to ~/.m2 (the same hook the Java and Kotlin template suites use), reads the version sbt
# published them at, and hands the rest of the command line to ./gradlew with that version.
#
#   sdks/kotlin/gradlew-this-commit.sh check
#   sdks/kotlin/gradlew-this-commit.sh :samples:shopping-cart:test
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

# On a failure sbt's own output is the only explanation, so it is printed rather than swallowed.
out="$(sbt -batch -no-colors -Dankka.cluster.tests=off -Dankka.template.tests=kotlin \
  cli/templateArtifacts 'print sdkJava/version' 2>&1)" || { echo "$out" >&2; exit 1; }
version="$(printf '%s\n' "$out" | tail -n 1 | tr -d '[:space:]')"
case "$version" in
  [0-9]*) ;;
  *) echo "could not read the Java SDK's version from sbt (got '$version')" >&2; exit 1 ;;
esac

cd "$root/sdks/kotlin"
exec ./gradlew --no-daemon "-PankkaVersion=$version" "$@"
