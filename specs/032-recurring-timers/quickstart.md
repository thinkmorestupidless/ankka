# Quickstart: validating recurring timers

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[scala-api](contracts/scala-api.md), [protocol](contracts/protocol.md),
[sdk-apis](contracts/sdk-apis.md). Data: [data-model.md](data-model.md).

No step needs a k3s cluster; every `sbt` command switches those suites off.

## 1. Pure: the cadence and the period's rule

```bash
sbt -Dankka.cluster.tests=off 'runtime/testOnly *CadenceSuite' 'sdk/testOnly *TimerRulesSuite'
```

Expect: the next due one period on when the handler was quick; the first cadence point after now
when it was not, with `now` exactly on a point, a millisecond before and a millisecond after; a
period of zero, of less, and of under a millisecond each refused with the timer's name.

## 2. The defect, red and then green

On the base, before any change to the sweeper, with only the test and its steps written:

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *TimerFeatures -- "*a timer whose handler sets it again fires again*"'
```

Expect it to **fail**: the timer fires once and its row is gone. This is SC-001's first half and
the feature's first task. After the matched delete, the same command passes.

## 3. Timers on a real database

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *TimerStoreSuite *TimerFeatures *RecurringTimerFeatures *TimerRecoveryFeatures *TimerTestingFeatures *TimerUpgradeFeatures *TimerSuite'
```

Expect one test for each scenario of `features/timers/` but `languages.feature`, the statements of
the data model one by one, and `TimerSuite`'s seven existing cases unchanged. About three minutes: the
scenarios wait real periods and real backoffs.

To see it fail:

- make `Cadence.next` return `now + period`: the cadence scenarios and the slow-handler scenario
  go red;
- make the recurring upsert always write the first due: "set again … keeps its next due time"
  goes red;
- store a recurring timer with a finite `due_at`: the upgrade scenarios go red, because the
  legacy sweep fires it and deletes it.

## 4. A process and a module

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ProtocolSuite *RemoteProjectionSuite *WasmHostSuite'
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ConformanceSuite -- *timer.*'
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && ANKKA_CONFORMANCE_ONLY='*timer.*' uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && ANKKA_CONFORMANCE_ONLY='*timer.*' npm run conformance
cd sdks/rust && cargo test --workspace && ANKKA_CONFORMANCE_ONLY='*timer.*' ./conformance.sh
```

Expect `timer.fires` as before and the five `timer.recurring.*` cases, against the Scala
reference, each SDK's process and the Rust module in both guest shapes. Read the first line of
each conformance run: it says which target and shape it ran, and how many cases. A filter that
matched nothing reports the suite ignored and exits green.

## 5. Everything offline

```bash
sbt scalafmtCheckAll scalafmtSbtCheck
sbt compile                                  # warning-free
caffeinate -i sbt -Dankka.cluster.tests=off test
```

## 6. Documentation and features

```bash
just docs-sync && just docs
sbt -Dankka.cluster.tests=off 'testkit/testOnly *TimersDocumentationSuite'
just features
```

Expect the protocol table on `docs/reference/sidecar-protocol.md` to gain `ScheduleRecurring`, the
four documentation scenarios to pass, and the features check to report nothing for this spec.

## 7. By hand

**The previous runtime's own timer tests on the new schema.** The legacy fixture proves the old
statements; this proves the old binary.

```bash
git worktree add /tmp/ankka-v0.10.0 v0.10.0
cp kustomization/components/postgres/ddl/30-timers-postgres.sql /tmp/ankka-v0.10.0/kustomization/components/postgres/ddl/
(cd /tmp/ankka-v0.10.0 && sbt -Dankka.cluster.tests=off 'testkit/testOnly *TimerSuite')
git worktree remove --force /tmp/ankka-v0.10.0
```

Expect all seven of that version's cases to pass against a table with the three new columns.

**A local database from before the feature.** With a compose volume created on `main`, run a
service from this branch that sets a recurring timer. Expect a refusal that names
`30-timers-postgres.sql` and says to recreate the local database, not an SQL error, one warning in the
log saying the same, and timers that fire once still set and run. Then apply the file
(`docker exec -i <container> psql -U ankka -d ankka < kustomization/components/postgres/ddl/30-timers-postgres.sql`)
or `docker compose down -v && docker compose up -d`, and the recurring timer is set.

**A period on a laptop.** `docker compose up -d`, run a service that sets a recurring timer with a
period of ten seconds whose handler logs `dueTime`, and stop it for a minute. On restart expect
one fire, a log line on `ankka.timers` naming the skipped periods, and the next fire on the
original ten-second grid.
