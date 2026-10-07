# Feature Specification: Replayable Topic Sources — Groups of Their Own, a Chosen Start, and Rebuild on Version Change

**Feature Branch**: `024-replayable-topics`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "Replayable topic sources (core feature C3). A view or consumer that
reads a broker topic today sees only what is published after it starts, cannot be rebuilt when its
handler changes, and shares a Kafka consumer group with any other service on the same broker that
happens to use the same component id, so two such services each see half the messages. Fix the
group id first, qualifying it by project and service. Then let a topic source declare where it
starts, earliest, latest or a timestamp, applied when its group has no committed offset. Then let a
view declare a version, so that a changed version truncates its table, moves to a fresh group and
reads from its declared start again, which is rebuild on deploy for the span of the broker's
retention. State plainly in the documentation that a rebuild is bounded by retention. Out of
scope: entity-sourced views, which the journal already makes rebuildable; a broker other than
Kafka; and the broker's provisioning, which is 027-managed-broker."

## Context

A view or consumer may read a Kafka topic instead of an entity, declared as
`ChangeSource.fromTopic(topic, serializer)` in Scala, `topic = "..."` in Python and TypeScript,
and `Source::topic("...")` in Rust.
`KafkaSubscriber.subscribe(topic, groupId, handle)` in
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Kafka.scala` reads with a
committable source, hands each record to the handler one at a time, commits the offset to Kafka
after the handler returns, and restarts with backoff on failure. It sets `auto.offset.reset` to
`earliest`, which Kafka applies only when a group has no committed offset. Delivery is at least
once, and the limitations page says what follows: a topic-sourced component must tolerate
duplicates and cannot rebuild its state from history. The page also says it sees only what is
published after it starts, which is wrong for a group that has never read: it reads everything the
broker retains.

The group id is the projection's name, `ankka-view-<componentId>` or
`ankka-consumer-<componentId>`, built in `ProjectionRuntime.scala`. Nothing in it names the
project or the service. Kafka assigns a topic's partitions among the members of one group, so two
services on one broker, each with a view of component id `orders` over one topic, are one group,
and each service's instances receive only the partitions assigned to them: every message is
delivered to exactly one of the two services. Nothing reports this. Each service's view simply
holds about half the rows it should. The platform supplies no broker today, so every installation
with two topic-reading services on one Kafka is exposed to it, and the domain plan puts seven
services on one broker.

There is no way to choose where a new group starts. `earliest` is set, so a new group reads
everything the broker retains, which is right for a view and surprising for a consumer that sends
an email per message and is deployed against a topic a month old. There is no way to move a group
back once it has committed, so a view whose handler changes keeps the rows the old handler wrote
and applies the new handler only to new messages. A topic is not a journal: the broker's retention
is a window, not a record, and that is why the designing page sends a view that must be whole to
an entity. Within the window, though, a rebuild is possible and today is not offered.

Three changes, in order.

- **The group id is qualified first.** It becomes `ankka.<project>.<service>.<kind>.<componentId>`,
  built from the identity the platform issues every workload. A local run has no project: one
  that states a service name gets `ankka.local.<service>.<kind>.<componentId>`, and the current
  form is kept only where no name is given at all. This is a defect with a real failure
  behind it, and it is built first. It is not released alone: an existing deployment's group
  changes name on upgrade, so its offsets start over under the new name, and a consumer must be
  able to say where it starts in the release that does that to it. The documentation says so.
- **A start position is declared.** A topic source names `startFrom`: `latest`, `earliest`, or
  a timestamp. It applies when the group has no committed offset; a restart resumes from the
  committed offset as today. The default stays `earliest` for a view. A consumer has no default:
  one that reads a topic and declares no start position is refused at registration, because
  `earliest` replays every retained message through a side effect and `latest` silently drops
  the backlog, and the group rename makes every existing consumer choose between them on upgrade.
- **A view declares a version.** A version is a positive integer that only goes up. When the
  version a running view declares is higher than the one recorded for its table, the runtime
  truncates the table and records the new version in one transaction, derives a new group id
  carrying the version, and reads from `startFrom` again. The old group's offsets are left where
  they are and never reused. An instance declaring a lower version than the recorded one, which
  is every old instance during a rolling update and every instance after a rollback, stops
  reading the topic for that view, writes nothing more to its table and never truncates it. A
  consumer has no table to truncate, and its version changes only its group, which is how a
  consumer is deliberately made to re-read a topic.

What this feature is not: a way to make a topic a complete record, which only an entity's journal
is; a change to entity-sourced views, whose rebuild on deploy is a separate gap on the limitations
page; a rewind API for a running group, which the version mechanism replaces; and anything about
how a broker is provisioned, secured or reached, which is 027-managed-broker. Nor does it show a
topic source in `services get`: that needs a channel from a running workload to the platform,
which is a spec of its own.

## Clarifications

### Session 2026-10-03

- Q: What start position does a topic-sourced consumer get when it declares none? → A: None. A
  topic-sourced consumer must declare its start position and is refused at registration without
  one; a view's default stays `earliest`.
- Q: What is the rule during a rolling update, when one instance runs a view at version one and
  another at version two? → A: The highest version wins. Versions are positive integers that only
  go up; a higher declared version truncates and records itself in one transaction, once; every
  write checks the recorded version, and an instance declaring a lower one stops reading the
  topic for that view, writes nothing more and never truncates.
- Q: How is a consumer group named on a local run? → A: Qualified when the service is named:
  `ankka.local.<service>.<kind>.<componentId>`. The form used before this feature remains only
  for a local run that states no service name. The templates state one.
- Q: Should a version bump be refused or warned about when the broker retains less than the table
  was built from? → A: Never refused. Before truncating, the runtime logs the view, the old and
  new versions, and the timestamp of the earliest record the broker still holds on each
  partition.
- Q: Beyond the service's log, where is a topic source's group, start position and version
  visible? → A: In the service's metrics. `services get` and the console's page were chosen
  first and withdrawn in planning: nothing carries a running service's facts to the platform (the
  operator reads nothing from a pod, holds no certificate a workload would accept, and the one
  open port answers readiness alone), and building that channel is a later spec's. The local
  console's topology view is not part of this feature either.
- Q: A version on a view or consumer that reads an entity: accepted with no effect, or refused?
  → A: Refused at registration, naming the component, as FR-009 says. The scenario that said
  nothing changes is replaced.
- Q (raised by analysis): a project named `local` on the platform and a local run that states
  its name would share group names. → A: `local` is reserved: it cannot be a project's id.
- Q (raised by analysis): may the group rename be released before a start position can be
  declared? → A: No. User Stories 1 and 2 are released together.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two services reading one topic each see every message (Priority: P1)

An operator runs two services on one broker. Each has a view over the same topic, and because the
same developer wrote both the views have the same component id. Every message published to the
topic appears in both services' views.

**Why this priority**: Today each sees about half, silently. This is a defect, not a feature, and
it is fixed before anything else in this series touches the subscriber. It is released with User
Story 2, never before it: the rename restarts every group, and a consumer must be able to say
where.

**Independent Test**: Two `AnkkaTestKit` services with distinct project and service names and a
view of one component id, both subscribed to one topic on the testcontainers Kafka the topic suite
already starts; publish a hundred messages with distinct subjects; assert each service's view has
a hundred rows.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topics/groups.feature`: two services reading one topic through views of the same id each hold every message
- added `features/topics/groups.feature`: the instances of one service read each partition of a topic once between them
- added `features/topics/groups.feature`: a deployed service's group is named for its project, its service, its kind and its id
- added `features/topics/groups.feature`: a local service that states its name has a group named for it
- added `features/topics/groups.feature`: a local service that states no name has a group named for its kind and id alone
- added `features/topics/groups.feature`: two named local services reading one topic each hold every message
- added `features/topics/groups.feature`: a project made from a template states its service's name
- added `features/topics/groups.feature`: no project is named "local"
- added `features/topics/groups.feature`: an upgraded service starts again under its new group
- added `features/topics/groups.feature`: the longest permitted ids make a group the broker accepts

---

### User Story 2 - A topic source declares where it starts (Priority: P1)

A developer deploys a consumer that sends a notification per message against a topic that has held
messages for weeks. They declare it starts at `latest`, and nobody receives a month of
notifications. Another developer deploys a view over the same topic and declares `earliest`, and
the view has a row for every message the broker still holds. A third declares a timestamp and gets
everything from that moment.

**Why this priority**: Without it a new consumer against an old topic replays everything, which is
the wrong default for a side effect and cannot be changed.

**Independent Test**: Publish fifty messages, then start a view with `earliest`, a view with
`latest` and a view with a timestamp between the twentieth and twenty-first message; publish ten
more; assert row counts of sixty, ten and forty.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topics/start-position.feature`: a view starting at the earliest message holds every retained message
- added `features/topics/start-position.feature`: a view starting at the latest message holds only what is published after it starts
- added `features/topics/start-position.feature`: a view starting at a time holds every message published since that time
- added `features/topics/start-position.feature`: a view starting at a time goes on to read what is published after it starts
- added `features/topics/start-position.feature`: a view declaring no start position starts at the earliest message
- added `features/topics/start-position.feature`: a restarted view reads what was published while it was stopped
- added `features/topics/start-position.feature`: a restarted view is not delivered what it has already read
- added `features/topics/start-position.feature`: a consumer reading a topic must declare its start position
- added `features/topics/start-position.feature`: a consumer whose service cannot declare a start position starts at the earliest message
- added `features/topics/start-position.feature`: a start position is honoured in every language a service is written in
- added `features/topics/start-position.feature`: a start position earlier than every retained message starts at the earliest
- added `features/topics/start-position.feature`: a start position later than now starts at the latest message

---

### User Story 3 - A changed view is rebuilt from the topic (Priority: P2)

A developer changes what a view's rows hold and bumps its declared version. On deploy, the view's
table is emptied, the view joins a fresh group and reads the topic from its start position again.
Rows the old handler wrote are gone; every message the broker retains is applied by the new
handler. A consumer with a version bump re-reads the topic the same way, and the developer who
bumps it knows that is what they asked for.

**Why this priority**: It makes a topic-sourced view maintainable. Without it the first change to
a cross-service read model is a manual table truncation and a group reset by hand.

**Independent Test**: Deploy a view at version one over a topic with messages; assert rows; change
the handler to write a different shape and bump the version; restart the service; assert the
table has only rows of the new shape, as many as the retained messages, and the broker shows a
second group for the view with the first group's offsets untouched.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topics/versions.feature`: a view at a higher version is rebuilt from every retained message
- added `features/topics/versions.feature`: a view restarted at the same version is not rebuilt
- added `features/topics/versions.feature`: a view rebuilt from the latest message is empty until a message is published
- added `features/topics/versions.feature`: a view with no recorded version is taken to be at version 1
- added `features/topics/versions.feature`: a consumer at a higher version is delivered every retained message again
- added `features/topics/versions.feature`: a version on a consumer that reads an entity is refused
- added `features/topics/versions.feature`: a version that is not a positive whole number is refused
- added `features/topics/versions.feature`: during a rolling update the higher version rebuilds the view once and the lower stops writing
- added `features/topics/versions.feature`: instances starting together at a higher version rebuild the view once
- added `features/topics/versions.feature`: a service rolled back to a lower version leaves the view as it is
- added `features/topics/versions.feature`: a rebuild says how far back the broker retains before it removes a row
- added `features/topics/status.feature`: a topic source says in the log how it subscribed
- added `features/topics/status.feature`: a service's metrics list each topic source
- added `features/topics/status.feature`: a view behind its recorded version is shown as behind in the metrics
- added `features/topics/status.feature`: a service with no topic source lists none in its metrics

---

### User Story 4 - The limits are documented (Priority: P3)

A developer reads the topics page and learns that a topic source's rebuild reaches back only as
far as the broker retains, that a group's name includes the service's, and what an upgrade does to
an existing group. The limitations page no longer says a topic source cannot replay, and says
instead what bounds it.

**Why this priority**: The feature without the page is a surprise waiting for the first developer
who expects a rebuild to be complete.

**Independent Test**: The documentation build passes and the generated configuration table lists
the new settings.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/topic-sources.feature`: the documentation describes groups, start positions and versions, and what bounds a rebuild
- added `features/documentation/topic-sources.feature`: the documentation's limitations say what bounds a topic source's rebuild
- added `features/documentation/topic-sources.feature`: the documentation says what an upgrade does to a service's groups

---

### Edge Cases

- **A timestamp older than the broker retains.** Reads from the earliest retained offset; nothing
  is reported, since the broker cannot say what it has dropped.
- **A timestamp in the future.** Behaves as `latest`.
- **A topic with no messages and `earliest`.** The view is empty and waits; no error.
- **A version bump on a view whose table is large.** Truncation is one statement; the rebuild's
  duration is the topic's size, and the view serves an empty or partial table while it runs,
  which the page states.
- **A version bump and a start position of `latest`.** The table is truncated and nothing is
  read until a new message arrives: an empty view, by the developer's own declaration. The page
  says so.
- **Two components with the same id and kind in one service.** Refused at registration today;
  unchanged.
- **The longest group name.** Kafka states no limit on a group id's length. Project, service and
  component ids are bounded by their own rules, so the longest name is 277 characters, and a test
  shows a broker accepts it.
- **A consumer that throws on every message under `earliest`.** It stops at the first message
  and is redelivered it, as today; a start position changes where it stops, not whether.
- **The version recorded for a table is absent because the table predates this feature.** Treated
  as version one on first start, with no truncation.
- **A project on the platform whose id is `local`.** Refused: `local` is reserved, as `platform`
  is, so that a deployed service's groups and a named local run's can never be the same name. An
  installation that already has a project called `local` can no longer project it, and the
  upgrading page says so.
- **A rollback to a lower version.** The view stops updating and serves the rows the higher
  version wrote, which the older code may not read. Going back is done by going forward: the old
  handler is published under a version higher than the recorded one. The topics page says so.
- **An old instance on a runtime that predates versions, during the roll that introduces one.**
  It cannot check the recorded version and keeps writing until it stops; the rows it writes after
  the truncation are overwritten only where the new handler writes the same row. The upgrading
  page says a view's first version bump should follow the runtime upgrade, not ride with it.
- **A consumer's version bump during a rolling update.** Both groups are live until the old
  instances stop, so a message published during the roll is delivered under each; this is the
  duplicate delivery a consumer already tolerates.

## Requirements *(mandatory)*

### Functional Requirements

**Group ids**

- **FR-001**: A topic source's consumer group MUST be named by project, service, component kind
  and component id, from the identity the platform issued the workload. A local run that
  states a service name MUST use `local` in the project's place, and only a local run that states
  none falls back to the current form. `local` MUST be refused as a project id, by the control
  plane and by the operator alike.
- **FR-014**: A deployed workload MUST take its project and its service name from the identity
  the platform issued it, never from its descriptor or its own configuration, so that no service
  can name its groups as another's. A descriptor that sets the variable a local run states its
  name with MUST be refused. Every template `ankka init` renders MUST state the service's name
  for a local run.
- **FR-002**: The longest group name the rules for project, service and component ids permit MUST
  be accepted by a broker, asserted by a test against one.

**Start position**

- **FR-003**: A topic source MUST be able to declare a start position of `earliest`, `latest` or
  a timestamp, applied only when its group has no committed offset.
- **FR-004**: The default start position for a view MUST remain `earliest`. A topic-sourced
  consumer has no default: one that declares no start position MUST be refused at registration,
  naming the component. The one exception is a consumer whose service is built on an SDK that
  predates start positions and so cannot declare one: it MUST start at `earliest`, as it always
  has, and the runtime MUST log that it declares none. Without the exception a service that was
  running could not restart after the platform beneath it was upgraded.
- **FR-005**: The start position and the version MUST be declarable in Scala, Python, TypeScript
  and Rust, and carried in the sidecar protocol's discovery, a minor version bump. Rust is here
  because a module can already declare a topic source, and a consumer that could not state its
  start position could not be registered at all.

**Versions**

- **FR-006**: A topic-sourced view MUST be able to declare a version, a positive integer; when
  the declared version is higher than the version recorded for its table, the runtime MUST
  truncate the table and record the new version in one transaction, so that it happens once
  however many instances start, and subscribe under a group id carrying the version from the
  declared start position.
- **FR-012**: Every write to a topic-sourced view's table MUST be conditional on the recorded version
  equalling the writer's declared version. An instance whose declared version is lower than the
  recorded one MUST stop reading the topic for that view, MUST NOT truncate, MUST keep serving
  the table and stay ready, and MUST log that the view is behind the recorded version.
- **FR-013**: A declared version that is not a positive integer MUST be refused at registration,
  naming the component.
- **FR-015**: A rebuild MUST NOT be refused for what the broker retains. Before truncating, the
  runtime MUST log the view, the version it replaces, the version it builds, and the timestamp of
  the earliest record the broker holds on each of the topic's partitions.
- **FR-007**: A topic-sourced consumer MUST be able to declare a version, which changes its group
  id alone.
- **FR-008**: The previous group's committed offsets MUST NOT be altered or reused.
- **FR-009**: A version on an entity-sourced view or consumer MUST be refused at registration,
  naming the component, so that it is not silently ignored.
- **FR-010**: The recorded version MUST be stored in the service's database, in an additive
  change to its schema.

**Visibility**

- **FR-016**: When a topic source subscribes, the runtime MUST log its component, topic, group
  name, start position and version.
- **FR-017**: A service's metrics MUST list each topic source with its component kind and id,
  topic, group name, start position and declared version, and MUST say for a view whether its
  declared version is lower than its recorded one. A service with no topic source lists none. A
  deployed service's metrics are its metrics endpoint; a local run, which has none, answers the
  same facts from the endpoint its local console reads. Until 026-telemetry-export, a deployed
  service's metrics endpoint admits only the service's own instances, so on the platform the log
  of FR-016 and FR-012 is what a person can read, and the documentation MUST say so.
- **FR-018**: Withdrawn. Showing topic sources in `services get` and on the console's page needs
  a channel from a running workload to the platform, which does not exist; it is a later spec's.

**Documentation**

- **FR-011**: The topics page MUST describe group names, start positions, versions and the
  retention bound; the limitations page MUST be rewritten for topic sources; the upgrading page
  MUST describe the group rename.

### Key Entities

- **Topic source**: a topic, a serializer, a start position.
- **View version record**: a view's component id and the version its table was last built at, a
  positive integer that only increases.
- **Consumer group name**: project, service, kind, component id, and optionally a version.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Two services on one broker reading one topic through views of the same component id
  each hold every message, measured by row count equal to the message count.
- **SC-002**: A consumer declared `latest` against a topic holding a month of messages processes
  zero of them on first start.
- **SC-003**: A view whose version is bumped holds, after rebuild, exactly one row per retained
  message and zero rows written by the previous version.
- **SC-004**: A restart with an unchanged version resumes from the committed offset and truncates
  nothing, measured by no message being delivered again and no row being removed. The row count
  alone is not the measure: a view read again from the start holds the same number of rows.

## Assumptions

- The broker is Kafka, reached as today through `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, until
  027-managed-broker.
- A deployed workload already holds its project and its service name: its certificate carries
  `ankka://<project>/<service>`. Nothing else tells it its project, and nothing needs to. A local
  run has no project, and a service name only when it states one.
- Existing deployments accept a one-time re-read on upgrade because their group's name changes;
  the alternative, migrating offsets between group names, is not worth its complexity.

## Dependencies

- Gates stage 4 of the domain plan, with 028-websocket-routes and 031-multi-source-views.
- 027-managed-broker assumes the qualified group id for its access rules; this ships first.
- 031-multi-source-views applies the version rule to views over several sources.
- The group-id fix depends on nothing and is built first, but is released with the start
  position, not ahead of it.
- A later spec, not yet written, builds the channel a running workload reports to the platform
  through, and shows topic sources in `services get` and the console with it.

## Open Questions

None remain. Two things were considered and left to later specs: the local console's topology
view showing a topic source's group and version, and `services get` and the console's page showing
them for a deployed service, which waits on a channel from a workload to the platform.
