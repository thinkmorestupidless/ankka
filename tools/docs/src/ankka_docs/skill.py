"""The documentation as an Agent Skill.

A skill is a directory an agent loads on demand: a `SKILL.md` whose frontmatter says when to use it, and
reference files it opens only when a task needs them. This renders one from the documentation:

- `SKILL.md` is the curated entry in `tools/docs/skill/SKILL.md` — the few rules an agent must hold to
  write a correct ankka service — followed by a generated index of every reference file with its
  one-sentence description, so the agent can choose what to read.
- `references/` is every public page, as the same Markdown `llms.txt` links to, keeping the docs tree's
  paths so links between pages still resolve.

It is written to two places, both committed and both checked:

- `plugins/ankka/skills/ankka/`, the Claude Code plugin this repository publishes as a marketplace;
- `ankka.g8/src/main/g8/.claude/skills/ankka/`, so every service created from the template starts with
  the documentation of the ankka version it was created against. Giter8 reads `$` as template syntax, so
  that copy escapes it.
"""

from __future__ import annotations

import filecmp
import shutil
import tempfile
from pathlib import Path

from .pages import ROOT, Tree
from .render import page_markdown, sections, site_url
from .snippets import Problem

CURATED = ROOT / "tools/docs/skill/SKILL.md"
PLUGIN_TARGET = ROOT / "plugins/ankka/skills/ankka"
TEMPLATE_TARGET = ROOT / "ankka.g8/src/main/g8/.claude/skills/ankka"

# Pages about writing these pages are not about using ankka.
EXCLUDED_KINDS = {"contributing"}


def render(tree: Tree, target: Path, escape_dollars: bool) -> None:
    base = site_url()
    included = [p for p in tree.ordered() if p.kind not in EXCLUDED_KINDS]
    listing = ["", "## Reference files", "", "Open the one a task needs; each is one topic and stands alone.", ""]
    for name, pages in sections(tree):
        chosen = [p for p in pages if p.kind not in EXCLUDED_KINDS]
        if not chosen:
            continue
        listing += [f"### {name}", ""]
        for page in chosen:
            listing.append(f"- `references/{page.path}` — {page.description}")
        listing.append("")
    text = CURATED.read_text(encoding="utf-8").rstrip("\n") + "\n" + "\n".join(listing)
    files = {"SKILL.md": text}
    for page in included:
        files[f"references/{page.path}"] = page_markdown(page, base)
    if target.exists():
        shutil.rmtree(target)
    for relative, content in files.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content.replace("$", "\\$") if escape_dollars else content, encoding="utf-8")


TARGETS = [(PLUGIN_TARGET, False), (TEMPLATE_TARGET, True)]


def sync(tree: Tree) -> None:
    for target, escape in TARGETS:
        render(tree, target, escape)


def _same(left: Path, right: Path) -> bool:
    comparison = filecmp.dircmp(left, right)
    if comparison.left_only or comparison.right_only or comparison.funny_files:
        return False
    _, mismatch, errors = filecmp.cmpfiles(left, right, comparison.common_files, shallow=False)
    if mismatch or errors:
        return False
    return all(_same(left / d, right / d) for d in comparison.common_dirs)


def check(tree: Tree) -> list[Problem]:
    problems = []
    for target, escape in TARGETS:
        with tempfile.TemporaryDirectory() as scratch:
            expected = Path(scratch) / "skill"
            render(tree, expected, escape)
            if not target.exists() or not _same(expected, target):
                problems.append(Problem(f"../{target.relative_to(ROOT)}", 1, "the rendered skill is stale; run `docs sync`"))
    return problems
