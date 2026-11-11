# Contract: the standing and copy reads (protocol 1.24)

## Callback rpcs (`protocol/src/main/protobuf/ankka/protocol/v1/client.proto`)

```proto
service Client {
  // ... existing rpcs ...

  // A workflow's standing and how a replicated entity's copy stands (protocol 1.24): the runtime's
  // own reads, which run no handler and journal nothing. A refusal is an Error in the reply, with
  // the code the runtime refuses the same read with; a runtime older than 1.24 answers
  // UNIMPLEMENTED.
  rpc Lifecycle   (LifecycleRequest)   returns (LifecycleReply);
  rpc Replication (ReplicationRequest) returns (ReplicationReply);
}

// 1.24: the workflow `workflow_id` of the component `component_id`.
message LifecycleRequest {
  string component_id = 1;
  string workflow_id  = 2;
}

enum WorkflowStatus {
  WORKFLOW_STATUS_UNSPECIFIED = 0; // never sent
  NOT_STARTED = 1;
  RUNNING     = 2;
  PAUSED      = 3;
  COMPLETED   = 4;
  FAILED      = 5;
}

message WorkflowLifecycle {
  WorkflowStatus      status       = 1;
  optional string     pending_step = 2; // the step it is on or waits after
  map<string, int32>  retries      = 3; // by step name; a step never retried is absent
  optional string     failure      = 4; // set when status is FAILED
}

message LifecycleReply {
  oneof result {
    WorkflowLifecycle lifecycle = 1;
    Error             error     = 2;
  }
}

// 1.24: the copy at this installation of the entity `entity_id` of the component `component_id`.
message ReplicationRequest {
  string component_id = 1;
  string entity_id    = 2;
}

message ReplicationStatus {
  optional string home             = 1;
  repeated string replication_set  = 2; // sorted
  optional string pending_handover = 3; // the installation a handover waiting goes to
  optional string conflict         = 4; // the other home, when in conflict
  int64           position         = 5; // records this copy's history holds
}

message ReplicationReply {
  oneof result {
    ReplicationStatus status = 1;
    Error             error  = 2;
  }
}
```

Mapping from the runtime's values (`modules/sdk/.../WorkflowLifecycle.scala`,
`ReplicationStatus.scala`):

| Runtime | Message |
|---|---|
| `status` `"NotStarted"`, `"Running"`, `"Paused"`, `"Completed"`, `"Failed"` | `WorkflowStatus` 1 to 5 |
| `status` anything else | an `Error` with `INTERNAL` that names the value (never expected) |
| `pendingStep`, `failure` (`Option`) | `optional string`, absent when `None` |
| `retries: Map[String, Int]` | `retries` |
| `home`, `pendingHandover`, `conflict` (`Option`) | `optional string` |
| `replicationSet: Set[String]` | `replication_set`, sorted |
| `position: Long` | `position` |

## Module imports (module `ankka1`, `protocol/WASM-ABI.md`)

| Import | Request bytes | Reply bytes | Since |
|---|---|---|---|
| `lifecycle` | `LifecycleRequest` | `LifecycleReply` | 1.24 |
| `replication` | `ReplicationRequest` | `ReplicationReply` | 1.24 |

Each is `(ptr: u32, len: u32) -> u64`, as `await_end` is, and is imported only by a module that calls
it. A runtime older than 1.24 refuses to load a module that imports either one.

## SDK surface

| Language | Standing | Copy | Too old |
|---|---|---|---|
| Python | `client.for_workflow(Comp, id).lifecycle() -> Lifecycle` (`status: Status`, `pending_step`, `retries`, `failure`; `is_running` and the other predicates) | `client.for_event_sourced_entity(Comp, id).replication() -> ReplicationStatus` (`home`, `replication_set: frozenset`, `pending_handover`, `conflict`, `position`, `in_conflict`) | `CommandError` `UNAVAILABLE`, naming 1.24 |
| TypeScript | `client.forWorkflow(Comp, id).lifecycle(): Promise<Lifecycle>` (`status: "not-started" \| "running" \| "paused" \| "completed" \| "failed"`, `pendingStep?`, `retries`, `failure?`) | `client.forEventSourcedEntity(Comp, id).replication(): Promise<ReplicationStatus>` (`home?`, `replicationSet: string[]`, `pendingHandover?`, `conflict?`, `position: bigint`) | `CommandError` `UNAVAILABLE`, naming 1.24 |
| Go | `ankka.Lifecycle(ctx, Comp, id) (ankka.WorkflowLifecycle, error)` (`Status ankka.WorkflowStatus`, `PendingStep *string`, `Retries map[string]int`, `Failure *string`) | `ankka.Replication(ctx, Comp, id) (ankka.ReplicationStatus, error)` | `*ProtocolTooOldError` naming 1.24 |
| Rust | `ctx.client().lifecycle(Workflow, id) -> Result<Lifecycle, CommandError>` (`status: WorkflowStatus`, `pending_step: Option<String>`, `retries: BTreeMap<String, u32>`, `failure: Option<String>`) | `ctx.client().replication(Entity, id) -> Result<ReplicationStatus, CommandError>` | the module is refused at load |

The exact names follow each SDK's existing calls (`await_end` / `awaitEnd` / `AwaitEnd`). An
implementation that finds a name colliding with an existing one keeps the SDK's convention and
notes it in `research.md`.

## Component test kit stubs

| Language | Set an answer | Unstubbed read |
|---|---|---|
| Python | `ClientStub().lifecycle(Comp, id, Lifecycle(...)).replication(Comp, id, ReplicationStatus(...))`, passed as a kit's `client` | `CommandError` saying the read of `<id>` was not stubbed |
| TypeScript | `stubClient({ lifecycle: [[Comp, id, {...}]], replication: [[Comp, id, {...}]] })` as the kit's `client` | the same |
| Go | `testkit.AnswerLifecycle(Comp, id, ...)`, `testkit.AnswerReplication(Comp, id, ...)` options | the same |
| Rust | `with_lifecycle(Comp, id, ...)`, `with_replication(Comp, id, ...)` on the kit; otherwise answered from what `InMemory` runs | not stubbed and not run: the same error |
