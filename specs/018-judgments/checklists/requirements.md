# Specification Quality Checklist: Judgments — Fast, Typed Decisions from a System One Model

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-30
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the spec names existing platform
      pieces (session memory, guardrails, the scripted model, the default model's place of
      configuration) as the context every ankka spec does, and the one external provider the
      feature exists to reach; it decides no class, method, config key or stored layout. The
      provider's key variable and default version are in Assumptions as defaults, not in
      requirements.
- [x] Focused on user value and business needs — each story opens with what a developer or an
      operator gets.
- [x] Written for non-technical stakeholders — to the degree a component-model feature can be.
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — none were raised; the scope was agreed in the
      feature description. The two decisions with a defensible alternative (a guardrail fails
      closed with no option; the effect replies and does not continue) are stated as the first
      two assumptions so `/speckit-clarify` can reopen them.
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded — Out of Scope names every later candidate from the assessment and
      what a caller does in the meantime.
- [x] Dependencies and assumptions identified — including that the provider is in early access
      and its published facts are dated.

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows — ask and read (US1), the real provider (US2), guard
      (US3), test (US4), usage and version (US5), document (US6).
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
- Research the plan must settle before design:
  - how a judged guardrail reaches the service's default provider and reports its tokens, given
    that a guardrail today is a synchronous check that returns only allow or refuse and is handed
    no context;
  - how a provider failure inside a guardrail is told apart from a refusal on each path (request
    agent, streaming handler, autonomous iteration, task start);
  - how judgment tokens are added to a session's record without a message, compatibly with
    sessions already stored;
  - how a choice is tied to an enumerated type with declared option keys, and what reading an
    answer looks like so that it is typed without a second declaration;
  - the encoded form of a judgment, since it becomes a reply and can be stored;
  - the provider's actual behaviour where its published sources disagree (rate limits, the
    per-request size limit) and its retry hint's form, measured against the live endpoint.
