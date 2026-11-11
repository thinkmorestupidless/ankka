# Workflows

> Build a durable multi-step process in Scala, Java, Kotlin, Python, TypeScript, Rust or Go — commands, steps, transitions, pauses, timeouts, retries and compensation — that resumes where it stopped after a crash.

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

## A workflow, step by step

This workflow moves money between two wallets and puts it back if the deposit fails. The class holds the
handlers; the companion registers them under their wire names.

**Scala**

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

**Java**

```java
@Step("withdraw")
public StepEffect withdraw(Transfer transfer) {
  wallet(transfer.from()).call(Wallet::withdraw).invoke(transfer.amount());
  return stepEffects()
      .updateState(new TransferState(transfer, "withdrawn"))
      .thenTransitionTo(TransferWorkflow::deposit, transfer);
}

@Step("deposit")
public StepEffect deposit(Transfer transfer) {
  wallet(transfer.to()).call(Wallet::deposit).invoke(transfer.amount());
  return stepEffects().updateState(new TransferState(transfer, "completed")).thenEnd();
}

/**
 * Puts the money back.
 *
 * <p>Takes no input and reads the transfer from the state, which is why failover steps are
 * declared without one: the runtime names the step, and the state carries what it needs.
 */
@Step("compensate")
public StepEffect compensate() {
  var transfer = currentState().transfer();
  wallet(transfer.from()).call(Wallet::deposit).invoke(transfer.amount());
  return stepEffects().updateState(new TransferState(transfer, "compensated")).thenEnd();
}

private EntityRef wallet(String id) {
  return context().componentClient().forKeyValueEntity(id);
}
```

**Kotlin**

```kotlin
@Step("withdraw")
fun withdrawStep(transfer: Transfer): StepEffect {
    wallet(transfer.from).call(WalletEntity::withdraw).invoke(transfer.amount)
    return stepEffects {
        updateState(currentState().copy(status = "withdrawn")).thenTransitionTo(stepRef(TransferWorkflow::depositStep), transfer)
    }
}

@Step("deposit")
fun depositStep(transfer: Transfer): StepEffect {
    wallet(transfer.to).call(WalletEntity::deposit).invoke(transfer.amount)
    return stepEffects { updateState(currentState().copy(status = "completed")).thenEnd() }
}

/**
 * Puts the money back. Takes no input and reads the transfer from the state, which is why
 * failover steps are input-free: what needs compensating is whatever the workflow has
 * recorded, not whatever was known when the settings were written.
 */
@Step("compensate")
fun compensateStep(): StepEffect {
    val transfer = currentState().transfer
    wallet(transfer.from).call(WalletEntity::deposit).invoke(transfer.amount)
    return stepEffects { updateState(currentState().copy(status = "compensated")).thenEnd() }
}
```

**TypeScript**

```ts
export class TransferWorkflow extends Workflow<TransferState> {
  static readonly componentId = "transfer"
  static readonly state = jsonCodec(TransferState, "transfer-state")
  static readonly settings = workflowSettings({
    timeout: Duration.ofSeconds(60),
    defaultStepTimeout: Duration.ofSeconds(10),
    // One retry, then compensate. The deposit is the step that can fail for reasons outside this
    // workflow's control, so it is the one worth a second attempt.
    steps: { deposit: { recovery: { maxRetries: 1, failoverTo: "compensate" } } },
  })

  // Step names and handler names are wire names: renaming a method is safe, renaming the string is a
  // protocol change for every instance already in flight.
  static readonly steps = {
    withdraw: step("withdraw", Transfer, (w: TransferWorkflow, transfer) => w.withdrawStep(transfer)),
    deposit: step("deposit", Transfer, (w: TransferWorkflow, transfer) => w.depositStep(transfer)),
    compensate: step("compensate", (w: TransferWorkflow) => w.compensateStep()),
  }

  static readonly handlers = {
    start: command("start", Transfer, Done, (w: TransferWorkflow, transfer) => w.start(transfer)),
    status: query("status", TransferState, (w: TransferWorkflow) => w.effects.reply(w.state)),
  }
```

**Rust**

```rust
impl Workflow for TransferWorkflow {
    type State = TransferState;
    const COMPONENT_ID: &'static str = "transfer";
    const STATE_MANIFEST: Option<&'static str> = Some("transfer-state");

    fn empty_state(_: &str) -> TransferState {
        TransferState {
            transfer: Transfer {
                from: String::new(),
                to: String::new(),
                amount: 0,
            },
            status: "not-started".to_string(),
        }
    }

    fn handlers() -> WorkflowHandlers<TransferWorkflow> {
        WorkflowHandlers::new()
            .command("start", TransferWorkflow::start)
            .query("status", TransferWorkflow::status)
    }

    // `deposit` takes an input, a `Transfer`; `compensate` takes none. Step names and handler
    // names are wire names: renaming the Rust function is safe, renaming the string is a
    // protocol change for every instance already in flight.
    fn steps() -> Steps<TransferWorkflow> {
        Steps::new()
            .step("withdraw", TransferWorkflow::withdraw)
            .step("deposit", TransferWorkflow::deposit)
            .step("compensate", TransferWorkflow::compensate)
    }
```

**Go**

```go
// TransferWorkflow moves money between two wallets and puts it back if the deposit fails. Every
// step and handler is registered under a wire name: renaming a Go function is safe, renaming the
// string is a protocol change for every instance already in flight.
var TransferWorkflow = ankka.Workflow[TransferState]("transfer",
	func(string) TransferState { return TransferState{Status: "not-started"} },
	ankka.WorkflowCommand("start", startTransfer),
	ankka.Query("status", func(_ context.Context, s TransferState, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		return ankka.Reply(s.Status), nil
	}),
	// A step with an input, stored in the journal with the transition, and two without.
	ankka.Step("withdraw", withdrawStep),
	ankka.Step("deposit", depositStep),
	ankka.Step("compensate", compensateStep),
	// A deposit that fails after its retry is compensated rather than left half done.
	ankka.ForStep("deposit", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
	ankka.StateCodec(ankka.CodecFor[TransferState](ankka.Manifest("transfer-state"))),
)
```

`step("deposit")(_.depositStep)` registers a step that takes an input, because `depositStep` takes a
`Transfer`; the `given Serializer[Transfer]` in the companion is how that input is stored in the
journal. `step("compensate")(_.compensateStep)` registers a step with no input. `command` and `query` work
as they do on an entity. Step names and handler names are wire names: renaming the Scala method is safe,
renaming the string is a protocol change for every instance already in flight. See
[Handlers and wire names](../concepts/wire-names.md).

A command sets the initial state and starts the workflow at its first step:

**Scala**

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

**Java**

```java
@Command("start")
public Effect<Done> start(Transfer transfer) {
  if (transfer.amount() <= 0)
    return effects().error("transfer amount must be greater than zero");
  if (!currentState().status().equals("not-started"))
    return effects().error(Problem.conflict("transfer already started"));
  return effects()
      .updateState(new TransferState(transfer, "started"))
      .transitionTo(TransferWorkflow::withdraw, transfer)
      .thenDone();
}
```

**Kotlin**

```kotlin
@Command("start")
fun start(transfer: Transfer): Effect<Done> = effects {
    when {
        transfer.amount <= 0 -> error("transfer amount must be greater than zero")
        currentState().status != "not-started" -> error(Problem.conflict("transfer already started"))
        else ->
            updateState(TransferState(transfer, "started"))
                .transitionTo(stepRef(TransferWorkflow::withdrawStep), transfer)
                .thenDone()
    }
}
```

**Python**

```python
@command("start")
def start(self, transfer: Transfer) -> WorkflowEffect[TransferState, Done]:
    if transfer.amount <= 0:
        return self.effects.error("transfer amount must be greater than zero")
    if self.state.status != "not-started":
        return self.effects.error("transfer already started", ErrorCode.CONFLICT)
    return self.effects.update_state(TransferState(transfer, "started")).then_transition_to("withdraw", transfer).then_reply(lambda _: DONE)
```

**TypeScript**

```ts
start(transfer: Transfer) {
  if (transfer.amount <= 0) return this.effects.error("transfer amount must be greater than zero")
  if (this.state.status !== "not-started") return this.effects.error("transfer already started", ErrorCode.Conflict)
  return this.effects.updateState({ transfer, status: "started" }).thenTransitionTo("withdraw", transfer).thenReply(() => done)
}
```

**Rust**

```rust
fn start(
    state: &TransferState,
    transfer: Transfer,
    _: &Context,
) -> WorkflowEffect<TransferState, Done> {
    if transfer.amount <= 0 {
        let message = "transfer amount must be greater than zero";
        return effects::error(ErrorCode::BadRequest, message).into();
    }
    if state.status != "not-started" {
        return effects::error(ErrorCode::Conflict, "transfer already started").into();
    }
    workflow::update_state(TransferState {
        transfer: transfer.clone(),
        status: "started".to_string(),
    })
    .transition_to_with("withdraw", transfer)
    .then_reply_value(Done)
}
```

**Go**

```go
// startTransfer sets the initial state and starts the workflow at its first step. The reply is sent
// once the new state and the transition are journalled, so a caller that sees it knows the
// workflow will run.
func startTransfer(_ context.Context, s TransferState, t Transfer) (transferStart, error) {
	if t.Amount <= 0 {
		return ankka.WorkflowEffect[TransferState, ankka.Done]{},
			ankka.Refuse(ankka.BadRequest, "transfer amount must be greater than zero")
	}
	if s.Status != "not-started" {
		return ankka.WorkflowEffect[TransferState, ankka.Done]{},
			ankka.Refuse(ankka.Conflict, "transfer already started")
	}
	return ankka.WorkflowUpdate[TransferState, ankka.Done](TransferState{Transfer: t, Status: "started"}).
		ThenTransitionTo("withdraw", t).
		ThenReplyValue(ankka.Done{}), nil
}
```

`withInput(transfer)` names the step and the value it will receive. A step with no input is used directly
as a transition target. The reply is sent once the new state and the transition are journalled, so a
caller that sees `Done` knows the workflow will run.

Steps are ordinary sequential code. They call other components with the blocking `invoke`, which costs
nothing because steps run on virtual threads:

**Scala**

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

**Java**

```java
@Step("withdraw")
public StepEffect withdraw(Transfer transfer) {
  wallet(transfer.from()).call(Wallet::withdraw).invoke(transfer.amount());
  return stepEffects()
      .updateState(new TransferState(transfer, "withdrawn"))
      .thenTransitionTo(TransferWorkflow::deposit, transfer);
}

@Step("deposit")
public StepEffect deposit(Transfer transfer) {
  wallet(transfer.to()).call(Wallet::deposit).invoke(transfer.amount());
  return stepEffects().updateState(new TransferState(transfer, "completed")).thenEnd();
}

/**
 * Puts the money back.
 *
 * <p>Takes no input and reads the transfer from the state, which is why failover steps are
 * declared without one: the runtime names the step, and the state carries what it needs.
 */
@Step("compensate")
public StepEffect compensate() {
  var transfer = currentState().transfer();
  wallet(transfer.from()).call(Wallet::deposit).invoke(transfer.amount());
  return stepEffects().updateState(new TransferState(transfer, "compensated")).thenEnd();
}

private EntityRef wallet(String id) {
  return context().componentClient().forKeyValueEntity(id);
}
```

**Kotlin**

```kotlin
@Step("withdraw")
fun withdrawStep(transfer: Transfer): StepEffect {
    wallet(transfer.from).call(WalletEntity::withdraw).invoke(transfer.amount)
    return stepEffects {
        updateState(currentState().copy(status = "withdrawn")).thenTransitionTo(stepRef(TransferWorkflow::depositStep), transfer)
    }
}

@Step("deposit")
fun depositStep(transfer: Transfer): StepEffect {
    wallet(transfer.to).call(WalletEntity::deposit).invoke(transfer.amount)
    return stepEffects { updateState(currentState().copy(status = "completed")).thenEnd() }
}

/**
 * Puts the money back. Takes no input and reads the transfer from the state, which is why
 * failover steps are input-free: what needs compensating is whatever the workflow has
 * recorded, not whatever was known when the settings were written.
 */
@Step("compensate")
fun compensateStep(): StepEffect {
    val transfer = currentState().transfer
    wallet(transfer.from).call(WalletEntity::deposit).invoke(transfer.amount)
    return stepEffects { updateState(currentState().copy(status = "compensated")).thenEnd() }
}
```

**Python**

```python
@step("withdraw")
async def withdraw(self, transfer: Transfer) -> WorkflowStepEffect[TransferState]:
    await self._wallet(transfer.source).call("withdraw").invoke(transfer.amount, reply=Balance)
    return self.step_effects.update_state(replace(self.state, status="withdrawn")).then_transition_to("deposit", transfer)

@step("deposit")
async def deposit(self, transfer: Transfer) -> WorkflowStepEffect[TransferState]:
    await self._wallet(transfer.target).call("deposit").invoke(transfer.amount, reply=Balance)
    return self.step_effects.update_state(replace(self.state, status="completed")).then_end()

@step("compensate")
async def compensate(self) -> WorkflowStepEffect[TransferState]:
    """Puts the money back.

    Takes no input and reads the transfer from the state, which is why failover steps are
    input-free: what needs compensating is whatever the workflow has recorded, not whatever
    was known when the settings were written."""
    transfer = self.state.transfer
    await self._wallet(transfer.source).call("deposit").invoke(transfer.amount, reply=Balance)
    return self.step_effects.update_state(replace(self.state, status="compensated")).then_end()
```

**TypeScript**

```ts
async withdrawStep(transfer: Transfer) {
  await this.balance(transfer.from).call(Balances.handlers.withdraw).invoke(transfer.amount)
  return this.stepEffects.updateState({ ...this.state, status: "withdrawn" }).thenTransitionTo("deposit", transfer)
}

async depositStep(transfer: Transfer) {
  await this.balance(transfer.to).call(Balances.handlers.deposit).invoke(transfer.amount)
  return this.stepEffects.updateState({ ...this.state, status: "completed" }).thenEnd()
}

/**
 * Puts the money back.
 *
 * Takes no input and reads the transfer from the state, which is why failover steps are input-free:
 * what needs compensating is whatever the workflow has recorded, not whatever was known when the
 * settings were written.
 */
async compensateStep() {
  const transfer = this.state.transfer
  await this.balance(transfer.from).call(Balances.handlers.deposit).invoke(transfer.amount)
  return this.stepEffects.updateState({ ...this.state, status: "compensated" }).thenEnd()
}
```

**Rust**

```rust
fn withdraw(
    state: &TransferState,
    transfer: Transfer,
    ctx: &Context,
) -> StepEffect<TransferState> {
    let withdrawn: Result<Done, CommandError> =
        ctx.client()
            .invoke(WalletEntity, &transfer.from, "withdraw", transfer.amount);
    if let Err(e) = withdrawn {
        return step_effects::fail(e);
    }
    step_effects::update_state(TransferState {
        status: "withdrawn".to_string(),
        ..state.clone()
    })
    .then_transition_to_with("deposit", transfer)
}

fn deposit(
    state: &TransferState,
    transfer: Transfer,
    ctx: &Context,
) -> StepEffect<TransferState> {
    let deposited: Result<Done, CommandError> =
        ctx.client()
            .invoke(WalletEntity, &transfer.to, "deposit", transfer.amount);
    if let Err(e) = deposited {
        return step_effects::fail(e);
    }
    step_effects::update_state(TransferState {
        status: "completed".to_string(),
        ..state.clone()
    })
    .then_end()
}

/// Puts the money back.
///
/// Takes no input and reads the transfer from the state, which is why failover steps are
/// input-free: what needs compensating is whatever the workflow has recorded, not whatever
/// was known when the settings were written.
fn compensate(state: &TransferState, _: (), ctx: &Context) -> StepEffect<TransferState> {
    let transfer = &state.transfer;
    let returned: Result<Done, CommandError> =
        ctx.client()
            .invoke(WalletEntity, &transfer.from, "deposit", transfer.amount);
    if let Err(e) = returned {
        return step_effects::fail(e);
    }
    step_effects::update_state(TransferState {
        status: "compensated".to_string(),
        ..state.clone()
    })
    .then_end()
}
```

**Go**

```go
// Steps are ordinary sequential code: they call other components and wait for the answer.

func withdrawStep(ctx context.Context, s TransferState, t Transfer) (transferStep, error) {
	if _, err := ankka.Invoke[int](ctx, "wallet", t.From, "withdraw", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "withdrawn"
	return ankka.StepUpdate(s).ThenTransitionTo("deposit", t), nil
}

func depositStep(ctx context.Context, s TransferState, t Transfer) (transferStep, error) {
	if _, err := ankka.Invoke[int](ctx, "wallet", t.To, "deposit-amount", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "completed"
	return ankka.StepUpdate(s).ThenEnd(), nil
}

// compensateStep puts the money back. It takes no input and reads the transfer from the state,
// which is why failover steps are input-free: what needs compensating is whatever the workflow has
// recorded, not whatever was known when the settings were written.
func compensateStep(ctx context.Context, s TransferState, _ ankka.Unit) (transferStep, error) {
	t := s.Transfer
	if _, err := ankka.Invoke[int](ctx, "wallet", t.From, "deposit-amount", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "compensated"
	return ankka.StepUpdate(s).ThenEnd(), nil
}
```

The class is `final class TransferWorkflow(context: WorkflowContext) extends Workflow[TransferState]`,
with `def emptyState` giving the state before the workflow starts and `context.componentClient` giving
the client its steps call through. The whole file is
[TransferWorkflow.scala](https://github.com/thinkmorestupidless/ankka/blob/main/modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/TransferWorkflow.scala).

Register it on the service like any other component:

**Scala**

```scala
val service = Ankka.service
  .register(WalletEntity.descriptor)
  .register(TransferWorkflow.descriptor)
```

**Java**

```java
/** A workflow is registered on the service like any other component. */
public static ServiceBuilder service() {
  return Ankka.service("banking")
      .register(Wallet.class)
      .register(TransferWorkflow.class)
      .register(KycWorkflow.class)
      .register(OnboardingWorkflow.class);
}
```

**Kotlin**

```kotlin
Ankka.service("transfers")
    .register(WalletEntity::class.java)
    .register(TransferWorkflow::class.java)
```

**Python**

```python
return Ankka.service().register(WalletBalance).register(TransferWorkflow).register(KycWorkflow).register(OnboardingWorkflow)
```

**TypeScript**

```ts
export function transferService() {
  return Ankka.service().register(Balances).register(TransferWorkflow)
}
```

**Rust**

```rust
/// The wallets and the workflows over them, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(WalletEntity)
        .register(TransferWorkflow)
        .register(QuoteWorkflow)
        .register(KycWorkflow)
        .register(OnboardingWorkflow)
        .endpoint(QuoteEndpoint)
}
```

**Go**

```go
// TransferService registers the workflow and the wallets it moves money between.
func TransferService() *ankka.Service { return ankka.NewService(WalletEntity, TransferWorkflow) }
```

## One workflow, in every language

The shopping cart's checkout is the same durable process in each SDK: reserve what the cart holds,
charge, and check the cart out — or compensate. A workflow class holds a state type, `command` and
`query` handlers, and step methods; the runtime journals every transition before the next step begins.

**Scala**

```scala
final class CheckoutWorkflow(context: WorkflowContext) extends Workflow[Checkout]:

  private val client = context.componentClient

  def emptyState: Checkout = Checkout(context.workflowId)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(10.seconds)
      .stepRecovery(
        CheckoutWorkflow.charge,
        RecoverStrategy.maxRetries(1).failoverTo(CheckoutWorkflow.compensate)
      )
      .build

  /** `mode`: `ok`, `fail` (the charge is declined) or `pause` (a pause before it). */
  def start(mode: String): Effect[Done] =
    if currentState.status != "new" then
      effects.error(s"checkout is already ${currentState.status}", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(status = "reserving", mode = mode))
        .transitionTo(CheckoutWorkflow.reserve)
        .thenReply(Done)

  def status: ReadOnlyEffect[Checkout] = effects.reply(currentState)

  def reserveStep: StepEffect =
    // A client call from a step: what the cart holds.
    val total = cart.call(ShoppingCartEntity.totalQuantity).invoke()
    val next  =
      if currentState.mode == "pause" then CheckoutWorkflow.waitForTimeout
      else CheckoutWorkflow.charge
    stepEffects
      .updateState(currentState.copy(status = "reserved", reserved = total))
      .thenTransitionTo(next.ref)

  def waitStep: StepEffect =
    stepEffects
      .updateState(currentState.copy(status = "waiting"))
      .thenPause(1500.millis, CheckoutWorkflow.charge.ref)

  def chargeStep: StepEffect =
    if currentState.mode == "fail" then throw PaymentDeclined("payment declined")
    // Not idempotent — a retry after the cart was checked out is refused — which is why `charge` is
    // allowed one retry and then fails over, and why compensation exists.
    if currentState.reserved > 0 then cart.call(ShoppingCartEntity.checkout).invoke(): Unit
    stepEffects.updateState(currentState.copy(status = "charged")).thenEnd

  def compensateStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "compensated", reserved = 0)).thenEnd

  private def cart = client.forEventSourcedEntity(EntityId(currentState.cartId))

object CheckoutWorkflow
    extends Workflow.Companion[CheckoutWorkflow, Checkout](
      componentId = ComponentId("checkout"),
      stateSerializer = Codecs.serializer[Checkout]("checkout")
    ):

  def create(context: WorkflowContext) = new CheckoutWorkflow(context)

  val reserve = step("reserve")(_.reserveStep)
  // The wire name is "wait"; the Scala name cannot be, because `wait` is final on `AnyRef`. That the
  // two are declared separately is exactly what makes this possible.
  val waitForTimeout = step("wait")(_.waitStep)
  val charge         = step("charge")(_.chargeStep)
  val compensate     = step("compensate")(_.compensateStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
```

**Java**

```java
@ComponentId("checkout")
public class Checkout extends Workflow<Checkout.CheckoutState> {

  @Manifest("checkout")
  public record CheckoutState(String id, String mode, String status) {
    CheckoutState withStatus(String next) {
      return new CheckoutState(id, mode, next);
    }
  }

  private static final Set<String> DECLINED = Set.of("fail", "abort", "drop", "doomed");

  @Override
  protected CheckoutState emptyState() {
    return new CheckoutState(context().entityId(), "", "new");
  }

  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(10))
        .stepRecovery(Checkout::charge, RecoverStrategy.maxRetries(1).failoverTo(Checkout::compensate))
        .build();
  }

  @Command("start")
  public Effect<String> start(String mode) {
    if (!currentState().status().equals("new"))
      return effects().error(Problem.conflict("already started"));
    return effects()
        .updateState(new CheckoutState(currentState().id(), mode, "reserving"))
        .transitionTo(Checkout::reserve)
        .thenReply("started");
  }

  @Step("reserve")
  public StepEffect reserve() {
    // A step held where the platform records it running: the scripted service answers when the
    // case lets it.
    if (currentState().mode().equals("hold")) services().of("scripted").getText("/hold");
    // A client call from a step: what the cart holds.
    context()
        .componentClient()
        .forEventSourcedEntity(currentState().id())
        .call(ShoppingCartEntity::totalQuantity)
        .invoke();
    var reserved = stepEffects().updateState(currentState().withStatus("reserved"));
    return currentState().mode().equals("pause")
        ? reserved.thenTransitionTo(Checkout::waitStep)
        : reserved.thenTransitionTo(Checkout::charge);
  }

  @Step("wait")
  public StepEffect waitStep() {
    return stepEffects()
        .updateState(currentState().withStatus("waiting"))
        .thenPause(Duration.ofMillis(1500), Checkout::charge);
  }

  @Step("charge")
  public StepEffect charge() {
    if (DECLINED.contains(currentState().mode())) throw new RuntimeException("payment declined");
    return stepEffects().updateState(currentState().withStatus("charged")).thenEnd();
  }

  @Step("compensate")
  public StepEffect compensate() {
    return switch (currentState().mode()) {
      case "abort" ->
          stepEffects()
              .updateState(currentState().withStatus("aborted"))
              .thenFail("payment declined");
      case "drop" -> stepEffects().thenFail("payment declined");
      case "doomed" -> throw new RuntimeException("compensation failed too");
      default -> stepEffects().updateState(currentState().withStatus("compensated")).thenEnd();
    };
  }

  @Query("status")
  public ReadOnlyEffect<String> status() {
    return effects().reply(currentState().status());
  }
}
```

**Kotlin**

```kotlin
/** A checkout carried out as a workflow: what it is checking out, and what the cart held once it was. */
@Manifest("checkout")
data class Checkout(val cartId: String, val ordered: Cart?)

/**
 * Checks a cart out in a step. The step is a suspend fun: while it waits for the cart's answer the
 * workflow holds no thread.
 */
@ComponentId("checkout")
class CheckoutWorkflow : Workflow<Checkout>() {
    @Command("start")
    fun start(cartId: String): Effect<Done> = effects {
        updateState(Checkout(cartId, null)).transitionTo(CheckoutWorkflow::checkOut).thenDone()
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Checkout> = effects { reply(currentState()) }

    @Step("check-out")
    suspend fun checkOut(): StepEffect {
        val ordered =
            context().componentClient().forEventSourcedEntity(currentState().cartId).call(ShoppingCart::checkout).invokeSuspending()
        return stepEffects { updateState(currentState().copy(ordered = ordered)).thenEnd() }
    }
}
```

**Python**

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
        """``mode``: ``ok``, ``fail`` (the charge is declined and compensated), ``pause`` (a pause
        before it), ``abort`` (declined; the compensation records it and fails the workflow),
        ``drop`` (declined; the compensation fails the workflow and records nothing), ``doomed``
        (declined, and the compensation fails too) or ``hold`` (the reserve step waits on the
        scripted service, which a case holds, then goes on)."""
        if self.state.status != "new":
            return self.effects.error(f"checkout is already {self.state.status}", ErrorCode.CONFLICT)
        return self.effects.update_state(replace(self.state, status="reserving", mode=mode)).then_transition_to("reserve").then_reply(lambda _: DONE)

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[Checkout, Checkout]:
        return self.effects.reply(self.state)

    @step("reserve")
    async def reserve(self) -> WorkflowStepEffect[Checkout]:
        # A step held where the platform records it running: the scripted service answers when the
        # case lets it.
        if self.state.mode == "hold":
            await self.services("scripted").get_text("/hold")
        # A client call from a step: what the cart holds.
        total = await self._cart().call("total-quantity").invoke(reply=int)
        next_step = "wait" if self.state.mode == "pause" else "charge"
        return self.step_effects.update_state(replace(self.state, status="reserved", reserved=total)).then_transition_to(next_step)

    @step("wait")
    def wait(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="waiting")).then_pause(after=timedelta(milliseconds=1500), on_timeout=StepRef("charge"))

    @step("charge")
    async def charge(self) -> WorkflowStepEffect[Checkout]:
        if self.state.mode in ("fail", "abort", "drop", "doomed"):
            raise PaymentDeclined("payment declined")
        # Not idempotent — a retry after the cart was checked out is refused — which is why
        # ``charge`` is allowed one retry and then fails over, and why compensation exists.
        if self.state.reserved > 0:
            await self._cart().call("checkout").invoke(reply=ShoppingCart)
        return self.step_effects.update_state(replace(self.state, status="charged")).then_end()

    @step("compensate")
    def compensate(self) -> WorkflowStepEffect[Checkout]:
        if self.state.mode == "abort":
            return self.step_effects.update_state(replace(self.state, status="aborted", reserved=0)).then_fail("payment declined")
        if self.state.mode == "drop":
            return self.step_effects.fail("payment declined")
        if self.state.mode == "doomed":
            raise RuntimeError("compensation failed too")
        return self.step_effects.update_state(replace(self.state, status="compensated", reserved=0)).then_end()

    def _cart(self) -> Calls:
        return self.context.client.for_event_sourced_entity("shopping-cart", self.state.cartId)
```

**TypeScript**

```ts
export class CheckoutWorkflow extends Workflow<Checkout> {
  static readonly componentId = "checkout"
  static readonly state = jsonCodec(Checkout, "checkout")
  static readonly settings = workflowSettings({
    defaultStepTimeout: Duration.ofSeconds(10),
    steps: { charge: { recovery: { maxRetries: 1, failoverTo: "compensate" } } },
  })

  static readonly handlers = {
    /**
     * `mode`: `ok`, `fail` (the charge is declined and compensated), `pause` (a pause before it), `abort`
     * (declined; the compensation records it and fails the workflow) or `drop` (declined; the compensation
     * fails the workflow and records nothing).
     */
    start: command("start", s.string, Done, (w: CheckoutWorkflow, mode) => w.start(mode)),
    status: query("status", Checkout, (w: CheckoutWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    reserve: step("reserve", (w: CheckoutWorkflow) => w.reserve()),
    wait: step("wait", (w: CheckoutWorkflow) => w.wait()),
    charge: step("charge", (w: CheckoutWorkflow) => w.charge()),
    compensate: step("compensate", (w: CheckoutWorkflow) => w.compensate()),
  }

  emptyState(): Checkout {
    return { cartId: this.entityId, status: "new", reserved: 0, mode: "ok" }
  }

  start(mode: string) {
    if (this.state.status !== "new") return this.effects.error(`checkout is already ${this.state.status}`, ErrorCode.Conflict)
    return this.effects.updateState({ ...this.state, status: "reserving", mode }).thenTransitionTo("reserve").thenReply(() => done)
  }

  async reserve() {
    // `hold`: a step held where the platform records it running; the scripted service answers when
    // the case lets it.
    if (this.state.mode === "hold") await this.services.service("scripted").getText("/hold")
    // A client call from a step: what the cart holds.
    const total = await this.cart().call(ShoppingCartEntity.handlers.totalQuantity).invoke()
    const next = this.state.mode === "pause" ? "wait" : "charge"
    return this.stepEffects.updateState({ ...this.state, status: "reserved", reserved: total }).thenTransitionTo(next)
  }

  wait() {
    return this.stepEffects.updateState({ ...this.state, status: "waiting" }).thenPause({ after: Duration.ofMillis(1500), onTimeout: "charge" })
  }

  async charge() {
    if (["fail", "abort", "drop", "doomed"].includes(this.state.mode)) throw new PaymentDeclined("payment declined")
    // Not idempotent — a retry after the cart was checked out is refused — which is why `charge` is
    // allowed one retry and then fails over, and why compensation exists.
    if (this.state.reserved > 0) await this.cart().call(ShoppingCartEntity.handlers.checkout).invoke()
    return this.stepEffects.updateState({ ...this.state, status: "charged" }).thenEnd()
  }

  compensate() {
    if (this.state.mode === "abort") return this.stepEffects.updateState({ ...this.state, status: "aborted", reserved: 0 }).thenFail("payment declined")
    if (this.state.mode === "drop") return this.stepEffects.fail("payment declined")
    if (this.state.mode === "doomed") throw new Error("compensation failed too")
    return this.stepEffects.updateState({ ...this.state, status: "compensated", reserved: 0 }).thenEnd()
  }

  cart() {
    return this.client.of(ShoppingCartEntity, this.state.cartId)
  }
}
```

**Rust**

```rust
pub struct CheckoutWorkflow;

impl CheckoutWorkflow {
    /// `mode`: `ok`, `fail` (the charge is declined and compensated), `pause` (a pause before it),
    /// `abort` (declined; the compensation records it and fails the workflow) or `drop` (declined;
    /// the compensation fails the workflow and records nothing).
    fn start(checkout: &Checkout, mode: String, _: &Context) -> WorkflowEffect<Checkout, Done> {
        if checkout.status != "new" {
            let message = format!("checkout is already {}", checkout.status);
            return effects::error(ErrorCode::Conflict, message).into();
        }
        let mode = if mode.is_empty() {
            "ok".to_string()
        } else {
            mode
        };
        let reserving = Checkout {
            status: "reserving".into(),
            mode,
            ..checkout.clone()
        };
        workflow::update_state(reserving)
            .transition_to("reserve")
            .then_reply_value(Done)
    }

    fn status(checkout: &Checkout, _: (), _: &Context) -> ReadOnlyEffect<Checkout> {
        effects::reply(checkout.clone())
    }

    fn reserve(checkout: &Checkout, _: (), ctx: &Context) -> StepEffect<Checkout> {
        // `hold`: a step held where the platform records it running; the scripted service answers
        // when the case lets it.
        if checkout.mode == "hold" {
            let services = ctx.services().expect("a step calls other services");
            let _ = services.service("scripted").get_text("/hold");
        }
        // A client call from a step: what the cart holds.
        let total: i32 = ctx
            .client()
            .invoke(ShoppingCart, &checkout.cart_id, "total-quantity", ())
            .expect("the cart answers");
        let next = if checkout.mode == "pause" {
            "wait"
        } else {
            "charge"
        };
        let reserved = Checkout {
            status: "reserved".into(),
            reserved: total,
            ..checkout.clone()
        };
        step_effects::update_state(reserved).then_transition_to(next)
    }

    fn wait(checkout: &Checkout, _: (), _: &Context) -> StepEffect<Checkout> {
        let waiting = Checkout {
            status: "waiting".into(),
            ..checkout.clone()
        };
        step_effects::update_state(waiting).then_pause_for(Duration::of_millis(1500), "charge")
    }

    fn charge(checkout: &Checkout, _: (), ctx: &Context) -> StepEffect<Checkout> {
        if matches!(checkout.mode.as_str(), "fail" | "abort" | "drop" | "doomed") {
            panic!("payment declined");
        }
        // Not idempotent — a retry after the cart was checked out is refused — which is why
        // `charge` is allowed one retry and then fails over, and why compensation exists.
        if checkout.reserved > 0 {
            let _: Cart = ctx
                .client()
                .invoke(ShoppingCart, &checkout.cart_id, "checkout", ())
                .expect("the cart checks out");
        }
        let charged = Checkout {
            status: "charged".into(),
            ..checkout.clone()
        };
        step_effects::update_state(charged).then_end()
    }

    fn compensate(checkout: &Checkout, _: (), _: &Context) -> StepEffect<Checkout> {
        let declined = || CommandError::new(ErrorCode::Internal, "payment declined");
        match checkout.mode.as_str() {
            "abort" => {
                let aborted = Checkout {
                    status: "aborted".into(),
                    reserved: 0,
                    ..checkout.clone()
                };
                return step_effects::update_state(aborted).then_fail(declined());
            }
            "drop" => return step_effects::fail(declined()),
            "doomed" => panic!("compensation failed too"),
            _ => {}
        }
        let compensated = Checkout {
            status: "compensated".into(),
            reserved: 0,
            ..checkout.clone()
        };
        step_effects::update_state(compensated).then_end()
    }
}

impl Workflow for CheckoutWorkflow {
    type State = Checkout;
    const COMPONENT_ID: &'static str = "checkout";
    const STATE_MANIFEST: Option<&'static str> = Some("checkout");

    fn empty_state(cart_id: &str) -> Checkout {
        Checkout {
            cart_id: cart_id.into(),
            status: "new".into(),
            reserved: 0,
            mode: "ok".into(),
        }
    }

    fn handlers() -> WorkflowHandlers<CheckoutWorkflow> {
        WorkflowHandlers::new()
            .command("start", CheckoutWorkflow::start)
            .query("status", CheckoutWorkflow::status)
    }

    fn steps() -> Steps<CheckoutWorkflow> {
        Steps::new()
            .step("reserve", CheckoutWorkflow::reserve)
            .step("wait", CheckoutWorkflow::wait)
            .step("charge", CheckoutWorkflow::charge)
            .step("compensate", CheckoutWorkflow::compensate)
    }

    fn settings() -> WorkflowSettings {
        WorkflowSettings::new()
            .default_step_timeout(Duration::of_seconds(10))
            .step_recovery("charge", Recovery::retries(1).failover_to("compensate"))
    }
}
```

**Go**

```go
// Checkout is a checkout's state, in the Scala reference's shape: the id, the mode it was started
// in and where it has got to.
type Checkout struct {
	ID     string `json:"id"`
	Mode   string `json:"mode"`
	Status string `json:"status"`
}

var errDeclined = errors.New("payment declined")

func step(s Checkout) ankka.StepBuilder[Checkout] { return ankka.StepUpdate(s) }

// CheckoutFlow is a checkout as a durable multi-step process: reserve, then charge, or compensate.
// mode is ok, fail (the charge is declined and compensated), pause (a pause before charging),
// abort (declined; the compensation records it and fails the workflow), drop (declined; the
// compensation fails the workflow and records nothing) or doomed (declined, and the compensation
// fails too, so the workflow fails).
var CheckoutFlow = ankka.Workflow[Checkout]("checkout", func(id string) Checkout {
	return Checkout{ID: id, Status: "new"}
},
	ankka.WorkflowCommand("start", func(_ context.Context, s Checkout, mode string) (ankka.WorkflowEffect[Checkout, string], error) {
		if s.Status != "new" {
			return ankka.WorkflowEffect[Checkout, string]{}, ankka.Refuse(ankka.Conflict, "already started")
		}
		s.Status, s.Mode = "reserving", mode
		return ankka.WorkflowUpdate[Checkout, string](s).ThenTransitionTo("reserve", nil).ThenReplyValue("started"), nil
	}),
	ankka.Query("status", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		return ankka.Reply(s.Status), nil
	}),
	ankka.Step("reserve", func(ctx context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		// `hold`: a step held where the platform records it running; the scripted service answers
		// when the case lets it.
		if s.Mode == "hold" {
			if _, err := ankka.CallService(ctx, "scripted", ankka.ServiceRequest{Method: "GET", Path: "/hold"}); err != nil {
				return ankka.StepEffect[Checkout]{}, err
			}
		}
		// A client call from a step: what the cart holds.
		if _, err := ankka.Invoke[int](ctx, cart.ComponentID, s.ID, "total-quantity", ankka.Unit{}); err != nil {
			return ankka.StepEffect[Checkout]{}, err
		}
		next := "charge"
		if s.Mode == "pause" {
			next = "wait"
		}
		s.Status = "reserved"
		return step(s).ThenTransitionTo(next, nil), nil
	}),
	ankka.Step("wait", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		s.Status = "waiting"
		return step(s).ThenPause(ankka.PauseOptions{After: 1500 * time.Millisecond, OnTimeout: "charge"}), nil
	}),
	ankka.Step("charge", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		if slices.Contains([]string{"fail", "abort", "drop", "doomed"}, s.Mode) {
			return ankka.StepEffect[Checkout]{}, errDeclined
		}
		s.Status = "charged"
		return step(s).ThenEnd(), nil
	}),
	ankka.Step("compensate", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		switch s.Mode {
		case "abort":
			s.Status = "aborted"
			return step(s).ThenFail(ankka.WorkflowFailed, "payment declined"), nil
		case "drop":
			return ankka.FailWorkflow[Checkout](ankka.WorkflowFailed, "payment declined"), nil
		case "doomed":
			return ankka.StepEffect[Checkout]{}, errors.New("compensation failed too")
		}
		s.Status = "compensated"
		return step(s).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Checkout](ankka.Manifest("checkout"))),
	ankka.StepTimeout(10*time.Second),
	ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
)
```

A Scala step names its successor with the companion's step handle, which carries the wire name; Python
and TypeScript name it by the wire name itself, `then_transition_to("charge")` and
`thenTransitionTo("charge")`, Java and Kotlin name it by a method reference to the `@Step` method,
`thenTransitionTo(Checkout::charge)`, and Go likewise by name, `ThenTransitionTo("charge", input)`; each passes an input as a further argument when the step takes one. In every
case the transition is journalled and the runtime runs one step at a time.

Steps may be asynchronous. In Python and TypeScript they are `async` and call other components through
the client; in Scala, Java and Kotlin a step is ordinary blocking code on a virtual thread, so `invoke` inside a step
costs nothing but the wait. A Go step is a plain function given the handler's `context.Context`, and
calls other components with it; a returned error is a step that failed, which its recovery handles.

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

**Java**

```java
@Override
protected WorkflowSettings settings() {
  return WorkflowSettings.builder()
      .timeout(Duration.ofSeconds(60))
      .defaultStepTimeout(Duration.ofSeconds(10))
      // One retry, then compensate. The deposit is the step that can fail for reasons
      // outside this workflow's control, so it is the one worth a second attempt.
      .stepRecovery(
          TransferWorkflow::deposit,
          RecoverStrategy.maxRetries(1).failoverTo(TransferWorkflow::compensate))
      .build();
}
```

**Kotlin**

```kotlin
override fun settings(): WorkflowSettings =
    WorkflowSettings.builder()
        .timeout(Duration.ofSeconds(60))
        .defaultStepTimeout(Duration.ofSeconds(10))
        // One retry, then compensate. The deposit is the step that can fail for reasons
        // outside this workflow's control, so it is the one worth a second attempt.
        .stepRecovery(
            stepRef(TransferWorkflow::depositStep),
            RecoverStrategy.maxRetries(1).failoverTo(stepRef(TransferWorkflow::compensateStep)),
        )
        .build()
```

**Python**

```python
settings = WorkflowSettings(
    timeout=timedelta(seconds=60),
    default_step_timeout=timedelta(seconds=10),
    # One retry, then compensate. The deposit is the step that can fail for reasons outside
    # this workflow's control, so it is the one worth a second attempt.
    steps={"deposit": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate"))},
)
```

**TypeScript**

```ts
static readonly settings = workflowSettings({
  timeout: Duration.ofSeconds(60),
  defaultStepTimeout: Duration.ofSeconds(10),
  // One retry, then compensate. The deposit is the step that can fail for reasons outside this
  // workflow's control, so it is the one worth a second attempt.
  steps: { deposit: { recovery: { maxRetries: 1, failoverTo: "compensate" } } },
})
```

**Rust**

```rust
fn settings() -> WorkflowSettings {
    WorkflowSettings::new()
        .timeout(Duration::of_seconds(60))
        .default_step_timeout(Duration::of_seconds(10))
        // One retry, then compensate. The deposit is the step that can fail for reasons
        // outside this workflow's control, so it is the one worth a second attempt.
        .step_recovery("deposit", Recovery::retries(1).failover_to("compensate"))
}
```

**Go**

```go
ankka.StepTimeout(10*time.Second),
ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
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
`Recovery(max_retries=1, failover_to="compensate")` in Python,
`ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"}))` in Go. Without a failover step, a step that
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

A pause with a deadline outlives the instance that paused. The deadline is journalled with the pause and
kept as a timer in the service's database, under the name `ankka-pause-<component id>-<workflow id>`, so
the workflow moves to `onTimeout` on time even when nothing else reaches it: after it was removed from
memory for being idle, after a restart and across a redeployment. Every service with a workflow runs the
timer sweeper for this, whether or not it registers `TimerRuntime` itself. A workflow that was not in
memory resumes up to one poll of the sweeper after its deadline, a second by default. A command that ends
the pause first removes its timer, and a deadline that is no longer the workflow's does nothing.

## Queries while a step runs

A workflow keeps answering commands and queries while a step is running. A status query does not wait
for a slow step to finish, and it sees the state as of the last journalled transition. Updates a step
makes are visible once the step returns its effect.

## Domain state and lifecycle

A workflow's state is your type and says what the process has decided. It does not say whether the
engine is still running it. A plan whose state reads "consulting" looks the same whether a model call is
in flight or the workflow failed an hour ago.

Every workflow, in every language, therefore answers a lifecycle query the runtime implements itself,
whatever handlers its author declared:

**Scala**

```scala
/** The engine's own view: running, paused, completed or failed, and why. */
get("/{planId}/lifecycle") { (planId: String) =>
  val lifecycle = client.forWorkflow(EntityId(planId)).lifecycle(PlannerWorkflow).invoke()
  s"${lifecycle.status}${lifecycle.failure.map(reason => s": $reason").getOrElse("")}"
}
```

**Java**

```java
/** The engine's own view: running, paused, completed or failed, and why. */
@Get("/plans/{planId}/lifecycle")
public String lifecycle(String planId) {
  var lifecycle =
      componentClient().forWorkflow(planId).lifecycle(PlannerWorkflow.class).invoke();
  return lifecycle.status() + lifecycle.failure().map(reason -> ": " + reason).orElse("");
}
```

**Kotlin**

```kotlin
/** The engine's own view: running, paused, completed or failed, and why. */
@Get("/transfers/{id}/lifecycle")
fun lifecycle(id: String): String {
    val lifecycle = componentClient().forWorkflow(id).lifecycle(TransferWorkflow::class.java).invoke()
    return lifecycle.status() + (lifecycle.failure().orNull()?.let { ": $it" } ?: "")
}
```

**Python**

```python
@get("/{cartId}/checkouts/lifecycle")
async def checkout_lifecycle(self, cartId: str) -> str:
    """The engine's own view: running, paused, completed or failed, and why."""
    lifecycle = await self.client.for_workflow("checkout", cartId).lifecycle()
    return lifecycle.status.value + (f": {lifecycle.failure}" if lifecycle.failure else "")
```

**TypeScript**

```ts
/** The engine's own view: running, paused, completed or failed, and why. */
checkoutLifecycle: get("/{cartId}/checkouts/lifecycle", s.string, async (ep: ShoppingCartEndpoint, req) => {
  const lifecycle = await ep.client.of(CheckoutWorkflow, req.params.cartId).lifecycle()
  return lifecycle.status + (lifecycle.failure ? `: ${lifecycle.failure}` : "")
}),
```

**Rust**

```rust
/// The engine's view of a transfer: running, paused, completed or failed, and why it failed.
/// The transfer's own state answers what it decided; this read runs no handler of it.
fn lifecycle(request: &Request) -> Result<String, HttpProblem> {
    let lifecycle = request
        .client()
        .lifecycle(TransferWorkflow, request.path("id"))?;
    Ok(match lifecycle.failure {
        Some(reason) => format!("{}: {reason}", lifecycle.status),
        None => lifecycle.status.to_string(),
    })
}
```

**Go**

```go
// The engine's view of a quote: running, paused, completed or failed, and why it failed. The
// quote's own state says what it decided; this read runs no handler of it.
ankka.Get("/{id}/lifecycle", func(ctx context.Context, req *ankka.Request) (string, error) {
	lifecycle, err := ankka.Lifecycle(ctx, "quote", req.Param("id"))
	if err != nil {
		return "", err
	}
	if lifecycle.Failure != nil {
		return string(lifecycle.Status) + ": " + *lifecycle.Failure, nil
	}
	return string(lifecycle.Status), nil
}),
```

The answer carries the status (not started, running, paused, completed or failed), the step pending, a
count of retries per step, and the failure reason when there is one, and says whether the workflow has
ended, completed or failed. Each language names these in its own idiom: `WorkflowLifecycle` and
`isTerminal` in Scala, Java and Kotlin, `Lifecycle` and `is_terminal` in Python and Rust, `ankka.WorkflowLifecycle`
and `IsTerminal()` in Go, and a `Lifecycle` whose `status` is `"failed"` or `"not-started"` in TypeScript.
Handler names beginning `ankka:` are reserved for queries like this one, and registering one is refused.

The same answer, the workflow's **standing**, reaches a view or a consumer that reads the workflow, as data
with each state the workflow records, so a listing of failed checkouts needs no status field written into
the state. A view or a consumer is handed only the states a workflow records: a step whose outcome a reader
must see records it in the state, and a failover step that records the failure before it fails the
workflow is what makes a failure visible. See [Reading a workflow](views.md#reading-a-workflow) and
[Reacting to a workflow](consumers.md#reacting-to-a-workflow).

## Waiting for the end

A command's reply says the workflow started, not how it finished. A caller that needs the result waits
for the workflow's end. The runtime answers the wait when the end is recorded, so there is no query to
poll and no interval to choose, and the answer comes within the time it takes to record the end and
reply, however long the workflow ran.

### Starting a workflow and waiting for its end

Sending a command and waiting for the end is one call. The command is sent first; once it is accepted,
the caller waits for the end, within the same timeout.

**Scala**

```scala
/** Starts the quote and answers the request with the quote, once the workflow has ended. */
postBody("/{id}") { (id: String, request: QuoteRequest) =>
  client
    .forWorkflow(EntityId(id))
    .call(QuoteWorkflow.start)
    .thenAwaitEnd(30.seconds)
    .invoke(request)
}
```

**Java**

```java
// Starts a checkout and answers with how it ended, as one request; a failed one is 424.
@Post("/conformance/checkout-await/{id}")
public String checkoutAwait(String id, @Body String mode) {
  try {
    return componentClient()
        .forWorkflow(id)
        .callThenAwaitEnd(Checkout::start, Duration.ofSeconds(30))
        .invoke(mode)
        .status();
  } catch (CommandException failed) {
    var end = failed.workflowFailure();
    if (end.isEmpty()) throw failed;
    throw new ProblemException(
        new Problem(424, "", end.get().step().orElse("") + ": " + end.get().reason()));
  }
}
```

**Kotlin**

```kotlin
// Starts a checkout and answers with how it ended, as one request; a failed one is 424.
@Post("/conformance/checkout-await/{id}")
fun checkoutAwait(id: String, @Body mode: String): String =
    try {
        componentClient().forWorkflow(id).callThenAwaitEnd(Checkout::start, Duration.ofSeconds(30)).invoke(mode).status
    } catch (failed: CommandException) {
        val end = failed.workflowFailureOrNull ?: throw failed
        throw ProblemException(Problem(424, "", "${end.step.orNull() ?: ""}: ${end.reason}"))
    }
```

**Python**

```python
@post("/{cartId}/checkouts/wait")
async def check_out_and_wait(self, cartId: str, mode: str) -> Checkout:
    """Starts the checkout and answers with how it ended, as one request."""
    checkout = self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId)
    return await checkout.call("start").then_await_end(30.0).invoke(mode or "ok", reply=Checkout)
```

**TypeScript**

```typescript
/** Starts the checkout and answers with how it ended, as one request. */
checkOutAndWait: post("/{cartId}/checkouts/wait", s.string, Checkout, (ep: ShoppingCartEndpoint, req, mode) =>
  ep.client.of(CheckoutWorkflow, req.params.cartId).call(CheckoutWorkflow.handlers.start).thenAwaitEnd<Checkout>(30_000).invoke(mode || "ok"),
),
```

**Rust**

```rust
/// Starts the quote and answers the request with the quote, once the workflow has ended.
fn quote(request: &Request, asked: QuoteRequest) -> Result<Quote, HttpProblem> {
    Ok(request.client().invoke_then_await_end(
        QuoteWorkflow,
        request.path("id"),
        "start",
        asked,
        Duration::of_seconds(30),
    )?)
}
```

**Go**

```go
// Starts a checkout and answers with how it ended, as one request; a failed one is 424.
ankka.Post("/checkout-await/{id}", func(ctx context.Context, req *ankka.Request, mode string) (string, error) {
	end, err := ankka.SendAndAwait[Checkout](ctx, "checkout", req.Param("id"), "start", mode, 30*time.Second)
	if r, ok := ankka.AsRefusal(err); ok && r.Code == ankka.WorkflowFailed {
		return "", &ankka.HttpProblem{Status: 424, Message: r.Details["step"] + ": " + r.Details["reason"]}
	}
	return end.Status, err
}),
```

The command's own reply is not kept: a caller that wants both sends the command, then waits. A command the
workflow refuses is answered with that refusal at once, and no wait begins.

### Waiting for a workflow already started

Any caller that may call a workflow can wait for its end, whoever started it: an endpoint asked for a
result an event started, a caller that reconnects, or several callers at once, each of which is answered.

**Scala**

```scala
/**
 * Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if not.
 */
get("/{id}/wait") { (id: String) =>
  client.forWorkflow(EntityId(id)).awaitEnd(QuoteWorkflow, 10.seconds)
}
```

**Java**

```java
/** A plan started earlier, by anyone: at once if it has ended, when it ends if not. */
@Get("/plans/{planId}/wait")
public PlannerWorkflow.Plan await(String planId) {
  return componentClient()
      .forWorkflow(planId)
      .awaitEnd(PlannerWorkflow.class, Duration.ofSeconds(10));
}
```

**Kotlin**

```kotlin
/** Answers with a transfer started earlier, by anyone: at once if it has ended, when it ends if not. */
@Get("/transfers/{id}/end")
fun end(id: String): TransferState = componentClient().forWorkflow(id).awaitEnd(TransferWorkflow::class.java, Duration.ofSeconds(10))
```

**Python**

```python
@get("/{cartId}/checkouts/end")
async def checkout_end(self, cartId: str) -> Checkout:
    """How a checkout someone else started ended: at once if it has, when it does if not."""
    return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).await_end(10.0, reply=Checkout)
```

**TypeScript**

```typescript
/** How a checkout someone else started ended: at once if it has, when it does if not. */
checkoutEnd: get("/{cartId}/checkouts/end", Checkout, (ep: ShoppingCartEndpoint, req) =>
  ep.client.of(CheckoutWorkflow, req.params.cartId).awaitEnd<Checkout>(10_000),
),
```

**Rust**

```rust
/// Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if
/// not.
fn wait(request: &Request) -> Result<Quote, HttpProblem> {
    Ok(request.client().await_end(
        QuoteWorkflow,
        request.path("id"),
        Duration::of_seconds(10),
    )?)
}
```

**Go**

```go
// Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if
// not. A quote that failed is 424, naming the step and the reason.
ankka.Get("/{id}/wait", func(ctx context.Context, req *ankka.Request) (Quote, error) {
	quote, err := ankka.AwaitEnd[Quote](ctx, "quote", req.Param("id"), 10*time.Second)
	if r, ok := ankka.AsRefusal(err); ok && r.Code == ankka.WorkflowFailed {
		return Quote{}, &ankka.HttpProblem{Status: 424, Message: r.Details["step"] + ": " + r.Details["reason"]}
	}
	return quote, err
}),
```

### What each ending answers

| The workflow | The wait answers |
|---|---|
| completed | the state it ended with |
| failed | an error whose code is `WorkflowFailed`, whose details name the step that failed and the reason |
| deleted | `WorkflowFailed`, whose details say it was deleted and name no step |
| paused | nothing yet: a paused workflow has not ended, and the wait goes on |

A workflow that has already ended is answered at once, from what was recorded. `WorkflowFailed` is neither
a refusal of the call nor a fault: the call was answered, and what it waited for failed. In Scala
`WorkflowEnd.failure(error)` reads its step and reason; in Python they are `error.details["step"]` and
`["reason"]`, in TypeScript `error.details.step` and `.reason`, and in Java and Kotlin the
`CommandException`'s `workflowFailure()` answers one whose `step()` and `reason()` say the same. An endpoint answers it with HTTP 424.

### The caller says how long it waits

Every wait takes a timeout from the caller, and there is no default. A wait not answered in time fails
with `Timeout`, and the workflow runs on; a wait made again is answered when it ends. The timeout is the
caller's, not `ankka.ask-timeout`: the runtime holds each ask for at most half of that and asks again
until the caller's time is up, so a workflow that moves to another instance, or whose instance stops,
loses no caller. Nothing about a wait is recorded.

A wait holds the workflow in memory while it waits. A wait is for a workflow that will end: one whose
commands only update its state never moves to a step and never ends, and every wait for it times out.

Wait from an endpoint, a workflow step, a timed action or an agent's tool. A consumer may wait too, but
it holds that slice of the consumer's events for as long as the wait lasts: start the workflow from a
consumer and wait for it from an endpoint instead. A step that waits for another workflow is bounded by
its own timeout, and fails as timed out if the other has not ended by then; the other runs on.

**Scala**

```scala
def verifyStep: StepEffect =
  val kyc = context.componentClient
    .forWorkflow(EntityId(s"kyc-$id"))
    .call(KycWorkflow.start)
    .thenAwaitEnd(OnboardingWorkflow.VerifyTimeout)
    .invoke(currentState.applicant)
  if kyc.decision.contains("approved") then
    stepEffects.thenTransitionTo(OnboardingWorkflow.welcome)
  else stepEffects.thenTransitionTo(OnboardingWorkflow.decline)
```

**Java**

```java
@Step("verify")
public StepEffect verify() {
  var kyc =
      context()
          .componentClient()
          .forWorkflow("kyc-" + context().entityId())
          .callThenAwaitEnd(KycWorkflow::start, VERIFY_TIMEOUT)
          .invoke(currentState().applicant());
  return kyc.decision().equals("approved")
      ? stepEffects().thenTransitionTo(OnboardingWorkflow::welcome)
      : stepEffects().thenTransitionTo(OnboardingWorkflow::decline);
}
```

**Kotlin**

```kotlin
@Step("verify")
fun verifyStep(): StepEffect {
    val kyc =
        context()
            .componentClient()
            .forWorkflow("kyc-${context().entityId()}")
            .callThenAwaitEnd(KycWorkflow::start, VERIFY_TIMEOUT)
            .invoke(currentState().applicant)
    return stepEffects {
        if (kyc.decision == "approved") thenTransitionTo(stepRef(OnboardingWorkflow::welcomeStep))
        else thenTransitionTo(stepRef(OnboardingWorkflow::declineStep))
    }
}
```

**Python**

```python
@step("verify")
async def verify(self) -> WorkflowStepEffect[Onboarding]:
    kyc = await (
        self.context.client.for_workflow("kyc", f"kyc-{self.entity_id}")
        .call("start")
        .then_await_end(VERIFY_TIMEOUT)
        .invoke(self.state.applicant, reply=Kyc)
    )
    if kyc.decision == "approved":
        return self.step_effects.transition_to("welcome")
    return self.step_effects.transition_to("decline")
```

**TypeScript**

```ts
async verifyStep() {
  const kyc = await this.client
    .of(KycWorkflow, `kyc-${this.entityId}`)
    .call(KycWorkflow.handlers.start)
    .thenAwaitEnd<Kyc>(OnboardingWorkflow.VerifyTimeout)
    .invoke(this.state.applicant)
  return this.stepEffects.transitionTo(kyc.decision === "approved" ? "welcome" : "decline")
}
```

**Rust**

```rust
fn verify(onboarding: &Onboarding, _: (), ctx: &Context) -> StepEffect<Onboarding> {
    let kyc: Kyc = match ctx.client().invoke_then_await_end(
        KycWorkflow,
        &format!("kyc-{}", ctx.entity_id()),
        "start",
        onboarding.applicant.clone(),
        OnboardingWorkflow::verify_timeout(),
    ) {
        Ok(kyc) => kyc,
        Err(e) => return step_effects::fail(e),
    };
    if kyc.decision.as_deref() == Some("approved") {
        step_effects::transition_to("welcome")
    } else {
        step_effects::transition_to("decline")
    }
}
```

**Go**

```go
// verifyStep starts the applicant's check and waits for it to end, as one call: the step is
// bounded by its own timeout, and fails as timed out if the check has not ended by then.
func verifyStep(ctx context.Context, o Onboarding, _ ankka.Unit) (onboardingStep, error) {
	kyc, err := ankka.SendAndAwait[Kyc](ctx, "kyc", "kyc-"+onboardingID(ctx), "start", o.Applicant, VerifyTimeout)
	if err != nil {
		return onboardingStep{}, err
	}
	if kyc.Decision == "approved" {
		return ankka.StepUpdate(o).ThenTransitionTo("welcome", ankka.Unit{}), nil
	}
	return ankka.StepUpdate(o).ThenTransitionTo("decline", ankka.Unit{}), nil
}
```

### Serving a wait as a stream

Serve a wait as server-sent events when it may outlast a connection's idle timeout. The service ends an
HTTP connection that is quiet for `pekko.http.server.idle-timeout`, sixty seconds unless it is set, so a
wait answered as one whole response is cut if the workflow takes longer. The stream form sends a
`heartbeat` event while the wait goes on, then one event with the end — `ended` with the state, `failed`,
or `timed-out` — and closes. The heartbeat is every event stream's, `ankka.http.sse.heartbeat` (15 seconds),
or half the idle timeout when that is sooner, and the caller does not set it; see
[configuration](../reference/configuration.md).

**Scala**

```scala
/**
 * A quote's end as server-sent events: a heartbeat while it is worked out, then the quote, on one
 * connection however long that takes.
 */
sseEvents("/{id}/events") { (id: String) =>
  clients.awaitEnd(EntityId(id), QuoteWorkflow, 10.minutes)
}
```

**Java**

```java
@Sse("/checkouts/{id}/end")
public Flow.Publisher<SseEvent> end(String id) {
  return componentClient().forWorkflow(id).awaitEndEvents(Checkout.class, Duration.ofSeconds(30));
}
```

**Kotlin**

```kotlin
/**
 * A transfer's end as server-sent events: a heartbeat while it runs, then the state, on one
 * connection however long that takes.
 */
@Sse("/transfers/{id}/events")
fun events(id: String): Flow.Publisher<SseEvent> =
    componentClient().forWorkflow(id).awaitEndEvents(TransferWorkflow::class.java, Duration.ofMinutes(10))
```

**Python**

```python
@sse("/{cartId}/checkouts/events")
async def checkout_events(self, cartId: str) -> AsyncIterator[str]:
    """A heartbeat while the checkout goes on, then how it ended, on one connection."""
    checkout = self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId)
    async for part in checkout.await_end_parts(600.0, reply=Checkout):
        yield part.to_json()
```

**TypeScript**

```typescript
/** A heartbeat while the checkout goes on, then how it ended, on one connection. */
checkoutEvents: sse("/{cartId}/checkouts/events", async function* (ep: ShoppingCartEndpoint, req) {
  for await (const part of ep.client.of(CheckoutWorkflow, req.params.cartId).awaitEndParts<Checkout>(600_000)) yield awaitPartJson(part)
}),
```

**Go**

```go
// A quote's end as server-sent events: the wait as one event, on one connection. A Go route
// sends no heartbeat of its own while it waits, so a wait longer than a connection's idle
// timeout is served as a plain wait made again by the caller.
ankka.SSEEvents("/{id}/events", func(ctx context.Context, req *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
	return func(yield func(ankka.SSEEvent, error) bool) {
		quote, err := ankka.AwaitEnd[Quote](ctx, "quote", req.Param("id"), 10*time.Minute)
		switch r, refused := ankka.AsRefusal(err); {
		case err == nil:
			yield(ankka.SSEEvent{Name: "ended", Data: quote}, nil)
		case refused && r.Code == ankka.WorkflowFailed:
			yield(ankka.SSEEvent{Name: "failed", Text: r.Details["reason"]}, nil)
		case refused && r.Code == ankka.Timeout:
			yield(ankka.SSEEvent{Name: "timed-out"}, nil)
		default:
			yield(ankka.SSEEvent{}, err)
		}
	}
}),
```

A gateway in front of the service still bounds the response, as it bounds any stream through it; see
[limitations](../reference/limitations.md). In Rust, a module waits with `Client::await_end` and
`invoke_then_await_end`; a module cannot stream, and its instance is held for as long as the wait, so
wait from a step, a route or a tool rather than a command.

Every instance of the service, and the runtime beside a process, must be at a release that can wait. An
instance from before waiting answers a wait as an unknown handler, and the caller is told so; a process
beside an older runtime is refused when it waits, naming the protocol version waiting needs.

## Calling agents and entities from steps

A step is where a workflow does its work, and the work is calls through the component client. The
[multi-agent orchestration](multi-agent-orchestration.md) guide shows a workflow that consults several
agents in parallel and combines their answers. Two properties make that safe:

- Each step's result is journalled before the next step starts. A crash after two of three expensive
  calls resumes at the third rather than paying for the first two again.
- A step that calls another component can fail for reasons outside the workflow. Declare a timeout that
  fits the slowest call it makes; model calls in particular need far longer than the 30-second default.

## Testing a workflow

A workflow's commands and steps are driven by hand with `WorkflowTestKit`, with no runtime. Its
transitions, recovery and timeouts are the runtime's behaviour, tested through the integration test kit,
which starts the whole service against a throwaway Postgres. [Testing](testing.md) shows both. A pause's
deadline is read by the service's timer runtime's clock, so in the Scala kit a pause of a day ends when
the test moves a `MovableClock` past it (see [Timers](timers.md#testing-timers)).
