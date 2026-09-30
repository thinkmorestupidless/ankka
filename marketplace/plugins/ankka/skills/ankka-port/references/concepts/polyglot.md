# Services in other languages

> How ankka hosts a service in Python, TypeScript or Rust — the runtime runs beside a process as a sidecar or loads a WebAssembly module, owning everything durable while the service's code decides.

Source: https://docs.ankka.cloud/concepts/polyglot/
A service written in Python or TypeScript is hosted by the same runtime as one written in Scala, with the same
components, journal, cluster, deployment and console. What differs is where the code runs. A Scala
service compiles into one JVM with the runtime. A Python or TypeScript service runs as its own process, and the ankka
runtime runs beside it as a **sidecar**, booted from a conversation with that process instead of from a
Scala builder. A Rust service is built to a **WebAssembly module**, and the same runtime loads the module
into its own process instead of running beside one; see
[Services as WebAssembly modules](#services-as-webassembly-modules).

## Who owns what

The split follows the idea every ankka component is built on: a handler decides what should happen, and
the runtime makes it happen.

| The sidecar owns | The process owns |
|---|---|
| sharding, the journal, snapshots and durable state | given this command and this state, which events or new state and which reply |
| projections for views and consumers, and the rows they write | what an event does to a view's row |
| timers and when they fire | what a timed action does when it fires |
| the workflow engine: transitions, timeouts, retries, failover | what each workflow step does and where it goes next |
| the agent loop: model calls, session memory, compaction, token counts, the model's key | an agent's plan, its tools and its guardrails |
| HTTP: binding the port, ACLs, routing | what each route does |
| cluster formation, readiness and observability | nothing |

![Where an agent runs. In Scala, the agent and the ankka runtime share one JVM in one container: the handler returns an effect describing the request, and the runtime runs the loop, running the agent's tool and guardrail as ordinary method calls. In Python or TypeScript, the pod has two containers: your process, listening on loopback port 9010, and the runtime as a sidecar, listening on 9011. They speak protobuf over gRPC on loopback: the sidecar asks the process to Plan a request, InvokeTool and CheckGuardrail, and the tool's call to the cart entity goes back through the sidecar's Client Invoke. In Rust, the pod has one container: the runtime, with your service's WebAssembly module loaded into its JVM. They speak the same protobuf messages across the module's memory, with no network: the runtime calls the module's exports ankka1_plan, ankka1_invoke_tool and ankka1_check_guardrail, and the tool's call to the cart entity goes through the ankka1 invoke import. In all three, only the runtime calls the model provider and writes to the service's Postgres.](../assets/diagrams/agent-hosting.svg)

Because the process holds no durable state, it can be restarted, redeployed or crash without losing
anything. An entity whose process is briefly unavailable is re-opened when the process returns; callers
waiting at that moment are told the service is unavailable and the component client retries such a
refusal briefly, so a rolling replacement refuses nothing.

## The protocol

The two speak a protobuf protocol over gRPC, on loopback inside one pod. Your code never sees it; the SDK
does. The process listens on port 9010 and answers the sidecar's questions: handle this command, apply
this event, run this step, plan this agent request. The sidecar listens on port 9011 for the process's
own calls to other components, views and timers, which is what the SDK's component client uses. Both
ports are bound to loopback, so nothing outside the pod can reach either.

When the sidecar starts, it asks the process to **discover** itself: which components it hosts, their
handlers and wire names, which are read-only, which stream, the settings the engine must enforce for a
workflow, the tools an agent offers, and the HTTP routes and ACLs of its endpoints. The sidecar validates
all of it and reports every problem at once, both in its own log and back to the process, so a service
with four mistakes takes one restart to fix, not four. It then hosts what was described. The sidecar
waits up to 60 seconds for the process to answer discovery before giving up.

The protocol is versioned `MAJOR.MINOR`, and a process-hosted service declares the version its SDK
speaks. The platform accepts a declaration with the same major version and a minor version no higher
than its own. See [Sidecar protocol](../reference/sidecar-protocol.md).

## One journal, four languages

Stored data does not record which language wrote it. The Python, TypeScript and Rust SDKs' default encoding produces the same
JSON the Scala SDK does: records with every field present, sum types with a `"type"` discriminator
naming the case, `null` for an absent optional value, and primitives such as a string or an integer as
plain text. The mapping is written down in the protocol's
[ENCODING.md](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md) and checked by
shared fixtures that every SDK must pass.

So a cart written by a Scala service can be read by a Python, TypeScript or Rust service on the same database,
and the reverse. Field names are the contract. A Python dataclass or a TypeScript shape that stores a field
as `product_id` does not read a journal that says `productId`, which is why the Python and TypeScript
samples use the stored spelling.

## Deployed, a service is two containers

A descriptor says a service is process-hosted in two fields:

```json title="service.json"
{ "name": "cart", "service": { "image": "my-cart:1.0.0", "hosting": "process", "protocol": "1.2" } }
```

The image holds only your process. The platform adds the sidecar, at the version that matches the
platform, and the descriptor cannot name its image or set its variables.

- **The sidecar carries the service's outward face**: its HTTP and management ports, the readiness
  probe, the database credential and the cluster membership. Scaling, restarting, exposing and pausing
  behave exactly as for a Scala service.
- **The app container has no ports and no probe**, and small fixed resources.
- **The descriptor's environment is split by name.** Variables starting `ANTHROPIC_`, `ANKKA_MODEL_` or
  `ANKKA_DB_` go to the sidecar; everything else goes to your process. A model key is therefore supplied
  in the descriptor as usual, and your process never sees it or the database.
- **A producing consumer or a topic-sourced view needs a broker.** Set `ANKKA_KAFKA_BOOTSTRAP_SERVERS`;
  the sidecar refuses to start without it and names the component that needs it.

## Services as WebAssembly modules

A service can instead be built to a WebAssembly module, which the runtime loads into its own process.
The division of work is the same as with a process — the module decides, the runtime does everything
durable and distributed — and so is the conversation: the same messages, carried across the module's
linear memory through the functions it exports and the ones the runtime lends it, instead of over gRPC.
The Rust SDK builds services this way. See [WebAssembly ABI](../reference/wasm-abi.md).

- **Deployed, it is one container.** The descriptor says `"hosting": "wasm"`, and the image holds only
  the module. The platform runs the service's image once, as an init container that copies the module
  into a volume the pod shares, and then runs its own runtime image with the module loaded. The
  service's outward face — ports, probe, credential, cluster membership — is the runtime's, as for a
  Scala service.

  ```json title="service.json"
  { "name": "cart", "service": { "image": "my-cart-module:1.0.0", "hosting": "wasm", "protocol": "1.1" } }
  ```

- **A module reaches nothing but the runtime.** It has no network, no file system and no clock of its
  own: it calls other components, queries views and sets timers through the runtime, reads the time the
  runtime hands it, and reads its configuration through a `config` call that answers the descriptor's
  variables and withholds the platform's own — a model key, the database's credentials, the cluster's
  settings. Because one container has one environment, that withholding happens when the module asks,
  not when the pod is rendered.
- **Two shapes of guest, chosen per component.** A *stateless* component is handed its state on every
  call and keeps nothing between calls. A *stateful* one is handed its state once, when its instance is
  loaded, and keeps it until the runtime unloads it, which saves decoding the state on every command.
  Either way the runtime holds the encoded state too, so a module that faults loses nothing: the call
  that faulted fails, and the next one starts from the state the runtime holds.
- **What it costs, and what it saves.** A command handled by a module skips the round trip to a process
  entirely, and there is no second container to size or restart. In exchange a running call cannot be
  interrupted — a call that exceeds the runtime's timeout is abandoned and its instance replaced — a
  route cannot stream, and a deployed module cannot be debugged the way a process can. The module
  format is WebAssembly's core modules with nothing but ankka's own imports, so a language needs a guest
  library that targets that, as the Rust SDK does.

## What makes an SDK compatible

A second language is compatible when it behaves the same, not when it looks the same. ankka defines that
with a conformance suite: one case per behaviour, from "a deleted entity written to again starts empty"
to "a query answered while a workflow step runs", run against the Scala reference in-process and against
any process through the real sidecar, and against any module loaded by the real runtime, in both guest
shapes. The Python, TypeScript and Rust SDKs pass it, and a further language arrives by passing it with its
own reference service, plus the encoding fixtures. The platform needs no change to host it.
See [Adding a language SDK](../contributing/language-sdks.md).
