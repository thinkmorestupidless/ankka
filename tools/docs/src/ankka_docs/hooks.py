"""MkDocs hooks the tool ships. A project lists this file under `hooks:` in its `mkdocs.yml`.

`on_page_markdown` rewrites each page's release forms to the release `docs build` names in the
environment, so the site names the release it was built for while the sources keep theirs. Without the
variable (`mkdocs serve`, or a project without `extra.docs.release`) it changes nothing.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from ankka_docs import release


def on_page_markdown(markdown: str, page: Any, config: Any, files: Any) -> str:
    version = os.environ.get(release.ENVIRONMENT)
    if not version:
        return markdown
    settings = ((config.get("extra") or {}).get("docs") or {}).get("release")
    if not isinstance(settings, dict) or "patterns" not in settings:
        return markdown
    root = Path(config["config_file_path"]).parent
    return release.rewrite(markdown, version, release.read_patterns(root / settings["patterns"]))
