#!/usr/bin/env bash
# Holds the living features, the glossary and the specs that name their scenarios to each other,
# with speckit-bdd's checker: an undefined word, a refused synonym, a contradiction, a spec naming a
# scenario no feature has. Run by the `features` job and by `just features`.
#
# Everything it checks with comes from the extension's own config, the file `/speckit-bdd-check`
# reads, so this and the command cannot check different things or run different versions of the
# checker. Needs `uv` on PATH.
set -euo pipefail

cd "$(dirname "$0")/.."
config=.specify/extensions/bdd/bdd-config.yml
[ -f "$config" ] || { echo "features-check: no $config: is the bdd extension installed?" >&2; exit 2; }

# One `key: value` line of the config, with the quotes around the value taken off.
value() {
  sed -n "s/^$1:[[:space:]]*//p" "$config" | sed -e "s/^'\(.*\)'[[:space:]]*$/\1/" -e 's/^"\(.*\)"[[:space:]]*$/\1/'
}

glossary="$(value glossary)"
features="$(value features)"
specs="$(value specs)"
first="$(value specs-from)"
checker="$(value checker)"
for name in glossary features specs checker; do
  [ -n "${!name}" ] || { echo "features-check: $config sets no '$name'" >&2; exit 2; }
done

# The checker is a command line with a quoted argument in it, so it is split as a shell splits it.
eval "set -- $checker"
args=(check --root . --glossary "$glossary" --features "$features" --specs "$specs")
[ -z "$first" ] || args+=(--specs-from "$first")

# The findings, each with its file and line; the exit status is the checker's.
"$@" "${args[@]}"

# Nothing found is only good news if something was read. `specs-from` naming a spec later than every
# spec there is reads none, and features that were moved read as no scenarios; both exit 0.
report="$("$@" "${args[@]}" --format json)"
python3 - "$report" <<'EOF'
import json, sys

report = json.loads(sys.argv[1])
problems = []
if report["scenarios"] == 0:
    problems.append("no scenario was read: have the features moved?")
if report["specs"]["checked"] == 0:
    problems.append(f"no spec was read ({report['specs']['skipped']} skipped): does specs-from name a spec that exists?")
for problem in problems:
    print(f"features-check: {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
EOF
