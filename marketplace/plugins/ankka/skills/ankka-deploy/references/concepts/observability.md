# Observability

> What ankka records about every request — spans, traces, unattributed time, a service's topology of declared connections and observed calls, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.

Source: https://docs.ankka.cloud/concepts/observability/
Every ankka service records every component invocation, always, with nothing to switch on. Locally you
read those records in [the local console](../operate/local-console.md). In a cluster they are served as
metrics on each instance's management port, and a service's output is read with
[`ankka services logs`](../operate/logs.md).

## Spans and traces

Each time a component handles something — an endpoint serving a request, an entity handling a command, a
view applying an event, a consumer handling a message, a workflow running a step, an agent or an autonomous
agent answering — the runtime records a **span**: which component, which
handler, when it started, how long it took, and how it ended. A span started while handling another
span is its child, and a tree of spans rooted in one request is a **trace**. A trace shows which
components a request went through and how long each one took:

```text
POST /{cartId}/items           118 ms
├── shopping-cart#add-item     1.8 ms
└── unattributed               117 ms   (98%)
```

Recording costs a few tens of nanoseconds per span against requests that take fractions of a
millisecond at the least, which is what makes recording everything a reasonable default rather than
something to sample.

A workflow's step appears inside the trace of the request that started the workflow, and a call the step
makes appears inside the step:

```text
POST /checkout/{id}                 41 ms
├── checkout#start                  2.1 ms
└── checkout#reserve                31 ms
    └── shopping-cart#total-quantity 1.4 ms
```

Because every one of these kinds records a span, a request that passes through workflows, agents or
consumers fills the fixed window sooner than one that touches only entities.

### Unattributed time

The row that matters most is often **unattributed**: time inside a span that none of its children
account for. In the first trace shown here the entity took two milliseconds, and the other hundred and
seventeen were spent writing to the journal. An agent waiting on its model shows that wait as the agent
span's unattributed time. Waiting on a model, waiting on a database, and work a
handler hands to another thread all appear this way. The platform reports that time as its own row
rather than spreading it over the spans it can see, because it is usually the answer to "why was that
slow". A span with no children has no such row, since all of its time is already its own.

### Orphans are shown, not guessed

Trace context follows the handler's own thread. Work handed to another thread cannot carry it, so a span
started there has no known parent. Such a span stays at the root of the trace, marked as having an
unknown parent. It is never attached to the nearest plausible candidate: a tree that reads correctly and
describes something that did not happen is worse than a visible gap.

### How a span ended

A span ends in one of four ways: `Ok`, `Refused`, `Failed` or `TimedOut`. A **refusal** is a handler
deciding to say no, such as a command rejected with an error effect; a **failure** is something going
wrong, such as an exception. They are recorded differently because they mean different things: a
refused command is the application working, and a console that painted it red would teach its reader to
ignore the colour.

## Topology

A service's **topology** is what it is made of and how the parts connect. It has two kinds of edge, and
they mean different things.

- **Declared connections** come from what the components registered: a view that reads an entity's events,
  a consumer that reads a topic or publishes to one. They are complete, because they are read from the
  service's own declarations, whether or not anything has happened yet.
- **Observed calls** are the calls one component's handler made to another's, counted as they happened over
  a recent window, ten minutes by default. They are the calls made in the window, not every call the
  service can make: a call that was not made in that time is not shown. Each observed call is broken down
  by pair of handlers, such as the route `POST /carts/{cartId}/items` calling the command `add-item`, and
  no entity id or request path is ever recorded.

An observed call has two counts, kept apart. **Handled** is counted where the handler ran, as `ok`,
`refused` or `failed`. **Unanswered** is counted where the caller got no answer, as `timedOut` or
`undelivered`. They are never added together: a handler that fails after its caller stopped waiting is
both handled, as failed, and unanswered, as timed out, because both ends saw something true. A failure or
an unanswered call marks an observed call as going wrong; refusals alone never do. Durations are read
from bucketed histograms, so each figure is approximate and says so.

A call is attributed to the handler that made it by a name the runtime itself writes into the call, and
believed only when it names a component and handler the service declared. A call made from a thread no
handler is running on, or carrying a name the service never declared, is from the **unknown caller**,
drawn as a node of its own. It follows the same rule as an orphan span: it is shown as unknown and never
given to the nearest plausible handler.

Calls to other services are observed calls too, to a node for the other service, counted by the request's
method and never by its path. Services beyond a limit, 32 by default, are counted together as other
services, so no call is dropped and the names kept stay bounded. A call to another service is a span of its
own, under the calling handler's, and the service that is called continues the trace under it: one request
across several services is one trace, by HTTP, by gRPC and through a message on a topic. In an instance's
own window the callee's part of such a trace is partial, since its parent is on another instance; a
collector holds the whole of it.

The local console's own reads, such as running a query on an entity, are not counted as calls.

## A window, not a history

Spans go into a fixed-size ring in each instance's memory, 4096 spans by default, and the oldest are
overwritten. Memory use is therefore fixed rather than a function of traffic or uptime. The capacity is
the one tuning setting, `ankka.observability.ring-capacity`.

Nothing is persisted and nothing is sampled. A trace whose oldest spans have already been overwritten is
reported as **partial** rather than returned as a tree that only looks complete. The window is meant to
explain what just happened, not to be a record of the past. Where the installation names an OpenTelemetry
collector every instance also exports its spans and its invocation counts there, and the collector's store
is the history; see [Telemetry](../operate/telemetry.md).

## Token usage

For agents, the platform records the tokens every model call used — input, output, and cache reads and
writes where the provider reports them — and keeps a running total on each session. The local console
shows a session's stored conversation and its tokens.

Cost is not shown in money. The provider reports tokens, and turning them into money needs a price the
platform has not been told; cost is reported as unknown rather than as zero, because zero would be read
as free.

## Where to read it

| Where the service runs | What you read | How |
|---|---|---|
| Your machine | services, components, topology, traces, sessions, entity state through declared queries | `ankka local console` |
| A cluster | a service's topology, merged across its instances | `ankka services topology`, or a service's topology page in [the console](../operate/console.md) |
| A cluster | invocation counts and time by component, handler and outcome | `GET /ankka/metrics` on each instance's management port, in Prometheus text format |
| A cluster | every service's traces and metrics, across services and over time | the collector the installation names; on a local platform, the telemetry store at `https://grafana.<base domain>` — see [Telemetry](../operate/telemetry.md) |
| A cluster | what the service printed | `ankka services logs`, or a service's logs page in [the console](../operate/console.md) |
| A cluster | a service's state, history and instances | `ankka services get` and `history`, or [the console](../operate/console.md) |

Locally, each service serves its records on a loopback address with a random port and announces itself
in `~/.ankka/running`, which is how the console finds every service on the machine. In a cluster, the
same records are served on the management port instead, next to the readiness probe. The metrics are
counts over the current window, not counters since the process started, so a monitoring system should
not compute rates by differencing them across scrapes; the exported `ankka.invocations` count since the
instance started. The platform ships no dashboards and no alerts.

## What is not there

- **No traces or sessions of a deployed service in the console.** The installation's console and the CLI
  show a deployed service's topology, status, history and logs, but not its traces, sessions or entity
  state; a deployed service's traces are in the collector the installation names.
- **No trace history in an instance.** Each instance's window holds what just happened; a history is the
  collector's.
- **No cross-instance traces in an instance's window.** A request whose components ran on several
  instances, or several services, has its spans in several windows; the collector joins them by trace id.
- **No log store.** `ankka services logs` reads what Kubernetes holds for a pod at the moment you ask.
