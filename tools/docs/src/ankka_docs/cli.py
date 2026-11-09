"""`uv run docs <command>` — the one entry point for everything the documentation build does.

    check   every rule, every include, every generated block, the rendered skills; exits 1 on a problem
    sync    rewrite includes, generated blocks and the skills from their sources
    build   check, then build the site with MkDocs (strict) and write the machine renderings beside it;
            `build --release <version>` also writes latest.json, naming that release as the newest
    serve   the site with live reload, for writing

The project is the nearest `mkdocs.yml` at or above the working directory; its `extra.docs` block says
what is specific to that repository. The site lands in its `site_dir`.
"""

from __future__ import annotations

import subprocess
import sys

from . import check as checks
from . import generate, release, render, skill, snippets
from .pages import ConfigError, Project, load, project


def _report(problems: list[snippets.Problem]) -> int:
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"\n{len(problems)} problem(s)", file=sys.stderr)
        return 1
    return 0


def run_check(current: Project) -> int:
    tree = load(current)
    problems = checks.run(tree) + skill.check(tree)
    status = _report(problems)
    if status == 0:
        print(f"docs: {len(tree.pages)} pages, no problems")
    return status


def run_sync(current: Project) -> int:
    tree = load(current)
    changed_snippets, problems = snippets.sync(tree)
    changed_generated, more = generate.sync(tree)
    problems += more
    # Includes and generated blocks change page bodies, and the skills are rendered from bodies.
    skill.sync(load(current))
    for page in sorted(set(changed_snippets + changed_generated)):
        print(f"updated {current.docs_label}{page}")
    return _report(problems)


def _mkdocs(current: Project, *args: str) -> int:
    return subprocess.call([sys.executable, "-m", "mkdocs", *args, "-f", str(current.root / "mkdocs.yml")], cwd=current.root)


def run_build(current: Project, version: str | None = None) -> int:
    if run_check(current) != 0:
        return 1
    if _mkdocs(current, "build", "--strict") != 0:
        return 1
    render.write(load(current), current.site)
    print(f"site, llms.txt, llms-full.txt and docs-index.json in {current.site.relative_to(current.root)}")
    if version is not None:
        release.write_latest(current.site, version)
        print(f"latest.json names {version}")
    return 0


def _build_arguments(arguments: list[str]) -> str | None:
    """`--release <version>`, the only option `build` takes; anything else is a usage error."""
    if not arguments:
        return None
    if len(arguments) == 2 and arguments[0] == "--release" and release.is_version(arguments[1]):
        return arguments[1]
    raise _Usage("build takes `--release <major.minor.patch>`, without a leading v, or nothing")


class _Usage(Exception):
    pass


def run_serve(current: Project) -> int:
    return _mkdocs(current, "serve")


def main() -> None:
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    handlers = {"check": run_check, "sync": run_sync, "build": run_build, "serve": run_serve}
    handler = handlers.get(command)
    if handler is None:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    try:
        version = _build_arguments(sys.argv[2:]) if command == "build" else None
    except _Usage as usage:
        print(f"docs: {usage}", file=sys.stderr)
        sys.exit(2)
    try:
        current = project()
        snippets.Problem.prefix = current.docs_label
        sys.exit(run_build(current, version) if command == "build" else handler(current))
    except ConfigError as error:
        print(f"docs: {error}", file=sys.stderr)
        sys.exit(2)
