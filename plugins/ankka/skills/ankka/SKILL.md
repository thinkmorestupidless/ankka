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

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Start here

- `references/index.md` — What ankka is, who it is for, the components a service is built from, and where in this documentation to start.

### Get started

- `references/get-started/install.md` — Install what ankka needs on your machine, build the ankka CLI from the repository, and make the Scala libraries and Python SDK available to your own projects.
- `references/get-started/first-service-scala.md` — Create a Scala service from the ankka template, test it, run it against a local Postgres, watch it in the local console, and change its domain.
- `references/get-started/first-service-python.md` — Write a Python service with an event sourced entity and an HTTP endpoint, run it beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.
- `references/get-started/deploy-locally.md` — Run the whole build, deploy and observe cycle on your own machine — a kind cluster with the ankka platform, your service deployed and exposed over HTTPS, its logs, a restart and its history.
- `references/get-started/coding-agents.md` — Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.

### Concepts

- `references/concepts/architecture.md` — The parts of ankka and how they fit together — components and the runtime inside a service, the control plane and operator that deploy it, the sidecar for other languages, and where every piece of state lives.
- `references/concepts/effects.md` — Why every ankka handler returns a description of what should happen instead of doing it, what each component's effects look like, and why a refusal is a returned value rather than an exception.
- `references/concepts/components.md` — The component kinds an ankka service is built from, what each is for, how the runtime hosts it, and how to choose between them.
- `references/concepts/designing-services.md` — The concepts that shape an ankka system — consistency boundaries, events, read models, reactions, processes, time, agents, the edge and service boundaries — with a worked example mapping a checkout onto components.
- `references/concepts/wire-names.md` — How ankka names components, handlers and stored types independently of your code's names, why those names are a versioning boundary, and which renames are safe in a running system.
- `references/concepts/consistency.md` — The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.
- `references/concepts/agents.md` — How an ankka agent works — sharded by session, one request at a time, with session memory kept as an event sourced entity and the model loop, tools and guardrails run by the platform.
- `references/concepts/clustering.md` — How a service's instances form one cluster, how entities are spread across it, how nodes find each other locally and in Kubernetes, and what that means for rollouts, failures and instance counts.
- `references/concepts/polyglot.md` — How ankka hosts a service written in Python — the runtime runs beside the process as a sidecar, owning everything durable and distributed, while the process decides what each command does.
- `references/concepts/control-plane.md` — How the control plane records what you asked for, how the operator makes the cluster match it, and how the status you read is kept honest with generations and confirmation.
- `references/concepts/tenancy-and-access.md` — How organizations, projects and services divide an installation, who may operate each of them, and how the identity provider and the control plane share the work of authentication and authorization.
- `references/concepts/observability.md` — What ankka records about every request — spans, traces, unattributed time, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.

### Build

- `references/build/event-sourced-entities.md` — Model state as a sequence of persisted events, write commands and queries that return effects, delete or expire an entity, and tune snapshots, in Scala or Python.
- `references/build/key-value-entities.md` — Store only the latest value of a piece of state, replace it with updateState, delete or expire it, and decide when that is a better fit than event sourcing.
- `references/build/views.md` — Build a queryable projection of an entity's or a topic's changes, keep one row per source id, and query the rows with SQL in Scala or by key in Python.
- `references/build/consumers.md` — React to every change from an entity or a topic, call other components or publish onward to a topic, and make the reaction safe to repeat under at-least-once delivery.
- `references/build/workflows.md` — Build a durable multi-step process in Scala or Python — commands, steps, transitions, pauses, timeouts, retries and compensation — that resumes where it stopped after a crash.
- `references/build/timers.md` — Schedule a call for later with a timed action, cancel or replace it by name, and handle retries — timers are stored in the database and outlive the process that set them.
- `references/build/agents.md` — Write an agent in Scala or Python — instructions, tools, guardrails, session memory, structured replies and compaction — and configure the model it talks to.
- `references/build/streaming.md` — Stream an agent's reply token by token to a caller and over HTTP as server-sent events, and know what streaming changes about guardrails and sessions.
- `references/build/multi-agent-orchestration.md` — Coordinate several agents from a workflow — sequentially, in parallel, or chosen dynamically by another agent — sharing one session, and test the coordination with a scripted model.
- `references/build/http-endpoints.md` — Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala or Python.
- `references/build/component-client.md` — Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.
- `references/build/topics.md` — Read views and consumers from a Kafka topic and publish to one, with CloudEvents attributes as headers, per-entity ordering by subject, and a broker-free in-memory pair for tests.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in both languages, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in Scala and Python — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.

### Run and deploy

- `references/deploy/run-locally.md` — Run an ankka service on your own machine against a local Postgres, configure its database and HTTP port, form a two-node cluster in two terminals, and run a Python service beside the sidecar.
- `references/deploy/images.md` — Package a Scala or Python ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.
- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.
- `references/deploy/expose.md` — Make a deployed service reachable from outside the cluster at its platform-derived HTTPS hostname, understand why the hostname has the shape it does, and remove the route again.
- `references/deploy/scaling-and-rollouts.md` — Choose how many instances a service runs and how large each is, and understand how deploys, restarts and scaling change the running pods without refusing requests.
- `references/deploy/ci.md` — Give a CI job its own identity as a Keycloak client, obtain a token with the client-credentials grant, and run the ankka CLI non-interactively with environment variables and meaningful exit codes.
- `references/deploy/upgrading.md` — Move a service to a new ankka version by changing the library version and the descriptor's runtime declaration together, refreshing the local schema, and checking what a deployed instance actually runs.

### Observe and operate

- `references/operate/local-console.md` — Use `ankka local console` to see every ankka service running on your machine — its components, a form per HTTP route, the traces of recent requests, entity state and agent sessions.
- `references/operate/status-and-history.md` — Read a deployed service's status with `ankka services list` and `get`, understand every field including unconfirmed readings, and see who changed a service with `ankka services history`.
- `references/operate/logs.md` — Read what a deployed service printed with `ankka services logs` — from every instance or one, from the container before the last restart, limited by lines or time — and know what it does not keep.
- `references/operate/service-lifecycle.md` — What pausing, resuming, restarting and deleting a deployed service do to its instances, its data, its hostname and its generation, and how a suspended service differs from a paused one.
- `references/operate/troubleshooting.md` — Symptoms you are likely to meet building, running, deploying and operating ankka services, with the cause of each and what to do about it.

### Run the platform

- `references/platform/install-local.md` — Run the whole ankka platform on your own machine in a kind cluster — operator, control plane, identity provider, databases, gateway and TLS — with one script, and point the CLI at it.
- `references/platform/install-cloud.md` — Install ankka on a real Kubernetes cluster with a production kustomize overlay — a load balancer, a public wildcard certificate over DNS-01, a real base domain, images from a registry and an identity provider with no default credentials.
- `references/platform/organizations.md` — Create organizations and projects, invite members by email, manage roles, and disable or delete an organization, with the rules the control plane enforces on each.
- `references/platform/identity.md` — How people and machines authenticate to the ankka control plane through the installation's Keycloak — logging in with the CLI, adding users, platform administrators, machine accounts and the realm.
- `references/platform/databases.md` — How the platform provisions a Postgres database for every service with CloudNativePG, why each service must have its own, how data survives deletion, and how to bring your own database instead.
- `references/platform/networking.md` — How traffic reaches ankka services — the installation's single gateway and wildcard certificate, per-service routes, in-cluster addresses, the ports an instance uses, readiness, and what is not isolated.

### Reference

- `references/reference/cli.md` — Every `ankka` command and option, how the CLI resolves its settings and credentials, its output formats and its exit codes.
- `references/reference/service-descriptor.md` — Every field of the JSON service descriptor that `ankka services apply` takes, with its type, default and validation rules, and the environment variables the platform reserves.
- `references/reference/lifecycle-states.md` — What each of a deployed service's eight lifecycle states means, what usually causes it, what to do about it, and what an unconfirmed status is.
- `references/reference/control-plane-api.md` — Every route the control plane serves, with its parameters, request body, response and who may call it, plus how requests are authenticated and how errors are reported.
- `references/reference/configuration.md` — Every environment variable and configuration key a running ankka service reads, their defaults, how configuration is layered, and which variables the platform sets for you.
- `references/reference/runtime-endpoints.md` — The ports and HTTP endpoints every running ankka service exposes besides its own routes — readiness, version and metrics on a deployed instance, and the loopback observability endpoint a local one serves the console.
- `references/reference/error-codes.md` — The eight error codes a component can refuse with, the HTTP status each becomes, how a refusal travels from a handler to a caller, and how it differs from a failure.
- `references/reference/scala-sdk.md` — A compact map of the Scala SDK — the artifacts, and for every component kind its base class, companion, handler declarations, effect builders and in-handler accessors.
- `references/reference/python-sdk.md` — A compact map of the Python SDK — installing it, and for every component kind its base class, class attributes, decorators, effect builders and testkit — plus the SDK's own development commands.
- `references/reference/sidecar-protocol.md` — The gRPC protocol between the ankka sidecar and a service's process — transport, the discovery handshake, every service and RPC, the payload encoding, versioning, and the rules the messages do not state on their own.
- `references/reference/akka-divergences.md` — Where ankka deliberately differs from Akka's SDK and platform — registration, handler identity, tools, views, routing, ACLs, model settings, descriptors, defaults and lifecycle states — and why.
- `references/reference/limitations.md` — What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.
- `references/reference/glossary.md` — One or two sentences on every term ankka's documentation uses, from ACL to workflow, each with its own anchor so any page can link to the definition.
