# Secrets a service keeps

> Keep a credential a person gives your service in its secret store — its own database, encrypted, or Google Secret Manager; never a journal or a view — read it back by name, and find every read afterwards.

Source: https://docs.ankka.cloud/build/secrets/
A service sometimes has to hold a value it must never record: a payment provider's API key that an
operator enters through the service's own backoffice, a token a partner issued for one merchant. Kept as
an entity's state, that value would be in the journal, in every snapshot, in every view built from them,
and in every backup of any of those, readable by anything that can read the database.

The **secret store** is where such a value goes. It is apart from everything the service's components
know: it is not an entity, not a view, and nothing a projection reads. Where it keeps a value is the
installation's choice: by default a table in the service's own database, each value encrypted with the
service's **secret key**, so a dump of every table holds the value in one row, encrypted, and nowhere
else; or Google Secret Manager, where the service's database holds nothing of it. Your code is the same
on either.

A value kept here is a **service secret**: a name and a text value, kept and read by the service while it
runs. A value a member sets for a project before a service starts, which a descriptor's variable takes,
is a different thing — a project secret, described in
[Secrets on the platform](../platform/secrets.md).

## Who has the store

The store is offered to endpoints, workflow steps, consumers, timed actions and agents. It is **not**
offered to an entity or a view: their contexts have no secret store at all, so in Scala reaching for one
does not compile, in Python, TypeScript and Rust the attribute does not exist, and in Go
`ankka.Secrets(ctx)` refuses every call made with an entity's or a view's context. A read from the store is a blocking
database call, which an entity's single-writer path must not make, and a value read inside an entity's
handler is one line away from being put into an event or a state — the leak the store exists to prevent.

A workflow's command handlers and its steps share one context, so a workflow's store answers only in a
step; a command handler that calls it is refused.

## Keeping and reading

Three operations, by name:

- **put(name, value)** keeps the value, replacing what was there.
- **get(name)** answers the value, or nothing when none is kept. It never answers an empty string.
- **delete(name)** removes the value. Removing nothing is not an error.

Each blocks the handler until the database has answered. The components that have a store run on
virtual threads (Scala, Java, Kotlin) or call through the runtime beside them (Python, TypeScript, Rust, Go), so the wait
holds nothing else up.

An endpoint through which an operator enters a provider's credential:

**Scala**

```scala
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
final class ProviderCredentialsEndpoint(secrets: SecretStore) extends HttpEndpoint("/credentials"):

  private given JsonValueCodec[KeepSecret] = Codecs.make[KeepSecret]

  val acl: Acl = Acl.AllowAll

  postBody("/") { (request: KeepSecret) =>
    secrets.put(request.name, request.value)
    Done: Done
  }

  get("/") { () =>
    val name = query.required[String]("name")
    secrets.get(name).getOrElse(throw HttpProblem.notFound(s"no secret '$name'"))
  }

  delete("/") { () =>
    secrets.delete(query.required[String]("name"))
    Done: Done
  }
```

**Java**

```java
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
public class CredentialsRoutes extends HttpEndpoint {

  public record KeepSecret(String name, String value) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/credentials")
  public Done keep(@Body KeepSecret request) {
    secrets().put(request.name(), request.value());
    return Done.done();
  }

  // The name is a query parameter because it may hold a slash.
  @Get("/credentials")
  public String read() {
    var name = request().requiredQuery("name");
    return secrets()
        .get(name)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no secret '" + name + "'")));
  }

  @Delete("/credentials")
  public Done remove() {
    secrets().delete(request().requiredQuery("name"));
    return Done.done();
  }
}
```

**Kotlin**

```kotlin
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
class ProviderCredentialsEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        post<KeepSecret>("/credentials") { request ->
            secrets().put(request.name, request.value)
            Done.done()
        }

        get("/credentials") {
            val name = request.requiredQuery("name")
            secrets().getOrNull(name) ?: throw ProblemException(Problem.notFound("no secret '$name'"))
        }

        delete("/credentials") {
            secrets().delete(request.requiredQuery("name"))
            Done.done()
        }
    }
}
```

**Python**

```python
def _secret_name(self) -> str:
    return next((v for k, v in self.request.query if k == "name"), "")

@post("/secrets")
async def keep_secret(self, value: str) -> Done:
    await self.secrets.put(self._secret_name(), value)
    return DONE

@get("/secrets")
async def read_secret(self) -> str:
    name = self._secret_name()
    value = await self.secrets.get(name)
    if value is None:
        raise HttpProblem(404, f"no secret '{name}'")
    return value

@delete("/secrets")
async def remove_secret(self) -> Done:
    await self.secrets.delete(self._secret_name())
    return DONE
```

**TypeScript**

```ts
const KeepSecret = s.record("KeepSecret", { name: s.string, value: s.string })

/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
export class ProviderCredentialsEndpoint extends Endpoint {
  static readonly prefix = "/credentials"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    keep: post("/", KeepSecret, Done, async (ep: ProviderCredentialsEndpoint, _req, request) => {
      await ep.secrets.put(request.name, request.value)
      return done
    }),
    read: get("/", s.string, async (ep: ProviderCredentialsEndpoint, req) => {
      const name = req.query.get("name") ?? ""
      const value = await ep.secrets.get(name)
      if (value === undefined) throw new HttpProblem(404, `no secret '${name}'`)
      return value
    }),
    remove: del("/", Done, async (ep: ProviderCredentialsEndpoint, req) => {
      await ep.secrets.delete(req.query.get("name") ?? "")
      return done
    }),
  }
}
```

**Rust**

```rust
/// Where a person gives the service a credential it must keep. The value goes to the secret store
/// and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
pub struct ProviderCredentialsEndpoint;

impl ProviderCredentialsEndpoint {
    fn secrets(request: &Request) -> Result<ankka::Secrets, HttpProblem> {
        request
            .context()
            .secrets()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has a secret store"))
    }

    fn keep(request: &Request, keep: KeepSecret) -> Result<Done, HttpProblem> {
        Self::secrets(request)?.put(&keep.name, &keep.value)?;
        Ok(Done)
    }

    fn read(request: &Request) -> Result<String, HttpProblem> {
        let name = request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))?;
        Self::secrets(request)?
            .get(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no secret '{name}'")))
    }

    fn remove(request: &Request) -> Result<Done, HttpProblem> {
        let name = request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))?;
        Self::secrets(request)?.delete(name)?;
        Ok(Done)
    }
}

impl Endpoint for ProviderCredentialsEndpoint {
    const ENDPOINT_ID: &'static str = "ProviderCredentialsEndpoint";
    const PREFIX: &'static str = "/credentials";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<ProviderCredentialsEndpoint> {
        Routes::new()
            .post("/", ProviderCredentialsEndpoint::keep)
            .get("/", ProviderCredentialsEndpoint::read)
            .delete("/", ProviderCredentialsEndpoint::remove)
    }
}
```

**Go**

```go
// CredentialsEndpoint is where a person gives the service a credential it must keep. The value
// goes to the secret store and nowhere else: not to an entity, so it reaches no journal, snapshot
// or view. The name is a query parameter because it may hold a slash.
var CredentialsEndpoint = ankka.NewEndpoint("/credentials", ankka.AllowAll(),
	ankka.Post("/", func(ctx context.Context, _ *ankka.Request, keep KeepSecret) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Put(keep.Name, keep.Value)
	}),
	ankka.Get("/", func(ctx context.Context, req *ankka.Request) (string, error) {
		name := req.Query.Get("name")
		value, found, err := ankka.Secrets(ctx).Get(name)
		if err == nil && !found {
			err = &ankka.HttpProblem{Status: 404, Message: fmt.Sprintf("no secret '%s'", name)}
		}
		return value, err
	}),
	ankka.Delete("/", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Delete(req.Query.Get("name"))
	}),
)
```

A workflow step that reads it when it needs it, and records only that it charged:

**Scala**

```scala
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
final class ChargeWorkflow(context: WorkflowContext) extends Workflow[ChargeState]:

  def emptyState: ChargeState = ChargeState("", 0, "not-started")

  def start(charge: Charge): Effect[Done] =
    effects
      .updateState(ChargeState(charge.provider, 0, "started"))
      .transitionTo(ChargeWorkflow.charge.withInput(charge))
      .thenReply(Done)

  def chargeStep(charge: Charge): StepEffect =
    val credential = context.secrets
      .get(s"provider/${charge.provider}")
      .getOrElse(throw IllegalStateException(s"no credential for ${charge.provider}"))
    // Calling the provider with `credential` goes here. The state records that it was charged, and
    // never the credential.
    stepEffects
      .updateState(currentState.copy(credentialLength = credential.length, status = "charged"))
      .thenEnd
```

**Java**

```java
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from
 * the secret store when it needs it, and records only that it charged.
 */
@ComponentId("charge")
public class ChargeWorkflow extends Workflow<ChargeWorkflow.ChargeState> {

  public record Charge(String provider, int amount) {}

  public record ChargeState(String provider, int credentialLength, String status) {}

  @Override
  protected ChargeState emptyState() {
    return new ChargeState("", 0, "not-started");
  }

  @Command("start")
  public Effect<Done> start(Charge charge) {
    return effects()
        .updateState(new ChargeState(charge.provider(), 0, "started"))
        .transitionTo(ChargeWorkflow::charge, charge)
        .thenDone();
  }

  @Step("charge")
  public StepEffect charge(Charge charge) {
    var credential =
        secrets()
            .get("provider/" + charge.provider())
            .orElseThrow(
                () -> new IllegalStateException("no credential for " + charge.provider()));
    // Calling the provider with the credential goes here. The state records that it charged,
    // and never the credential.
    return stepEffects()
        .updateState(new ChargeState(charge.provider(), credential.length(), "charged"))
        .thenEnd();
  }

  @Query("get")
  public ReadOnlyEffect<ChargeState> get() {
    return effects().reply(currentState());
  }
}
```

**Kotlin**

```kotlin
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
@ComponentId("charge")
class ChargeWorkflow : Workflow<ChargeState>() {
    override fun emptyState() = ChargeState("", 0, "not-started")

    @Command("start")
    fun start(charge: Charge): Effect<Done> = effects {
        updateState(ChargeState(charge.provider, 0, "started")).transitionTo(stepRef(ChargeWorkflow::chargeStep), charge).thenDone()
    }

    @Step("charge")
    fun chargeStep(charge: Charge): StepEffect {
        val credential =
            secrets().getOrNull("provider/${charge.provider}")
                ?: throw IllegalStateException("no credential for ${charge.provider}")
        // Calling the provider with `credential` goes here. The state records that it was charged, and
        // never the credential.
        return stepEffects { updateState(currentState().copy(credentialLength = credential.length, status = "charged")).thenEnd() }
    }

    @Query("get")
    fun get(): ReadOnlyEffect<ChargeState> = effects { reply(currentState()) }
}
```

**Python**

```python
class ChargeWorkflow(Workflow[ChargeState]):
    """Charges a customer through a payment provider. The step reads the provider's credential from
    the secret store when it needs it, and records only that it charged."""

    component_id = "charge"
    state_codec = json_codec(ChargeState, "charge-state")

    def empty_state(self) -> ChargeState:
        return ChargeState("", 0, "not-started")

    @command("start")
    def start(self, charge: Charge) -> WorkflowEffect[ChargeState, Done]:
        return self.effects.update_state(ChargeState(charge.provider, 0, "started")).then_transition_to("charge", charge).then_reply(lambda _: DONE)

    @step("charge")
    async def charge(self, charge: Charge) -> WorkflowStepEffect[ChargeState]:
        credential = await self.secrets.get(f"provider/{charge.provider}")
        if credential is None:
            raise RuntimeError(f"no credential for {charge.provider}")
        # Calling the provider with `credential` goes here. The state records that it was charged,
        # and never the credential.
        return self.step_effects.update_state(replace(self.state, credentialLength=len(credential), status="charged")).then_end()
```

**TypeScript**

```ts
export const Charge = s.record("Charge", { provider: s.string, amount: s.int })
export type Charge = Infer<typeof Charge>
export const ChargeState = s.record("ChargeState", { provider: s.string, credentialLength: s.int, status: s.string })
export type ChargeState = Infer<typeof ChargeState>

/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
export class ChargeWorkflow extends Workflow<ChargeState> {
  static readonly componentId = "charge"
  static readonly state = jsonCodec(ChargeState, "charge-state")

  static readonly handlers = {
    start: command("start", Charge, Done, (w: ChargeWorkflow, charge) =>
      w.effects
        .updateState({ provider: charge.provider, credentialLength: 0, status: "started" })
        .thenTransitionTo("charge", charge)
        .thenReply(() => done),
    ),
    status: query("status", ChargeState, (w: ChargeWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    charge: step("charge", Charge, (w: ChargeWorkflow, charge) => w.charge(charge)),
  }

  emptyState(): ChargeState {
    return { provider: "", credentialLength: 0, status: "not-started" }
  }

  async charge(charge: Charge) {
    const credential = await this.secrets.get(`provider/${charge.provider}`)
    if (credential === undefined) throw new Error(`no credential for ${charge.provider}`)
    // Calling the provider with `credential` goes here. The state records that it was charged, and
    // never the credential.
    return this.stepEffects.updateState({ ...this.state, credentialLength: credential.length, status: "charged" }).thenEnd()
  }
}
```

**Rust**

```rust
/// Charges a customer through a payment provider. The step reads the provider's credential from
/// the secret store when it needs it, and records only that it charged.
pub struct ChargeWorkflow;

impl ChargeWorkflow {
    fn start(_: &ChargeState, charge: Charge, _: &Context) -> WorkflowEffect<ChargeState, Done> {
        workflow::update_state(ChargeState {
            provider: charge.provider.clone(),
            credential_length: 0,
            status: "started".to_string(),
        })
        .transition_to_with("charge", charge)
        .then_reply_value(Done)
    }

    fn charge(state: &ChargeState, charge: Charge, ctx: &Context) -> StepEffect<ChargeState> {
        // A step has the store; the command handler above does not.
        let secrets = ctx.secrets().expect("a workflow step has a secret store");
        let credential = match secrets.get(&format!("provider/{}", charge.provider)) {
            Ok(Some(credential)) => credential,
            Ok(None) => {
                let why = format!("no credential for {}", charge.provider);
                return step_effects::fail(CommandError::new(ErrorCode::Internal, why));
            }
            Err(e) => return step_effects::fail(e),
        };
        // Calling the provider with `credential` goes here. The state records that it was
        // charged, and never the credential.
        step_effects::update_state(ChargeState {
            credential_length: credential.len(),
            status: "charged".to_string(),
            ..state.clone()
        })
        .then_end()
    }

    fn status(state: &ChargeState, _: (), _: &Context) -> ReadOnlyEffect<ChargeState> {
        effects::reply(state.clone())
    }
}

impl Workflow for ChargeWorkflow {
    type State = ChargeState;
    const COMPONENT_ID: &'static str = "charge";
    const STATE_MANIFEST: Option<&'static str> = Some("charge-state");

    fn empty_state(_: &str) -> ChargeState {
        ChargeState {
            provider: String::new(),
            credential_length: 0,
            status: "not-started".to_string(),
        }
    }

    fn handlers() -> WorkflowHandlers<ChargeWorkflow> {
        WorkflowHandlers::new()
            .command("start", ChargeWorkflow::start)
            .query("status", ChargeWorkflow::status)
    }

    fn steps() -> Steps<ChargeWorkflow> {
        Steps::new().step("charge", ChargeWorkflow::charge)
    }
}
```

**Go**

```go
// ChargeWorkflow charges a customer through a payment provider. The step reads the provider's
// credential from the secret store when it needs it, and records only that it charged.
var ChargeWorkflow = ankka.Workflow[ChargeState]("charge",
	func(string) ChargeState { return ChargeState{Status: "not-started"} },
	ankka.WorkflowCommand("start", func(_ context.Context, _ ChargeState, c Charge) (chargeStart, error) {
		return ankka.WorkflowUpdate[ChargeState, ankka.Done](ChargeState{Provider: c.Provider, Status: "started"}).
			ThenTransitionTo("charge", c).ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Step("charge", func(ctx context.Context, s ChargeState, c Charge) (chargeStep, error) {
		credential, found, err := ankka.Secrets(ctx).Get("provider/" + c.Provider)
		if err != nil {
			return chargeStep{}, err
		}
		if !found {
			return chargeStep{}, fmt.Errorf("no credential for %s", c.Provider)
		}
		// Calling the provider with the credential goes here. The state records that it was
		// charged, and never the credential.
		s.CredentialLength, s.Status = len(credential), "charged"
		return ankka.StepUpdate(s).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ChargeState](ankka.Manifest("charge-state"))),
)
```

In Java and Kotlin the store is `secrets()`, on an HTTP, gRPC or MCP endpoint, a consumer, a workflow
and a timed action's context (`context().secrets()`); an entity and a view have no such method, and a
workflow's command handler that calls it is refused, as in Scala.

In Python an endpoint, a consumer, a timed action, an agent and a workflow step reach the store as
`self.secrets` and every call is awaited; in TypeScript it is `ep.secrets` (or `this.secrets` in a class
handler). In Rust, `ctx.secrets()` answers the store, or `None` in an entity, a view and a workflow's
command handler. In Go, `ankka.Secrets(ctx)` is the store of the handler whose context it is given; an
entity, a view or a workflow's command handler has a context too, so the call compiles there, and the
store refuses every call from one, naming which.

## Every read is recorded

Each read, keep and removal leaves a record — the secret's name, the service, the time, the trace, and the
component that asked when the platform ran it — and never the value. The record is written before the
value is returned, and a read whose record cannot be written fails as `Unavailable`: a service's reads of
its secrets depend on the platform's record being reachable, as they depend on the store. On your machine
the record is a line in the service's log. An owner reads the records of a deployed project with
`ankka projects secret-reads list`; see [Secrets on the platform](../platform/secrets.md#the-record-of-reads).

Nothing is cached: every `get` is a read of the store, and a record. A service that reads a credential for
every request it makes reads the store, and records, that often; on Secret Manager that counts against a
per-project quota. Read once where the value cannot change underneath you, and every time where it can.

## The rules

- **A name** is 1 to 253 characters, each a letter, a digit, `.`, `_`, `-` or `/`. A slash is a separator
  with no meaning to the store, so `provider/acme` is one name. Any other name is refused naming the rule.
- **A value** is text, not empty, and at most 65,536 bytes as UTF-8. A secret is a credential, not a
  document; binary material is yours to encode, as base64 for example.
- **Keeping again** replaces the value; the store holds one value per name. When two instances keep the
  same name at once, the last write wins.

## When it fails

Every failure is a `CommandError` with a code:

| Code | When |
|---|---|
| `BadRequest` | a name or a value breaks its rule; a workflow's command handler called the store |
| `Internal` | the service has no secret key, or not the one the value was kept with; on Secret Manager, the service has not been given access to its secrets |
| `Unavailable` | the database or Secret Manager cannot be reached, or is over its quota; or the record of the read was not acknowledged |

A service **with no secret key** starts, and keeping or reading fails naming `ANKKA_SECRET_KEY`; removing
needs no key. A key that is set but is not the base64 of 32 bytes stops the service starting, naming the
variable. A value kept with one key and read with another fails saying the key is not the one the value
was kept with — never as a missing value. That is what changing a service's key without re-encrypting
does, and why the key belongs to the service, not to its data: a database restored under a different key
has every secret unreadable.

On the platform each deployed service is given a key of its own, made once and kept when the service is
deleted; on your machine you supply one. Both are in [Secrets on the platform](../platform/secrets.md).

## Testing

`AnkkaTestKit` gives the service it starts a fresh key, so the store works with no setup, and
`testKit.secrets` is that service's store. `restartService(secretKey = None)` restarts it with none, and
another key restarts it as a careless rotation would. A dump of the test database is how to prove a value
reached no table but the store's.

A test that must show its service on the Secret Manager backend starts the kit on a
`FakeSecretManager`: Secret Manager played on loopback, with Google's answers, which refuses what Google
Cloud's access would refuse. Two kits on one fake are two services, each refused the other's secrets.

**Scala**

```scala
val kit = AnkkaTestKit.start(
  Seq.empty,
  secretBackend = SecretBackendChoice.secretManager(theFake, "spinvibe", service)
)
```

**Kotlin**

```kotlin
// The kit's `secrets()` is the service's store: what one keeps, the other reads.
assertEquals(204, send("POST", "/credentials", """{"name":"provider/stripe","value":"sk-test-1"}""").status)
assertEquals("sk-test-1", secrets().getOrNull("provider/stripe"))

componentClient().forWorkflow("ch-1").call(ChargeWorkflow::start).invoke(Charge("stripe", 1200))
eventually(Duration.ofSeconds(10)) {
    assertEquals(ChargeState("stripe", 9, "charged"), componentClient().forWorkflow("ch-1").call(ChargeWorkflow::get).invoke())
}
```

`fake.latestValue(id)` and `fake.versionsOf(id)` show what it holds, `fake.unreachable(true)` and
`fake.failNext(503)` make it fail, and `fake.access.withhold(identity)` takes a service's access away.
`testKit.recordedReads` is every record the service made, which a test reads to show what was recorded
and that no value was. Nothing reaches a network or needs a credential.

For a unit test, `InMemorySecretStore` is a store in memory that applies the runtime's rules, so a name or
a value a running service would refuse is refused there too. `ConsumerTestKit` gives the consumer one:

**Scala**

```scala
val kit = ConsumerTestKit.of(WarehouseCredentialKeeper)
kit.onMessage(StockEvent("sku-1", 1, "w9"), "sku-1"): Unit
assertEquals(kit.secrets.get("warehouse/w9"), Some("token-w9"))
```

**Python**

```python
keeper = WarehouseCredentialKeeper()
keeper.secrets = InMemorySecrets()
asyncio.run(keeper.on_message(StockEvent("sku-1", 1, "w9")))
assert asyncio.run(keeper.secrets.get("warehouse/w9")) == "token-w9"
```

**TypeScript**

```ts
test("the step reads the credential and records only that it charged", async () => {
  await store.put("provider/acme", "sk-live-1234")
  class Charging extends ChargeWorkflow {
    override get secrets(): Secrets {
      return store
    }
  }
  const kit = WorkflowTestKit.of(Charging, "ch-1")
  await kit.call(ChargeWorkflow.handlers.start, { provider: "acme", amount: 10 })
  const progress = await kit.runUntilEnd()
  assert.equal(progress.ended, true)
  assert.deepEqual(kit.state, { provider: "acme", credentialLength: 12, status: "charged" })
  assert.equal(JSON.stringify(kit.state).includes("sk-live"), false)
})
```

**Rust**

```rust
#[test]
fn the_keeper_keeps_a_warehouses_credential_the_first_time_it_sees_it() {
    let kit = ConsumerTestKit::<WarehouseCredentialKeeper>::new();
    kit.on_message(
        "sku-1",
        StockEvent {
            sku: "sku-1".into(),
            delta: 1,
            warehouse: "w9".into(),
        },
    );
    assert_eq!(
        store().get("warehouse/w9"),
        Ok(Some("token-w9".to_string()))
    );
}
```

The Python, TypeScript and Rust SDKs have the same double (`InMemorySecrets`, and in Rust the native host
a unit test runs against), and their integration test kits put a generated key on the runtime they start. The Go SDK
has no such double, so a Go component that keeps a secret is tested with its integration test kit, which
also puts a generated key on the runtime it starts.

## What it is not

The secret store is not shared between services: another service asks for what it needs over HTTP, as
for anything else. It keeps no history a service can read: on Secret Manager a few versions are kept and
the rest destroyed. The secret key cannot be rotated in place. These are in
[Limitations](../reference/limitations.md).
