"""The rules every page is held to.

Most of them exist because the most common reader of a page is a model that retrieved it alone, as one
chunk, with no navigation around it. A page that says "as described above" is broken for that reader,
and so is a link to a heading that does not exist. The same rules happen to make pages better for
people, which is why there is one set of them.
"""

from __future__ import annotations

import re
from pathlib import PurePosixPath

from . import generate, snippets
from .pages import COMPONENTS, DOCS, FENCE, KINDS, LANGUAGES, ROOT, Tree, anchors, strip_code
from .snippets import Problem

# Phrases that only make sense to a reader who arrived from somewhere else on the site.
POSITIONAL = re.compile(
    r"\b(see|as|mentioned|described|shown|discussed|explained)\s+(above|below|earlier|previously)\b"
    r"|\b(previous|next|following|preceding)\s+(page|chapter)\b"
    r"|\bthe\s+(section|example|code|snippet)\s+(above|below)\b",
    re.IGNORECASE,
)

# The build's own history. Public pages explain behaviour as a property of the system, not as the
# outcome of a feature, a research note or a test that caught something.
HISTORY = re.compile(
    r"\bfeature\s+0\d\d\b|\bresearch\s+R\d+\b|\bSC-\d{3}\b|\bspecs/0\d\d|\bFR-\d{3}\b|\bT\d{3}\b",
    re.IGNORECASE,
)

LINK = re.compile(r"(?<!!)\[(?P<label>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")

MAX_DESCRIPTION = 240


def frontmatter(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    for page in tree.pages.values():
        meta = page.meta
        if not meta:
            problems.append(Problem(page.path, 1, "no frontmatter; every page starts with --- title/description/kind ---"))
            continue
        for key in ("title", "description", "kind"):
            if not meta.get(key):
                problems.append(Problem(page.path, 1, f"frontmatter has no '{key}'"))
        allowed = {"title", "description", "kind", "languages", "components", "related"}
        for key in meta:
            if key not in allowed:
                problems.append(Problem(page.path, 1, f"unknown frontmatter key '{key}'; one of {sorted(allowed)}"))
        if meta.get("kind") and meta["kind"] not in KINDS:
            problems.append(Problem(page.path, 1, f"kind '{meta['kind']}' is not one of {', '.join(KINDS)}"))
        description = str(meta.get("description", ""))
        if description and len(description) > MAX_DESCRIPTION:
            problems.append(Problem(page.path, 1, f"description is {len(description)} characters; keep it under {MAX_DESCRIPTION}"))
        if description and not description.endswith("."):
            problems.append(Problem(page.path, 1, "description should be one sentence ending with a full stop"))
        for key, vocabulary in (("languages", LANGUAGES), ("components", COMPONENTS)):
            values = meta.get(key, [])
            if not isinstance(values, list):
                problems.append(Problem(page.path, 1, f"'{key}' must be a list"))
                continue
            for value in values:
                if value not in vocabulary:
                    problems.append(Problem(page.path, 1, f"{key} value '{value}' is not one of {', '.join(vocabulary)}"))
        related = meta.get("related", [])
        if not isinstance(related, list):
            problems.append(Problem(page.path, 1, "'related' must be a list of paths under docs/"))
        else:
            for target in related:
                if str(target) not in tree.pages:
                    problems.append(Problem(page.path, 1, f"related page '{target}' does not exist"))
    return problems


def structure(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    for page in tree.pages.values():
        lines = page.body.lstrip("\n").splitlines()
        first = lines[0] if lines else ""
        if first != f"# {page.title}":
            problems.append(Problem(page.path, 1, f"the body must open with '# {page.title}', matching the frontmatter title"))
        # Exactly one H1: the page is one topic.
        prose_lines = strip_code(page.body).splitlines()
        h1 = [line for line in prose_lines if re.match(r"^#\s", line)]
        if len(h1) > 1:
            problems.append(Problem(page.path, 1, "more than one level-one heading; split the page or demote the heading"))
        # Every fence says what language it holds, so a reader — or a model — knows what it is looking at.
        fence: str | None = None
        for number, line in enumerate(page.body.splitlines(), start=1):
            match = FENCE.match(line)
            if not match:
                continue
            if fence is None:
                fence = match.group(2)
                if not match.group(3).strip():
                    problems.append(Problem(page.path, number, "a code block with no language; use ```text for plain output"))
            elif match.group(2).startswith(fence[0]) and len(match.group(2)) >= len(fence) and not match.group(3).strip():
                fence = None
    return problems


def prose(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    for page in tree.pages.values():
        text = strip_code(page.body)
        for number, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("<!--"):
                continue
            positional = POSITIONAL.search(line)
            if positional:
                problems.append(Problem(page.path, number, f"'{positional.group(0)}' assumes the reader arrived in order; link to the section instead"))
            history = HISTORY.search(line) if page.kind != "contributing" else None
            if history:
                problems.append(Problem(page.path, number, f"'{history.group(0)}' is the project's internal history; explain the behaviour instead"))
            if "](http" not in line and re.search(r"\]\((\.\./)+(modules|samples|sdks|protocol|cli|controlplane|kustomization|sidecar|operator|crd)/", line):
                problems.append(Problem(page.path, number, "a relative link out of docs/; link to repository files by their GitHub URL"))
    return problems


def escapes(tree: Tree) -> list[Problem]:
    """No page may contain a literal backslash-dollar.

    Every page is copied into the template's skills with each `$` escaped for Giter8, which reads an
    unescaped one as its own syntax. A page that already contains the escape is escaped a second time,
    and Giter8 then sees an escaped backslash followed by a live expression and refuses the whole
    template: `sbt new` exits with an error naming this file, every generated project is empty, and the
    only thing that notices is a slow, gated suite.

    A page explaining the escape is exactly the page that wants to print it, so this says what to do
    instead rather than only refusing.
    """
    problems: list[Problem] = []
    for page in tree.pages.values():
        for number, line in enumerate(page.body.splitlines(), start=1):
            if "\\$" in line:
                problems.append(
                    Problem(
                        page.path,
                        number,
                        "a literal '\\$' is escaped again for the template and breaks project "
                        "generation; describe the escape in words rather than printing it",
                    )
                )
    return problems


def links(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    page_anchors = {path: anchors(page.body) for path, page in tree.pages.items()}
    for page in tree.pages.values():
        text = strip_code(page.body)
        for number, line in enumerate(text.splitlines(), start=1):
            for match in LINK.finditer(line):
                target = match.group("target")
                if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("mailto:"):
                    continue
                path_part, _, anchor = target.partition("#")
                if not path_part:
                    resolved = page.path
                else:
                    resolved = str(PurePosixPath(page.path).parent / path_part)
                    resolved = _normalise(resolved)
                    if resolved is None or not resolved.endswith(".md"):
                        problems.append(Problem(page.path, number, f"link '{target}' leaves docs/ or is not a page; use a GitHub URL for repository files"))
                        continue
                if resolved not in tree.pages:
                    problems.append(Problem(page.path, number, f"link '{target}' points at a page that does not exist"))
                    continue
                if anchor and anchor not in page_anchors[resolved]:
                    problems.append(Problem(page.path, number, f"link '{target}' names an anchor docs/{resolved} does not have"))
    return problems


def _normalise(path: str) -> str | None:
    parts: list[str] = []
    for part in path.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                return None
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def navigation(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    in_nav = {entry.path for entry in tree.nav}
    for entry in tree.nav:
        if entry.path not in tree.pages:
            problems.append(Problem("../mkdocs.yml", 1, f"nav names {entry.path}, which is not a page"))
    for path in tree.pages:
        if path not in in_nav:
            problems.append(Problem(path, 1, "the page is not in mkdocs.yml's nav"))
    return problems


def examples(tree: Tree) -> list[Problem]:
    """A block titled service.json is a whole descriptor. Its validity is checked by the JVM; its shape here."""
    problems: list[Problem] = []
    for page in tree.pages.values():
        for number, line in enumerate(page.body.splitlines(), start=1):
            if re.match(r'^```json\s+title="[^"]*service\.json"', line):
                following = page.body.splitlines()[number:number + 3]
                if not any('"name"' in f for f in following):
                    problems.append(Problem(page.path, number, "a service.json block should be a complete descriptor, starting with its name"))
    return problems


# The site's home is `site_url` in mkdocs.yml. Its first home, GitHub Pages' project address, is dead;
# a link to it reads as correct and goes nowhere, so it is refused wherever the site is linked from.
RETIRED_HOST = "thinkmorestupidless.github.io"
LINKS_TO_SITE = [
    "mkdocs.yml",
    "README.md",
    "sdks/python/README.md",
    "sdks/python/pyproject.toml",
    "sdks/typescript/README.md",
    "sdks/typescript/package.json",
    "marketplace/plugins/ankka/.claude-plugin/plugin.json",
    "marketplace/.claude-plugin/marketplace.json",
    "marketplace/README.md",
    *[f"tools/docs/skill/{d.name}/SKILL.md" for d in sorted((ROOT / "tools/docs/skill").iterdir()) if d.is_dir()],
]


def addresses(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    files = [(f"../{name}", (ROOT / name)) for name in LINKS_TO_SITE] + [
        (page.path, DOCS / page.path) for page in tree.pages.values()
    ]
    for label, path in files:
        if not path.exists():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if RETIRED_HOST in line:
                problems.append(Problem(label, number, f"{RETIRED_HOST} is not where the site lives; use site_url from mkdocs.yml"))
    return problems


def run(tree: Tree) -> list[Problem]:
    pages = sorted(tree.pages)
    problems = [
        *frontmatter(tree),
        *structure(tree),
        *prose(tree),
        *escapes(tree),
        *links(tree),
        *navigation(tree),
        *examples(tree),
        *addresses(tree),
        *snippets.check(pages),
        *generate.check(pages),
    ]
    return problems


__all__ = ["run", "DOCS", "ROOT"]
