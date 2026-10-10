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

## The backend is the installation's; every read is recorded

Feature 038. `SecretStores.build` (`runtime/secrets`) is the one place a store is chosen, from
`ANKKA_SECRET_BACKEND`: `DatabaseSecretStore` as above, or `SecretManagerStore`, which speaks Secret Manager's
REST API over the JDK client (`SecretManager`, no Google library: the sidecar image carries the store, and
gax/grpc/protobuf 4 would clash with ScalaPB) with the pod's Workload Identity token from the metadata server
(`AccessTokens`). Either is wrapped in `RecordingSecretStore`: the backend answers, the record is written,
and only then is the value returned; a record not acknowledged within `ankka.secrets.record-timeout` is
`Unavailable` and the value is dropped. The record goes to the control plane (`POST /secret-reads`, admitted
by any service's certificate through `CallerMatcher.AnyService`, held to the caller's own project and name) at
the address the **Kubernetes overlay** carries (`ankka.secrets.records-url`) — a runtime default, so the
operator renders nothing and no pod template changed; locally `LocalRecorder` logs it and the test kit reads
it (`recordedReads`). The control plane keeps records in their own database (`components/secret-reads`,
`SecretRecords`, `PostgresReadRecordStore`, which makes its table as the owning role), lists them to owners and
sweeps them by retention. `DerivedIds` is the one id derivation: `s_<project>_<service>_<enc(name)>` and
`p_<project>_<enc(secret)>__<enc(entry)>`, `_`→`_u`, `.`→`_p`, `/`→`_s` — `_` because project and service
names are DNS labels, so a prefix can never begin another's, and access is conditioned on the prefix.
`FakeSecretManager` (testkit) plays Google on loopback; the bearer token is the identity
(`fake:<project>/<service>`, `fake:controlplane`, `fake:provider`). A move (`ANKKA_SECRET_MOVE`) runs at start
and gates readiness; its ledger, `ankka_secret_moves`, is in the service's own database, and its `*removed*`
mark makes a Postgres-backend start refuse (`StartRefusal`). On Secret Manager with a cloud provider the
operator asks 044's `CloudResource`s for each service's access (`SecretAccess`: `identity`, then `secret-access`
with `own` its prefix and `read` its project's) and one `secret-sync` per project secret (`ProjectSecretSync`,
`<project>.secret-sync.<secret>`, at the secret's `entryGeneration`), and holds the Deployment until both
are answered; the identity's answer also binds the ServiceAccount when no bucket asked for it.
`SecretsCloudClusterSuite` (k3s) runs both against the scripted provider. `SecretsClusterSuite` (k3s)
proves the record's path from a pod — the overlay's address, mutual TLS, both network policies — through the
shopping cart sample's `/secrets` route, which admits only the sample itself and never answers a value.

## Traps

- **A Secret Manager annotation key allows no `/`.** Kubernetes' `ankka.thinkmorestupidless.com/name` form is
  refused with `INVALID_ARGUMENT`; the platform's keys are `ankka-name`, `ankka-project` and so on, and the
  fake refuses what Google would.
- **An IAM condition on a name cannot limit a create.** The secret does not exist when the create is
  authorised, so a service's create is unconditioned and everything else is conditioned on its prefix. A
  squat is harmless — the creator can do nothing else with it, and the owner's `put` adds a version to it —
  and `GrantsSuite` holds that. A condition names the project by **number**, not id.
- **`get` is never cached, on either backend.** It is a read and a record every time; on Secret Manager that
  counts against a per-project quota. Do not add a cache: a copy is a second place a plaintext lives, and the
  record would stop being one per read.
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
