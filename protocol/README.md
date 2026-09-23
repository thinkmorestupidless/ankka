# The sidecar protocol

This directory is the artifact an SDK consumes: the `.proto` files under `src/main/protobuf`,
`ENCODING.md` (what the bytes inside a `Payload` mean) and `fixtures/` (documents every SDK's
default codec must encode and decode exactly). An SDK copies the whole directory in — the Python
SDK's `uv run proto` does — and CI checks the copies are identical.

The sbt project here (`ankka-protocol`) only generates Scala for the sidecar; it is never
published. Nothing of ankka's depends on it except `sidecar`, and it depends on nothing of
ankka's.

## Version

The protocol version is `1.0`, carried in discovery by both sides and checked by the sidecar.
It is written once for code in `controlplane-api` (`Protocol.version`) and once here.

`MAJOR.MINOR`. Within a major:

- adding an optional field, a message, an rpc or a fixture is a **minor**, and a sidecar that
  speaks a *later* minor accepts an SDK that declares an earlier one;
- renaming, removing or changing the meaning of anything, or changing the encoding of a shape the
  fixtures already cover, is a **major**, and the sidecar refuses a `Spec` declaring another
  major, naming both versions.

## Layout

| file | conversation |
|---|---|
| `payload.proto` | `Payload`, `Metadata`, `Outcome`, `Retention`, `Error`, `Failure` — shared by everything |
| `discovery.proto` | `Discovery.Discover` / `ReportError`: the process describes its components and endpoints |
| `event_sourced.proto`, `key_value.proto`, `workflow.proto` | one bidirectional stream per loaded instance |
| `view.proto`, `consumer.proto`, `timed_action.proto` | stateless: one request, one effect |
| `endpoint.proto` | `Http.Handle` / `HandleStream`: HTTP requests the sidecar forwards for declared routes |
| `agent.proto` | `Agent.Plan` / `InvokeTool` / `CheckGuardrail`: the process plans and runs tools, the sidecar runs the loop |
| `client.proto` | `Client`: the callback service the sidecar serves — component calls, view queries, timers |

Every service but `Client` is implemented by the developer's process on loopback at
`ANKKA_PROCESS_PORT` (9010) and dialled by the sidecar; `Client` is served by the sidecar on
loopback at `ANKKA_SIDECAR_PORT` (9011). Neither ever binds another interface.
