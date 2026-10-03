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
`ChangeSource.fromTopic(topic, serializer)` in Scala and `topic = "..."` in Python and TypeScript.
`KafkaSubscriber.subscribe(topic, groupId, handle)` in
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Kafka.scala` reads with a
committable source, hands each record to the handler one at a time, commits the offset to Kafka
after the handler returns, and restarts with backoff on failure. It sets `auto.offset.reset` to
`earliest`, which Kafka applies only when a group has no committed offset. Delivery is at least
once, and the limitations page says what follows: a topic-sourced component sees only what is
published after it starts, must tolerate duplicates, and cannot rebuild its state from history.

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
  built from variables the operator already injects into every workload, with the current form
  kept where there is no project, which is a local run. This is a defect with a real failure
  behind it and it is independent of everything else here; it ships first and alone if need be.
  An existing deployment's group changes name on upgrade, so its offsets start over under the new
  name, and the documentation says so.
- **A start position is declared.** A topic source names `startFrom`: `latest`, `earliest`, or
  a timestamp. It applies when the group has no committed offset; a restart resumes from the
  committed offset as today. The default stays `earliest` for a view and becomes `latest` for a
  consumer. [NEEDS CLARIFICATION: whether a consumer's default should change, which is a
  behaviour change for existing consumers whose group name is changing anyway]
- **A view declares a version.** When the version a running view declares differs from the one
  recorded for its table, the runtime truncates the table, derives a new group id carrying the
  version, and reads from `startFrom` again. The old group's offsets are left where they are and
  never reused. A consumer has no table to truncate, and its version changes only its group, which
  is how a consumer is deliberately made to re-read a topic.

What this feature is not: a way to make a topic a complete record, which only an entity's journal
is; a change to entity-sourced views, whose rebuild on deploy is a separate gap on the limitations
page; a rewind API for a running group, which the version mechanism replaces; and anything about
how a broker is provisioned, secured or reached, which is 027-managed-broker.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two services reading one topic each see every message (Priority: P1)

An operator runs two services on one broker. Each has a view over the same topic, and because the
same developer wrote both the views have the same component id. Every message published to the
topic appears in both services' views.

**Why this priority**: Today each sees about half, silently. This is a defect, not a feature, and
it is fixed before anything else in this series touches the subscriber.

**Independent Test**: Two `AnkkaTestKit` services with distinct project and service names and a
view of one component id, both subscribed to one topic on the testcontainers Kafka the topic suite
already starts; publish a hundred messages with distinct subjects; assert each service's view has
a hundred rows.

**Acceptance Scenarios**:

1. **Given** two services with distinct project and service names and a view with the same
   component id over one topic, **When** a hundred messages with distinct subjects are
   published, **Then** each service's view holds a hundred rows.
2. **Given** one service with two instances, **When** the same is published, **Then** the view
   holds a hundred rows, and the two instances between them consumed every partition once.
3. **Given** a service deployed on the platform, **When** its consumer group's name is read from
   the broker, **Then** it is `ankka.<project>.<service>.view.<componentId>` for a view and
   `ankka.<project>.<service>.consumer.<componentId>` for a consumer.
4. **Given** a service run locally with no project, **When** its group's name is read,
   **Then** it is the form used before this feature.
5. **Given** a service upgraded from a runtime that used the old group name, **When** it
   starts, **Then** it reads from its declared start position under the new name, and the
   documentation's upgrade note says this will happen.

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

**Acceptance Scenarios**:

1. **Given** a topic holding fifty messages, **When** a view declaring `earliest` starts for the
   first time, **Then** it holds fifty rows before any new message is published.
2. **Given** the same topic, **When** a view declaring `latest` starts for the first time and ten
   messages are then published, **Then** it holds ten rows.
3. **Given** the same topic, **When** a view declaring a timestamp between the twentieth and
   twenty-first message starts for the first time, **Then** it holds thirty rows, and forty after
   ten more are published.
4. **Given** a view that has committed an offset, **When** it is restarted with any start
   position, **Then** it resumes from the committed offset and the start position is not applied.
5. **Given** a Python service declaring a start position on a topic source, **When** it starts,
   **Then** the sidecar honours it, through the same cases as above in the conformance suite.
6. **Given** a view declaring no start position, **When** it starts for the first time, **Then**
   it reads from `earliest`, as today.

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

**Acceptance Scenarios**:

1. **Given** a view at version one with rows written, **When** the service restarts with the
   view at version two, **Then** the table holds no row the version-one handler wrote.
2. **Given** the same, **When** the rebuild completes, **Then** the table holds one row per
   retained message as written by the version-two handler.
3. **Given** the same, **When** the broker's groups are listed, **Then** a new group exists for
   the view carrying the version, and the previous group's committed offsets are unchanged.
4. **Given** a view restarted with the same version, **When** it starts, **Then** nothing is
   truncated and it resumes from its committed offset.
5. **Given** a consumer whose version is bumped, **When** the service restarts, **Then** the
   consumer reads from its start position under a new group and every retained message is
   delivered to it again.
6. **Given** a view over an entity, **When** its version is bumped, **Then** nothing changes;
   the version applies to topic sources only, and the limitations page still lists entity view
   rebuild as not built.
7. **Given** a rolling update where one instance runs version one and another version two,
   **When** both run, **Then** the version-two instance's truncation happens once, and the
   version-one instance's writes after it do not survive the roll. [NEEDS CLARIFICATION: the
   exact rule during a roll, whether version one keeps writing to the truncated table or is
   stopped]

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

**Acceptance Scenarios**:

1. **Given** the topics page, **When** a reader looks for start positions and versions, **Then**
   both are described with their defaults and the retention bound stated.
2. **Given** the limitations page, **When** a reader looks for topic sources, **Then** the entry
   says a rebuild is bounded by retention and that entity-sourced views do not rebuild yet.
3. **Given** the upgrading page, **When** a reader looks for consumer groups, **Then** it says
   the group name changed and what that means for an existing deployment.

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
- **A group id over Kafka's length limit.** Project, service and component ids are bounded by the
  descriptor's rules; the longest permitted combination is asserted to fit.
- **A consumer that throws on every message under `earliest`.** It stops at the first message
  and is redelivered it, as today; a start position changes where it stops, not whether.
- **The version recorded for a table is absent because the table predates this feature.** Treated
  as version one on first start, with no truncation.

## Requirements *(mandatory)*

### Functional Requirements

**Group ids**

- **FR-001**: A topic source's consumer group MUST be named by project, service, component kind
  and component id, from variables the platform injects, and MUST fall back to the current form
  when no project is known.
- **FR-002**: The group name's components MUST be bounded such that the longest permitted
  combination fits Kafka's group id length, asserted by a test.

**Start position**

- **FR-003**: A topic source MUST be able to declare a start position of `earliest`, `latest` or
  a timestamp, applied only when its group has no committed offset.
- **FR-004**: The default start position for a view MUST remain `earliest`. [NEEDS
  CLARIFICATION: the default for a consumer]
- **FR-005**: The start position MUST be declarable in Scala, Python and TypeScript, and carried
  in the sidecar protocol's topic source, a minor version bump.

**Versions**

- **FR-006**: A topic-sourced view MUST be able to declare a version; when the declared version
  differs from the version recorded for its table, the runtime MUST truncate the table, record
  the new version, and subscribe under a group id carrying the version from the declared start
  position.
- **FR-007**: A topic-sourced consumer MUST be able to declare a version, which changes its group
  id alone.
- **FR-008**: The previous group's committed offsets MUST NOT be altered or reused.
- **FR-009**: A version on an entity-sourced view or consumer MUST be refused at registration,
  naming the component, so that it is not silently ignored.
- **FR-010**: The recorded version MUST be stored in the service's database in an additive DDL
  change.

**Documentation**

- **FR-011**: The topics page MUST describe group names, start positions, versions and the
  retention bound; the limitations page MUST be rewritten for topic sources; the upgrading page
  MUST describe the group rename.

### Key Entities

- **Topic source**: a topic, a serializer, a start position.
- **View version record**: a view's component id and the version its table was last built at.
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
  nothing, measured by the row count being unchanged across the restart.

## Assumptions

- The broker is Kafka, reached as today through `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, until
  027-managed-broker.
- The project and service names are available to the runtime through the variables the operator
  injects today; a local run has neither.
- Existing deployments accept a one-time re-read on upgrade because their group's name changes;
  the alternative, migrating offsets between group names, is not worth its complexity.

## Dependencies

- Gates stage 4 of the domain plan, with 028-websocket-routes and 031-multi-source-views.
- 027-managed-broker assumes the qualified group id for its access rules; this ships first.
- 031-multi-source-views applies the version rule to views over several sources.
- The group-id fix depends on nothing and may ship as its own change ahead of the rest.

## Open Questions

- Whether a consumer's default start position should become `latest`, which is a behaviour
  change hidden inside the group rename.
- The rule during a rolling update between two versions of a view, per User Story 3 scenario 7.
- Whether a version bump should be refused, or warned about, when the broker's earliest retained
  offset is younger than the view's own table, which the runtime can only estimate.
- Whether the version should also be exposed on the local console's topology view beside the
  source it reads.
