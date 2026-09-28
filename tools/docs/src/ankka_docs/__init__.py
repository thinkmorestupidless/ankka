"""Documentation tooling for one tree of Markdown, rendered many ways.

One source tree of plain Markdown with YAML frontmatter. Everything else is a rendering of it: the site
(MkDocs), `llms.txt` and `llms-full.txt`, a JSON index of every page, raw Markdown per page, and the
agent skills. A new consumer is a new renderer here, never a second source.

The tool belongs to no one repository. It finds the project it is run in by the `mkdocs.yml` above the
working directory, and everything that names a file, a vocabulary or a target in that repository is read
from `extra.docs` in that file — see `pages.Settings`. ankka is one project that uses it; another
repository takes it as a dependency and writes its own `extra.docs`.
"""
