# Specification Quality Checklist: Multi-Node Service Clusters

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-17
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

Three decisions were resolved with the user before drafting:

1. **Discovery** — through the Kubernetes API, following the user's reference project, over
   name-resolution-based discovery. The cost is that every service holds a credential able to list
   pods in its project; FR-028 to FR-031 exist to bound it, and SC-007 to prove the bound under a
   service's real identity.
2. **Instance count** — the descriptor's existing minimum-instances value, honoured as a fixed count,
   with no autoscaler (FR-013 to FR-015). No schema change.
3. **Scope** — the control plane is included (US5, FR-032 to FR-035).

On the reference project (`eitheror-platform/substrate/substrate-server/src/main/resources`): its
structure — a shared base, a local overlay naming seed nodes, a Kubernetes overlay configuring
API-based bootstrap, and **the same startup code in both** because configured seed nodes make
bootstrap stand down — is adopted as FR-006, FR-007 and FR-012. Its *selector*
(`-Dconfig.resource=application.k8s.conf`) is deliberately not: it replaces the application's
configuration wholesale, which suits an application that owns its configuration and would clobber a
nakka service's own (FR-011). The Assumptions section records this so the plan does not have to
rediscover it.

Two findings from reading the code while drafting, which change the size of the work:

- **The control plane is nearer ready than expected.** Its projection sweeper is already a cluster
  singleton. The only per-node piece is the status watch, and its duplicates are already absorbed by
  the service entity's "an identical observation is refused" guard — which is what FR-034 asks for.
  US5 is mostly proving that, plus manifests, rather than new mechanism.
- **Readiness is the safety net for old images.** An image whose runtime predates this feature would
  join itself; tying readiness to cluster membership (FR-021, FR-022) means it fails visibly instead
  of splitting silently. That edge case is why FR-024 words readiness as membership rather than a
  port — it also generalises feature 003's probe to services that serve no HTTP.

Carried to planning as things to **verify on a real cluster, not read about** — the standard features
002 and 003 set, each of which found the documentation wrong or silent:

- whether simultaneous cold start really yields one cluster (FR-002, SC-002), and what the
  required-contact-point count must be as a function of instance count for that to hold;
- whether a rolling update of a formed cluster keeps one cluster throughout (FR-019), including at
  one instance;
- how long graceful leave and shard handoff take against the pod termination grace period (FR-018);
- what an instance reports for readiness before and after joining, and whether an image without the
  management endpoint is held un-ready as the spec assumes (FR-022);
- the narrowest RBAC that lets the operator create a Role — Kubernetes refuses to let a principal
  grant permissions it does not itself hold, which will shape FR-031.
