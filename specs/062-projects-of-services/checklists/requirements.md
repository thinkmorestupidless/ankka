# Specification Quality Checklist: Projects of several services

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-31
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

- The feature is developer tooling, so its users are developers and the spec names the things they
  touch: `ankka init`, a template, tags, checks, the design and inspect skills. It names no build
  tool, CI product, file layout or tag format; those are the plan's.
- The three clarifications raised while specifying (system or project, the repository model, how a
  build of several services is reached) were answered by Trevor on 2026-10-31 and are recorded
  under Clarifications.
- FR-005 changes an existing glossary term (project), settled. The bdd hook also proposed a change
  to repository (a project's code or a service's) and six new terms, all *Proposed.* for clarify.
- The single-service template's living features are unchanged (FR-012); no scenario of theirs is
  referenced.
- Checker: 0 findings after the bdd hook (2026-10-31).
