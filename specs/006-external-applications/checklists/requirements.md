# Specification Quality Checklist: A nakka Application Built Outside This Repository

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-18
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

Three decisions were resolved with the user before finalising: **one version for everything**
cut from one tag (FR-007); **both `sbt new` and `nakka init`** over a single template — the user
chose both where the recommendation was `sbt new` alone, so `nakka init` is specified as a front
door that runs the same expansion and carries no template of its own (FR-012); and the **schema
extracted from the published runtime by a build task** for the local database, with the runtime
explicitly never applying schema itself (FR-016).

Two things from reading the code while drafting:

- The single-copy schema rule (features 001–002) already makes FR-006 true: the test kit copies
  the DDL from the runtime's own resources, so a published runtime carries it. What does *not*
  carry over is this repository's `docker-compose.yml`, which mounts the DDL from a source path —
  that is exactly the gap FR-016's question is about.
- The operator applies the schema from *its* image (`CnpgRendering` reads `/nakka/ddl` from its
  own classpath), so the first external application is the first time the schema an application
  was tested against and the schema it is deployed onto can differ. US4 exists for that.
