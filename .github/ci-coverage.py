"""Hold ci.yml's path filters to the tree, in both directions.

The `changes` job decides which jobs a pull request runs from the files it touched, and a job whose
filter matches nothing is skipped, which GitHub counts as a pass. So a file no filter claims is a file
whose change is never tested, and a pattern that matches no file is a filter that can never fire —
usually one naming a path that has since moved. Both pass silently. This refuses both:

- every tracked file matches at least one pattern in the filters block, including the `unchecked`
  filter, which names what deliberately needs no job and why;
- every pattern matches at least one tracked file.

Globs are read as dorny/paths-filter reads them (picomatch, dotfiles included): `**` spans
directories, `*` stays within one. Run from the repository root; it needs Python 3 and git only.
"""

import re
import subprocess
import sys

WORKFLOW = ".github/workflows/ci.yml"


def patterns(workflow: str) -> list[str]:
    lines = workflow.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "filters: |")
    indent = len(lines[start]) - len(lines[start].lstrip())
    found = []
    for line in lines[start + 1 :]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        match = re.match(r"\s*- '([^']+)'\s*(?:#.*)?$", line)
        if match:
            found.append(match.group(1))
    return found


def regex(glob: str) -> re.Pattern[str]:
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif glob.startswith("**", i):
            out, i = out + ".*", i + 2
        elif glob[i] == "*":
            out, i = out + "[^/]*", i + 1
        elif glob[i] == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(glob[i]), i + 1
    return re.compile(out + r"\Z")


def main() -> int:
    with open(WORKFLOW) as f:
        globs = patterns(f.read())
    if not globs:
        print(f"{WORKFLOW}: found no path filters; has the `filters: |` block moved?")
        return 1
    compiled = [(g, regex(g)) for g in globs]
    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()

    unclaimed = [f for f in files if not any(r.match(f) for _, r in compiled)]
    unused = sorted({g for g, r in compiled if not any(r.match(f) for f in files)})

    for f in unclaimed:
        print(f"no filter claims {f}: add it to the job that reads it, or to `unchecked` with the reason")
    for g in unused:
        print(f"'{g}' matches no tracked file: a filter naming it can never fire")
    if unclaimed or unused:
        return 1
    print(f"{len(files)} tracked files, each claimed by a filter; {len(set(globs))} patterns, each matching a file")
    return 0


if __name__ == "__main__":
    sys.exit(main())
