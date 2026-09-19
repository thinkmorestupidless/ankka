# Contract: Port Resolution

**Satisfies**: FR-001 to FR-006

One value decides five things. This contract is the complete table of how a descriptor becomes that
value, how the value becomes each of the five, and the rules that refuse a descriptor that would
make two of them disagree.

## The descriptor surface

Two fields on `ServiceSpec`, both optional:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `http` | `Boolean` | `true` | whether this service serves HTTP at all |
| `port` | `Int` | `9000` | the port it serves on; ignored when `http` is `false` |

`9000` is `ankka.http.port`'s existing default in `modules/http/src/main/resources/reference.conf`.
This feature adopts it rather than choosing a number, so a descriptor that says nothing produces the
behaviour the runtime already had.

**Why "serves no HTTP" is a boolean and not `"port": null`** — verified, research R4: under ankka's
shared codec config jsoniter treats `null` as *absent* and applies the default, so
`{"port": null}` parses as port 9000. Absent and `null` cannot be told apart, so the statement has to
be a positive one. (With `port` now a plain `Int`, that same `{"port": null}` is a loud decode error
rather than a silent 9000 — better, and pinned by `DescriptorSuite`.) `"port": 0` was rejected because `0` already means "pick a free port" in
`HttpServer.at`.

## Resolution

`ServiceSpec.resolvedPort: Option[Int] = Option.when(http)(port)` — total, in `controlplane-api`,
where the CLI and the control plane both read it. **Nothing downstream of this function ever sees
the two fields**; the custom resource and the operator know only the `Option[Int]`.

| Descriptor JSON | Resolved | Meaning |
|---|---|---|
| neither field | `Some(9000)` | serves HTTP on the platform default |
| `"port": 9000` | `Some(9000)` | identical — stating the default is not an error |
| `"port": 8080` | `Some(8080)` | serves HTTP on 8080 |
| `"http": false` | `None` | serves no HTTP |
| `"http": false, "port": 8080` | `None` | serves no HTTP; the port is ignored, not an error |

The last row is deliberately lenient: with the codec omitting default values on write, "the user
wrote `port`" is not reliably distinguishable from "the user did not", so a rule depending on it
could not be enforced consistently.

## What the resolved value renders

| Resolved | `containerPort` | `ANKKA_HTTP_PORT` | `readinessProbe` | Service | `imagePullPolicy` |
|---|---|---|---|---|---|
| `Some(p)` | `p`, named `http` | `p` | `tcpSocket` on `p` | rendered, targeting `p` | `IfNotPresent` |
| `None` | none | not injected | none | not rendered — removed if one exists | `IfNotPresent` |

The first four columns come from the *same* `Option[Int]`. They cannot disagree because there is
nothing to disagree with — this is the whole point of resolving once (FR-005).

`imagePullPolicy` is in the table only to record that it is unconditional (research R2).

## Validation

Added to `ServiceSpec.problems`, which reports every problem in one response rather than one per
apply.

| # | Condition | Problem |
|---|---|---|
| 1 | `port` is outside `1..65535` | `service port <n> is outside the range 1-65535` |
| 2 | `env` declares a variable named exactly `ANKKA_HTTP_PORT` | `env var 'ANKKA_HTTP_PORT' conflicts with the service port; declare the port instead` |

**Both rules are unconditional** — they apply when `http` is `false` too. A nonsense port is
nonsense whether or not it is used, and the `port` field is the only way to set the runtime's HTTP
port: a descriptor with two ways to say one thing is refused rather than silently resolved in favour
of one of them (FR-006).

Rule 2 matches by **variable name only, never value** — the same shape as feature 002's `ANKKA_DB_*`
escape hatch, and for the same reason: a value may arrive from a secret reference, so only the name
is reliably inspectable.

### Deliberately not validated

- **A port that nothing listens on.** Undetectable at apply time. It surfaces as the workload never
  becoming ready and the rollout exceeding its deadline (FR-013) — the existing, already-honest
  failure path for a workload that does not come up.
- **Two services in a project choosing the same port number.** Not a conflict: each gets its own
  Service with its own address, and ports are per-pod.

## Worked examples

```json
{ "name": "cart", "service": { "image": "sample-shopping-cart:latest" } }
```
→ `Some(9000)`; container port 9000, `ANKKA_HTTP_PORT=9000`, probe on 9000, Service `cart:9000`.

```json
{ "name": "cart", "service": { "image": "cart:1.0", "port": 8080 } }
```
→ `Some(8080)`; all four follow 8080.

```json
{ "name": "sweeper", "service": { "image": "sweeper:1.0", "http": false } }
```
→ `None`; no container port, no `ANKKA_HTTP_PORT`, no probe, no Service. Reaches `Ready` on the
container running (FR-012).

```json
{ "name": "cart", "service": { "image": "cart:1.0", "port": 8080,
    "env": [{ "name": "ANKKA_HTTP_PORT", "value": "9000" }] } }
```
→ **refused**, rule 2. Accepting it would deploy a workload listening on 9000 behind an address
routing to 8080 — the exact silent break this contract exists to prevent.

## A consequence worth stating: the default now has teeth

Before this feature a descriptor naming only an image deployed anything. After it, the same
descriptor asserts "this serves HTTP on 9000" and **will not become `Ready` until that is true**.
Correct for an ankka service; fatal for `registry.k8s.io/pause`, which every existing end-to-end case
deploys. Those descriptors gain `"http": false` in the same change — research R12.
