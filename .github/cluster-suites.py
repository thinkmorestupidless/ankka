#!/usr/bin/env python3
"""The k3s suites, as the `cluster` workflow's matrix: one runner per suite per architecture, and
for the longest suites one runner per shard of the suite.

Derived from the source rather than listed, so a new k3s suite cannot be left out of CI: a suite
that starts a cluster is switched off by `-Dankka.cluster.tests=off`, so every file that reads that
switch holds k3s suites, and every concrete top-level class in it is one. The sbt project that owns
a file is read from `build.sbt`'s `project.in(file(...))` declarations.

Every suite runs once per architecture the platform's images are published for, each on a runner
of that architecture, so the images a leg builds and deploys are native to it and nothing runs
under emulation: `amd64` on `ubuntu-24.04`, `arm64` on `ubuntu-24.04-arm`.

A run takes as long as its slowest job, and a few Gherkin suites spend five to ten minutes a
scenario rebuilding databases, so `SHARDS` runs those as several jobs: `"scenario"` is one job per
scenario (an outline with its rows), and a list of lists is one job per group of feature files. A
shard is `-Dankka.features.only=<selectors>`, which `GherkinSuite` reads; the suite's other tests
run in every shard. The table is checked against the tree: a suite it names must be a k3s suite,
and a grouping must name every feature file of the suite's directory exactly once, so a feature
added or moved fails this script rather than silently running nowhere.

Prints `{"include": [{"suite", "class", "project", "name", "slug", "only", "arch", "runner"}, ...]}`
(`name` is the job's, `slug` the artifact's, `only` the shard's selectors or empty), and fails when
it finds none: a matrix of nothing would be a green run that tested nothing. The first argument
narrows it to the suites it names, for running some on demand: a name, or a glob (`Broker*`); the
second narrows to one architecture (`amd64` or `arm64`); either that matches nothing fails the
same way.
"""

import fnmatch
import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent

# Measured on the nightly of 2026-10-22: a restore is a new CNPG cluster from a backup, five to ten
# minutes a scenario (AfterRestore 56 min whole, Restoring 48, Rehearsals 32); the object storage
# and installation model suites have one feature file worth half the suite (`move.feature` 16 min
# of 36, `isolation.feature` 13 of 32, `lifecycle.feature` 10 of 21).
SHARDS = {
    "AfterRestoreClusterFeatures": "scenario",
    "RestoringClusterFeatures": "scenario",
    "RehearsalsClusterFeatures": "scenario",
    "ObjectStorageGcsClusterFeatures": [
        ["move.feature"],
        ["provisioning.feature", "reachable.feature"],
        ["isolation.feature", "names.feature"],
        ["retention.feature", "keyless.feature"],
    ],
    "ObjectStorageClusterFeatures": [
        ["isolation.feature"],
        ["reachable.feature", "provisioning.feature"],
        [
            "kept.feature",
            "own-object-store.feature",
            "console.feature",
            "durability.feature",
            "hostings.feature",
        ],
    ],
    "InstallationModelsClusterFeatures": [
        ["lifecycle.feature"],
        [
            "placement.feature",
            "serving.feature",
            "naming.feature",
            "capabilities.feature",
            "access.feature",
            "observing.feature",
        ],
    ],
}

projects = {
    directory: name
    for name, directory in re.findall(
        r'lazy val (\w+)\s*=\s*project\s*\.in\(file\("([^"]+)"\)\)',
        (root / "build.sbt").read_text(),
    )
}


def scenarios(feature: pathlib.Path):
    """Each scenario of a feature file as (header line, [pickle lines]): a scenario's one pickle is
    at its own line, an outline's at its example rows' lines, which is where `GherkinSuite` names
    each test. One tagged `@ignore` is left out: the suite reports it ignored, so a job of it
    alone would run nothing."""
    found = []
    current = None
    tags = set()
    in_examples = header_seen = False
    docstring = None
    for number, raw in enumerate(feature.read_text().splitlines(), 1):
        line = raw.strip()
        if docstring is not None:
            if line.startswith(docstring):
                docstring = None
            continue
        if line.startswith('"""') or line.startswith("```"):
            docstring = line[:3]
            continue
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            tags |= set(line.split())
            continue
        if re.match(r"(Scenario Outline|Scenario Template|Scenario|Example):", line):
            current = {
                "line": number,
                "rows": [],
                "outline": "Outline" in line or "Template" in line,
            }
            if "@ignore" not in tags:
                found.append(current)
            tags = set()
            in_examples = False
            continue
        if re.match(r"(Examples|Scenarios):", line):
            in_examples, header_seen = True, False
            tags = set()
            continue
        if re.match(r"(Feature|Background|Rule):", line):
            current, in_examples, tags = None, False, set()
            continue
        if line.startswith("|") and current and in_examples:
            if header_seen:
                current["rows"].append(number)
            else:
                header_seen = True
    return [
        (s["line"], s["rows"] if s["outline"] else [s["line"]])
        for s in found
        if not s["outline"] or s["rows"]
    ]


def shards(suite, directory, source):
    """The shards of one suite from `SHARDS`, each (name suffix, slug suffix, selectors); one empty
    shard for a suite it does not name."""
    plan = SHARDS.get(suite)
    if plan is None:
        return [("", "", "")]
    declared = re.search(r'extends\s+\w+\(\s*"([^"]+)"', source)
    if not declared:
        sys.exit(f"{suite}: SHARDS names it, but it does not extend a Gherkin suite of one path")
    features = (root / directory / declared.group(1)).resolve()
    if plan == "scenario":
        if not features.is_file():
            sys.exit(f"{suite}: SHARDS asks for a job per scenario, but {features} is not one file")
        return [
            (
                f": {features.name}:{header}",
                f"-{features.stem}-{header}",
                ",".join(f"{features.name}:{line}" for line in lines),
            )
            for header, lines in scenarios(features)
        ]
    if not features.is_dir():
        sys.exit(f"{suite}: SHARDS groups feature files, but {features} is not a directory")
    present = sorted(p.name for p in features.glob("*.feature"))
    named = [name for group in plan for name in group]
    if sorted(named) != present:
        sys.exit(
            f"{suite}: SHARDS must name every feature file of {features} once; "
            f"it names {sorted(named)}, the directory holds {present}"
        )
    return [
        (
            ": " + ", ".join(pathlib.Path(name).stem for name in group),
            "-" + "-".join(pathlib.Path(name).stem for name in group),
            ",".join(f"{features.name}/{name}" for name in group),
        )
        for group in plan
    ]


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
        for suffix, slug, only in shards(name, directory, source):
            suites.append(
                {
                    "suite": name,
                    "class": f"{package}.{name}",
                    "project": projects[directory],
                    "name": name + suffix,
                    "slug": name + slug,
                    "only": only,
                }
            )

if not suites:
    sys.exit("no k3s suites found: nothing reads ankka.cluster.tests")

unknown = set(SHARDS) - {s["suite"] for s in suites}
if unknown:
    sys.exit(f"SHARDS names suites that are not k3s suites: {sorted(unknown)}")

only = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
if only:
    suites = [s for s in suites if fnmatch.fnmatchcase(s["suite"], only)]
    if not suites:
        sys.exit(f"no k3s suite is named {only}")

ARCHITECTURES = {"amd64": "ubuntu-24.04", "arm64": "ubuntu-24.04-arm"}

arch = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None
if arch and arch not in ARCHITECTURES:
    sys.exit(f"no runner for the architecture {arch}; one of {', '.join(ARCHITECTURES)}")
legs = [arch] if arch else list(ARCHITECTURES)

print(
    json.dumps(
        {
            "include": [
                {**suite, "arch": a, "runner": ARCHITECTURES[a]}
                for suite in suites
                for a in legs
            ]
        }
    )
)
