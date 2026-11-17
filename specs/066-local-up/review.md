# Review: feat(066): a project started on one machine with one command (`ankka local up`)

**Verdict:** Request changes
**What it does:** Adds `ankka local up | down | status | logs | run`. The command reads the context map, each descriptor, a new `topics.json` and a new `local.json`, checks everything it can, and renders one compose document from a pure `LocalPlan`. One `Engine` seam then drives `docker compose` to run one Postgres, one Kafka with the declared topics, the companions and every service from its image in its hosting's shape. It also adds `ankka projects topics apply` for the same `topics.json`, holds the context map's topic crossings to that file, gives the proxy image a plain local mode, and moves the project template from its compose file and scripts to the command.
**Should it exist:** Yes. Spec 066 sets out the need: six terminals and six builds for six services, topics made with the broker's defaults, and nothing for a project made outside the template. The change builds on what exists: `ProxyEngine`, `ControlPlaneClient.declareTopic`, `PlatformVariables`, the console's entry files and the canonical DDL by glob. It does not add a second way to do any of these.
**Read:**
- **Read in full:** spec.md, plan.md, tasks.md, `REVIEW.md`, `CLAUDE.md`, `.claude/rules/control-plane.md` (its "A project on one machine" section and the CLI traps), `.claude/rules/testing.md`, and the Schema section of `kubernetes.md`.
- **Read in full, code:** `cli/local/` `LocalPlan`, `LocalUp`, `LocalRun`, `LocalState`, `LocalProject`, `LocalFile`, `LocalVariables`, `Companions`, `Compose`, `Engine`, `Schemas`, `BrokerTopics`, `ConsoleEntries`, `LocalDown`, `LocalStatus`, `LocalLogs`, `LocalSeams` and `SourceCommands`. Also core `TopicDeclarations` and the `ContextMap` diff, `TopicsCommand`, the `Main` and `Projects` diffs, the `LocalSource` diff, the proxy's `Main` and `LocalLocator`, the build, CI and `native-smoke.sh` diffs, the template diffs, and every changed page under `docs/`.
- **Read in part:** the tests. I read `LocalUpSuite`'s source-run fixture, the `--without` cases of `LocalCommandSuite` and `ProxyEnvironmentSuite`'s new case. For the rest I checked that each scenario of the new features has a test with its name, and every one has.
- **Skimmed (generated):** the rendered skills under `ankka.g8/src/main/g8/.claude/skills/` and `marketplace/plugins/ankka/skills/`, and the generated block in `docs/reference/cli.md`.
- **Opened outside the diff:** `PlatformVariables`, the operator's proxy env (`Rendering.ProxyEnv`), the SDKs' `ANKKA_PROCESS_PORT` binds (Python, TypeScript, Go), `modules/http` `reference.conf`, the DDL directory, `RetentionTime`, the Java, Kotlin, Rust, TypeScript and web templates (to check each default source command), the old `scripts/run.sh` at `origin/main`, `docs/platform/databases.md`, `docs/deploy/upgrading.md` and `tools/docs/skill/ankka-inspect/SKILL.md`.
- **Not reviewed:** the research.md text beyond R9 and the Linux notes, and data-model.md and the contracts line by line. I compared refusal wording only where the code and the docs say it.

**Size:** 123 hand-written files, +11128 −431 lines. That is far past the point where finding defects by reading falls off. The 45 task-sized commits make it readable, and I would not ask for a split now. The split I would have accepted is:
1. `topics.json`, the check and `topics apply`.
2. `local up` and `down` for embedded services.
3. The other hostings and the proxy's local mode.
4. The template and the docs.

The findings below lean toward the paths the offline suites do not reach: hostings other than embedded, `--without` and `run` for them, and Linux.

## Blocking

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:204` — issue (blocking): a service whose descriptor reads an `ANKKA_DB_` or `ANKKA_KAFKA_` variable from a secret cannot be brought up at all.
  - **What happens:** `valueProblems.secrets` refuses each `secretKeyRef` variable that has no local value. `owned` then refuses a local value for any name `LocalVariables.isCommandOwned` covers, and `ANKKA_DB_` and `ANKKA_KAFKA_` are among them. Either way the command refuses.
  - **Why it matters:** `docs/platform/databases.md:160` documents exactly this descriptor for a supplied database: `{"name": "ANKKA_DB_PASSWORD", "secretKeyRef": …}`. A broker credential from a secret has the same shape. SC-008 promises that a project made outside the template comes up with no change to any service. The literal form of the same variables is already dropped with a notice (`isInstallationOnly`, and the control-plane trap of 2026-11-17); the secret form needs the same treatment.
  - **Suggestion:** filter the secret check the same way, and name the dropped variables in the same notice:
    ```scala
    case e if e.secretKeyRef.isDefined && !values.contains(e.name) &&
              !LocalVariables.isInstallationOnly(e.name) => …
    ```
    Add a `LocalPlanSuite` case with an `ANKKA_DB_PASSWORD` read from a secret.
  - Resolved: a descriptor's `ANKKA_DB_`/`ANKKA_KAFKA_` variable read from a secret is dropped with the same notice as a literal, and needs no local value; plan case added, 938c5a230

## Should fix

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/Compose.scala:62` — issue (non-blocking): values go into the compose document without escaping `$`.
  - **What happens:** Compose interpolates `$VAR` and `${VAR}` in every string of the file, environment values included, and in JSON as well as YAML. A local value, a descriptor literal or a companion's `env` that holds `$` reaches the container changed, or fails the whole `up` with a compose interpolation error. Examples: a bcrypt hash, a regex, `JAVA_TOOL_OPTIONS` naming `$HOME`.
  - **Why it matters:** the developer is shown neither the value nor the reason, by design (FR-020).
  - **Suggestion:** write each environment value, command word and label with `$` doubled (`$$`) when the document is rendered. Add a plan case with a value holding `$`.
  - Resolved: the rendered document doubles every `$` in commands, entrypoints, environment values, healthchecks and labels; plan case added, 66af4ecd5

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:327` with `LocalUp.scala:155` — issue (non-blocking): `--without` stops only the first container of a process-hosted or web-hosted service.
  - **What happens:** `containers` is filled for `selected` only, so `containersOf(plan.leftOut)` falls back to `Vector(name)`. `up --without billing` therefore removes `billing` (the sidecar, or the proxy) and leaves `billing-app` running in the network namespace of a container that no longer exists. `--remove-orphans` does not catch it, because every service stays in the document.
  - **Why it matters:** the one `--without` test (`LocalCommandSuite`, "a service left out of a project that is up is stopped …") is embedded-only.
  - **Suggestion:** fill `containers` for every service of the project, or compute `containerNames` for `leftOut` too. Add a scripted case for a process service left out, which expects `stop ankka-shop billing billing-app`.
  - Resolved: the plan lists the containers of every service, so `--without` stops a process or web service whole; scripted case expects `stop ankka-shop billing billing-app`, 68cd098e9

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalStatus.scala:41` — issue (non-blocking): a web-hosted service is always reported as "running, not ready".
  - **What happens:** status asks the observability address chosen in `state.json`. The proxy serves none there, and the web compose service does not even publish that port. `LocalUp` asks a web service's readiness with `ReadyCheck.Port(spec.port)`.
  - **Suggestion:** reuse the plan's ready check per hosting, so `up` and `status` cannot disagree. Add a status case for a web service.
  - Resolved: `LocalPlan.readyCheck` is the one ready check for `up` and `status`, so a web service is ready when its port answers; status case added, 95e316bf1

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Projects.scala:166` — issue (non-blocking): this breaks a project made from the earlier template once it adds a non-Scala service. FR-025 and FR-031 say running one service from source in such a project keeps working.
  - **What happens:** `ankka init --service x --language python` (or TypeScript, Go, Rust) no longer writes the service's `docker-compose.yml`. The old `scripts/run.sh` reads that file both to start the sidecar or runtime and to export `ANKKA_PROCESS_PORT`. `scripts/run.sh x` in an old project therefore starts the process with no sidecar. `addSteps` now prints `ankka local run x`, which works only once the project has moved to the command.
  - **Why it matters:** `docs/build/projects.md:161` tells such a project it "can keep" its scripts.
  - **Suggestion:** pick one. Either keep writing the per-service compose file when the project root still holds `scripts/run.sh` or a root `docker-compose.yml`, or say on `projects.md` that a service added after the upgrade runs only with `ankka local`.
  - Resolved: the second option. projects.md says a Python, TypeScript, Go or Rust service added to an earlier project runs only with `ankka local`, 7ea4b053a

- `tools/docs/skill/ankka-inspect/SKILL.md:60` — issue (non-blocking): the skill's row for "A project of several services" still says `docker compose up -d`, `sbt schema` and `scripts/run.sh <service>` for each service. The diff edited this file to add the new page but left that row, and the rendered copies under `marketplace/` and `ankka.g8/` repeat it.
  - **Why it matters:** an agent following the skill on a new project runs scripts the template no longer has.
  - **Suggestion:** make the row `sbt Docker/publishLocal`, `ankka local up` (and `ankka local run <service>` for one from source), then `just docs-sync`.
  - Resolved: the row now says `sbt Docker/publishLocal`, `ankka local up`, and `--without` with `local run` for one from source; copies synced, 7ea4b053a

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalState.scala:50` — issue (non-blocking): an unreadable `state.json` is silently replaced with an empty state.
  - **What happens:** the next `up` chooses new observability ports and new secret keys. The services' databases are kept, so a secret store written under the old `ANKKA_SECRET_KEY` becomes unreadable with nothing said. The class's own doc says "a secret store reads what it kept".
  - **Suggestion:** refuse with a line naming the file. Something like: `.ankka/local/state.json is not readable: <why>; ankka local down --volumes removes it with what was recorded`. Treat it as a first-tier problem.
  - Resolved: an unreadable state is refused before anything starts, naming the file and `down --volumes`, never replaced (the person chose this; contracts/cli.md gained the row); scripted case added, e0d9917c1

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:164` — issue (non-blocking): only the ports the descriptors name are checked for being taken.
  - **What happens:** the ports `state.json` chose once (Postgres, Kafka, each observability port, a companion's unnamed ports) are never re-checked. Once another program holds one, `up` fails part-way with Docker's "port is already allocated", after the database server may already have started.
  - **Why it matters:** FR-010 asks for the ports to be checked before anything starts.
  - **Suggestion:** pass every port of `state.ports` through `machine.holder` in `problems`, and keep `Ours` exempt.
  - Resolved: every port the state chose once (Postgres, Kafka, observability, a companion's) is checked before anything starts, unless what it was chosen for holds it; contract row widened; scripted case added, e0d9917c1

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalLogs.scala:46` — issue (non-blocking) (if-minor): `--follow` follows the last container in the `"" , "-app", "-module"` order, not "the platform's", which the comment says.
  - **What happens:** for a module service the last container is `<name>-module`, which has already exited, so `ankka local logs <wasm> --follow` returns at once. For a process service it follows the program, not the sidecar.
  - **Suggestion:** follow the hosting's main container, which is `name` for every hosting. Or order the suffixes per hosting as `containerNames` does.
  - Resolved: `--follow` follows the container named for the service, after the others' logs so far; module case added, 95e316bf1

- `specs/066-local-up/tasks.md` (T054) — todo: SC-002 to SC-004 (memory within 8 GiB, up within five minutes, an unchanged `up` within thirty seconds, a four-core neighbour) are not yet recorded.
  - **What is missing:** there is no `runs/measurements.md`, and spec.md still says `Status: Draft`.
  - **Suggestion:** record the measurements before merge, or name the missed figure in the PR body as the task says.
  - Resolved: measured on this Mac and written to `specs/066-local-up/runs/measurements.md`: first `up` 47 s, unchanged `up` 8 s, 2.80 GiB idle, every service ready after ten minutes beside a four-core load; the run also found the companion port defect fixed in 5aba576cf. spec.md now says Implemented

- `Commit 743f9786a` and the PR title — issue (non-blocking): `ankka context-map check` now fails in every project made from the 062 template that has topic crossings and no `topics.json`.
  - **Why it matters:** plan.md says so plainly, but no commit carries `!` or a `BREAKING CHANGE:` footer, as `CLAUDE.md` asks of a breaking change. A project's CI that runs the check goes red on a CLI upgrade.
  - **Suggestion:** mark the squash title with `!` and add the footer, naming `topics.json` as the fix. Consider a line on `docs/deploy/upgrading.md`.
  - Answered: the pull request's title carries `!` and the squash body a `BREAKING CHANGE:` footer naming `topics.json`; upgrading.md has a section for it, 7ea4b053a

## Nits

- `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/TopicDeclarations.scala:30` — nitpick (non-blocking): compaction can be said two ways, `"compacted": true` and `"cleanup": "compact"`.
  - **Where it shows:** the two new examples use one each: `local-projects.md` uses `compacted`, `topics.md` uses `cleanup`. `TopicsCommand.unchanged` compares `request.compacted == listed.compacted` (`TopicsCommand.scala:169`), so a file that says only `cleanup: compact` is sent and reported "declared" on every `apply`.
  - **Suggestion:** use one form in both pages. Either refuse the redundant pair or normalise the two before comparing.
  - Resolved: once `cleanup` is given it says the compaction, so `topics apply` no longer compares the `compacted` flag; TopicsApplySuite case red without it, db197c38d. Both pages keep their form: `--compacted` is the documented short form
- `.github/workflows/ci.yml:240` — nitpick (non-blocking): the `local-up` filter leaves out paths the suite depends on: `cli/src/main/scala/**/cli/Main.scala` (the wiring), `modules/core/**/ContextMap.scala`, `cli/**/console/LocalSource.scala` and `kustomization/components/postgres/ddl/**`. The CLI now bundles the DDL and applies it to every local database.
  - Resolved: Main, the console's source, the context map and the canonical DDL added to the filter; coverage check green, 2865fbedb
- `.claude/rules/kubernetes.md` (Schema) — nitpick (non-blocking): the line "Everything that globs the directory (compose, the sidecar image, the template)" should now name the CLI's `ankka/ddl` resource generator. The template no longer reads it.
  - Resolved: the line names the CLI's `ankka/ddl` resource, 2865fbedb
- `docs/build/local-projects.md:170` — suggestion: "A project made outside the template" should tell such a project to ignore `.ankka/`. `state.json` holds each service's secret key, and only the template's `.gitignore` ignores it today.
  - Resolved: the section asks a project to ignore `.ankka/` and says why, 7ea4b053a
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalStatus.scala:22` — nitpick (non-blocking): `status` does not ask `engine.available`. With Docker stopped, `ps` answers empty and every service is "not running", with exit 0. `up`, `run` and `down` say "docker is not running on this machine".
  - Declined: contracts/cli.md says status exits 1 only when the project cannot be read; with Docker stopped it says every service is not running, which is true, and a contract change is not worth a nit
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/ConsoleEntries.scala:78` — nitpick (non-blocking): `sourceRun` matches unowned entries by service name alone. A service of another project with the same name, run from source, makes this project refuse its port with "it is running from its source". Matching the entry's `project` (or its port) would make that true.
  - Left: nit. The runtime's console entry carries no project, so matching on it needs a runtime change outside this feature

## Questions

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:349` — question: does `ankka local up` refuse after an `ankka local run` of a process service has ended?
  - **What I read:** `run` brings up `<name>-sidecar`, which publishes the service's HTTP and observability ports, and leaves it running when the process ends. It is in the document, so it is never an orphan. A later plain `up` selects `name`. `Machines.holder` does not count `<name>-sidecar` as `Ours`, because `ours` keys on the service name. The port is therefore held by "something on this machine" and the service is refused until `down`.
  - **What would settle it:** a `LocalCommandSuite` case of run, then end, then `up`. If the refusal is real, stop `<name>-sidecar` when the run ends, or count it as the service's own container.
  - Resolved: yes, it was refused. A running `<name>-sidecar` now counts as the service's own port, and `up` stops it before the service's containers start; scripted case added, 68cd098e9
- `docs/build/local-projects.md` (the source-command table) — question: has `ankka local run` for a Python, TypeScript or Go service been run on Linux?
  - **Why I ask:** the SDKs bind `ANKKA_PROCESS_PORT` on loopback (`service.ts:413`, `service.py:223`). The sidecar container dials `host.docker.internal:<port>`, which on Linux is the bridge gateway and cannot reach a loopback-only listener. The old template's compose had the same shape, so this may be pre-existing. CI's `LocalUpSuite` covers process hosting only inside the pod-like namespace, not from source.
  - **What would settle it:** either a Linux run, or a limitations line saying that from source on Linux the process must bind every interface.
  - Answered: not on Linux. The SDKs listen on 127.0.0.1 by default, as the reviewer read; limitations.md now says a process run from source on Linux must listen on every interface, 7ea4b053a
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:168` — question: is refusing a plain `ankka local up` while a service runs from its source what FR-007 means?
  - **What I read:** the refusal names the service and says it runs from source, which is honest. FR-007 reads as "start what is not running", and treating that service as one of the project's (US5) would suggest leaving it out implicitly with a notice.
  - Answered: yes. contracts/cli.md names that refusal (`; it is running from its source`); leaving the service out silently would hide that the developer's own process holds the port, and `--without` says it in one word
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalUp.scala:404` — question: should `movable` handle the case where both versions are development builds?
  - **What I read:** `movable` accepts a development version only as the new one. When both the old and the new are development builds with different versions (`Release.parse` is `None` for both), the database is refused and the developer is told to drop it. That case looks reachable for a development CLI whose descriptor names no runtime.
  - **The answer that resolves it:** either "a descriptor always names a runtime", or treating the new development build as newest in that case too.
  - Resolved: a development build is taken as newest after another development build too; case added, f823cb546

## Praise

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala` — praise: the whole command is a pure plan with a single executor behind the `Engine` seam. This is the family's "effects are inert data" principle applied to Docker, and it is what lets `LocalCommandSuite` prove ordering, refusals and the never-printed values offline.
- `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/ProxyEnvironmentSuite.scala` — praise: the proxy's local mode drops TLS and admits every caller, and the new case pins that the operator never sets `ANKKA_PROXY_LOCAL` or `ANKKA_PROXY_LOCAL_SERVICES`. That is the right guard for a switch in a production image.
- `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/TopicDeclarations.scala` — praise: one strict file is read by the check, by `local up` and by `topics apply`, and it is held to `ProjectTopics.problems`, the control plane's own rules. The machine and the installation cannot drift apart.
- `.claude/rules/control-plane.md` — praise: each trap is dated the day it cost time: the `SO_REUSEADDR` free-port check, `sbt --client -D`, the 200 for an empty GET, and `--without` having to stop the container. Each one explains a line of code that would otherwise look arbitrary.

## Out of scope

- note: `LocalRun.foreground` and `LocalWeb` stop the child with a shutdown hook. The plugin trap in `control-plane.md` says the native image runs no exit handlers, which is why `Plugins` ignores SIGINT while its child runs. Ctrl-c on `ankka local run` may therefore return the prompt while `sbt` is still shutting down. `LocalWeb` had this before the diff. Suggested issue: make `local run` and `local web` ignore SIGINT as `Plugins` does, and check it on the native binary.
- note: none of the `DockerEngine` calls has a timeout (`pull`, `compose up`). A wedged Docker daemon hangs the command with no output. Suggested issue: a generous timeout per call, which names the call when it runs out.

## Checks applied

- **General checklist groups:**
  - Applied: Correctness; Design and scope; Error handling; Concurrency and state (light: one thread per command, and the `LocalSeams` vars are test seams only); Security (secrets in `state.json`, local values never printed, the proxy's local mode, loopback-only publishing); Data and migrations (schema moves between runtime versions, the comment recording a version and digest, and the DDL being idempotent, which I confirmed: every statement is `IF NOT EXISTS`); Tests; Observability; API and compatibility; Dependencies and build (no new library; images pinned by tag, not digest, as the rest of the repository does); Documentation; Commit and PR hygiene.
  - Skipped: Performance, beyond SC-002 to SC-004, which are T054's measurements.
- **Language skills:**
  - Not loaded: `pr-review-scala`. I applied its high-harm groups by hand (non-local `return` inside a method, not a lambda; `Try` swallowing in `ConsoleEntries`, deliberate and confined; the strict jsoniter codecs for `local.json` and `topics.json`, as the repository's strict-descriptor trap asks).
  - Not loaded: `pr-review-java`. The one Java file is the test stand-in.
  - The repository has no `pr-review-<language>` skill of its own, and `REVIEW.md` has no `## pr-review` section.
- **Every sample in every language (`REVIEW.md`):**
  - **No SDK samples owed:** the feature adds no SDK surface, so no per-language sample is owed. Plan.md's "Languages reached" says so, and the diff agrees.
  - **New page:** `docs/build/local-projects.md` declares no `languages`. It lists a default source command for every supported language: Scala, Java, Kotlin, Python, TypeScript, Go, Rust and web. I checked each command against its template: `mvnw` and `exec-maven-plugin`, the Gradle `application` plugin, `cargo module`, `npm start` and `npm run dev`, and the Python `name`.
  - **Changed pages with `languages`:** `topics.md` (all seven) gains a file-format example, not an SDK sample. `calling-services.md` and `run-locally.md` gain prose only.
  - **Limitations page:** it gains FR-033's list, the unchecked contracts, the web service missing from the console, Docker with Compose v2, and the images not being built.
  - **Result:** no language is missing a page, a sample or a limitations entry. The documentation finding is the stale `ankka-inspect` skill row.
- **Project rules read:** `CLAUDE.md`, `REVIEW.md`, `.claude/rules/control-plane.md`, `testing.md`, `kubernetes.md` (Schema), and `docs.md` by its heading.
- **Enforced by tooling and not reviewed:** formatting (scalafmt and the pre-commit hook), commit header shape (`.githooks/commit-msg`; every header parses and is at most 72 characters), and the docs page shape (`docs check`).
- **Tests run:** none. I was asked not to run sbt (another build and a Docker measurement are using this checkout), so every finding above comes from reading. The `--without`, status, `$` and post-run findings are each settled by one scripted-engine or plan case, which is cheap to write and run.

## Second round

# Review: fix(066) round two, af1f7fa45...HEAD

**Verdict:** Request changes
**What it does:** Ten commits answer the first round. They drop a descriptor's `ANKKA_DB_` and `ANKKA_KAFKA_` secrets on this machine. They double `$` in the rendered compose document and stop every container of a service left out. They give `up` and `status` one ready check per hosting, make `logs --follow` follow the container named for the service, and refuse an unreadable `state.json` and a chosen port that is now held. They fix the topics apply compaction comparison and let a database move between two development builds. They widen the `local-up` CI filter and correct the pages and the skill. One more commit (5aba576cf) counts a running companion's named host port as the project's own.
**Should it exist:** Yes. Each commit answers one finding, or the defect the measurement run found, and nothing else.
**Read:**
- **Read in full:** every hunk of the ten commits.
- **Opened outside the diff:** `LocalUp.run` and `start` in full, `Machines.of`, `LocalPlan.of`, `problems`, `forRun`, `wanted` and `entries`, `LocalRun`'s `run` and `inForeground`, `LocalStatus` in full, `ConsoleEntries.write` and `sourceRun`, `ScriptedEngine`'s `stop`, `ps` and `logs`, `Compose.Service`, `BrokerTopics`' topic script, the Postgres and Kafka healthchecks, and `TopicsCommand.unchanged` in full.
- **Skimmed (generated):** the rendered copies under `ankka.g8/` and `marketplace/`. They change with their sources in 7ea4b053a.
- **Skipped:** T054 (SC-002 to SC-004), as asked. `spec.md` still says `Status: Draft`, which belongs to that task.
**Size:** 38 files, +341 −52, of which about 150 lines are hand-written code and tests. The rest is docs and rendered copies.

### Previous resolutions

- Blocking, secret `ANKKA_DB_` variable (938c5a230): **holds.** The secret check skips `isInstallationOnly` names. The dropped notice now reads every env entry, not only literals. The new plan case would be `Left` without the fix.
- `$` escaping (66af4ecd5): **holds.** Nothing the plan writes means compose interpolation (the healthchecks and the topic script carry no `$`), so doubling every `$` is safe. `volumes` and `image` are not escaped: see Nits.
- `--without` stops a hosted service whole (68cd098e9): **holds.** `containers` now covers every service, and the scripted case expects `stop ankka-shop billing billing-app`.
- `status` of a web-hosted service (95e316bf1): **holds**, but it newly writes a console entry for that service: see Should fix.
- Old-template project and a non-Scala service (7ea4b053a, `projects.md`): **holds.**
- `ankka-inspect` skill row (7ea4b053a): **holds.** The source and both rendered copies changed together.
- Unreadable `state.json` (e0d9917c1): **holds.** `up` and `run` reach it through `readChecked`. `status` still uses the lenient `read`, which is right for a command that chooses nothing. The contract row and the research note were added.
- Chosen ports re-checked (e0d9917c1): **holds** for Postgres, Kafka, observability and companions. `processPorts` is left out: see Nits.
- `logs --follow` (95e316bf1): **holds.** A module service follows its runtime, a process service its sidecar, a web service its proxy.
- T054: skipped, as asked.
- Breaking change on the PR title (7ea4b053a): **holds** as an answer. There is no PR yet, so the `!` and the footer are still to be checked on the squash. The upgrading.md section is there.
- Nit, compaction said two ways (db197c38d): **holds.** `same` compares `cleanup` when it is given. The new case fails without the fix, because the listed topic is compacted and the request's `compacted` is false.
- Nit, CI filter (2865fbedb): **holds.** All four paths exist, so the coverage check's "every pattern matches" rule is met.
- Nit, kubernetes.md Schema line (2865fbedb): **holds.**
- Nit, `.ankka/` ignored (7ea4b053a): **holds.**
- Nit, `status` with Docker stopped: **declined.** The reason given (the contract) is accepted.
- Nit, `sourceRun` by name alone: **left.** The reason (the runtime's entry carries no project) is accepted.
- Question, `up` after a run of a process service (68cd098e9): **does not hold as written.** The ended run is fixed, but the fix cannot tell an ended run from one still going: see Blocking.
- Question, Linux and loopback (7ea4b053a): **holds.** The limitations entry names Python, TypeScript and Go, the three process languages.
- Question, FR-007 refusal: the answer is accepted. For a process service, the Blocking finding now contradicts it.
- Question, two development builds (f823cb546): **holds.** One question remains: see Questions.
- 5aba576cf (a companion's own port refused on a second `up`): **holds.** `Holder.Ours` for a running companion's named host ports is what `Companions.scala:165` already exempts. The new case fails without the fix (`isFree = _ != 8081` gives `Holder.Other`). `LocalSeams.reset()` in `afterEach` keeps the seam from leaking into the next test.

## Blocking

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalUp.scala:314` with `:177` — issue (blocking): a plain `ankka local up` while `ankka local run billing` is still running a process service now stops that run's sidecar and starts `billing` from its image in its place, with nothing said.
  - **What happens:** `ours` counts billing's ports as the project's own whenever `billing-sidecar` runs, whether or not the developer's process is still alive. The run's console entry is written by the command with an owner, so `sourceRun` does not see it and no `FromSource` refusal is reached. `stateTaken` exempts the sidecar's owner as well. `start` then puts `billing-sidecar` in `leaving`, stops it, and brings `billing` up on the same ports. The developer's foreground process loses its sidecar.
  - **Second path, same cause:** a second `ankka local run billing` during the first stops the first run's sidecar. `ForRun`'s `plan.services` is `Vector(billing)`, and `LocalRun` checks only for a running `billing` container, not `billing-sidecar`.
  - **Before this commit:** the port was `Holder.Other`, so both were refused. That is what contracts/cli.md and the first round's FR-007 answer say should happen.
  - **Why it matters:** US5's loop is: run one service from source, change another, run `up` again. That loop now silently takes the developer's run away.
  - **Suggestion:** count the sidecar as the service's own only once its run has ended, which is when the process port chosen for it is free on the host. Otherwise treat it as `Holder.FromSource`:
    ```scala
    val ended = (n: String) => state.processPorts.get(n).forall(isFree)
    .filter(n => running(n) || (running(LocalPlan.sidecarKey(n)) && ended(n)))
    ```
    `Machines.of` would need the chosen process ports, and `stateTaken`'s exemption the same condition. In `LocalRun`, refuse a run while `<name>-sidecar` runs and its process port is held. Add a scripted case with a run still going (`isFree = _ != processPort`), expecting the `; it is running from its source` refusal.
  - Resolved: a run's sidecar is the service's own only once the process port chosen for the run is free; while it is held, `up` refuses with `; it is running from its source`, and `--without` stops what the map dropped by name rather than removing orphans, keeping the live run's sidecar; three scripted cases, each red on the previous code, e5940e019

## Should fix

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalUp.scala:185` — issue (non-blocking): after an ended run, the `up` that stops `billing-sidecar` also prints `"billing-sidecar" is no longer in the context map; it is stopped, and its database is kept`. That is false: `billing` is in the map and the sidecar has no database of its own.
  - **What happens:** `gone` is every container in `before` that the plain plan's document does not hold. The run's sidecar is only in a `ForRun` document.
  - **Suggestion:** compute `gone` without the containers already in `leaving` (or without every `sidecarKey`). Make the new case "after a process service ran from its source, up brings it up from its image" assert that `err` is empty.
  - Resolved: a run's sidecar is not counted among what the map dropped, so no notice is printed for it; asserted in the ended-run case, e5940e019
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalStatus.scala:65` — issue (non-blocking): now that a web-hosted service can be "ready", `status` writes it a console entry. The entry points at the observability port `state.json` chose for it, which the proxy does not serve.
  - **What happens:** `LocalPlan.entries` leaves web-hosted services out on purpose, and limitations.md says the console does not list them. `LocalSource` keeps an owned entry that does not answer, so the console would show a web service that never answers.
  - **Suggestion:** filter `isWebHosted` out of the rows written here, as `entries` does. Extend the new status case to assert that no `<project>.billing.json` entry is written.
  - Resolved: status writes no console entry for a web-hosted service; asserted in the web status case, e5940e019

## Nits

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/Compose.scala:72` — nitpick (non-blocking): `volumes` and `image` are not escaped. A companion's volume in `local.json`, or a project directory, holding `$` would still be interpolated, though the doc comment says each `$` is escaped. Escape `volumes` too, or narrow the comment.
  - Resolved: `image` and `volumes` are escaped too, 015d4e96f
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalUp.scala:99` — nitpick (non-blocking): the chosen-port check has two gaps, both cheap to close in the same expression.
  - It leaves out `processPorts`. The sidecar's call-back port is published by `local run`, so a held one still fails part-way at Docker's refusal.
  - It includes a web-hosted service's observability port, which is never published, so an unrelated holder of that port refuses `up` for no reason.
  - Resolved in part: a web service's observability port is no longer checked, e5940e019. Left: `processPorts` are a run's, and the run's own start meets a held one

## Questions

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalUp.scala:432` — question: `(None, Some(_))` is still refused. A database a development build made, opened next by a released CLI, is refused with the `dropdb` line. This happens, for example, to someone who builds from `main` and then installs the next release. Is that intended? The answer that settles it is either "yes, a release after a development build is a drop", said in local-projects.md, or treating a release as movable from any development build of the same major.
  - Answered: intended. A development build's schema may be ahead of any release, so a released CLI refuses its database and says to drop it, the safe direction; only a contributor running a CLI built from source meets it
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalStatus.scala:40` — thought: while a process service runs from its source, `status` reports it "not running". The service's own container is not there, and the run's entry is owned, so `fromSource` is false. This predates the round. It falls out of the same "is the run still going" test the Blocking fix needs, if that test is shared.
  - Left: a thought on behaviour from before this round, noted for a follow-up

## Praise

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/local/LocalPlan.scala:231` — praise: `readyCheck` as one function for `up` and `status` removes the disagreement for good, not just for web hosting.
- `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/LocalCommandSuite.scala` — praise: each fix came with a case that fails without it. The movable table, the unreadable-state case (which checks the file is untouched and no `up` was called) and the companion-port case are all small and state-based.

## Checks applied

- **General checklist groups:**
  - Applied: Correctness; Design and scope; Error handling; Concurrency and state (the run against `up` interplay); Data and migrations (`movable`, `state.json`'s secret keys); Tests; API and compatibility (the contract rows); Documentation; Commit and PR hygiene.
  - Skipped: Security beyond the `$` escaping and `state.json` (no new entry point or credential); Performance (T054); Dependencies (none).
- **Language skills:** `pr-review-scala` not loaded; its totality and test-fixture groups were applied by hand to about 150 lines (`state.processPorts(...)` is a partial `Map.apply` in pre-existing `LocalRun` and `forRun` code, not this diff). There is no repository skill of its own, and `REVIEW.md` has no `## pr-review` section.
- **Every sample in every language (`REVIEW.md`):** the round adds no SDK surface or sample. The new limitations entry names every process-hosted language (Python, TypeScript, Go). The JVM languages are embedded and Rust is a module, so none of them is owed the entry. `projects.md` names Python, TypeScript, Go and Rust, the languages whose per-service compose file the template used to write. No language is missing a page, a sample or a limitations entry.
- **Project rules read:** `CLAUDE.md`, `.claude/rules/control-plane.md` ("A project on one machine"), `testing.md`, and `kubernetes.md` (Schema).
- **Enforced by tooling, not reviewed:** formatting and commit headers. Every header in the range parses as a Conventional Commit.
- **Tests run:** none. sbt and Docker were not to be run while the measurement runs. The Blocking finding is from reading `Machines.of`, `start` and `forRun`, and is settled by one scripted case with the process port held.
