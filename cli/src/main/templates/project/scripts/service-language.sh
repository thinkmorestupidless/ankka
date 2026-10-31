#!/usr/bin/env bash
# Prints the language a service is written in, from its build files: what the checks and the deploy
# job run for it.
#
#   scripts/service-language.sh services/orders     # scala
set -euo pipefail

dir="${1:?usage: scripts/service-language.sh <services/dir>}"

if [ -f "$dir/pyproject.toml" ]; then echo python
elif [ -f "$dir/package.json" ] && grep -q '"vite"' "$dir/package.json"; then echo web
elif [ -f "$dir/package.json" ] && grep -q '"ankka"' "$dir/package.json"; then echo typescript
elif [ -f "$dir/Cargo.toml" ]; then echo rust
elif [ -f "$dir/go.mod" ]; then echo go
elif [ -f "$dir/pom.xml" ]; then echo java
elif [ -f "$dir/build.gradle.kts" ]; then echo kotlin
elif [ -d "$dir/src/main/scala" ]; then echo scala
else
  echo "cannot tell the language of $dir" >&2
  exit 1
fi
