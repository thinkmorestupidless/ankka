# Contract: protocol 1.7 — a topic source's start position and a component's version

Additive to 1.3. One message and one field on `Source`, one field each on `ViewDetail` and
`ConsumerDetail`, all in `protocol/src/main/protobuf/ankka/protocol/v1/discovery.proto`. No request
or reply message changes: a start position and a version are declarations, read once at discovery,
and everything they cause happens in the runtime.

## Messages

```proto
message Source {
  oneof source {
    ComponentRef component = 1;
    string topic = 2;
  }
  message ComponentRef { Kind kind = 1; string id = 2; }

  // 1.7: where this source starts the first time its group reads the topic. Only with `topic`.
  optional StartFrom start_from = 3;
}

// 1.7
message StartFrom {
  oneof position {
    Named named = 1;
    // The first message at or after this time, in milliseconds since the epoch.
    int64 at_millis = 2;
  }
  enum Named {
    NAMED_UNSPECIFIED = 0;
    EARLIEST = 1;
    LATEST = 2;
  }
}

message ViewDetail {
  Source source = 1;
  string row_manifest = 2;
  repeated string queries = 3;
  // 1.7: only with a topic source.
  optional uint32 version = 4;
}

message ConsumerDetail {
  Source source = 1;
  optional string produces_to = 2;
  // 1.7: only with a topic source.
  optional uint32 version = 3;
}
```

`at_millis` is milliseconds because that is the unit of a Kafka record's timestamp, which is what
it is compared with. `version` is `uint32` so a negative one cannot be written; zero can, and is
refused.

## What the runtime refuses at discovery

`Discovery.validate` collects these with every other problem, so a process is told all of them at
once through `ReportError` and a module's host refuses to start with the same list. Each names the
component. They are the rules of [topic-sources.md](topic-sources.md), stated once in the runtime
and applied to an in-process registry and a discovered one alike.

| Declared | Refused with |
| --- | --- |
| a consumer over a topic with no `start_from` | `consumer '<id>' reads topic '<topic>' and declares no start position; declare earliest, latest or a time` |
| `start_from` with an unset `position`, or `NAMED_UNSPECIFIED` | `<kind> '<id>' declares a start position that names none` |
| `start_from` on a `component` source | `<kind> '<id>' declares a start position, which applies to a topic; it reads <source kind> '<source id>'` |
| `version` on a `component` source | `<kind> '<id>' declares a version, which applies to a topic; it reads <source kind> '<source id>'` |
| `version = 0` | `<kind> '<id>' declares version 0; a version is a positive whole number` |

A view over a topic with no `start_from` is accepted and starts at the earliest retained message.
An absent `version` is version 1.

## Compatibility

| SDK | Runtime | What happens |
| --- | --- | --- |
| 1.6 | 1.7 | Everything runs. A consumer over a topic cannot have declared a start position, because its SDK has no way to say one, so the runtime does not hold it to the rule: it starts at the earliest retained message, as it always has, and the runtime logs a warning naming the consumer and the SDK version that can declare one. The rule binds only an SDK that states 1.7 or later in `Spec.protocol_version`. |
| 1.7 | 1.6 | The runtime does not know field 3 or the version fields and would ignore them: a consumer declared `latest` would read from the earliest message, and a version bump would rebuild nothing. So an SDK that declares a start position or a version **refuses to start** when `SidecarInfo.protocol_version` is below 1.7, naming the component and both versions. An SDK that declares neither runs unchanged. |
| 1.7 | 1.7 | As this contract says. |

The first row exists because a process-hosted service's runtime is the platform's sidecar image,
which moves when the platform is upgraded, and its SDK is in its own image, which moves when its
developer deploys. If the 1.7 runtime refused a 1.6 consumer, a service that was running would be
unable to restart after a platform upgrade, and could not have been fixed beforehand, because of
the second row. So the platform goes first and changes nothing a 1.6 service does except its
group's name; the service follows, and from then on must say where it starts.

The second row is the rule the protocol already follows for `produce_all`: what an older runtime
would drop without saying so is refused by the newer side that knows it was said.

In the first table, "a consumer over a topic with no `start_from`" therefore reads "from an SDK
stating 1.7 or later".

## Where the version is written

`Protocol.version` in `controlplane-api` (`Compatibility.scala`), `WireProtocol.Version` in
`runtime` (`remote/Conversation.scala`), `Discovery.ProtocolVersion`'s changelog in `sidecar`,
`PROTOCOL_VERSION` in each of the three SDKs, `protocol/README.md`, and an inline `// 1.7` on
each new field. The three SDK copies of `protocol/` stay byte-identical to the canonical one; CI
compares them.

## The WebAssembly ABI

Unchanged. `WasmSpec.spec` is the same `Spec`, so both declarations reach the host in discovery,
and `WasmDiscovery` applies the same validation. `protocol/WASM-ABI.md` gains one sentence saying
so. The ABI version does not move.
