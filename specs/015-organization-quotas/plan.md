# Implementation Plan: Organization Quotas

**Branch**: `015-organization-quotas` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-organization-quotas/spec.md`

## Summary

A platform administrator sets, changes and clears a quota on an organization — at most so many
projects, services and instances — and the control plane refuses, at the door, a project create or
a service apply that would exceed it. Usage is an exact record kept by the organization entity:
the endpoints *reserve* capacity on the organization before creating a project or applying a
service, and give it back when the second step fails or the thing is deleted. The quota and the
usage ride on `OrganizationDetail` and `OrganizationSummary`, so members, the CLI and the hosted
product (through the published wire library) all read them the same way.

Technically: two wire types (`Quota`, `Usage`) in `controlplane-api`; a `UsageRecord` fold in the
domain shared by the entity state and the listing row so the two cannot disagree; seven new
organization events; six new entity commands; a reserve-first / release-on-failure sequence in
`ProjectEndpoint` and `ServiceEndpoint`; two admin-only routes; two CLI commands; the docs and the
two generated reference pages.

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (control plane, wire library, CLI)

**Primary Dependencies**: no new dependency. jsoniter (existing codecs), Pekko via the ankka
runtime, decline for the CLI.

**Storage**: Postgres via the existing journal — **no DDL change**. New event and state fields
carry defaults, so pre-feature journals and snapshots replay (`EventCompatibilitySuite` pins it).

**Testing**: `TenancyEntitySuite` (entity, `EventSourcedTestKit`, milliseconds); a new
`QuotaSuite` over real HTTP against `AnkkaTestKit` (the shape of
`OrganizationCreationPolicySuite`), including `restartService()` for replay;
`AuthorizationMatrixSuite` for who may set one; `EventCompatibilitySuite` for pinned wire shapes;
`CliEndToEndSuite` for the two commands; `DescriptorSuite` (controlplane-api) for `Quota.problems`;
`CliReferenceSuite` and `ControlPlaneRoutesReferenceSuite` regenerate the reference pages.

**Target Platform**: the control plane process; no operator, cluster or Kubernetes change.

**Project Type**: platform (control plane + wire library + CLI + docs)

**Performance Goals**: one extra entity call per project create/delete and per service
apply/delete (the organization is already opened on every such request for `roleOf`, so the shard
is warm); no change to reads.

**Constraints**: the wire library stays on `core` only; cross-entity checks live in the endpoint;
the entity never calls out; no view is ever consulted for enforcement; every existing suite passes
unchanged with no quota set (SC-001).

**Scale/Scope**: ~12 source files in `controlplane-api`, `controlplane`, `cli`; 5 test suites
touched, 1 new; 4 docs pages plus 2 generated.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states for this codebase:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data | pass | every new handler returns an effect; the testkit drives them with no runtime |
| Module direction (`controlplane-api` on `core` only) | pass | `Quota`/`Usage` are plain case classes with jsoniter codecs in `Wire`; the rules (`Quota.problems`) sit beside them so the CLI can refuse a negative before the round trip |
| Cross-entity checks live in the endpoint, never a handler | pass | the organization is asked to reserve; the project/service entity is then commanded; the endpoint sequences and compensates. Neither entity sees the other |
| Wire names are a versioning boundary | pass | new commands get their own wire names (`set-quota`, `reserve-project`, …); nothing existing is renamed |
| Pre-feature journals replay | pass | new event cases are additive; `Organization` and `OrganizationRow` gain fields with defaults; pinned in `EventCompatibilitySuite` |
| The `Option` default trap | pass | `Quota`'s limits are `Option[Int]` with default `None`, so `null` and absent both mean unlimited — the one case where jsoniter's null-as-absent is the meaning wanted |
| The listing is a view and lags; enforcement reads the entity | pass | `reserve*` on the entity is the only gate; the listing row folds the same events for display only |
| Docs stand alone; generated tables covered; every route documented | pass | organizations page section, limitations bullet, two route sections, CLI reference regenerated |

**Violations to justify**: none.

## Project Structure

### Documentation (this feature)

```text
specs/015-organization-quotas/
├── plan.md              # this file
├── research.md          # R1–R7: the decisions and why
├── data-model.md        # Quota, Usage, UsageRecord, events, commands, state
├── quickstart.md        # how to validate, offline and over HTTP
├── contracts/
│   ├── api.md           # the two routes, the changed summaries, the refusals
│   └── cli.md           # the two commands and the table
└── tasks.md             # /speckit-tasks output
```

### Source Code (repository root)

```text
controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/
└── descriptors.scala                  # Quota (+ problems), Usage; OrganizationDetail/Summary gain quota, usage; codecs in Wire

controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/
├── domain/events.scala                # QuotaSet, QuotaCleared, ProjectReserved, ProjectReleased,
│                                      #   ServiceReserved, ServiceReleased, UsageReconciled; SetQuota, ReserveService, RecordService
├── domain/model.scala                 # UsageRecord (the shared fold); Organization.quota, .record, .usage
├── application/OrganizationEntity.scala   # setQuota, clearQuota, reserveProject, releaseProject, reserveService, recordService
├── application/OrganizationRows.scala     # row carries quota + record → detail carries both
├── api/OrganizationEndpoint.scala     # PUT/DELETE /organizations/{id}/quota (admin), snapshot from the views
├── api/ProjectEndpoint.scala          # create: reserve → create → release on failure; delete: delete → release
└── api/ServiceEndpoint.scala          # apply: reserve → apply → restore on failure; delete: delete → release

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                         # organizations quota set|clear
├── ControlPlaneClient.scala           # setQuota, clearQuota
└── Output.scala                       # QUOTA column, usage in the table

controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/
├── QuotaSuite.scala                   # NEW: US1–US3 over HTTP, with a restart
├── TenancyEntitySuite.scala           # entity cases
├── AuthorizationMatrixSuite.scala     # case 11: who may set a quota
├── EventCompatibilitySuite.scala      # pre-feature state and summary decode; new events pinned
└── CliEndToEndSuite.scala             # quota set / get / clear through Main

docs/
├── platform/organizations.md          # "## Quotas"
├── concepts/tenancy-and-access.md     # one paragraph under Platform administrators
├── reference/limitations.md           # what quotas do not cover
├── reference/control-plane-api.md     # two route sections; summary example; generated table
└── reference/cli.md                   # generated
```
