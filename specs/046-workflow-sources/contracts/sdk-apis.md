# Contract: Python, TypeScript and Rust, and the conformance components

Each SDK names a workflow source exactly where it names an entity today; what is new is the
`Standing` it reads and the refusal of a runtime too old to send one.

## Python

```python
@dataclass(frozen=True)
class Standing:
    status: str                      # NotStarted, Running, Paused, Completed, Failed, Unknown
    step: str | None
    retries: Mapping[str, int]
    failure: str | None
    @property
    def is_unknown(self) -> bool: ...
    is_running, is_paused, is_completed, is_failed, is_terminal likewise

class CheckoutRows(View[CheckoutState, CheckoutRow]):
    component_id = "checkout-rows"
    source = Checkout                # a Workflow subclass; event_codec is its state codec
    event_codec = checkout_state_codec
    row_codec = checkout_row_codec
    by_standing = query("by-standing", "SELECT payload FROM ankka_view_checkout_rows WHERE payload::jsonb->>'standing' = :standing")

    def on_change(self, state: CheckoutState) -> ViewEffect:
        standing = self.standing     # Standing for a workflow source, None otherwise
        ...
```

`self.standing` on `View`, `KeyedView` (also on the change handed to a source handler) and
`Consumer`. `ViewTestKit.on_change(key, event, standing=None)`,
`KeyedViewTestKit.change(source, key, event, standing=None)`,
`ConsumerTestKit.on_message(message, subject, sequence=None, standing=None)`.

Discovery refusal (`server.py`, beside the 1.13 and 1.14 ones): when the sidecar states a protocol
older than 1.15 and any registered view, keyed view or consumer's source is a `Workflow` subclass.

## TypeScript

```ts
export interface Standing {
  readonly status: "NotStarted" | "Running" | "Paused" | "Completed" | "Failed" | "Unknown"
  readonly step?: string
  readonly retries: Readonly<Record<string, number>>
  readonly failure?: string
}
export function isUnknown(s: Standing): boolean

export class CheckoutRows extends View<CheckoutState, CheckoutRow> {
  static readonly componentId = "checkout-rows"
  static readonly source = CheckoutWorkflow         // a Workflow class
  static readonly events = CheckoutState
  static readonly row = CheckoutRow
  static readonly declared = [query("by-standing", "...")]
  onChange(state: CheckoutState) {
    const standing = this.standing                   // Standing | undefined
    ...
  }
}
```

`this.standing` on `View`, `KeyedView` (and on a keyed change) and `Consumer`. The unit kits take
`standing?` as a trailing option. Discovery refusal in `server/discovery.ts` beside
`olderThanContracts` and `olderThanDeclaredQueries`: `olderThanWorkflowSources`.

## Rust

```rust
pub struct Standing { pub status: String, pub step: Option<String>, pub retries: BTreeMap<String, i32>, pub failure: Option<String> }
impl Standing { pub fn is_unknown(&self) -> bool; is_running, is_paused, is_completed, is_failed, is_terminal }

impl View for CheckoutRows {
    type Event = CheckoutState;
    type Row = CheckoutRow;
    fn source() -> Source { Source::of(Checkout) }   // a Workflow
    fn on_change(&self, ctx: &Context, state: CheckoutState) -> ViewEffect<CheckoutRow> {
        let standing = ctx.standing();               // Option<&Standing>
        ...
    }
}
```

`Context::standing()` on views, keyed views and consumers. The unit kits gain
`.standing(Standing)` builders beside `.at(sequence)`. A module built with the crate refuses a
runtime below 1.15 when it declares a workflow source, in `service.rs` beside the 1.13 and 1.14
checks; `WASM-ABI.md` notes the field on `ViewRequest` and `ConsumerRequest`.

## The conformance reference (every language declares the same)

| Component | Kind | Declares |
|---|---|---|
| `checkout` | workflow | gains mode `abort`: `compensate` records `status = "aborted"` and fails with `"payment declined"`; and mode `drop`: `compensate` fails with `"payment declined"` and records nothing |
| `checkout-rows` | view | `source = checkout`; row `{ id, status (the state's), standing, step?, failure? }`; query `by-standing` taking `standing` |
| `checkout-ends` | consumer | `source = checkout`; on a change whose standing is `Completed` or `Failed`, calls `profile` (the key value entity) to record `{ id, standing, failure? }` under the checkout's id; ignores every other change |

Routes: `GET /conformance/checkout/{id}/row` (404 until written), `GET /conformance/checkout-rows/{standing}`,
`GET /conformance/checkout/{id}/end` (404 until recorded). `checkout-ends` records an end in the
`profile` key value entity under `end-<id>`, as `Completed` or `Failed: <reason>`.

## Conformance cases (`ConformanceSuite`)

| Case | Asserts |
|---|---|
| `view.workflow-completed` | mode `ok`: the row's `standing` is `Completed`, its `status` `charged`, after the workflow ends |
| `view.workflow-failure-recorded` | mode `abort`: `standing` `Failed`, `failure` `payment declined`, `status` `aborted` |
| `view.workflow-no-change-without-state` | mode `drop`: once the row shows the last recorded state (`reserved`, running on `charge`) and the journal holds the failure, the row stays at that state and standing |
| `view.workflow-by-standing` | `by-standing` with `Failed` answers the aborted checkout and not the completed one |
| `consumer.workflow-end-once` | `Completed` recorded for `ok`, `Failed: payment declined` for `abort`, and nothing for `drop`, whose failure records no state |
| `topology.workflow-subscription` | a connection from `checkout` to `checkout-rows` and to `checkout-ends` of kind `workflow` |
| `discovery.workflow-source-needs-1.15` | a sidecar stating `1.14` to the reference is refused by it, naming the components and both versions |

`discovery.lists-every-component` holds the four references to the same list, so a reference
missing either component fails before any case runs.
