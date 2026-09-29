<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/ankka-lockup-dark.svg">
  <img src="assets/ankka-lockup.svg" alt="ankka" width="400">
</picture>

[![ci](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml/badge.svg?event=pull_request)](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml) [![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE.md)

[![java 21+](https://img.shields.io/badge/java-21%2B-007396?logo=openjdk&logoColor=white)](docs/get-started/install.md) [![maven central](https://img.shields.io/maven-central/v/com.thinkmorestupidless/ankka-core_3?label=maven%20central&logo=apachemaven&logoColor=white)](https://central.sonatype.com/artifact/com.thinkmorestupidless/ankka-core_3)<br>
[![python 3.12+](https://img.shields.io/badge/python-3.12%2B-3776AB?logo=python&logoColor=white)](docs/get-started/install.md) [![pypi](https://img.shields.io/pypi/v/ankka?label=pypi&logo=pypi&logoColor=white)](https://pypi.org/project/ankka/)<br>
[![node 22.22+](https://img.shields.io/badge/node-22.22%2B-5FA04E?logo=nodedotjs&logoColor=white)](docs/get-started/install.md) [![npm](https://img.shields.io/npm/v/ankka?label=npm&logo=npm&logoColor=white)](https://www.npmjs.com/package/ankka)

A serverless application platform for agentic AI, built on the actor model — a component model
in Scala 3 on [Apache Pekko](https://pekko.apache.org/), with services in Scala, Python or
TypeScript.

You write components; ankka supplies the runtime. Sharding, persistence, replay,
projections, durable orchestration, timers, HTTP and the agent loop are the platform's
problem, not yours. A control plane, an operator and a CLI deploy, expose, scale and observe
the result on Kubernetes.

An event sourced entity keeps its state as the fold of the events it persisted. The same cart, in
each of the three languages:

```scala
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  def emptyState: ShoppingCart = ShoppingCart.empty(context.entityId)

  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

<details>
<summary><b>The same entity in Python</b></summary>

```python
class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")

    def empty_state(self) -> ShoppingCart:
        return ShoppingCart.empty(self.entity_id)

    def apply_event(self, state: ShoppingCart, event: ShoppingCartEvent) -> ShoppingCart:
        match event:
            case ItemAdded(item):
                return state.add_item(item)
            case ItemRemoved(product_id):
                return state.remove_item(product_id)
            case CheckedOut():
                return state.on_checked_out()
        raise AssertionError(event)

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)
```

</details>

<details>
<summary><b>The same entity in TypeScript</b></summary>

```ts
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
  }

  emptyState(): ShoppingCart {
    return emptyCart(this.entityId)
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":
        return addItem(cart, event.item)
      case "ItemRemoved":
        return removeItem(cart, event.productId)
      case "CheckedOut":
        return { ...cart, checkedOut: true }
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }
}
```

</details>

## Get started

### Install the CLI

On macOS, and on Linux with [Homebrew](https://brew.sh/), the CLI comes from ankka's tap as a native
executable that needs no JVM:

```bash
brew install thinkmorestupidless/tap/ankka
ankka version
```

Without Homebrew, take the native executable straight from a
[release](https://github.com/thinkmorestupidless/ankka/releases) — `linux-x64`, `linux-arm64`,
`macos-arm64` or `macos-x64`, each beside a `.sha256`:

```bash
version=0.7.0                                        # a release from the releases page
platform=linux-x64                                   # or linux-arm64, macos-arm64, macos-x64
base="https://github.com/thinkmorestupidless/ankka/releases/download/v$version"
curl -LO "$base/ankka-cli-$version-$platform.tar.gz" -LO "$base/ankka-cli-$version-$platform.tar.gz.sha256"
shasum -a 256 --check "ankka-cli-$version-$platform.tar.gz.sha256"
tar -xzf "ankka-cli-$version-$platform.tar.gz"       # one file, ankka: put it on your PATH
./ankka version
```

**There is no Windows build yet**, and the Linux executables need glibc 2.35 or later, so they do not
run on Alpine. Every release also carries a zip that runs anywhere with a JDK 21 on the `PATH`.
[Install the tools](docs/get-started/install.md) covers that, and the prerequisites and SDK for each
language.

### Create a service and run it

`ankka init` writes a complete service: an event sourced entity, a view, an HTTP endpoint, tests at
both levels, a `docker-compose.yml`, a Dockerfile, the deployment descriptor and GitHub workflows.

```bash
ankka init cart                          # Scala, and needs sbt on your PATH
ankka init cart --language python        # or Python
ankka init cart --language typescript    # or TypeScript
cd cart
```

Then, in that directory — Docker is needed whichever language, for the Postgres that holds the
journal, the views, the timers and the offsets:

```bash
sbt schema && docker compose up -d && sbt run                     # Scala
uv sync && docker compose up -d && uv run python -m cart.main     # Python
npm install && docker compose up -d && npm start                  # TypeScript
```

A Python or TypeScript service runs as its own process beside the **sidecar**, which owns everything
stateful and distributed. That image is not on a public registry yet, so build it once from a
checkout of this repository: `sbt sidecar/Docker/publishLocal`.

The service answers on port 9000:

```bash
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
curl localhost:9000/items/               # the view, which follows the journal a moment behind
```

```bash
ankka local console                      # http://localhost:9889
```

The console lists every ankka service running on this machine and, for each, its components, a form
per HTTP route, and the trace of every request it served.

### Deploy it

A local platform is a kind cluster and one script —
[Install a local platform](docs/platform/install-local.md). From there the CLI deploys the
`service.json` the template wrote:

```bash
ankka login
ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka services apply -f service.json
ankka services list                      # Ready
ankka services expose cart               # a hostname that answers
```

[Deploy to a local platform](docs/get-started/deploy-locally.md) walks the whole path.

The template's GitHub workflows do the same thing without you: push the project and it builds and
tests on every commit, and a version tag builds the image, pushes it to the repository's container
registry and deploys it. That needs a credential a machine can hold, which is what a **deploy token**
is — `ankka organizations tokens create acme --label github`, a few repository secrets, and nothing
about the installation to administer. See
[Deploy from GitHub Actions](docs/deploy/ci.md).

## Documentation

**[docs.ankka.cloud](https://docs.ankka.cloud/)**, and the same
pages as Markdown in [`docs/`](docs/index.md):

- **[Get started](docs/get-started/install.md)** — install the tools, write a first service in
  [Scala](docs/get-started/first-service-scala.md), [Python](docs/get-started/first-service-python.md)
  or [TypeScript](docs/get-started/first-service-typescript.md), and
  [deploy it to a local platform](docs/get-started/deploy-locally.md).
- **[Concepts](docs/concepts/architecture.md)** — how ankka works, the component model, and
  [designing a service](docs/concepts/designing-services.md).
- **[Build](docs/build/event-sourced-entities.md)**, **[Run and deploy](docs/deploy/run-locally.md)**,
  **[Observe and operate](docs/operate/local-console.md)**, **[Run the platform](docs/platform/install-local.md)**.
- **[Reference](docs/reference/cli.md)** — the CLI, the service descriptor, the control plane API,
  configuration, the SDKs, the sidecar protocol, and an honest list of [limitations](docs/reference/limitations.md).

For coding agents: the documentation ships as Agent Skills in every project made from the template and
in the Claude Code plugin published to [`ankka-marketplace`](https://github.com/thinkmorestupidless/ankka-marketplace), `ankka mcp` serves the platform's tools
over MCP, and the site publishes `llms.txt` and `llms-full.txt`. See
[Work with a coding agent](docs/get-started/coding-agents.md).

## Why Pekko

[Apache Pekko](https://pekko.apache.org/) is an Apache 2.0 actor runtime carrying everything a
distributed, stateful platform needs — typed actors, cluster sharding, persistence, projections,
streams and HTTP. Building on it means ankka's programming model comes with no licence constraint on
who runs it, or on what they run on it.

## This repository

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
cli               the `ankka` command, over HTTP; `ankka mcp` for agents
protocol          the sidecar protocol: .proto files, ENCODING.md, WASM-ABI.md, the encoding fixtures
sidecar           the runtime booted from a discovery handshake, for a service in another language —
                  a process beside it, or a WebAssembly module loaded into it
sdks/python       the Python SDK, its testkits, and the sample cart ported to it
sdks/typescript   the TypeScript SDK, its testkits, and the sample cart ported to it
sdks/rust         the Rust crate for services built to WebAssembly modules, and the sample cart
samples/          the shopping cart and the multi-agent planner
ankka.g8          the service template
action            the GitHub Action that installs and authenticates the CLI; pushed to ankka-action on release
homebrew          the Homebrew formula for the CLI; pushed to homebrew-tap on release
docs              the documentation; tools/docs builds it
marketplace       the Claude Code plugin: the documentation as skills, and `ankka mcp`; pushed to ankka-marketplace on release
kustomization     the platform's manifests and the local deploy script
```

Contributing starts at [`CLAUDE.md`](CLAUDE.md) — the architecture, the build, and the traps that have
already cost debugging time — and, for the documentation,
[Writing documentation](docs/contributing/documentation.md).

```bash
sbt -Dankka.cluster.tests=off test   # everything but the Kubernetes suites; Docker required
just docs                            # check and build the documentation
(cd sdks/rust && cargo build -p shopping-cart --release --target wasm32-unknown-unknown)   # the Rust cart, as a module
docker compose --profile wasm up -d  # the runtime hosting that module on :9000
```

## Licence

[Apache 2.0](LICENSE.md), matching Pekko.
