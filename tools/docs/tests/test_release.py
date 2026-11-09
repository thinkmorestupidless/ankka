"""The release a documentation tree names: `latest.json` beside the site, the rewrite and the check.

The fixture is a repository that is not ankka, with releases of its own as git tags, so nothing here
depends on ankka's history. Each test is named after the living scenario it holds.
"""

from __future__ import annotations

import datetime
import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

from ankka_docs import check, cli, release
from ankka_docs.pages import load, project

HOOKS = Path(release.__file__).with_name("hooks.py")

MKDOCS = """\
site_name: widget
site_description: Widgets, as a service.
site_url: https://docs.widget.example/
docs_dir: docs
site_dir: build/site
nav:
  - Home: index.md
  - Install: install.md
hooks:
  - {hooks}
extra:
  docs:
    release:
      patterns: docs/release-patterns.txt
      tags: "v*"
"""

INDEX = """\
---
title: widget
description: What a widget is and where to start.
kind: concept
---

# widget

A widget is one thing. [Install it](install.md).
"""

INSTALL = """\
---
title: Install widget
description: Add the widget library to a project.
kind: guide
---

# Install widget

```bash
go get example.com/widget-go@v0.14.0
```
"""

PATTERNS = """\
# Where a page names a release of widget.
release widget-go@v{version}
other spec-kit.git@v
"""


def problems(root: Path) -> list[str]:
    return [str(p) for p in check.run(load(project(root)))]


def write(root: Path, path: str, text: str) -> None:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    write(tmp_path, "mkdocs.yml", MKDOCS.format(hooks=HOOKS))
    write(tmp_path, "docs/index.md", INDEX)
    write(tmp_path, "docs/install.md", INSTALL)
    write(tmp_path, "docs/release-patterns.txt", PATTERNS)
    git(tmp_path, "init", "-q")
    git(tmp_path, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "start")
    for tag in ("v0.14.0", "v0.14.1", "v0.16.0"):
        git(tmp_path, "tag", tag)
    return tmp_path


def build(root: Path, *args: str, monkeypatch: pytest.MonkeyPatch) -> int:
    monkeypatch.chdir(root)
    monkeypatch.setattr(sys, "argv", ["docs", "build", *args])
    with pytest.raises(SystemExit) as exited:
        cli.main()
    return int(exited.value.code or 0)


# ── features/documentation/release-named.feature ─────────────────────────────


def test_a_published_release_is_the_latest_release_beside_its_documentation(repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert build(repo, "--release", "0.17.0", monkeypatch=monkeypatch) == 0
    latest = json.loads((repo / "build/site/latest.json").read_text(encoding="utf-8"))
    assert latest == {"version": "0.17.0", "released": datetime.datetime.now(datetime.UTC).date().isoformat()}


def test_a_build_without_a_release_writes_no_latest_release(repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert build(repo, monkeypatch=monkeypatch) == 0
    assert not (repo / "build/site/latest.json").exists()


def test_a_release_that_is_not_three_numbers_is_refused(repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert build(repo, "--release", "v0.17.0-RC1", monkeypatch=monkeypatch) == 2
    assert not (repo / "build/site/latest.json").exists()


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/release.yml"


@pytest.mark.skipif(not WORKFLOW.is_file(), reason="the release workflow is ankka's")
def test_a_release_whose_libraries_were_not_published_leaves_the_latest_release_where_it_was() -> None:
    # The site, and latest.json in it, is written only by the job that builds with --release; that job
    # runs only on a release tag and only once `publish` (Maven Central) has succeeded, so a release
    # whose libraries never arrived publishes neither and the previous latest.json stays.
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    jobs = [
        (name, job)
        for name, job in workflow["jobs"].items()
        if any("docs build --release" in str(step.get("run", "")) for step in job.get("steps", []))
    ]
    assert len(jobs) == 1, f"one job builds the site for a release, found {[name for name, _ in jobs]}"
    _, job = jobs[0]
    needs = job.get("needs")
    assert "publish" in (needs if isinstance(needs, list) else [needs])
    assert "refs/tags/v" in str(job.get("if", ""))


def test_a_repository_without_release_settings_is_read_as_before(tmp_path: Path) -> None:
    write(tmp_path, "mkdocs.yml", MKDOCS.format(hooks=HOOKS).replace("    release:\n      patterns: docs/release-patterns.txt\n      tags: \"v*\"\n", ""))
    write(tmp_path, "docs/index.md", INDEX + "\nwidget 0.14.0 by hand, and no tags at all.\n")
    write(tmp_path, "docs/install.md", INSTALL)
    assert project(tmp_path).settings.release is None
    assert problems(tmp_path) == []


def test_a_page_that_names_a_release_of_ankka_names_the_release_the_documentation_was_built_for(
    repo: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert build(repo, "--release", "0.16.0", monkeypatch=monkeypatch) == 0
    site = repo / "build/site"
    for rendering in ("install/index.html", "install.md", "llms-full.txt"):
        text = (site / rendering).read_text(encoding="utf-8")
        assert "widget-go@v0.16.0" in text, rendering
        assert "widget-go@v0.14.0" not in text, rendering
    # A build never writes a source.
    assert "widget-go@v0.14.0" in (repo / "docs/install.md").read_text(encoding="utf-8")


def test_a_build_without_a_release_names_the_newest_release_tag(repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert build(repo, monkeypatch=monkeypatch) == 0
    assert "widget-go@v0.16.0" in (repo / "build/site/install/index.html").read_text(encoding="utf-8")


def test_a_page_that_names_a_release_of_ankka_by_hand_fails_the_documentations_check(repo: Path) -> None:
    assert problems(repo) == []
    write(repo, "docs/index.md", INDEX + "\nUpgrade from widget 0.14.0 first.\n")
    found = problems(repo)
    assert len(found) == 1
    assert found[0].startswith("docs/index.md:11: names release 0.14.0 by hand")


def test_a_page_that_names_a_release_of_another_project_is_not_refused(repo: Path) -> None:
    write(repo, "docs/index.md", INDEX + "\n`uv tool install specify-cli --from git+https://github.com/github/spec-kit.git@v0.14.1`\n")
    assert problems(repo) == []


def test_numbers_that_are_not_a_release_are_not_refused(repo: Path) -> None:
    write(repo, "docs/index.md", INDEX + "\nAt 127.0.0.1, `0.14.0-SNAPSHOT` and `0.14.0+3-abc` and 10.14.0 and 0.14.0.1.\n")
    assert problems(repo) == []


def test_sync_rewrites_the_sources_to_the_newest_release_tag(repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(repo)
    assert cli.run_sync(project(repo)) == 0
    assert "widget-go@v0.16.0" in (repo / "docs/install.md").read_text(encoding="utf-8")


def test_a_checkout_without_the_tags_fails_the_check_rather_than_checking_nothing(repo: Path) -> None:
    for tag in ("v0.14.0", "v0.14.1", "v0.16.0"):
        git(repo, "tag", "-d", tag)
    found = problems(repo)
    assert len(found) == 1
    assert "no git tag matches 'v*'" in found[0]
    assert found[0].startswith("docs/release-patterns.txt:1:")


def test_a_patterns_file_the_tool_cannot_use_is_a_problem(repo: Path) -> None:
    write(repo, "docs/release-patterns.txt", "release widget-go@v\n")
    assert "holds `{version}` once" in problems(repo)[0]


def test_an_other_prefix_survives_its_trailing_space_being_trimmed(repo: Path) -> None:
    write(repo, "docs/release-patterns.txt", PATTERNS + "other from version\n")
    write(repo, "docs/index.md", INDEX + "\nA rule that holds from version 0.14.0 on.\n")
    assert problems(repo) == []


ANKKA = Path(__file__).resolve().parents[3]


@pytest.mark.skipif(not (ANKKA / "docs/reference/cli.md").is_file(), reason="the command line's page is ankka's")
def test_the_documentation_of_the_command_line_says_when_the_notice_is_given_and_how_to_turn_the_check_off() -> None:
    from ankka_docs.render import page_markdown

    page = load(project(ANKKA)).pages["reference/cli.md"]
    text = page_markdown(page, "https://docs.ankka.cloud/")
    section = text.split("## When a newer release exists", 1)[1].split("\n## ", 1)[0]
    # When it is given, and when it is not.
    for said in ("ankka version Y is available (you're currently using version X)", "at most once a day", "`ankka version` reads every time", "-o json", "not at a terminal", "`CI`", "development build", "plugin"):
        assert said in section, said
    # How to turn it off.
    assert "`ANKKA_NO_UPDATE_CHECK=1`, which turns the check off" in section
