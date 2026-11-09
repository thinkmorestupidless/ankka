# Contract: the docs tool's release handling

## Config

```yaml
extra:
  docs:
    release:
      patterns: docs/release-patterns.txt   # relative to mkdocs.yml; absent → the feature is off
      tags: v*                              # the git tags that are releases of this repository
```

A repository without `release` (ankka-flow, satisfactory, ankka-reasoning take the tool from `main`)
behaves exactly as before.

## Commands

| Command | Release used | Effect |
|---|---|---|
| `docs check` | n/a | fails on an occurrence of a release tag's version that no `release` or `other` line covers: `<page>:<line>: names ankka release 0.14.0 by hand; use a form docs/release-patterns.txt lists` |
| `docs sync` | the newest tag matching `tags` | rewrites each `release` occurrence in the page sources |
| `docs build` | the newest tag | rewrites into the built site only (sources untouched), then MkDocs |
| `docs build --release X` | `X` | as above, and writes `latest.json` (`latest-release.md`) |

The built site is rewritten by a MkDocs hook the tool ships (`ankka_docs.hooks`, its
`on_page_markdown`; MkDocs takes a hook by its file path, so `mkdocs.yml` lists `tools/docs/src/ankka_docs/hooks.py` under `hooks:`), given the release through the
environment `docs build` sets; `llms.txt`, `llms-full.txt`, the raw pages and `docs-index.json`
apply the same rewrite in `render.write`. The sources are never written by a build, so it never
dirties the tree (a dirty tree changes dynver's version, `build.sbt` line 61).

## The CLI's bundled pages

`build.sbt`'s `ankka/docs/` generator reads the same file and applies the `release` lines with the
CLI's release: `version` on a tag, `previousStableVersion` otherwise.
