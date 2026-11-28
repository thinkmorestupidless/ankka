"""`docs init` and the kept parts: one test per scenario of `features/documentation/setting-up.feature`,
named by the scenario, and the cases the scenarios rest on, against temporary git repositories."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from collections.abc import Callable
from importlib.resources import files
from pathlib import Path

import pytest
import yaml

from ankka_docs import check, cli, kept, setup, skill
from ankka_docs.pages import ConfigError, load, project

ANKKA = Path(__file__).resolve().parents[3]
CONTRACT = ANKKA / "specs/068-documentation-setup/contracts/kept-parts.md"
INIT_CONTRACT = ANKKA / "specs/068-documentation-setup/contracts/docs-init.md"

TEMPLATES = {
    "pyproject.toml.tmpl",
    "docs.yml.tmpl",
    "mkdocs.yml.tmpl",
    "index.md.tmpl",
    "writing-documentation.md.tmpl",
    "limitations.md.tmpl",
    "glossary.md.tmpl",
    "SKILL.md.tmpl",
    "plugin.json.tmpl",
}

DESCRIPTION = "Widgets, as a service."
SITE = "https://docs.widget.example/"
WIDGET = ["--description", DESCRIPTION, "--site-url", SITE, "--release", "0.19.0"]

OWNED_PAGES = [
    "mkdocs.yml",
    "docs/index.md",
    "docs/contributing/documentation.md",
    "docs/reference/limitations.md",
    "docs/reference/glossary.md",
    "tools/docs/skill/widget/SKILL.md",
    "marketplace/plugins/widget/.claude-plugin/plugin.json",
]
SET_UP = [kept.SETTINGS, kept.JOB, *OWNED_PAGES]


def init(root: Path, argv: list[str], capsys: pytest.CaptureFixture[str]) -> tuple[int, str, str]:
    status = cli.run_init(argv, root)
    out = capsys.readouterr()
    return status, out.out, out.err


def snapshot(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file() and ".git" not in p.parts}


def problems(root: Path) -> list[str]:
    current = project(root)
    tree = load(current)
    return [str(p) for p in check.run(tree) + skill.check(tree) + kept.check(current)]


@pytest.fixture
def widget(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> Path:
    """`widget`, set up with release 0.19.0 and synced."""
    root = repository("widget")
    assert init(root, WIDGET, capsys)[0] == 0
    skill.sync(load(project(root)))
    return root


# ── setup ────────────────────────────────────────────────────────────────────


def test_the_wheel_carries_the_templates() -> None:
    listed = {entry.name for entry in (files("ankka_docs") / "templates").iterdir()}
    assert listed == TEMPLATES


def test_the_built_wheel_carries_the_templates(tmp_path: Path) -> None:
    subprocess.run(["uv", "build", "--wheel", "--out-dir", str(tmp_path), str(ANKKA / "tools/docs")], check=True, capture_output=True)
    import zipfile

    (wheel,) = tmp_path.glob("*.whl")
    names = {Path(n).name for n in zipfile.ZipFile(wheel).namelist() if "/templates/" in n}
    assert names == TEMPLATES


# ── facts ────────────────────────────────────────────────────────────────────


def test_the_root_is_the_git_top_level_from_a_subdirectory(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    (root / "src" / "deep").mkdir(parents=True)
    facts = setup.facts(root / "src" / "deep", setup.Arguments(DESCRIPTION, SITE, "0.19.0"))
    assert facts.root.resolve() == root.resolve()
    assert (facts.name, facts.owner, facts.repo_url) == ("widget", "example", "https://github.com/example/widget")


@pytest.mark.parametrize("remote", ["git@github.com:example/widget.git", "https://github.com/example/widget.git", "https://github.com/example/widget", "ssh://git@github.com/example/widget.git"])
def test_every_form_of_a_github_remote_gives_the_same_address(repository: Callable[[str], Path], remote: str) -> None:
    root = repository("widget")
    subprocess.run(["git", "remote", "set-url", "origin", remote], cwd=root, check=True)
    assert setup.facts(root, setup.Arguments(DESCRIPTION, SITE, "0.19.0")).repo_url == "https://github.com/example/widget"


def test_no_checkout_is_a_usage_error(tmp_path: Path) -> None:
    outside = tmp_path / "plain"
    outside.mkdir()
    with pytest.raises(setup.Usage, match="not inside a git checkout"):
        setup.facts(outside, setup.Arguments(DESCRIPTION, SITE, "0.19.0"))


def test_no_origin_a_remote_elsewhere_and_a_name_no_skill_can_have_are_usage_errors(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    arguments = setup.Arguments(DESCRIPTION, SITE, "0.19.0")
    subprocess.run(["git", "remote", "set-url", "origin", "git@gitlab.com:example/widget.git"], cwd=root, check=True)
    with pytest.raises(setup.Usage, match="not on GitHub"):
        setup.facts(root, arguments)
    subprocess.run(["git", "remote", "set-url", "origin", "git@github.com:example/Widget_Two.git"], cwd=root, check=True)
    with pytest.raises(setup.Usage, match="lower-case words joined by hyphens"):
        setup.facts(root, arguments)
    subprocess.run(["git", "remote", "remove", "origin"], cwd=root, check=True)
    with pytest.raises(setup.Usage, match="no `origin`"):
        setup.facts(root, arguments)


def test_the_description_and_address_are_needed_unless_mkdocs_yml_has_them(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    with pytest.raises(setup.Usage, match="no `--description`"):
        setup.facts(root, setup.Arguments(None, SITE, "0.19.0"))
    with pytest.raises(setup.Usage, match="no `--site-url`"):
        setup.facts(root, setup.Arguments(DESCRIPTION, None, "0.19.0"))
    with pytest.raises(setup.Usage, match="ending with a full stop"):
        setup.facts(root, setup.Arguments("Widgets", SITE, "0.19.0"))
    (root / "mkdocs.yml").write_text(f"site_name: widget\nsite_description: {DESCRIPTION}\nsite_url: https://docs.widget.example\n")
    facts = setup.facts(root, setup.Arguments(release="0.19.0"))
    assert (facts.description, facts.site_url) == (DESCRIPTION, SITE)


def test_what_mkdocs_yml_already_says_is_taken_as_it_is_unless_a_page_would_carry_it(widget: Path, capsys: pytest.CaptureFixture[str]) -> None:
    long = "Widgets, " + "and more widgets, " * 15 + "as a service."
    assert len(long) > 240
    mkdocs = widget / "mkdocs.yml"
    mkdocs.write_text(mkdocs.read_text().replace(json.dumps(DESCRIPTION), json.dumps(long)))
    status, out, _ = init(widget, ["--release", "0.20.0"], capsys)
    assert status == 0, "a repository whose description the check would refuse still takes the kept parts"
    assert f"rewrote  {kept.SETTINGS}" in out.splitlines()

    (widget / "docs/index.md").unlink()
    before = snapshot(widget)
    status, _, err = init(widget, [], capsys)
    assert status == 2 and "under 240 characters" in err, "a page about to be written would carry it"
    assert snapshot(widget) == before


@pytest.mark.parametrize("address", ["https://docs.widget.example/#top", "https://docs.widget.example/?a: b", "http://docs.widget.example/", "https://docs widget.example/"])
def test_an_address_that_is_not_a_plain_https_address_is_refused(repository: Callable[[str], Path], address: str) -> None:
    with pytest.raises(setup.Usage, match="plain `https://` address"):
        setup.facts(repository("widget"), setup.Arguments(DESCRIPTION, address, "0.19.0"))


def test_a_description_that_holds_a_template_marker_is_written_as_it_is(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    odd = "Widgets by @@name@@ and @@nobody@@, as a service."
    status, _, err = init(root, ["--description", odd, "--site-url", SITE, "--release", "0.19.0"], capsys)
    assert status == 0, err
    assert yaml.safe_load((root / "mkdocs.yml").read_text())["site_description"] == odd
    assert odd in (root / "docs/index.md").read_text()


def test_an_option_with_no_value_says_so(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    status, _, err = init(repository("widget"), ["--description"], capsys)
    assert status == 2 and "`--description` needs a value" in err


def test_a_repository_that_keeps_neither_part_needs_no_release(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    (root / "mkdocs.yml").write_text(f"site_name: widget\nsite_description: {DESCRIPTION}\nsite_url: {SITE}\nnav: []\nextra:\n  docs:\n    kept: {{settings: false, job: false}}\n")
    status, out, err = init(root, [], capsys)
    assert status == 0, err
    assert not (root / kept.SETTINGS).exists() and not (root / kept.JOB).exists()
    assert "wrote    docs/index.md" in out.splitlines()


# ── the release ──────────────────────────────────────────────────────────────


class FakeDistribution:
    def __init__(self, direct_url: dict | None) -> None:
        self.direct_url = direct_url

    def read_text(self, filename: str) -> str | None:
        assert filename == "direct_url.json"
        return None if self.direct_url is None else json.dumps(self.direct_url)


def installed_at(revision: str) -> FakeDistribution:
    return FakeDistribution({"url": "https://github.com/thinkmorestupidless/ankka", "vcs_info": {"vcs": "git", "commit_id": "4f8a14dc564c8e1778c956d4256e4a67ff5c02f5", "requested_revision": revision}, "subdirectory": "tools/docs"})


def test_the_release_is_the_tag_the_tool_was_installed_from() -> None:
    assert setup.tool_release(installed_at("v0.19.0")) == "0.19.0"
    assert setup.tool_release(installed_at("main")) is None
    assert setup.tool_release(installed_at("4f8a14dc564c8e1778c956d4256e4a67ff5c02f5")) is None
    assert setup.tool_release(FakeDistribution(None)) is None
    assert setup.tool_release(FakeDistribution({"url": "file:///src/ankka/tools/docs", "dir_info": {"editable": True}})) is None


def test_the_release_is_the_one_named_else_installed_else_in_place(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    given = setup.Arguments(DESCRIPTION, SITE)
    assert setup.facts(root, setup.Arguments(DESCRIPTION, SITE, "0.21.0"), installed_at("v0.19.0")).release == "0.21.0"
    assert setup.facts(root, given, installed_at("v0.19.0")).release == "0.19.0"
    with pytest.raises(setup.Usage, match="name one with `--release <major.minor.patch>`"):
        setup.facts(root, given, installed_at("main"))
    (root / "tools/docs").mkdir(parents=True)
    (root / kept.SETTINGS).write_text(kept.render("widget", "0.18.0")[kept.SETTINGS])
    assert setup.facts(root, given, installed_at("main")).release == "0.18.0"


# ── the kept parts ───────────────────────────────────────────────────────────


def contract_block(after: str, language: str) -> str:
    text = CONTRACT.read_text()
    start = text.index(after)
    block = re.search(rf"```{language}\n(.*?)```", text[start:], re.DOTALL)
    assert block
    return block.group(1)


def test_the_kept_parts_are_the_contracts_byte_for_byte() -> None:
    rendered = kept.render("widget", "0.19.0")
    assert rendered[kept.SETTINGS] == contract_block("## `tools/docs/pyproject.toml`", "toml").replace("<name>", "widget").replace("<release>", "0.19.0")
    assert rendered[kept.JOB] == contract_block("## `.github/workflows/docs.yml`", "yaml").replace("<name>", "widget").replace("<release>", "0.19.0")


def test_two_repositories_kept_parts_differ_only_where_the_name_is() -> None:
    one, other = kept.render("widget", "0.19.0"), kept.render("gadget", "0.19.0")
    assert one[kept.JOB] == other[kept.JOB]
    assert one[kept.SETTINGS].replace("widget", "gadget") == other[kept.SETTINGS]


# ── the plan ─────────────────────────────────────────────────────────────────


def test_the_plan_for_an_empty_repository_is_nine_writes_in_order(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    writes = setup.plan(setup.facts(root, setup.Arguments(DESCRIPTION, SITE, "0.19.0")), root)
    assert [(w.path, w.action) for w in writes] == [(p, setup.WRITE) for p in SET_UP]
    assert [w.ownership for w in writes] == [setup.KEPT] * 2 + [setup.OWNED] * 7


def test_the_plan_writes_nothing(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    facts = setup.facts(root, setup.Arguments(DESCRIPTION, SITE, "0.19.0"))
    os.chmod(root, 0o555)
    try:
        writes = setup.plan(facts, root)
    finally:
        os.chmod(root, 0o755)
    assert len(writes) == 9
    assert sorted(p.name for p in root.iterdir()) == [".git"]


def test_apply_writes_nothing_when_anything_is_refused(repository: Callable[[str], Path]) -> None:
    root = repository("widget")
    facts = setup.facts(root, setup.Arguments(DESCRIPTION, SITE, "0.19.0"))
    writes = setup.plan(facts, root)
    refused = [setup.Write(w.path, w.content, w.ownership, setup.REFUSE if w.path == kept.JOB else w.action) for w in writes]
    assert setup.apply(refused, root) is False
    assert sorted(p.name for p in root.iterdir()) == [".git"]


# ── User Story 1 ─────────────────────────────────────────────────────────────


def test_a_repository_with_no_documentation_is_set_up_by_one_command_and_its_documentation_builds(
    repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]
) -> None:
    root = repository("widget")
    status, out, _ = init(root, WIDGET, capsys)
    assert status == 0
    for path in SET_UP:
        assert (root / path).is_file(), path
    plugin = json.loads((root / "marketplace/plugins/widget/.claude-plugin/plugin.json").read_text())
    assert (plugin["name"], plugin["version"], plugin["description"], plugin["homepage"]) == ("widget", "0.0.0", DESCRIPTION, SITE)

    skill.sync(load(project(root)))
    assert problems(root) == []
    assert (root / "marketplace/plugins/widget/skills/widget/SKILL.md").is_file()

    # FR-009: the pages say they are a start.
    index = (root / "docs/index.md").read_text()
    assert "not written yet" in index
    limitations = (root / "docs/reference/limitations.md").read_text()
    assert len(re.findall(r"^## ", limitations, re.MULTILINE)) == 1

    built = subprocess.run([sys.executable, "-m", "mkdocs", "build", "--strict", "-f", str(root / "mkdocs.yml")], cwd=root, capture_output=True, text=True)
    assert built.returncode == 0, built.stderr
    assert (root / "target/docs-site/index.html").is_file()


def test_the_set_up_names_the_release_of_the_documentation_tool_that_made_it(widget: Path) -> None:
    assert kept.release_of((widget / kept.SETTINGS).read_text()) == "0.19.0"
    assert "documentation.yml@v0.19.0" in (widget / kept.JOB).read_text()
    for path, content in snapshot(widget).items():
        text = content.decode()
        assert "@main" not in text, path
        assert not re.search(r"thinkmorestupidless/ankka[^\s\"]*@(?!v(\d|<release>))", text), path


def test_no_release_named_and_none_installed_is_refused(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    status, out, err = init(root, WIDGET[:4], capsys)
    assert status == 2
    assert "name one with `--release <major.minor.patch>`" in err
    assert sorted(p.name for p in root.iterdir()) == [".git"]


def test_setting_up_leaves_what_the_repository_owns_as_it_is_and_names_it(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    own = f"site_name: widget\nsite_description: {DESCRIPTION}\nsite_url: {SITE}\n# ours\n"
    (root / "mkdocs.yml").write_text(own)
    status, out, _ = init(root, ["--release", "0.19.0"], capsys)
    assert status == 0
    assert (root / "mkdocs.yml").read_text() == own
    lines = out.splitlines()
    assert "left     mkdocs.yml" in lines
    for path in SET_UP:
        if path != "mkdocs.yml":
            assert f"wrote    {path}" in lines


def test_setting_up_a_repository_that_is_already_set_up_writes_only_what_is_missing(widget: Path, capsys: pytest.CaptureFixture[str]) -> None:
    (widget / "docs/reference/limitations.md").unlink()
    before = snapshot(widget)
    status, out, _ = init(widget, [], capsys)
    assert status == 0
    lines = out.splitlines()
    assert "wrote    docs/reference/limitations.md" in lines
    assert sum(line.startswith("wrote") for line in lines) == 1
    assert f"current  {kept.SETTINGS}" in lines and f"current  {kept.JOB}" in lines
    after = snapshot(widget)
    assert {k: v for k, v in after.items() if k != "docs/reference/limitations.md"} == before


def test_a_second_run_on_a_current_set_up_writes_nothing(widget: Path, capsys: pytest.CaptureFixture[str]) -> None:
    before = snapshot(widget)
    status, out, _ = init(widget, [], capsys)
    assert (status, out) == (0, "docs: the set-up is current; nothing written\n")
    assert snapshot(widget) == before


def left_for_a_person() -> list[str]:
    text = INIT_CONTRACT.read_text()
    block = re.search(r"```text\n(wrote.*?)```", text, re.DOTALL)
    assert block
    lines = block.group(1).splitlines()
    return lines[lines.index("Left for a person:") :]


def test_the_set_up_says_what_is_left_for_a_person_to_do(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    status, out, _ = init(root, WIDGET, capsys)
    expected = [line.replace("<site-url's host>", "docs.widget.example").replace("<name>", "widget") for line in left_for_a_person()]
    lines = out.splitlines()
    assert lines[lines.index("Left for a person:") :] == expected
    assert not (root / "Justfile").exists()


def test_a_justfile_is_never_changed(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    (root / "Justfile").write_text("test:\n    echo\n")
    init(root, WIDGET, capsys)
    assert (root / "Justfile").read_text() == "test:\n    echo\n"


# ── User Story 2 ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize("path", [kept.SETTINGS, kept.JOB])
def test_a_part_of_the_set_up_the_documentation_tool_keeps_that_was_changed_by_hand_fails_the_check(widget: Path, path: str) -> None:
    file = widget / path
    text = file.read_text()
    file.write_text(text.replace("contents: read", "contents: write") if path == kept.JOB else text.replace(">=3.12", ">=3.13"))
    part = "documentation job" if path == kept.JOB else "build settings"
    assert problems(widget) == [f"docs/../{path}:1: the {part} differ{'s' if path == kept.JOB else ''} from what release 0.19.0 of the documentation tool writes; run `docs init`"]


def test_a_repository_that_says_the_documentation_tool_keeps_no_documentation_job_there_is_held_to_none(widget: Path) -> None:
    mkdocs = widget / "mkdocs.yml"
    mkdocs.write_text(mkdocs.read_text().replace("  docs:\n", "  docs:\n    kept: {job: false}\n", 1))
    (widget / kept.JOB).unlink()
    assert problems(widget) == []


def test_a_missing_documentation_job_fails_the_check(widget: Path) -> None:
    (widget / kept.JOB).unlink()
    assert problems(widget) == ["docs/../.github/workflows/docs.yml:1: the documentation job is missing; run `docs init`, or say `kept: {job: false}` under extra.docs"]


def test_build_settings_naming_a_branch_fail_the_check(widget: Path) -> None:
    file = widget / kept.SETTINGS
    file.write_text(file.read_text().replace("@v0.19.0", "@main"))
    assert "docs/../tools/docs/pyproject.toml:1: the build settings name no release of the documentation tool; run `docs init --release <major.minor.patch>`" in problems(widget)


def test_the_check_messages_are_the_contracts() -> None:
    text = CONTRACT.read_text()
    block = re.search(r"## The check.*?```text\n(.*?)```", text, re.DOTALL)
    assert block
    source = (ANKKA / "tools/docs/src/ankka_docs/kept.py").read_text()
    for line in block.group(1).splitlines():
        message = line.split(":1: ", 1)[1].replace("<release>", "{release}")
        assert message in source, message


def test_a_part_of_the_set_up_the_documentation_tool_keeps_is_put_back_by_setting_up_again(widget: Path, capsys: pytest.CaptureFixture[str]) -> None:
    job = widget / kept.JOB
    job.write_text(job.read_text().replace("contents: read", "contents: write"))
    status, out, _ = init(widget, [], capsys)
    assert status == 0
    assert f"rewrote  {kept.JOB}" in out.splitlines()
    assert problems(widget) == []


def test_a_repository_that_builds_with_a_later_release_of_the_documentation_tool_takes_that_releases_kept_parts_by_setting_up_again(
    widget: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    before = snapshot(widget)
    status, out, _ = init(widget, ["--release", "0.20.0"], capsys)
    assert status == 0
    assert kept.release_of((widget / kept.SETTINGS).read_text()) == "0.20.0"
    assert "documentation.yml@v0.20.0" in (widget / kept.JOB).read_text()
    after = snapshot(widget)
    assert {k: v for k, v in after.items() if k not in (kept.SETTINGS, kept.JOB)} == {k: v for k, v in before.items() if k not in (kept.SETTINGS, kept.JOB)}
    assert problems(widget) == []


def test_a_file_at_a_kept_path_the_tool_did_not_write_stops_everything_until_forced(repository: Callable[[str], Path], capsys: pytest.CaptureFixture[str]) -> None:
    root = repository("widget")
    (root / ".github/workflows").mkdir(parents=True)
    (root / kept.JOB).write_text("name: docs\non: push\n")
    before = snapshot(root)
    status, out, err = init(root, WIDGET, capsys)
    assert status == 2
    assert ".github/workflows/docs.yml was not written by the documentation tool; nothing was written" in err
    assert "--force" in err
    assert snapshot(root) == before
    status, out, _ = init(root, [*WIDGET, "--force"], capsys)
    assert status == 0
    assert f"rewrote  {kept.JOB}" in out.splitlines()
    assert (root / kept.JOB).read_text() == kept.render("widget", "0.19.0")[kept.JOB]


def test_a_disclaimed_part_is_neither_written_nor_reported(widget: Path, capsys: pytest.CaptureFixture[str]) -> None:
    mkdocs = widget / "mkdocs.yml"
    mkdocs.write_text(mkdocs.read_text().replace("  docs:\n", "  docs:\n    kept: {job: false}\n", 1))
    (widget / kept.JOB).unlink()
    status, out, _ = init(widget, [], capsys)
    assert (status, out) == (0, "docs: the set-up is current; nothing written\n")
    assert not (widget / kept.JOB).exists()


# ── the reusable workflow ────────────────────────────────────────────────────


def workflow(path: Path) -> dict:
    loaded = yaml.safe_load(path.read_text())
    loaded["on"] = loaded.pop(True, loaded.get("on"))  # YAML 1.1 reads `on` as true
    return loaded


def evaluate(condition: str, event: str, ref: str) -> bool:
    """The conditions `documentation.yml` uses, evaluated for one event: GitHub's expression language
    for exactly the functions it calls."""
    expression = condition.removeprefix("${{").removesuffix("}}").strip()
    expression = expression.replace("github.event_name", repr(event)).replace("github.ref_name", repr(ref.removeprefix("refs/tags/"))).replace("github.ref", repr(ref))
    expression = re.sub(r"startsWith\(([^,]+), ([^)]+)\)", r"str(\1).startswith(\2)", expression)
    expression = re.sub(r"contains\(([^,]+), ([^)]+)\)", r"(\2 in str(\1))", expression)
    expression = expression.replace("&&", " and ").replace("||", " or ").replace("!(", " not (").replace("==", " == ").replace("!=", " != ")
    return bool(eval(expression, {"__builtins__": {"str": str}}))  # noqa: S307 - the expression is this repository's own


def jobs_run(event: str, ref: str) -> dict[str, list[str]]:
    flow = workflow(ANKKA / ".github/workflows/documentation.yml")
    ran: dict[str, list[str]] = {}
    for name, job in flow["jobs"].items():
        if "if" in job and not evaluate(job["if"], event, ref):
            continue
        steps = [s for s in job["steps"] if "if" not in s or evaluate(s["if"], event, ref)]
        ran[name] = [s.get("run") or s.get("uses", "") for s in steps]
    return ran


def test_a_set_up_repository_checks_its_documentation_on_a_pull_request_and_publishes_nothing(widget: Path) -> None:
    caller = workflow(widget / kept.JOB)
    assert "pull_request" in caller["on"] and caller["on"]["pull_request"] is None, "every pull request, whatever it touches"
    ran = jobs_run("pull_request", "refs/pull/1/merge")
    steps = [s for job in ran.values() for s in job]
    assert "uv run --project tools/docs docs build" in steps
    assert not any("pages" in s for s in steps)


@pytest.mark.parametrize(("tag", "published"), [("v0.2.0", True), ("v0.2.0-rc.1", False)])
def test_a_set_up_repository_publishes_its_documentation_when_a_release_is_tagged(widget: Path, tag: str, published: bool) -> None:
    caller = workflow(widget / kept.JOB)
    assert caller["on"]["push"] == {"tags": ["v*"]}
    assert "branches" not in caller["on"]["push"]
    ran = jobs_run("push", f"refs/tags/{tag}")
    steps = [s for job in ran.values() for s in job]
    assert any(s.startswith("actions/deploy-pages@") for s in steps) is published
    built = [s for s in steps if "docs build" in s]
    assert built == (['uv run --project tools/docs docs build --release "${GITHUB_REF_NAME#v}"'] if published else ["uv run --project tools/docs docs build"])


def test_the_reusable_workflow_takes_no_input_and_reads_no_secret() -> None:
    flow = workflow(ANKKA / ".github/workflows/documentation.yml")
    assert flow["on"] == {"workflow_call": None}
    text = (ANKKA / ".github/workflows/documentation.yml").read_text()
    assert "secrets" not in text and "inputs" not in text
    assert "fetch-depth: 0" in text
    release = (ANKKA / ".github/workflows/release.yml").read_text()
    uv = re.search(r"astral-sh/setup-uv@(\S+)", text)
    assert uv and f"astral-sh/setup-uv@{uv.group(1)}" in release, "the uv the release workflow uses"
