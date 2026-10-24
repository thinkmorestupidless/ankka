#!/usr/bin/env bash
# Gather everything a review needs for one change, in one pass.
#
#   gather.sh 123             a GitHub pull request number (needs gh)
#   gather.sh feature/x       a branch, compared with the repository's default branch
#   gather.sh main..HEAD      a commit range
#   gather.sh --working-tree  staged and unstaged changes against HEAD
#   gather.sh                 same as --working-tree when the tree is dirty, else HEAD~1..HEAD
#
# Writes description.md, commits.txt, files.txt, diff.patch and summary.md into an output
# directory ($PR_REVIEW_OUT, else $CLAUDE_SCRATCHPAD/pr-review-<target>, else a mktemp dir) and
# prints summary.md. Needs git; uses gh when present and the target is a PR number.
set -euo pipefail

target="${1:-}"
root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "not inside a git repository" >&2; exit 2; }
cd "$root"

default_branch() {
  local b
  b="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')" || true
  if [ -z "$b" ]; then
    for c in main master; do git show-ref --verify --quiet "refs/heads/$c" && { b="$c"; break; }; done
  fi
  echo "${b:-main}"
}

have_gh=0
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then have_gh=1; fi

mode=""; range=""; pr=""
if [ -z "$target" ]; then
  if [ -n "$(git status --porcelain)" ]; then mode=worktree; else mode=range; range="HEAD~1..HEAD"; fi
elif [ "$target" = "--working-tree" ]; then
  mode=worktree
elif [[ "$target" =~ ^[0-9]+$ ]]; then
  mode=pr; pr="$target"
elif [[ "$target" == *..* ]]; then
  mode=range; range="$target"
else
  mode=branch
fi

slug="$(echo "${target:-worktree}" | tr -c 'A-Za-z0-9._-' '_' | sed 's/_*$//')"
out="${PR_REVIEW_OUT:-}"
if [ -z "$out" ]; then
  if [ -n "${CLAUDE_SCRATCHPAD:-}" ]; then out="$CLAUDE_SCRATCHPAD/pr-review-$slug"; else out="$(mktemp -d -t pr-review-"$slug")"; fi
fi
mkdir -p "$out"

base_ref=""; head_ref=""
case "$mode" in
  pr)
    if [ "$have_gh" = 1 ]; then
      gh pr view "$pr" --json title,body,author,baseRefName,headRefName,url,isDraft,additions,deletions,changedFiles,labels,reviewDecision \
        --template '# {{.title}}

{{.url}} by {{.author.login}} — base `{{.baseRefName}}`, head `{{.headRefName}}`{{if .isDraft}} (draft){{end}}
Labels: {{range .labels}}{{.name}} {{end}}
Review decision: {{.reviewDecision}}

{{.body}}
' > "$out/description.md"
      { echo; echo "## Discussion"; gh pr view "$pr" --comments 2>/dev/null | sed -n '/^──/,$p' || true; } >> "$out/description.md"
      { echo "## CI"; gh pr checks "$pr" 2>&1 || true; } > "$out/ci.txt"
      base_ref="$(gh pr view "$pr" --json baseRefName -q .baseRefName)"
      head_ref="$(gh pr view "$pr" --json headRefName -q .headRefName)"
      git fetch --quiet origin "$base_ref" "pull/$pr/head:refs/pr-review/$pr" 2>/dev/null || git fetch --quiet origin "$base_ref" "$head_ref" 2>/dev/null || true
      if git show-ref --verify --quiet "refs/pr-review/$pr"; then head_ref="refs/pr-review/$pr"; else head_ref="origin/$head_ref"; fi
      base_ref="origin/$base_ref"
    else
      echo "gh is not available or not authenticated; treat $pr as a branch name or pass a range" >&2; exit 2
    fi ;;
  branch)
    base_ref="$(default_branch)"; head_ref="$target"
    git rev-parse --verify --quiet "$head_ref" >/dev/null || { echo "unknown ref $head_ref" >&2; exit 2; }
    printf '# Branch %s against %s\n\n(no pull request description; read the commit messages below)\n' "$head_ref" "$base_ref" > "$out/description.md" ;;
  range)
    base_ref="${range%%..*}"; head_ref="${range##*..}"
    printf '# Range %s\n\n(no pull request description; read the commit messages below)\n' "$range" > "$out/description.md" ;;
  worktree)
    printf '# Working tree against HEAD\n\n(uncommitted changes; no description)\n' > "$out/description.md" ;;
esac

if [ "$mode" = worktree ]; then
  git diff HEAD > "$out/diff.patch"
  git diff HEAD --name-status > "$out/files.txt"
  git diff HEAD --numstat > "$out/numstat.txt"
  git log -1 --format='%h %s' > "$out/commits.txt"
else
  merge_base="$(git merge-base "$base_ref" "$head_ref" 2>/dev/null || echo "$base_ref")"
  git diff "$merge_base" "$head_ref" > "$out/diff.patch"
  git diff "$merge_base" "$head_ref" --name-status > "$out/files.txt"
  git diff "$merge_base" "$head_ref" --numstat > "$out/numstat.txt"
  git log --format='%h %an %ad%n    %s%n%w(0,4,4)%b' --date=short "$merge_base..$head_ref" > "$out/commits.txt"
  echo "merge-base $merge_base" > "$out/refs.txt"
fi

# Classify files by extension into the language skills; everything else is "general".
classify() {
  case "$1" in
    *.scala|*.sbt|*.sc|project/*.scala) echo scala ;;
    *.java) echo java ;;
    *.kt|*.kts) echo kotlin ;;
    *.go|go.mod|go.sum) echo go ;;
    *.py|*.pyi|pyproject.toml|uv.lock|poetry.lock|requirements*.txt|setup.cfg|setup.py) echo python ;;
    *.rs|Cargo.toml|Cargo.lock) echo rust ;;
    *.ts|*.tsx|*.mts|*.cts|*.js|*.mjs|*.cjs|*.jsx|package.json|package-lock.json|pnpm-lock.yaml|yarn.lock|tsconfig*.json) echo typescript ;;
    *.sql|*migrations*|*migration*) echo sql-migration ;;
    .github/workflows/*|Dockerfile*|*.dockerfile|Justfile|Makefile|*.sh|*.bash|*.zsh) echo build-ci ;;
    *.proto|openapi*|*.openapi.*|*.avsc|*schema*.json) echo api-contract ;;
    *.md|*.mdx|*.rst|*.txt) echo docs ;;
    *.yaml|*.yml|*.toml|*.json|*.conf|*.properties|*.hocon) echo config ;;
    *) echo other ;;
  esac
}

generated_re='(^|/)(generated|gen|target|build|dist|node_modules|\.g8|vendor)/|\.pb\.|_pb2\.|\.g\.(dart|cs)$|\.min\.(js|css)$|\.lock$|-lock\.(json|yaml)$|\.snap$'

# bash 3.2 (macOS) has no associative arrays: tally through a file of "class lines" rows.
total_add=0; total_del=0; hand_add=0; hand_del=0
gen_files=()
: > "$out/classes.txt"
while IFS=$'\t' read -r add del path; do
  [ -z "${path:-}" ] && continue
  [ "$add" = "-" ] && add=0; [ "$del" = "-" ] && del=0
  total_add=$((total_add + add)); total_del=$((total_del + del))
  echo "$(classify "$path") $((add + del))" >> "$out/classes.txt"
  if [[ "$path" =~ $generated_re ]]; then gen_files+=("$path"); else hand_add=$((hand_add + add)); hand_del=$((hand_del + del)); fi
done < "$out/numstat.txt"

# Tooling configuration present in the repository, so the review does not duplicate it.
tooling=()
for f in .scalafmt.conf .scalafix.conf .editorconfig .golangci.yml .golangci.yaml staticcheck.conf \
         ruff.toml .ruff.toml mypy.ini .mypy.ini pyrightconfig.json pyproject.toml \
         rustfmt.toml .rustfmt.toml clippy.toml .clippy.toml deny.toml \
         eslint.config.js eslint.config.mjs eslint.config.ts .eslintrc.json .eslintrc.js .eslintrc.cjs biome.json biome.jsonc .prettierrc .prettierrc.json tsconfig.json \
         .editorconfig detekt.yml config/detekt/detekt.yml checkstyle.xml config/checkstyle/checkstyle.xml \
         build.sbt build.gradle build.gradle.kts pom.xml go.mod Cargo.toml package.json; do
  [ -e "$f" ] && tooling+=("$f")
done
rules=()
if [ -d .claude/rules ]; then while IFS= read -r r; do rules+=("$r"); done < <(ls .claude/rules/*.md 2>/dev/null); fi
instructions=()
for f in CLAUDE.md AGENTS.md CONTRIBUTING.md ../CLAUDE.md ../CLAUDE.local.md; do [ -e "$f" ] && instructions+=("$f"); done

# Conventions the repository declares for the description and the commits, so the review holds the
# change to them: a pull request template, a commit message hook or a commitlint configuration.
conventions=()
for f in .github/pull_request_template.md .github/PULL_REQUEST_TEMPLATE.md .github/PULL_REQUEST_TEMPLATE docs/pull_request_template.md PULL_REQUEST_TEMPLATE.md \
         .githooks/commit-msg .husky/commit-msg commitlint.config.js commitlint.config.mjs commitlint.config.cjs commitlint.config.ts .commitlintrc .commitlintrc.json .commitlintrc.yml .commitlintrc.yaml .commitlintrc.js .gitlint; do
  # Exact name, not a case-insensitive filesystem's match: pull_request_template.md and
  # PULL_REQUEST_TEMPLATE.md are one file on macOS and would be listed twice.
  [ -e "$f" ] && ls -1a "$(dirname "$f")" | grep -qxF "$(basename "$f")" && conventions+=("$f")
done

# The repository's own review checklist: REVIEW.md at the root and in any directory above a changed
# file. Its optional "## pr-review" section names bundled checklists to skip and skills to load.
: > "$out/review-files.txt"
[ -e REVIEW.md ] && echo REVIEW.md >> "$out/review-files.txt"
while IFS=$'\t' read -r _ _ path; do
  [ -z "${path:-}" ] && continue
  d="$(dirname "$path")"
  while [ "$d" != . ] && [ "$d" != / ]; do
    if [ -e "$d/REVIEW.md" ] && ! grep -qxF "$d/REVIEW.md" "$out/review-files.txt"; then echo "$d/REVIEW.md" >> "$out/review-files.txt"; fi
    d="$(dirname "$d")"
  done
done < "$out/numstat.txt"

# A project or personal skill named like a bundled language skill replaces it.
own_skills=()
for d in .claude/skills/pr-review-*/ "${HOME:-/nonexistent}"/.claude/skills/pr-review-*/; do
  [ -e "${d}SKILL.md" ] && own_skills+=("${d%/}")
done

{
  echo "# Review input: ${target:-working tree}"
  echo
  echo "Output directory: $out"
  echo "Mode: $mode${base_ref:+; base $base_ref}${head_ref:+; head $head_ref}"
  echo
  echo "## Size"
  echo
  echo "| | files | +added | -deleted |"
  echo "|---|---|---|---|"
  echo "| all | $(wc -l < "$out/numstat.txt" | tr -d ' ') | $total_add | $total_del |"
  echo "| hand-written (generated and lockfiles excluded) | $(( $(wc -l < "$out/numstat.txt") - ${#gen_files[@]} )) | $hand_add | $hand_del |"
  hand=$((hand_add + hand_del))
  if [ "$hand" -gt 1000 ]; then echo; echo "**Size verdict:** $hand hand-written lines changed: ask for a split unless this is a deletion, a mechanical rename or a named tool's refactor."
  elif [ "$hand" -gt 400 ]; then echo; echo "**Size verdict:** $hand hand-written lines changed: large; defect-finding falls off past 400. Say which split you would accept, review in more than one sitting."
  else echo; echo "**Size verdict:** $hand hand-written lines changed: reviewable in one pass."; fi
  echo
  echo "## Languages (load the matching pr-review-<language> skill for each)"
  echo
  echo "| class | files | lines changed |"
  echo "|---|---|---|"
  awk '{files[$1]++; lines[$1]+=$2} END {for (k in files) printf "| %s | %d | %d |\n", k, files[k], lines[k]}' "$out/classes.txt" | sort -t'|' -k4 -n -r
  if [ "${#gen_files[@]}" -gt 0 ]; then
    echo; echo "Generated or lockfile paths (skim, do not review line by line):"; printf -- '- %s\n' "${gen_files[@]}"
  fi
  echo
  echo "## Project rules and tooling to read first"
  echo
  [ "${#instructions[@]}" -gt 0 ] && { echo "Instructions:"; printf -- '- %s\n' "${instructions[@]}"; }
  if [ "${#rules[@]}" -gt 0 ]; then
    echo "Rule files (read those whose paths: match a changed file):"
    for r in "${rules[@]}"; do
      paths="$(awk '/^paths:/{f=1;next} f&&/^[^ -]/{exit} f{gsub(/^[ -]+/,"");print}' "$r" | tr '\n' ' ')"
      echo "- $r ($(wc -l < "$r" | tr -d ' ') lines) — paths: ${paths:-(none declared)}"
      headings="$(grep -E '^## ' "$r" | sed 's/^## //' | tr '\n' ';' | sed 's/;$//; s/;/; /g')"
      [ -n "$headings" ] && echo "  sections: $headings"
    done
    echo "Read the sections about the diff's subject and grep each matching file for the identifiers the diff touches; skim the rest."
  fi
  if [ -s "$out/review-files.txt" ]; then
    echo "Review checklist of the repository's own (apply its items; its '## pr-review' lines below say what to skip or load):"
    while IFS= read -r r; do
      echo "- $r ($(wc -l < "$r" | tr -d ' ') lines)"
      awk '/^## pr-review/{f=1;next} /^## /{f=0} f && /^- *(skip|load):/' "$r" | sed 's/^/  /'
    done < "$out/review-files.txt"
  fi
  [ "${#conventions[@]}" -gt 0 ] && { echo "Description and commit conventions (hold the title, body and commits to them):"; printf -- '- %s\n' "${conventions[@]}"; }
  [ "${#own_skills[@]}" -gt 0 ] && { echo "Skills of the repository's or the user's own that replace the bundled language skill of the same name:"; printf -- '- %s\n' "${own_skills[@]}"; }
  [ "${#tooling[@]}" -gt 0 ] && { echo "Tooling configuration (what it enforces is not reviewed by hand):"; printf -- '- %s\n' "${tooling[@]}"; }
  echo
  echo "## Files"
  echo
  echo '```'
  cat "$out/files.txt"
  echo '```'
  echo
  echo "## Commits"
  echo
  echo '```'
  cat "$out/commits.txt"
  echo '```'
  if [ -s "$out/ci.txt" ]; then echo; echo '```'; cat "$out/ci.txt"; echo '```'; fi
  echo
  echo "Read next: $out/description.md, then $out/diff.patch: the main files first, the tests early to learn the intent, then every remaining hand-written line."
  if [ "$mode" = pr ] && git show-ref --verify --quiet "refs/pr-review/$pr"; then
    echo
    echo "The PR head is at refs/pr-review/$pr (read a whole file with: git show refs/pr-review/$pr:path). When the review is done: git update-ref -d refs/pr-review/$pr"
  fi
} > "$out/summary.md"

cat "$out/summary.md"
