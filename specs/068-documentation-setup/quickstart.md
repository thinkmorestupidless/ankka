# Quickstart: proving the set-up

Prerequisites: `uv`, `git`, and this branch checked out.

## 1. The tool's own suites

```bash
uv run --project tools/docs pytest tools/docs
uv run --project tools/docs docs build
```

Expected: every test passes, with one test per scenario of `features/documentation/setting-up.feature`
and `writing-documentation.feature`; ankka's own build reports no problems, with
`extra.docs.kept` disclaiming both parts.

## 2. An empty repository

```bash
mkdir /tmp/widget && cd /tmp/widget && git init -q && git remote add origin git@github.com:example/widget.git
uv run --project <this checkout>/tools/docs docs init \
  --description "Widgets, as a service." --site-url https://docs.widget.example/ --release 0.19.0
```

Expected: nine `wrote` lines and the list of what is left. Then, with the dependency pointed at this
checkout for the test (`uv run --project <this checkout>/tools/docs docs build` from `/tmp/widget`):
`docs: 4 pages, no problems` and a site in `target/docs-site`.

## 3. Held

Change one character of `/tmp/widget/.github/workflows/docs.yml`; `docs check` fails naming it.
`docs init`; the report says `rewrote .github/workflows/docs.yml`; `docs check` passes. Edit
`mkdocs.yml`; `docs init` says `left mkdocs.yml` and the edit is still there.

## 4. On GitHub, after the release that carries this

In ankka-surface: `uvx --from "git+https://github.com/thinkmorestupidless/ankka@v<release>#subdirectory=tools/docs" docs init --force`
(its `docs.yml` predates the tool), commit, open a pull request. Expected: the `docs` check runs
through ankka's `documentation.yml` and publishes nothing; the first `v*` tag publishes the site.

## 5. The skill

`marketplace/plugins/ankka/skills/ankka-docs/` and the template's copy exist after `docs sync`, each
with `references/reference/documentation-rules.md` and `references/build/documentation.md`.

## 6. An agent, told nothing

In a scratch repository with one small tested module and a GitHub remote, start a fresh agent session
with only the `ankka` plugin installed and say "document this repository". Expected: it loads
`ankka-docs`, runs `docs init`, and leaves pages `docs check` passes whose samples are includes of
the module's tests. Shown once, before the pull request (SC-005).
