# Contract: `docs init`

```text
docs init [--description <sentence>] [--site-url <url>] [--release <major.minor.patch>] [--force]
```

Run at or below the root of a git checkout whose `origin` is on GitHub.

| Argument | Needed | Meaning |
|---|---|---|
| `--description` | when there is no `mkdocs.yml` | the site's one-sentence description |
| `--site-url` | when there is no `mkdocs.yml` | the address the site will have |
| `--release` | when the tool was not installed from a release tag and the repository has no build settings | the release of the tool the build settings name |
| `--force` | never | write over a file at a kept path that the tool did not write |

## Report (standard output)

One line per part, in the order of the set-up, then what is left to do:

```text
wrote    tools/docs/pyproject.toml
wrote    .github/workflows/docs.yml
left     mkdocs.yml
wrote    docs/index.md
…
Left for a person:
  - run `uv run --project tools/docs docs sync`, then commit
  - turn the site on: the repository's Settings, Pages, Source "GitHub Actions"
  - point <site-url's host> at GitHub Pages: a DNS CNAME, and the custom domain in the same settings
  - add to the Justfile:
      docs:
          uv run --project tools/docs docs build
      docs-sync:
          uv run --project tools/docs docs sync
      docs-serve:
          uv run --project tools/docs docs serve
  - the skill is published by this repository's own release: push marketplace/plugins/<name>/ from it
```

The verbs are `wrote`, `rewrote`, `left` and `current`. When nothing changed the first line is
`docs: the set-up is current; nothing written`.

## Exit codes

| Code | When |
|---|---|
| 0 | the plan was applied, or nothing needed writing |
| 2 | nothing was written: not a git checkout; no `origin` on GitHub; a name that cannot name a skill; a missing `--description` or `--site-url`; no release and none named; a foreign file at a kept path without `--force`. The message names what is missing or the file. |
