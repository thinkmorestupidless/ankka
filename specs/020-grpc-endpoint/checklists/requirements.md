# Specification Quality Checklist: gRPC Endpoints — A Second Way Into a Service

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-02
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

- **"No implementation details" is read for this product.** The platform's users are developers, and
  gRPC, `.proto` files, gRPC statuses, Scala and mutual TLS are what the feature *is* to them, not
  how it is built. The spec names no library, no server, no module, no port number, no Kubernetes
  object and no file of the codebase. Which server serves gRPC, whether it shares the HTTP port's
  machinery, and how the generated code is bound to handlers are left to `/speckit-plan`.
- **No clarification markers.** Scope decisions that had a defensible default are recorded under
  Assumptions rather than asked. `/speckit-clarify` then settled the ones most worth challenging:
  exposure through the gateway is in this version, at the service's existing hostname; every kind
  of method is served; reflection is opt-in; `Conflict` maps to failed precondition. Still
  assumptions: the calling side is in this version, and a descriptor that declares gRPC for a
  process- or module-hosted service is refused, not ignored.
- **Acceptance scenarios are references.** Each user story names its scenarios in `features/`;
  the scenarios themselves are written by `/speckit-bdd-features`, in the words of `GLOSSARY.md`.
- **A word that was settled.** The codebase and the documentation call the workload a request came from
  its *caller*. The glossary on the `019-service-topology` branch gives *caller* to the component
  that made a call inside a service. This spec says *calling workload* for the former so the two
  do not collide when both branches are on `main`. `/speckit-clarify` settled it: *calling
  workload* stays, and the rule about who may call is an *ACL*, the established word.
