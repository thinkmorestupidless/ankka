---
paths:
  - "modules/core/**"
  - "modules/sdk/**"
  - "modules/runtime/**"
  - "controlplane/**"
  - "controlplane-api/**"
  - "operator/**"
  - "sidecar/**"
  - "sdks/**"
---

# Secrets: service secrets, the secret key and project secrets

## Secrets: a store a service keeps, a key it is given, and project secrets

Feature 023. A **service secret** is a named text value a service keeps and reads while it runs, through
a `SecretStore` (`sdk`, implemented by `runtime`'s `DatabaseSecretStore`): one table, `ankka_secrets`,
in the service's own database, each value AES-256-GCM under the service's **secret key**, with the name
as associated data and a version byte in front (`SecretCipher`). It is not an entity, a view or anything
a projection reads, so no journal, snapshot or view row ever holds a value. It is offered to endpoints
(`EndpointClients.secrets`), workflow steps, consumers, timed actions and agents — on their contexts —
and **not** to `EntityContext` or `ViewComponentContext`, so `context.secrets` in an entity does not
compile. A workflow's commands and steps share one context, so `StepScope` (a thread-local the engine
sets around a step's body) is what refuses a command handler. A process or a module reaches the store
through `GetSecret`/`PutSecret`/`DeleteSecret` on the protocol's `Client` service (1.6) and the module
imports of the same names, all behind `ClientLogic`; the sidecar cannot tell which component called, so
"not in an entity" is each SDK's rule, enforced by its own types and contexts.

The key is `ANKKA_SECRET_KEY` (`ankka.secrets.key`), base64 of 32 bytes. Unset: the service runs and
`put`/`get` fail naming it (`delete` needs none). Malformed: the service does not start. On the platform
the operator renders `Action.EnsureSecretKey`, which **carries no key** (actions are printed), and
`Fabric8Executor` makes the bytes when it **creates** `<service>-secret-key` — a 409 is success, the
Secret is never read, and it has no owner reference, so a service deleted and re-applied reads what it
kept. A descriptor that sets the variable gets no rendered key, as `ANKKA_DB_*` gets no database. The
variable reaches only the platform's container; a module's `config` answers it absent.

A **project secret** is a Kubernetes Secret a member sets through the control plane (`PUT
/projects/{id}/secrets/{name}`, `ankka projects secrets set|unset|list`), entry by entry, which a
descriptor takes by the `secretKeyRef` that always worked. Written by a **merge patch**, created when the
patch finds nothing, never read; the `Project` entity records names only (`ProjectSecretEntriesSet`,
`ProjectSecretEntryRemoved`), and the listing is an entity query, exact and immediate. Names of the forms
the platform uses for its own Secrets in a namespace are refused (`ProjectSecrets.problems`), held to the
operator's naming by `ReservedSecretNamesSuite`.

## Traps

- **Server-side apply cannot merge a Secret's entries.** An apply replaces everything that field manager
  applied before, so applying one entry of a project secret removes the others, and the control plane,
  which holds no `get`, cannot read them back to send again. Project secrets are a JSON merge patch
  (`stringData` to add, `data: {key: null}` to remove), with a `create` when the patch is a 404. Both are
  the `patch` and `create` verbs, so the grant is unchanged. Note what `patch` answers: the whole object,
  every entry's value included — the code discards it, and a grant of `patch` is in effect a read of what
  it patches.
- **A project secret's name can be a platform Secret's.** The control plane writes by a member's name
  into the namespace that holds `<service>-db`, the TLS Secrets and `<service>-secret-key`, and cannot
  look first; a project secret called `payments-secret-key` would replace that service's key and make
  everything it kept unreadable. The forms are refused; a new platform Secret in a project namespace
  needs its form added to `ProjectSecrets` and its naming function to `ReservedSecretNamesSuite`.
- **A descriptor's `secretKeyRef` could name a Secret the platform issues** — another service's
  certificate, or the project database's authority — and hand its key to a process. The descriptor's
  rules now refuse it for every hosting (`ServiceSpec.isPlatformSecret`), and `ProxyEnvironmentSuite`
  holds the list to every Secret name `Rendering` asks cert-manager to write, so a new certificate cannot
  be added without the rule.
