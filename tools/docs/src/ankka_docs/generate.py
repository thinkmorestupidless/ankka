"""Reference content generated from the code it describes.

A page holds generated content between two comments:

    <!-- generated:start configuration -->
    ...
    <!-- generated:end configuration -->

Everything between them is owned by a generator and rewritten by it; everything outside is written by
hand. Most reference pages are both: a generated table of facts (every variable, every route, every
command) and hand-written prose saying what they mean, with a check that the prose covers every fact.

Which generator owns which block is `extra.docs.generated` in `mkdocs.yml`: each block name maps to a
`kind` this module knows and that kind's options. Two kinds read files here — `hocon` (configuration
keys and the environment variables that override them) and `protobuf` (the RPCs of every service). A
block of kind `external` is owned by something that can enumerate what the file system cannot: in ankka,
Scala test suites rewrite the CLI's command tree and the control plane's route table, and fail when the
page is stale. This module leaves those blocks alone.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from .pages import ConfigError, Generator, Tree, fenced_lines
from .snippets import Problem

BLOCK = re.compile(
    r"(?P<open><!--\s*generated:start\s+(?P<name>[\w-]+)\s*-->\n)(?P<body>.*?)(?P<close><!--\s*generated:end\s+(?P=name)\s*-->)",
    re.DOTALL,
)

KINDS = ("hocon", "protobuf", "external")


# ── hocon ────────────────────────────────────────────────────────────────────


@dataclass
class Setting:
    key: str
    default: str | None
    env: str | None
    required: bool
    scope: str


def _hocon_settings(path: Path, scope: str) -> list[Setting]:
    """A deliberately small HOCON reader: nested blocks, `key = value`, and `${?ENV}` overrides."""
    stack: list[str] = []
    found: dict[str, Setting] = {}
    order: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.strip().startswith('"') else raw.strip()
        if not line:
            continue
        if line == "}":
            if stack:
                stack.pop()
            continue
        opened = re.match(r"^([\w.\-\"]+)\s*\{$", line)
        if opened:
            stack.append(opened.group(1).strip('"'))
            continue
        assign = re.match(r"^([\w.\-\"]+)\s*[=:]\s*(.+)$", line)
        if not assign:
            continue
        key = ".".join([*stack, assign.group(1).strip('"')])
        value = assign.group(2).strip()
        env = re.fullmatch(r"\$\{(\?)?([A-Z_][A-Z0-9_]*)\}", value)
        setting = found.get(key)
        if setting is None:
            setting = Setting(key, None, None, False, scope)
            found[key] = setting
            order.append(key)
        if env:
            setting.env = env.group(2)
            setting.required = env.group(1) is None
        else:
            setting.default = value
    return [found[k] for k in order]


def _hocon_files(root: Path, generator: Generator) -> list[tuple[Path, str]]:
    files = generator.options.get("files")
    if not isinstance(files, list) or not files:
        raise ConfigError(f"extra.docs.generated.{generator.name}: a hocon block needs a `files` list of {{path, scope}}")
    out = []
    for entry in files:
        if not isinstance(entry, dict) or "path" not in entry:
            raise ConfigError(f"extra.docs.generated.{generator.name}: each file is a mapping with `path` and `scope`")
        out.append((root / str(entry["path"]), str(entry.get("scope", ""))))
    return out


def _all_settings(root: Path, generator: Generator) -> list[Setting]:
    settings: list[Setting] = []
    for path, scope in _hocon_files(root, generator):
        settings.extend(_hocon_settings(path, scope))
    return settings


def hocon(root: Path, generator: Generator) -> str:
    """Two tables: the settings an environment variable overrides, then the rest under `prefix`."""
    settings = _all_settings(root, generator)
    prefix = str(generator.options.get("prefix", ""))
    from_env = [s for s in settings if s.env]
    own_keys = [s for s in settings if s.key.startswith(prefix) and not s.env]

    lines = [
        "| Variable | Configuration key | Default | Applies in |",
        "|---|---|---|---|",
    ]
    for s in from_env:
        default = "required, set by the platform" if s.required else (f"`{s.default}`" if s.default else "none")
        lines.append(f"| `{s.env}` | `{s.key}` | {default} | {s.scope} |")
    lines += [
        "",
        "Settings with no environment variable, overridable in the service's own `application.conf`:",
        "",
        "| Configuration key | Default | Applies in |",
        "|---|---|---|",
    ]
    for s in own_keys:
        lines.append(f"| `{s.key}` | `{s.default}` | {s.scope} |")
    return "\n".join(lines) + "\n"


def hocon_variables(root: Path, generator: Generator) -> list[str]:
    return [s.env for s in _all_settings(root, generator) if s.env]


# ── protobuf ─────────────────────────────────────────────────────────────────


def protobuf(root: Path, generator: Generator) -> str:
    directory = generator.options.get("directory")
    if not directory:
        raise ConfigError(f"extra.docs.generated.{generator.name}: a protobuf block needs a `directory` of .proto files")
    lines = ["| Service | RPC | Request | Response | Defined in |", "|---|---|---|---|---|"]
    for path in sorted((root / str(directory)).glob("*.proto")):
        service = None
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.split("//", 1)[0].strip()
            opened = re.match(r"^service\s+(\w+)\s*\{(.*)$", line)
            if opened:
                service = opened.group(1)
                line = opened.group(2)
            if service is None:
                continue
            for rpc in re.finditer(
                r"rpc\s+(\w+)\s*\(\s*(stream\s+)?([\w.]+)\s*\)\s*returns\s*\(\s*(stream\s+)?([\w.]+)\s*\)", line
            ):
                request = ("stream " if rpc.group(2) else "") + rpc.group(3)
                response = ("stream " if rpc.group(4) else "") + rpc.group(5)
                lines.append(f"| `{service}` | `{rpc.group(1)}` | `{request}` | `{response}` | `{path.name}` |")
            if "}" in line:
                service = None
    return "\n".join(lines) + "\n"


# ── applying ────────────────────────────────────────────────────────────────


def render(root: Path, generator: Generator) -> str | None:
    """The block's content, or None for a block this module does not own."""
    if generator.kind == "hocon":
        return hocon(root, generator)
    if generator.kind == "protobuf":
        return protobuf(root, generator)
    if generator.kind == "external":
        return None
    raise ConfigError(f"extra.docs.generated.{generator.name}: unknown kind '{generator.kind}'; one of {', '.join(KINDS)}")


def process(tree: Tree, page: str, text: str) -> tuple[str, list[Problem]]:
    problems: list[Problem] = []
    in_code = fenced_lines(text)
    generators = tree.project.settings.generators
    root = tree.project.root

    def replace(match: re.Match[str]) -> str:
        name = match.group("name")
        # A block shown inside a code fence is an example of the syntax, not a block to fill.
        if text[: match.start()].count("\n") in in_code:
            return match.group(0)
        generator = generators.get(name)
        if generator is None:
            line = text[: match.start()].count("\n") + 1
            problems.append(Problem(page, line, f"no generator named '{name}' in mkdocs.yml's extra.docs.generated"))
            return match.group(0)
        content = render(root, generator)
        if content is None:
            return match.group(0)
        return match.group("open") + content + match.group("close")

    return BLOCK.sub(replace, text), problems


def sync(tree: Tree) -> tuple[list[str], list[Problem]]:
    changed, problems = [], []
    for page in sorted(tree.pages):
        text = tree.read(page)
        updated, found = process(tree, page, text)
        problems.extend(found)
        if updated != text:
            tree.write(page, updated)
            changed.append(page)
    return changed, problems


def check(tree: Tree) -> list[Problem]:
    problems: list[Problem] = []
    for page in sorted(tree.pages):
        text = tree.read(page)
        updated, found = process(tree, page, text)
        problems.extend(found)
        if updated != text:
            problems.append(Problem(page, 1, "a generated block is stale; run `docs sync`"))
    problems.extend(coverage(tree))
    return problems


def outside_blocks(text: str) -> str:
    return BLOCK.sub("", text)


def coverage(tree: Tree) -> list[Problem]:
    """Hand-written prose must mention every variable a hocon table lists, on the page `described-on` names."""
    problems: list[Problem] = []
    for generator in tree.project.settings.generators.values():
        if generator.kind != "hocon":
            continue
        page = generator.options.get("described-on")
        if not page:
            continue
        page = str(page)
        if page not in tree.pages:
            raise ConfigError(f"extra.docs.generated.{generator.name}: described-on names {page}, which is not a page")
        prose = outside_blocks(tree.read(page))
        for name in hocon_variables(tree.project.root, generator):
            if f"`{name}`" not in prose:
                problems.append(Problem(page, 1, f"`{name}` is in the generated table but described nowhere on the page"))
    return problems

