# Workflows

> Build a durable multi-step process in Scala or Python — commands, steps, transitions, pauses, timeouts, retries and compensation — that resumes where it stopped after a crash.

Source: https://docs.ankka.cloud/build/workflows/
A workflow is a durable, multi-step process. Each instance has an id, a state of your own type, and a
current step. The runtime journals every transition before the next step runs, so an instance that was
halfway through when its node died resumes at the step it had reached. It does not start over, and it
does not silently stop.

Use a workflow when a process spans several components or several slow calls and must finish or be
compensated: a money transfer across two accounts, a checkout that reserves stock and then charges, or a
plan that consults several agents. A plain chain of calls from an endpoint is lost with the request the
moment the process holding it goes away. A workflow is not.

## How a workflow is shaped

A workflow has two kinds of handler, and each returns an effect rather than doing the work itself.

- **Commands** are called from outside through the component client, like an entity's commands. A
  command can set the state, start the workflow at a step, and reply. Queries are read-only commands.
- **Steps** are run by the runtime, never called directly. A step does the work — usually calls to
  other components — and says what happens next: another step, a pause, the end, or a failure.

The runtime hosts each workflow instance as a sharded, event-sourced actor whose events are the step
transitions. That is what makes the transitions durable and what makes one instance single-writer: two
steps of one instance never run at the same time.

## Declaring a workflow in Scala

This workflow moves money between two wallets and puts it back if the deposit fails. The class holds the
handlers; the companion registers them under their wire names.

```scala
object TransferWorkflow
    extends Workflow.Companion[TransferWorkflow, TransferState](
      componentId = ComponentId("transfer"),
      stateSerializer = Codecs.serializer[TransferState]("transfer-state")
    ):

  given Serializer[Transfer] = Codecs.serializer[Transfer]("transfer")

  def create(context: WorkflowContext) = new TransferWorkflow(context)

  val withdraw   = step("withdraw")(_.withdrawStep)
  val deposit    = step("deposit")(_.depositStep)
  val compensate = step("compensate")(_.compensateStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
```

`step("deposit")(_.depositStep)` registers a step that takes an input, because `depositStep` takes a
`Transfer`; the `given Serializer[Transfer]` in the companion is how that input is stored in the
journal. `step("compensate")(_.compensateStep)` registers a step with no input. `command` and `query` work
as they do on an entity. Step names and handler names are wire names: renaming the Scala method is safe,
renaming the string is a protocol change for every instance already in flight. See
[Handlers and wire names](../concepts/wire-names.md).

A command sets the initial state and starts the workflow at its first step:

```scala
def start(transfer: Transfer): Effect[Done] =
  if transfer.amount <= 0 then effects.error("transfer amount must be greater than zero")
  else if currentState.status != "not-started" then
    effects.error("transfer already started", ErrorCode.Conflict)
  else
    effects
      .updateState(TransferState(transfer, "started"))
      .transitionTo(TransferWorkflow.withdraw.withInput(transfer))
      .thenReply(Done)
```

`withInput(transfer)` names the step and the value it will receive. A step with no input is used directly
as a transition target. The reply is sent once the new state and the transition are journalled, so a
caller that sees `Done` knows the workflow will run.

Steps are ordinary sequential code. They call other components with the blocking `invoke`, which costs
nothing because steps run on virtual threads:

```scala
def withdrawStep(transfer: Transfer): StepEffect =
  wallet(transfer.from).call(WalletEntity.withdraw).invoke(transfer.amount)
  stepEffects
    .updateState(currentState.copy(status = "withdrawn"))
    .thenTransitionTo(TransferWorkflow.deposit.withInput(transfer))

def depositStep(transfer: Transfer): StepEffect =
  wallet(transfer.to).call(WalletEntity.deposit).invoke(transfer.amount)
  stepEffects.updateState(currentState.copy(status = "completed")).thenEnd

/**
 * Puts the money back.
 *
 * Takes no input and reads the transfer from `currentState`, which is why failover steps are
 * input-free: what needs compensating is whatever the workflow has recorded, not whatever was
 * known when the settings were written.
 */
def compensateStep: StepEffect =
  val transfer = currentState.transfer
  wallet(transfer.from).call(WalletEntity.deposit).invoke(transfer.amount)
  stepEffects.updateState(currentState.copy(status = "compensated")).thenEnd
```

The class is `final class TransferWorkflow(context: WorkflowContext) extends Workflow[TransferState]`,
with `def emptyState` giving the state before the workflow starts and `context.componentClient` giving
the client its steps call through. The whole file is
[TransferWorkflow.scala](https://github.com/thinkmorestupidless/ankka/blob/main/modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/TransferWorkflow.scala).

Register it on the service like any other component:

```scala
Ankka.service
  .register(TransferWorkflow.descriptor)
  .start()
```

## Declaring a workflow in Python

The Python workflow is a class with a `component_id`, a `state_codec`, `@command` and `@query` handlers,
and `@step` methods. Steps may be `async`, and call other components through `self.context.client`.

```python
class CheckoutWorkflow(Workflow[Checkout]):
    component_id = "checkout"
    state_codec = json_codec(Checkout, "checkout")
    settings = WorkflowSettings(
        default_step_timeout=timedelta(seconds=10),
        steps={"charge": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate"))},
    )

    def empty_state(self) -> Checkout:
        return Checkout(self.entity_id)

    @command("start")
    def start(self, mode: str) -> WorkflowEffect[Checkout, Done]:
        """``mode``: ``ok``, ``fail`` (the charge is declined) or ``pause`` (a pause before it)."""
        if self.state.status != "new":
            return self.effects.error(f"checkout is already {self.state.status}", ErrorCode.CONFLICT)
        return self.effects.update_state(replace(self.state, status="reserving", mode=mode)).then_transition_to("reserve").then_reply(lambda _: DONE)

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[Checkout, Checkout]:
        return self.effects.reply(self.state)

    @step("reserve")
    async def reserve(self) -> WorkflowStepEffect[Checkout]:
        # A client call from a step: what the cart holds.
        total = await self._cart().call("total-quantity").invoke(reply=int)
        next_step = "wait" if self.state.mode == "pause" else "charge"
        return self.step_effects.update_state(replace(self.state, status="reserved", reserved=total)).then_transition_to(next_step)

    @step("wait")
    def wait(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="waiting")).then_pause(after=timedelta(milliseconds=1500), on_timeout=StepRef("charge"))

    @step("charge")
    async def charge(self) -> WorkflowStepEffect[Checkout]:
        if self.state.mode == "fail":
            raise PaymentDeclined("payment declined")
        # Not idempotent — a retry after the cart was checked out is refused — which is why
        # ``charge`` is allowed one retry and then fails over, and why compensation exists.
        if self.state.reserved > 0:
            await self._cart().call("checkout").invoke(reply=ShoppingCart)
        return self.step_effects.update_state(replace(self.state, status="charged")).then_end()

    @step("compensate")
    def compensate(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="compensated", reserved=0)).then_end()

    def _cart(self) -> Calls:
        return self.context.client.for_event_sourced_entity("shopping-cart", self.state.cartId)
```

A Python step names its successor by its wire name, `then_transition_to("charge")`, and passes an input
as a second argument when the step takes one. The sidecar journals the transition and runs the steps; the
process is asked to run one step at a time and answers with the step's effect.

## What a command can do

A command handler returns one of these, built from `effects`:

| Scala | Python | Meaning |
|---|---|---|
| `effects.updateState(s).transitionTo(step).thenReply(r)` | `self.effects.update_state(s).then_transition_to("step").then_reply(lambda _: r)` | Set the state, start or redirect the workflow, reply. |
| `effects.transitionTo(step).thenReply(r)` | `self.effects.transition_to("step").then_reply(...)` | Start or redirect without changing the state. |
| `effects.updateState(s).thenReply(r)` | `self.effects.update_state(s).then_reply(...)` | Change the state only. |
| `…thenReplyState` | `…then_reply_state()` | Reply with the new state. |
| `effects.reply(r)` | `self.effects.reply(r)` | A read-only reply, for a query. |
| `effects.error(message, code)` | `self.effects.error(message, code)` | Refuse. Nothing is journalled. |
| `effects.delete().thenReply(r)` | — | Discard the workflow's state. |

A command that transitions a workflow already in progress redirects it. Guard against that in the
command when a second start is a mistake, as both samples do by checking the state and refusing with
`Conflict`.

## What a step can do

A step returns one of these, built from `stepEffects` in Scala and `self.step_effects` in Python. Each
can be preceded by `updateState(s)` / `update_state(s)` to record progress in the same journalled
transition.

| Scala | Python | Meaning |
|---|---|---|
| `thenTransitionTo(step)` | `then_transition_to("step", input)` | Run another step next. |
| `thenPause()` | `then_pause()` | Stop and wait for a command to transition the workflow. |
| `thenPause(after, onTimeout)` | `then_pause(after=..., on_timeout=StepRef("step"))` | Wait, and transition to `onTimeout` if nothing else does first. |
| `thenEnd` | `then_end()` | The workflow is complete. |
| `thenFail(message)` | `then_fail(message, code)` | The workflow ends failed. No recovery runs. |

`thenFail` is a deliberate outcome: the step decided the process cannot continue. A step that throws, or
that runs past its timeout, is different: that is a fault, and the workflow's recovery settings decide
what happens next.

## Timeouts, retries and compensation

Recovery is declared in the workflow's settings, because the runtime enforces it and a step cannot
enforce it on itself.

**Scala**

```scala
override def settings: WorkflowSettings =
  WorkflowSettings.builder
    .timeout(60.seconds)
    .defaultStepTimeout(10.seconds)
    // One retry, then compensate. The deposit is the step that can fail for reasons
    // outside this workflow's control, so it is the one worth a second attempt.
    .stepRecovery(
      TransferWorkflow.deposit,
      RecoverStrategy.maxRetries(1).failoverTo(TransferWorkflow.compensate)
    )
    .build
```

**Python**

```python
settings = WorkflowSettings(
    timeout=timedelta(minutes=5),
    default_step_timeout=timedelta(seconds=10),
    steps={"charge": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate"))},
)
```

| Setting | Default | Meaning |
|---|---|---|
| `timeout` | none | A ceiling on the whole workflow, measured from its start. When it passes, the workflow fails. |
| `defaultStepTimeout` / `default_step_timeout` | 30 seconds | How long a step may run before it counts as failed. |
| `stepTimeout(step, d)` / `StepSettings(timeout=...)` | the default | One step's own limit. |
| `defaultRecovery` / `default_recovery` | fail, no retries | What happens when any step throws or times out. |
| `stepRecovery(step, strategy)` / `StepSettings(recovery=...)` | the default | One step's own recovery. |

A recovery strategy is a number of retries and, optionally, a step to fail over to when they are used up:
`RecoverStrategy.maxRetries(1).failoverTo(TransferWorkflow.compensate)` in Scala,
`Recovery(max_retries=1, failover_to="compensate")` in Python. Without a failover step, a step that
exhausts its retries fails the workflow.

**Retries default to zero.** The runtime cannot know whether a step is safe to run twice, and a step that
charges a card must not be retried blindly. Give a step retries only when running it again is harmless,
or when the component it calls refuses a duplicate.

**A failover step takes no input.** Compensation needs what the workflow has recorded so far, which it
reads from the state, not a value captured when the settings were written. That is why `compensateStep`
reads the transfer from `currentState`.

## Pausing for something outside

A step that has to wait for a person or another system ends with `thenPause()`. The workflow stays paused
until a command transitions it. With `thenPause(after, onTimeout)` the runtime transitions it to
`onTimeout` if nothing else has by then, which is how a workflow gives up on an approval that never
comes. The Python checkout's `wait` step pauses and then resumes at `charge` on its own.

## Queries while a step runs

A workflow keeps answering commands and queries while a step is running. A status query does not wait
for a slow step to finish, and it sees the state as of the last journalled transition. Updates a step
makes are visible once the step returns its effect.

## Domain state and lifecycle

A workflow's state is your type and says what the process has decided. It does not say whether the
engine is still running it. A plan whose state reads "consulting" looks the same whether a model call is
in flight or the workflow failed an hour ago.

Every Scala workflow therefore answers a lifecycle query the runtime implements itself, whatever handlers
its author declared:

```scala
/** The engine's own view: running, paused, completed or failed, and why. */
get("/{planId}/lifecycle") { (planId: String) =>
  val lifecycle = client.forWorkflow(EntityId(planId)).lifecycle(PlannerWorkflow).invoke()
  s"\${lifecycle.status}\${lifecycle.failure.map(reason => s": \$reason").getOrElse("")}"
}
```

`WorkflowLifecycle` carries `status` (`Running`, `Paused`, `Completed` or `Failed`), the pending step, a
count of retries per step, and the failure reason when there is one. `isTerminal` is true once the
workflow has completed or failed. Handler names beginning `ankka:` are reserved for queries like this one,
and registering one is refused.

## Calling agents and entities from steps

A step is where a workflow does its work, and the work is calls through the component client. The
[multi-agent orchestration](multi-agent-orchestration.md) guide shows a workflow that consults several
agents in parallel and combines their answers. Two properties make that safe:

- Each step's result is journalled before the next step starts. A crash after two of three expensive
  calls resumes at the third rather than paying for the first two again.
- A step that calls another component can fail for reasons outside the workflow. Declare a timeout that
  fits the slowest call it makes; model calls in particular need far longer than the 30-second default.

## Testing a workflow

In Scala, a workflow is tested through the integration test kit, which starts the whole service against
a throwaway Postgres, because transitions, recovery and timeouts are the runtime's behaviour. In Python,
`WorkflowTestKit` runs commands and steps by hand with no sidecar. [Testing](testing.md) shows both.
