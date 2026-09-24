---
title: Components
description: The component kinds an ankka service is built from, what each is for, how the runtime hosts it, and how to choose between them.
kind: concept
languages: [scala, python]
components: [event-sourced-entity, key-value-entity, view, consumer, workflow, timed-action, agent, http-endpoint]
related: [concepts/designing-services.md, concepts/effects.md, concepts/consistency.md, concepts/architecture.md]
---

# Components

An ankka service is built from a fixed set of component kinds. Each kind has one job, and the runtime
supplies everything that job needs: persistence, distribution, scheduling or delivery. You choose the kind
by the question the component answers, write its handlers, and register it. Every kind is available in
Scala and in Python.

## The kinds

| Component | Answers | State | Addressed by |
|---|---|---|---|
| Event sourced entity | "what happened to this thing, and what is it now?" | derived from its events | entity id |
| Key value entity | "what is this thing now?" | its latest value | entity id |
| View | "which things match this?" | rows in a table, one per source id | a query |
| Consumer | "when this changes, what else should happen?" | none | nothing; fed by a source |
| Workflow | "how does this multi-step process proceed?" | its own state and current step | workflow id |
| Timed action | "what should happen later?" | none; timers are stored by the runtime | a timer name |
| Agent | "what does a model make of this, with these tools?" | a conversation per session | session id |
| HTTP endpoint | "how does the outside world reach the service?" | none | a route |

### Event sourced entity

An event sourced entity holds the state of one thing, identified by an id: a cart, an order, an account.
Its command handlers decide what happened and persist **events**; its state is the fold of those events,
rebuilt by replaying them. The journal of events is the durable record, so the entity has a full history
of how it reached its state, and views and consumers can react to each change.

The runtime hosts each entity id as one actor in cluster sharding, so exactly one instance of the service
handles a given id at a time, and commands to it are processed one after another. That makes an entity a
**consistency boundary**: a rule over one entity's state holds without locks.
[Event sourced entities](../build/event-sourced-entities.md) shows how to build one.

### Key value entity

A key value entity holds the latest value of one thing and nothing else. A command handler replaces the
value, and no history is kept. It has the same hosting and the same one-at-a-time guarantee as an event
sourced entity. Choose it for state whose history nobody needs: preferences, a configuration, a cached
summary. [Key value entities](../build/key-value-entities.md) shows how.

### View

A view is a queryable projection of another component's changes. Its handler receives each change from
its **source** — an entity's events, a key value entity's new values, or messages from a broker topic —
and says what that change does to the row for that source id. The runtime stores the rows in a Postgres
table as JSON, and you query them with SQL.

A view exists to answer questions an entity cannot: an entity can only be looked up by its id, and "every
cart containing product p1" is a question about all of them. A view is updated after the change it
reflects, so it is **eventually consistent**. [Views](../build/views.md) shows how.

### Consumer

A consumer reacts to a source's changes, as a view does, but keeps no rows. It either acts, by calling
other components through the component client, or publishes a message to a broker topic. Use it to turn
an internal event into a published one, to trigger work in another component, or to integrate with
something outside the service. Delivery is at least once, so what it does must tolerate a repeat.
[Consumers](../build/consumers.md) shows how.

### Workflow

A workflow is a durable multi-step process: a transfer between two accounts, a checkout that reserves
stock and takes payment. Commands start it and change its state; **steps** run one at a time, call other
components, and say what happens next. The runtime journals each transition before the next step starts,
so a workflow survives a restart mid-flight and resumes where it was. Timeouts, retries and a failover
step for compensation are declared as settings. [Workflows](../build/workflows.md) shows how.

### Timed action

A timed action is a call the runtime makes later. A component schedules a timer by name, with a delay and
the call to make; the runtime stores it in the database and calls the timed action's handler when it is
due, retrying with backoff until the handler reports success. Timers survive restarts. Use them for
deadlines, reminders and expiry. [Timers](../build/timers.md) shows how.

### Agent

An agent carries out a task by talking to a language model. Its handler returns an effect naming the
instructions, the user's message, the tools the model may call and the guardrails to apply; the runtime
runs the loop — calling the model, running tools, feeding their results back — until the model answers.
Conversation memory is kept per **session**, as an event sourced entity, so it survives restarts and can be
shared by several agents. Requests to one session are handled one at a time.
[Agents and sessions](agents.md) explains the model and [Agents](../build/agents.md) shows how to build one.

### HTTP endpoint

An HTTP endpoint is the service's edge. It declares routes, turns requests into component calls, and
states an **access control list** saying who may call it. Rejections from components carry their own error
codes, which the endpoint turns into HTTP statuses without mapping them itself. An endpoint can also stream
server-sent events, which is how agent responses reach a browser token by token.
[HTTP endpoints](../build/http-endpoints.md) shows how.

## Choosing a component

| You need to | Use |
|---|---|
| Enforce a rule over one thing's state, and keep its history | an event sourced entity |
| Enforce a rule over one thing's state, with no need for history | a key value entity |
| Find things by anything other than their id, or list them | a view over the entity |
| Do something else whenever a thing changes | a consumer |
| Tell another service that something happened | a consumer that produces to a topic |
| Coordinate several components through steps that must all complete or be compensated | a workflow |
| Make something happen after a delay, even across restarts | a timer and a timed action |
| Ask a model, possibly with tools and memory | an agent |
| Accept requests from outside the service | an HTTP endpoint |

Two rules settle most choices:

- **A rule that must always hold lives in one entity.** An entity is the only place where a check and the
  change it guards happen together, one command at a time. A rule that spans entities cannot be enforced
  by a single handler; it is a process, and belongs in a workflow.
- **Reads that are not by id go to a view.** Do not keep a list inside an entity to make listing easy; it
  grows without bound and serializes every change through one entity. Project it into a view instead.

[Designing a service](designing-services.md) applies these to a whole system, with a worked example.

## Registration

Every component is registered on the service builder explicitly. There is no classpath scanning, so the
registration is the complete inventory of what a service hosts, and a component that is not registered
fails at startup rather than at its first request.

**Scala**

```scala
Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime())
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .start()
```

**Python**

```python
await Ankka.service().register(ShoppingCartEntity).register(CartRows).register(ShoppingCartEndpoint).listen()
```

In Scala, some kinds need a runtime extension as well as registration: views and consumers need
`ProjectionRuntime`, timed actions need `TimerRuntime`, agents need `AgentRuntime`, and endpoints need
`HttpServer`. In Python the sidecar supplies all of them.
