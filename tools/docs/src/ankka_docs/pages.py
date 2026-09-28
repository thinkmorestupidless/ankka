"""Reading the documentation tree: the project, its settings, pages, their frontmatter, and the navigation order."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

KINDS = ("tutorial", "guide", "concept", "reference", "contributing")

# The frontmatter every page carries. A project adds its own keys as facets in `extra.docs.facets`.
FIXED_KEYS = ("title", "description", "kind", "related")

FRONTMATTER = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)


class ConfigError(Exception):
    """`mkdocs.yml` is missing, or its `extra.docs` block says something the tool cannot use."""


# ── the project ──────────────────────────────────────────────────────────────


@dataclass
class SkillTarget:
    """A directory the rendered skills are written to, committed, and checked for staleness."""

    path: Path  # absolute
    escape: str | None = None  # `giter8`: every `$` is escaped for a template that reads it as syntax


@dataclass
class Generator:
    """One `generated:start <name>` block's owner: a kind this module knows, or `external` for a block
    another tool (in ankka, a Scala test suite) rewrites and this tool leaves alone."""

    name: str
    kind: str
    options: dict[str, Any] = field(default_factory=dict)


@dataclass
class Settings:
    """Everything in `extra.docs`. Every path is relative to the repository root.

    ```yaml
    extra:
      docs:
        facets:                       # frontmatter keys beyond title/description/kind/related, with their values
          languages: [scala, python]
        skills:
          source: tools/docs/skill    # the curated SKILL.md files (the default)
          targets:
            - path: marketplace/plugins/ankka/skills
            - path: ankka.g8/src/main/g8/.claude/skills
              escape: giter8
        generated:                    # owners of `generated:start <name>` blocks
          configuration:
            kind: hocon
            files: [{path: modules/runtime/src/main/resources/reference.conf, scope: every service}]
            prefix: ankka.
            described-on: reference/configuration.md
          protocol:
            kind: protobuf
            directory: protocol/src/main/protobuf/ankka/protocol/v1
          cli:
            kind: external
        site-links:                   # files outside docs/ that link to the site
          files: [README.md]
          retired-hosts: [thinkmorestupidless.github.io]
    ```
    """

    facets: dict[str, tuple[str, ...]] = field(default_factory=dict)
    skills_source: Path | None = None
    skill_targets: list[SkillTarget] = field(default_factory=list)
    generators: dict[str, Generator] = field(default_factory=dict)
    site_link_files: list[str] = field(default_factory=list)
    retired_hosts: list[str] = field(default_factory=list)

    @property
    def escapes_dollars(self) -> bool:
        return any(target.escape == "giter8" for target in self.skill_targets)


@dataclass
class Project:
    root: Path
    config: dict[str, Any]  # mkdocs.yml, parsed
    settings: Settings

    @property
    def docs(self) -> Path:
        return self.root / str(self.config.get("docs_dir", "docs"))

    @property
    def docs_label(self) -> str:
        """How a page's path is prefixed in a problem report: `docs/`."""
        return str(self.config.get("docs_dir", "docs")).rstrip("/") + "/"

    @property
    def site(self) -> Path:
        return self.root / str(self.config.get("site_dir", "site"))

    @property
    def site_name(self) -> str:
        return str(self.config.get("site_name", ""))

    @property
    def site_url(self) -> str:
        return str(self.config.get("site_url", "")).rstrip("/") + "/"

    def label(self, path: Path) -> str:
        """A file outside docs/, named relative to docs/ the way a problem report names it."""
        return f"../{path.relative_to(self.root).as_posix()}"


def find_root(start: Path | None = None) -> Path:
    """The nearest directory at or above `start` holding a `mkdocs.yml`."""
    here = (start or Path.cwd()).resolve()
    for candidate in (here, *here.parents):
        if (candidate / "mkdocs.yml").is_file():
            return candidate
    raise ConfigError(f"no mkdocs.yml at or above {here}; run from inside a documented repository")


def project(root: Path | None = None) -> Project:
    root = root or find_root()
    config = _mkdocs_config(root / "mkdocs.yml")
    extra = config.get("extra") or {}
    raw = extra.get("docs") if isinstance(extra, dict) else None
    return Project(root, config, settings(raw or {}, root))


def settings(raw: Any, root: Path) -> Settings:
    if not isinstance(raw, dict):
        raise ConfigError("extra.docs must be a mapping")
    known = {"facets", "skills", "generated", "site-links"}
    for key in raw:
        if key not in known:
            raise ConfigError(f"extra.docs has an unknown key '{key}'; one of {sorted(known)}")

    facets: dict[str, tuple[str, ...]] = {}
    for key, values in (raw.get("facets") or {}).items():
        if key in FIXED_KEYS:
            raise ConfigError(f"extra.docs.facets names '{key}', which every page already has")
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            raise ConfigError(f"extra.docs.facets.{key} must be a list of strings")
        facets[str(key)] = tuple(values)

    skills = raw.get("skills") or {}
    if not isinstance(skills, dict):
        raise ConfigError("extra.docs.skills must be a mapping")
    source = root / str(skills.get("source", "tools/docs/skill"))
    targets: list[SkillTarget] = []
    for entry in skills.get("targets") or []:
        if not isinstance(entry, dict) or "path" not in entry:
            raise ConfigError("each extra.docs.skills.targets entry is a mapping with a `path`")
        escape = entry.get("escape")
        if escape not in (None, "giter8"):
            raise ConfigError(f"extra.docs.skills.targets: unknown escape '{escape}'; only `giter8` is known")
        targets.append(SkillTarget(root / str(entry["path"]), escape))

    generators: dict[str, Generator] = {}
    for name, spec in (raw.get("generated") or {}).items():
        if not isinstance(spec, dict) or "kind" not in spec:
            raise ConfigError(f"extra.docs.generated.{name} is a mapping with a `kind`")
        generators[str(name)] = Generator(str(name), str(spec["kind"]), {k: v for k, v in spec.items() if k != "kind"})

    site_links = raw.get("site-links") or {}
    if not isinstance(site_links, dict):
        raise ConfigError("extra.docs.site-links must be a mapping")

    return Settings(
        facets=facets,
        skills_source=source if source.is_dir() or targets else None,
        skill_targets=targets,
        generators=generators,
        site_link_files=[str(f) for f in site_links.get("files") or []],
        retired_hosts=[str(h) for h in site_links.get("retired-hosts") or []],
    )


class _Loader(yaml.SafeLoader):
    """mkdocs.yml may carry `!!python/name:` tags for extensions; nothing here needs them."""


def _ignore(loader: yaml.SafeLoader, suffix: str, node: yaml.Node) -> None:
    return None


_Loader.add_multi_constructor("tag:yaml.org,2002:python/", _ignore)
_Loader.add_multi_constructor("!", _ignore)


def _mkdocs_config(path: Path) -> dict[str, Any]:
    loaded = yaml.load(path.read_text(encoding="utf-8"), Loader=_Loader)  # noqa: S506 - SafeLoader subclass
    if not isinstance(loaded, dict):
        raise ConfigError(f"{path} is not a mapping")
    return loaded


# ── pages ────────────────────────────────────────────────────────────────────


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
    project: Project
    pages: dict[str, Page] = field(default_factory=dict)
    nav: list[NavEntry] = field(default_factory=list)

    def ordered(self) -> list[Page]:
        """Pages in navigation order; anything not in the navigation last, alphabetically."""
        seen = [e.path for e in self.nav if e.path in self.pages]
        rest = sorted(p for p in self.pages if p not in seen)
        return [self.pages[p] for p in [*seen, *rest]]

    def read(self, path: str) -> str:
        return (self.project.docs / path).read_text(encoding="utf-8")

    def write(self, path: str, text: str) -> None:
        (self.project.docs / path).write_text(text, encoding="utf-8")


def parse(relative: str, raw: str) -> Page:
    match = FRONTMATTER.match(raw)
    if not match:
        return Page(relative, {}, raw, raw)
    meta = yaml.safe_load(match.group(1)) or {}
    return Page(relative, meta if isinstance(meta, dict) else {}, raw[match.end() :], raw)


def load(current: Project | None = None) -> Tree:
    current = current or project()
    tree = Tree(current)
    for file in sorted(current.docs.rglob("*.md")):
        relative = file.relative_to(current.docs).as_posix()
        tree.pages[relative] = parse(relative, file.read_text(encoding="utf-8"))
    tree.nav = read_nav(current.config)
    return tree


def read_nav(config: dict[str, Any]) -> list[NavEntry]:
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

    walk(config.get("nav", []), [])
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
