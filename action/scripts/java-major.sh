#!/usr/bin/env bash
# Prints the major version of the `java` on PATH, or nothing when there is none.
#
# `java -version` writes to stderr, and the version is quoted on the first line in one of two
# shapes: `openjdk version "21.0.2"` since JDK 9, and `java version "1.8.0_392"` before it, where
# the major is the number after `1.`. Spike V7 of feature 013; T023 inlines this into action.yml.
set -uo pipefail

raw=$(java -version 2>&1) || exit 0
line=$(printf '%s\n' "$raw" | head -1)

# The first quoted field: 21.0.2, 1.8.0_392, 17-ea, …
quoted=${line#*\"}
quoted=${quoted%%\"*}
[ "$quoted" = "$line" ] && exit 0

case "$quoted" in
  1.*) rest=${quoted#1.}; printf '%s\n' "${rest%%[.._-]*}" ;;
  *)   printf '%s\n' "${quoted%%[.._-]*}" ;;
esac
