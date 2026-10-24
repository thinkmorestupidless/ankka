# Feature Specification: Deploying over a generation that never became ready

**Feature Branch**: `060-rollout-between-crash-looping-generations`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "A rolling update between two crash-looping generations never forms a
cluster (issue #108 item 4). Today, when a service's running generation is crash-looping (its pods
never Ready) and a member applies a new generation that also fails, or that is healthy but has to
form a cluster beside the old never-Ready pods, bootstrap discovery sees the old pods and the new
cluster never forms; the member waits for ever. The platform must form the new generation's cluster
regardless of the old generation's never-Ready instances, so a fix deploys over a broken one; the
operator and the service's bootstrap discovery are the parties."

## Context

Every apply, restart and roll back of a deployed service is a rolling update: a new instance starts
beside the old ones, joins the service cluster the old ones form, becomes ready, and only then does
an old instance stop. That is what gives a deploy no gap, down to a service of one instance, and it
rests on one thing: there is a service cluster to join. An instance is ready when it is a member of
one, so "joins, then is ready, then an old one stops" is one movement.

The movement has no answer for a generation that never got that far. A service whose instances
crash shortly after they start, for a reason in the service, has instances but no service cluster:
each is a running copy that is not ready, and never was. When the developer fixes the fault and
applies the next generation, its new instance starts beside the old one, as the rolling update
requires, and looks for the cluster to join. It finds the old instance, which is a copy of the
service and so a candidate for membership, and the two wait for each other: the new one for a
cluster the old one would form, the old one crashing before it can. Neither is ready, the old one is
never stopped because nothing new became ready, and the rollout never finishes, however good the
new generation is. On the installation where this was seen (issue #108, item 4), the only way out
was to pause the service and resume it, so that one instance of the new generation started with
nothing beside it and formed a service cluster alone.

The rule that makes a deploy safe has become the thing that holds a fix out. This feature gives the
rolling update the answer it lacks: a generation none of whose instances has ever been ready has no
service cluster to protect, so the next generation forms its own among its own instances and the
old ones are stopped, and the member does nothing but apply. Everything about a generation that
*has* been ready is unchanged, because there the old instances are a service cluster, and a new
instance that formed a second one beside it would have two instances of the service each believing
an entity is active on it alone. The line is "has ever been ready", judged over the generation's
whole life, not "is ready at this moment": an instance that is restarting after a long run is still
the service cluster, and is still joined.

The lifecycle already says what the platform knows: a generation whose instances crash is reported
`Failed`, with the instance's own reason, once the deployment's deadline passes (feature 003), and
`UpdateInProgress` while a rollout is under way. This feature changes what happens next, not how it
is named.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A fix deploys over a generation that never became ready (Priority: P1)

A developer deploys a service whose instances crash shortly after start, for a reason in the
service, and the platform reports the service `Failed` with that reason. The developer fixes the
fault, builds a new image and applies the descriptor again. The new generation's instances start,
form their own service cluster, and are ready; the old generation's instances are gone. The
developer did nothing but apply, and the service reads `Ready` within the time an ordinary deploy
takes.

**Why this priority**: This is the defect. Without it a developer whose first deploy of a service
fails cannot deploy the fix without knowing a workaround that nothing tells them, and a service
whose instances start crashing after a bad release cannot be rescued by a good one.

**Independent Test**: Apply a descriptor whose image exits shortly after start; wait for `Failed`;
apply a descriptor whose image runs; assert the service is `Ready`, that every instance runs the new
generation, and that nothing else was done to it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/deploying/rollouts.feature`: a generation that works replaces one whose instances never became ready
- added `features/deploying/rollouts.feature`: the next generation forms its service cluster among its own instances when the running generation never became ready
- added `features/deploying/rollouts.feature`: a restart of a service whose instances never became ready gives it instances that form a service cluster

---

### User Story 2 - A second broken generation does not wedge the service (Priority: P2)

A developer's fix is itself wrong: the new generation's instances crash too. The platform reports
the service `Failed` with the new generation's reason, naming the new generation, rather than
leaving the report on the old one or on a rollout that never ends. The developer fixes it properly
and applies a third generation, which is ready as in the first story.

**Why this priority**: A fix that is itself broken is the ordinary course of debugging a deploy.
If the second broken generation leaves the service in a state the third cannot deploy over, the
first story has only moved the wedge one generation along.

**Independent Test**: Two descriptors in a row whose images exit shortly after start, then one
whose image runs; assert `Failed` naming the second generation and its reason after the second
apply, and `Ready` on the third.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/deploying/rollouts.feature`: a generation that also never becomes ready is reported failed with its own reason
- added `features/deploying/rollouts.feature`: a generation that works is ready after two that never became ready

---

### User Story 3 - A generation that has been ready is joined, never replaced out of hand (Priority: P3)

A service has run for weeks. One of its instances is restarting at the moment a developer applies a
new generation: it is not ready right now, but its generation has been. The new generation's
instances join the existing service cluster and the rollout proceeds one instance at a time, as it
always has; at no moment does the service have two service clusters, and no request is refused.

**Why this priority**: This is the guard on the first story. The rule that lets a fix deploy over a
broken generation must not let a new generation form a second service cluster beside a working one,
which would have an entity active on two instances at once. It is third because the behaviour
exists today; the story keeps it.

**Independent Test**: A ready service of three instances; stop one instance's process so that it
restarts; apply a new generation while it is restarting; assert that every new instance joined the
cluster the old ones formed, that the service was never without a ready instance, and that no entity
was active on two instances.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/deploying/rollouts.feature`: a generation that has been ready is joined by the next even while one of its instances is not ready
- `features/deploying/applying.feature`: a descriptor applied with a new image replaces the instances with ones running it

---

### Edge Cases

- A generation of three instances, one of which was ready once and two of which never were. The
  generation has been ready; the next generation joins its service cluster as a rolling update does
  today. The two instances that never became ready are the generation's problem to report, not a
  reason to form a second cluster.
- An instance of a generation that never became ready starts to come good while the next
  generation is forming its cluster. It is of a generation that had never been ready when the next
  was applied, so it takes no part in the new service cluster and is stopped; it does not form a
  second cluster of its own.
- The next generation is applied within seconds of the first, while the first generation's
  instances are still starting for the first time and have not yet been ready. They have never been
  ready, so the next generation forms its own service cluster and they are stopped. Nothing is
  lost: an instance that was never ready was never a member of a service cluster and never had an
  entity active on it; the service's records are in its database.
- A service of one instance whose generation never became ready. The commonest case, and the one
  reported. The next generation's one instance forms a service cluster alone and is ready.
- A member scales a service whose generation never became ready, changing nothing else. The new
  instances run what the old ones run and fail as they do; the service stays `Failed` with the same
  reason. Nothing in this feature changes that.
- A member pauses a service whose generation never became ready and resumes it. This works today
  and goes on working: a paused service has no instances, and a resumed one's instances form a
  service cluster among themselves.
- The generation that never became ready was reached by a roll back. A roll back is a generation
  like any other; the rule reads the generation, not how it came to be.
- A generation whose instances are never ready because the image cannot be pulled, or is built for
  the wrong architecture, rather than because the service crashes. Never ready is never ready: the
  next generation forms its own cluster and the old instances are stopped.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When a new generation is applied to a deployed service none of whose instances of the
  running generation has ever been ready, the platform MUST bring the new generation to ready,
  where its instances can run, with no action from the member beyond the apply. Pausing and
  resuming MUST NOT be needed.
- **FR-002**: The instances of a generation that never became ready MUST take no part in forming
  the next generation's service cluster: the next generation forms it among its own instances.
- **FR-003**: The instances of a generation that never became ready MUST be stopped by the time
  the next generation is ready, and MUST NOT be sent a request at any time.
- **FR-004**: A deployed service MUST have at most one service cluster at any moment. A generation
  that has had a ready instance MUST be joined by the next generation's instances as a rolling
  update does today, even while one or more of its instances is not ready at that moment.
- **FR-005**: Whether a generation has ever been ready MUST be judged over the generation's whole
  life, not at the moment of the next apply.
- **FR-006**: When the next generation also never becomes ready, the service's lifecycle MUST be
  `Failed`, and the report MUST name the latest generation and the reason its instances give, as
  the lifecycle does today for a first generation that fails.
- **FR-007**: A generation that works MUST be ready when applied after any number of generations
  that never became ready.
- **FR-008**: A restart or a roll back of a service whose generation never became ready MUST behave
  as an apply does under FR-001 to FR-003.
- **FR-009**: The ordinary rolling update of a generation that has been ready MUST be unchanged: one
  new instance at a time joins the service cluster and is ready before an old one stops, and no
  request is refused for it.
- **FR-010**: The behaviour MUST hold for a service of one instance and of several, and for every
  way a service is hosted: an image of the platform's own runtime, a process beside the platform's
  own program, and a module the platform's own program loads.
- **FR-011**: The documentation of deploys and rollouts MUST say what happens when the running
  generation never became ready, in the terms of this feature, and the limitations page MUST
  promise nothing past it.

### Key Entities

- **Generation**: one apply, restart or roll back of a deployed service, counted from 1. This
  feature reads one fact of a generation it did not need before: whether any of its instances has
  ever been ready.
- **Instance**: one running copy of a service. An instance is ready when it is a member of the
  service cluster and listens on what it declares.
- **Service cluster**: the instances of one service joined together, so that each entity is active
  on exactly one of them. A generation that never became ready has none.
- **Report**: what the platform reports of a deployed service: its lifecycle, how many of its
  instances are ready of how many it asks for, the generation it describes, and why.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service whose generation never became ready is `Ready` within three minutes of a
  working generation being applied to it, with no other action by the member, on the local
  installation. Today it never is.
- **SC-002**: Across every scenario of this feature, the service has at most one service cluster at
  every moment: no entity is observed active on two instances at once, in any scenario.
- **SC-003**: A rolling update of a service that has been ready refuses no request, as measured by
  the existing scenario for a restart under load, which stays green.
- **SC-004**: Within one minute of a second broken generation being applied, the report names that
  generation and the reason its instances give.

## Assumptions

- The platform can know whether any instance of a generation has ever been ready; it already judges
  a generation `Failed` from its instances, and reports how many are ready of how many it asks for.
- "Crashes shortly after start, for a reason in the service" is the reported case, and an image that
  cannot be pulled or run is the same case for this feature: both leave a generation with no ready
  instance ever.
- A generation that never became ready never had an entity active on an instance and never served a
  request, so stopping its instances rather than waiting for them loses nothing a member could want
  kept.
- The pause-and-resume workaround stays available and is not what the documentation offers for this
  case once the feature is built.
- The scenarios run where the platform runs services: the cluster suites, with an image that exits
  shortly after start as the broken generation.

## Out of Scope

- A generation that was ready and later stops being so, for every instance at once: that is the
  service being `Unavailable` until its instances are replaced (feature 003 and the healing
  features), not a generation that never became ready.
- Diagnosing or reporting the service's own fault beyond what the lifecycle reports today.
- Changing how many instances a rolling update of a ready generation starts or stops at once.
- A service whose instances are ready but cannot agree on a service cluster for any other reason.
