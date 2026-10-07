# Quickstart: Blueprints

The runs that show the feature works, cheapest first. Every command is from the repository root,
with Docker running.

## 1. Checking and shapes, with no runtime

```bash
sbt 'agent/testOnly *BlueprintCheckSuite *ShapeSuite *ScheduleSuite'
```

Expect: every check in `features/blueprints/checking.feature` refuses with the problem named, and a
blueprint with four problems names four; shapes refuse every non-conforming path; due times keep
20:00 in Europe/London across 25 October 2026, and periods meet.

## 2. Blueprints and runs on a real journal

```bash
sbt 'testkit/testOnly *BlueprintFeatures *RunFeatures *PatternFeatures'
```

Expect: one version for a blueprint registered twice, two for a changed one; a three-step run whose
service is restarted in its second step completes with each recorded model call made once
(SC-002); one step of each pattern gives its result.

## 3. Schedules with a moved clock

```bash
sbt 'testkit/testOnly *ScheduleFeatures'
```

Expect: three Sundays give three runs whose periods meet; two missed Sundays give one run, or two
with `catchUp: each` (SC-005).

## 4. Following runs

```bash
sbt 'testkit/testOnly *FollowingFeatures'
```

Expect: a tool is told its run; a consumer is told each step's end and the run's end in order.

## 5. Compatibility

```bash
sbt 'testkit/testOnly *EventCompatibilitySuite'
```

Expect: a task journal written before the feature reads with no definition; the pinned blueprint and
run journals read back.

## 6. The sample, offline

```bash
sbt researchDigest/test
```

Expect: thirty entries from forty scripted papers of which ten are found twice; a script naming
only papers read (SC-006).

## 7. The planner as a blueprint

```bash
sbt 'multiAgentPlanner/testOnly *PlannerBlueprintSuite'
```

Expect: select, consult and summarise as three steps pass with the planner's scripted model (SC-001).

## 8. Documentation and features

```bash
just docs-sync && just docs
just features
```

Expect: the blueprints page builds, is in the nav and the `ankka-agents` skill, and every sample on
it comes from tested code; the root features and the sample's features check clean.

## 9. By hand

```bash
docker compose up -d
ANTHROPIC_API_KEY=… sbt researchDigest/run
curl -s localhost:9000/digest/runs -d '{"from":"2026-10-11T19:00:00Z","to":"2026-10-18T19:00:00Z"}'
```

Expect: a run id; reading the run shows its steps, then a script in which every statement names a
paper.

## The whole build

```bash
caffeinate -i sbt buildAll
```
