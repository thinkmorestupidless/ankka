# Quickstart: proving the feature

Prerequisites: Docker with Compose v2 running, JDK 21, sbt, `uv` (for `just features`). Run long
steps under `caffeinate -i`.

## 1. The offline suites (no Docker)

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off -Dankka.local.tests=off \
  'core/testOnly *TopicDeclarationsSuite *ContextMapSuite' \
  'cli/testOnly *LocalFileSuite *LocalPlanSuite *LocalCommandSuite *DiscoverySuite *CliReferenceSuite' \
  'proxy/testOnly *LocalModeSuite' 'controlPlane/testOnly *ProxyEnvironmentSuite' \
  'testkit/testOnly *ProjectsDocumentationSuite'
```

Expected: green. `LocalPlanSuite` holds every scenario of
`features/projects/local-environment.feature` and SC-006 (three problems, one run, nothing
started); `LocalCommandSuite` holds what is printed and that no local value is.

## 2. The command against real Docker, with the stand-in image

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off -Dankka.local.tests=on 'cli/testOnly *LocalUpSuite'
```

Expected: every scenario of `local-up.feature`, `local-topics.feature` and
`local-companions.feature` as a test of its name (the two about a service run from its source
with the stand-in run on the host; the real runtime's half is §3), green, and no
container, network or volume named `ankka-shop-*` left when the suite ends. With Docker stopped
the same command fails, naming Docker; it does not skip.

## 3. A real project, end to end

```bash
caffeinate -i sbt -Dankka.cluster.tests=off -Dankka.template.tests=project 'cli/testOnly *ProjectTemplateSuite'
```

Expected: the rendered project holds `topics.json` and no `docker-compose.yml`, `scripts/run.sh`
or `scripts/databases.sh`; after its images are built, `ankka local up` and nothing else answers a
request to the first service that calls the second (SC-001); the two use different databases;
what was recorded survives `down` and `up` and is gone after `down --volumes`; with the second
left out and run by `ankka local run`, the request is answered, and likewise the first; the
console lists both.

## 4. Topics sent to an installation

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off 'controlPlane/testOnly *TopicsApplySuite'
```

Expected: `ankka projects topics apply` declares the file's topics through the real route, a
second run says `unchanged`, and a change that removes messages asks first.

## 5. The checker, the reference and the pages

```bash
just features          # 0 findings
just docs-reference    # rewrites the CLI reference; then nothing to commit
just docs-sync && just docs
bash cli/native-smoke.sh <the built binary>   # after sbt cli/GraalVMNativeImage/packageBin
```

Expected: green; the smoke test shows the native image carries the schema files and that
`ankka local up` in a directory with no project prints the refusal.

## 6. By hand, on a Mac (the path CI does not run)

```bash
cd "$(mktemp -d)" && ankka init shop --project --services orders,billing && cd shop
sbt Docker/publishLocal
ankka local up
curl -s localhost:9000/...            # the template's route that calls billing
ankka local status
ankka local console --no-open         # lists orders and billing, and the call between them
ankka local up --without billing      # says: ankka local run billing; restarts nothing
ankka local run billing               # in a second terminal; the request is answered again
ankka local logs orders --tail 20
ankka local down && ankka local up    # what was recorded is still there
ankka local down --volumes
```

Then a failure of each kind once, to see the message: stop Docker; occupy port 9001; remove the
topic from `topics.json`; add a `secretKeyRef` variable with no local value.

## 7. The measurements (SC-002 to SC-004)

A project of six Scala services made from the template (`ankka init` four more inside it), with
one companion declared, on the 10-core 32 GB machine:

1. images present; `time ankka local up` (SC-003: within five minutes); again with nothing changed
   (within thirty seconds);
2. ten minutes idle, then `docker stats --no-stream` summed over the compose project (SC-002:
   within 8 GiB);
3. a four-core load for ten minutes beside it (`stress-ng --cpu 4 --timeout 600` or the build of
   this repository), then `ankka local status` and a read of what was recorded (SC-004: every
   service ready, nothing lost).

Recorded in `specs/066-local-up/runs/measurements.md` with the date, the machine and the Docker
runtime. A figure missed is a question for the person, not a figure quietly changed.
