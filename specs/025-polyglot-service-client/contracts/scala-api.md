# Contract: calling another service in Scala

What a service written in Scala sees. Decisions are in [research.md](../research.md) (R1 to R4,
R10).

## The client (`sdk`, unchanged in shape)

`ServiceClients` and `ServiceClient` are as they are: `services(name)` or
`services(project, name)`, then `get`, `getText`, `post`, `put`, `delete` and the raw `request`.

## The errors (`sdk`)

| Error | When | Was anything sent |
|---|---|---|
| `ServiceUnresolvable(service, reason)` | no such service was found | no |
| `ServiceIdentityMismatch(service, detail)` | what answered is not the service asked for | no |
| `ServiceUnanswered(service, reason)` **new** | the connection was refused or failed, or no answer came within the timeout | perhaps; the service may have received it |
| `ServiceCallFailed(service, status, body)` | the service answered with a status outside 2xx, to a typed helper | yes, and it answered |

`ServiceUnanswered` is a `RuntimeException` whose cause is the JDK's exception. Before this
feature the JDK's own `ConnectException`, `HttpTimeoutException` and `IOException` reached the
handler; they no longer do. The raw `request` returns a `ServiceResponse` for every status and
raises only the first three.

## Who has it

| Component | Through | Has the client |
|---|---|---|
| HTTP endpoint, gRPC endpoint | `EndpointClients.services` | yes, as before |
| Workflow | `WorkflowContext.services` | yes, in a step; refused in a command handler |
| Consumer (and a graph consumer) | `ConsumerContext.services` | yes |
| Timed action | `TimedActionContext.services` | yes |
| Agent | `AgentContext.services` | yes |
| Autonomous agent | `AutonomousAgentContext.services` | yes |
| Event sourced entity, key value entity | `EntityContext` | **no member**: does not compile |
| View | `ViewComponentContext` | **no member**: does not compile |

A workflow's client used outside a step throws `CommandError(BadRequest)` naming "a step", as its
secret store does and for the same reason.

`AnkkaService.services` is the running service's client, as before, and every context is given
that same one.

## What the client does to a request, for every caller

- **Headers.** Removed before sending, in any letter case: names starting `X-Ankka-`,
  `Forwarded`, `X-Forwarded-*`, `Host`, `Content-Length`, `Expect` and the hop-by-hop headers.
  (`Host` and `Content-Length` threw `IllegalArgumentException` from the JDK before; they are now
  dropped.)
- **Counting.** As before: one call from the calling thread's handler to the service, by method.
- **Tracing.** New: when the calling thread is in a trace, the call is a span in it, under the
  handler's span, named by the service and the method (`service:payments/psp-gateway`, `POST`),
  with the outcome the count has: ok, refused (4xx), failed (5xx or unanswered).
- **Timeout.** `ankka.service-client.timeout` for the answer; five seconds to connect, as before.

## Configuration

| Key | Variable | Default | Meaning |
|---|---|---|---|
| `ankka.service-client.timeout` | `ANKKA_SERVICE_CLIENT_TIMEOUT` | `30s` | how long a call to another service waits for its answer |
| `ankka.local-services.<name>` | none | none | where a service is on this machine, as before |

`ANKKA_SERVICE_CLIENT_TIMEOUT` is a platform setting a descriptor may give: it goes to the
platform's container and never to a process, and a module's `config` import answers it absent.

## Test kits (`testkit`)

- `ScriptedServices()`: a `ServiceClients` for unit tests. `scripted.answer("merchant") { request
  => ServiceResponse(…) }` sets what a service answers; `scripted.unresolvable("ledger")`,
  `scripted.unanswered("merchant")` and `scripted.mismatch("merchant")` raise the named errors;
  `scripted.requests` is every request made, in order, with its service, method, path, headers and
  body. A call to a service with no script fails the test, naming the service: a test whose call
  quietly returned a default is not testing what it says.
- `ConsumerTestKit(…, services: ScriptedServices = ScriptedServices())`, read back as
  `kit.services`.
- `AnkkaTestKit.start(…, localServices: Map[String, String] = Map.empty)` sets
  `ankka.local-services` for the service it starts; `ScriptedService.start()` is an HTTP server on
  loopback and an ephemeral port that records requests and answers as told, for a test of the
  whole service. `ServiceBuilder.withServices(wrap: ServiceClients => ServiceClients)` is
  `private[ankka]`, for the conformance target's impostor.

## What a test must show

Each scenario of `features/service-calls/components.feature` is a case of `ServiceCallsSuite`
(`testkit`), failing without the feature. Four where a weaker check would pass:

- **The step's call is the service's.** Locally every caller is the local caller, so "calls as
  the service" cannot be read from the callee. Assert instead that the scripted service received
  the request the step made, and leave identity to the k3s case, where a certificate exists.
- **The consumer's change is delivered again.** Use `ScriptedService.failNext()`, which fails
  once; a service that fails forever stalls the projection for every later test.
- **The span is under the step.** Assert the span's parent is the step's span id, not that some
  span names the service.
- **No client in an entity or a view.** `compileErrors("context.services")` against an
  `EntityContext` and a `ViewComponentContext`, with the same expression compiling against a
  `ConsumerContext` beside it.
