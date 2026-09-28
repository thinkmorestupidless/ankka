# ankka marketplace

Claude Code plugins for [ankka](https://docs.ankka.cloud/), a serverless platform for agentic AI on the
actor model, and for what is built on it.

```text
/plugin marketplace add thinkmorestupidless/ankka-marketplace
/plugin install ankka@ankka
/plugin install satisfactory@ankka
/plugin install ankka-flow@ankka
```

The `ankka` plugin carries the platform's documentation as a set of Agent Skills, one per kind of task
(`ankka`, `ankka-design`, `ankka-entities`, `ankka-views-consumers`, `ankka-workflows`, `ankka-agents`,
`ankka-endpoints`, `ankka-python`, `ankka-typescript`, `ankka-deploy`, `ankka-platform`), and registers
the `ankka mcp` server, which gives an agent the CLI's verbs, the services running on your machine and
this version's documentation as tools. The `ankka` CLI must be on your `PATH` for the server to start:
`brew install thinkmorestupidless/tap/ankka`.

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
documentation it holds: `plugins/ankka/` from
[thinkmorestupidless/ankka](https://github.com/thinkmorestupidless/ankka) (`marketplace/` there, which
also owns this README and the manifest's name), `plugins/satisfactory/` from
[thinkmorestupidless/satisfactory](https://github.com/thinkmorestupidless/satisfactory),
`plugins/ankka-flow/` from [thinkmorestupidless/ankka-flow](https://github.com/thinkmorestupidless/ankka-flow).
Changes go to those repositories, not here.
