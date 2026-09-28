# Specification Quality Checklist: Zero Trust in the Service Clusters

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-28
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the spec names existing platform
      pieces (cert-manager, the operator, the sidecar, `Acl` forms) as the context every ankka spec
      does; it decides no certificate format, key length, issuer kind, config key or class.
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders — to the degree a platform-security feature can be;
      each story opens with the guarantee a service author or installer gets.
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — **one remains, on purpose**: whether a project is
      a network boundary for HTTP or only an identity one (Assumptions, last item). The spec takes
      the identity-only position and says what the other costs; `/speckit-clarify` should settle it.
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

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
- Research items the plan must settle before design, recorded in the spec's Assumptions: whether
  the installed gateway presents a client certificate per route or per installation; whether the
  local kind network enforces policy without a CNI change; the exact rotation numbers.
