---
title: Work with a coding agent
description: Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.
kind: guide
related: [reference/cli.md, operate/local-console.md, contributing/documentation.md]
---

# Work with a coding agent

A coding agent writes a correct ankka service when it has two things: the documentation of the ankka
version you are using, and a way to act on the platform and on the services running on your machine.
ankka ships both. The documentation comes as an Agent Skill, and the actions come as a Model Context
Protocol (MCP) server built into the CLI, `ankka mcp`.

## The skill

An Agent Skill is a directory an agent loads when a task needs it: a `SKILL.md` saying what it is for,
and reference files it opens on demand. ankka's skill holds a short set of rules that hold for every
ankka service, and every page of this documentation as a reference file. The samples in those pages are
copied from code the ankka build compiles and tests, so an agent that reads them writes current
signatures and imports.

**In a project made from the template**, the skill is already there. `ankka init` and
`sbt new thinkmorestupidless/ankka.g8` create `.claude/skills/ankka/` in the new project, holding the
documentation of the ankka version the project was created with. Claude Code loads skills from that
directory with no configuration. Other agents that read Agent Skills can be pointed at it.

**In any other project**, install the Claude Code plugin from this repository's marketplace:

```text
/plugin marketplace add thinkmorestupidless/ankka
/plugin install ankka@ankka
```

The plugin carries the same skill, and it registers `ankka mcp` as an MCP server, so the CLI must be on
your `PATH` for the tools to start. [Install the tools](install.md) covers building the CLI.

## The MCP server

`ankka mcp` is an MCP server over standard input and output. It offers three groups of tools, and the
documentation as resources.

| Group | Tools | Acts on |
|---|---|---|
| Control plane | `whoami`, `list_organizations`, `list_projects`, `list_services`, `get_service`, `service_history`, `service_logs`, `apply_service`, `expose_service`, `unexpose_service`, `pause_service`, `resume_service`, `restart_service`, `delete_service` | deployed services, as the logged-in user |
| This machine | `list_local_services`, `describe_local_service`, `local_traces`, `query_local_entity`, `call_local_endpoint`, `local_agent_session` | services running locally |
| Documentation | `search_docs`, `read_doc`, and every page as an `ankka://docs/<path>` resource | this CLI's version of the docs |

To use it from Claude Code without the plugin, add it once:

```bash
claude mcp add ankka -- ankka mcp
```

Any other MCP client starts it the same way. A client that takes a JSON configuration names the command
and its argument:

```json
{
  "mcpServers": {
    "ankka": { "command": "ankka", "args": ["mcp"] }
  }
}
```

`ankka mcp` takes the same `--url`, `--token` and `--project` options as every other command, and reads
the same environment variables and saved settings. It resolves them on every tool call rather than once
at start, so an `ankka login` or `ankka config set project` in another terminal takes effect on the next
call without restarting the client.

### What the tools can do

**The control plane tools do what the matching commands do, as you.** Each is one call to the control
plane with your saved login, and the control plane authorizes it exactly as it authorizes the command
line. An agent cannot do anything through `ankka mcp` that you could not type. `apply_service` checks a
descriptor with the platform's own validation before anything is sent, so a malformed descriptor comes
back to the agent as a readable error.

**Tenancy administration is not offered.** Creating and deleting organizations and projects, inviting
and removing members, and disabling organizations stay on the command line. They are rare, they are a
person's decision, and a tool that exists is a tool a model may choose.

**Every tool says how far it reaches.** Tools carry the protocol's annotations: read-only tools change
nothing, destructive ones may remove or replace something, and idempotent ones do nothing more the second
time. MCP clients use these to decide what to confirm with you before running. `delete_service`,
`pause_service`, `unexpose_service`, `apply_service` and `call_local_endpoint` are marked destructive.

**The local tools see what the local console sees.** `call_local_endpoint` sends an ordinary HTTP
request to the service's own port, so the endpoint's ACL applies to it exactly as it does to `curl`.
`query_local_entity` runs only the queries a component declared; a command is refused by the service.
[The local console](../operate/local-console.md) describes the same data for a person.

### The development loop, for an agent

With the skill and the server, an agent can take a change from code to a running, observed service:

1. Read the page for the component it is writing, with `read_doc` or from the skill.
2. Write the code and its unit test, and run `sbt test` or `uv run pytest`.
3. Run the service locally, then use `list_local_services`, `call_local_endpoint` and `local_traces` to
   exercise it and see which components each request went through.
4. Build the image and `apply_service` a descriptor, then poll `get_service` until the service is
   `Ready`, and read `service_logs` if it is not.

## Documentation for other tools

The documentation site publishes the same pages for any tool that reads the web:

| Address | Holds |
|---|---|
| `https://docs.ankka.cloud/llms.txt` | every page's title, link and one-sentence description, by section |
| `https://docs.ankka.cloud/llms-full.txt` | every page in one file |
| `https://docs.ankka.cloud/<page>.md` | one page as Markdown, beside its HTML |
| `https://docs.ankka.cloud/docs-index.json` | every page's metadata and headings |

The site describes the latest release. The skill in a project and the pages inside the CLI describe the
version they were built with, which is the better source while you work on a service pinned to an older
version.
