# Contract: the kept parts

Both open with the same comment line, which is how the tool knows a file is its own. `<name>` and
`<release>` are the only things that vary.

## `tools/docs/pyproject.toml`

```toml
# Kept by ankka's documentation tool (`docs init`). Edits are written over.
# The documentation build. Everything specific to this repository is `extra.docs` in mkdocs.yml.
[project]
name = "<name>-docs"
version = "0.0.0"
description = "Builds <name>'s documentation with ankka's documentation tool."
requires-python = ">=3.12"
dependencies = [
    "ankka-docs @ git+https://github.com/thinkmorestupidless/ankka@v<release>#subdirectory=tools/docs",
]
```

## `.github/workflows/docs.yml`

```yaml
# Kept by ankka's documentation tool (`docs init`). Edits are written over.
# The documentation: checked on every pull request, published to GitHub Pages when a release is tagged.
name: docs
on:
  pull_request:
  push:
    tags: ['v*']
  workflow_dispatch:
jobs:
  docs:
    uses: thinkmorestupidless/ankka/.github/workflows/documentation.yml@v<release>
    permissions:
      contents: read
      pages: write
      id-token: write
```

## `documentation.yml` in ankka (`on: workflow_call`)

| Event in the caller | What runs | Publishes |
|---|---|---|
| a pull request | `docs build` | nothing |
| `workflow_dispatch` | `docs build` | nothing |
| a tag `v*` with no hyphen | `docs build --release <tag without v>`, the Pages artifact, the deploy | the site |
| a tag `v*` with a hyphen | `docs build` | nothing |

It takes no input and reads no secret. The checkout fetches tags.

## The check

`docs check` reports, for each kept part the repository's settings do not disclaim:

```text
tools/docs/pyproject.toml:1: the build settings differ from what release <release> of the documentation tool writes; run `docs init`
.github/workflows/docs.yml:1: the documentation job differs from what release <release> of the documentation tool writes; run `docs init`
.github/workflows/docs.yml:1: the documentation job is missing; run `docs init`, or say `kept: {job: false}` under extra.docs
tools/docs/pyproject.toml:1: the build settings name no release of the documentation tool; run `docs init --release <major.minor.patch>`
```
