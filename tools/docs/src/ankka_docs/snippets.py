"""Code samples copied from tested sources, kept in step by a check.

A page shows a sample as an ordinary fenced block, preceded by a comment naming where it comes from:

    <!-- include: samples/shopping-cart/src/main/scala/shoppingcart/application/ShoppingCartEntity.scala#entity -->
    ```scala
    ...
    ```

The block's content is the named region of that file. Regions are marked in the source with
`docs:start <name>` and `docs:end <name>` on comment lines, in whatever comment syntax the file uses;
the marker lines themselves, and any other region's markers inside, are left out. Without `#region`
the whole file is included.

The copy is written into the page rather than resolved at render time on purpose: the source must read
correctly with no renderer at all — on GitHub, in an editor, or pasted whole into a model's context.
`docs sync` rewrites the copies; `docs check` fails when one has drifted from its source, so a sample
that is compiled and tested by the build is also the sample the reader sees.
"""

from __future__ import annotations

import re
import textwrap
from dataclasses import dataclass
from pathlib import Path

from .pages import DOCS, ROOT, fenced_lines

INCLUDE = re.compile(r"^<!--\s*include:\s*(?P<target>\S+?)\s*-->\s*$")
START = re.compile(r"docs:start\s+(?P<name>[\w.-]+)")
END = re.compile(r"docs:end\s+(?P<name>[\w.-]+)")
OPEN_FENCE = re.compile(r"^(?P<fence>`{3,})(?P<info>.*)$")


@dataclass
class Problem:
    page: str
    line: int
    message: str

    def __str__(self) -> str:
        return f"docs/{self.page}:{self.line}: {self.message}"


def region(target: str) -> str:
    """The text a target names, dedented, with no trailing blank lines."""
    file, _, name = target.partition("#")
    path = ROOT / file
    if not path.is_file():
        raise LookupError(f"include source {file} does not exist")
    lines = path.read_text(encoding="utf-8").splitlines()
    if not name:
        body = [line for line in lines if not (START.search(line) or END.search(line))]
    else:
        inside = False
        found = False
        body = []
        for line in lines:
            start = START.search(line)
            end = END.search(line)
            if start and start.group("name") == name:
                inside, found = True, True
                continue
            if end and end.group("name") == name:
                inside = False
                continue
            if inside and not (start or end):
                body.append(line)
        if not found:
            raise LookupError(f"include source {file} has no region '{name}'")
    text = textwrap.dedent("\n".join(body)).strip("\n")
    return text


def process(page: str, text: str) -> tuple[str, list[Problem]]:
    """The page with every include refreshed, and the problems found doing it."""
    lines = text.split("\n")
    # An include comment shown inside a code block — on the page that documents the syntax — is an
    # example, not an instruction.
    in_code = fenced_lines(text)
    out: list[str] = []
    problems: list[Problem] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        match = INCLUDE.match(line) if i not in in_code else None
        if not match:
            i += 1
            continue
        target = match.group("target")
        if i + 1 >= len(lines) or not OPEN_FENCE.match(lines[i + 1]):
            problems.append(Problem(page, i + 1, f"include of {target} is not followed by a fenced code block"))
            i += 1
            continue
        fence_match = OPEN_FENCE.match(lines[i + 1])
        assert fence_match is not None
        fence = fence_match.group("fence")
        close = next((j for j in range(i + 2, len(lines)) if lines[j].strip() == fence), None)
        if close is None:
            problems.append(Problem(page, i + 2, f"the code block for {target} is never closed"))
            i += 1
            continue
        try:
            content = region(target)
        except LookupError as error:
            problems.append(Problem(page, i + 1, str(error)))
            out.extend(lines[i + 1 : close + 1])
            i = close + 1
            continue
        out.append(lines[i + 1])
        out.extend(content.split("\n"))
        out.append(lines[close])
        i = close + 1
    return "\n".join(out), problems


def sync(pages: list[str]) -> tuple[list[str], list[Problem]]:
    """Rewrites every page whose includes are stale; returns the pages changed and any problems."""
    changed, problems = [], []
    for page in pages:
        path = DOCS / page
        text = path.read_text(encoding="utf-8")
        updated, found = process(page, text)
        problems.extend(found)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(page)
    return changed, problems


def check(pages: list[str]) -> list[Problem]:
    problems = []
    for page in pages:
        text = (DOCS / page).read_text(encoding="utf-8")
        updated, found = process(page, text)
        problems.extend(found)
        if updated != text:
            problems.append(Problem(page, 1, "an included sample has drifted from its source; run `docs sync`"))
    return problems


def sources() -> list[Path]:
    """Every file a page includes from, for tooling that wants to watch them."""
    found: set[Path] = set()
    for path in DOCS.rglob("*.md"):
        for line in path.read_text(encoding="utf-8").splitlines():
            match = INCLUDE.match(line)
            if match:
                found.add(ROOT / match.group("target").partition("#")[0])
    return sorted(found)
