"""Reference content generated from the code it describes.

A page holds generated content between two comments:

    <!-- generated:start configuration -->
    ...
    <!-- generated:end configuration -->

Everything between them is owned by a generator and rewritten by it; everything outside is written by
hand. Most reference pages are both: a generated table of facts (every variable, every route, every
command) and hand-written prose saying what they mean, with a check that the prose covers every fact.

Two owners. The generators here read files — HOCON configuration and protobuf definitions. The CLI and
the control plane's routes can only be enumerated by the JVM, so their blocks are owned by Scala test
suites (`CliReferenceSuite`, `ControlPlaneRoutesReferenceSuite`), which fail when the page is stale and
rewrite it under `-Dankka.docs.update=true`. This module leaves those blocks alone.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .pages import DOCS, ROOT, fenced_lines
from .snippets import Problem

BLOCK = re.compile(
    r"(?P<open><!--\s*generated:start\s+(?P<name>[\w-]+)\s*-->\n)(?P<body>.*?)(?P<close><!--\s*generated:end\s+(?P=name)\s*-->)",
    re.DOTALL,
)

# Blocks whose owner is a Scala suite, not this module.
JVM_OWNED = {"cli", "control-plane-routes"}


# ── configuration ────────────────────────────────────────────────────────────

CONF_FILES = [
    ("modules/runtime/src/main/resources/reference.conf", "every service"),
    ("modules/http/src/main/resources/reference.conf", "every service"),
    ("modules/runtime/src/main/resources/ankka-cluster-local.conf", "local mode"),
    ("modules/runtime/src/main/resources/ankka-cluster-kubernetes.conf", "kubernetes mode"),
]


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


def configuration() -> str:
    settings: list[Setting] = []
    for file, scope in CONF_FILES:
        settings.extend(_hocon_settings(ROOT / file, scope))
    from_env = [s for s in settings if s.env]
    ankka_keys = [s for s in settings if s.key.startswith("ankka.") and not s.env]

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
    for s in ankka_keys:
        lines.append(f"| `{s.key}` | `{s.default}` | {s.scope} |")
    return "\n".join(lines) + "\n"


def configuration_variables() -> list[str]:
    names: list[str] = []
    for file, scope in CONF_FILES:
        names.extend(s.env for s in _hocon_settings(ROOT / file, scope) if s.env)
    return names


# ── protocol ────────────────────────────────────────────────────────────────

PROTO_DIR = ROOT / "protocol/src/main/protobuf/ankka/protocol/v1"


def protocol() -> str:
    lines = ["| Service | RPC | Request | Response | Defined in |", "|---|---|---|---|---|"]
    for path in sorted(PROTO_DIR.glob("*.proto")):
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


GENERATORS: dict[str, Callable[[], str]] = {
    "configuration": configuration,
    "protocol": protocol,
}


# ── applying ────────────────────────────────────────────────────────────────


def process(page: str, text: str) -> tuple[str, list[Problem]]:
    problems: list[Problem] = []
    in_code = fenced_lines(text)

    def replace(match: re.Match[str]) -> str:
        name = match.group("name")
        # A block shown inside a code fence is an example of the syntax, not a block to fill.
        if text[: match.start()].count("\n") in in_code:
            return match.group(0)
        if name in JVM_OWNED:
            return match.group(0)
        generator = GENERATORS.get(name)
        if generator is None:
            line = text[: match.start()].count("\n") + 1
            problems.append(Problem(page, line, f"no generator named '{name}'"))
            return match.group(0)
        return match.group("open") + generator() + match.group("close")

    return BLOCK.sub(replace, text), problems


def sync(pages: list[str]) -> tuple[list[str], list[Problem]]:
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
    problems: list[Problem] = []
    for page in pages:
        text = (DOCS / page).read_text(encoding="utf-8")
        updated, found = process(page, text)
        problems.extend(found)
        if updated != text:
            problems.append(Problem(page, 1, "a generated block is stale; run `docs sync`"))
    problems.extend(coverage())
    return problems


def outside_blocks(text: str) -> str:
    return BLOCK.sub("", text)


def coverage() -> list[Problem]:
    """Hand-written prose must mention every fact a generated table lists."""
    problems: list[Problem] = []
    page = "reference/configuration.md"
    path = DOCS / page
    if path.exists():
        prose = outside_blocks(path.read_text(encoding="utf-8"))
        for name in configuration_variables():
            if f"`{name}`" not in prose:
                problems.append(Problem(page, 1, f"`{name}` is in the generated table but described nowhere on the page"))
    return problems
