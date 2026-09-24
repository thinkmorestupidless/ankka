"""`uv run docs <command>` — the one entry point for everything the documentation build does.

    check   every rule, every include, every generated block, the rendered skill; exits 1 on a problem
    sync    rewrite includes, generated blocks and the skill from their sources
    build   check, then build the site with MkDocs (strict) and write the machine renderings beside it
    serve   the site with live reload, for writing
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from . import check as checks
from . import generate, render, skill, snippets
from .pages import ROOT, load

SITE = ROOT / "target/docs-site"


def _report(problems: list[snippets.Problem]) -> int:
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"\n{len(problems)} problem(s)", file=sys.stderr)
        return 1
    return 0


def run_check() -> int:
    tree = load()
    problems = checks.run(tree) + skill.check(tree)
    status = _report(problems)
    if status == 0:
        print(f"docs: {len(tree.pages)} pages, no problems")
    return status


def run_sync() -> int:
    tree = load()
    pages = sorted(tree.pages)
    changed_snippets, problems = snippets.sync(pages)
    changed_generated, more = generate.sync(pages)
    problems += more
    # Includes and generated blocks change page bodies, and the skill is rendered from bodies.
    skill.sync(load())
    for page in sorted(set(changed_snippets + changed_generated)):
        print(f"updated docs/{page}")
    return _report(problems)


def _mkdocs(*args: str) -> int:
    return subprocess.call([sys.executable, "-m", "mkdocs", *args, "-f", str(ROOT / "mkdocs.yml")], cwd=ROOT)


def run_build() -> int:
    if run_check() != 0:
        return 1
    if _mkdocs("build", "--strict", "--site-dir", str(SITE)) != 0:
        return 1
    render.write(load(), SITE)
    print(f"site, llms.txt, llms-full.txt and docs-index.json in {Path(SITE).relative_to(ROOT)}")
    return 0


def main() -> None:
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    handlers = {"check": run_check, "sync": run_sync, "build": run_build, "serve": lambda: _mkdocs("serve")}
    handler = handlers.get(command)
    if handler is None:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(handler())
