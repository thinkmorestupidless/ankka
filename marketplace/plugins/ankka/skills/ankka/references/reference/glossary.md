# Glossary

> One or two sentences on every term ankka's documentation uses, from ACL to workflow, each with its own anchor so any page can link to the definition.

Source: https://docs.ankka.cloud/reference/glossary/
The documentation uses each of these terms in exactly this sense.

### ACL

An endpoint's access control list: the rule deciding who may call it. Every Scala endpoint must declare one,
such as `Acl.DenyAll`, `Acl.AllowAll`, a predicate over the request, or an authenticator. Exposing a service
changes who can reach an endpoint, never who its ACL allows.

### Agent

A component that carries out a task by talking to a model. A handler returns an effect describing the
messages, tools and guardrails; the runtime runs the loop of model calls and tool calls. Agents are addressed
by session.

### AnkkaService resource

The Kubernetes custom resource, short name `asvc`, through which the control plane tells the operator what
should run. The control plane writes its specification; the operator reports its status. It is the only thing
the two share.

### Base domain

The domain an installation serves under. The control plane answers at `api.<base domain>`, the identity
provider at `auth.<base domain>`, and an exposed service at `<service>-<project>.<base domain>`. A local
platform uses `127.0.0.1.sslip.io`.

### Cluster

The set of a service's instances that act as one: entity ids are sharded across them, each entity has one
writer, and singletons such as the timer sweeper run on exactly one. Every service forms its own cluster.

### Codec

What turns a value into bytes and back. In Scala it is a `Serializer`, usually from `Codecs.serializer`; in
Python a `Codec`, usually from `json_codec`. A codec names its manifest.

### Command

A handler that may change state: persist events, update state, or start a workflow step. Declared with
`command` in Scala and `@command` in Python.

### Compaction

Replacing the oldest messages of an agent session with a summary, so a long conversation fits a model's
context window. It runs in the background after a turn, and recent messages stay verbatim.

### Compensation

The step a workflow fails over to when a step fails past its retries, to undo or make good what earlier
steps did. It is an ordinary step, named in the failing step's recovery.

### Component

A unit of an ankka service that the runtime hosts: an event sourced entity, key value entity, view, consumer,
workflow, timed action, agent or HTTP endpoint. A service is the set of components registered with it.

### Component client

The means by which one component calls another, by component, id and handler. Calls go through it because the
target is usually on another instance. A refusal comes back as a typed error.

### Component id

The stable name of a component within a service, such as `shopping-cart`. It is stored with the component's
data, so changing it orphans that data.

### Conformance suite

The platform's definition of a compatible SDK: a suite of behaviours run against a language SDK's reference
service through the sidecar. An SDK that passes it, and the encoding fixtures, can host services on the
platform.

### Confirmed

Whether a service's status describes a current observation of the cluster. An unconfirmed status restates
what was last known, because the cluster could not be reached or nothing has reported yet.

### Consumer

A component that reacts to changes from a source — an entity's events, a key value entity's state, or a topic
— by calling other components or publishing onward. Delivery is at least once.

### Control plane

The service that operates the platform: it records organizations, projects and service descriptors, checks
who may change them, and projects each service's desired state into an AnkkaService resource. It is itself an
ankka service. The CLI is its client.

### Descriptor

The JSON document stating a service's desired state: its image, environment, port, size and instance count.
Applied with `ankka services apply -f service.json`.

### Desired state

What a service should be: its latest descriptor, whether it is paused, whether it is exposed. Recorded by the
control plane when you change it, and reconciled towards by the operator.

### Effect

The value a handler returns: a description of what should happen, such as "persist this event, then reply
with the new state". Building one performs no I/O; the runtime carries it out. This is why a component's logic
can be tested with nothing running.

### Embedded hosting

How a Scala service runs: the image is an ankka service, and its JVM is a node of the service's cluster. The
descriptor's default `hosting`.

### Endpoint

A component that turns HTTP requests into component calls. It declares a path prefix, an ACL and routes; the
runtime serves them.

### Entity id

The id of one instance of an entity or workflow, such as a cart id. All commands for one id are handled one
at a time by one instance.

### Event

A fact an event sourced entity persisted, such as `ItemAdded`. Events are appended to the journal, never
changed, and replayed to rebuild state.

### Expose

Make a service reachable from outside the cluster at its platform-derived hostname, with `ankka services
expose`. A service is private until exposed.

### Generation

A counter on each service that increments on every apply and every restart. An observation states the
generation it describes, so a late report about an older generation is discarded.

### Guardrail

A check on an agent's input or output text that can block it. Input guardrails run before the model sees the
text; output guardrails run on what the model produced.

### Handler

A method of a component that the runtime calls: a command, a query, a workflow step, a timed action's action,
or an agent's handler. Each is declared with a wire name.

### Hostname

The URL an exposed service answers at, `https://<service>-<project>.<base domain>`. The platform derives it;
it cannot be chosen.

### Instance

One running copy of a service: a pod on the platform, a process on a laptop. A service's instances form one
cluster.

### Key value entity

An entity that stores only its latest state, with no history of how it got there.

### Lifecycle

The one-word summary of what the platform last observed about a service: `Ready`, `UpdateInProgress`,
`PartiallyReady`, `Unavailable`, `Failed`, `Paused`, `Suspended` or `NotDeployed`.

### Local console

A web page, started with `ankka local console`, that shows every ankka service running on your machine: its
components and routes, the traces of requests it served, entity state through its declared queries, and agent
sessions.

### Manifest

The name stored beside a serialized value in the journal, such as `shopping-cart-event`, which says which codec
reads it. Changing a manifest leaves existing data unreadable.

### Member

A person or machine identity that belongs to an organization, as an owner or a member. The `member` role may
create projects and deploy and operate services in them.

### Observed state

What the cluster reports about a service: its lifecycle, ready and desired instances, database and route. The
operator reports it; the control plane records it beside the desired state.

### Operator

The in-cluster process that watches AnkkaService resources and creates what each one needs: namespace,
database, Deployment, Service and route. It reports status back through the resource.

### Organization

The top-level tenancy boundary. Members belong to an organization, projects belong to it, and everything in it
is invisible to non-members.

### Owner

The organization role that can also rename and delete the organization and manage its members. Whoever creates
an organization is its first owner.

### Passivation

Unloading an idle entity from memory. The next command rebuilds it from its journal. The default idle time is
two minutes.

### Platform-admin

A role held in the identity provider, not in an organization. Its holders can see every organization, disable
and re-enable one, and add a member to one whose owners have all left.

### Principal

Who a request came from, as an endpoint's authenticating ACL established it. Its `subject` is the stable
identity; its name and email are for display.

### Process

In a process-hosted service, the container running your code in another language. It serves the sidecar
protocol and never touches the database, the cluster or a model.

### Process hosting

How a service in a language other than Scala runs: your process in one container, and the sidecar beside it in
the same pod. Declared with `"hosting": "process"` and a `protocol` version.

### Project

A group of services within an organization, deployed to one namespace. Services are named per project, and
each project has its own database cluster.

### Protocol version

The version of the sidecar protocol a process-hosted service's SDK speaks, `MAJOR.MINOR`, such as `1.0`. The
platform accepts the same major and a minor no later than its own.

### Query

A handler that only reads. It must return a read-only effect, so it cannot persist. The local console runs
queries and never commands.

### Read-only effect

An effect that can reply or refuse but cannot persist events or change state. A query must return one.

### Refusal

A handler's deliberate "no", returned as an error effect with a message and an error code. Nothing is persisted
and nothing is retried. It differs from a failure, which is a handler that threw.

### Row

One record of a view, keyed by its source's subject and stored as JSON in the view's table.

### Runtime version

The ankka version a service's image was built against, declared as `runtime` in its descriptor and served at
`/ankka/version`. The platform accepts the same major and a minor equal to its own or one below.

### Session

The conversation an agent works in, identified by a session id. Its memory is an event sourced entity, so it
survives restarts, and several agents can share one session to collaborate. Requests to one session are handled
one at a time.

### Sharding

Distributing entity instances across a service's instances by id, so that each id lives on exactly one instance
at a time and moves when instances come and go.

### Sidecar

The ankka runtime running beside a process-hosted service in the same pod. It owns sharding, the journal,
projections, timers, HTTP, the agent loop and cluster formation, and asks the process only for decisions.

### Source

Where a view or consumer reads changes from: an event sourced entity's events, a key value entity's state, or a
broker topic.

### Span

One timed piece of work in a trace, such as one handler invocation, with its outcome.

### State

What an entity or workflow knows now. For an event sourced entity it is derived by applying events; for a key
value entity and a workflow it is stored directly.

### Step

One unit of a workflow's work. A step runs, may call other components, and says what happens next: another
step, a pause, the end, or a failure. Each transition is journaled before the next begins.

### Timed action

A component whose handlers the runtime calls later, when a timer fires. Timers are stored in the database, so
they outlive the process that set them, and a failed call is retried with backoff.

### Timer

A scheduled future call to a timed action, identified by a name. Scheduling again under the same name replaces
it.

### Tool

A function an agent's model may call, with a name, a description and a schema for its arguments. The runtime
calls it with the model's arguments and hands the result back to the model.

### Topic

A named stream on a message broker, Kafka, that a view or consumer can read from and a consumer can publish to.

### Trace

The tree of spans one request produced, across every component it reached, with the time the platform could
not attribute to any of them.

### Unattributed time

The part of a span's duration that none of its child spans accounts for, shown as its own row on a trace. It is
usually time spent waiting on a database, a model, or work handed to another thread.

### View

A component that maintains a queryable table from a source's changes, answering questions no single entity can,
such as "every cart containing this product".

### Wire name

The name a handler is declared with, such as `add-item`, separate from its method name. Callers, timers and the
journal address handlers by wire name, so it is part of the service's protocol and changing it is a breaking
change.

### Workflow

A component that runs a durable multi-step process. Each step's outcome is journaled before the next step
begins, so a workflow survives restarts and resumes where it was.
