# Specification Quality Checklist: Topic Retention

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

- Like every ankka spec, the Context names the code and resources the feature changes
  (`KafkaTopicSpec`, `TopicProvisioning`, `kafka.yaml`); the requirements themselves are stated as
  behaviour. Strimzi appears in Assumptions, for the plan to confirm; Cruise Control is named only as
  the reason copies are fixed at declaration, and is not installed.
- Reviewed 2026-10-08 against the code (recorded under Clarifications, "Session 2026-10-08 (review)"):
  copies fixed at declaration; the three-node overlay a new installation's shape, with the single-node
  grow out of scope as a quorum change; a keyless publication to a compacted topic fails locally
  (FR-021); copies above the node count refused by the operator, not the control plane (FR-004a); the
  gap report states only what `beginningOffsets` and `earliestRetained` know; the seven-day default
  documented as a laptop's with a view-declaration warning (FR-020); tombstone window and compaction
  lags per topic; an `acks` override below `all` refused (FR-022); the overlay's 3× storage stated.
- The acceptance scenarios live in `features/broker/{retention,cleanup-policy,copies,changing}.feature`
  and `features/topics/gap.feature`. What the control plane decides runs offline in
  `TopicRetentionOfflineFeature`, `TopicCopiesOfflineFeature` and `TopicChangingOfflineFeature`; what a
  broker shows runs on k3s in `BrokerRetentionFeatures`, `BrokerCleanupFeatures`, `BrokerCopiesFeatures`
  (three nodes) and `BrokerChangingFeatures`; the gap runs in `RetentionGapSuite` (in memory) and
  `KafkaSuite` (a broker); each file's `ranElsewhere` names where its other scenarios run.
