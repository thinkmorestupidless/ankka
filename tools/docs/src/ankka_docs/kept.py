"""The parts of a set-up the documentation tool keeps, and the check that holds a repository to them.

Two files of every set-up are the tool's, not the repository's: the build settings
(`tools/docs/pyproject.toml`, naming the release of the tool that builds the documentation) and the
documentation job (`.github/workflows/docs.yml`, a caller of the reusable workflow in ankka at the same
release). They are the same in every repository but for its name. `docs init` writes them every time it
runs; `docs check` renders them again for the release the build settings name and fails on any
difference, so a hand edit, or a dependency update that moved one without the other, is a failing build
that says how to put it back.

A repository that keeps a part of its own (ankka, which is the tool's repository and publishes its site
from its release workflow) says so in `extra.docs.kept`, and the check holds that part to nothing.
"""

from __future__ import annotations

import re
from importlib.resources import files
from pathlib import Path

from .pages import Project
from .snippets import Problem

SETTINGS = "tools/docs/pyproject.toml"
JOB = ".github/workflows/docs.yml"

# The first line of every kept part: how the tool knows a file at a kept path is its own.
MARK = "# Kept by ankka's documentation tool"

TEMPLATES = {SETTINGS: "pyproject.toml.tmpl", JOB: "docs.yml.tmpl"}

DEPENDENCY = re.compile(
    r'"ankka-docs @ git\+https://github\.com/thinkmorestupidless/ankka@(?P<ref>[^#"]*)#subdirectory=tools/docs"'
)
CALLER = re.compile(r"uses: thinkmorestupidless/ankka/\.github/workflows/documentation\.yml@v(?P<release>\d+\.\d+\.\d+)\s*$", re.MULTILINE)
NAME = re.compile(r'^name = "(?P<name>[a-z0-9-]+)-docs"$', re.MULTILINE)
VERSION = re.compile(r"^v(?P<release>\d+\.\d+\.\d+)$")


def template(name: str) -> str:
    return (files("ankka_docs") / "templates" / name).read_text(encoding="utf-8")


def fill(text: str, values: dict[str, str]) -> str:
    """Replace each `@@key@@`, in one pass, so a value that itself holds `@@` is never read as a marker.
    The markers are not `$` or braces, so a workflow's `${{ }}` or a page's `{version}` is never read as
    one. A marker the values do not name is the template's mistake, and a `KeyError`."""
    return re.sub(r"@@(\w+)@@", lambda match: values[match.group(1)], text)


def render(name: str, release: str) -> dict[str, str]:
    """Both kept parts, as release `release` of the tool writes them for the repository `name`."""
    return {path: fill(template(source), {"name": name, "release": release}) for path, source in TEMPLATES.items()}


def release_of(settings_text: str) -> str | None:
    """The release the build settings name, or None when the dependency names a branch, a commit or nothing."""
    match = DEPENDENCY.search(settings_text)
    if not match:
        return None
    version = VERSION.match(match.group("ref"))
    return version.group("release") if version else None


def check(project: Project) -> list[Problem]:
    settings = project.settings
    if not settings.kept_settings and not settings.kept_job:
        return []
    root = project.root
    problems: list[Problem] = []
    settings_file, job_file = root / SETTINGS, root / JOB

    release: str | None = None
    name: str | None = None
    if settings.kept_settings:
        if not settings_file.is_file():
            problems.append(_problem(project, SETTINGS, "the build settings are missing; run `docs init`, or say `kept: {settings: false}` under extra.docs"))
        else:
            text = settings_file.read_text(encoding="utf-8")
            release = release_of(text)
            found = NAME.search(text)
            name = found.group("name") if found else None
            if release is None:
                problems.append(_problem(project, SETTINGS, "the build settings name no release of the documentation tool; run `docs init --release <major.minor.patch>`"))
            elif name is None or text != render(name, release)[SETTINGS]:
                problems.append(_problem(project, SETTINGS, f"the build settings differ from what release {release} of the documentation tool writes; run `docs init`"))
    if settings.kept_job:
        if not job_file.is_file():
            problems.append(_problem(project, JOB, "the documentation job is missing; run `docs init`, or say `kept: {job: false}` under extra.docs"))
        else:
            text = job_file.read_text(encoding="utf-8")
            if not settings.kept_settings:
                caller = CALLER.search(text)
                release = caller.group("release") if caller else None
            if release is not None and text != render(name or "", release)[JOB]:
                problems.append(_problem(project, JOB, f"the documentation job differs from what release {release} of the documentation tool writes; run `docs init`"))
            elif release is None and not settings.kept_settings:
                problems.append(_problem(project, JOB, "the documentation job names no release of the documentation tool; run `docs init`"))
    return problems


def _problem(project: Project, path: str, message: str) -> Problem:
    return Problem(project.label(project.root / path), 1, message)


def is_ours(path: Path) -> bool:
    """A file at a kept path that the tool wrote: its first line is the mark."""
    with path.open(encoding="utf-8") as file:
        return file.readline().startswith(MARK)
