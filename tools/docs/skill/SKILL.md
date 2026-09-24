---
name: ankka
description: Build, test, deploy and operate services on ankka, a serverless platform for agentic AI on the actor model (Akka's component model in Scala 3 on Apache Pekko, with Python via a sidecar). Use when writing ankka components in Scala or Python — event sourced and key value entities, views, consumers, workflows, timers, agents, HTTP endpoints — when writing a service descriptor, or when using the `ankka` CLI to deploy, expose, observe or troubleshoot a service.
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

## How to use this skill

Read the reference file for the task before writing code — the samples in them are copied from code the
ankka build compiles and tests, so their imports and signatures are current. For a design question start
with `references/concepts/designing-services.md` and `references/concepts/components.md`. For a command
line, `references/reference/cli.md`. For a descriptor, `references/reference/service-descriptor.md`.
When something does not work, `references/operate/troubleshooting.md`. When a capability seems missing,
check `references/reference/limitations.md` before building around it.
