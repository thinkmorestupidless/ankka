# Feature Specification: Topic Retention — How Long a Topic Keeps, How It Is Cleaned and How Many Copies It Has

**Feature Branch**: `043-topic-retention`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "A topic declaration says how many partitions a topic has and nothing
more. Retention is whatever the broker's default is, silently; nothing can be compacted, so a graph
consumer's topic is created by the pipeline that reads it; and every topic has one copy on one node.
Let a project's topic declaration say how long the topic keeps messages, how much it keeps, and how it
is cleaned (delete, compact, or both), within defaults and bounds the installation sets, with the
default shown in the topic's status rather than inherited from Kafka. Let it say how many copies the
broker keeps and how many must acknowledge a write, bounded by the broker's size, and let an
installation run a three-node broker. Keep 024's rule that a view rebuild is never refused, but show
the gap between what a topic holds and what was ever published in the view's status, not only in a
log line. Settings change without redeploying a service and every change is audited. Erasure of
personal data is 042's and is not done by retention. Out of scope: tiered storage, a broker per
project, and backing Kafka up."

## Context

Feature 027 gave the installation one broker and made topics the project's: a member declares a topic
on the project (`PUT /projects/{id}/topics/{name}`, `ankka projects topics set --partitions N`), the
`Project` entity records `ProjectTopicDeclared(name, partitions, actor, at)`, `ProjectTopicsTrigger`
writes the declarations as an `AnkkaProject` resource, and the operator's `ProjectReconciler` renders a
Strimzi `KafkaTopic` per declaration (`TopicProvisioning.topicsToRender`), named `<project>.<name>`.
The wire type is `TopicDeclarationRequest(partitions: Int)`; the CRD's entry is
`ProjectTopicEntry(name, partitions, declaredAt)`; `KafkaTopicSpec(partitions)` is the whole of what
is asked of Strimzi. Its comment says why replicas are left out: so that ankka never states a number
that has to match the installation's size.

What follows from that is three silent defaults.

- **Retention is Kafka's.** Neither the `KafkaTopic` nor the `Kafka` resource
  (`kustomization/components/broker/kafka.yaml`) sets `retention.ms`, `retention.bytes` or
  `log.retention.*`, so every topic keeps seven days, by Kafka's own default, and nobody on the
  platform can see that number or change it. The limitations page says so: "no retention, compaction
  or other topic setting".
- **Compaction is one boolean and nothing else is said.** Feature 037, merged after this spec was
  drafted, let a declaration say `compacted` (rendered as `cleanup.policy: compact`), so a graph
  consumer's topic is already the project's to declare. The tombstone window, the compaction lags
  and `compact,delete` are still Kafka's defaults, unseen; this feature generalises the boolean to a
  cleanup policy and keeps `compacted` as its short form. One thing 037 documented changes: a
  redeclaration that leaves `compacted` out no longer makes the topic keep every message again, since
  a setting left out of a redeclaration keeps its value (FR-002), and `compact` to `delete` is a
  removal an owner acknowledges (FR-007a).
- **There is one copy.** The broker component is one KRaft node that is both controller and broker,
  with `default.replication.factor: 1`, `min.insync.replicas: 1` and both internal topics at a factor
  of 1. The cloud overlay's `broker-size.yaml` can raise the node count, and its comment says the
  replication settings must be raised "to match" by a patch nobody has written. A topic made before
  that patch keeps one copy after it.

Feature 024 made a view over a topic rebuildable by raising its version, and decided a rebuild is
never refused for what the broker holds. Before emptying the table, the runtime asks the broker for the
earliest message it retains on each partition (`KafkaConnection.earliestRetained`) and logs it with the
view and both versions, a line beginning `view rebuild:`. That line is the only place a person learns
that a rebuilt view holds less than the old one did. The topology a deployed service reports over the
observe port (feature 019) already carries its topic sources to the local console's `topicSources`; the
messaging rule says `services get` is "a later change" away from them.

Feature 041 backs up project databases, the control plane's database and object storage, and not
Kafka. A topic is not a journal, and an entity's journal is what a view that must be whole is built
from (`docs/concepts/designing-services.md`). So the broker's durability is its replication and nothing
else, and an installation that runs one node has one copy of every message.

The shipped default of seven days is a laptop's. ankka's views default to reading a topic from its
earliest message, and a view that must be whole is built from an entity's journal, not a topic; but a
view that is rebuilt from a topic holding long-lived facts, as eitheror's partner-api and audit views
are, reaches only what the topic kept. An installation serving such facts raises the default and the
bound on its first day, and a view declared over a short-lived topic is told so.

This feature makes the five decisions those facts call for.

- **A topic's settings are said where its partitions are: on the project's declaration.** A declaration
  gains retention time, retention size and cleanup policy, and replication factor and minimum in-sync
  copies. Each is optional; one left out is filled from the installation's default *when it is
  declared*, recorded on the declaration, and shown in the topic's status. Nothing reaches the broker
  unsaid, so no topic inherits a Kafka default, and a later change to the installation's default
  changes no topic already declared, as 039's retention floor changes no existing bucket.
- **The installation sets defaults and bounds, and a declaration outside them is refused.** They are
  platform variables on the control plane, set by the installation's overlay. The shipping default is
  seven days, delete, and the copies the overlay states: one with one in sync on the one-node shape,
  three with two in sync on the three-node overlay. The bounds are a longest retention time (which may
  be unbounded), a largest retention size per partition, and a most copies. Refused means refused at the control plane, with the bound named,
  before anything is written to the cluster. The one bound the control plane cannot check is the
  broker's node count, which only the operator knows; copies above it are the operator's failure to
  report.
- **Replication is a topic's setting, fixed when it is declared, and an installation's shape.** A new
  installation can run a three-node broker from an overlay that states every replication setting the
  comment in `broker-size.yaml` asks a person to remember, the internal topics included, and that costs
  three times the storage. A topic declares how many copies it has and how many must acknowledge a
  write, and keeps both for its life: Strimzi changes a topic's copies only through Cruise Control, a
  pod of about a gigabyte and a metrics reporter on every broker, and this feature does not install it.
  Copies above the broker's node count are a failure the operator reports, as a partition shrink is
  today, because the operator is what reads the node count from the broker's own resources. The
  broker's one node is both controller and broker, so growing it to three is a change of the
  controller quorum, not a replica count; the three-node overlay is a shape for a new installation,
  and converting one already running is a platform administrator's own procedure, outside this
  feature.
- **What a rebuild reached is shown, not only logged.** The gap is a fact about each partition: the
  earliest offset the broker still holds and when it was published. A beginning position above zero, or
  an earliest retained time later than the view's start, says messages are gone; what was ever written
  is not knowable, and the report does not pretend to it. A view's topic source carries the gap in its
  metrics, in the topology it reports over the observe port, in the local console and in
  `ankka services get`. A rebuild is still never refused, but a view is warned when it is declared
  over a topic whose retention is short.
- **Changing a setting is a declaration, applied in place and attributed.** Declaring a topic again with
  other settings changes them on the broker without a service being redeployed or restarted, and the
  project records who changed what from what. Partitions keep 027's rule, never fewer. Copies and the
  minimum in-sync copies are fixed at declaration; a declaration that changes them is refused.

What this feature is not: erasure. A topic whose messages carry personal data carries it in fields 042
encrypts under a key per data subject, so destroying the key erases the field on every partition and
every copy whatever the topic's retention; retention decides how long a topic keeps messages, never
whether a person is forgotten, and a short retention is not offered as a way to comply with an erasure
request. It is not a backup of Kafka (041 decided against one), tiered storage, a broker per project,
or a change to who may read a topic, which is 027's ACL and 040's grants. A topic granted to another
project or to a machine outside the installation shows its settings to the grantee, because a reader
that does not know a topic keeps seven days cannot know what a rebuild will reach.

## Clarifications

### Session 2026-10-08

- Q: Who may lower a topic's retention, or change its cleanup from `compact` to `delete`, both of which
  destroy messages? → A: Only an owner of the organization, after confirming; the change is recorded.
  Every change that destroys no message needs only a member.

- Q: Where are a topic's retention and cleanup settings declared? → A: On the topic's declaration on
  the project, extending 027's route and CLI: retention time, retention size and cleanup policy
  (`delete`, `compact`, or `compact,delete`). The installation sets defaults and bounds; a declaration
  outside the bounds is refused.
- Q: What retention does a topic get when its declaration says none? → A: An explicit installation
  default, seven days as shipped, recorded on the declaration and visible in the topic's status, so
  nothing silently inherits the broker's default.
- Q: Is replication in this feature? → A: Yes. A declaration states a replication factor and a minimum
  of in-sync copies, bounded by the broker's size, and a new installation can run a three-node broker.
  Kafka is not backed up (041), so replication is its durability. (Narrowed in review: copies are fixed
  at declaration.)
- Q: What happens when a view rebuild starts after messages its topic has already dropped? → A: 024's
  rule stands: the rebuild is never refused. The gap between the earliest message retained and the
  first ever published is shown in the view's status, not only in a log line.
- Q: Which settings can change on an existing topic, and how? → A: Retention time and size, the
  cleanup policy, the tombstone window and the compaction lags, within the bounds. Copies and the
  minimum in-sync copies cannot change. Each change is a new declaration, applied without redeploying
  a service, and recorded in the project's history with its actor.
- Q: Is retention how personal data is erased from a topic? → A: No. 042 encrypts personal fields under
  a key per data subject and erases by destroying the key; retention plays no part in it.
- Q: Does a grantee of a topic (040) see its settings? → A: Yes: the retention, cleanup policy and
  copies of a topic granted to another project or a machine are shown wherever the grant is listed
  from the grantee's side.
- Q: Does changing the installation's default change topics already declared? → A: No. A default is
  copied into a declaration when the declaration is made. Raising or lowering an existing topic's
  setting is always a member's declaration, recorded with its actor.

### Session 2026-10-08 (review)

- Q: Can a topic's copies be changed after it is declared? → A: No. Copies and the minimum in-sync
  copies are fixed at declaration. Strimzi changes a topic's replication only through Cruise Control,
  which this feature does not install; a declaration that changes either is refused naming the rule. A
  topic declared before this feature keeps the copies it has.
- Q: Can an installation's single-node broker be grown to three nodes? → A: Not by this feature. The
  node is both controller and broker, so growing the pool changes the KRaft controller quorum. The
  three-node overlay is a shape for a new installation, and sets the internal topics' replication at
  install time; converting a running single-node broker is a platform administrator's manual
  procedure, out of scope.
- Q: Does every message ankka publishes carry a key? → A: No. The publisher keys a record by the named
  key or the message's subject, and a message with neither is written with no key, which a compacted
  topic refuses. So a publication to a topic declared compacted must carry a key, or it fails locally
  with a named error before anything reaches the broker; the runtime learns a topic's cleanup from its
  declaration.
- Q: Where is a declaration with more copies than the broker has nodes refused? → A: In the operator,
  as a `Failed` phase naming the node count, as a partition shrink is today. The control plane never
  sees the broker's size; it refuses only what exceeds the installation's declared bounds.
- Q: What does the gap report say? → A: Per partition, the beginning position and the earliest retained
  time. Messages are gone when the beginning position is above zero or the earliest retained time is
  later than the view's start. "The first offset ever written" is not knowable and is not claimed.
- Q: Is seven days the right default? → A: It is the shipped default and a laptop's. Views default to
  reading from the earliest message, so an installation serving long-lived facts raises the default and
  the bound on its first day, and a view declared over a topic whose retention is shorter than the
  installation's warning threshold (30 days as shipped) is warned in its status.
- Q: Are the tombstone window and the compaction lags per topic? → A: Yes. `delete.retention.ms`,
  `min.compaction.lag.ms` and `max.compaction.lag.ms` are fields of the declaration with installation
  defaults, since a graph sink (037) may need its own.
- Q: Does the publisher wait for every in-sync copy? → A: By default, yes, since Kafka 3.0; but the
  service's own producer configuration could override it. The runtime refuses to start a producer
  whose `acks` is below `all` when any topic it publishes to has a minimum of in-sync copies above one.

### Session 2026-10-08 (clarify)

- Q: Who fills the settings of a topic declared before this feature, and records that the platform did
  it? → A: The control plane, in one sweep on its first start after the upgrade: every declaration
  without settings is filled from the installation's defaults then in force and recorded on the project
  as the platform's act, not a member's; the operator then writes them onto the topic as for any
  declaration. Copies are left as the broker holds them (FR-008) and reported by the operator in the
  status. The operator cannot reach the control plane, so it fills nothing.
- Q: Does the control plane take part in an owner's confirmation of a change that removes messages, or
  is the confirmation the CLI's and the console's alone? → A: The route carries it. A declaration that
  removes messages names what it accepts removing; the control plane refuses one from an owner that
  does not, naming what would be removed, so a direct call cannot destroy messages by accident. The CLI
  and the console send the acknowledgement only once the owner has confirmed.
- Q: Where does a running service learn a topic's cleanup policy and minimum in-sync copies (FR-021,
  FR-022)? → A: From the broker's own configuration of the topic, read when the service first publishes
  to it and refreshed on an interval: it is what the broker enforces, it follows an in-place change
  with no restart, it needs no channel from the control plane, and it holds for a supplied broker. The
  declaration is what put the configuration there; the runtime never reads the declaration itself.
- Q: Where do the installation's defaults and bounds live, and how is one changed? → A: As platform
  variables on the control plane's deployment, set by the installation's overlay, as its issuer and
  organization-creation settings are today. The one-node shape ships one copy with one in sync and
  the three-node overlay three with two, so the default copies is the overlay's statement, never a
  guess at the broker's size. Changing a default or a bound is an overlay change and a control plane
  rollout; it changes no topic already declared (FR-002).
- Q: Is a view's retention warning re-evaluated when its topic's retention changes after the view is
  deployed? → A: Yes. The warning is a function of the topic's current declaration, recomputed whenever
  the topic is declared again or the service's descriptor is applied: lowering a topic below the
  threshold warns every view reading it, and raising it clears the warning.

### Session 2026-10-09 (analysis)

- Q: What does a setting left out of a *redeclaration* mean? → A: It keeps its current value. The
  installation's defaults fill a topic's first declaration only, so growing a topic's partitions, or
  changing one setting, never resets another to a default; and partitions may be left out of a
  redeclaration, since they have a value to keep. A default reaches an existing topic only when a
  member declares it.
- Q: Can copies be declared later for a topic declared before this feature, whose copies are the
  broker's? → A: No. Its copies and minimum in-sync copies stay unstated for its life and the listing
  shows what the broker holds; a declaration that states either is refused by the fixed-copies rule.
- Q: Is there a least-copies bound? → A: No. The bounds are the longest retention time, the largest
  retention size and the most copies; a "must be replicated" rule stays an open question.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A member says how long a topic keeps and sees it (Priority: P1)

A member declares `transactions` on the project `money` with 12 partitions and a retention of 90 days.
`ankka projects topics list -p money` shows it with 90 days, delete, and three copies, two of which must
acknowledge a write, the last two filled from the installation's defaults. A second topic, `notices`,
declared with partitions alone, shows seven days and says the value is the installation's default.
Neither topic has a setting anyone has to look up in Kafka.

**Why this priority**: This is the feature. A real-money installation must be able to say, and show, how
long a fact stays on its broker; today the answer is a number nobody on the platform states.

**Independent Test**: In the broker k3s suite, declare one topic with a retention and one without; read
the topic resources' configuration from the broker and the project's topic listing, and assert both say
the declared and the defaulted values, and that the broker's configuration for each topic names every
setting explicitly.

**Acceptance Scenarios**:

- added `features/broker/retention.feature`: a topic declared with partitions alone is filled from the installation's defaults
- added `features/broker/retention.feature`: a topic keeps a message until it is older than its retention time or its partition is larger than its retention size
- added `features/broker/retention.feature`: a declaration longer than the installation's longest retention time is refused
- added `features/broker/retention.feature`: a topic keeps everything where the installation sets no longest retention time
- added `features/broker/retention.feature`: a change to the installation's default changes no topic already declared
- added `features/broker/retention.feature`: a topic declared before a declaration could say its settings is filled by the control plane when it is upgraded

---

### User Story 2 - A project makes a compacted topic itself (Priority: P1)

A graph consumer in `casino` publishes deltas keyed by element. A member declares `graph` on `casino`
with cleanup `compact`. The broker keeps the latest message per key and removes a tombstone after the
topic's tombstone window. The pipeline reading it no longer creates the topic, and the limitations
page no longer says a project cannot.

**Why this priority**: A graph consumer's topic must be compacted, and today the only way to get one on
the installation's broker is outside ankka. 037 moves graph sinks into services, which reads a project's
own declared topics.

**Independent Test**: Declare a compacted topic, publish three messages under one key and one under
another, force the broker to clean the log, and read from the earliest offset: one message per key, the
latest.

**Acceptance Scenarios**:

- added `features/broker/cleanup-policy.feature`: a topic declared with the cleanup policy "compact" is compacted
- added `features/broker/cleanup-policy.feature`: a topic with the cleanup policy "compact,delete" keeps the last message under a key only within its retention time
- added `features/broker/cleanup-policy.feature`: a message published under no key to a compacted topic fails in the service before it reaches the broker
- added `features/broker/cleanup-policy.feature`: a deletion on a compacted topic is read for the topic's tombstone window
- added `features/broker/cleanup-policy.feature`: no message on a compacted topic is compacted away within its minimum compaction lag
- changed `features/graph-deltas/store.feature`: a topic its project declares compacted holds only element keys

---

### User Story 3 - A topic has more than one copy (Priority: P1)

A platform administrator installs a new cloud installation from the overlay for a three-node broker.
Every broker setting that counts copies, the two internal topics' included, says three, and the minimum
in-sync copies says two. A member declares `transactions` with partitions alone; it has three copies,
two must acknowledge each write, and stopping one broker node loses no acknowledged message and refuses
no publication. The topic's copies are what it was declared with for its life.

**Why this priority**: Kafka is not backed up. One node is one copy, and a real-money installation's
topics carry money facts between projects. Replication is the only durability the broker has.

**Independent Test**: In a k3s suite with the three-node overlay, declare a topic, publish a hundred
messages, stop one broker pod, publish a hundred more, and read two hundred from the earliest offset.

**Acceptance Scenarios**:

- added `features/broker/copies.feature`: an installation installed with three broker nodes sets every setting that counts copies for three
- added `features/broker/copies.feature`: a topic with more copies than the broker has broker nodes is reported failed by the operator
- added `features/broker/copies.feature`: stopping one broker node of three loses no acknowledged message
- added `features/broker/copies.feature`: a publication waits for the topic's minimum in-sync copies
- added `features/broker/copies.feature`: a topic on a broker of one broker node says it has a single copy
- added `features/broker/copies.feature`: a topic's copies and minimum in-sync copies are fixed when it is declared

---

### User Story 4 - A topic's settings change without a deploy, and the change is on record (Priority: P2)

A member raises `transactions` from 90 days to 180, and later lengthens its tombstone window. Neither
change redeploys or restarts a service. The project's history says who changed which setting, from
what to what, and when. Lowering the retention to 30 days removes what is older than 30 days at the
broker's next cleanup; the CLI and the console say so before the declaration is sent, and only an
owner may send it.

**Why this priority**: Retention set once and never changeable would have to be guessed right on the
first day. Changing it must be as safe and as visible as declaring it.

**Independent Test**: Declare a topic, change each setting in turn by declaring again, and after each
assert that the broker's topic configuration changed, that the publishing and reading services kept
their pods, and that the project's history has one attributed entry per change.

**Acceptance Scenarios**:

- added `features/broker/changing.feature`: a topic declared again with a longer retention time is changed on the broker in place
- added `features/broker/copies.feature`: a topic's copies and minimum in-sync copies are fixed when it is declared
- added `features/broker/changing.feature`: a change that removes messages is refused to a member who is not an owner
- changed `features/broker/changing.feature`: an owner is told what a shorter retention time removes and confirms before it is sent
- added `features/broker/changing.feature`: a declaration that removes messages without stating what it accepts removing is refused
- added `features/broker/changing.feature`: a change that removes no message needs only a member
- added `features/broker/changing.feature`: a topic declared again with the cleanup policy "compact" is compacted from then on
- added `features/broker/changing.feature`: a running service learns a topic's new cleanup policy from the broker without a restart
- added `features/broker/changing.feature`: a declaration that changes nothing records nothing

---

### User Story 5 - A view's status shows what its topic no longer holds (Priority: P2)

A developer raises the version of a view over `transactions`, which keeps 90 days and has been written
to for a year. The view is rebuilt from what the broker holds. `ankka services get` shows the view's
topic source with, per partition, the earliest offset the broker still holds and when it was published,
and says that the topic has dropped earlier messages. The console shows the same. The rebuild was not
refused.

**Why this priority**: The rule that a rebuild is never refused is right, and it is only safe if the
person who raised the version can see what the rebuild reached without reading a pod's log.

**Independent Test**: In an `AnkkaTestKit` suite with the testcontainers Kafka, publish to a topic with a
short retention, wait until the broker has removed a segment, raise a view's version, and assert the
topic source's reported gap: a partition whose beginning position is above zero, with its earliest
retained time. Assert the same in the topology the observe port returns. Separately, deploy a view over
a topic declared with two days' retention and assert the view's status carries the warning.

**Acceptance Scenarios**:

- added `features/topics/gap.feature`: a view rebuilt from a topic that no longer holds its earliest messages reports the retention gap for each partition
- added `features/topics/gap.feature`: a view rebuilt from a topic that still holds every message reports no retention gap
- added `features/topics/gap.feature`: a view rebuilt from a compacted topic reports the topic as compacted and not as having a retention gap
- added `features/topics/gap.feature`: the retention gap is shown wherever a service's topic sources are
- added `features/topics/gap.feature`: a view over a topic that keeps less than the warning threshold is warned in its status
- added `features/topics/gap.feature`: a topic lowered below the warning threshold warns the views reading it from the change on
- added `features/topics/gap.feature`: a topic raised above the warning threshold clears the retention warning
- added `features/topics/gap.feature`: a topic that keeps everything or is compacted draws no retention warning

---

### Edge Cases

- **A topic declared before this feature.** It has partitions and nothing else in its declaration and
  on the broker. On the control plane's first start after the upgrade, one sweep fills every such
  declaration from the installation's defaults then in force (seven days, delete, and the shipped
  tombstone window and compaction lags, as a declaration made that day would be filled) and records on
  the project that the platform filled them, not a member (FR-002a). The operator then writes them onto
  the topic explicitly, as for any declaration, which changes nothing the shipped broker does, and the
  status shows them marked as defaults. Copies are not filled: the topic keeps the copies it has
  (FR-008), and the operator reports them in the status from the broker. The operator fills nothing
  itself, because it cannot reach the control plane.
- **A broker grown from one node to three.** Out of scope. The node pool's roles are
  `[controller, broker]`, so raising its replicas changes the KRaft controller quorum, and Kafka does
  not raise an existing topic's copies when the default changes. The three-node overlay is a shape for
  a new installation; a platform administrator who converts a running broker does so by Strimzi's own
  procedure, and the topics guide says so and says what the existing topics keep. A topic's status on
  such a broker says it holds fewer copies than the installation's default, and that is all ankka
  does about it.
- **A broker shrunk below a topic's copies.** The operator reports every topic whose copies exceed the
  node count as failed, naming the count; the broker reports them under-replicated. Shrinking a broker
  is the installation's decision, and nothing in ankka refuses it.
- **Retention size and partitions.** Kafka's retention size is per partition. The declaration states it
  per partition and the status shows both per partition and the topic's total.
- **Compact without keys.** The publisher keys a record by the named key, else the message's subject,
  else nothing. A publication with nothing to a topic declared compacted fails in the service, named,
  before it is sent (FR-021); the runtime knows the topic's cleanup from the broker's configuration of
  the topic, read when it first publishes there and refreshed on an interval, so a topic declared
  compacted after the service started is refused keyless messages within that interval. A client
  outside ankka that writes a keyless record is refused by the broker.
- **A tombstone on a compacted topic.** It is removed after the topic's tombstone window, a field of
  the declaration filled from the installation's default; a reader that falls further behind than that
  window misses the deletion, and the topics guide says so.
- **A producer whose `acks` is below `all`.** Kafka's producer default is `acks=all` and the runtime
  does not change it, but a service's own configuration can. A service whose producer's effective
  `acks` is below `all` does not become ready when any topic it publishes to has a minimum of in-sync
  copies above one; the status names the property and the topic (FR-022).
- **A supplied broker.** A service whose descriptor names its own broker gets nothing from a
  declaration, as in 027; the settings apply to the installation's broker only.
- **An installation with no broker.** A declaration with settings is recorded and its topic reported
  failed naming the missing broker, as today.
- **A topic removed from the project and declared again.** It is recovered with what the broker holds,
  as 027 says; the settings of the new declaration are applied to it, and the status shows them.
- **A granted topic (040) whose retention is lowered.** The grantee's view of the grant shows the new
  value; nothing tells the grantee's services beyond that and their own topic source's gap.
- **Several instances of a view report the gap.** Each partition is read by one instance; the service's
  status merges the instances' reports by partition, as the topology already merges calls.
- **A retention lowered on a topic a view is reading.** The view is not rebuilt; it has the rows it
  read. Its topic source's gap changes on the next report, and if the new retention is below the
  warning threshold the view's status carries the retention warning from the change on (FR-020);
  raising it above the threshold clears the warning.

## Requirements *(mandatory)*

### Functional Requirements

**Declaration**

- **FR-001**: A topic's declaration on a project MUST accept, beside its partitions, a retention time
  (a duration, or "keep everything"), a retention size per partition (a size, or none), a cleanup
  policy (`delete`, `compact`, or `compact,delete`), a tombstone window, a minimum and a maximum
  compaction lag, a replication factor and a minimum of in-sync copies. Each MUST be optional on the
  route and the CLI.
- **FR-002**: A setting a topic's *first* declaration leaves out MUST be filled from the
  installation's default when the declaration is accepted, and recorded on the declaration with a mark
  that it was the default. A setting a *redeclaration* leaves out MUST keep its current value, so
  changing one setting never resets another. A later change to the installation's defaults MUST NOT
  change a topic already declared; a default reaches an existing topic only when a member declares it.
- **FR-002a**: On every start, the control plane MUST fill every declaration that has no settings from
  the installation's defaults then in force, in one sweep, recorded on the project as the platform's
  act with the time; a start that finds nothing unfilled MUST record nothing; copies MUST NOT be filled
  (FR-008). The operator MUST NOT fill a declaration, since it cannot reach the control plane.
- **FR-003**: The installation MUST state its defaults and bounds once, as platform variables on the
  control plane's deployment that the overlay sets, declared once beside the platform's other
  variables; changing one is an overlay change and a control plane rollout. They are: default retention time (seven days as shipped), default retention size (none),
  default cleanup (`delete`), default tombstone window and compaction lags, default copies and minimum
  in-sync copies, longest retention time (which may be unbounded), largest retention size per
  partition, most copies, and the retention below which a view is warned (30 days as shipped). The
  shipped defaults MUST be documented as a laptop's, with the instruction that an installation serving
  long-lived facts raises the default and the bound before its first topic is declared.
- **FR-004**: The control plane MUST refuse a declaration whose retention time, size or copies exceed
  the installation's declared bounds, or whose minimum in-sync copies is below one or above its copies,
  naming the rule, before anything is written to the cluster. The control plane does not know the
  broker's node count and MUST NOT claim to check it.
- **FR-004a**: The operator MUST report a topic whose copies exceed the broker's node count as failed,
  naming the count, with nothing created on the broker, as it reports a partition shrink today.
- **FR-005**: The operator MUST write every setting of a declaration onto the topic's resource
  explicitly, so the broker's configuration of the topic names each one and no topic takes a value from
  the broker's defaults.

**Change**

- **FR-006**: Declaring a topic again with other settings MUST change them on the broker in place,
  without redeploying or restarting any service.
- **FR-007**: Retention time and size and the cleanup policy MUST be changeable in either direction
  within the bounds. A change that removes messages (a shorter retention, a smaller size, or `compact`
  to `delete`) MUST be refused unless the actor is an owner of the organization, and confirmed as
  FR-007a says. Every other change needs only a member.
- **FR-007a**: The confirmation MUST reach the control plane: a declaration that removes messages MUST
  name what it accepts removing, and the control plane MUST refuse one that does not, naming what the
  change would remove, whoever the actor is. The CLI and the console MUST send the acknowledgement only
  once the owner has confirmed, and MUST NOT send it on a declaration that removes nothing.
- **FR-008**: A topic's copies and minimum in-sync copies MUST be fixed at declaration. A declaration
  that changes either MUST be refused at the control plane naming the rule, and MUST apply none of its
  other settings. A topic declared before this feature keeps the copies it has, unstated: the
  listing shows what the broker holds, and a declaration that states copies or minimum in-sync copies
  for it MUST be refused by the same rule.
- **FR-009**: Partitions MUST keep 027's rule: never fewer. A redeclaration MAY leave them out, in
  which case they are unchanged.
- **FR-010**: Every declaration that changes a setting MUST be recorded on the project with the actor,
  the time, and each setting's old and new value. A declaration that changes nothing MUST record
  nothing.

**Status**

- **FR-011**: A project's topic listing MUST show, per topic, its partitions, retention time and size,
  cleanup policy, copies and minimum in-sync copies, with each value the installation supplied marked
  as a default, and the phase.
- **FR-012**: A topic with fewer copies than the installation's current default MUST say so in its
  status, and a topic on a one-node broker MUST say it has a single copy.
- **FR-013**: A topic granted to another project or a machine (040) MUST show its settings wherever the
  grant is listed from the grantee's side. This lands with 040, which does not exist yet; nothing in
  this feature's plan implements it.

**Broker**

- **FR-014**: An overlay MUST provide a three-node broker for a new installation, in which the default
  replication, the minimum in-sync copies and both internal topics' factors are set for three nodes at
  install time, and the control plane's default copies and minimum in-sync copies (FR-003) are set to
  three and two by the same overlay; the one-node shape sets both to one. Its documentation MUST state that it holds three times the storage of the one-node
  shape, and that it is not a conversion of a running single-node broker. The operator MUST read the
  broker's node count from the broker's own resources.
- **FR-015**: Converting a running single-node broker to three nodes is out of scope: a controller
  quorum change the platform administrator performs by the broker's own procedure. The topics guide
  MUST say so and say what existing topics keep.

**Rebuild visibility**

- **FR-016**: A topic source MUST report, per partition, the beginning position the broker holds and the
  earliest retained time, and whether messages are gone, which is the case when the beginning position is
  above zero or the earliest retained time is later than the view's start; it MUST distinguish a
  compacted topic from one that has dropped messages by retention, and MUST NOT claim to know the first
  offset ever written. It MUST report this when it subscribes, when its view is rebuilt, and at an
  interval while it runs.
- **FR-017**: The report MUST be carried in the service's metrics, in the topology a deployed service
  returns over the observe port, in the local console's topic sources, and in `ankka services get`,
  merged across instances by partition.
- **FR-018**: A view's rebuild MUST NOT be refused for what the broker holds (024 FR stands).
- **FR-020**: A view declared over a topic whose retention time is below the installation's warning
  threshold MUST carry a warning in its status, naming the topic's retention and the threshold, in
  `ankka services get` and the console. A topic that keeps everything, or is compacted, draws none.
  The warning MUST follow the topic's current declaration: recomputed whenever the topic is declared
  again or the service's descriptor is applied, so that a retention lowered after the view was deployed
  warns it and one raised clears the warning.

**Publishing**

- **FR-021**: A publication to a topic declared `compact` or `compact,delete` whose message has neither
  a named key nor a subject MUST fail in the service, before anything is sent to the broker, with an
  error naming the topic and the rule. The runtime MUST learn a topic's cleanup policy and minimum
  in-sync copies from the broker's own configuration of the topic, read when it first publishes to the
  topic and refreshed on an interval, never from the declaration or from platform variables, so that an
  in-place change (FR-006) reaches a running service without a restart.
- **FR-022**: A service whose producer's effective `acks` is below `all` MUST NOT become ready when
  any topic it publishes to has a minimum of in-sync copies above one; the status MUST name the
  property and the topic.

**Documentation**

- **FR-019**: The topics guide, the broker page and the configuration reference MUST describe the
  settings, their defaults and bounds, how a setting is changed and what a shorter retention removes,
  that copies are fixed at declaration, the three-node overlay as a new installation's shape and its
  cost, that the shipped seven days is a laptop's default, that a compacted topic needs keyed messages,
  and that retention is not erasure (042). The limitations page MUST drop "no retention, compaction or
  other topic setting" and the graph consumer's topic being the pipeline's to create, and MUST add that
  a topic's copies cannot change and a single-node broker is not grown in place.

### Key Entities

- **Topic declaration** (extended): partitions, retention time, retention size per partition, cleanup
  policy, tombstone window, minimum and maximum compaction lag, copies and minimum in-sync copies,
  each marked as declared or defaulted; copies and the minimum are fixed once declared.
- **Installation topic defaults and bounds**: the defaults a declaration is filled from, the bounds it
  is checked against, and the retention below which a view is warned; platform variables on the
  control plane that the installation's overlay sets.
- **Topic setting change**: an entry in the project's history naming the topic, the actor, the time and
  each changed setting's old and new value.
- **Removal acknowledgement**: on a declaration that removes messages, the actor's statement of what it
  accepts removing, which the control plane requires before applying the change.
- **Retention gap**: per partition of a topic a source reads, the beginning position and the earliest
  retained time, and whether messages were dropped or compacted.
- **Retention warning**: on a view's status, the topic's retention against the installation's
  threshold.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On every installation, every declared topic's retention, cleanup policy and copies can be
  read from the platform, and none of them is a value the broker supplied unstated.
- **SC-002**: A project can make a compacted topic with one declaration and no tool outside ankka.
- **SC-003**: On the three-node overlay, stopping any one broker node loses no acknowledged message on a
  topic with three copies and a minimum of two, and refuses no publication to it.
- **SC-004**: Every change to a topic's settings appears in the project's history with its actor, and
  no change restarts a service.
- **SC-005**: A person who rebuilt a view can learn what the rebuild reached from `ankka services get`
  or the console, without reading a log.
- **SC-006**: A publication without a key to a compacted topic never reaches the broker, and the
  service's error names the topic.
- **SC-007**: A view declared over a topic that keeps less than the installation's threshold shows the
  warning in `ankka services get` on the first status read after an instance has reported its topic
  sources.

## Assumptions

- Strimzi's topic operator applies a `KafkaTopic`'s `config` to the topic in place. It changes a
  topic's replication factor only through Cruise Control, which is why copies are fixed at
  declaration; Cruise Control is not installed by this feature.
- The runtime's publisher waits for every in-sync copy to acknowledge a write by default (Kafka's
  producer default since 3.0); the runtime does not override it, but a service's own producer
  configuration could, which FR-022 refuses. The connection's variables from the environment carry
  only the broker's address and credential.
- `earliestRetained` is the existing call the gap report extends: it reads `beginningOffsets` and
  discards them, and yields the earliest retained time per partition; the report surfaces both.
- The three-node overlay is installed fresh; growing the shipped combined-role node pool in place is a
  controller quorum change the feature does not perform.
- The observe port's topology (019) can carry a topic source's report; the local console already shows
  topic sources.
- A topic's default copies on the shipped one-node broker is one, and the shipping default for an
  installation with three nodes is three copies with a minimum of two in sync; each overlay states its
  own, as the control plane's platform variables.

## Dependencies

- 027-managed-broker and 024-replayable-topics: this feature extends 027's declaration and 024's rebuild
  report.
- 040-cross-project-access: a granted topic's settings are shown to its grantee (FR-013). Neither
  feature waits for the other; the grantee's listing gains the settings when both are in.
- 041-postgres-backup-recovery: decides Kafka is not backed up, which this feature answers with
  replication.
- 042-personal-data-erasure: erasure of personal fields on topics is by key, not by retention.
- 037-pipelines: a graph sink reading a compacted topic its project declared.

## Open Questions

- Whether a project should be able to mark a topic "must be replicated", so a declaration with one copy
  is refused even where the installation allows it; nothing in this feature needs it yet.
- Whether a later feature installs Cruise Control to let copies change; nothing in eitheror's plan
  needs it, since a real-money installation is installed on the three-node shape from the start.
