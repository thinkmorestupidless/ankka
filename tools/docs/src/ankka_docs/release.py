"""The release a documentation tree is built for, and the places its pages name one.

`latest.json`, written beside the site by `docs build --release <version>`, says which release is the
newest and the day it was released; whatever tells a person or a model that a newer release exists
reads it.

A page names a release of the repository in its text (`go get .../widget-go@v0.14.0`) and keeps a real
one there, so the source reads correctly with no renderer. The project's patterns file (named by
`extra.docs.release.patterns`) lists each form in which a page names one:

    release widget-go@v{version}    # a place a page names a release; `{version}` is it, without its v
    other spec-kit.git@v            # a version that follows this is not one of ours to move

A build rewrites every `release` form to the release it builds, in the site and in every machine
rendering, and never in the sources; `docs sync` rewrites the sources to the newest release tag; and
`docs check` refuses a version equal to one of the repository's release tags anywhere no form covers,
so a release written by hand cannot be left behind by the next one.
"""

from __future__ import annotations

import datetime
import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from .pages import Project, Tree, parse
from .snippets import Problem

VERSION = re.compile(r"\d+\.\d+\.\d+")

# Three numbers that are not part of a longer run of them (`127.0.0.1`), and not the start of a
# pre-release or build (`0.1.0-SNAPSHOT`, `0.1.0+3-abc`), which are never releases.
_NUMBERS = r"(\d+\.\d+\.\d+)(?!\.?\d)(?![-+]\w)"
OCCURRENCE = re.compile(r"(?<![\w.])v?" + _NUMBERS)

PLACEHOLDER = "{version}"

# The environment variable through which `docs build` tells the MkDocs hook which release it builds.
ENVIRONMENT = "ANKKA_DOCS_RELEASE"


def is_version(text: str) -> bool:
    """Three numbers, without a `v`: the form a release is written in everywhere this module writes one."""
    return VERSION.fullmatch(text) is not None


def write_latest(site: Path, version: str, today: datetime.date | None = None) -> Path:
    """`latest.json` in the site directory: the release, and the UTC date it was built on."""
    released = today or datetime.datetime.now(datetime.UTC).date()
    target = site / "latest.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"version": version, "released": released.isoformat()}) + "\n", encoding="utf-8")
    return target


# ── the patterns file ────────────────────────────────────────────────────────


@dataclass
class Patterns:
    release: list[re.Pattern[str]] = field(default_factory=list)
    other: list[str] = field(default_factory=list)


class PatternsError(Exception):
    """A line of the patterns file the tool cannot use."""


def read_patterns(path: Path) -> Patterns:
    patterns = Patterns()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        line = raw
        kind, _, literal = line.partition(" ")
        if kind == "release":
            if literal.count(PLACEHOLDER) != 1:
                raise PatternsError(f"{path.name}:{number}: a release line holds `{PLACEHOLDER}` once")
            before, after = literal.split(PLACEHOLDER)
            patterns.release.append(re.compile(re.escape(before) + _NUMBERS + re.escape(after)))
        elif kind == "other":
            if not literal.strip():
                raise PatternsError(f"{path.name}:{number}: an other line names the text the version follows")
            # Compared with trailing space stripped on both sides, so an editor that trims the line
            # (`other from version `) changes nothing.
            patterns.other.append(literal.rstrip())
        else:
            raise PatternsError(f"{path.name}:{number}: a line is `release <literal>` or `other <prefix>`, not '{kind}'")
    return patterns


def patterns_of(current: Project) -> Patterns | None:
    settings = current.settings.release
    if settings is None:
        return None
    return read_patterns(settings.patterns)


# ── the rewrite ──────────────────────────────────────────────────────────────


def rewrite(text: str, version: str, patterns: Patterns) -> str:
    """The text with every `release` form naming `version`."""
    for pattern in patterns.release:
        text = pattern.sub(lambda m: m.group(0)[: m.start(1) - m.start(0)] + version + m.group(0)[m.end(1) - m.start(0) :], text)
    return text


def rewritten(tree: Tree, version: str) -> Tree:
    """The tree as a build for `version` reads it: every page's release forms naming that release."""
    patterns = patterns_of(tree.project)
    if patterns is None:
        return tree
    result = Tree(tree.project, nav=tree.nav)
    for path, page in tree.pages.items():
        result.pages[path] = parse(path, rewrite(page.raw, version, patterns))
    return result


def sync(tree: Tree, version: str) -> list[str]:
    """Rewrites the page sources to `version`; the pages it changed."""
    patterns = patterns_of(tree.project)
    if patterns is None:
        return []
    changed = []
    for path, page in tree.pages.items():
        text = rewrite(page.raw, version, patterns)
        if text != page.raw:
            tree.write(path, text)
            changed.append(path)
    return changed


# ── the tags ─────────────────────────────────────────────────────────────────


def _git(root: Path, *args: str) -> str | None:
    try:
        done = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=False)
    except OSError:
        return None
    return done.stdout if done.returncode == 0 else None


def tags(current: Project) -> set[str]:
    """The releases the repository's tags name, without their `v`."""
    settings = current.settings.release
    if settings is None:
        return set()
    listed = _git(current.root, "tag", "--list", settings.tags) or ""
    return {name.lstrip("v") for name in listed.split() if is_version(name.lstrip("v"))}


def newest_tag(current: Project) -> str | None:
    """The newest release tag reachable from the checkout, without its `v`."""
    settings = current.settings.release
    if settings is None:
        return None
    # The highest version among the tags the checkout contains, not `git describe`'s choice, which
    # among several tags on one commit is not the highest.
    listed = _git(current.root, "tag", "--list", settings.tags, "--merged", "HEAD") or ""
    versions = [name.lstrip("v") for name in listed.split() if is_version(name.lstrip("v"))]
    return max(versions, key=lambda v: tuple(int(n) for n in v.split(".")), default=None)


NO_TAGS = (
    "no git tag matches '{tags}', so a release written by hand cannot be told from any other number; "
    "fetch the tags (`git fetch --tags`, or `fetch-depth: 0` on actions/checkout)"
)


# ── the check ────────────────────────────────────────────────────────────────


def covered(line: str, start: int, patterns: Patterns) -> bool:
    """Whether the version whose numbers begin at `start` is in a form the patterns name."""
    for pattern in patterns.release:
        for match in pattern.finditer(line):
            if match.start(1) == start:
                return True
    before = line[:start].rstrip()
    return any(before.endswith(prefix) for prefix in patterns.other)


def check(tree: Tree) -> list[Problem]:
    current = tree.project
    settings = current.settings.release
    if settings is None:
        return []
    label = _label(current, settings.patterns)
    name = _name(current, settings.patterns)
    try:
        patterns = read_patterns(settings.patterns)
    except (OSError, PatternsError) as error:
        return [Problem(label, 1, f"the release patterns cannot be read: {error}")]
    releases = tags(current)
    if not releases or newest_tag(current) is None:
        return [Problem(label, 1, NO_TAGS.format(tags=settings.tags))]
    problems: list[Problem] = []
    for page in tree.pages.values():
        for number, line in enumerate(page.raw.split("\n"), start=1):
            for match in OCCURRENCE.finditer(line):
                version = match.group(1)
                if version in releases and not covered(line, match.start(1), patterns):
                    problems.append(
                        Problem(
                            page.path,
                            number,
                            f"names release {version} by hand; use a form {name} lists, or add one there",
                        )
                    )
    return problems


def _label(current: Project, path: Path) -> str:
    """Where a problem names the patterns file: relative to docs/ (the report adds the prefix)."""
    try:
        return path.relative_to(current.docs).as_posix()
    except ValueError:
        return current.label(path)


def _name(current: Project, path: Path) -> str:
    """The patterns file as a person finds it, from the repository root."""
    try:
        return path.relative_to(current.root).as_posix()
    except ValueError:
        return str(path)
