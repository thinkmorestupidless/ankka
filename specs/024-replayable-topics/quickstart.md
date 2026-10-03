# Quickstart: validating Replayable Topic Sources

How to see each user story hold, from this repository. Docker is required. Contracts are in
[contracts/](contracts/); the scenarios these runs prove are under `features/topics/` and
`features/documentation/`.

## The fast loop

```bash
sbt 'runtime/testOnly *ConsumerGroupsSuite *TopicSourceRulesSuite *ServiceIdentitySuite *InMemoryBrokerSuite'
sbt 'testkit/testOnly *TopicSourceSuite *ViewVersionSuite'
```

The first line is pure: group names for every form, the rules T1 to T5, an identity read from a
certificate and from configuration, and the in-memory broker's groups. The second starts Postgres
and no broker: start positions, a restart resuming, a rebuild, a view left behind, and the race
between a rebuild and a write.

Expect, in `ViewVersionSuite`, a case that runs a version-1 writer against a version-2 rebuild
repeatedly and finds no version-1 row afterwards. To see it can fail, remove the shared lock from
the guarded write and run it again.

## User Story 1 — two services each see every message

```bash
sbt 'testkit/testOnly *KafkaSuite'
```

Starts one Kafka and two services with one view id between them. Expect:

- each service's view holds a row for every one of a hundred messages;
- the broker lists `ankka.shop.orders.view.<id>` and `ankka.shop.billing.view.<id>` as two
  groups, read back with an `AdminClient`, not from ankka's own log;
- with two instances of one service, every partition of the topic is assigned to exactly one;
- a subscription under the longest permitted group name is accepted.

Two local services by hand, against any Kafka:

```bash
ANKKA_SERVICE_NAME=orders  ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 sbt 'yourService/run'
ANKKA_SERVICE_NAME=billing ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ANKKA_HTTP_PORT=9001 sbt 'yourOtherService/run'
kafka-consumer-groups --bootstrap-server localhost:9092 --list     # ankka.local.orders.… and ankka.local.billing.…
```

## User Story 2 — a start position

`KafkaSuite`, same run. Expect, over a topic holding fifty messages with the last thirty after a
known time: a view at `earliest` holds fifty rows; at `latest`, none until ten more are published
and then ten; at that time, thirty and then forty. And the case that separates a committed start
from a reset: a `latest` view that has read nothing is stopped, ten messages are published, it is
restarted, and it holds ten.

A consumer over a topic with no start position:

```bash
sbt 'runtime/testOnly *TopicSourceRulesSuite'       # Scala and discovered components, one function
sbt 'sidecar/testOnly *ProtocolSuite'              # the process is told, with its other problems
cd sdks/python && uv run pytest -q -k start_from    # and each SDK says so itself
cd sdks/typescript && npm test -- --test-name-pattern 'start'
cd sdks/rust && cargo test -p ankka start_from
```

Every language against the real sidecar:

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- *topic.*'
cd sdks/python && uv run conformance
cd sdks/typescript && npm run conformance
cd sdks/rust && ./conformance.sh
```

Read what each run says it ran. The munit filter needs its leading `*`, and a filter that matches
nothing reports the suite as ignored and exits green.

## User Story 3 — a version

`ViewVersionSuite` and `KafkaSuite`. Expect:

- a view restarted at version 2 holds only rows the version-2 handler wrote, one for each
  retained message, and the service's log has line L2 before the truncation, with a timestamp for
  each partition;
- on the broker, a group `…view-v2.<id>` beside `…view.<id>`, and the first group's committed
  offsets exactly as they were;
- restarted again at version 2, nothing is delivered twice and nothing is emptied;
- restarted at version 1, the view keeps its version-2 rows, reads nothing, the service is ready,
  the log has line L3 and `ankka_topic_source_behind` is `1`;
- two instances started together at version 2 empty the table once: one L2 followed by a
  truncation, one L4.

A consumer:

- deployed at version 2 it is delivered every retained message again, under `…consumer-v2.<id>`.

Refusals, in `TopicSourceRulesSuite`: a version on a view over an entity, on a consumer over an
entity, and version 0.

What the service shows:

```bash
sbt 'runtime/testOnly *MetricsSuite'
curl -s "$(jq -r .observabilityAddress ~/.ankka/running/*.json | head -1)/observability/service" | jq .topicSources   # a local run
```

## User Story 4 — the documentation

```bash
just docs-sync      # the configuration table gains ankka.service.name
just docs           # fails until the prose beside the table mentions ANKKA_SERVICE_NAME
```

Then read three places: `docs/build/topics.md` for group names, start positions, versions and
the retention bound; `docs/reference/limitations.md`, whose topic entry no longer says "cannot
replay"; `docs/deploy/upgrading.md` for the group rename, the consumer's start position and the
cut-over recipe.

## The templates

```bash
sbt -Dankka.template.tests=python,typescript,rust 'cli/testOnly *TemplateSuite'
sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'
```

Each rendered project states its service's name: the compose file's `ANKKA_SERVICE_NAME` for the
three polyglot templates, `ankka.service.name` in `application.conf` for Scala.

## Everything

```bash
sbt -Dankka.cluster.tests=off test     # about a minute more than before this feature
just docs
uvx --from "git+https://github.com/thinkmorestupidless/speckit-bdd@v0.2.0#subdirectory=checker" \
  speckit-bdd check --root . --glossary GLOSSARY.md --features features --specs specs --specs-from 019
```

The k3s suites are unchanged by this feature and prove nothing about it: no cluster suite runs a
broker. A deployed service's group name is first read off a real broker in 027-managed-broker.
