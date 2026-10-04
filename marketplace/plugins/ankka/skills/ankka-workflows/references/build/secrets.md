# Secrets a service keeps

> Keep a credential a person gives your service, such as a payment provider's key, in the service's secret store — encrypted in its own database, never in a journal, a snapshot or a view — and read it back by name.

Source: https://docs.ankka.cloud/build/secrets/
A service sometimes has to hold a value it must never record: a payment provider's API key that an
operator enters through the service's own backoffice, a token a partner issued for one merchant. Kept as
an entity's state, that value would be in the journal, in every snapshot, in every view built from them,
and in every backup of any of those, readable by anything that can read the database.

The **secret store** is where such a value goes. It is a table in the service's own database that holds
each value encrypted with the service's **secret key**, and it is apart from everything the service's
components know: it is not an entity, not a view, and nothing a projection reads. A dump of every
table holds the value in one row, encrypted, and nowhere else.

A value kept here is a **service secret**: a name and a text value, kept and read by the service while it
runs. A value a member sets for a project before a service starts, which a descriptor's variable takes,
is a different thing — a project secret, described in
[Secrets on the platform](../platform/secrets.md).

## Who has the store

The store is offered to endpoints, workflow steps, consumers, timed actions and agents. It is **not**
offered to an entity or a view: their contexts have no secret store at all, so in Scala reaching for one
does not compile, and in the other SDKs the attribute does not exist. A read from the store is a blocking
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
virtual threads (Scala) or call through the runtime beside them (Python, TypeScript, Rust), so the wait
holds nothing else up.

An endpoint through which an operator enters a provider's credential, in Scala:

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

A workflow step that reads it when it needs it, and records only that it charged:

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

The same three operations in Python, where an endpoint, a consumer, a timed action, an agent and a
workflow step reach the store as `self.secrets` and every call is awaited:

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

In TypeScript, as `ep.secrets` (or `this.secrets` in a class handler):

```typescript
keepSecret: post("/secrets", s.string, Done, async (ep: ConformanceEndpoint, req, value) => {
  await ep.secrets.put(req.query.get("name") ?? "", value)
  return done
}),
readSecret: get("/secrets", s.string, async (ep: ConformanceEndpoint, req) => {
  const name = req.query.get("name") ?? ""
  const value = await ep.secrets.get(name)
  if (value === undefined) throw new HttpProblem(404, `no secret '${name}'`)
  return value
}),
removeSecret: del("/secrets", Done, async (ep: ConformanceEndpoint, req) => {
  await ep.secrets.delete(req.query.get("name") ?? "")
  return done
}),
```

In Rust, `ctx.secrets()` answers the store, or `None` in an entity, a view and a workflow's command
handler:

```rust
fn secrets(request: &Request) -> Result<ankka::Secrets, HttpProblem> {
    request
        .context()
        .secrets()
        .ok_or_else(|| HttpProblem::new(500, "an endpoint has a secret store"))
}

fn keep_secret(request: &Request, value: String) -> Result<Done, HttpProblem> {
    Self::secrets(request)?.put(request.query("name").unwrap_or_default(), &value)?;
    Ok(Done)
}

fn read_secret(request: &Request) -> Result<String, HttpProblem> {
    let name = request.query("name").unwrap_or_default();
    Self::secrets(request)?
        .get(name)?
        .ok_or_else(|| HttpProblem::new(404, format!("no secret '{name}'")))
}

fn remove_secret(request: &Request) -> Result<Done, HttpProblem> {
    Self::secrets(request)?.delete(request.query("name").unwrap_or_default())?;
    Ok(Done)
}
```

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
| `Internal` | the service has no secret key; or the key is not the one the value was kept with |
| `Unavailable` | the database cannot be reached |

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

For a unit test, `InMemorySecretStore` is a store in memory that applies the runtime's rules, so a name or
a value a running service would refuse is refused there too. `ConsumerTestKit` gives the consumer one:

```scala
val kit = ConsumerTestKit.of(WarehouseCredentialKeeper)
kit.onMessage(StockEvent("sku-1", 1, "w9"), "sku-1"): Unit
assertEquals(kit.secrets.get("warehouse/w9"), Some("token-w9"))
```

The Python, TypeScript and Rust SDKs have the same double (`InMemorySecrets`, and in Rust the native host
a unit test runs against), and their integration test kits put a generated key on the runtime they start.

## What it is not

The secret store has no versions, no history and no audit of reads. It is not shared between services:
another service asks for what it needs over HTTP, as for anything else. The secret key cannot be rotated
in place. These are in [Limitations](../reference/limitations.md).
