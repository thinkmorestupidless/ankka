# Specification Quality Checklist: Cross-Project Access

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

- Both clarifications were answered on 2026-10-08 and folded in: machines outside the installation
  may be granted topics (the broker is exposed through the gateway by TLS passthrough, machines
  authenticate with the same token by SASL `OAUTHBEARER`, and quotas bound them), and a grant to
  another organization's principal is pending until that organization accepts it. No markers remain.
- A review on 2026-10-08 (recorded as a second clarification session) corrected the spec against the
  code: the control plane has only `Owner` and `Member`, so owners grant, accept and register
  machines; `machine` is a reserved project id so a machine's group prefix cannot collide with a
  service's; a grants volume is mounted on every pod from its first deploy, since a volume cannot be
  added later; gRPC methods are grant targets; delivery is at least once and idempotency is the
  callee's; the grantee-side record is derived by a consumer, not written beside the granting
  project's event; a revocation closes open streams; the control plane's cost as a token issuer and
  the honest shape of the Keycloak alternative are stated; and grants carry the `decrypt` attribute
  and `erasure` right feature 042 relies on, and show retention (043) to the grantee. Requirements
  were renumbered FR-001 to FR-033 in reading order.
- Two assumptions are to be confirmed at planning against the pinned releases: Envoy Gateway v1.9.1's
  experimental `TLSRoute` passthrough on the installation's one `Gateway`, and Strimzi 1.2.0's `oauth`
  listener with plain JWKS validation, `simple` authorization and credential-less `KafkaUser`s.
- As in the repository's other platform specs, the spec names existing types, files and Kafka/Strimzi
  constructs where the decision is about them; that is this repository's convention for platform
  features, whose readers are its developers, and is not counted as implementation leakage.
- The client-credentials grant (OAuth 2.0) is named because it is the interoperability contract for
  machines outside ankka, not an implementation choice.
- The mandatory `after_specify` hook (`/speckit-bdd-features`) has not been run; the acceptance
  scenarios stay in the spec until it is.
