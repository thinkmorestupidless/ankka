# The local console

> Use `ankka local console` to see every ankka service running on your machine — its components, a form per HTTP route, the traces of recent requests, entity state and agent sessions.

Source: https://docs.ankka.cloud/operate/local-console/
`ankka local console` is a web page over every ankka service running on your machine. For each service it
shows the components it registered, a form for each HTTP route, the traces of the requests it has served,
the state of an entity read through the component's own queries, and the conversation and token usage of
an agent session. It needs no configuration, no login and no control plane.

## Start it

The console is part of the CLI. See [Install the tools](../get-started/install.md) for putting `ankka`
on your `PATH`.

```bash
ankka local console
# Local console: http://localhost:9889
```

It opens a browser at that address. Pass `--no-open` to only print it, and `--port` to prefer another
port. If the port is taken, for example by a console already running in another terminal, the console
takes the next free one and says so rather than failing.

Start it before or after your services; the order does not matter, and it can stay running while you
restart them. It stops with ctrl-c.

## How it finds services

Every ankka service run outside Kubernetes starts a small observability endpoint on a loopback address
with a random port, and writes a file announcing it to `~/.ankka/running`. The console reads that
directory, asks each entry whether it is still answering, and removes entries for services that have
gone. A service run with `sbt run`, from a test, or as a Python process beside a local sidecar all
appear.

A service is listed under the name it was started with. For a Scala service that is the name passed to
`start`, which defaults to `ankka`, so give each service its own when you run several:

```scala
Ankka.service.register(ShoppingCartEntity.descriptor).start(name = "cart")
```

## Components

The Components tab lists what the service registered: each component's kind and id, and the queries it
declares.

To read an entity's state, choose a component, enter an entity id and run one of its queries — `get-cart`
on the shopping cart, for example. The console runs **only the queries the component declared for
itself**, and only queries that take no argument. It cannot run a command: asking for one is refused with
`405` and a message saying the handler is a command, not a query. That guarantee comes from the
component, not from the console. A handler declared with `query` can only return a read-only effect, so
it cannot persist anything, and the console refuses every handler not declared that way. A component that
declares no queries shows none; the console never reads an entity's journal behind its back.

## Invoke

The Invoke tab has a form for each HTTP route the service serves: pick the route, fill in the path, the
body and the content type, and send. Streaming routes show their events as they arrive.

The request goes to the service's own HTTP port as an ordinary client request. An endpoint's ACL
therefore refuses the console exactly as it would refuse `curl`; there is no privileged path from the
console to a handler. A service with `"http": false` serves no routes and has nothing here.

## Traces

The Traces tab lists the recent requests the service handled. Opening one shows the
tree of spans it produced:

```text
POST /{cartId}/items           118 ms
├── shopping-cart#add-item     1.8 ms
└── unattributed               117 ms   (98%)
```

Each row is a component and handler with the time spent in it. **Unattributed** is time inside a span
that none of its children account for, such as a journal write, a model call or a database wait, and is
often where the answer is. A span whose parent the runtime could not know, because the work ran on
another thread, stays at the root and is marked as having an unknown parent. A trace marked **partial**
has lost its oldest spans to the recorder's fixed window.

Refused requests, such as a command rejected with an error effect, are shown differently from failed ones,
because a refusal is the application working as designed. See [Observability](../concepts/observability.md)
for how traces are recorded.

## Agents

For a service with agents, the Agents tab takes a session id and shows that session's stored
conversation — messages, model replies, tool calls and results — and the tokens it has used. Cost is
shown as unknown, because the platform is not told any prices.

## What it will not do

- **It serves your machine only.** It binds loopback, holds no credential, and reads only services that
  announced themselves locally. There is no console for a deployed installation; use
  [logs](logs.md) and the metrics described in [Observability](../concepts/observability.md).
- **It keeps no history.** Traces come from each service's in-memory window and disappear when the
  service restarts.
- **It never changes state on its own.** The only way it changes anything is a request you send from the
  Invoke tab, through the same door every other client uses.
