"""`docs init`: set a repository's documentation up from the tool's own templates.

The command reads what it can from the repository (its root, its name and its address, from git), takes
the rest as arguments, and plans the set-up as data: one `Write` per file, saying whether the tool keeps
the file or the repository owns it, and what will happen to it. `apply` is the one function that touches
the file system, and it writes nothing when the plan refuses anything. The report is printed from the
plan, so what the command says it did is what it did.

What the repository owns (the navigation, the pages, the skill, the plugin's description) is written
when absent and never again. What the tool keeps (`kept.SETTINGS`, `kept.JOB`) is written every time, to
what this release writes, which is how a hand edit is put back and how a repository follows the tool to
a later release. A file at a kept path that the tool did not write is someone's own: the plan refuses
it unless told `--force`.
"""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, distribution
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import urlsplit

from . import kept
from .pages import ConfigError, project
from .skill import SKILL_NAME

KEPT = "kept"
OWNED = "owned"

WRITE, LEAVE, REWRITE, CURRENT, REFUSE = "write", "leave", "rewrite", "current", "refuse"
VERBS = {WRITE: "wrote", REWRITE: "rewrote", LEAVE: "left", CURRENT: "current"}

GITHUB = re.compile(r"^(?:git@github\.com:|ssh://git@github\.com/|https://github\.com/)(?P<owner>[^/]+)/(?P<name>[^/]+?)(?:\.git)?/?$")
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
MAX_DESCRIPTION = 240

USAGE = "docs init [--description <sentence>] [--site-url <url>] [--release <major.minor.patch>] [--force]"


class Usage(Exception):
    """Nothing was written; the message says what is missing."""


@dataclass(frozen=True)
class Arguments:
    description: str | None = None
    site_url: str | None = None
    release: str | None = None
    force: bool = False


@dataclass(frozen=True)
class Facts:
    root: Path
    name: str
    owner: str
    repo_url: str
    description: str
    site_url: str
    release: str
    force: bool = False


@dataclass(frozen=True)
class Write:
    path: str  # relative to the root
    content: str
    ownership: str  # KEPT or OWNED
    action: str  # WRITE, LEAVE, REWRITE, CURRENT or REFUSE


def arguments(argv: list[str]) -> Arguments:
    values: dict[str, Any] = {}
    options = {"--description": "description", "--site-url": "site_url", "--release": "release"}
    rest = list(argv)
    while rest:
        word = rest.pop(0)
        if word == "--force":
            values["force"] = True
        elif word in options:
            if not rest:
                raise Usage(f"`{word}` needs a value: `{USAGE}`")
            values[options[word]] = rest.pop(0)
        else:
            raise Usage(f"init takes `{USAGE}`; `{word}` is not one of them")
    return Arguments(**values)


# ── the facts ────────────────────────────────────────────────────────────────


def _git(start: Path, *args: str) -> str | None:
    try:
        done = subprocess.run(["git", *args], cwd=start, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return None
    return done.stdout.strip() if done.returncode == 0 and done.stdout.strip() else None


class Distribution(Protocol):
    def read_text(self, filename: str) -> str | None: ...


def tool_release(installed: Distribution | None = None) -> str | None:
    """The release this tool was installed from: the tag `direct_url.json` records for a git install
    (`uvx --from git+…@v0.19.0`, or a project's `ankka-docs @ git+…@v0.19.0`). A branch, a commit, a
    local path or an editable install is not a release, and gives None."""
    if installed is None:
        try:
            installed = distribution("ankka-docs")
        except PackageNotFoundError:
            return None
    text = installed.read_text("direct_url.json")
    if not text:
        return None
    try:
        revision = (json.loads(text).get("vcs_info") or {}).get("requested_revision") or ""
    except (ValueError, AttributeError):
        return None
    return revision[1:] if revision.startswith("v") and VERSION.match(revision[1:]) else None


def _existing_site(root: Path) -> dict[str, Any]:
    if not (root / "mkdocs.yml").is_file():
        return {}
    try:
        return project(root).config
    except ConfigError:
        return {}


def facts(start: Path, given: Arguments, installed: Distribution | None = None) -> Facts:
    top = _git(start, "rev-parse", "--show-toplevel")
    if top is None:
        raise Usage("not inside a git checkout; run `docs init` in the repository whose documentation it sets up")
    root = Path(top)
    remote = _git(root, "config", "--get", "remote.origin.url")
    if remote is None:
        raise Usage("the checkout has no `origin`; the repository's name and address are read from a remote on GitHub")
    match = GITHUB.match(remote)
    if not match:
        raise Usage(f"`origin` is {remote}, which is not on GitHub; the set-up publishes through GitHub")
    owner, name = match.group("owner"), match.group("name")
    if not SKILL_NAME.match(name):
        raise Usage(f"the repository's name `{name}` names its skill, and a skill's name is lower-case words joined by hyphens")

    site = _existing_site(root)
    description = given.description or site.get("site_description")
    if not description:
        raise Usage("no `--description`: the site's description is one sentence, and there is no mkdocs.yml to read it from")
    description = " ".join(str(description).split())
    site_url = given.site_url or site.get("site_url")
    if not site_url:
        raise Usage("no `--site-url`: the address the site will have, and there is no mkdocs.yml to read it from")
    site_url = str(site_url).rstrip("/") + "/"
    # What mkdocs.yml already says is the repository's own, and is taken as it is unless a part the
    # command writes would carry it (`plan` holds it then); what is given here is held now.
    if given.description:
        validate_description(description)
    if given.site_url:
        validate_site_url(site_url)

    release = given.release or tool_release(installed) or _release_in_place(root)
    if release is None and any(kept_here(root)):
        raise Usage(
            "no release of the documentation tool: it was not installed from a release tag and the repository names none; "
            "name one with `--release <major.minor.patch>`"
        )
    if release is not None and not VERSION.match(release):
        raise Usage(f"`--release {release}` is not a release: `major.minor.patch`, without a leading v")
    return Facts(root, name, owner, f"https://github.com/{owner}/{name}", description, site_url, release or "", given.force)


def validate_description(description: str) -> None:
    if not description.endswith(".") or len(description) >= MAX_DESCRIPTION:
        raise Usage(f"the description is one sentence under {MAX_DESCRIPTION} characters, ending with a full stop")


def validate_site_url(site_url: str) -> None:
    parts = urlsplit(site_url)
    plain = parts.scheme == "https" and parts.netloc and not parts.query and not parts.fragment and not re.search(r"\s", site_url)
    if not plain:
        raise Usage(f"the site's address is a plain `https://` address, with no query, fragment or space; `{site_url}` is not")


def _release_in_place(root: Path) -> str | None:
    settings = root / kept.SETTINGS
    return kept.release_of(settings.read_text(encoding="utf-8")) if settings.is_file() else None


# ── the plan ─────────────────────────────────────────────────────────────────


def _values(facts: Facts, root: Path) -> dict[str, str]:
    links = [f"marketplace/plugins/{facts.name}/.claude-plugin/plugin.json"]
    if (root / "README.md").is_file():
        links.insert(0, "README.md")
    skill = f"Use {facts.name}, and answer questions about it. {facts.description} Load it for any task that names {facts.name}."
    return {
        "name": facts.name,
        "owner": facts.owner,
        "repo_url": facts.repo_url,
        "repo_name": f"{facts.owner}/{facts.name}",
        "site_url": facts.site_url,
        "site_url_quoted": json.dumps(facts.site_url),
        "description": facts.description,
        "description_quoted": json.dumps(facts.description, ensure_ascii=False),
        "skill_description_quoted": json.dumps(skill, ensure_ascii=False),
        "release": facts.release,
        "site_link_files": "\n".join(f"        - {link}" for link in links),
    }


def owned_parts(name: str) -> list[tuple[str, str]]:
    """The parts the repository owns, in the set-up's order, with the template each is written from."""
    return [
        ("mkdocs.yml", "mkdocs.yml.tmpl"),
        ("docs/index.md", "index.md.tmpl"),
        ("docs/contributing/documentation.md", "writing-documentation.md.tmpl"),
        ("docs/reference/limitations.md", "limitations.md.tmpl"),
        ("docs/reference/glossary.md", "glossary.md.tmpl"),
        (f"tools/docs/skill/{name}/SKILL.md", "SKILL.md.tmpl"),
        (f"marketplace/plugins/{name}/.claude-plugin/plugin.json", "plugin.json.tmpl"),
    ]


def kept_here(root: Path) -> tuple[bool, bool]:
    """Whether the tool keeps (the build settings, the documentation job) in this repository: both,
    unless its `extra.docs.kept` says otherwise."""
    if not (root / "mkdocs.yml").is_file():
        return True, True
    settings = project(root).settings
    return settings.kept_settings, settings.kept_job


def plan(facts: Facts, root: Path) -> list[Write]:
    """Every file of the set-up and what will happen to it. Reads the repository; writes nothing."""
    keeps_settings, keeps_job = kept_here(root)
    writes: list[Write] = []
    for path, content in (kept.render(facts.name, facts.release) if keeps_settings or keeps_job else {}).items():
        if (path == kept.SETTINGS and not keeps_settings) or (path == kept.JOB and not keeps_job):
            continue
        file = root / path
        if not file.exists():
            action = WRITE
        elif file.read_text(encoding="utf-8") == content:
            action = CURRENT
        elif kept.is_ours(file) or facts.force:
            action = REWRITE
        else:
            action = REFUSE
        writes.append(Write(path, content, KEPT, action))
    values = _values(facts, root)
    for path, source in owned_parts(facts.name):
        content = kept.fill(kept.template(source), values)
        writes.append(Write(path, content, OWNED, LEAVE if (root / path).exists() else WRITE))
    if any(w.ownership == OWNED and w.action == WRITE for w in writes):
        # A page about to be written carries the description and the address, so they must pass the check.
        validate_description(facts.description)
        validate_site_url(facts.site_url)
    return writes


def apply(writes: list[Write], root: Path) -> bool:
    """Write the plan. Writes nothing, and says so, when any entry is refused."""
    if any(w.action == REFUSE for w in writes):
        return False
    for w in writes:
        if w.action in (WRITE, REWRITE):
            file = root / w.path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(w.content, encoding="utf-8")
    return True


# ── the report ───────────────────────────────────────────────────────────────


def refusal(writes: list[Write]) -> str:
    refused = [w.path for w in writes if w.action == REFUSE]
    return "\n".join(
        f"docs: {path} was not written by the documentation tool; nothing was written. "
        f"Run `docs init --force` to write the tool's {path} over it."
        for path in refused
    )


def report(writes: list[Write], facts: Facts) -> str:
    if not any(w.action in (WRITE, REWRITE) for w in writes):
        return "docs: the set-up is current; nothing written"
    lines = [f"{VERBS[w.action]:<9}{w.path}" for w in writes]
    host = facts.site_url.removeprefix("https://").split("/")[0]
    lines += ["Left for a person:", "  - run `uv run --project tools/docs docs sync`, then commit"]
    if any(w.path == kept.JOB for w in writes):
        lines += [
            '  - turn the site on: the repository\'s Settings, Pages, Source "GitHub Actions"',
            '  - let release tags publish: Settings, Environments, github-pages, Deployment branches and tags, add the tag rule "v*"',
            f"  - point {host} at GitHub Pages: a DNS CNAME, and the custom domain in the same settings",
        ]
    lines += [
        "  - add to the Justfile:",
        "      docs:",
        "          uv run --project tools/docs docs build",
        "      docs-sync:",
        "          uv run --project tools/docs docs sync",
        "      docs-serve:",
        "          uv run --project tools/docs docs serve",
        f"  - the skill is published by this repository's own release: push marketplace/plugins/{facts.name}/ from it",
    ]
    navigation = next((w for w in writes if w.path == "mkdocs.yml"), None)
    settings = next((w for w in writes if w.path == kept.SETTINGS), None)
    if navigation is not None and navigation.action == LEAVE and settings is not None and settings.action == WRITE:
        lines.append("  - mkdocs.yml was left as it is: the build may not pass until its extra.docs names the skill and its targets")
    return "\n".join(lines)
