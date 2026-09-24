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

from .pages import Page, Tree, headings, mkdocs_config

COMMENT = re.compile(r"^<!--\s*(include|generated):.*-->\s*\n", re.MULTILINE)

SECTION_ORDER_FALLBACK = "Other"


def site_url() -> str:
    url = str(mkdocs_config().get("site_url", "")).rstrip("/")
    return url + "/"


def page_markdown(page: Page, base: str) -> str:
    """A page as a model should receive it: title, summary, where it lives, then the body — no tooling comments."""
    body = COMMENT.sub("", page.body.lstrip("\n"))
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
    config = mkdocs_config()
    home = tree.pages.get("index.md")
    lines = [
        f"# {config.get('site_name', 'ankka')}",
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
    config = mkdocs_config()
    parts = [f"# {config.get('site_name', 'ankka')} documentation\n\n> {config.get('site_description', '')}\n"]
    for page in tree.ordered():
        parts.append(page_markdown(page, base))
    return "\n\n---\n\n".join(parts) + "\n"


def index(tree: Tree, base: str) -> list[dict[str, Any]]:
    section_of: dict[str, list[str]] = {e.path: e.section for e in tree.nav}
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
                "languages": page.meta.get("languages", []),
                "components": page.meta.get("components", []),
                "related": page.meta.get("related", []),
                "section": section_of.get(page.path, []),
                "headings": [h.replace("`", "") for h in headings(page.body)[1:]],
            }
        )
    return records


def write(tree: Tree, site: Path) -> None:
    base = site_url()
    for page in tree.pages.values():
        target = site / page.markdown_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page_markdown(page, base), encoding="utf-8")
    (site / "llms.txt").write_text(llms_txt(tree, base), encoding="utf-8")
    (site / "llms-full.txt").write_text(llms_full(tree, base), encoding="utf-8")
    (site / "docs-index.json").write_text(json.dumps(index(tree, base), indent=2) + "\n", encoding="utf-8")
