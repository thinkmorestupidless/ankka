"""`uv run conformance`, as a script: see `ankka._conformance`."""

from __future__ import annotations

import sys

from ankka._conformance import main

if __name__ == "__main__":
    sys.exit(main())
