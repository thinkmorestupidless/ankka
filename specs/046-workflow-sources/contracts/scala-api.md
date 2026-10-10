# Contract: the Scala API

## Declaring the source

```scala
object CheckoutRows
    extends View.Companion[CheckoutRowsView, CheckoutState, CheckoutRow](
      componentId = ComponentId("checkout-rows"),
      source = ChangeSource.stateOf(Checkout),          // a Workflow.Companion; the change type is CheckoutState
      rowSerializer = Codecs.serializer[CheckoutRow]("checkout-row")
    ):
  def create(ctx: ViewComponentContext) = new CheckoutRowsView
  val byStanding = query("by-standing")(s"SELECT payload FROM $table WHERE payload::jsonb->>'standing' = :standing")
```

`ChangeSource.stateOf` is overloaded: `stateOf(KeyValueEntity.Companion[C, S])` as today, and
`stateOf(Workflow.Companion[W, S]): ChangeSource[S]`. A keyed view's `source(ChangeSource.stateOf(Checkout))(_.onCheckout)`
and a consumer companion's `source = ChangeSource.stateOf(Transfer)` take the same value.

## Reading a change

```scala
final class CheckoutRowsView extends View[CheckoutState, CheckoutRow]:
  def onChange(state: CheckoutState): Effect =
    val standing = updateContext.standing.get          // Some for a workflow source
    effects.updateRow(CheckoutRow(updateContext.subject, state, standing.status, standing.pendingStep, standing.failure))
```

| Where | Member | Type | Value |
|---|---|---|---|
| `View.updateContext` | `standing` | `Option[WorkflowLifecycle]` | `Some` on a workflow source, `None` otherwise |
| `Consumer.messageContext` | `standing` | `Option[WorkflowLifecycle]` | likewise |
| `KeyedChange` | `standing` | `Option[WorkflowLifecycle]` | likewise |

`WorkflowLifecycle` gains `isUnknown: Boolean` (`status == "Unknown"`). Its other members are
unchanged: `status`, `pendingStep`, `retries`, `failure`, `isRunning`, `isPaused`, `isCompleted`,
`isFailed`, `isTerminal`. `pendingStep` holds the step the workflow is on, or the one a pause names
as its timeout step, or the step paused after; the last is more than the lifecycle query answers for
a paused workflow, which is `None` (FR-005).

## What is delivered (rules S1–S6)

- **S1** One change per `state` record, in the order recorded. A view applies each exactly once
  (row and offset in one transaction); a consumer is handed each at least once.
- **S2** The change's value is the state as the workflow recorded it, decoded by the workflow's
  `stateSerializer`.
- **S3** The standing is the standing after the whole effect that recorded the state: a command's
  or step's `updateState` with its transition, pause, end or failure applied.
- **S4** A transition, pause, end, failure or retry that records no state delivers nothing. A
  workflow that times out delivers nothing.
- **S5** A `delete` runs `onDelete` as an entity's deletion does.
- **S6** A `state` record written by a release before this one delivers its state with the standing
  `Unknown`.

## Rules at registration (K-rules, extended)

- A plain view's source may be a workflow; a plain view still has exactly one source.
- A keyed view may read workflows and entities together; a topic beside either is refused:
  `view 'x' reads the topic 't' and a workflow; a topic and a workflow may not be sources of one view`.
- A consumer's source may be a workflow; a `version` on it is refused as on an entity source.
- A view over a workflow may declare a `version`; a higher one rebuilds it under the entity rules.

## Test kits

```scala
ConsumerTestKit.of(CheckoutEnds).onMessage(state, subject = "c1", sequenceNumber = 3, standing = Some(WorkflowLifecycle("Completed", None, Map.empty, None)))
KeyedViewTestKit(Fulfilment).change(Fulfilment.checkouts, "c1", state, standing = Some(...))
```

Both default `standing` to `None`.

## Topology

`DeclaredConnections.sourcesOf` yields `DeclaredSource.Workflow(component)`; the JSON connection's
`kind` is `"workflow"`.
