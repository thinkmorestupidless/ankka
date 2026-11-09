"""The release a documentation tree is built for.

`latest.json`, written beside the site by `docs build --release <version>`, says which release is the
newest and the day it was released; whatever tells a person or a model that a newer release exists
reads it.
"""

from __future__ import annotations

import datetime
import json
import re
from pathlib import Path

VERSION = re.compile(r"\d+\.\d+\.\d+")


def is_version(text: str) -> bool:
    """Three numbers, without a `v`: the form a release is written in everywhere this module writes one."""
    return VERSION.fullmatch(text) is not None


def write_latest(site: Path, version: str, today: datetime.date | None = None) -> Path:
    """`latest.json` in the site directory: the release, and the UTC date it was built on."""
    released = today or datetime.datetime.now(datetime.UTC).date()
    target = site / "latest.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"version": version, "released": released.isoformat()}) + "\n", encoding="utf-8")
    return target
