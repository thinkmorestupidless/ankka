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

from ankka_docs import cli
from ankka_docs.pages import project

MKDOCS = """\
site_name: widget
site_description: Widgets, as a service.
site_url: https://docs.widget.example/
docs_dir: docs
site_dir: build/site
nav:
  - Home: index.md
  - Install: install.md
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


def write(root: Path, path: str, text: str) -> None:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    write(tmp_path, "mkdocs.yml", MKDOCS)
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
    write(tmp_path, "mkdocs.yml", MKDOCS.replace("    release:\n      patterns: docs/release-patterns.txt\n      tags: \"v*\"\n", ""))
    assert project(tmp_path).settings.release is None
