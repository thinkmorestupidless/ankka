# Data Model: Organization Quotas

## Wire types (`controlplane-api`, `descriptors.scala`)

```scala
/** Up to three limits. A limit absent (or null) is unlimited. Zero is a limit. */
final case class Quota(
    projects: Option[Int] = None,
    services: Option[Int] = None,
    instances: Option[Int] = None
)
object Quota:
  /** Empty when valid: every named limit ≥ 0, and at least one named. */
  def problems(quota: Quota): Seq[String]

/** What the organization records as existing. */
final case class Usage(projects: Int, services: Int, instances: Int)   // Usage.zero; no field defaults, so a written usage is whole

final case class OrganizationDetail(
    id: String, name: String, disabled: Boolean = false,
    quota: Option[Quota] = None, usage: Usage = Usage.zero
)
final case class OrganizationSummary(
    id: String, name: String, projects: Int, disabled: Boolean = false, role: Option[Role] = None,
    quota: Option[Quota] = None, usage: Usage = Usage.zero
)
```

`Wire` gains `JsonValueCodec[Quota]` (the request body). Both summaries' codecs derive the new
fields; a pre-feature client ignores them, a pre-feature server's JSON decodes with the defaults.
The shared codec config omits a field at its default, so `usage` is absent from the JSON when
everything is zero and `quota` when none is set; a written `usage` always has all three numbers.

## Domain (`controlplane`, `model.scala`)

```scala
/** The organization's exact record of what exists, folded from its own events. */
final case class UsageRecord(
    projects: Set[String] = Set.empty,        // project ids
    services: Map[String, Int] = Map.empty    // "projectId/name" → minInstances
):
  def usage: Usage = Usage(projects.size, services.size, services.values.sum)
  def fold(event: OrganizationEvent): UsageRecord   // the five usage events; identity otherwise

final case class Organization(
    id, name, deleted, members, invitations, disabled,   // as today
    quota: Option[Quota] = None,
    record: UsageRecord = UsageRecord()
):
  def usage: Usage = record.usage
  // onDeleted also clears quota and record: usage is gone with the organization.
```

`OrganizationRow` gains `quota: Option[Quota] = None` and `record: UsageRecord = UsageRecord()`;
`detail` becomes `OrganizationDetail(id, name, disabled, quota, record.usage)`.

## Events (`events.scala`, new cases of `OrganizationEvent`)

| Event | Fields | Folded into |
|---|---|---|
| `QuotaSet` | `quota: Quota, actor, at` | `quota = Some(quota)` |
| `QuotaCleared` | `actor, at` | `quota = None` |
| `ProjectReserved` | `projectId, actor, at` | `record.projects + id` |
| `ProjectReleased` | `projectId, actor, at` | `record.projects - id` |
| `ServiceReserved` | `key, instances, actor, at` | `record.services + (key → instances)` |
| `ServiceReleased` | `key, actor, at` | `record.services - key` |
| `UsageReconciled` | `projects: Set[String], services: Map[String, Int], actor, at` | `record = UsageRecord(projects, services)` — the *merged* record, computed by the handler |

`actor`/`at` default to `None` as every event's do. Nothing existing changes shape.

## Commands (`events.scala`, request types)

```scala
final case class SetQuota(quota: Quota, projects: Set[String], services: Map[String, Int])
final case class ReserveService(key: String, instances: Int)
final case class RecordService(key: String, instances: Option[Int])   // unchecked; None removes
```

## Entity handlers (`OrganizationEntity`)

| Wire name | Handler | Reply | Refuses |
|---|---|---|---|
| `set-quota` | `setQuota(SetQuota)` | `Done` | not found; `Quota.problems` (400). Allowed while disabled. Merges the snapshot into the record (union of projects; snapshot's count per service it names; record's other services kept) and persists `UsageReconciled` (if that changed anything) then `QuotaSet` |
| `clear-quota` | `clearQuota` | `Done` | not found. No quota → `Done`, no event |
| `reserve-project` | `reserveProject(projectId)` | `Boolean` (newly reserved) | not found; `projects.size >= quota.projects` → 409 "organization 'acme' has reached its quota of 2 projects (2 in use)" |
| `release-project` | `releaseProject(projectId)` | `Done` | not found. Unknown id → `Done`, no event |
| `reserve-service` | `reserveService(ReserveService)` | `Option[Int]` (previous) | not found; new key and `services.size >= quota.services` → 409 "… quota of 3 services (3 in use)"; increase and `total - previous + instances > quota.instances` → 409 "applying 'checkout/cart' with 4 instances would take organization 'acme' to 6 instances, over its quota of 4 (3 in use)". Same count → reply, no event |
| `record-service` | `recordService(RecordService)` | `Done` | not found. Unchecked: `Some(n)` records `n` (no event if equal), `None` releases (no event if absent) |

`get` answers `OrganizationDetail` with `quota` and `usage`.

## State transitions of usage

```
create project:   reserve-project ──ok──▶ ProjectEntity.create ──ok──▶ done
                        │ 409                     │ fail
                        ▼                         ▼
                     refused          release-project (if newly reserved), rethrow
delete project:   ProjectEntity.delete ──ok──▶ release-project
apply service:    [descriptor pre-checks] ──▶ reserve-service ──ok──▶ ServiceEntity.apply ──ok──▶ done
                                                  │ 409                     │ fail
                                                  ▼                         ▼
                                               refused        record-service(previous), rethrow
delete service:   ServiceEntity.delete ──ok──▶ record-service(None)
quota set:        snapshot(views + desiredState) ──▶ set-quota (merge into the record + set)
```
