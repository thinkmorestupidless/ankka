---
name: ankka
description: Start here for any work on ankka, a serverless platform for agentic AI on the actor model (Akka's component model in Scala 3 on Apache Pekko, with Python and TypeScript via a sidecar). Use when a task mentions ankka and no narrower ankka skill fits — what ankka is, installing it, creating a first service from the template, the shape of a service, the Scala, Python and TypeScript SDK maps, what ankka does not do, and where it differs from Akka. The narrower skills (ankka-design, ankka-entities, ankka-views-consumers, ankka-workflows, ankka-agents, ankka-endpoints, ankka-python, ankka-typescript, ankka-deploy, ankka-platform) carry the rules for one kind of task each.
pages:
  - index.md
  - get-started/install.md
  - get-started/first-service-scala.md
  - get-started/first-service-python.md
  - get-started/first-service-typescript.md
  - get-started/deploy-locally.md
  - get-started/coding-agents.md
  - concepts/architecture.md
  - concepts/components.md
  - concepts/effects.md
  - reference/scala-sdk.md
  - reference/python-sdk.md
  - reference/typescript-sdk.md
  - reference/akka-divergences.md
  - reference/limitations.md
  - reference/glossary.md
---

# ankka

ankka hosts services built from a fixed set of components. The developer writes the components; the
runtime supplies sharding, persistence, replay, projections, durable orchestration, timers, HTTP and the
agent loop. A service is written in Scala (compiled into one JVM with the runtime) or in Python or TypeScript (a
process beside a runtime sidecar), and is deployed to a Kubernetes-based platform with the `ankka` CLI.

## Rules that hold everywhere

1. **Handlers return effects; they do not perform them.** A handler builds a description — persist
   these events then reply, update this state, transition to this step — and returns it. Never do I/O,
   read a database or call a model inside an entity handler. Calls to other components go through the
   component client, from endpoints, workflow steps, consumers, timed actions and agent tools.
2. **Wire names are protocol.** `command("add-item")(_.addItem)` in Scala, `@command("add-item")` in Python and
   `command("add-item", ...)` in TypeScript declare the name the platform stores and routes by. Rename the method freely; never change a
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
| A service in TypeScript on Node.js beside the sidecar | `ankka-typescript` |
| A service descriptor, the `ankka` CLI, images, deploying, exposing, logs, troubleshooting | `ankka-deploy` |
| Installing or operating the platform itself, organizations, identity, databases, networking | `ankka-platform` |

## How to use this skill

Read the reference file for the task before writing code — the samples in them are copied from code the
ankka build compiles and tests, so their imports and signatures are current. For a new project, start
from the template with `ankka init` (`references/get-started/first-service-scala.md`), never from an
empty build. For the shape of every component's base class, companion and effect builders in one place,
`references/reference/scala-sdk.md`, `references/reference/python-sdk.md` or
`references/reference/typescript-sdk.md`. When a capability seems
missing, check `references/reference/limitations.md` before building around it, and when a habit from
Akka does not fit, `references/reference/akka-divergences.md` says what ankka does instead.
