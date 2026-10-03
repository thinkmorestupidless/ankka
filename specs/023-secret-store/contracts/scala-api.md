# Contract: the secret store in Scala

What a service written in Scala sees. Decisions are in [research.md](../research.md) (R1, R2, R4,
R6, R7, R10).

## The trait (`sdk`)

```scala
trait SecretStore:
  /** Keep `value` under `name`, replacing what was there. */
  def put(name: String, value: String): Unit
  /** The value kept under `name`, or `None` when there is none. Never an empty string. */
  def get(name: String): Option[String]
  /** Remove what is kept under `name`. Removing nothing is not an error. */
  def delete(name: String): Unit
```

All three block the calling thread until the database has answered. All three throw
`CommandError`:

| When | Code | Message names |
|---|---|---|
| `name` breaks the rule (`put`, `get`, `delete`) | `BadRequest` | the rule for names |
| `value` is empty or over 65,536 UTF-8 bytes (`put`) | `BadRequest` | the rule, with the limit |
| no secret key (`put`, `get`) | `Internal` | `ANKKA_SECRET_KEY` |
| the key is not the one the secret was kept with (`get`) | `Internal` | that, and `name` |
| a workflow's store used outside a step | `BadRequest` | "a step" |
| the database cannot be reached | `Unavailable` | the secret store |

## The rules (`sdk`, `SecretRules`)

- **Name**: 1 to 253 characters, each a letter, a digit, `.`, `_`, `-` or `/`.
- **Value**: text, not empty, at most 65,536 bytes as UTF-8.

`protocol/fixtures/secrets/rules.json` lists names and values with the verdict for each;
`SecretRulesSuite` reads it.

## Who has it

| Component | Through | Has a store |
|---|---|---|
| HTTP endpoint | `EndpointClients.secrets` | yes |
| Workflow | `WorkflowContext.secrets` | yes, in a step; refused in a command handler |
| Consumer (and a graph consumer) | `ConsumerContext.secrets` | yes |
| Timed action | `TimedActionContext.secrets` | yes |
| Agent | `AgentContext.secrets` | yes |
| Autonomous agent | `AutonomousAgentContext.secrets` | yes |
| Event sourced entity, key value entity | `EntityContext` | **no member**: does not compile |
| View | `ViewComponentContext` | **no member**: does not compile |

`AnkkaService.secrets` is the running service's store, for a `main` that needs it and for tests.

## Configuration

| Key | Variable | Default | Meaning |
|---|---|---|---|
| `ankka.secrets.key` | `ANKKA_SECRET_KEY` | empty | the secret key: standard base64 of 32 bytes. Empty: the service starts and `put`/`get` fail naming the variable. Set and malformed: the service does not start |

## Test kits (`testkit`)

- `AnkkaTestKit.start(…, secretKey: Option[String] = <generated per kit>)`. `None` starts the
  service with no key.
- `AnkkaTestKit.restartService(secretKey: Option[String] = <the kit's>)`. Another key is the
  wrong-key case.
- `AnkkaTestKit.current.secrets: SecretStore`.
- `InMemorySecretStore()`: a `SecretStore` for unit tests, applying `SecretRules`. `ConsumerTestKit`
  builds its context with one; `kit.secrets` reads it.

## What a test must show

Each scenario of `features/secrets/secret-store.feature` is a case of `SecretStoreSuite`
(`testkit`), which fails without the feature. Three are worth spelling out because a weaker check
would pass:

- **The dump.** Read every row of every table in the database as text — not a list of tables the
  test knows — and assert the plaintext occurs in none; then assert `ankka_secrets` has exactly one
  row for the name. A list of known tables passes when a new table leaks.
- **The wrong key.** Assert the failure's message says the key is wrong, and assert the call did
  not return `None`.
- **No store in an entity or a view.** `compileErrors("context.secrets")` against an
  `EntityContext` and a `ViewComponentContext`, with the same expression compiling against a
  `ConsumerContext` beside it, so the check is not passing on a typo.
