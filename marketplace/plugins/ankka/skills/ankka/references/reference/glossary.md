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

### Approval

A person's "yes" to a tool call before it runs. A tool, or an MCP server, that requires approval is one
whose tool calls wait for it. See [Agents](../build/agents.md#tools-that-wait-for-a-person).

### Approval request

What an agent records, and gives its caller instead of an answer, when its model calls a tool that requires
approval: an id, the tool and the arguments the model proposed. It awaits a decision until a person decides
it, or until its time limit passes and the platform refuses it.

### AnkkaService resource

The Kubernetes custom resource, short name `asvc`, through which the control plane tells the operator what
should run. The control plane writes its specification; the operator reports its status. It is the only thing
the two share.

### Base domain

The domain an installation serves under. The control plane answers at `api.<base domain>`, the identity
provider at `auth.<base domain>`, and an exposed service at `<service>-<project>.<base domain>`. A local
platform uses `127.0.0.1.sslip.io`.

### Broker

What holds topics and carries what is published to one to whatever reads it: Kafka. An installation has
one, which the platform provides for every project; a descriptor may name another instead. See
[The installation's broker](../platform/broker.md).

### Broker variable

A variable that says where a broker is or how to connect to it, beginning `ANKKA_KAFKA_`. A descriptor that
gives one names a broker of its own; the platform gives them to every service with components on an
installation with a broker.

### Calling address

The address, inside an instance of a web-hosted service, at which the process calls another service by
name as the web-hosted service: `ANKKA_SERVICES_URL`. Nothing outside the instance can use it.

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

### Declared topic

A topic a member declares on a project, once, with its partitions, which the platform makes on the
installation's broker. Every service of the project publishes to it and reads it by its name; no service
declares it. See [Broker topics](../build/topics.md#declaring-a-topic).

### Decision

A person's answer to an approval request: approved or refused, with who decided and an optional note the
model is told. A request is decided once. Who decided is recorded; who may decide is the ACL of the route
the decision is sent through.

### Delta

One element of a graph as it now is, whole, at a version, or a tombstone marking it deleted: what a
[graph consumer](#graph-consumer) publishes for each element a change leaves. Deltas follow the contract
`ankka.graph-delta.v1`, and a reader applies one when its version is newer than what it holds.

### Descriptor

The JSON document stating a service's desired state: its image, environment, port, size and instance count.
Applied with `ankka services apply -f service.json`.

### Desired state

What a service should be: its latest descriptor, whether it is paused, whether it is exposed. Recorded by the
control plane when you change it, and reconciled towards by the operator.

### Digest

A short value computed from a service's descriptor: two descriptors share one exactly when they state the
same things. A service's history shows one for each apply and rollback, so two generations with one image
and a different environment can be told apart.

### Due time

When a timer is to fire. The runtime fires a timer at its due time or up to a poll interval after, never
before, and tells the handler the due time it is run for; a retry after a failure is told the same one.

### Effect

The value a handler returns: a description of what should happen, such as "persist this event, then reply
with the new state". Building one performs no I/O; the runtime carries it out. This is why a component's logic
can be tested with nothing running.

### Element

A node or an edge of a graph, identified by which of the two it is and its id. Nodes and edges are
separate id spaces.

### Element key

The record key of every delta for one element: `node:<id>` or `edge:<id>`.

### Embedded hosting

How a Scala service runs: the image is an ankka service, and its JVM is a node of the service's cluster. The
descriptor's default `hosting`.

### Endpoint

A component that turns requests from outside the service into component calls, and holds no state. An HTTP
endpoint declares a path prefix, an ACL and routes; a gRPC endpoint implements a service definition's methods
under an ACL. The runtime serves both.

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

A counter on each service that increments on every apply, every restart and every rollback. An
observation states the generation it describes, so a late report about an older generation is discarded.
A rollback is a new generation, never a return to an old number.

### Graph consumer

A consumer that publishes its source as a graph. Its handlers return the elements a change leaves, and
each is published as a delta under its element key, at the change's sequence number.

### gRPC endpoint

An endpoint that implements the methods of a service definition, served on the service's gRPC port. Scala
services only. See [gRPC endpoints](../build/grpc-endpoints.md).

### Guardrail

A check on an agent's input or output text that can block it. Input guardrails run before the model sees the
text; output guardrails run on what the model produced.

### Handler

A method of a component that the runtime calls: a command, a query, a workflow step, a timed action's action,
or an agent's handler. Each is declared with a wire name.

### History

What the control plane keeps of the changes members made to a service: what was done, at which generation,
by whom and when, newest first, the last 50 of them. It is not what the service printed, which is its logs.

### Hostname

The URL an exposed service answers at, `https://<service>-<project>.<base domain>`. The platform derives it;
it cannot be chosen.

### Instance

One running copy of a service: a pod on the platform, a process on a laptop. A service's instances form one
cluster.

### Judgment

The answer to a set of typed questions about a state — a choice, a score, a yes or no — from a System One
model, each answer with the probabilities behind it. An agent's handler can reply with one, and a judged
guardrail refuses by one.

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

### MCP server

A program outside the service that offers tools over the Model Context Protocol. An agent lists the servers
whose tools its model is offered, each as `mcp__<server>__<tool>`, with the credential the platform sends
it. See [MCP servers](../build/mcp-servers.md).

### Member

A person or machine identity that belongs to an organization, as an owner or a member. The `member` role may
create projects and deploy and operate services in them.

### Mount

A path of a web-hosted service together with the service of the same project that answers requests under
it. The proxy passes such a request to the mounted service, which is told it came from the internet. A
mount does not expose the mounted service.

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

### Partition

One of the parts a topic is divided into on a broker, each in order. The members of a consumer group divide
a topic's partitions between them. A topic may be given more partitions and never fewer.

### Passivation

Unloading an idle entity from memory. The next command rebuilds it from its journal. The default idle time is
two minutes.

### Period

How long after one due time a recurring timer's next due time is. A period is a length of time, from one
millisecond to 36,500 days; it says nothing of a time of day or a day of the week.

### Platform-admin

A role held in the identity provider, not in an organization. Its holders can see every organization, disable
and re-enable one, and add a member to one whose owners have all left.

### Principal

Who a request came from, as an endpoint's authenticating ACL established it. Its `subject` is the stable
identity; its name and email are for display.

### Process

The container running your own program beside the platform's: in a process-hosted service, your code in
another language, serving the sidecar protocol and never touching the database, the cluster or a model; in
a web-hosted service, any program that serves HTTP, beside the proxy.

### Process hosting

How a service in a language other than Scala runs: your process in one container, and the sidecar beside it in
the same pod. Declared with `"hosting": "process"` and a `protocol` version.

### Project

A group of services within an organization, deployed to one namespace. Services are named per project, and
each project has its own database cluster.

### Project secret

A named set of entries a member sets for a project, held in a Kubernetes Secret in the project's
namespace, which a descriptor's variable takes by `secretKeyRef`. The control plane writes it and can never
read it back. Not a service secret. See [Secrets on the platform](../platform/secrets.md).

### Protocol version

The version of the sidecar protocol a process-hosted service's SDK speaks, `MAJOR.MINOR`, such as `1.0`. The
platform accepts the same major and a minor no later than its own.

### Proxy

In a web-hosted service's instance, the platform's program beside the process. It accepts every request
from outside the instance, refuses one from a service the descriptor does not admit, tells the process who
sent it and where it was sent, passes requests under a mount on, and sends the process's calls to other
services. See [Deploy a user interface](../deploy/web-hosting.md).

### Query

A handler that only reads. It must return a read-only effect, so it cannot persist. The local console runs
queries and never commands.

### Question

What a judgment asks: a choice among described options, a score on described levels, or a yes or no. A
value declared once with a wire id, used both to ask and to read the typed answer.

### Read-only effect

An effect that can reply or refuse but cannot persist events or change state. A query must return one.

### Record key

The key a published message has on the broker, which decides which messages are ordered together and
which record a compacted topic keeps. It is the key a message names, and the message's subject when it
names none. Separate from the subject, which says which entity a message is about.

### Reflection

A service's answer to a tool that asks which service definitions it serves and what their methods and
messages look like. A service answers it only when it opts in, and then only to the callers its own ACL for
reflection admits.

### Recurring timer

A timer with a period. It fires for one due time after another, each the previous due time plus the period,
until it is cancelled or replaced. A due time that passed while it could not run is not caught up: it fires
once and goes on. Set again for the same handler with the same period, it keeps its next due time.

### Refusal

A handler's deliberate "no", returned as an error effect with a message and an error code. Nothing is persisted
and nothing is retried. It differs from a failure, which is a handler that threw.

### Result guardrail

A guardrail an agent declares for what an MCP server's tool answers. It runs before the model is told the
result; a result it refuses is never told to the model, which is told of an error instead.

### Rollback

Applying again the descriptor a service recorded at an earlier generation, as a new generation. Nothing is
rewound: the generation keeps counting, the history shows both, and the service's data is untouched.

### Row

One record of a view, kept under its row key and stored as JSON in the view's table.

### Row key

What a row of a view is kept under. A plain view's row key is the id of the entity the change came from;
a keyed view's handlers name their rows' keys.

### Declared query

A question a view can be asked by name: one SQL statement over the view's own table, whose values are the
`:name`s it holds. It is checked when the service starts, and run in a read-only transaction.

### Keyed view

A view of one or more entities whose handlers name every row they write or delete by key, and read the
view's own rows. It handles one change at a time.

### Runtime version

The ankka version a service's image was built against, declared as `runtime` in its descriptor and served at
`/ankka/version`. The platform accepts the same major and a minor equal to its own or one below.

### Secret key

What a service's secret store encrypts its service secrets with: 32 bytes, given as `ANKKA_SECRET_KEY`.
The platform makes one per deployed service and keeps it when the service is deleted; only the platform's
own program holds it.

### Secret store

Where a service keeps its service secrets: a table in its own database, holding each value encrypted with
the service's secret key. Not an entity or a view, and nothing a projection reads. Offered to endpoints,
workflow steps, consumers, timed actions and agents, never to an entity or a view.

### Service definition

A named set of gRPC methods written in a `.proto` file, which a gRPC endpoint implements and a client is
generated from. A method's name in it is the method's wire name.

### Service secret

A named text value a service keeps in its secret store while it runs and reads back by that name, such as
a credential a person gave it. See [Secrets a service keeps](../build/secrets.md).


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

### Socket

A connection a request to a socket route opens and that stays open, over which the client and the
route's handler send each other frames — pieces of text — until one of them closes it. A socket is closed
with a close reason its client is told by a close code, never cut off without being told. The platform
carries a socket and keeps nothing of it.

### Socket route

A route of an HTTP endpoint answered by opening a socket rather than with one response. Its ACL is
decided once, when the socket is opened.

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

### System One model

A model that answers typed questions about a state with probabilities rather than writing text, quickly
and cheaply. TypeSafe AI's Jev is one. See judgment.

### Timed action

A component whose handlers the runtime calls later, when a timer fires. Timers are stored in the database, so
they outlive the process that set them, and a failed call is retried with backoff.

### Timer

A scheduled future call to a timed action, identified by a name. Scheduling again under the same name replaces
it, except that a recurring timer set again for the same handler with the same period is kept as it is.

### Tombstone

A delta that marks an element deleted, at a version. The element stays in the graph, marked, so an older
delta arriving late cannot bring it back.

### Tool

A function an agent's model may call, with a name, a description and a schema for its arguments: one of the
agent's own, or one an MCP server has. The runtime calls it with the model's arguments and hands the result
back to the model; a tool that requires approval waits for a person's decision first.

### Topic

A named stream on a message broker, Kafka, that a view or consumer can read from and a consumer can publish to.

### Trace

The tree of spans one request produced, across every component it reached, with the time the platform could
not attribute to any of them.

### Unattributed time

The part of a span's duration that none of its child spans accounts for, shown as its own row on a trace. It is
usually time spent waiting on a database, a model, or work handed to another thread.

### View

A component that maintains a queryable table from changes, answering questions no single entity can,
such as "every cart containing this product". A plain view reads one source and keeps a row per entity of
it; a keyed view reads several entities and names its rows' keys.

### Web hosting

`"hosting": "web"`: a service whose image is any program that serves HTTP, run beside the platform's
proxy. It has no database, no components and no cluster. See [Deploy a user
interface](../deploy/web-hosting.md).

### Wire name

The name a handler is declared with, such as `add-item`, separate from its method name. Callers, timers and the
journal address handlers by wire name, so it is part of the service's protocol and changing it is a breaking
change.

### Workflow

A component that runs a durable multi-step process. Each step's outcome is journaled before the next step
begins, so a workflow survives restarts and resumes where it was.
