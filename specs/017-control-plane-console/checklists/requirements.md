# Specification Quality Checklist: A Console for a Deployed Installation

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-28
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the requirements name the platform's
      existing pieces (the realm, the gateway, cert-manager, the control plane's routes) as every ankka
      spec does, and decide no library, page layout, cookie name or config key. The one framework
      choice the user asked for — server-rendered TypeScript — is recorded under Assumptions with the
      shape it implies and the alternatives it rejects, for the plan to confirm.
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders — each story opens with what a person at a browser gets.
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain. Three decisions were taken rather than asked, each with
      its reasoning under Assumptions, and `/speckit-clarify` should confirm them: the refresh token in
      the sealed cookie rather than a shared session store; a confidential client with PKCE rather than
      a public one; `console.<base domain>` as the hostname. A fourth was asked and settled: the
      console is a TypeScript package plus a thin host, so `ankka-cloud` can reuse it, rather than a
      Scala library mounted in the product's process.
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
- Research items the plan must settle before design: the size of a refresh token the shipped realm
  issues once sealed (SC-003's 2 kilobytes); whether the control plane's listener admits a certificate
  whose URI is `ankka://platform/console` without a change (Assumptions); how the local overlay's
  replacements reach a redirect address inside the realm import's JSON; whether the end-to-end
  cluster suite can complete Keycloak's sign-in form from the host with `curl`, or needs a browser;
  the package's registry name; how the platform emits wire-type fixtures for the package's client
  (the existing fixture suites are the model); and which of React Router's mechanisms carries a
  mountable route tree with a host-supplied layout.
