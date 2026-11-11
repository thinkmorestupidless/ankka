"""The rules every page is held to.

Most of them exist because the most common reader of a page is a model that retrieved it alone, as one
chunk, with no navigation around it. A page that says "as described above" is broken for that reader,
and so is a link to a heading that does not exist. The same rules happen to make pages better for
people, which is why there is one set of them.
"""

from __future__ import annotations

import re
from pathlib import PurePosixPath

from . import generate, release, snippets
from .pages import FENCE, FIXED_KEYS, KINDS, Tree, anchors, fenced_lines, strip_code
from .snippets import Problem

# Phrases that only make sense to a reader who arrived from somewhere else on the site.
POSITIONAL = re.compile(
    r"\b(see|as|mentioned|described|shown|discussed|explained)\s+(above|below|earlier|previously)\b"
    r"|\b(previous|next|following|preceding)\s+(page|chapter)\b"
    r"|\bthe\s+(section|example|code|snippet)\s+(above|below)\b",
    re.IGNORECASE,
)

# The build's own history. Public pages explain behaviour as a property of the system, not as the
# outcome of a feature, a research note or a test that caught something. The forms are the ones a
# specification-driven build leaves behind: feature and task numbers, research notes, success criteria.
HISTORY = re.compile(
    r"\bfeature\s+0\d\d\b|\bresearch\s+R\d+\b|\bSC-\d{3}\b|\bspecs/0\d\d|\bFR-\d{3}\b|\bT\d{3}\b",
    re.IGNORECASE,
)

LINK = re.compile(r"(?<!!)\[(?P<label>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")
IMAGE = re.compile(r"!\[(?P<alt>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")

MAX_DESCRIPTION = 240


def frontmatter(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    facets = tree.project.settings.facets
    allowed = {*FIXED_KEYS, *facets}
    for page in tree.pages.values():
        meta = page.meta
        if not meta:
            problems.append(Problem(page.path, 1, "no frontmatter; every page starts with --- title/description/kind ---"))
            continue
        for key in ("title", "description", "kind"):
            if not meta.get(key):
                problems.append(Problem(page.path, 1, f"frontmatter has no '{key}'"))
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
        for key, vocabulary in facets.items():
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
    return problems


def escapes(tree: Tree) -> list[Problem]:
    """No page may contain a literal backslash-dollar, when a skill target escapes for Giter8.

    Every page is copied into such a target with each `$` escaped, because Giter8 reads an unescaped one
    as its own syntax. A page that already contains the escape is escaped a second time, and Giter8 then
    sees an escaped backslash followed by a live expression and refuses the whole template: `sbt new`
    exits with an error naming this file, every generated project is empty, and the only thing that
    notices is a slow, gated suite.

    A page explaining the escape is exactly the page that wants to print it, so this says what to do
    instead rather than only refusing.
    """
    if not tree.project.settings.escapes_dollars:
        return []
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
    """Links between pages are relative and resolve, anchors included; anything outside docs/ is a URL."""
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
                    resolved = _normalise(str(PurePosixPath(page.path).parent / path_part))
                    if resolved is None:
                        problems.append(Problem(page.path, number, f"link '{target}' leaves docs/; link to repository files by their GitHub URL"))
                        continue
                    if not resolved.endswith(".md"):
                        problems.append(Problem(page.path, number, f"link '{target}' is not a page; link to repository files by their GitHub URL"))
                        continue
                if resolved not in tree.pages:
                    problems.append(Problem(page.path, number, f"link '{target}' points at a page that does not exist"))
                    continue
                if anchor and anchor not in page_anchors[resolved]:
                    problems.append(Problem(page.path, number, f"link '{target}' names an anchor docs/{resolved} does not have"))
    return problems


def images(tree: Tree) -> list[Problem]:
    """An image is a file under docs/ and says in its alt text what it shows.

    Everywhere but the site the picture is gone: a model reading the raw Markdown, `llms-full.txt` or a
    skill gets the alt text and nothing else, so an image without it is a hole in the page."""
    problems: list[Problem] = []
    docs = tree.project.docs
    for page in tree.pages.values():
        text = strip_code(page.body)
        for number, line in enumerate(text.splitlines(), start=1):
            for match in IMAGE.finditer(line):
                target = match.group("target")
                if not match.group("alt").strip():
                    problems.append(Problem(page.path, number, f"image '{target}' has no alt text; say what it shows"))
                if re.match(r"^[a-z][a-z0-9+.-]*:", target):
                    continue
                resolved = _normalise(str(PurePosixPath(page.path).parent / target.partition("#")[0]))
                if resolved is None:
                    problems.append(Problem(page.path, number, f"image '{target}' leaves docs/; the site cannot serve it"))
                elif not (docs / resolved).is_file():
                    problems.append(Problem(page.path, number, f"image '{target}' points at a file that does not exist"))
    return problems


def _normalise(path: str) -> str | None:
    """The path with `.` and `..` resolved, or None when `..` climbs out of docs/."""
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
    """A block titled service.json is a whole ankka service descriptor. Its validity is checked by the JVM; its shape here."""
    problems: list[Problem] = []
    for page in tree.pages.values():
        for number, line in enumerate(page.body.splitlines(), start=1):
            if re.match(r'^```json\s+title="[^"]*service\.json"', line):
                following = page.body.splitlines()[number:number + 3]
                if not any('"name"' in f for f in following):
                    problems.append(Problem(page.path, number, "a service.json block should be a complete descriptor, starting with its name"))
    return problems


def addresses(tree: Tree) -> list[Problem]:
    """The site's home is `site_url`. A former home — `site-links.retired-hosts` — reads as correct and goes
    nowhere, so it is refused on every page and in every file `site-links.files` names, and in the curated
    skills, which link to the site from wherever a plugin installs them."""
    settings = tree.project.settings
    if not settings.retired_hosts:
        return []
    project = tree.project
    files = [(f"../{name}", project.root / name) for name in settings.site_link_files]
    if settings.skills_source is not None:
        files += [(project.label(s), s) for s in sorted(settings.skills_source.glob("*/SKILL.md"))]
    files += [(page.path, project.docs / page.path) for page in tree.pages.values()]
    problems: list[Problem] = []
    for host in settings.retired_hosts:
        if host in project.site_url:
            problems.append(Problem("../mkdocs.yml", 1, f"site_url is on {host}, which extra.docs.site-links says is retired"))
    for label, path in files:
        if not path.exists():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            for host in settings.retired_hosts:
                if host in line:
                    problems.append(Problem(label, number, f"{host} is not where the site lives; use site_url from mkdocs.yml"))
    return problems


# A tab set says "the same thing, in each language". Two tabs holding the same characters say instead
# that one of them was pasted and never rewritten, and that is exactly how a reader reports it: "the
# Python sample looks like the TypeScript one". The languages' APIs are deliberately close, so this is
# easy to do by accident and invisible on review — hence a check rather than a note.
TAB = re.compile(r"^/// tab \| (?P<label>.+?)\s*$")


def tabs(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    for page in tree.pages.values():
        lines = page.body.split("\n")
        in_code = fenced_lines(page.body)
        seen: dict[str, tuple[str, int]] = {}
        label: str | None = None
        number = 0
        for index, line in enumerate(lines, start=1):
            if index - 1 in in_code and not (label is not None and line.startswith("```")):
                # A Rust doc comment is a bare `///` inside a fence; only a `///` in prose closes a tab.
                continue
            opened = TAB.match(line)
            if opened:
                label, number = opened.group("label"), index
                continue
            if line.strip() == "///":
                # A closer ends the tab; a run of tabs with nothing but blanks between them is one set.
                label = None
                rest = lines[index:]
                if not any(TAB.match(l) for l in rest[: _gap(rest)] or [""]):
                    seen = {}
                continue
            if label is None or not line.startswith("```"):
                continue
            fence = line[: len(line) - len(line.lstrip("`"))]
            close = next((j for j in range(index, len(lines)) if lines[j].strip() == fence), None)
            code = "\n".join(lines[index:close]).strip() if close else ""
            if code and code in seen:
                other, where = seen[code]
                problems.append(
                    Problem(
                        page.path,
                        number,
                        f"the '{label}' and '{other}' tabs (line {where}) hold the same code; "
                        "write each in its own language's idiom, or say in prose why they are the same",
                    )
                )
            elif code:
                seen[code] = (label, number)
            label = None
    return problems


def _gap(rest: list[str]) -> int:
    """How many blank lines follow, so a tab set is told from two sets that happen to be adjacent."""
    n = 0
    while n < len(rest) and not rest[n].strip():
        n += 1
    return n + 1


# A sample is shown in every language the page is for. A page's `languages` (the facet `extra.docs.samples`
# names) says which languages its reader may be writing in; a tab set that shows three of them, or a
# block in one language alone, tells the other readers the feature is not theirs — and the gap is
# invisible to the writer, who reads one language at a time. So every tab set must hold a tab for each
# of the page's languages, in the order mkdocs.yml lists them, and a block in one of those languages
# outside a tab set is refused on a page with more than one. A language a feature cannot have is left
# out on purpose, and the purpose is written down where the check can read it, on the line before:
#
#     <!-- languages: scala, java, kotlin; only a service in the runtime's own JVM registers a blueprint -->
#
# The comment names exactly the languages shown and says why the rest are missing; the reason is what
# a reviewer checks against the limitations page.
HTML_COMMENT = re.compile(r"^<!--.*-->\s*$")


def samples(tree: Tree) -> list[Problem]:
    settings = tree.project.settings
    facet = settings.samples_facet
    if facet is None:
        return []
    vocabulary = settings.facets[facet]
    rank = {value: n for n, value in enumerate(vocabulary)}
    by_label = {value.lower(): value for value in vocabulary}
    by_info = {value: value for value in vocabulary}
    for value, infos in settings.samples_fences.items():
        by_info.update({info: value for info in infos})
    exception = re.compile(rf"^<!--\s*{re.escape(facet)}:\s*(?P<shown>[^;]*?)\s*;\s*(?P<reason>.+?)\s*-->\s*$")

    problems: list[Problem] = []
    for page in tree.pages.values():
        declared = page.meta.get(facet, [])
        declared = [str(v) for v in declared] if isinstance(declared, list) else []
        lines = page.body.split("\n")
        in_code = fenced_lines(page.body)

        def said(before: int) -> tuple[list[str], str] | None | str:
            """The exception comment above line `before` (zero-based), skipping blanks and other comments:
            its languages and reason, None when there is none, or the offending line when it is malformed."""
            i = before - 1
            while i >= 0 and (not lines[i].strip() or HTML_COMMENT.match(lines[i])):
                if lines[i].lstrip().startswith(f"<!-- {facet}:") or lines[i].lstrip().startswith(f"<!--{facet}:"):
                    match = exception.match(lines[i].strip())
                    if not match:
                        return lines[i]
                    shown = [v.strip() for v in match.group("shown").split(",") if v.strip()]
                    return shown, match.group("reason")
                i -= 1
            return None

        def judge(number: int, shown: list[str], what: str, comment: tuple[list[str], str] | None | str) -> None:
            """`shown` are the facet values a tab set or a lone block displays; `number` is its first line."""
            if isinstance(comment, str):
                problems.append(
                    Problem(page.path, number, f"the `{facet}:` comment is malformed; write `<!-- {facet}: a, b; why the rest are left out -->`")
                )
                return
            if comment is not None:
                named, _reason = comment
                for value in named:
                    if value not in vocabulary:
                        problems.append(Problem(page.path, number, f"the `{facet}:` comment names '{value}', which is not one of {', '.join(vocabulary)}"))
                if sorted(named) != sorted(shown):
                    problems.append(
                        Problem(page.path, number, f"the `{facet}:` comment says {', '.join(named) or 'nothing'} but {what} shows {', '.join(shown)}")
                    )
                elif declared and sorted(named) == sorted(declared):
                    problems.append(Problem(page.path, number, f"the `{facet}:` comment names every language the page does; remove it"))
                return
            missing = [v for v in declared if v not in shown]
            if missing:
                problems.append(
                    Problem(
                        page.path,
                        number,
                        f"{what} shows {', '.join(shown)}; the page's {facet} also name {', '.join(missing)}: "
                        f"show it in each, or say why not in `<!-- {facet}: {', '.join(shown)}; <reason> -->` on the line before",
                    )
                )
            elif not declared:
                problems.append(
                    Problem(
                        page.path,
                        number,
                        f"{what} shows {', '.join(shown)} but the page names no {facet}; add `{facet}:` to the frontmatter, "
                        f"or say why the sample is in {', '.join(shown)} alone in a `<!-- {facet}: ...; <reason> -->` comment",
                    )
                )

        index = 0
        while index < len(lines):
            line = lines[index]
            if index in in_code:
                # `fenced_lines` counts the fences themselves, so an opening fence is the first line of a run.
                fence = FENCE.match(line) if index == 0 or index - 1 not in in_code else None
                if fence and fence.group(2).startswith("`"):
                    info = fence.group(3).strip().split(" ")[0] if fence.group(3).strip() else ""
                    value = by_info.get(info)
                    if value is not None and (len(declared) != 1 or declared[0] != value):
                        # Its own comment may sit above the `<!-- include -->` line; `said` skips comments.
                        judge(index + 1, [value], f"a `{info}` block outside a tab set", said(index))
                index += 1
                continue
            opened = TAB.match(line)
            if opened:
                # Read the whole tab set: tabs separated by blank lines only.
                start = index
                labels: list[tuple[str, int]] = []
                while index < len(lines):
                    opened = TAB.match(lines[index])
                    if not opened:
                        break
                    labels.append((opened.group("label"), index + 1))
                    index += 1
                    # A bare `///` inside a fence is a Rust doc comment, not the tab's closer.
                    while index < len(lines) and (index in in_code or lines[index].strip() != "///"):
                        index += 1
                    index += 1
                    gap = index
                    while gap < len(lines) and not lines[gap].strip():
                        gap += 1
                    if gap < len(lines) and TAB.match(lines[gap]):
                        index = gap
                        continue
                    break
                shown: list[str] = []
                for label, where in labels:
                    value = by_label.get(label.lower())
                    if value is None:
                        problems.append(Problem(page.path, where, f"tab '{label}' is not one of the {facet}: {', '.join(vocabulary)}"))
                        continue
                    if declared and value not in declared:
                        problems.append(Problem(page.path, where, f"a '{label}' tab on a page whose {facet} do not name {value}; add it to the frontmatter"))
                    shown.append(value)
                ordered = sorted(shown, key=lambda v: rank[v])
                if shown != ordered:
                    problems.append(
                        Problem(page.path, start + 1, f"the tabs are in the order {', '.join(shown)}; put them in the order mkdocs.yml lists the {facet}: {', '.join(ordered)}")
                    )
                judge(start + 1, ordered, "the tab set", said(start))
                continue
            index += 1
    return problems


# A sample a reader copies must be one a test compiles and runs, or it drifts from the SDK with nothing
# going red. With `extra.docs.samples.tested`, every tab in one of the facet's languages opens with the
# `<!-- include: path#region -->` that `docs sync` fills (see snippets.py). A tab whose code no test can
# hold (a version line `docs sync` keeps current, a setting of the reader's own build) says so on the
# line before its block, and the reason is what a reviewer checks:
#
#     <!-- untested: the version line docs sync keeps at the newest release -->
UNTESTED = re.compile(r"^<!--\s*untested:\s*(?P<reason>.*?)\s*-->\s*$")


def tested(tree: Tree) -> list[Problem]:
    settings = tree.project.settings
    facet = settings.samples_facet
    if facet is None or not settings.samples_tested:
        return []
    by_label = {value.lower(): value for value in settings.facets[facet]}
    problems: list[Problem] = []
    for page in tree.pages.values():
        lines = page.body.split("\n")
        in_code = fenced_lines(page.body)
        for index, line in enumerate(lines):
            opened = TAB.match(line) if index not in in_code else None
            if not opened or opened.group("label").lower() not in by_label:
                continue
            first = index + 1
            while first < len(lines) and not lines[first].strip():
                first += 1
            head = lines[first].strip() if first < len(lines) else ""
            label = opened.group("label")
            untested = UNTESTED.match(head)
            if snippets.INCLUDE.match(head):
                continue
            if untested and not untested.group("reason"):
                problems.append(Problem(page.path, first + 1, f"the '{label}' tab's `untested:` comment gives no reason; say why no test can hold it"))
            elif untested:
                after = first + 1
                while after < len(lines) and not lines[after].strip():
                    after += 1
                if after < len(lines) and snippets.INCLUDE.match(lines[after].strip()):
                    problems.append(Problem(page.path, first + 1, f"the '{label}' tab is included from a tested file; remove its `untested:` comment"))
            elif head.startswith("```"):
                problems.append(
                    Problem(
                        page.path,
                        index + 1,
                        f"the '{label}' tab's code is written in the page, where nothing tests it: mark a region in a tested file and "
                        "put `<!-- include: path#region -->` before the block (then `docs sync`), "
                        "or say why no test can hold it in `<!-- untested: <reason> -->`",
                    )
                )
    return problems


def run(tree: Tree) -> list[Problem]:
    return [
        *frontmatter(tree),
        *structure(tree),
        *prose(tree),
        *escapes(tree),
        *links(tree),
        *images(tree),
        *navigation(tree),
        *examples(tree),
        *tabs(tree),
        *samples(tree),
        *tested(tree),
        *addresses(tree),
        *snippets.check(tree),
        *generate.check(tree),
        *release.check(tree),
    ]


__all__ = ["run"]
