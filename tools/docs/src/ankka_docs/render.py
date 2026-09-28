"""Renderings of the documentation for machines: raw Markdown per page, llms.txt, llms-full.txt and a JSON index.

The site MkDocs builds is for people. These sit beside it, at the same root, for everything else:

- `<page>.md` beside every page — the page as Markdown, which is what a model reads best and what the
  llms.txt convention links to.
- `llms.txt` — the site's table of contents in the llms.txt format: a title, a summary, and one link per
  page with its one-sentence description, grouped by section.
- `llms-full.txt` — every page in navigation order, in one file, for a context window that can take it.
- `docs-index.json` — every page's metadata, headings and addresses, for a retriever that wants to choose
  pages before reading them.

All of them are produced from the same parsed tree the checks run over, so a page cannot be valid for
one consumer and broken for another.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .pages import Page, Tree, fenced_lines, headings

COMMENT = re.compile(r"^<!--\s*(include|generated):.*-->\s*\n", re.MULTILINE)

# Language tabs exist for the site only. A model receives a page as Markdown with no renderer, so the
# tab markers are flattened back to a bold language label above each block. `/// tab | Python` becomes
# `**Python**`, and the closing `///` on a line of its own goes. Anything else beginning with `///` is
# left alone: it is not a tab, and silently eating it would hide a mistake.
# `[ \t]*`, never `\s*`: `\s` matches newlines, so a greedy trailing `\s*$` would swallow the blank line
# between the label and its fence and the flattened page would come out as `**Scala**` glued to ```scala.
TAB_OPEN = re.compile(r"^/// tab \| (?P<label>.+?)[ \t]*$")


def flatten_tabs(body: str) -> str:
    """The page with its language tabs reduced to labelled blocks, for every reader that is not the site."""
    # Markers inside a fenced block are an *example* of the syntax, not an instruction — the page that
    # documents tabs shows one. Flattening those would have that page teach the flattened form, which is
    # not what a writer should type. `fenced_lines` counts fence length, so the ````markdown block that
    # wraps such an example is tracked correctly around the ```scala blocks inside it.
    in_code = fenced_lines(body)
    lines = body.split("\n")
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if i not in in_code:
            opened = TAB_OPEN.match(line)
            if opened:
                out.append(f"**{opened.group('label')}**")
                i += 1
                continue
            if line.rstrip() == "///":
                # Drop the closer and the blank line under it, so what is left is one blank line rather
                # than two. Collapsing runs of newlines afterwards would be the obvious alternative and
                # is wrong: it also reformats the blank lines *inside* a Python sample, and these blocks
                # are copied verbatim from tested source.
                i += 1
                if i < len(lines) and not lines[i].strip():
                    i += 1
                continue
        out.append(line)
        i += 1
    return "\n".join(out)


def page_markdown(page: Page, base: str) -> str:
    """A page as a model should receive it: title, summary, where it lives, then the body — no tooling comments."""
    body = flatten_tabs(COMMENT.sub("", page.body.lstrip("\n")))
    lines = body.split("\n", 1)
    rest = lines[1] if len(lines) > 1 else ""
    header = [lines[0], "", f"> {page.description}", "", f"Source: {base}{page.url_path}", ""]
    return "\n".join(header) + rest.lstrip("\n")


def sections(tree: Tree) -> list[tuple[str, list[Page]]]:
    grouped: dict[str, list[Page]] = {}
    order: list[str] = []
    for entry in tree.nav:
        page = tree.pages.get(entry.path)
        if page is None:
            continue
        name = entry.section[0] if entry.section else "Start here"
        if name not in grouped:
            grouped[name] = []
            order.append(name)
        grouped[name].append(page)
    return [(name, grouped[name]) for name in order]


def llms_txt(tree: Tree, base: str) -> str:
    config = tree.project.config
    home = tree.pages.get("index.md")
    lines = [
        f"# {tree.project.site_name}",
        "",
        f"> {config.get('site_description', '')}",
        "",
        "Every link below is a page's Markdown. `llms-full.txt` at the same root holds every page in one file,",
        "and `docs-index.json` every page's metadata. Code samples are copied from sources the build compiles",
        "and tests.",
        "",
    ]
    if home:
        lines += [f"Start with [{home.title}]({base}index.md): {home.description}", ""]
    for name, pages in sections(tree):
        visible = [p for p in pages if p.path != "index.md"]
        if not visible:
            continue
        lines += [f"## {name}", ""]
        for page in visible:
            lines.append(f"- [{page.title}]({base}{page.markdown_path}): {page.description}")
        lines.append("")
    lines += ["## Optional", "", f"- [Every page in one file]({base}llms-full.txt): the whole documentation, in navigation order.", ""]
    return "\n".join(lines)


def llms_full(tree: Tree, base: str) -> str:
    config = tree.project.config
    parts = [f"# {tree.project.site_name} documentation\n\n> {config.get('site_description', '')}\n"]
    for page in tree.ordered():
        parts.append(page_markdown(page, base))
    return "\n\n---\n\n".join(parts) + "\n"


def index(tree: Tree, base: str) -> list[dict[str, Any]]:
    section_of: dict[str, list[str]] = {e.path: e.section for e in tree.nav}
    facets = tree.project.settings.facets
    records = []
    for page in tree.ordered():
        records.append(
            {
                "path": page.path,
                "url": base + page.url_path,
                "markdown": base + page.markdown_path,
                "title": page.title,
                "description": page.description,
                "kind": page.kind,
                **{facet: page.meta.get(facet, []) for facet in facets},
                "related": page.meta.get("related", []),
                "section": section_of.get(page.path, []),
                "headings": [h.replace("`", "") for h in headings(page.body)[1:]],
            }
        )
    return records


def write(tree: Tree, site: Path) -> None:
    base = tree.project.site_url
    for page in tree.pages.values():
        target = site / page.markdown_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page_markdown(page, base), encoding="utf-8")
    (site / "llms.txt").write_text(llms_txt(tree, base), encoding="utf-8")
    (site / "llms-full.txt").write_text(llms_full(tree, base), encoding="utf-8")
    (site / "docs-index.json").write_text(json.dumps(index(tree, base), indent=2) + "\n", encoding="utf-8")
