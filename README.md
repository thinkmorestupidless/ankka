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

![The components of one ankka service and how they communicate: callers reach an HTTP endpoint; the endpoint, workflow steps, agent tools, consumers and timed actions all call components through the component client; agents, workflows and entities write to the service's Postgres journal or durable state; projections of those changes feed views and consumers; stored timers fire timed actions; agents call the model provider; views and consumers can read Kafka topics and consumers can publish to them.](docs/assets/diagrams/components.svg)

You write components; ankka supplies the runtime. Sharding, persistence, replay,
projections, durable orchestration, timers, HTTP and the agent loop are the platform's
problem, not yours. A control plane, an operator and a CLI deploy, expose, scale and observe
the result on Kubernetes.

An agent carries out a task by talking to a model. Its handler describes the interaction — the
instructions, the user's message, the tools the model may call and the guardrails to apply — and the
runtime runs the loop: it calls the model, runs the tools it asks for, keeps the conversation as session
memory and counts the tokens. The shopping cart's assistant, whose one tool looks up a cart entity, in
each language:

```scala
final class CartAssistant extends Agent:

  private def describe(question: String) =
    val client = componentClient

    val lookup = FunctionTool
      .named("lookup")
      .describedAs("Looks up what is in a cart by its id.")
      .param[String]("cartId", "The id of the cart to look up.")
      .handle { cartId =>
        val cart = client
          .forEventSourcedEntity(EntityId(cartId))
          .call(ShoppingCartEntity.getCart)
          .invoke()
        if cart.items.isEmpty then s"cart $cartId is empty"
        else cart.items.map(item => s"${item.quantity} x ${item.name}").mkString(", ")
      }

    effects
      .systemMessage(
        "You help shoppers with their carts. Use the lookup tool before answering about a cart."
      )
      .userMessage(question)
      .tools(lookup)
      .guardrails(CartAssistant.noSecrets)

  def ask(question: String): Effect[String] = describe(question).thenReply()

  def chat(question: String): StreamEffect = describe(question).thenStream()

object CartAssistant extends Agent.Companion[CartAssistant](ComponentId("assistant")):

  val noSecrets: Guardrail = new Guardrail:
    val name = "no-secrets"
    override def checkOutput(text: String): Either[String, Unit] =
      if text.contains("sk-") then Left("a key leaked") else Right(())

  def create(context: AgentContext) = new CartAssistant

  val ask  = command("ask")(_.ask)
  val chat = stream("chat")(_.chat)
```

<details>
<summary><b>The same agent in Python</b></summary>

```python
@dataclass(frozen=True)
class CartLookup:
    cartId: str


async def _lookup(agent: Agent, arguments: CartLookup) -> str:
    assert agent.client is not None
    cart = await agent.client.for_event_sourced_entity("shopping-cart", arguments.cartId).call("get-cart").invoke(reply=ShoppingCart)
    if not cart.items:
        return f"cart {arguments.cartId} is empty"
    return ", ".join(f"{i.quantity} x {i.name}" for i in cart.items)


class CartAssistant(Agent):
    component_id = "assistant"
    tools = {"lookup": Tool("Looks up what is in a cart by its id.", _lookup, CartLookup)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: "a key leaked" if stage == "output" and "sk-" in text else None)}

    def _describe(self, question: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
            .user_message(question)
            .tools("lookup")
            .guardrails("no-secrets")
            .then_reply()
        )

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self._describe(question)

    @stream("chat")
    def chat(self, question: str) -> AgentEffect[str]:
        return self._describe(question)
```

</details>

<details>
<summary><b>The same agent in TypeScript</b></summary>

```ts
const CartLookup = s.record("CartLookup", { cartId: s.string })

export class CartAssistant extends Agent {
  static readonly componentId = "assistant"
  static readonly role = "helps shoppers with their carts"

  static readonly tools = {
    lookup: tool("lookup", "Looks up what is in a cart by its id.", CartLookup, (a: CartAssistant, input) => a.lookup(input.cartId)),
  }

  static readonly guardrails = {
    noSecrets: guardrail("no-secrets", (stage, text) => (stage === "output" && text.includes("sk-") ? "a key leaked" : null)),
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: CartAssistant, question) => a.describe(question)),
    chat: stream("chat", s.string, (a: CartAssistant, question) => a.describe(question)),
  }

  describe(question: string) {
    return this.effects
      .systemMessage("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
      .userMessage(question)
      .tools("lookup")
      .guardrails("no-secrets")
      .thenReply()
  }

  async lookup(cartId: string): Promise<string> {
    const cart = await this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
    if (cart.items.length === 0) return `cart ${cartId} is empty`
    return cart.items.map((i) => `${i.quantity} x ${i.name}`).join(", ")
  }
}
```

</details>

<details>
<summary><b>The same agent in Rust</b></summary>

```rust
#[derive(Debug, Deserialize)]
pub struct CartLookup {
    #[serde(rename = "cartId")]
    pub cart_id: String,
}

pub struct CartAssistant;

impl CartAssistant {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You help shoppers with their carts. Use the lookup tool before answering about a cart.",
        )
        .user_message(question)
        .tools(["lookup"])
        .guardrails(["no-secrets"])
        .then_reply()
    }

    fn lookup(args: CartLookup, ctx: &Context) -> Result<String, String> {
        let cart: Cart = ctx
            .client()
            .invoke(ShoppingCart, &args.cart_id, "get-cart", ())
            .map_err(|e| e.message)?;
        if cart.items.is_empty() {
            return Ok(format!("cart {} is empty", args.cart_id));
        }
        let lines: Vec<String> = cart
            .items
            .iter()
            .map(|i| format!("{} x {}", i.quantity, i.name))
            .collect();
        Ok(lines.join(", "))
    }

    fn no_secrets(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if stage == Stage::Output && text.contains("sk-") {
            Err("a key leaked".to_string())
        } else {
            Ok(())
        }
    }
}

impl Agent for CartAssistant {
    const COMPONENT_ID: &'static str = "assistant";

    fn handlers() -> AgentHandlers<CartAssistant> {
        AgentHandlers::new().command("ask", CartAssistant::ask)
    }

    fn tools() -> Tools<CartAssistant> {
        Tools::new().tool(
            "lookup",
            "Looks up what is in a cart by its id.",
            Schema::object().string("cartId", "the cart's id"),
            CartAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<CartAssistant> {
        Guardrails::new().guardrail("no-secrets", CartAssistant::no_secrets)
    }
}
```

A service in Rust is a WebAssembly module, and a module cannot stream a reply, so this agent has `ask`
and no `chat`.

</details>

## How your code is hosted

In Python and TypeScript the loop runs in the runtime beside your process, and in Rust in the runtime your
service's WebAssembly module is loaded into; either way your code is only called back to run a tool or
check a guardrail — so it never holds the model's key. [Agents](docs/build/agents.md)
covers memory, structured replies, streaming and compaction.

![Where an agent runs. In Scala, the agent and the ankka runtime share one JVM in one container: the handler returns an effect describing the request, and the runtime runs the loop, running the agent's tool and guardrail as ordinary method calls. In Python or TypeScript, the pod has two containers: your process, listening on loopback port 9010, and the runtime as a sidecar, listening on 9011. They speak protobuf over gRPC on loopback: the sidecar asks the process to Plan a request, InvokeTool and CheckGuardrail, and the tool's call to the cart entity goes back through the sidecar's Client Invoke. In Rust, the pod has one container: the runtime, with your service's WebAssembly module loaded into its JVM. They speak the same protobuf messages across the module's memory, with no network: the runtime calls the module's exports ankka1_plan, ankka1_invoke_tool and ankka1_check_guardrail, and the tool's call to the cart entity goes through the ankka1 invoke import. In all three, only the runtime calls the model provider and writes to the service's Postgres.](docs/assets/diagrams/agent-hosting.svg)

## The platform

The platform runs on Kubernetes. The CLI talks to a **control plane**, which records what you asked for
and writes one `AnkkaService` resource per service into the project's namespace; an in-cluster
**operator** watches those resources and creates everything each service needs — its instances, its own
database, and a route when it is exposed. [How ankka works](docs/concepts/architecture.md) explains the
split.

![The ankka platform on Kubernetes: the CLI and CI jobs reach the control plane through the installation's gateway, and sign in with Keycloak. The control plane writes one AnkkaService resource per service into the project's namespace; the operator watches those resources, creates and owns each service's Deployment, database and route, and writes status back. Callers reach an exposed service through the same gateway. A Scala service is a Deployment of JVM instances forming one Pekko cluster; a Python or TypeScript service is your process beside the runtime; a Rust service is the runtime with your WebAssembly module loaded into it. Each has its own database in the project's Postgres.](docs/assets/diagrams/platform.svg)

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
