# ankka marketplace

Claude Code plugins for [ankka](https://docs.ankka.cloud/), a serverless platform for agentic AI on the
actor model, and for what is built on it.

```text
/plugin marketplace add thinkmorestupidless/ankka-marketplace
/plugin install ankka@ankka
/plugin install pr-review@ankka
/plugin install satisfactory@ankka
/plugin install ankka-flow@ankka
```

The `ankka` plugin carries the platform's documentation as a set of Agent Skills, one per kind of task
(`ankka`, `ankka-design`, `ankka-inspect`, `ankka-port`, `ankka-entities`, `ankka-views-consumers`, `ankka-workflows`,
`ankka-agents`, `ankka-endpoints`, `ankka-python`, `ankka-typescript`, `ankka-go`, `ankka-rust`, `ankka-deploy`,
`ankka-platform`), and registers
the `ankka mcp` server, which gives an agent the CLI's verbs, the services running on your machine and
this version's documentation as tools. The `ankka` CLI must be on your `PATH` for the server to start:
`brew install thinkmorestupidless/tap/ankka`.

The `pr-review` plugin carries code review as skills, and needs nothing from ankka. `pr-review` reviews a
pull request, branch, commit range or working tree: it reads the repository's own instructions and
rule files first, collects the change with a bundled script, judges whether the change should exist,
reads every line against a general checklist in thirteen groups (correctness, design and scope, error
handling, concurrency, security, data and migrations, performance, tests, observability, API
compatibility, dependencies, documentation, commit hygiene), then loads one skill per language the diff
touches (`pr-review-scala`, `pr-review-java`, `pr-review-kotlin`, `pr-review-go`, `pr-review-python`,
`pr-review-rust`, `pr-review-typescript`) and reports findings labelled by severity with a verdict. Each
language skill says how to detect the dialect or version, what the language's tooling already enforces
so the review never repeats the compiler or linter, and carries its full sourced checklist under
`references/`. A repository customises the review without forking it: its `CLAUDE.md` and rule files
are read first and overrule the skills; a `REVIEW.md` is its own review checklist, whose `## pr-review`
section can skip a bundled checklist or load a further skill; and a project or personal skill named
`pr-review-<language>` replaces the bundled one.

The `satisfactory` plugin carries the documentation of
[satisfactory](https://satisfactory.ankka.cloud/), constraint solving as a service built on ankka, as
four skills: the service and its API (`satisfactory`), the Scala client (`satisfactory-client`), using
it from an ankka application (`satisfactory-ankka`), and writing a model (`satisfactory-models`).

The `ankka-flow` plugin carries the documentation of [ankka-flow](https://flow.ankka.cloud/), streaming
pipelines beside ankka, as four skills: designing pipelines and blueprints (`ankka-flow`), writing and
testing a streamlet in Python (`ankka-flow-python`), deploying and operating pipelines on Kubernetes
(`ankka-flow-deploy`), and implementing the streamlet protocol in another language
(`ankka-flow-protocol`).

This repository is generated. Each plugin is rendered from its own project's documentation and pushed
here by that project's release workflow on every tag, so a plugin's version is the release whose
documentation it holds: `plugins/ankka/` and `plugins/pr-review/` from
[thinkmorestupidless/ankka](https://github.com/thinkmorestupidless/ankka) (`marketplace/` there, which
also owns this README and the manifest's name), `plugins/satisfactory/` from
[thinkmorestupidless/satisfactory](https://github.com/thinkmorestupidless/satisfactory),
`plugins/ankka-flow/` from [thinkmorestupidless/ankka-flow](https://github.com/thinkmorestupidless/ankka-flow).
Changes go to those repositories, not here.
