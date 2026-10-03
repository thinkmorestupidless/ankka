# Contract: the secret key in a cluster, and the platform's variables

What the operator renders and what one declaration decides. Decisions are in
[research.md](../research.md) (R11, R12).

## The secret key's Secret

| | |
|---|---|
| Name | `<service>-secret-key`, in the project's namespace |
| Type | `Opaque` |
| Data | one entry, `key`: the standard base64 of 32 random bytes |
| Labels | the service's identity labels |
| Owner reference | none: it outlives the service's resource |
| Made | once, by `create`; `409 AlreadyExists` is success. Never read, patched or deleted by the operator |

`Action.EnsureSecretKey(namespace, name, labels)` describes it and holds no key. `describe` prints
the namespace and name. `Fabric8Executor` makes the bytes when it performs the action.

## When it is rendered

| The descriptor | The operator |
|---|---|
| does not set `ANKKA_SECRET_KEY` | emits `EnsureSecretKey` before `ApplyDeployment`, and adds `ANKKA_SECRET_KEY` from the Secret to the platform's container |
| sets `ANKKA_SECRET_KEY`, as a value or a `secretKeyRef` | emits no `EnsureSecretKey` and adds nothing; the descriptor's variable is routed as below |

## Where the variable goes

| Hosting | Containers | `ANKKA_SECRET_KEY` is on |
|---|---|---|
| embedded | one | that one |
| process | the platform's (index 0) and `<service>-app` | the platform's only |
| module | one, the platform's | that one; the module's `config` answers absent |

## The platform's variables (`core`, `PlatformVariables`)

One `private[ankka]` object, with no import outside the standard library, compiled into `core`
and, as the same source file, into the operator.

```scala
private[ankka] object PlatformVariables:
  val SecretKey = "ANKKA_SECRET_KEY"

  /** Set by the platform alone. A descriptor that gives one is refused. Exact names. */
  val PlatformOnly: Set[String]
  /** For the platform's program and never the developer's. A descriptor may give them. */
  val RuntimeOnlyPrefixes: Vector[String]   // ANTHROPIC_, ANKKA_MODEL_, ANKKA_DB_
  val RuntimeOnlyNames: Set[String]         // ANKKA_SECRET_KEY
  /** Given to both programs of a process-hosted service. */
  val SharedPrefixes: Vector[String]        // ANKKA_KAFKA_
  /** Read by the platform's program from its own environment. */
  val RuntimeReadPrefixes: Vector[String]   // ANKKA_CLUSTER_, ANKKA_WASM_, ANKKA_SIDECAR_, ANKKA_PROCESS_, ANKKA_AUTH_
  val RuntimeReadNames: Set[String]         // ANKKA_BASE_DOMAIN, ANKKA_HTTPS_PORT

  def platformOnly(name: String): Boolean
  def runtimeOnly(name: String): Boolean
  def shared(name: String): Boolean
  def withheldFromModule(name: String): Boolean   // platformOnly || runtimeOnly || read by the runtime
```

| Reader | Uses |
|---|---|
| `ServiceSpec.problems` (`controlplane-api`) | `platformOnly`, to refuse a descriptor's variable |
| `Rendering.containersFor` (operator) | `runtimeOnly` and `shared`, to split a process-hosted service's environment |
| `HostImports.lookup` (`sidecar`) | `withheldFromModule`, to answer `config` |

The constants those three hold today are deleted. The operator's `dependsOn` stays `crd`.

## What a test must show

- **Before the lists move**: `DescriptorSuite`, `HostingSuite`, `ProcessHostingRenderingSuite` and
  `WasmHostSuite` pass unchanged against the consolidated code, apart from the two changes named
  in R12 (`ANKKA_SECRET_KEY`; `ANKKA_NAMESPACE_PREFIX` withheld from a module).
- **By iteration**: for every member of `RuntimeOnlyPrefixes` and `RuntimeOnlyNames`, a
  process-hosted pod rendered with a variable of that name has it on the platform's container and
  not on the app's, and a module's `config` answers absent. A member added to the declaration is
  covered with no test edited.
- **One declaration**: a suite walks the sources and fails on a second `PlatformVariables.scala`,
  and on a collection of `ANKKA_`/`ANTHROPIC_` literals declared in `operator`, `sidecar` or
  `controlplane-api`. It is shown failing once with a copy pasted into the operator.
- **Rendering, offline**: the action precedes the Deployment; the variable's placement for each
  hosting; no action and no added variable when the descriptor sets it; the Secret has no owner
  reference; `describe` of the action contains no key.
- **k3s** (`OperatorClusterSuite`): the Secret exists after the first reconcile with a 32-byte
  key; ten reconciles leave its `resourceVersion` unchanged; deleting the resource keeps it and
  re-applying leaves it unchanged and names it from the new pod; the operator's shipped grant is
  unchanged. Two services in one project have different keys.
