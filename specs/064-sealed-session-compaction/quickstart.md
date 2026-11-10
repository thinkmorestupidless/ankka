# Quickstart: proving the fix

Docker is running (the kit suites share a Postgres container). JDK 21. No API key.

## 1. The bug, red on `main`

```bash
sbt 'testkit/testOnly *CompactionFeatures'
```

Before the fix, "a session started with a data subject past the compaction size is compacted as one
started with none" fails: the wait for a summary runs out, and the summariser's provider was never
called. The two scenarios with no data subject pass.

## 2. The fix, green

```bash
sbt 'agent/testOnly *SealingSuite *CompactionSuite'          # opened; what the compactor acts on; the warning
sbt 'testkit/testOnly *CompactionFeatures'                   # features/agents/compaction.feature, five scenarios
sbt 'testkit/testOnly *CompactionIntegrationSuite'           # the existing cases, unchanged, and the keyring outage
sbt 'controlPlane/testOnly *ErasureAgentsFeatures'           # features/erasure/agents.feature, six scenarios
```

Expected: every suite green; `CompactionFeatures` reports five scenarios and
`ErasureAgentsFeatures` six. A suite that reports zero tests has proved nothing: read the count.

## 3. What to look at

- The sealed session's history: one summary, then 10 messages (SC-001, SC-003).
- `assertNoPersonalValue` over the summary's text finds nothing in the database (SC-002).
- The failing-summariser scenario leaves one `WARN` naming the session and no line holding any of
  the session's text (SC-005).
- `CompactionIntegrationSuite`'s seven existing cases pass with no edit (SC-004).

## 4. The words and the pages

```bash
just features        # 0 findings
just docs            # every page checks, the site builds
```

## 5. Before the pull request

```bash
sbt scalafmtCheckAll compile
sbt -Dankka.cluster.tests=off test
```
