# Contract: the command line

Two options on `ankka init`, one new command group. Each has a hand-written section in
`docs/reference/cli.md`, and the generated table is rewritten by `just docs-reference`.

## `ankka init <name> --project --services <a>,<b>[,…] [--language <lang>] [--package <pkg>] [--dir <path>]`

Renders a project at `<dir>/<name>` (contracts/project-layout.md).

| Rule | Message on refusal |
|---|---|
| `--services` is required with `--project` | `--project needs --services with at least two service names` |
| at least two names, each a valid service name, all distinct, none equal to `shared` or `tests` | the descriptor's name rule; `the services must be distinct`; `"shared" and "tests" are the project's own directories` |
| `<name>` is a valid project id | the project rule |
| the target directory is empty or absent | `<target> already exists and is not empty` (as today) |
| `--language` is one of the eight (Scala default) and applies to every initial service | as today |
| `--template` is refused with `--project` | `--template applies to a single Scala service` |
| `--add` with `--project` | `--add adds a service to a project; --project starts one` |

On success, prints the project's next steps: `cd <name>`, `docker compose up -d`,
`scripts/run.sh <a>`, `sbt test`, `ankka context-map check`, and, when `specify` was not on PATH,
first `specify init --here --ai claude --ai-skills`. When `specify` is on PATH, runs it in the
project before printing (its output inherited), and a non-zero exit is reported as `specify init
exited with <code>` after the project has been written.

## `ankka init <service> [--add] [--language <lang>] [--package <pkg>] [--dir <path>]`

When `<dir>` (default `.`) holds `context-map.json`: adds the service. When it does not and
`--add` was given: refuses. When it does not and `--add` was not given: exactly today's behaviour.

| Rule | Message on refusal |
|---|---|
| `--add` outside a project | `<dir> holds no project started from the project template (no context-map.json); nothing was written` |
| `<service>` already a service of the project, or a directory under `services/` | `<project> already has a service "<service>"` |
| `<service>` is `shared` or `tests` | as above |
| the project's `--language` applies only to this service | n/a |

Writes `services/<service>/` from the language's template without the project-owned parts
(contracts/project-layout.md), with `service.json`'s `port` the smallest integer from 9000 that no
other service's descriptor uses; appends the service to `context-map.json` (owns and why empty)
and re-renders `CONTEXT-MAP.md`; prints the service's next steps (the language's run and test
commands as today, prefixed with the directory) and `complete the context map's entry for
"<service>" (owns, why) and record its crossings`.

Nothing else in the project is written: no workflow, no build file, no compose change.

## `ankka context-map check [--dir <path>]`, `ankka context-map render [--dir <path>]`

contracts/context-map.md. Both are local commands: no control plane, no login, no `-p`.

## Exit codes

As the CLI's: 0 success, 1 a refusal the person can act on (every line above), 2 usage.

## `TemplateSwitch`

`-Dankka.template.tests` gains the word `project`; named, `ProjectTemplateSuite` fails rather than
skips without `sbt` and Docker on PATH; unset, it runs when they are present; `off` skips it.
`templateArtifacts` publishes the local artifacts for `project` as it does for `scala`.
