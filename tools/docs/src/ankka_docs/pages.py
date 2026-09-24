"""Reading the documentation tree: pages, their frontmatter, and the navigation order."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

# The repository root: tools/docs/src/ankka_docs/pages.py is four levels down.
ROOT = Path(__file__).resolve().parents[4]
DOCS = ROOT / "docs"
MKDOCS_YML = ROOT / "mkdocs.yml"

# Directories under docs/ that are not public pages. `design/` holds internal design treatments that
# feed feature specifications; they name features and specs by number and are written for the people
# building ankka, not the people using it.
EXCLUDED_DIRS = ("design/",)

KINDS = ("tutorial", "guide", "concept", "reference", "contributing")
LANGUAGES = ("scala", "python")
COMPONENTS = (
    "event-sourced-entity",
    "key-value-entity",
    "view",
    "consumer",
    "workflow",
    "timed-action",
    "agent",
    "http-endpoint",
)

FRONTMATTER = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)


@dataclass
class Page:
    """One Markdown page under docs/."""

    path: str  # relative to docs/, with forward slashes: "concepts/effects.md"
    meta: dict[str, Any]
    body: str  # everything after the frontmatter
    raw: str  # the whole file

    @property
    def title(self) -> str:
        return str(self.meta.get("title", ""))

    @property
    def description(self) -> str:
        return str(self.meta.get("description", ""))

    @property
    def kind(self) -> str:
        return str(self.meta.get("kind", ""))

    @property
    def url_path(self) -> str:
        """Where MkDocs serves the page, relative to the site root: `concepts/effects/`."""
        if self.path == "index.md":
            return ""
        if self.path.endswith("/index.md"):
            return self.path[: -len("index.md")]
        return self.path[: -len(".md")] + "/"

    @property
    def markdown_path(self) -> str:
        """Where the raw Markdown copy is served: the source path, so links between copies still work."""
        return self.path


@dataclass
class NavEntry:
    section: list[str]  # the headings above the page in the navigation
    title: str
    path: str


@dataclass
class Tree:
    pages: dict[str, Page] = field(default_factory=dict)
    nav: list[NavEntry] = field(default_factory=list)

    def ordered(self) -> list[Page]:
        """Pages in navigation order; anything not in the navigation last, alphabetically."""
        seen = [e.path for e in self.nav if e.path in self.pages]
        rest = sorted(p for p in self.pages if p not in seen)
        return [self.pages[p] for p in [*seen, *rest]]


def is_public(relative: str) -> bool:
    return not any(relative.startswith(d) for d in EXCLUDED_DIRS)


def parse(relative: str, raw: str) -> Page:
    match = FRONTMATTER.match(raw)
    if not match:
        return Page(relative, {}, raw, raw)
    meta = yaml.safe_load(match.group(1)) or {}
    return Page(relative, meta if isinstance(meta, dict) else {}, raw[match.end() :], raw)


def load() -> Tree:
    tree = Tree()
    for file in sorted(DOCS.rglob("*.md")):
        relative = file.relative_to(DOCS).as_posix()
        if not is_public(relative):
            continue
        tree.pages[relative] = parse(relative, file.read_text(encoding="utf-8"))
    tree.nav = read_nav()
    return tree


class _Loader(yaml.SafeLoader):
    """mkdocs.yml may carry `!!python/name:` tags for extensions; the nav needs none of them."""


def _ignore(loader: yaml.SafeLoader, suffix: str, node: yaml.Node) -> None:
    return None


_Loader.add_multi_constructor("tag:yaml.org,2002:python/", _ignore)
_Loader.add_multi_constructor("!", _ignore)


def mkdocs_config() -> dict[str, Any]:
    return yaml.load(MKDOCS_YML.read_text(encoding="utf-8"), Loader=_Loader)  # noqa: S506 - SafeLoader subclass


def read_nav() -> list[NavEntry]:
    entries: list[NavEntry] = []

    def walk(items: list[Any], section: list[str]) -> None:
        for item in items:
            if isinstance(item, str):
                entries.append(NavEntry(section, "", item))
            elif isinstance(item, dict):
                for title, value in item.items():
                    if isinstance(value, str):
                        entries.append(NavEntry(section, str(title), value))
                    elif isinstance(value, list):
                        walk(value, [*section, str(title)])

    walk(mkdocs_config().get("nav", []), [])
    return entries


def slugify(heading: str) -> str:
    """Python-Markdown's default `toc` slug, so an anchor checked here is the anchor the site has."""
    text = unicodedata.normalize("NFKD", heading).encode("ascii", "ignore").decode("ascii")
    text = re.sub(r"[^\w\s-]", "", text).strip().lower()
    return re.sub(r"[-\s]+", "-", text)


FENCE = re.compile(r"^(\s*)(`{3,}|~{3,})(.*)$")


def fenced_lines(text: str) -> set[int]:
    """Zero-based indices of every line that is part of a fenced code block, fences included."""
    inside: set[int] = set()
    fence: str | None = None
    for index, line in enumerate(text.split("\n")):
        match = FENCE.match(line)
        if fence is None:
            if match:
                fence = match.group(2)
                inside.add(index)
            continue
        inside.add(index)
        if match and match.group(2)[0] == fence[0] and len(match.group(2)) >= len(fence) and not match.group(3).strip():
            fence = None
    return inside


def strip_code(body: str) -> str:
    """The body with fenced code blocks and inline code removed — for checks that read prose only."""
    out: list[str] = []
    fence: str | None = None
    for line in body.splitlines():
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(2)
            continue
        if fence is not None:
            if match and match.group(2).startswith(fence[0]) and len(match.group(2)) >= len(fence) and not match.group(3).strip():
                fence = None
            continue
        out.append(re.sub(r"`[^`]*`", "", line))
    return "\n".join(out)


def headings(body: str) -> list[str]:
    """Every ATX heading in the prose, as text."""
    found = []
    for line in strip_code_keep_headings(body).splitlines():
        match = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", line)
        if match:
            found.append(match.group(2))
    return found


def strip_code_keep_headings(body: str) -> str:
    out: list[str] = []
    fence: str | None = None
    for line in body.splitlines():
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(2)
            continue
        if fence is not None:
            if match and match.group(2).startswith(fence[0]) and len(match.group(2)) >= len(fence) and not match.group(3).strip():
                fence = None
            continue
        out.append(line)
    return "\n".join(out)


def anchors(body: str) -> set[str]:
    """Anchors a heading produces, deduplicated the way `toc` does (`x`, `x_1`, `x_2`)."""
    result: set[str] = set()
    for heading in headings(body):
        # Inline code keeps its text in the anchor; links keep their label.
        text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading).replace("`", "")
        slug = slugify(text)
        candidate, n = slug, 0
        while candidate in result:
            n += 1
            candidate = f"{slug}_{n}"
        result.add(candidate)
    return result
