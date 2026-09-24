"""The documentation as Agent Skills.

A skill is a directory an agent loads on demand: a `SKILL.md` whose frontmatter says when to use it, and
reference files it opens only when a task needs them. The documentation renders into several, one per
kind of task — designing a service, writing entities, writing agents, deploying — so an agent loads the
rules for the component it is writing rather than an index of everything.

Each skill is curated in `tools/docs/skill/<name>/SKILL.md`: the frontmatter names the skill and says
when to load it, a `pages:` list names the documentation pages it carries, and the body holds the rules
an agent must hold for that task. Rendering strips `pages:`, appends an index of the named pages with
their one-sentence descriptions, and copies the pages themselves under `references/`, keeping the docs
tree's paths so links between pages still resolve. A page may belong to several skills; every public page
must belong to at least one, so a new page is a failing build until someone says which task it serves.

The skills are written to two places, both committed and both checked:

- `marketplace/plugins/ankka/skills/`, the Claude Code plugin this repository publishes through the
  `ankka-marketplace` repository (the release workflow pushes `marketplace/` there on every tag);
- `ankka.g8/src/main/g8/.claude/skills/`, so every service created from the template starts with the
  documentation of the ankka version it was created against. Giter8 reads `$` as template syntax, so
  that copy escapes it.
"""

from __future__ import annotations

import filecmp
import re
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path

import yaml

from .pages import FRONTMATTER, ROOT, Tree
from .render import page_markdown, sections, site_url
from .snippets import Problem

CURATED = ROOT / "tools/docs/skill"
PLUGIN_TARGET = ROOT / "marketplace/plugins/ankka/skills"
TEMPLATE_TARGET = ROOT / "ankka.g8/src/main/g8/.claude/skills"

# Pages about writing these pages are not about using ankka.
EXCLUDED_KINDS = {"contributing"}

SKILL_NAME = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


@dataclass
class Skill:
    name: str
    source: Path  # the curated SKILL.md
    meta: dict
    body: str
    pages: list[str]

    @property
    def label(self) -> str:
        return f"../{self.source.relative_to(ROOT)}"


def curated() -> list[Skill]:
    skills = []
    for source in sorted(CURATED.glob("*/SKILL.md")):
        raw = source.read_text(encoding="utf-8")
        match = FRONTMATTER.match(raw)
        meta = yaml.safe_load(match.group(1)) if match else {}
        meta = meta if isinstance(meta, dict) else {}
        pages = meta.get("pages") or []
        skills.append(Skill(source.parent.name, source, meta, raw[match.end():] if match else raw, [str(p) for p in pages]))
    return skills


def _frontmatter(skill: Skill) -> str:
    kept = {k: v for k, v in skill.meta.items() if k != "pages"}
    return "---\n" + yaml.safe_dump(kept, sort_keys=False, allow_unicode=True, width=10_000).rstrip("\n") + "\n---\n"


def _skill_markdown(skill: Skill, tree: Tree) -> str:
    chosen = set(skill.pages)
    listing = ["", "## Reference files", "", "Open the one a task needs; each is one topic and stands alone.", ""]
    for name, pages in sections(tree):
        in_section = [p for p in pages if p.path in chosen]
        if not in_section:
            continue
        listing += [f"### {name}", ""]
        for page in in_section:
            listing.append(f"- `references/{page.path}` — {page.description}")
        listing.append("")
    return _frontmatter(skill) + skill.body.rstrip("\n") + "\n" + "\n".join(listing)


def render(tree: Tree, target: Path, escape_dollars: bool) -> None:
    base = site_url()
    if target.exists():
        shutil.rmtree(target)
    for skill in curated():
        files = {"SKILL.md": _skill_markdown(skill, tree)}
        for path in skill.pages:
            page = tree.pages.get(path)
            if page is not None:
                files[f"references/{path}"] = page_markdown(page, base)
        for relative, content in files.items():
            file = target / skill.name / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content.replace("$", "\\$") if escape_dollars else content, encoding="utf-8")


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
    """The curated skills are well-formed, cover every public page, and the rendered copies are current."""
    problems: list[Problem] = []
    skills = curated()
    covered: set[str] = set()
    for skill in skills:
        if not SKILL_NAME.match(skill.name):
            problems.append(Problem(skill.label, 1, "a skill's directory name is lower-case words joined by hyphens"))
        if skill.meta.get("name") != skill.name:
            problems.append(Problem(skill.label, 1, f"the skill's `name` must be its directory name, `{skill.name}`"))
        description = str(skill.meta.get("description", ""))
        if not description:
            problems.append(Problem(skill.label, 1, "a skill needs a `description` saying when an agent should load it"))
        elif len(description) > 1024:
            problems.append(Problem(skill.label, 1, "a skill's `description` is at most 1024 characters"))
        if not skill.pages:
            problems.append(Problem(skill.label, 1, "a skill needs a `pages:` list naming the documentation pages it carries"))
        for path in skill.pages:
            page = tree.pages.get(path)
            if page is None:
                problems.append(Problem(skill.label, 1, f"`pages:` names `{path}`, which is not a public page"))
            elif page.kind in EXCLUDED_KINDS:
                problems.append(Problem(skill.label, 1, f"`pages:` names `{path}`, a {page.kind} page, which is not about using ankka"))
            else:
                covered.add(path)
        if len(set(skill.pages)) != len(skill.pages):
            problems.append(Problem(skill.label, 1, "`pages:` lists a page twice"))
    for page in tree.ordered():
        if page.kind not in EXCLUDED_KINDS and page.path not in covered:
            problems.append(Problem(page.path, 1, "no skill carries this page; add it to a `pages:` list under tools/docs/skill/"))
    if problems:
        return problems
    for target, escape in TARGETS:
        with tempfile.TemporaryDirectory() as scratch:
            expected = Path(scratch) / "skills"
            render(tree, expected, escape)
            if not target.exists() or not _same(expected, target):
                problems.append(Problem(f"../{target.relative_to(ROOT)}", 1, "the rendered skills are stale; run `docs sync`"))
    return problems
