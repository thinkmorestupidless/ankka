<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/ankka-lockup-dark.svg">
  <img src="assets/ankka-lockup.svg" alt="ankka" width="400">
</picture>

[![maven central](https://img.shields.io/maven-central/v/com.thinkmorestupidless/ankka-core_3?label=maven%20central)](https://central.sonatype.com/artifact/com.thinkmorestupidless/ankka-core_3)
[![ci](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml/badge.svg)](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](https://www.apache.org/licenses/LICENSE-2.0)

A serverless application platform for agentic AI, built on the actor model — a
reimplementation of [Akka's](https://doc.akka.io/) component model in Scala 3 on
[Apache Pekko](https://pekko.apache.org/).

You write components; ankka supplies the runtime. Sharding, persistence, replay,
projections, durable orchestration and the agent loop are the platform's problem, not
yours.

```scala
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  def emptyState: ShoppingCart = ShoppingCart.empty(context.entityId)

  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", Conflict)
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

## Why Pekko

Akka moved to the Business Source Licence. Pekko is the Apache 2.0 fork of Akka 2.6 with
the same APIs — typed actors, cluster sharding, persistence, projections, streams, HTTP —
so ankka can offer Akka's programming model with no licence constraint on who runs it.

## Getting started

```bash
docker compose up -d                 # Postgres: journal, views, timers, offsets
sbt "shoppingCart/run"               # HTTP on :9000
```

```bash
curl -X POST localhost:9000/carts/c1/items \
  -H 'Content-Type: application/json' \
  -d '{"productId":"p1","name":"Widget","quantity":2}'

curl localhost:9000/carts/c1
curl -X POST localhost:9000/carts/c1/checkout
```

With that running, `ankka local console` shows you what it is doing — see
[below](#seeing-what-it-is-doing).

The agentic sample needs a key:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
sbt "multiAgentPlanner/run"
```

### Seeing what it is doing

There is a local console, and it needs the CLI — which is built from this repository rather than
installed, since there is no binary release yet:

```bash
sbt cli/stage                                   # builds target/universal/stage/bin/ankka
export PATH="$PWD/cli/target/universal/stage/bin:$PATH"

ankka local console                             # → http://localhost:9889
```

It finds every ankka service running on this machine by itself — a service announces where its
observability endpoint is listening, and the console lists them — so start it before or after your
service, the order does not matter, and leave it running while you restart things.

For each service: its registered components, a form per HTTP route so a request can be sent without
leaving the page, the traces of requests it has served, and — for a service with agents — a
session's stored conversation and the tokens it has cost.

To look inside an entity, the console runs the component's **own declared queries** against an id —
`get-cart` on the cart, and nothing the component did not itself publish. It cannot run a command:
`query` accepts only a `ReadOnlyEffect`, so the binding already knows which handlers can persist,
and asking for one answers `405 'add-item' is a command, not a query`. That is the compiler's
guarantee carried onto the wire, not a list of safe names the console maintains — which is also why
a component that declares no query shows none rather than having its journal read behind its back.

A trace is the useful part. It shows which components a request went through, how long each took,
and how much of the request the platform *cannot* account for:

```
POST /{cartId}/items           118 ms
├── shopping-cart#add-item     1.8 ms
└── unattributed               117 ms   (98%)
```

Two milliseconds in the entity, a hundred and seventeen somewhere else — that one is the journal
write. Time the platform cannot attribute — waiting on a model, waiting on a database, work a
handler handed to another thread — gets its own row rather than being spread across the spans to
make the percentages tidy, because it is usually the answer. It is measured per span, as the gap
between a span's own time and its children's; a leaf gets no such row, because its whole duration
is already attributed to it.

The invoke panel sends its request to the service's own HTTP port as an ordinary client, so an
endpoint's `acl` refuses the console exactly as it refuses `curl`. There is no privileged path
from the console to a handler.

The console is for services on your own machine: it binds loopback and holds no credential. For a
deployed service, the CLI reads what it printed:

```bash
ankka services logs cart --follow
ankka services logs cart --previous    # the container before the last restart
```

## Your first service

The samples above live in this repository. A service of your own starts from the template and
depends on ankka's published libraries — six of them: `ankka-core`, `ankka-sdk`, `ankka-runtime`,
`ankka-http`, `ankka-agent`, `ankka-testkit`, under `com.thinkmorestupidless`. Nothing else in this
build is a library.

```bash
sbt new thinkmorestupidless/ankka.g8 --name=orders     # or: ankka init orders
cd orders
sbt test                                               # an entity test, an endpoint test, an integration test
sbt schema && docker compose up -d && sbt run          # Postgres from the runtime's own schema; :9000
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
```

What comes out is the shape of an ankka service with a trivial domain — an `Item` with a name and a
count: one event sourced entity, one endpoint, one view, tests at each level, a compose file, image
packaging and a `service.json` — all named after your project. Replace the domain; keep the shape.
Its `README` continues from here to a running, exposed service:

```bash
sbt Docker/publishLocal && kind load docker-image orders:latest --name ankka
ankka services apply -f service.json && ankka services expose orders
curl --cacert ~/.ankka/local-ca.crt https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1
```

Until the first release is on Maven Central, `sbt publishLocal` in this repository puts the
libraries where the template's build finds them, and `ankka init` hands the template the version
it published (`--ankka_version`), so the two agree. `sbt new file:///path/to/ankka/ankka.g8` is
the same template from a checkout.

**Versions.** The platform — libraries, operator, control plane, CLI — is released as one version
from one tag. `service.json` declares the ankka version a service was built against, and the
platform checks it when it deploys: same major, and a minor equal to the platform's or one
below. A declaration outside that range is reported on `services get` as `Unavailable`, naming
both versions, and nothing starts; an undeclared version is not checked. It is a declaration —
the runtime also logs its version at start and serves it at `/ankka/version` on its management
port, which is what to compare against if the two might differ.

## The component model

| Component | What it is | Hosted as | In Python |
|---|---|---|---|
| **Event Sourced Entity** | State derived by replaying persisted events | `EventSourcedBehavior` in cluster sharding | yes, via the sidecar |
| **Key Value Entity** | Latest value only, no history | `DurableStateBehavior` in cluster sharding | yes, via the sidecar |
| **View** | Queryable projection of a source's changes | Pekko Projection → Postgres row table | yes, via the sidecar |
| **Consumer** | Reacts to changes, optionally publishes onward | Pekko Projection, or a Kafka consumer group | yes, via the sidecar |
| **Workflow** | Durable multi-step process | `EventSourcedBehavior` whose events *are* step transitions | yes, via the sidecar |
| **Timer** | A call the runtime makes later | Postgres table + cluster-singleton sweeper | yes, via the sidecar (timed actions) |
| **Agent** | Carries out a task by talking to a model | Sharded per **session id**, serialized per conversation | yes, via the sidecar — the loop stays in the sidecar |
| **HTTP Endpoint** | The outside world | pekko-http route tree | yes, declared over the protocol and served by the sidecar |

The last column is one feature: a service written in another language runs as its own process
with ankka's runtime beside it as a sidecar, speaking a protobuf protocol over gRPC on loopback.
See *Polyglot services* below.

### Effects are data

Every handler returns a *description* of what should happen. Building one performs no
I/O, reads no state and calls no model — which is why a component's decision-making can
be unit-tested with nothing running, and why the runtime is free to decide *how*.

```scala
effects.persist(ItemAdded(item)).thenReply(_ => Done)   // event sourced entity
effects.updateState(profile).thenReplyState             // key value entity
stepEffects.updateState(s).thenTransitionTo(deposit)    // workflow step
effects.systemMessage("...").tools(getWeather).thenReply()  // agent
```

### Registration is explicit

There is no classpath scanning. A service's components are a value you can inspect,
diff and test, and a component that was never registered fails at startup rather than
at its first request.

```scala
val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime.withPublisher(publisher))
  .withExtension(HttpServer.of(ShoppingCartEndpoint(_)))
  .start()
```

Handlers are registered on the companion, which is what replaces Akka's
`Entity::method` lambda-bytecode inspection:

```scala
object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):
  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem = command("add-item")(_.addItem)
  val getCart = query("get-cart")(_.getCart)
```

`command` and `query` differ by more than name: `query` accepts only a `ReadOnlyEffect`,
so "this handler cannot persist" is enforced by the compiler rather than by convention.

Call sites are then fully typed, with no reflection anywhere:

```scala
componentClient.forEventSourcedEntity(cartId).call(ShoppingCartEntity.addItem).invoke(item)
```

### Blocking is free

`invoke` blocks; `invokeAsync` returns a `Future`. Endpoints, workflow steps, consumers,
timers and agent loops all run on virtual threads, so an await parks the virtual thread
and releases its carrier. Sequential code stays readable *and* cheap.

### Query parameters and headers

Path parameters and the body arrive as typed arguments, because they are structural — a
route either has them or is not that route. Query parameters and headers are optional,
repeatable and vary per call, so they are read from the request instead:

```scala
get("/") { () =>
  SearchResult(
    term    = query.required[String]("q"),
    limit   = query.optional[Int]("limit").getOrElse(20),
    tags    = query.all[String]("tag").toList,
    verbose = query.flag("verbose")
  )
}

get("/trace") { () => request.header("X-Trace-Id").getOrElse("none") }
```

`required` fails with a 400 naming the parameter rather than substituting a default — a
missing parameter the handler needed is the caller's mistake, and silently defaulting
turns it into a puzzling empty result. The same instances parse query values and path
segments, so `?limit=abc` yields the same "expected int" message either way. A parameter
present with no value counts as a set flag, so `?verbose` and `?verbose=true` agree.

`request` is ambient rather than passed — a deliberate exception to ankka's usual
explicitness, and the same shape entities already use for `currentState`. It is sound
because each handler runs on its own virtual thread, so there is exactly one request per
thread and the value is cleared on the way out. The consequence: work handed to *another*
thread cannot see it, so read what you need before fanning out. For a streaming route
that means reading parameters while building the source, since its elements are pulled
later by pekko-http.

The same `RequestContext` is what an ACL predicate inspects, so a check on a header or
query parameter is looking at exactly what the handler will:

```scala
val acl: Acl = Acl.AllowIf(context => context.header("X-Api-Key").contains(expected))
```

## Agents

```scala
final class WeatherAgent extends Agent:
  def ask(question: String): Effect[String] =
    effects
      .systemMessage("You are a concise weather assistant.")
      .userMessage(question)
      .tools(WeatherAgent.getWeather)
      .thenReply()
```

**Tools are declared, not annotated.** The same `SchemaType` instance emits the JSON
Schema *and* decodes the arguments, so a parameter cannot be described one way and read
another — and arity or type mistakes are compile errors:

```scala
val getWeather = FunctionTool
  .named("get_weather")
  .describedAs("Returns the weather forecast for a given city.")
  .param[String]("location", "A location or city name.")
  .param[Option[String]]("date", "Forecast date, yyyy-MM-dd")
  .handle { (location, date) => forecast(location, date) }
```

**Session memory is an event-sourced entity.** That is what makes collaboration fall
out: several agents share a conversation by addressing the same session id, memory
events are subscribable for compaction, and history survives a restart because it is a
journal like any other.

**Agents are sharded by session**, and requests to one session are handled strictly one
at a time. Without that, two overlapping requests read the same history, both append to
it, and neither turn acknowledges the other.

A failing tool comes back to the model as an *error tool result*, not an exception —
which is what lets it recover, usually by fixing its arguments.

**Compaction.** A long session eventually outgrows any context window, so the oldest
messages can be replaced by a summary:

```scala
val agents = AgentRuntime
  .withDefaultModel(model)
  .withCompaction(CompactionSettings(maxHistoryBytes = 100_000, keepRecentMessages = 10))

Ankka.service
  .registerAll(agents.descriptors)      // session memory + the compactor
  .withExtension(agents)
  .withExtension(ProjectionRuntime())   // the compactor is a consumer
  .start()
```

It runs as a consumer over session memory's own events, so the turn that pushed a session
over the limit is not the one that waits for a summarisation call. Recent messages stay
verbatim — those are what the model needs in full — and everything older becomes one
`SummaryMessage`, which the agent loop replays as marked-up context. Compacting twice
folds the earlier summary into the later one rather than accumulating them, because the
previous summary is part of what the summariser is shown.

The summariser calls the `ModelProvider` directly rather than going through an `Agent`.
An agent would need a session, and the only sensible session is the one being summarised
— so it would append its own turns to the history it is trying to shrink. Bypassing the
agent layer removes the problem instead of configuring around it. Supply your own
`Summariser` to override.

Compaction is best-effort by design: a summariser that fails loses that one compaction
and the offset still advances. A consumer that kept throwing would never advance, which
would stall compaction for *every* session because one session's summarisation is
failing — a far worse outcome than a missed compaction. A summary no shorter than what it
would replace is also skipped, since that is not progress.

**Streaming.** A handler declared with `stream` returns tokens as they are generated:

```scala
final class WeatherAgent extends Agent:
  def chat(question: String): StreamEffect =
    effects
      .systemMessage("You are a concise weather assistant.")
      .userMessage(question)
      .tools(WeatherAgent.getWeather)
      .thenStream()
```

```scala
componentClient.forAgent(sessionId).stream(WeatherAgent.chat)(question)  // Source[String, NotUsed]
```

Every turn streams, not just the last: a model that says "let me check the weather"
before calling a tool is producing real output, and withholding it until the tool
returns is what makes a streaming UI feel broken.

Tokens are pushed to an `ActorRef` carried inside the request rather than returned, so
they flow from wherever the session is sharded to wherever the call was made — Pekko
serializes `ActorRef` natively, which a stream `SourceRef` nested in a message would not.
The session stays held for the stream's duration, so one conversation cannot interleave
two streams.

Serve it over HTTP with `sse`:

```scala
sse("/{session}") { (session: String) =>
  client.forAgent(SessionId(session)).stream(WeatherAgent.chat)(question)
}
```

Each event carries a JSON-encoded string. Raw text in an SSE `data:` field loses a
leading space to the protocol's own rules and a newline inside a token splits the frame
— both silent corruptions that only appear on text a model happened to generate.

## Polyglot services

A service in Python is the same component model, deployed the same way, hosted by the same
runtime — running as a **sidecar** beside your process instead of in the same JVM. The sidecar
owns everything stateful and everything distributed (sharding, the journal, snapshots, projections,
timers, HTTP, cluster formation, observability, the agent loop and the model key); your process
owns the decision: given this command and this state, what should happen. The two speak the
protocol in `protocol/` over gRPC on loopback, and your code never sees it — the SDK does.

```json
{ "name": "cart", "service": { "image": "my-cart:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

`docs/polyglot.md` is the walkthrough: an entity, an endpoint, and one of every other kind, in
Python, with the unit testkits and an integration testkit that starts the real sidecar image. What
makes a second SDK *compatible* is `ConformanceSuite` in `sidecar/src/test`, one case per behaviour,
run in-process against the Scala reference and through the sidecar against any process
(`uv run conformance` in `sdks/python`), plus the encoding fixtures in `protocol/fixtures` every
SDK's default codec must pass — that is what lets a cart written in Scala be read by one written in
Python on the same journal, and the reverse.

## Broker topics

Views and consumers can read from a topic as well as from an entity, and a consumer can
publish onward:

```scala
object StockLevels
    extends View.Companion[StockLevelsView, StockEvent, StockRow](
      componentId = ComponentId("stock-levels"),
      source = ChangeSource.fromTopic("stock-events", Codecs.serializer[StockEvent]("stock-event")),
      rowSerializer = Codecs.serializer[StockRow]("stock-row")
    )
```

```scala
Ankka.service
  .register(StockLevels.descriptor)
  .withExtension(ProjectionRuntime.withKafka("localhost:9092"))
  .start()
```

CloudEvents attributes travel as Kafka headers rather than wrapping the payload, so a
consumer in another language reads a plain JSON body with metadata beside it.
`ce-subject` doubles as the record key, which is what preserves per-entity ordering:
Kafka guarantees order within a partition, and keying by subject puts every message about
one entity on the same partition.

Offsets commit to Kafka and partitions are assigned by consumer groups, rather than going
through ankka's projection offset store. Rebalancing across nodes then needs no code —
at the cost of topic sources being at-least-once and unable to rebuild from history, which
is all a topic can offer anyway, since a broker's retention is not an event journal.

`InMemoryBroker` implements both halves of the SPI, so the whole topic path — headers,
subject keying, decoding, view writes, consumer dispatch, republishing — is testable with
no broker running. `KafkaPublisher` and `KafkaSubscriber` are the real pair, and a topic
component started without either is rejected at startup rather than silently never
delivering.

## Control plane and CLI

ankka's control plane is an ankka application. Tenancy is three event sourced entities,
listings are three views, the API is three endpoints — the thing that operates ankka
services is built out of the same components those services are.

That is not a slogan. It means the control plane inherits sharding, replay and
projections instead of reimplementing them, every `apply` leaves an audit trail because
the journal *is* the audit trail, and a bug in the platform shows up in the tool you use
to operate the platform.

A service's desired state is a descriptor:

```json
{
  "name": "cart",
  "service": {
    "image": "registry.example.com/acme/cart:1.4.2",
    "env": [
      { "name": "LOG_LEVEL", "value": "info" },
      { "name": "API_KEY", "secretKeyRef": { "name": "cart-secrets", "key": "api-key" } }
    ],
    "resources": {
      "instanceType": "small",
      "autoscaling": { "minInstances": 1, "maxInstances": 10, "targetCpuPercent": 80 }
    }
  }
}
```

```bash
docker compose up -d                       # Postgres, and Keycloak with the platform's realm
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run

ankka config set url http://localhost:9000
ankka login                                # user dev, password dev — a code to type into a browser

ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout

ankka services apply -f cart.json
ankka services list
# NAME  STATUS            INSTANCES  GEN  IMAGE
# cart  UpdateInProgress  0/0        1    registry.example.com/acme/cart:1.4.2

ankka services pause cart
ankka services restart cart
ankka services list -o json | jq '.[].lifecycle'
```

The control plane run this way never reaches a cluster, so `cart` sits at `UpdateInProgress`
forever — there is nothing on the other end of `apply` yet. Running against a real cluster looks
the same from the CLI's side, but this time something is listening:

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
# or `just up`, which does both — see the Justfile for `down`, `deploy`, `console`, `test`

ankka config set url https://api.127.0.0.1.sslip.io:8443   # printed by the script
ankka config set ca ~/.ankka/local-ca.crt                    # the local cluster's root, exported by it
ankka login                              # user dev, password dev; users are added at https://auth.<base>:8443/admin/

ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout

echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
ankka services apply -f cart.json
ankka services list
# NAME  STATUS  INSTANCES  GEN  IMAGE
# cart  Ready   1/1        1    sample-shopping-cart:latest

kubectl -n ankka-checkout get asvc,deploy,svc,pods   # the operator's own work, visible directly
```

No `kubectl port-forward` anywhere in that: the control plane answers at a real address, over TLS,
and the CLI trusts the local cluster's root because it was told to — never because verification
was switched off (there is no switch). The `kind.yaml` matters: it publishes the gateway's ports on
your machine, and kind only does that at creation, so a cluster made with the bare one-liner is
refused by the deploy script with the fix named.

That descriptor is the whole of it: an image, and nothing about databases or ports. The platform
provisions the service a database of its own, applies the schema, gives it an in-cluster address,
and reports `Ready` only once it has joined its cluster and bound its port. It is the shopping
cart sample, running for real — and private, until you say otherwise:

```bash
ankka services expose cart
# https://cart-checkout.127.0.0.1.sslip.io:8443

curl --cacert ~/.ankka/local-ca.crt -XPOST https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1/items \
     -H 'content-type: application/json' -d '{"productId":"p1","name":"Widget","quantity":2}'
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Widget","quantity":2}],"checkedOut":false}

ankka services restart cart                                          # rolls; the URL keeps answering
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1   # the same cart
ankka services unexpose cart                                         # the hostname stops; nothing else changes
```

### Deploying a service written in Python

The same platform hosts a service in another language, and the descriptor says so in two fields:

```json
{ "name": "cart", "service": { "image": "my-cart:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

What that changes about deploying the platform itself:

- **A fourth image, `ankka-sidecar`.** `deploy-local.sh` builds and `kind load`s it with the operator,
  the control plane and the sample; the release workflow pushes it to the registry on a tag. A
  cluster that pulls needs it at the operator's tag, because the operator is what injects it.
- **The operator is told where it is.** `ANKKA_SIDECAR_IMAGE` on the operator's Deployment names
  the image: the local tag in the operator manifest, the registry image by patch in a remote
  overlay (`kustomization/overlays/arrakis/sidecar-image.yaml`). Unset, a process-hosted service
  fails with `operator has no sidecar image` rather than starting the wrong one.
- **The CRD and the operator are re-applied.** `AnkkaService` gained `hosting` (default
  `embedded`), and only the operator and the control plane changed behaviour. The schema is
  unchanged: no database migration.

What it changes about the service's pod:

- **Two containers.** The sidecar carries the ports, the readiness probe and the database
  credential; the app container has no ports, no probe and small fixed resources. Scale, restart,
  expose and pause behave exactly as for a Scala service.
- **The descriptor's `env` is split.** `ANTHROPIC_*`, `ANKKA_MODEL_*` and `ANKKA_DB_*` go to the
  sidecar, everything else to the app — so a model key is supplied as before, and the app never
  sees the database.
- **Loopback only.** The two talk on 9010 and 9011 inside the pod; no Service or NetworkPolicy
  changes. `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the sidecar is needed only for a topic-sourced view
  or a producing consumer, and the sidecar names the variable when it refuses to start without it.

A cluster that runs only Scala services notices none of this beyond the extra image.

### Ports and addresses

A descriptor's `service` block takes two optional fields:

| Field | Default | Meaning |
|---|---|---|
| `port` | `9000` | the port the workload listens on — the runtime's own default |
| `http` | `true` | `false` for a service that serves no HTTP at all |

From that one resolved value the operator renders three things that therefore cannot disagree: the
container port, `ANKKA_HTTP_PORT` (so the runtime binds where Kubernetes expects it), and a
`ClusterIP` Service named after the service. Inside the cluster a service is reachable at
`<service>` from its own project's namespace, and at `<service>.<prefix>-<project>.svc.cluster.local`
from anywhere else.

Setting `ANKKA_HTTP_PORT` yourself in `env` is refused at apply time: the `port` field is the only
way to say it, because two ways to say one thing is how an address ends up routing to a port
nothing listens on. With `"http": false` none of the three is rendered.

Readiness is not tied to the port. The probe is `/ready` on the runtime's management port, and it
answers 200 only once the instance is a member of the service's cluster *and* every part of the
runtime with an opinion agrees — the HTTP server, once it has bound. An image that is not an ankka
service has no such endpoint and is never `Ready`: `registry.k8s.io/pause` is reported `Failed`
when the rollout's deadline passes, and that is the intended answer, not a gap. See *Instances and
clusters* below for why membership is the test.

`deploy-local.sh` builds all four images with a single root-level `sbt docker:publishLocal` —
it aggregates to every project with `DockerPlugin` enabled (`operator`, `controlPlane`, the
`shoppingCart` sample and `sidecar`, the runtime that hosts a service written in another language;
see `docs/polyglot.md`) and silently skips the rest, the same way `sbt compile` and `sbt test`
already do; adding the third and the fourth needed no change to the command. `sbt buildAll` is the same idea one
level up: format check, compile, test and every image, one command, stopping at the first failing
stage.

The script then loads them into the cluster with `kind load docker-image`, installs CloudNativePG,
and applies the CRD, the operator, the control plane's own CNPG-managed database and the control
plane — no image registry, on purpose. Every workload is rendered with `imagePullPolicy:
IfNotPresent`, which is what lets an image loaded that way be used at all: Kubernetes' default for
a `:latest` tag is `Always`, which ignores it and fails with `ErrImagePull`.
Setting `DOCKER_REPOSITORY` (see `build.sbt`'s `dockerSettings`) is the whole change needed once
one exists. It refuses to run against any `kubectl` context other than a `kind-*` one it is told
to target, since it is meant for a disposable cluster, never whatever context happens to be
current.

### Exposing a service

A service is private by default: reachable at its in-cluster address and nowhere else. Exposing
it is a decision made after deploying, by a command — the model Akka's platform uses, and the one
ankka's own `pause`/`resume` already follow — so `apply` never changes it:

```bash
ankka services expose cart      # prints the URL
ankka services unexpose cart    # removes the route; the service is untouched
ankka services get cart         # hostname  https://cart-checkout.127.0.0.1.sslip.io:8443
```

The hostname is the platform's to derive: **`<service>-<project>.<base domain>`**, the control
plane itself at `api.<base domain>`. One label under the base domain — not `cart.checkout.…` —
because a wildcard is exactly one label deep, for the certificate and for the gateway's listener
alike, and the platform serves everything under *one* wildcard certificate that belongs to the
installation. Two consequences follow, and both are refused at `expose` time rather than ever
producing a hostname that does not work: a label longer than 63 characters, and a hostname another
exposed service already holds (`a-b` in project `c` and `a` in project `b-c` both derive `a-b-c`).
Two services with the *same* name in different projects never collide — that is what the project
is in the name for.

Under the hood: the operator renders a Gateway API `HTTPRoute` into the service's namespace,
attached to a `Gateway` the installation owns (`kustomization/components/gateway`), which admits
routes only from namespaces the platform created. The route's backend is the service's own
in-cluster address, so it inherits readiness: a request by hostname never reaches an instance that
is not a cluster member with its port bound. The operator holds RBAC for `httproutes` and nothing
else about routing — not gateways, not certificates, not secrets — and a deployed service's own
identity cannot read routes at all.

**TLS, always.** Plain HTTP is redirected; there is no HTTP-only mode. Locally the deploy script
creates a certificate authority inside the cluster, has cert-manager issue the wildcard from it,
and exports the root to `~/.ankka/local-ca.crt`. Nothing on your machine is asked to trust it —
the CLI is told (`config set ca`) and so is `curl` (`--cacert`) — and neither the CLI nor any
documented command has an option to skip verification. A real installation supplies its own
wildcard: a DNS-01 issuer, or a bought certificate in the `ankka-wildcard-tls` secret; HTTP-01
cannot issue wildcards, so a DNS provider cert-manager supports is a production prerequisite of
this design.

**Exposure changes who can reach an endpoint, not who is allowed to.** Every endpoint declares an
`acl`, and `DenyAll` is the stated default posture; an exposed `AllowAll` endpoint on a real
installation is on the internet. Decide the ACL before the `expose`.

**Local DNS.** The local base domain is `127.0.0.1.sslip.io`: a public wildcard-DNS convention
that resolves any name ending in an embedded address to that address, so nothing on your machine
is configured. The ports are `8443` and `8080` rather than 443 and 80 because a developer's machine
routinely has those taken — the port is part of the URL, never of the certificate or the hostname
rule. If your resolver blocks sslip.io, add a hosts-file line and redeploy with a matching domain:

```bash
echo '127.0.0.1  api.ankka.local cart-checkout.ankka.local' | sudo tee -a /etc/hosts
ANKKA_BASE_DOMAIN=ankka.local ./kustomization/deploy-local.sh
```

### Instances and clusters

A service runs as `resources.autoscaling.minInstances` pods, and those pods are **one cluster**:
one set of entity ids sharded across them, one journal with one writer per entity, cluster
singletons — the timer sweeper, the projections — on exactly one of them. `minInstances` is
honoured as a fixed count; `maxInstances` and `targetCpuPercent` are validated and carried but
no autoscaler is rendered.

Nodes find each other through the Kubernetes API. The operator gives each service a
ServiceAccount, a Role that can list pods in its own namespace and nothing else, and the five
environment variables the runtime's Kubernetes overlay needs (`ANKKA_CLUSTER_MODE=kubernetes`,
`POD_IP`, the Service to discover through, the pod label selector, the contact-point count). A
descriptor that sets any of them itself is refused at apply time, the same as `ANKKA_HTTP_PORT`.
The runtime's base configuration says nothing about peers at all: a laptop run gets the `local`
overlay (join self, or `ANKKA_CLUSTER_SEED_NODES` for a two-terminal cluster), a pod gets the
`kubernetes` one, and the startup code is the same in both — see `CLAUDE.md`'s *Cluster formation
is an overlay*.

What that buys, each measured on a real cluster by `MultiNodeClusterSuite`:

- **Deploys and restarts are zero-downtime, at any instance count.** Deployments roll with
  `maxSurge: 1, maxUnavailable: 0`: a new pod joins the existing cluster and takes its shards by
  handoff before an old one is stopped. At one instance the surge pod bootstraps into the old pod's
  cluster, so a single-instance service deploys with no gap either. A reader hammering an entity
  through the update sees no failed request.
- **A cold start of N pods forms one cluster, not N.** Cluster Bootstrap needs `min(N, 2)` contact
  points before it will form a new cluster — enough that two pods starting together find each
  other, few enough that one unschedulable pod does not hold the service down. Twenty consecutive
  cold starts of three pods: one cluster every time.
- **A crashed node (SIGKILL) is downed and replaced; a partition resolves by majority.**
  `keep-majority` split-brain resolution downs the minority side, which exits so Kubernetes
  restarts it into a clean rejoin. A pod that had been downed but kept running would be `Running`,
  never ready, forever — which is why the Kubernetes overlay alone sets `exit-jvm = on`.
- **Use an odd count.** `keep-majority` needs a majority to exist: with two instances a
  partition leaves one on each side, neither a majority, and *both* are downed — the service is
  out until Kubernetes restarts them. Three survives the loss of one; one, trivially, has no
  partition to survive.
- **Scaling does not roll.** Changing `minInstances` from 3 to 4 adds one pod; the three keep
  running. Only `services restart` rolls the pods, through a separate counter on the pod template,
  so an apply that changes nothing Kubernetes cares about changes nothing Kubernetes sees.

Locally there is no API server to ask, so the `local` overlay names peers instead:

```bash
ANKKA_CLUSTER_PORT=17355 sbt shoppingCart/run
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt shoppingCart/run
```

A cart added through `:9000` reads back through `:9001` — one cluster, two terminals. With
neither variable set a node joins itself, which is why a plain `sbt shoppingCart/run` needs no
configuration at all.

The control plane runs the same way — three replicas, discovered through the same API, with the
same `/ready`. Its status watch runs on every node and its projection sweeper on one, and the
duplicate observations that produces are absorbed by the service entity's own "an identical
observation is refused" rule rather than by any coordination.

### Desired state and observed state

A `ServiceEntity` holds both: `descriptor` and `generation` are what an operator asked
for; `lifecycle`, `readyInstances` and `desiredInstances` are what the cluster reports.
Reconciliation is closing the gap.

`generation` is what makes that safe. It increments on every apply and every restart, and
an observation carries the generation it describes — so a report arriving late from a
superseded deployment is recognised and dropped rather than overwriting the state of a
newer one. The check lives in the *fold*, not the command handler, so replay drops the
same observation every time.

Two observations are refused: a stale generation, and one identical to what is already
recorded. The second matters more than it looks — the reconciler runs on a timer, so
without it a steady-state service would grow its journal forever.

### What the entity deliberately does not do

`ServiceEntity` never talks to Kubernetes. A command handler that performed I/O could not
be replayed, and the point of persisting desired state separately from acting on it is
that the two fail independently: an apply records intent and returns, and a cluster that
is unreachable is a reconciliation problem rather than a lost request.

Cross-entity checks — "does this organization exist", "does this project still have
services" — live at the endpoint, not in a handler. An entity cannot see another entity's
state, and a handler that called out to fetch it would be making a check it could not
hold anyway. Doing it at the edge catches the typo, which is what the check is for.

### Tenancy ids are not reused; service names are

Deleting an organization or a project is a tombstone: the entity remains, `exists` goes
false, and creating the id again is a `409`. A tenancy boundary that quietly came back
carrying someone else's projects and audit trail would be worse than an error.

A service name is different — it is a deployment target, not a boundary — so re-applying
a descriptor for a name you removed recreates it, with the generation still climbing.

### The CLI is a thin client

`cli` depends on `controlplane-api` and nothing else: no actor system, no database
driver, no Kubernetes client. That module holds the wire types *and* the validation
rules, so a bad descriptor is rejected before the round trip using the same code the
server will run — and the end-to-end suite drives `Main.run` directly, which is the only
way to catch the two ends disagreeing about the wire format.

Settings resolve flags → environment → `~/.ankka/config.json`. The environment sits in
the middle so CI can point the CLI elsewhere without writing to a home directory it may
not have. Exit codes are `0` ok, `1` failed, `2` misused. The token is never printed, in
either output format.

### Who may operate the platform

Every call to the control plane carries an OpenID Connect access token from the installation's
own identity provider — a Keycloak that is part of the platform, deployed by the same command as
everything else, in its own namespace with its own database, reachable at `https://auth.<base>`.
There is no shared token, and no compatibility mode for one: a secret shared by everyone who
operates a platform is not identity, and the control plane's journal is only an audit trail once
every write names who asked for it.

Two responsibilities, kept apart on purpose. **Keycloak authenticates**: it decides who is a user
of the installation (self-registration is off; an administrator adds people in its console at
`/admin/`), signs their tokens, and holds their passwords, sessions and second factors — none of
which the control plane ever sees. **The control plane authorizes**: it verifies a token offline
against the realm's published keys (no call to Keycloak on the request path once the keys are
cached), takes the token's stable subject as the caller's identity, and answers every question
about organizations and membership from its own event-sourced state. It holds no credential for
Keycloak's administration and needs none.

`ankka login` is the OAuth 2.0 device authorization grant: the CLI asks the control plane where
the issuer is (`GET /auth`, the one route that answers without a credential), prints an address
and a short code, and waits while you sign in — in any browser, on any device, so it works over
SSH. The login it saves is renewable without a browser, lives in `~/.ankka/credentials.json`
readable by you alone, keyed by control plane URL, and is never printed. `ankka logout` forgets
it; `ankka whoami` says who you are. A non-interactive client — a CI job — passes a token it
obtained itself (a confidential client's client-credentials grant) through `ANKKA_TOKEN` or
`--token`, and that token is presented exactly as given: a machine identity is just another
Keycloak principal, so there is no second kind of credential to create, rotate or audit.

**Organizations are the boundary.** Anyone logged in may create one and becomes its first
*owner*; owners invite people by email (`ankka organizations members add acme --email
bob@example.test`), and the invitation becomes a membership the first time a token with that
email — *verified* by Keycloak — arrives, on the next listing or on the first thing they try to
do. *Members* create projects and deploy, pause, restart, expose and delete services in the
organization's projects; owners also manage members and rename or delete the organization. The
last owner cannot be removed or demoted. Membership is recorded on the organization's own journal
and checked on every request against the entity — never against a listing — so removal takes
effect on the very next request. What a non-member gets for an organization, project or service
they are not in is exactly what they get for one that never existed: a `404`. Listings show only
your organizations, each with your role and whether it is active.

One installation-level role lives in Keycloak: `platform-admin`, a realm role, whose holders see
every organization, can add an owner to one whose owners have all left (`members repair`), and
can *disable* an organization — every service in its projects is suspended, its members can read
but change nothing, and re-enabling brings back exactly what was running (a service its members
had paused stays paused). Every recorded change carries who asked for it and when, and whether
the platform-admin role was what let them do it; `ankka services history cart` reads it back.
Changes recorded before this existed show no actor.

**A machine is a Keycloak client.** For CI, create a confidential client in the console with
service accounts enabled and the `ankka-controlplane` scope assigned (and, if it is to be invited
by email like anyone else, an email on its service-account user, marked verified — `ankka
organizations members add` then works for it exactly as for a person). The job obtains a token
with the client-credentials grant and passes it as given:

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=ci-deployer -d "client_secret=$SECRET" \
  https://auth.example.com/realms/ankka/protocol/openid-connect/token | jq -r .access_token)
ANKKA_TOKEN="$TOKEN" ankka services apply -f cart.json      # no browser, no prompt, no saved login
```

The apply's history names the client as its actor. A request without a valid token is a `401`
with a `WWW-Authenticate` challenge, which the CLI turns into "run `ankka login`"; a valid token
for an action its holder may not take is a `403`.
The realm is one file, `kustomization/components/keycloak/realm.json`, imported by the operator
in a cluster and mounted by docker-compose locally: the public `ankka-cli` client with the device
grant, a client scope that puts the control plane's audience and the identity claims it reads on
every token, and the `platform-admin` realm role. The realm import is one-shot — it creates a
realm and never updates one — so a change to that file on an existing installation is applied in
Keycloak's console, not by redeploying.

## Testing

Two levels, both real.

**Unit — no actor system, no cluster, no database.** Effects are inert values, so this
is milliseconds. Inputs and replies still round-trip through the component's own
serializers, so a missing codec fails here rather than on first deployment.

```scala
val kit    = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

assertEquals(result.replyValue, Done)
assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
```

**Integration — the whole service against a throwaway Postgres**, using the same DDL
docker-compose applies. `restartService()` drops every entity from memory so a test can
prove durability rather than caching.

Agents are tested against `TestModelProvider`, which answers from a script and *fails
loudly* when the script runs out — a test whose model quietly returned a default is no
longer testing what it says.

```bash
sbt test          # 454 tests, no API key needed
```

Integration suites start their own Postgres, the Kafka suite its own broker, and two
suites a single-node Kubernetes cluster, all via testcontainers. Docker is required; no
API key is. `sbt -Dankka.cluster.tests=off test` skips the two that need a cluster —
everything else, including the whole reconciliation loop against a fake, still runs.

## Deliberate divergences from Akka

| Akka | ankka | Why |
|---|---|---|
| `@Component`, classpath scanning | explicit `register(...)` | A missing component fails at startup, not at first request |
| `Entity::method` lambda inspection | typed companion handles | No reflection; call sites checked by the compiler |
| `@FunctionTool` + reflection | `FunctionTool` builder | Schema and decoder come from one instance and cannot disagree |
| Bespoke SQL-like view query language | real SQL over a JSON row column | No parser to build, strictly more expressive, indexes are explicit |
| Route order decides dispatch | literal segments outrank parameters | `/users/me` should not depend on being declared before `/users/{id}` |
| ACL by absent annotation | abstract `def acl` | An unstated ACL is a decision nobody made |
| `budget_tokens` / `temperature` | `effort`, adaptive thinking | Current Claude models reject both outright |
| `apply -f service.yaml` | `apply -f service.json` | A YAML parser on the CLI's classpath for a cosmetic difference |
| `minInstances` defaults to 3 | defaults to 1 | A laptop-sized default. Three is what you want in production and one is what you want while trying the platform out; the number is honoured either way |
| Four service lifecycle states | seven | `NotDeployed`, `Paused` and `Failed` are distinctions Akka's four cannot express |

## Layout

```
modules/core      effects, ids, codecs, component descriptors — no Pekko, no I/O
modules/sdk       the component API: entities, workflows, views, consumers, timers, client
modules/runtime   interprets effects: sharding, persistence, projections, timers
modules/http      endpoint DSL and server
modules/agent     model providers, session memory, function tools, the agent loop
modules/testkit   unit and integration test support
controlplane-api  descriptors, statuses and validation shared by the server and the CLI
controlplane      the control plane, built as an ankka application
crd               the AnkkaService custom resource — the contract, no ankka dependencies
operator          the Kubernetes operator: watches resources, owns the workloads
cli               the `ankka` command, over HTTP
protocol          the sidecar protocol: .proto files, ENCODING.md, the encoding fixtures
sidecar           the runtime booted from a discovery handshake, for a service in another language
sdks/python       the Python SDK, its testkits, and the sample cart ported to it
samples/shopping-cart          entities, views, consumers, HTTP
samples/multi-agent-planner    dynamic + parallel multi-agent orchestration
```

Dependencies run strictly `core → sdk → runtime → {http, agent} → testkit → samples`,
with `controlplane-api → controlplane → cli` hanging off `core` and the runtime, and
`crd → operator` hanging off nothing at all — the resource contract is held by both the
control plane and the operator, so it inherits neither one's world.

## Not implemented

Honest gaps, not oversights:

- **One non-Scala SDK.** Python is the only one; a third language arrives through the conformance
  suite and the encoding fixtures, which define what "compatible" means without the platform
  knowing the language exists. The Python SDK is in this repository and not yet on PyPI.
- **Multi-region.** Single-region only. No replication filters, no `origin` routing.
- **Cluster traffic is neither isolated nor encrypted.** Remoting on 17355 and management on
  7626 are plain TCP on the pod network, reachable from any namespace, the same as the HTTP port.
  Pod-label selection stops a node *choosing* a stranger as a peer; nothing stops a stranger
  connecting. Artery TLS and a `NetworkPolicy` are the fixes, and neither is built.
- **Autoscaling.** `minInstances` is a fixed count. `maxInstances` and `targetCpuPercent` are
  validated and stored but nothing acts on them; no HorizontalPodAutoscaler is rendered.
- **Databases are provisioned automatically, one per service.** The operator manages
  [CloudNativePG](https://cloudnative-pg.io/) `Cluster`, `Database` and `DatabaseRole`
  custom resources: one shared `Cluster` per project, and one `Database`/`DatabaseRole`
  pair per service within it, with a generated credential secret and a schema-init
  container that applies the same single-copy DDL a service's own database needs before
  its main container starts.

  **One database per service, and this is not a style preference.** `ankka_timers` has no
  service column; `TimerSweeper` polls it unfiltered and *deletes* any row whose component
  id is not in its own registry. Two services sharing a database therefore delete each
  other's timers. View row tables are named from the component id alone and collide the
  same way, as do projection offsets. CNPG's default `PUBLIC CONNECT` grant is explicitly
  revoked per database for exactly this reason — one service's database is unreachable by
  another's credentials, not merely conventionally separate.

  **Nothing this platform does may destroy a database.** Deleting a service deletes its
  Deployment, never its `Database` — the operator's own RBAC withholds `delete` on every
  CNPG resource and on `secrets`, so this is enforced by the API server, not by discipline.
  Re-applying a previously deleted service's name recovers its existing data and reports
  `recovered: true` in its status, rather than starting clean.

  **The escape hatch remains, for a specific reason.** A descriptor whose own `env`
  declares a `ANKKA_DB_*` variable is bringing its own database — checked by variable name,
  not value, so a `secretKeyRef`-sourced value counts too. Nothing is provisioned for it,
  and `ankka services get` reports `supplied` rather than `provisioned`. This exists for
  the case a provisioned, single-instance `Cluster` cannot yet serve: an existing database
  with data to migrate, or a durability profile CNPG's defaults do not cover. It does not
  extend cross-service isolation — a supplied database's isolation is whatever its owner
  configured, not something ankka verifies.
- **A registry, still.** Images are built into the local Docker daemon and `kind load`ed; the
  template's `README` says so. `DOCKER_REPOSITORY` in the platform's build is the one switch.
- **The declared runtime is trusted.** `service.json`'s `runtime` is checked, not the image: a
  descriptor can lie, and the platform does not yet compare it with what the pod reports at
  `/ankka/version`. No compatibility matrix either — one rule, one minor of slack.
- **The CLI is `sbt cli/stage`.** No binary release, no package; `target/universal/stage/bin/ankka`
  on `PATH`. `ankka init` needs `sbt` on `PATH` for the same reason.
- **`sbt new thinkmorestupidless/ankka.g8` waits on the first release**, which pushes the
  template to that repository; until then the `file://` form from a checkout.
- **Custom hostnames.** An exposed service's hostname is the platform's to derive; there is no
  way to give a service a domain of your own. That needs the user to own DNS and certificates for
  a domain the platform does not control, and is a feature of its own.
- **Nothing at the route but routing.** No authentication, rate limit or header policy at the
  gateway; who may call an endpoint is the endpoint's `acl`. HTTP/1.1 only through the gateway —
  no gRPC or HTTP/2 upstream — and one gateway per installation.
- **Identity is for operating the platform, not for the services it hosts.** The control plane
  verifies Keycloak's tokens; a deployed service's endpoints still keep whatever `acl` their author
  wrote, and the platform provisions no realm, client or token check for them. That is a feature of
  its own — a realm per project, a credential per service delivered like `ANKKA_DB_*`, and a token
  ACL in the HTTP module — and it is why Keycloak runs under its operator here: it will be an
  added resource, not a replaced deployment. Per-project roles and a read-only role are not built
  either; membership is per organization, as owner or member.
- **Readiness means the node has joined and bound, not that the application is healthy.** The
  probe is the runtime's own `/ready`; it does not call a route, because the operator knows
  neither a workload's routes nor its ACL. There is deliberately no liveness probe: entities
  rehydrate from the journal, so restarting a pod for a slow GC costs more than the GC did.
- **Projects are not a network boundary.** A `ClusterIP` is reachable from every namespace, so any
  project's pods can call any other project's service. Projects separate names and databases —
  the latter enforced, down to a revoked `PUBLIC CONNECT` — but not traffic. That would be
  `NetworkPolicy`, and is not built.
- **One port, HTTP only.** No second port, no other protocol.
- **No image registry locally.** `sbt-native-packager` builds the operator's, the control plane's
  and the sample's images straight into the local Docker daemon, and `kustomization/deploy-local.sh`
  loads them directly into a `kind` node with `kind load docker-image` — nothing is pushed
  anywhere. A release tag is different: the release workflow pushes all three to the shared
  registry in the `ankka-ops` project (`europe-west2-docker.pkg.dev/ankka-ops/ankka`), through a
  keyless identity defined in the `ankka-deployments` repository, and a cloud environment's overlay
  names them from there. Setting `DOCKER_REPOSITORY` (see `build.sbt`) and changing the deploy target is
  the whole migration once one exists; nothing about the images or the manifests changes.
- **The k3s test suites mostly use kind's/testcontainers' default admin credentials, not
  the shipped RBAC**, so most of what the ClusterRoles in
  `kustomization/components/{operator,controlplane}/` grant is exercised by *use*, not by
  a client actually scoped to them — a missing verb there fails silently in CI and loudly
  on a real deploy. It already has: `ensureNamespace` uses server-side apply, which is
  always a PATCH even for an object that does not exist yet, so `create` alone on
  `namespaces` 403s on every project's first service. Deploying against a real cluster is
  what caught it; nothing in `sbt test` would have. One narrow exception exists: a test
  mints a real token for the operator's own ServiceAccount and asserts the API server
  itself, not just ankka's own code, refuses a `Database` delete — proving the withheld
  verb is structural rather than merely unused.
- **The console is local only.** `ankka local console` serves the services running on your own
  machine and nothing else: it binds loopback, holds no credential, and reads entity state only
  through the queries a component declared for itself. A console over a *deployed*
  installation is a feature of its own — the expensive half of it is reassembling one trace from
  several pods' separate windows, not the authentication — and the data it would read is already
  shaped for it (a service carries a list of instances, and `partial` means "this window does not
  hold it all" rather than "spans aged out"). Deployed services are served by `ankka services logs`
  and by metrics instead.
- **Traces are a window, not a history.** Every component invocation is recorded, always, into a
  fixed ring — 4096 spans by default, the one tuning knob — and the oldest are overwritten. Nothing
  is persisted, there is no sampling and no query language, and a trace whose older spans have gone
  is reported as partial rather than returned as a tree that merely looks whole. Recording costs
  about 22ns per span against a request measured at 0.64ms, which is what makes always-on a
  defensible default rather than a hope.
- **Time the platform cannot account for is reported, not distributed.** A model call, a database
  wait, or work a handler hands to another thread all show as *unattributed* on the trace, because
  the request context is a thread-local and cannot follow work to another thread. A span whose
  parent has gone stays at the root marked "parent unknown" and is never re-parented to the nearest
  plausible candidate: a tree that reads correctly and describes something that did not happen is
  worse than a visible hole.
- **Token counts, but not money.** Agent usage is reported as tokens, because the provider reports
  tokens. Cost needs a price the platform is told, there is no price table yet, and a model without
  one shows cost as *unknown* rather than as zero — a zero would be read as free.
- **`ankka services logs` is not a log store.** It reads what Kubernetes holds for a pod at the
  moment of asking: no search, no aggregation, no retention. A service that has restarted many
  times has lost everything but its last two containers, which is Kubernetes' behaviour reported
  rather than papered over. Reading logs is also the one thing that widened the control plane's
  rights — it now holds `get` on `pods` and `pods/log`, which changes what a compromised control
  plane *discloses* though not what it can *do*: every mutating verb is still withheld, `pods/exec`
  included.
- **No console for a deployed installation.** The CLI is the only client for anything in a cluster.
- **Cross-entity checks are edge checks.** "The project still has services" is counted
  from a projection, so a service created moments earlier may not be counted yet. It
  guards against the obvious mistake; it is not a transactional constraint.
- **Multi-table views**, view rebuild-on-deploy, and Akka's `@SnapshotHandler`
  projection optimisation.
- **Topic-source caveats.** At-least-once, so a topic-sourced component must tolerate
  duplicates, and it cannot rebuild from history — it sees only what was published after
  it started. A message with no `ce-subject` is skipped by a view rather than retried,
  since there is no row it could key off. Only Kafka ships; other brokers implement the
  two-method SPI.
- **Streaming caveats.** Output guardrails on a streaming handler run *after* the tokens
  have been delivered, so they can stop memory being written but cannot un-send what the
  reader saw — use input guardrails for anything that must never be shown. Only agents
  stream; entities and workflows reject a streaming request rather than ignoring it.

## Licence

Apache 2.0, matching Pekko.
