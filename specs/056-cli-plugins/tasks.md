# Tasks: Command Line Plugins

**Input**: Design documents from `/specs/056-cli-plugins/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/cli-plugins.md](./contracts/cli-plugins.md),
[quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule (`.claude/rules/testing.md`) is that each
acceptance scenario ends as a test that fails without the feature. The scenarios are already written
in `features/command-line/plugins.feature` (19 scenarios); no `GherkinSuite` can run them, because
their steps are shell-level (programs, signals, the PATH), so each becomes a `test(...)` in
`PluginSuite` named for the scenario, as `LocalWebSuite` does for `features/web-hosting/local.feature`.
Where a task says "case", it means such a test. A case must be red before the implementation task that
makes it green: run it once and read the failure.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a product's command runs as if it were built in), US2 (the plugin is given what
  the CLI had already resolved), US3 (a person can see what would run)

Paths are repository-relative. `CLI` = `cli/src/main/scala/com/thinkmorestupidless/ankka/cli`;
`CLIT` = `cli/src/test/scala/com/thinkmorestupidless/ankka/cli`. "R*n*" is a section of
`research.md`; "contract §*n*" a section of `contracts/cli-plugins.md`.

Work on branch `056-cli-plugins` in the worktree `.claude/worktrees/056-cli-plugins`, or in an
`056-cli-plugins-impl` worktree off it, as the project's habit is. Every `sbt` command here is
`sbt -Dankka.cluster.tests=off ...`; nothing needs Docker or k3s. The CLI's tests set `ankka.config`
in-process (`sys.props("ankka.config") = ...` in `beforeEach`, removed in `afterEach`, as
`SettingsSuite` does); `ankka.path` follows the same pattern. Never read the developer's `PATH` or
`~/.ankka` from a test.

---

## Phase 1: Setup

**Purpose**: the test harness every story's cases use, so a case is three lines: a stub, a run, an
assertion.

- [X] T001 Create `CLIT/PluginSuite.scala`: a munit suite with a `beforeEach` that makes a scratch
  directory holding `bin/` and `config.json`, sets `sys.props("ankka.config")` to the file and
  `sys.props("ankka.path")` to `bin/`, and an `afterEach` that deletes the scratch directory *before*
  removing both properties (the credentials trap in `.claude/rules/control-plane.md`). Helpers:
  `stub(name: String, body: String, executable: Boolean = true): Path` writing a `#!/bin/sh` script
  into `bin/` (the body gets `$out` pointing at a file in the scratch directory it may write to),
  `run(args: String*): (Int, String, String)` calling `Main.run` with captured `out` and `err`
  (catching `ExitWith` into the code, as the other CLI suites do), and `recorded(): (Seq[String],
  Map[String, String])` reading the arguments and the `ANKKA_*` environment a stub wrote to `$out`
  (one line per argument, then `env | grep '^ANKKA_'`). A comment at the top names
  `features/command-line/plugins.feature` and says each of its scenarios is a test here, and that the
  remaining tests (PATH empty, a hidden duplicate, `ankka plugin` alone, the ctrl-c wait) are named
  for the spec's Edge Cases. The suite compiles with no tests yet.

**Checkpoint**: `sbt 'cli/testOnly *PluginSuite'` runs and reports zero tests.

---

## Phase 2: Foundational

**Purpose**: the list of built-in words and the lookup on the path; every story reads these.

- [X] T002 Add `val builtIn: Set[String]` to `object Main` in `CLI/Main.scala`, the thirteen command
  words the tree has today (`login`, `logout`, `whoami`, `status`, `installation`, `organizations`,
  `projects`, `services`, `config`, `version`, `init`, `local`, `mcp`), with a comment that `plugin`
  joins it in US3 and that `CliReferenceSuite` holds it equal to the tree (R1, data-model *Built-in
  command*).
- [X] T003 Add to `CLIT/CliReferenceSuite.scala` a test `built-in words equal the root help's
  subcommands` asserting `Main.builtIn == subcommands(help(Seq.empty)).toSet` (the helpers the
  suite already has). It is green now; it goes red the moment a command is added to one and not the
  other (R1).
- [X] T004 Create `CLI/Plugins.scala` with the lookup (R2, data-model *Candidate*):
  `Plugins.directories: Seq[Path]` from `sys.props.get("ankka.path")`, else `sys.env.get("PATH")`,
  split on `File.pathSeparator`, empty entries dropped, each made absolute; `Plugins.isWord(word)`
  false when the word holds `/` or `\`; `enum Status { case Runs; case Shadowed; case Hidden(by:
  Path); case NotExecutable }`; `case class Candidate(word: String, program: Path, status: Status)`;
  `Plugins.candidates(builtIn: Set[String]): Seq[Candidate]` listing each directory with
  `Files.list` (an unlistable directory skipped silently), keeping entries whose file name starts
  `ankka-` with a non-empty rest, comparing names exactly (never `Files.exists`), status decided in
  path order per the data-model rules (a regular executable file is `Runs` unless its word is
  built-in, `Shadowed`, or an earlier one `Runs`, `Hidden(by)`; anything else `NotExecutable`);
  `Plugins.find(word, builtIn): Option[Path]` returning the `Runs` candidate for the word, and
  `Plugins.pathIsEmpty: Boolean` when `directories` is empty. Pure functions over the filesystem; no
  process is started here.

**Checkpoint**: `sbt cli/compile` is clean under `-Werror`; `sbt 'cli/testOnly *CliReferenceSuite'`
green.

---

## Phase 3: User Story 1 — A product's command runs as if it were built in (Priority: P1) 🎯 MVP

**Goal**: `ankka <word> args...` runs `ankka-<word>` from the path with the arguments untouched, the
streams inherited and the exit code passed through; a word that is nothing is a usage error; a
program that will not start is a failure naming it; ctrl-c reaches the plugin and the CLI waits.

**Independent Test**: quickstart steps 1 and 3, and `sbt 'cli/testOnly *PluginSuite'` with the nine
US1 cases.

### Cases for User Story 1 (write first; they fail with `'vendor' ... Unexpected argument` today)

- [X] T005 [P] [US1] In `CLIT/PluginSuite.scala`, the four cases of running: `a word that names no
  built-in command runs the plugin of that name with the rest of the command line` (stub
  `ankka-vendor` records its arguments; `run("vendor", "modules", "list")` → recorded arguments are
  exactly `modules list`); `the plugin's exit code is the command's exit code` (stub `exit 3` → 3);
  `every flag after the plugin's name is the plugin's, untouched` (`run("vendor", "modules", "list",
  "-o", "json", "--project", "p-2", "--help")` → the six words recorded in order, and nothing from
  decline on `err`); `a plugin ended by a signal ends the command with 128 and the signal's number`
  (stub `kill -TERM $$` → 143, `err` empty).
- [X] T006 [P] [US1] In `CLIT/PluginSuite.scala`, the five cases of not running: `a word that names
  neither a built-in command nor a plugin is a usage error` (`run("reports", "summary")` → 2; `err`
  holds `'reports' is not an ankka command`, `ankka-reports` and `See 'ankka --help'.`); `a plugin's
  name is matched exactly, whatever case the filesystem ignores` (stub `ankka-vendor` records a run;
  `run("Vendor")` → 2, nothing recorded; this is the case that fails on APFS with `Files.exists`);
  `a program of a plugin's name that is not executable is passed over` (a second directory on
  `ankka.path` before `bin/` holding a non-executable `ankka-vendor`; `run("vendor")` runs the
  executable one, recorded); `a plugin that cannot be run fails the command, naming the program`
  (stub `ankka-broken` whose shebang names `/nonexistent/interpreter` → 1; `err` holds `cannot run`
  and the program's absolute path); `a plugin's name holds no directory` (`run("../vendor")` → 2;
  `err` holds `named by a word, not a path`; nothing recorded). Plus a PATH-empty case: with
  `sys.props("ankka.path") = ""`, `run("vendor")` → 2 and `err` holds `PATH is empty`.

### Implementation for User Story 1

- [X] T007 [US1] In `CLI/Plugins.scala`, `Plugins.run(program: Path, args: Seq[String], env:
  Map[String, String], err: PrintStream): Int` (R3, R4, contract §3, §4): `new
  ProcessBuilder((program.toString +: args)*)` with `inheritIO()`, `env` put into
  `builder.environment()` over the inherited one, then `sun.misc.Signal.handle(new Signal("INT"), _
  => ())` so the CLI ignores ctrl-c while the child runs, `start()`, `waitFor()`, return the code
  (the JDK already reports a signalled child as 128 + signal; map nothing). The handler is
  deliberately not restored: the CLI exits right after. In `PluginSuite` that means the forked test
  JVM ignores SIGINT from the first run case on; sbt stops it with `destroy`, so nothing breaks, but
  say so in a comment. An `IOException` from `start()` prints `error: cannot run <program>:
  <message>` to `err` and returns 1 (FR-008).
- [X] T008 [US1] In `CLI/Plugins.scala`, the environment's first two variables and the usage errors
  (R8, contract §5): `Plugins.environment(err: PrintStream): Map[String, String]` returning
  `ANKKA_URL` from `Settings.resolve(None, None, None).url` and `ANKKA_CONFIG` from
  `Settings.path.toAbsolutePath.toString` (US2 extends it with the other three); and
  `Plugins.dispatch(word: String, rest: Seq[String], builtIn: Set[String], err: PrintStream): Int`
  which refuses a non-word with `ankka: a plugin is named by a word, not a path: '<word>'` → 2,
  reports an empty path with `ankka: '<word>' is not an ankka command, and PATH is empty, so no
  plugin can be found` → 2, reports nothing found with `ankka: '<word>' is not an ankka command, and
  no program 'ankka-<word>' is on the path` then `See 'ankka --help'.` → 2, and otherwise returns
  `run(program, rest, environment(err), err)`.
- [X] T009 [US1] In `CLI/Main.scala`, the dispatch at the top of `run(args, out, err)` before
  `command.parse` (R1): when `args` is non-empty, its head does not start with `-` and is not in
  `builtIn`, return `Plugins.dispatch(args.head, args.tail, builtIn, err)` (through the same
  `ExitWith`/return path the method uses today, so `main` stays a one-line wrapper). Everything else
  reaches decline unchanged: `ankka -o json vendor` stays decline's usage error, exit 2.
- [X] T010 [US1] In `CLIT/PluginSuite.scala`, one subprocess case `the CLI waits for an interrupted
  plugin and reports its code` (the ctrl-c edge case; R9): start the real CLI with `java -cp
  sys.props("java.class.path") com.thinkmorestupidless.ankka.cli.Main sleepy` as a `ProcessBuilder`
  child with `-Dankka.config` and `-Dankka.path` on its command line and its output piped, where stub
  `ankka-sleepy` does `trap 'echo interrupted; exit 7' INT; echo started; sleep 20`; once the piped
  output shows `started`, send SIGINT to the **CLI process's pid only** (`kill -INT <pid>`; the test
  JVM is in the same process group, so never signal the group) and then to the stub's pid (written
  to `$out` by the stub); assert the CLI's exit code is 7, its output holds `started` and
  `interrupted` (the inherited stream carried the plugin's words), and the whole thing took under the
  stub's sleep. Without R4 the CLI dies at the SIGINT with 130 and the case is red. Guard the case
  with `assume` on a Unix `os.name`.

**Checkpoint**: the ten US1 cases green; `sbt cli/test` green (no built-in command changed);
quickstart steps 1 and 3 behave as written after `sbt cli/stage`.

---

## Phase 4: User Story 2 — The plugin is given what the CLI had already resolved (Priority: P1)

**Goal**: `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA`, `ANKKA_CONFIG` in the plugin's
environment, resolved as a built-in command given no flags would; a saved login refreshed first; no
token is not an error; the token travels by the environment alone.

**Independent Test**: quickstart step 2, and the six US2 cases.

### Cases for User Story 2 (write first)

- [X] T011 [P] [US2] In `CLIT/PluginSuite.scala`, the six cases, each with stub `ankka-vendor`
  recording its `ANKKA_*` environment and using `FakeIssuer` (as `DeviceFlowSuite` does; it binds
  port 0) and `Credentials.put` to plant a saved login: `a plugin is given the settings a built-in
  command would have resolved` (saved `url` `https://cp.example.test`, `project` `p-1`, `ca`
  `local-roots.pem` as a relative path beside the config, a saved login with a live access token →
  `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT` equal those, `ANKKA_CA` is absolute and ends in
  `local-roots.pem`); `a plugin is told where the settings are saved` (`ANKKA_CONFIG` equals the
  scratch `config.json`'s absolute path); `a saved login that has run out is refreshed before the
  plugin runs` (saved `expiresAt` in the past → `issuer.refreshes == 1`, `ANKKA_TOKEN` is the
  issuer's new access token, and `Credentials.get` holds it too); `a plugin is given no token when
  none can be resolved, and still runs` (no login, no `token` → recorded run, no `ANKKA_TOKEN` key,
  exit 0, `err` empty); `a refused refresh leaves the plugin without a token, and says why`
  (`issuer.refuseRefresh = true`, expired login → recorded run, no `ANKKA_TOKEN`, exit 0, `err`
  holds `warning:`, the URL, `rejected the login` and `run 'ankka login'`); a seventh case for the
  refresh that cannot be made (FR-005's "cannot be asked for"): plant an expired login whose issuer
  is the `FakeIssuer`'s URL, stop the issuer before the run → recorded run, no `ANKKA_TOKEN`, exit 0,
  `err` holds `warning:` and `run 'ankka login'`, and no stack trace; `the token reaches the
  plugin by its environment alone` (a live login whose access token is a distinctive string → the
  recorded arguments, the captured `out` and `err` hold no such string, and of every file under the
  scratch directory only `credentials.json` holds it).

### Implementation for User Story 2

- [X] T012 [US2] In `CLI/Session.scala`, the seam (R5, data-model *Credential*): `enum Credential {
  case Token(value: String); case NotLoggedIn; case Refused(reason: String) }` in `object Session`,
  and `def credential(settings: Settings): Credential` holding the resolution `bearer` performs today
  (a flag or environment token → `Token`; no saved login for the URL → `NotLoggedIn`; a usable or
  successfully refreshed saved login, saved first → `Token`; a refresh the issuer refuses →
  `Refused(reason)` with the reason `bearer` puts in its `ApiError` today; a refresh that cannot be
  made, because `flow.discover` or `flow.refresh` threw (today `bearer` catches nothing there and a
  raw exception escapes `Main.run`) → `Refused(<the exception's message>)`). Refactor `bearer` to
  call `credential` and throw on `NotLoggedIn` the `ApiError` it throws now, and on `Refused` the
  `ApiError(0, s"$url rejected the login ($reason); run 'ankka login'")` it throws now, which for the
  unreachable issuer is a message where a stack trace was. Then `sbt 'cli/testOnly *DeviceFlowSuite'`
  and `sbt cli/test` green **unchanged**: that is the proof no built-in command moved.
- [X] T013 [US2] In `CLI/Plugins.scala`, complete `Plugins.environment(err)` (R5, contract §2): over
  `Settings.resolve(None, None, None)`, add `ANKKA_PROJECT` when the project resolves, `ANKKA_CA` as
  `Paths.get(ca).toAbsolutePath` when one resolves (against the working directory, exactly as
  `ControlPlaneClient` and `DeviceFlow` read the same value; `Trust` expands nothing), and
  `ANKKA_TOKEN` from
  `Session.credential(settings)` on `Token`; on `NotLoggedIn` set nothing; on `Refused(reason)` set
  nothing and print `warning: <url> rejected the login (<reason>); run 'ankka login'` to `err`. Never
  add the token to `args`; never print it.

**Checkpoint**: the sixteen cases green; `sbt cli/test` green; quickstart step 2 behaves as written.

---

## Phase 5: User Story 3 — A person can see what would run (Priority: P2)

**Goal**: `ankka plugin list` as a table or JSON with the four statuses; `ankka --help` ends with the
plugins that would run; the generated reference carries the `plugin` command and no plugin name.

**Independent Test**: quickstart step 4, the US3 cases, and `sbt 'cli/testOnly *CliReferenceSuite'`.

### Cases for User Story 3 (write first)

- [X] T014 [P] [US3] In `CLIT/PluginSuite.scala`, the cases: `the plugin list names every plugin on
  the path and where it is` (stubs `ankka-vendor` and `ankka-reports`; `run("plugin", "list")` → a
  table with header words `PLUGIN`, `PROGRAM`, `STATUS`, a `vendor` row with its absolute path and
  `runs`, a `reports` row likewise; `run("plugin", "list", "-o", "json")` → a JSON array of objects
  with `word`, `program`, `status: "runs"` in path order); `a plugin named like a built-in command is
  listed as shadowed and never run` (stub `ankka-services` recording a run; `run("services", "list",
  "--url", "http://127.0.0.1:1")` → the built-in answers with its own failure: exit 1, `err` names
  the connection to `127.0.0.1:1` (which proves decline parsed `services list`), nothing recorded;
  the list shows `services` with `shadowed by the built-in command`, JSON `status: "shadowed"`); the second half of `a program of a plugin's name that is not executable is passed
  over` (the list shows the non-executable file with `not executable`, JSON `not-executable`, and the
  executable one `runs`); a hidden case (two directories each with `ankka-vendor` → the second is
  `hidden by <first's path>`, JSON `status: "hidden"` with `hiddenBy`); an empty case (no stubs → `out`
  is `no plugins on the path`, JSON `[]`); `help names the plugins found beside the built-in
  commands` (stubs `ankka-vendor` and `ankka-services`; `run("--help")` → exit 2 as today, and
  **`err`**, where `Main.run` prints decline's help (`Main.scala:1661-1665`, for `--help` and for no
  arguments alike), ends with a line `Plugins (programs named ankka-<word> on the path):` followed
  by `    vendor`, and holds no `services` line under it; `out` is empty; `run()` with no arguments
  gives the same appendix; with no stubs the appendix is absent); and `ankka plugin` alone → 2
  (decline's usage).
- [X] T015 [P] [US3] In `CLIT/CliReferenceSuite.scala`, the case `the command reference names no
  plugin`: render the block the suite already renders and assert no line starts with `Plugins`
  (R6, FR-013). Extend T003's set to include `plugin` once T016 lands (the equality test tells you
  when).

### Implementation for User Story 3

- [X] T016 [P] [US3] In `CLI/Output.scala`, `def plugins(rows: Seq[Plugins.Candidate], format:
  Format, out: PrintStream)` (R7, contract §6): `Format.Table` → the existing `table` helper with
  headers `PLUGIN`, `PROGRAM`, `STATUS` and the status words `runs`, `shadowed by the built-in
  command`, `hidden by <path>`, `not executable`; an empty list prints `no plugins on the path`.
  `Format.Json` → an array of objects `word`, `program`, `status` (`runs` | `shadowed` | `hidden` |
  `not-executable`) and `hiddenBy` on hidden rows only, through the codec style the file already uses.
- [X] T017 [US3] In `CLI/Main.scala`: the `plugin` command, a decline `Opts.subcommand("plugin",
  "Programs named ankka-<word> on the path")` holding one subcommand `list` ("List the plugins on the
  path and whether each would run") that takes `formatOpt` only and calls
  `Output.plugins(Plugins.candidates(builtIn), format, out)`; add it to the command tree in
  alphabetical or the tree's existing order; add `"plugin"` to `builtIn` (T003 goes red until both
  are done). Then the help appendix (R6): in `run`'s `Left(help)` branch, when `args` is empty or
  exactly `Seq("--help")`, after `err.println(help)`, compute
  `Plugins.candidates(builtIn).filter(_.status == Runs)` and, when non-empty, print on **the same
  stream, `err`**, a blank line, `Plugins (programs named ankka-<word> on the path):` and one
  `    <word>` line each, in path order; the exit code stays 2, as decline's help has always been.
  Only `run` does this; `command.parse` never sees it.
- [X] T018 [US3] Regenerate the reference: `sbt -Dankka.docs.update=true 'cli/testOnly
  *CliReferenceSuite'` (or `just docs-reference`) rewrites the generated block of
  `docs/reference/cli.md` with `ankka plugin list`; read the diff, confirm no plugin name and no
  `Plugins` line entered the block, and commit the page with the code.

**Checkpoint**: every `PluginSuite` case green (about 24); `CliReferenceSuite` green with its three
new assertions; `sbt cli/test` green; quickstart step 4 behaves as written.

---

## Phase 6: Polish & cross-cutting

- [X] T019 [P] In `docs/reference/cli.md`, a hand-written `## Plugins` section before `## Commands`
  (R10, FR-014, contract §1–§7): the dispatch rule in two sentences; that everything after the word
  is the plugin's, flags included; a table of the five variables with when each is set; the
  warning on a refused refresh; the exit codes (the plugin's; 128 + signal; 2 for a word that is
  nothing or holds a path; 1 for a program that will not start); the four `plugin list` statuses;
  ctrl-c; that `ankka mcp` serves no plugin. No feature number, no history (`.claude/rules/docs.md`).
- [X] T020 [P] In `docs/reference/limitations.md`, one bullet under *SDKs and releases*: `ankka mcp`
  serves no plugin's commands; an agent reaches them by running the shell.
- [X] T021 [P] In `cli/native-smoke.sh`, a plugin step after the existing ones (R9, R11): write
  `$work/bin/ankka-smoke` (`#!/bin/sh`, prints `smoke: $*` and the presence of `ANKKA_CONFIG`, exits
  4), `chmod +x`, run `PATH="$work/bin:$PATH" "$bin" smoke hello` capturing output and code, `fail`
  unless the output is `smoke: hello` with `ANKKA_CONFIG` set and the code is 4, then `fail` unless
  `PATH="$work/bin:$PATH" "$bin" plugin list` names `smoke` with `runs`; echo `plugin     smoke` in
  the script's column style. The step runs in the release's `cli-native` job unchanged.
- [X] T022 [P] In `.claude/rules/control-plane.md`, beside the `-Dankka.config` trap, two dated
  lines: `-Dankka.path` overrides `PATH` for the plugin lookup for the same reason, and a plugin
  name is matched by listing the directory because `Files.exists` ignores case on APFS; and that the
  CLI ignores SIGINT while a plugin runs (`sun.misc.Signal`, not a shutdown hook, because the native
  image installs no exit handlers).
- [X] T023 Prove the cases can fail (quickstart *Done when*; `CLAUDE.md` "could this pass while the
  thing it checks is false?"): comment out the dispatch line in `Main.run`, run `sbt 'cli/testOnly
  *PluginSuite'`, confirm every US1 and US2 case and the shadowed/help cases go red, restore it, and
  rerun green. Note in the PR description which cases stayed green and why (the `plugin list` cases
  do not depend on the dispatch).
- [X] T024 Full check: `sbt scalafmtAll scalafmtSbt`, `sbt -Dankka.cluster.tests=off cli/test`,
  `just features` (0 findings), `just docs-sync`, `just docs`; then the quickstart's steps 1 to 5 by
  hand after `sbt cli/stage` (SC-001: time `ankka echo` against the stub run directly; the
  difference stays under 50 ms with no login to refresh), and step 7 if a GraalVM is at hand.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: T002 → T003; T004 independent of both. Blocks every story.
- **US1 (Phase 3)**: after Phase 2. T005, T006 parallel; T007 → T008 → T009; T010 after T009.
- **US2 (Phase 4)**: after Phase 2; builds on US1's `environment` (T008) and `dispatch` (T009) only
  to run the cases, so it may be *written* beside US1 and *run* after it. T011 parallel with T012;
  T012 → T013.
- **US3 (Phase 5)**: after Phase 2; needs nothing from US1 or US2 except T009 for the shadowed and
  help cases to run. T014, T015, T016 parallel; T016 → T017 → T018.
- **Polish (Phase 6)**: T019–T022 parallel and can start any time after Phase 2; T023 after Phases 3–5;
  T024 last.

### Within each story

Cases before implementation, red once, then green. `sbt cli/test` after each implementation task:
no built-in command may change its behaviour, and `DeviceFlowSuite` is the sentinel for T012.

### Parallel opportunities

- T005 ∥ T006 (both add cases to `PluginSuite` but to different scenarios; agree the order of
  insertion or write them in one sitting).
- T011 ∥ T012: the US2 cases and the `Session` seam touch different files.
- T014 ∥ T015 ∥ T016: cases, the reference assertion and the renderer.
- T019 ∥ T020 ∥ T021 ∥ T022: four files, no shared line.

---

## Parallel Example: User Story 1

```bash
# The cases, in one sitting (they are the same file):
Task: "T005 four running cases in CLIT/PluginSuite.scala"
Task: "T006 five not-running cases plus PATH-empty in CLIT/PluginSuite.scala"

# Then, in order, because each uses the one before:
Task: "T007 Plugins.run"  →  "T008 Plugins.environment (two variables) and dispatch"  →  "T009 Main.run dispatch"
# and once T009 is in:
Task: "T010 the subprocess SIGINT case"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 and Phase 2 (T001–T004).
2. Phase 3 (T005–T010): `ankka echo a b` runs a stub with the arguments and its code, and
   `ANKKA_URL`/`ANKKA_CONFIG` already reach it.
3. **Stop and validate**: quickstart steps 1 and 3; `sbt cli/test`.

### Incremental delivery

1. US2 (T011–T013): the login and the settings reach the plugin. Quickstart step 2.
2. US3 (T014–T018): `plugin list`, the help appendix, the regenerated reference. Quickstart step 4.
3. Polish (T019–T024): the pages, the smoke step, the rule-file traps, the red-then-green proof, the
   full check.

Each increment leaves `sbt cli/test` green and no built-in command changed.

---

## Notes

- Nothing outside `cli/`, `docs/reference/` and `.claude/rules/` changes (R11): no control plane, no
  `ankka mcp`, no Homebrew, no release workflow, no `ci-coverage.py` (`cli/**`, `features/**` and
  `cli/native-smoke.sh` are already claimed).
- `features/command-line/plugins.feature` and `GLOSSARY.md` are settled; a case that needs a
  different wording means the feature is wrong, not the case — fix the feature and rerun
  `just features`.
- Commit after each phase at least; the reference page (T018) commits with the `plugin` command
  (T017), never apart from it, or `CliReferenceSuite` is red at that commit.

---

## Implementation notes (2026-10-22)

- **T002 and T017 landed together.** `builtIn` was written with `plugin` in it from the start, so the
  equality test (T003) was never red on that account.
- **The JSON field is `hiddenBy`, not `hides`.** The row is the program being hidden; `hides` read
  backwards. The contract, research R7, the plan and this file now say `hiddenBy`.
- **The token-scan case leaves the stub out of the scan.** The test writes the token into the stub to
  compare against; the CLI writes it nowhere but `credentials.json`.
- **Red without the feature (T023).** With the dispatch disabled, 18 of `PluginSuite`'s 26 cases fail;
  the 8 that pass are `plugin list`, help, the shadowed built-in, `ankka plugin` alone and the flag
  before the word, none of which go through the dispatch. With the SIGINT handler replaced by a no-op,
  the ctrl-c case fails: the CLI ends at the interrupt.
- **Not run here: the native build (quickstart step 7).** No GraalVM on the machine. The new smoke step
  was run against the staged JVM build and passed; the release's `cli-native` job runs it natively.
- **SC-001.** On the staged JVM build, `ankka echo` took 367 ms against 341 ms for `ankka version`
  and 12 ms for the stub alone: the dispatch adds about 26 ms.
