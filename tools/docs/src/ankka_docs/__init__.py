"""ankka's documentation tooling.

One source tree, `docs/`, of plain Markdown with YAML frontmatter. Everything else is a rendering of
it: the site (MkDocs), `llms.txt` and `llms-full.txt`, a JSON index of every page, raw Markdown per
page, and the agent skill. A new consumer is a new renderer here, never a second source.
"""
