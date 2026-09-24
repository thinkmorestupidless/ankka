# ankka marketplace

Claude Code plugins for [ankka](https://docs.ankka.cloud/), a serverless platform for agentic AI on the
actor model.

```text
/plugin marketplace add thinkmorestupidless/ankka-marketplace
/plugin install ankka@ankka
```

The `ankka` plugin carries the platform's documentation as a set of Agent Skills, one per kind of task
(`ankka`, `ankka-design`, `ankka-entities`, `ankka-views-consumers`, `ankka-workflows`, `ankka-agents`,
`ankka-endpoints`, `ankka-python`, `ankka-deploy`, `ankka-platform`), and registers the `ankka mcp`
server, which gives an agent the CLI's verbs, the services running on your machine and this version's
documentation as tools. The `ankka` CLI must be on your `PATH` for the server to start.

This repository is generated. Its content is rendered from the documentation in
[thinkmorestupidless/ankka](https://github.com/thinkmorestupidless/ankka) (`marketplace/` there) and
pushed here by the release workflow on every tag, so each version of the plugin holds the documentation of
that ankka version. Changes go to the ankka repository, not here.
