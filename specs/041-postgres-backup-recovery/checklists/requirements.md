# Specification Quality Checklist: Backup and Recovery — Point-in-Time Restore for Every Database the Platform Provisions

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Every decision Trevor made on 2026-10-08 is recorded under Clarifications and embodied in the
  requirements; no clarification markers remain.
- Like every ankka spec, this one names its seams (`ClusterSpec`, `CnpgRendering`, `AnkkaProject`,
  `CloudEvents.headers`) because the reader is the platform's developer; "no implementation details" is
  read as "no choice the plan should make". The mechanism (CNPG's Barman Cloud plugin, how a service's
  host moves to a restored cluster, the control plane's restore procedure) is left to research.
- One finding goes beyond the decisions given: "re-produce idempotently by event id" does not hold today,
  because `CloudEvents.headers` gives each message a random `ce-id`. FR-019 and FR-020 make the id derived
  from the event and from the database's line of history, so a re-published event keeps its id and a new
  event after a restore never reuses a lost one's.
- Two more: Kafka consumers commit positions to the broker, so a restore leaves consumer groups ahead of
  the database (FR-018 lists them); and a restored control plane would re-project stale desired state over
  every service, so it starts with projection held (FR-031).
- Garage's durability is decided as three-node replication plus a scheduled copy to a secondary store
  outside the cluster, because backups held in a single-node Garage share the databases' failure domain.
- The review of 2026-10-08 changed ten things, recorded under "Session 2026-10-08 (review)": the switch is
  per service (the restore stays per cluster); the line of history is platform-written, never Postgres's
  timeline; re-published events re-apply to every in-platform view and consumer, stated rather than fixed;
  rehearsals run in `ankka-<project>-rehearsal` with a scoped delete and a time to live, and abandoned
  restored clusters are reclaimed by a platform administrator by hand; the Barman Cloud plugin is an
  installation component and each backup bucket has its own minted credential (044 on GCS); backups are
  encrypted client-side under a key in 038's store; an installation may require an off-cluster copy, and the
  copy mirrors deletions; the control plane's restore writes a marker, lists unknown namespaces and
  reconciles the erasure log; 038's read record and 042's keyring are backed-up stores, and services replay
  the erasure log before they are ready; restore ≠ rollback, pools reconnect after a promotion, SC-007 is
  50 GiB, archive lag is the RPO metric, major upgrades are out of scope.
- The mandatory `after_specify` hook (`/speckit-bdd-features`) was not run: the scenarios stay in the spec,
  as specs 025 onwards keep them, until the spec is reviewed.
