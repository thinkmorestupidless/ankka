---
name: ankka
description: Start here for any work on ankka, a serverless platform for agentic AI on the actor model (Akka's component model in Scala 3 on Apache Pekko, with Python via a sidecar). Use when a task mentions ankka and no narrower ankka skill fits — what ankka is, installing it, creating a first service from the template, the shape of a service, the Scala and Python SDK maps, what ankka does not do, and where it differs from Akka. The narrower skills (ankka-design, ankka-entities, ankka-views-consumers, ankka-workflows, ankka-agents, ankka-endpoints, ankka-python, ankka-deploy, ankka-platform) carry the rules for one kind of task each.
---

# ankka

ankka hosts services built from a fixed set of components. The developer writes the components; the
runtime supplies sharding, persistence, replay, projections, durable orchestration, timers, HTTP and the
agent loop. A service is written in Scala (compiled into one JVM with the runtime) or in Python (a
process beside a runtime sidecar), and is deployed to a Kubernetes-based platform with the `ankka` CLI.

## Rules that hold everywhere

1. **Handlers return effects; they do not perform them.** A handler builds a description — persist
   these events then reply, update this state, transition to this step — and returns it. Never do I/O,
   read a database or call a model inside an entity handler. Calls to other components go through the
   component client, from endpoints, workflow steps, consumers, timed actions and agent tools.
2. **Wire names are protocol.** `command("add-item")(_.addItem)` in Scala and `@command("add-item")` in
   Python declare the name the platform stores and routes by. Rename the method freely; never change a
   wire name of a deployed service without treating it as a breaking change, because persisted timers
   and in-flight calls address it.
3. **A query cannot persist.** Declare read-only handlers with `query`/`@query`; they must return a
   read-only effect. Everything else is a `command`.
4. **Registration is explicit.** Every component is registered on the service builder. There is no
   classpath scanning; an unregistered component does not exist.
5. **Events and state are stored as JSON under a manifest.** Changing a stored type is schema
   evolution: add optional fields, never rename or remove one a journal already holds. Field names are
   the contract between languages.
6. **Every HTTP endpoint declares an ACL.** Exposing a service changes who can reach it, not who may
   call it; an `AllowAll` endpoint on an exposed service is on the internet.
7. **One database per service.** Never point two services at one database; the platform provisions one
   per service.
8. **Consumers and topic-sourced views are at-least-once.** What they do must tolerate a repeat.
9. **Test at two levels.** Unit testkits run a component with no runtime, in milliseconds. Integration
   testkits start the whole service against a throwaway Postgres; restart the service in a test to prove
   durability rather than caching.

## Which skill to load next

This skill orients. The work itself has a skill each, and its rules are there, not here:

| Task | Skill |
|---|---|
| Decide which components a problem needs, where a rule lives, what may lag | `ankka-design` |
| Write or change an event sourced or key value entity, its events, state or serializers | `ankka-entities` |
| Project changes into a queryable table, react to changes, read or publish a broker topic | `ankka-views-consumers` |
| A durable multi-step process, compensation, deadlines and timers | `ankka-workflows` |
| An agent, its tools, guardrails, session memory, model, streaming, or several agents together | `ankka-agents` |
| An HTTP endpoint, its routes, ACL, errors and server-sent events | `ankka-endpoints` |
| A service in Python beside the sidecar | `ankka-python` |
| A service descriptor, the `ankka` CLI, images, deploying, exposing, logs, troubleshooting | `ankka-deploy` |
| Installing or operating the platform itself, organizations, identity, databases, networking | `ankka-platform` |

## How to use this skill

Read the reference file for the task before writing code — the samples in them are copied from code the
ankka build compiles and tests, so their imports and signatures are current. For a new project, start
from the template with `ankka init` (`references/get-started/first-service-scala.md`), never from an
empty build. For the shape of every component's base class, companion and effect builders in one place,
`references/reference/scala-sdk.md` or `references/reference/python-sdk.md`. When a capability seems
missing, check `references/reference/limitations.md` before building around it, and when a habit from
Akka does not fit, `references/reference/akka-divergences.md` says what ankka does instead.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Start here

- `references/index.md` — What ankka is, who it is for, the components a service is built from, and where in this documentation to start.

### Get started

- `references/get-started/install.md` — Install what ankka needs on your machine, install the ankka CLI with Homebrew or from a release, and make the Scala libraries and Python SDK available to your own projects.
- `references/get-started/first-service-scala.md` — Create a Scala service from the ankka template, test it, run it against a local Postgres, watch it in the local console, and change its domain.
- `references/get-started/first-service-python.md` — Write a Python service with an event sourced entity and an HTTP endpoint, run it beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.
- `references/get-started/deploy-locally.md` — Run the whole build, deploy and observe cycle on your own machine — a kind cluster with the ankka platform, your service deployed and exposed over HTTPS, its logs, a restart and its history.
- `references/get-started/coding-agents.md` — Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.

### Concepts

- `references/concepts/architecture.md` — The parts of ankka and how they fit together — components and the runtime inside a service, the control plane and operator that deploy it, the sidecar for other languages, and where every piece of state lives.
- `references/concepts/effects.md` — Why every ankka handler returns a description of what should happen instead of doing it, what each component's effects look like, and why a refusal is a returned value rather than an exception.
- `references/concepts/components.md` — The component kinds an ankka service is built from, what each is for, how the runtime hosts it, and how to choose between them.

### Reference

- `references/reference/scala-sdk.md` — A compact map of the Scala SDK — the artifacts, and for every component kind its base class, companion, handler declarations, effect builders and in-handler accessors.
- `references/reference/python-sdk.md` — A compact map of the Python SDK — installing it, and for every component kind its base class, class attributes, decorators, effect builders and testkit — plus the SDK's own development commands.
- `references/reference/akka-divergences.md` — Where ankka deliberately differs from Akka's SDK and platform — registration, handler identity, tools, views, routing, ACLs, model settings, descriptors, defaults and lifecycle states — and why.
- `references/reference/limitations.md` — What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.
- `references/reference/glossary.md` — One or two sentences on every term ankka's documentation uses, from ACL to workflow, each with its own anchor so any page can link to the definition.
