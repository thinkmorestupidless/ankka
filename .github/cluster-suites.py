#!/usr/bin/env python3
"""The k3s suites, as the `cluster` workflow's matrix: one runner per suite.

Derived from the source rather than listed, so a new k3s suite cannot be left out of CI: a suite
that starts a cluster is switched off by `-Dankka.cluster.tests=off`, so every file that reads that
switch holds k3s suites, and every concrete top-level class in it is one. The sbt project that owns
a file is read from `build.sbt`'s `project.in(file(...))` declarations.

Prints `{"include": [{"suite", "class", "project"}, ...]}`, and fails when it finds none: a matrix
of nothing would be a green run that tested nothing. An argument narrows it to the suites it names, for
running some on demand: a name, or a glob (`Broker*`); one that matches no suite fails the same way.
"""

import fnmatch
import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent

projects = {
    directory: name
    for name, directory in re.findall(
        r'lazy val (\w+)\s*=\s*project\s*\.in\(file\("([^"]+)"\)\)',
        (root / "build.sbt").read_text(),
    )
}

suites = []
for path in sorted(root.glob("**/src/test/scala/**/*.scala")):
    relative = path.relative_to(root).as_posix()
    if relative.startswith(".claude/") or "/target/" in relative:
        continue
    source = path.read_text()
    if "ankka.cluster.tests" not in source:
        continue
    directory = relative.split("/src/test/")[0]
    if directory not in projects:
        sys.exit(f"{relative}: no sbt project is declared in {directory}")
    package = re.search(r"^package ([\w.]+)", source, re.MULTILINE).group(1)
    for name in re.findall(r"^class (\w+)", source, re.MULTILINE):
        suites.append(
            {"suite": name, "class": f"{package}.{name}", "project": projects[directory]}
        )

if not suites:
    sys.exit("no k3s suites found: nothing reads ankka.cluster.tests")

only = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
if only:
    suites = [s for s in suites if fnmatch.fnmatchcase(s["suite"], only)]
    if not suites:
        sys.exit(f"no k3s suite is named {only}")

print(json.dumps({"include": suites}))
