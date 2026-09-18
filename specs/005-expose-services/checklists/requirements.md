# Specification Quality Checklist: Expose Services Outside the Cluster

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

Three decisions were resolved with the user before finalising: exposure is a **separate command**
(Akka's model; nakka's `pause`/`resume` precedent), hostnames are **platform-derived only**, and
**TLS is in scope now** — against the recommendation to defer it, which the user overrode. TLS adds
FR-016 to FR-016c, FR-019a, FR-021a and SC-010: certificates per hostname issued by an
installation-level authority, a self-signed one locally whose root the deployment exports, and
verification never disabled anywhere — the CLI gets a `config set ca` setting rather than an
insecure flag.

One design constraint is recorded in Assumptions rather than decided: the hostname's *shape* (one
level under the base domain or two) is left to planning, but the spec fixes what the choice must
satisfy — no collisions across any accepted names, and no hostname change when certificates are
added later. That is the kind of choice that reads as technical here and would be wrong to make
without checking what certificate issuance actually needs, which is planning's job.

Two things from reading the code while drafting:

- `pause`/`resume`/`restart` already exist as commands beside `apply`, so "state the descriptor
  does not carry" has precedent in nakka; Q1 is a choice between two models the codebase already
  contains, not between nakka's way and Akka's.
- Every endpoint declares an `acl`, with `DenyAll` the stated default posture. Exposure changes who
  can *reach* an endpoint and nothing about who is *allowed* to; the spec says the README must make
  that explicit, since an exposed `AllowAll` endpoint on a real cluster is public.
