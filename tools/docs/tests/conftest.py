"""Fixtures shared by the tool's suites."""

from __future__ import annotations

import subprocess
from collections.abc import Callable
from pathlib import Path

import pytest


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout


@pytest.fixture
def repository(tmp_path: Path) -> Callable[[str], Path]:
    """`repository("widget")`: an empty git checkout whose `origin` is `github.com/example/widget`."""

    def make(name: str) -> Path:
        root = tmp_path / name
        root.mkdir()
        git(root, "init", "-q")
        git(root, "remote", "add", "origin", f"git@github.com:example/{name}.git")
        return root

    return make
