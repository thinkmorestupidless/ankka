# Specification Quality Checklist: Personal Data Erasure — Crypto-Shredding Per Data Subject

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

- Seven decisions were settled in the first clarification session of 2026-10-08 (recorded in the
  spec): crypto-shredding, field marking per subject, a platform erasure request per subject and
  project, holds, a keyring outside project backups with an erasure log replayed after restores, the
  stores reached, and a typed `Erased` value.
- The review session of the same day changed the encryption design and settled fourteen more points:
  the codec encrypts (not the runtime or the sidecar); each SDK's own `Personal` codec with one new
  sidecar rpc and a wasm host import; the trust boundary; subject per project with domain fan-out and
  a correlation id; objects erased by an SDK `erase(subject)` call from the service's erasure handler;
  the keyring as a platform component of ankka with an availability requirement and a cache outage
  bound; the root key from 038 or 044; the notice stream behind the 60-second bound; the subject id as
  the accepted residual; owner override of a hold; the erasure certificate; the keyring tombstone; the
  two restore cases split; subject-key rotation out of scope.
- Like every ankka spec, this one names the platform's seams (the `Personal` type and codec, the
  keyring handle, the envelope's shape, the erasure handler, the CLI command) because its reader is
  the platform's developer; "no implementation details" is read as "no choice the plan should make".
  The cipher, the keyring's wire protocol, the exact envelope keys and the lookup token's construction
  are left to research.
- FR-023's 1.3× replay budget, FR-022's 60-second bound and FR-020's outage bound are targets set
  here; research should test them against a real keyring before planning commits to them.
- FR-026 adds the first storage call to the SDK, which 034 deliberately avoided; the Assumptions say
  why this one is the exception. The plan should confirm 034's documentation is amended to match.
- The mandatory `after_specify` hook (`/speckit-bdd-features`) has not been run; it is next, before
  `/speckit-plan`. Until then the scenarios stay in the spec.
