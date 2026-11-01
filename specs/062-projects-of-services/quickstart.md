# Quickstart: proving projects of several services

How the feature is proven, from the fast suites to a generated project end to end.

## Prerequisites

- JDK 21, sbt (1.12 or 2.0 launcher), Docker (OrbStack on the dev Mac), `uv` (for the docs tool
  and a Python service), `git`.
- `sbt publishLocal` is run by the template suites themselves (`templateArtifacts`).
- Optional: `specify` on PATH to see `ankka init --project` install spec-kit; without it the
  command prints the step.

## 1. The context map's logic

```bash
sbt 'core/testOnly *ContextMapSuite'            # parse, every static problem's message, the rendering
sbt 'testkit/testOnly *ContextMapCheckSuite'    # a service's stated topics against a map, over two small services
```

Expected: green; `ContextMapSuite` has one test per row of contracts/context-map.md's problem
table, each producing exactly that line.

## 2. The command line, fast

```bash
sbt 'cli/testOnly *InitSuite'
```

Expected: `--project` without `--services` refused; one name refused; `shared` refused; `--add`
outside a project refused with the scenario's message; a project detected by `context-map.json`;
the next free port 9002 after 9000 and 9001; the `specify init` line printed when `specify` is
not on PATH (scripted runner).

## 3. A generated project, end to end

```bash
sbt -Dankka.template.tests=project 'cli/testOnly *ProjectTemplateSuite'
```

What the suite does, in order, and what each step proves:

1. `ankka init shop --project --services orders,billing` into a temp directory: the layout of
   contracts/project-layout.md; descriptors named for their directories with ports 9000 and 9001;
   `context-map.json` with the two services and the crossing; `CONTEXT-MAP.md` in step.
2. `sbt test` in the project: both services' suites and the tests module pass; the tests module's
   `OrderInvoicedSuite` starts two kits on one broker (R7) and `ContextMapSuite` is green.
3. Break billing once (the suite edits the consumer to invoice nothing) and `sbt tests/test` fails
   on `OrderInvoicedSuite`; restore.
4. `sbt orders/Docker/publishLocal` makes `orders:latest` and no `billing` image.
5. In the temp repository, `git tag orders/v1.4.2` then a commit, `git tag billing/v3.1.0`:
   `sbt 'show orders/version'` starts `1.4.2+`, never `3.1.0`.
6. `ankka context-map check` passes; then fails with the expected line after each of: removing
   `services/billing`, adding `shipping` to the map, renaming the schema file, editing
   `CONTEXT-MAP.md`; each restored.
7. `ankka init stock --language python` inside the project: `services/stock/` from the Python
   template without the project-owned parts, port 9002, the map's third entry, the rendering
   updated, nothing else changed (`git status` names only those paths).
8. `scripts/changed-services.sh` over synthetic diffs: `services/orders/x` → orders alone (tests
   true); `shared/x` → orders, billing, tests; `services/stock/x` → stock (tests false);
   `README.md` → nothing.
9. `scripts/service-language.sh` on each service directory.
10. `ankka init stock --add` in an empty directory refuses: `… holds no project started from the
    project template (no context-map.json); nothing was written`, and the directory is still empty.
11. The workflows: `${{`/`}}` balanced; `deploy.yml` triggers on `'*/v*'`, names
    `secrets.ANKKA_TOKEN`, `ankka-action@`, `services deploy "$SERVICE"`, the language groups;
    `ci.yml` names no secret; `service-checks.yml` is `workflow_call` with the two inputs.

Expected: all green in under fifteen minutes (the suite's `munitTimeout`). With `sbt` or Docker
missing, the suite fails naming the tool, never skips.

## 4. Running the project by hand (what SC-002 measures)

```bash
ankka init shop --project --services orders,billing && cd shop
docker compose up -d                       # postgres and kafka
scripts/run.sh orders &                    # database "orders" made, port 9000
scripts/run.sh billing &                   # database "billing", port 9001
ankka local console                        # lists orders and billing
curl -X POST localhost:9000/orders -d '{"id":"o-1","total":10}'
curl localhost:9001/invoices/o-1           # the consumer read orders-placed and invoiced
```

Then, with a coding agent and the `ankka-inspect` skill: "check the scenario an order placed is
invoiced" reports it met with the request, the producer and consumer spans joined by one trace id
(`local_traces` on orders, then the same id on billing), the declared query's answer, and the
crossing `orders → billing by orders-placed`; `kill` billing's process and ask again: not met,
naming billing.

## 5. The skills and the pages

```bash
just docs-sync && just docs                # skills re-rendered; every page in nav and in a skill
sbt 'testkit/testOnly *ProjectsDocumentationSuite'
just features                              # 0 findings
```

Expected: `docs check` passes (the two new pages, the CLI reference's two sections after
`just docs-reference`); `ProjectsDocumentationSuite` has one test per scenario of
`features/projects/designing.feature`, `specifying.feature` and
`features/documentation/projects.feature`, asserting what the skill and the pages say.

## 6. The design skill, recorded (SC-001)

Three domain descriptions under `specs/062-projects-of-services/runs/`, each given to an agent
with only the rendered skills; the context map each produced is kept beside it. Expected: every
crossing names a contract or a route; the description with a pair that must be consistent
together has that pair as one service; the description that needs no boundary has no map.

## 7. The pull request

CI's `changes` job maps the new template directory and the new suites onto `build` and a new
`template-project` job; `.github/ci-coverage.py` claims every new file. The `cli-native` job's
smoke script renders a project from the native binary.
