# Data model: Setting up a repository's documentation

Nothing is stored. These are the values the command passes between its steps.

## Facts

What the command knows about the repository before it plans.

| Field | From | Rule |
|---|---|---|
| `root` | `git rev-parse --show-toplevel` | must exist |
| `name` | last segment of `remote.origin.url` | lower-case words joined by hyphens, since it names the skill |
| `repo_url` | the remote, as `https://github.com/<owner>/<name>` | host must be `github.com` |
| `description` | `--description`, else `site_description` of an existing `mkdocs.yml` | one sentence ending in a full stop, under 240 characters |
| `site_url` | `--site-url`, else `site_url` of an existing `mkdocs.yml` | `https://`, ending in `/` |
| `release` | `--release`, else how the tool was installed, else the existing build settings | `major.minor.patch`; never a branch |

## Write

One file of the set-up, and what the command will do with it.

| Field | Values |
|---|---|
| `path` | relative to `root` |
| `content` | the rendered template |
| `ownership` | `kept` or `owned` |
| `action` | `write` (absent), `leave` (owned and present), `rewrite` (kept and present, the tool's), `current` (kept and already equal), `refuse` (kept path, not the tool's, no `--force`) |

A plan with any `refuse` is not applied.

## The set-up

| Path | Ownership |
|---|---|
| `tools/docs/pyproject.toml` | kept (the build settings) |
| `.github/workflows/docs.yml` | kept (the documentation job) |
| `mkdocs.yml` | owned |
| `docs/index.md` | owned |
| `docs/contributing/documentation.md` | owned |
| `docs/reference/limitations.md` | owned |
| `docs/reference/glossary.md` | owned |
| `tools/docs/skill/<name>/SKILL.md` | owned |
| `marketplace/plugins/<name>/.claude-plugin/plugin.json` | owned |

`tools/docs/uv.lock` and the rendered skill under `marketplace/plugins/<name>/skills/` are written by
`uv` and by `docs sync`, not by the command; the report says to run `docs sync` once.

## Settings

`extra.docs.kept`, a mapping with two optional booleans, both true when absent: `settings` and
`job`. False means the tool keeps that part nowhere in this repository.
