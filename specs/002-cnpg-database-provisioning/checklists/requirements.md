# Specification Quality Checklist: Platform-Provisioned Databases

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-16
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

**All items pass.** 38 functional requirements, 15 success criteria, 4 prioritised user stories.

Judgement calls worth recording:

- **CloudNativePG is domain vocabulary, not leaked implementation.** The user named it explicitly,
  as Kubernetes was named in feature 001. Requirements stay outcome-shaped ("each service receives
  its own logical database no other service can access"); the mechanism's resource kinds appear
  only in Assumptions, and in each case record something **verified against a real cluster** during
  specification rather than taken from documentation — the docs proved inconsistent on exactly the
  points that mattered.
- **Per-project database capacity is forced, not preferred.** Verified directly: a `Database` or
  `DatabaseRole` referencing a Postgres cluster in another namespace produces no status and no
  events at all, while a same-namespace reference reconciles. Since projects already own
  namespaces, per-project capacity is the only topology without cross-namespace credential copying.
- **All three clarifications were resolved by the operator**, not by assumption:
  - Schema application → an init step beside each service's workload. This was *not* the
    assistant's recommendation (runtime-applied was, on the grounds that the runtime already
    creates its own view row tables). The chosen option's known consequence — getting a
    single-copy schema into every dynamically-created project namespace, via a component that
    deliberately cannot depend on the module holding that schema — is recorded in Assumptions so
    it is a planning input rather than a planning surprise.
  - Bring-your-own database → supported as a documented escape hatch, so two paths exist and
    one-database-per-service is enforceable on only one of them. Stated rather than implied.
  - Deletion → retain always; no platform operation destroys a database. FR-025 and FR-026 exist
    to make the accepted costs (accumulation, silent state inheritance on re-apply) visible.

Ready for `/speckit-plan`.
