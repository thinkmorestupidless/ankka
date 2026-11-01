#!/usr/bin/env bash
# What a change between two commits needs checked: the services it touched, each with its language,
# and whether the tests that cross services run. Printed as one line of JSON for the ci workflow:
#
#   scripts/changed-services.sh <base> <head>
#   {"services":[{"service":"orders","language":"scala"}],"tests":true}
#
# With no arguments, every service and the tests: what a run by hand checks.
#
#   services/<name>/**                          that service; the tests too when it is Scala
#   shared/**, build.sbt, project/**, contracts/**   every Scala service, and the tests (a schema
#                                               is on the shared module's classpath, so its fingerprint
#                                               is every Scala service's)
#   tests/**, context-map.json                  the tests
#   .github/**, scripts/**                      every service, and the tests
#   anything else                               nothing
set -euo pipefail
cd "$(dirname "$0")/.."

all_services() {
  for d in services/*/; do
    [ -f "$d/service.json" ] && basename "$d"
  done
  return 0
}

language_of() { scripts/service-language.sh "services/$1"; }

selected=""
tests=false
add() { case " $selected " in *" $1 "*) ;; *) selected="$selected $1" ;; esac; }

if [ $# -eq 0 ]; then
  for s in $(all_services); do add "$s"; done
  tests=true
else
  base="$1"; head="$2"
  # A first push has no base to compare with: everything is new.
  if [ -z "$base" ] || [ "$base" = "0000000000000000000000000000000000000000" ]; then
    changed=$(git ls-tree -r --name-only "$head")
  else
    changed=$(git diff --name-only "$base" "$head")
  fi
  while IFS= read -r path; do
    [ -z "$path" ] && continue
    case "$path" in
      services/*/*)
        name="${path#services/}"; name="${name%%/*}"
        if [ -f "services/$name/service.json" ]; then
          add "$name"
          [ "$(language_of "$name")" = scala ] && tests=true
        fi
        ;;
      shared/*|build.sbt|project/*|contracts/*)
        for s in $(all_services); do
          [ "$(language_of "$s")" = scala ] && add "$s"
        done
        tests=true
        ;;
      tests/*|context-map.json)
        tests=true
        ;;
      .github/*|scripts/*)
        for s in $(all_services); do add "$s"; done
        tests=true
        ;;
    esac
  done <<< "$changed"
fi

entries=""
for s in $selected; do
  entry="{\"service\":\"$s\",\"language\":\"$(language_of "$s")\"}"
  entries="${entries:+$entries,}$entry"
done
echo "{\"services\":[${entries}],\"tests\":${tests}}"
