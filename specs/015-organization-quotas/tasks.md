# Tasks: Organization Quotas

**Input**: Design documents from `/specs/015-organization-quotas/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included — every refusal and every count in the spec is a case in a suite, and the
reference pages are rewritten by their suites.

## Format: `[ID] [P?] [Story] Description`

Paths are repository-relative. `API` =
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`; `CP` =
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `CPT` its test twin;
`CLI` = `cli/src/main/scala/com/thinkmorestupidless/ankka/cli`.

---

## Phase 1: Foundational — the types and the fold (blocks every story)

- [x] T001 Add `Quota` (+ `Quota.problems`), `Usage`, the new fields on `OrganizationDetail` and
      `OrganizationSummary`, `OrganizationSummary.of` carrying them, and `Wire.quotaCodec` in
      `API/descriptors.scala`
- [x] T002 [P] `Quota.problems` cases in `controlplane-api/src/test/.../DescriptorSuite.scala`
- [x] T003 Add the seven events and the three request types to `CP/domain/events.scala`
- [x] T004 Add `UsageRecord` with `usage` and `fold`, and `Organization.quota`/`record`/`usage`
      (cleared by `onDeleted`) to `CP/domain/model.scala`
- [x] T005 Fold the new events in `CP/application/OrganizationEntity.applyEvent` and add the six
      handlers and their wire names (`set-quota`, `clear-quota`, `reserve-project`,
      `release-project`, `reserve-service`, `record-service`) with serializers
- [x] T006 Carry `quota` and `record` on `OrganizationRow` and fold the events in
      `CP/application/OrganizationRows.scala`; `detail` carries both
- [x] T007 Entity cases in `CPT/TenancyEntitySuite.scala`: set/clear; refusal of a negative and an
      empty quota; reserve/release project; reserve service new/lower/higher/same; the three 409
      messages; reconcile replaces; delete clears; fold-only replay equals the driven state
- [x] T008 Compatibility in `CPT/EventCompatibilitySuite.scala`: a pre-feature `Organization` state
      JSON decodes with no quota and zero usage; a pre-feature summary decodes; `ServiceReserved`
      and `QuotaSet` wire shapes pinned

## Phase 2: US1 + US2 — the cap, and usage that follows what exists (P1)

- [x] T009 [US1] `PUT`/`DELETE /organizations/{organizationId}/quota` in
      `CP/api/OrganizationEndpoint.scala`: `requireAdmin`, `Quota.problems` → 400, snapshot from
      `ProjectRows` + `ServiceRows` + `ServiceEntity.desiredState`, `setQuota` / `clearQuota`
- [x] T010 [US2] `POST /projects/{projectId}`: reserve → create → release on failure; `DELETE`:
      delete → release, in `CP/api/ProjectEndpoint.scala`
- [x] T011 [US2] `PUT /services/{projectId}/{name}`: pre-checks, reserve → apply → restore on
      failure; `DELETE`: delete → record none, in `CP/api/ServiceEndpoint.scala`
- [x] T012 [US1] New `CPT/QuotaSuite.scala` over HTTP: no quota → nothing refused; set 2/3/4;
      third project refused with the message; fourth service refused; instances refused then
      accepted at one; non-admin 403 and unchanged; clear → everything through
- [x] T013 [US2] `QuotaSuite` cases: re-apply lower frees, higher refused keeps the old generation;
      delete service and project free; pause changes nothing; disabled changes nothing;
      `restartService()` then usage unchanged; a failed create (id already taken elsewhere) leaves
      usage unchanged
- [x] T014 [US1] `CPT/AuthorizationMatrixSuite.scala` case 11: owner and member get 403 on set and
      clear; the administrator succeeds on a disabled organization too

## Phase 3: US3 — lowering stops nothing (P2)

- [x] T015 [US3] `QuotaSuite` cases: with three services set services=1 → accepted, services
      untouched (generation unchanged), new service refused naming 1 and 3, unchanged re-apply
      accepted, lower re-apply accepted; delete two → a new one lands

## Phase 4: US4 — the wire library and the CLI (P2)

- [x] T016 [US4] `setQuota`/`clearQuota` in `CLI/ControlPlaneClient.scala`
- [x] T017 [US4] `organizations quota set|clear` in `CLI/Main.scala`, refusing an empty or negative
      quota before the round trip
- [x] T018 [US4] `QUOTA`, `SERVICES`, `INSTANCES` columns in `CLI/Output.scala`
- [x] T019 [US4] `CPT/CliEndToEndSuite.scala`: `quota set` then `get` shows `1/-/-`, a refused
      create prints the server's message, `quota clear`; `set` with no limit is a usage error
- [x] T020 [US4] `QuotaSuite` reads the summary back with the wire library's codec (a client
      outside the control plane decodes `quota` and `usage`)

## Phase 5: Documentation

- [x] T021 [P] "## Quotas" on `docs/platform/organizations.md`; one paragraph in
      `docs/concepts/tenancy-and-access.md` under Platform administrators
- [x] T022 [P] Bullet in `docs/reference/limitations.md` on what quotas do not cover
- [x] T023 [P] Two route sections and the summary example in `docs/reference/control-plane-api.md`;
      the administrators line lists quotas
- [x] T024 Regenerate the route table and `docs/reference/cli.md` under `-Dankka.docs.update=true`;
      `just docs`
- [x] T025 `CLAUDE.md`: a reserve-first note under the control plane's invariants

## Phase 6: Finish

- [x] T026 `sbt scalafmtAll scalafmtSbt`, `sbt -Dankka.cluster.tests=off test` (or the module
      suites); commit on `015-organization-quotas`; open the PR

## Dependencies

T001 → T003 → T004 → T005/T006 → T009–T011 → T012–T015; T016–T019 after T001 and T009; docs after
the code lands (T024 needs the routes and commands).
