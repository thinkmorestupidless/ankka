# Contract: Deploy tokens

**Feature**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: R2–R6

## The credential

```text
ankka_<id>_<secret>
ankka_3f9a1c2e7b4d8f01_9c2e...(64 hex)...a1f0
```

- `id`: 16 lowercase hex characters. Public; it is the entity id and the suffix of the subject.
- `secret`: 64 lowercase hex characters, 256 bits from `SecureRandom`. Shown once.
- No dots, no `+`, `/`, `=` or `-`: safe unquoted in a shell, in YAML and in `curl -d`.
- Presented as `Authorization: Bearer ankka_<id>_<secret>`, exactly as an OIDC token is. The control
  plane classifies by the `ankka_` prefix and never guesses.

## Routes

All under `OrganizationEndpoint`'s prefix, all requiring the **owner** role in the organization
(`authz.requireOwner`, `write = true`), so a deploy token — always a member — is refused `403` on
every one of them (FR-010). A non-member sees `404`, as for every organization route.

| Method | Path | Body | Reply |
|---|---|---|---|
| `POST` | `/organizations/{organizationId}/tokens` | `CreateDeployToken` | `200` `DeployTokenCreated` — the only response that ever carries the secret |
| `GET` | `/organizations/{organizationId}/tokens` | | `200` `Vector[DeployTokenSummary]`, from the view, newest first |
| `DELETE` | `/organizations/{organizationId}/tokens/{tokenId}` | | `204`; `404` if unknown or already revoked |

A disabled organization refuses `POST` and `DELETE` with `409`, as it refuses every write.

### Wire types (`controlplane-api`, codecs in `Wire`)

```scala
final case class CreateDeployToken(
    label: String,
    /** Seconds; absent means 90 days; `0` means never. The endpoint turns it into an absolute instant. */
    expiresIn: Option[Long] = None
)

final case class DeployTokenCreated(
    id: String,
    label: String,
    /** `ankka_<id>_<secret>`. Shown here and nowhere else, ever. */
    secret: String,
    subject: String,                // "token:<id>"
    expiresAt: Option[Instant]
)

final case class DeployTokenSummary(
    id: String,
    label: String,
    subject: String,
    createdBy: Option[String],      // the creator's display, never a key
    createdAt: Instant,
    expiresAt: Option[Instant],     // None: never expires
    lastUsed: Option[LocalDate]     // the date, not the instant (clarification Q3)
)
```

`LocalDate` encodes as `"2026-09-25"` through an explicit codec in a companion, so the CLI and the
control plane cannot disagree.

### The create sequence

1. `requireOwner`.
2. Validate: label non-empty, ≤ 100 characters, one line; `expiresIn` absent, `0`, or `1..31536000`.
3. Generate id and secret (`DeployTokens.mint`), digest the secret.
4. `DeployTokenEntity.create(...)` — the token now exists and authorizes nothing.
5. `OrganizationEntity.addMember(AddMember("token:<id>", Role.Member, display = Some(label)))`.
6. Reply with `DeployTokenCreated` (`200`, this platform's convention — every route answers
   200 or 204, and `ToResponse` has no way to say 201). The secret is not logged, not stored, and
   is in no event.

If step 5 fails the reply is the failure, and the token from step 4 is listed and revocable; it
cannot be used. A retry creates a new token.

### The revoke sequence

1. `requireOwner`.
2. `DeployTokenEntity.revoke` — the index on this node evicts immediately; other nodes on delivery.
3. `OrganizationEntity.removeMember("token:<id>")` — `404` from here means it was never added, which is
   the step-5 failure above; the reply is still `204`.

## What the ACL decides

`ControlPlaneAcl.composite(index, oidc)` reads the bearer once.

| presented | decision | HTTP |
|---|---|---|
| does not start with `ankka_` | the existing OIDC decision, unchanged | as today |
| malformed after the prefix | `Unauthenticated(error="invalid_token", error_description="not a deploy token")` | `401` |
| id unknown, or digest mismatch | `Unauthenticated(error="invalid_token", error_description="deploy token not recognised")` | `401` |
| known, `expiresAt` before now | `Unauthenticated(error="invalid_token", error_description="deploy token expired on <date>")` | `401` |
| index not caught up | `Unavailable("deploy tokens cannot be verified yet on this node")` | `503`, `Retry-After: 5` |
| known and valid | `Allow(Principal("token:<id>", name = Some(label), claims = Map("kind" -> "deploy-token", "organization" -> orgId)))`, then `index.touch(id)` | proceeds |

The digest comparison is `MessageDigest.isEqual`. No path performs I/O. A `401` never says which of
"unknown" and "wrong secret" it was beyond the one message.

## What the rest of the control plane sees

- `Authorization.require` finds `token:<id>` in `Organization.members` with `Role.Member` and needs
  nothing else. A token presented against another organization's resources gets that organization's
  `404`.
- `Attribution` records `Actor("token:<id>", Some(label), administrative = false)`; `services history`
  shows `label (token:<id>)`.
- `GET /auth/whoami` for a token returns `subject = "token:<id>"`, `name = label`, no email, and the
  one organization.
- `GET /organizations/{id}/members` lists it as a member whose subject starts with `token:`.
- `InvitationClaim` never runs for it (no verified email).

## CLI

```text
ankka organizations tokens create <organization> --label <text> [--expires-in <duration> | --never-expires]
ankka organizations tokens list <organization>
ankka organizations tokens revoke <organization> <token-id>
```

`create` prints, in table format:

```text
Deploy token 'github-deploy' created for acme. This is the only time the secret is shown.

  ankka_3f9a1c2e7b4d8f01_9c2e…a1f0

Expires 2026-12-24. Store it as a secret named ANKKA_TOKEN.
```

and in JSON format the `DeployTokenCreated` document. `list` prints `ID  LABEL  CREATED  BY  EXPIRES
LAST USED` (`never` for a non-expiring token, `-` for never used); the JSON form is the summaries.
`revoke` prints nothing on success. `--expires-in` accepts `30d`, `12h`, `90d` (the default when
neither flag is given); anything over `365d` is refused before the request. The secret is never
printed by any command but `create`, and `create` never prints it twice.

## Tests that pin this

- `TenancyEntitySuite`: the token entity's folds and refusals; `recordUse` with a date not later is
  a no-op; a revoked id cannot be recreated.
- `DeployTokenIndexSuite`: fold of the three events; `touch` and the once-a-day rule; `caughtUp`
  gates `readiness`.
- `ControlPlaneHttpSuite`: the lifecycle over HTTP (create as owner → `whoami` as the token → a
  write as the token attributed to it → `POST …/tokens` as the token is `403` → revoke → `401` within
  five seconds and thereafter); expiry with a clock the suite advances; the secret absent from `GET`
  and from every JSON body but the create's; another organization is `404`.
- `AuthorizationMatrixSuite`: the token row of the matrix.
- `VerificationOverheadBenchmark`: a third run with a deploy token (SC-003).
- `ControlPlaneRoutesReferenceSuite`, `CliReferenceSuite`: the tables and the hand-written sections.
