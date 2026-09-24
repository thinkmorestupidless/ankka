---
title: Observability
description: What ankka records about every request — spans, traces, unattributed time, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.
kind: concept
related: [operate/local-console.md, operate/logs.md, reference/runtime-endpoints.md]
---

# Observability

Every ankka service records every component invocation, always, with nothing to switch on. Locally you
read those records in [the local console](../operate/local-console.md). In a cluster they are served as
metrics on each instance's management port, and a service's output is read with
[`ankka services logs`](../operate/logs.md).

## Spans and traces

Each time a component handles something — an endpoint serving a request, an entity handling a command, a
view applying an event, an agent answering — the runtime records a **span**: which component, which
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

### Unattributed time

The row that matters most is often **unattributed**: time inside a span that none of its children
account for. In the trace shown here the entity took two milliseconds, and the other hundred and
seventeen were spent writing to the journal. Waiting on a model, waiting on a database, and work a
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

## A window, not a history

Spans go into a fixed-size ring in each instance's memory, 4096 spans by default, and the oldest are
overwritten. Memory use is therefore fixed rather than a function of traffic or uptime. The capacity is
the one tuning setting, `ankka.observability.ring-capacity`.

Nothing is persisted and nothing is sampled. A trace whose oldest spans have already been overwritten is
reported as **partial** rather than returned as a tree that only looks complete. The window is meant to
explain what just happened, not to be a record of the past; for that, send metrics to a monitoring system
of your own.

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
| Your machine | services, components, traces, sessions, entity state through declared queries | `ankka local console` |
| A cluster | invocation counts and time by component, handler and outcome | `GET /ankka/metrics` on each instance's management port, in Prometheus text format |
| A cluster | what the service printed | `ankka services logs` |

Locally, each service serves its records on a loopback address with a random port and announces itself
in `~/.ankka/running`, which is how the console finds every service on the machine. In a cluster, the
same records are served on the management port instead, next to the readiness probe. The metrics are
counts over the current window, not counters since the process started, so a monitoring system should
not compute rates by differencing them across scrapes. The platform ships no dashboards and no alerts.

## What is not there

- **No console for a deployed installation.** The console reads services on your own machine only. For
  a deployed service you have its logs and its metrics.
- **No trace history.** Traces live only in each instance's window.
- **No cross-instance traces in a cluster.** A request whose components ran on several instances has
  its spans in several windows.
- **No log store.** `ankka services logs` reads what Kubernetes holds for a pod at the moment you ask.
