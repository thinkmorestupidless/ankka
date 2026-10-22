# Implementation Plan: Command Line Plugins

**Branch**: `056-cli-plugins` | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/056-cli-plugins/spec.md`, reviewed against the CLI's
code and clarified 2026-10-21 (two decisions in its Clarifications section).

## Summary

`ankka <word> ...` where `<word>` is none of the CLI's fourteen built-in commands runs the first
executable program named exactly `ankka-<word>` on the PATH, with every argument after the word
untouched, the CLI's standard streams inherited, and the CLI's resolved settings in its environment:
`ANKKA_URL`, `ANKKA_TOKEN` (a saved login refreshed first; absent when none resolves),
`ANKKA_PROJECT`, `ANKKA_CA` and `ANKKA_CONFIG`. The command's exit code is the plugin's, 128 plus
the signal when it was killed, and the CLI ignores SIGINT while the plugin runs so ctrl-c reaches
the plugin and the CLI is still there to report it. A word that is nothing is a usage error naming
the program looked for; a program that will not start is a failure naming the file. `ankka plugin
list` shows every candidate with its status (runs, shadowed, hidden, not executable), as a table or
JSON; `ankka --help` ends with the words that would run, outside decline's help so the generated
reference never carries them. The dispatch sits in `Main.run` before decline, on an explicit set of
built-in words a test holds equal to the command tree. No library, no control plane change, no
change to `ankka mcp`. Research is in [research.md](research.md); the plugin's contract in
[contracts/cli-plugins.md](contracts/cli-plugins.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21, the `cli` module only (`cli/src/main/scala/.../cli`).
No new library: decline 2.6.2 keeps the command tree, `java.lang.ProcessBuilder` runs the plugin,
`sun.misc.Signal` holds SIGINT (supported by Substrate VM for the native build).

**Primary Dependencies**: `Settings.resolve` and `Session` (the resolution order and the silent
refresh, reused, with `Session` gaining a value-returning seam, R5); `Output.table` for `plugin list`;
`CliReferenceSuite` for the generated reference; `FakeIssuer` for the refresh tests.

**Storage**: none. The refresh writes the saved login as today; nothing else is written.

**Testing**: munit. A new `PluginSuite` drives `Main.run` in process with `-Dankka.path` and
`-Dankka.config` at scratch directories holding `/bin/sh` stubs, one test per scenario of
`features/command-line/plugins.feature`; one subprocess test on the test classpath for the inherited
streams and the ctrl-c wait; `CliReferenceSuite` gains two assertions; `cli/native-smoke.sh` gains a
plugin step (R9).

**Target Platform**: macOS and Linux, the JVM build and the GraalVM native build alike. Windows out
of scope, as the limitations page already says.

**Project Type**: a command-line tool's dispatch; one module, one reference page.

**Performance Goals**: SC-001, under 50 ms added before the plugin starts: one `Files.list` per PATH
directory and one read of two small files, with a network call only when a saved login has run out.

**Constraints**: anything reading the environment must be overridable by a system property for the
in-process tests (`-Dankka.path`, after `-Dankka.config`; `.claude/rules/control-plane.md`). `main`
stays a one-line wrapper over `Main.run(args, out, err): Int`; the plugin's code travels as
`ExitWith`. Names are compared exactly, never with `Files.exists`, so a case-ignoring filesystem
cannot make `ankka Services` run `ankka-services`. The token is set in the child's environment only.
The reference is rendered from `Main.command.parse`, never from `Main.run`, so the PATH of the
generating machine cannot reach the page. No test binds a port the documented workflow uses
(`FakeIssuer` binds 0).

**Scale/Scope**: one new source file (`Plugins.scala`: the lookup, the candidates and the run), a
seam in `Session`, about forty lines in `Main` (the dispatch, the `plugin` command, the help
appendix), one `Output` renderer, one test suite of about twenty cases, two reference pages, one
smoke step.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template: no gate derives from it. The repository's
standing rules (`CLAUDE.md`, `.claude/rules/control-plane.md`, `build-and-release.md`, `docs.md`,
`testing.md`) and the family's `principles.md` are the gates:

| Gate | Pre-research | Post-design |
|---|---|---|
| Explicit registration, never scanning | pass: built-in words are an explicit set, held equal to the tree by a test (R1); a plugin is found by its exact name on the PATH the person controls, never by scanning for anything else | pass |
| Anything reading `$HOME` or the environment is overridable by a system property | pass: `-Dankka.path` beside `-Dankka.config` (R2) | pass: `PluginSuite` never reads the developer's PATH or `~/.ankka` |
| `main` is a one-line wrapper; exit codes return from `run` | pass: the plugin's code is an `ExitWith` (R3) | pass |
| Could this check pass while the thing it checks is false? | n/a | pass: every stub writes what it was given to a file the test reads; the signal case asserts 143 and not merely "non-zero"; the token case greps the arguments and the captured output for the token's value; the subprocess test reads the plugin's exit code after a real SIGINT |
| Reference facts are generated; a page stands alone | n/a | pass: the `plugin` command enters the block through `CliReferenceSuite`; the Plugins prose is hand-written beside it; the block is proved free of plugin names (R6, R10) |
| The honest limitations list | n/a | pass: `ankka mcp` serves no plugin, stated on the limitations page (R10) |
| Only a tag publishes; Homebrew, native builds | n/a | pass: nothing in the release changes; the native smoke covers process spawning (R11) |
| Be your own customer; nothing product-specific in the platform | pass: the CLI learns no product noun; `ankka-vendor` is a program the private repository ships | pass |
| Split by lifecycle and trust | pass: a plugin is trusted as a program on the PATH is; `plugin list` shows which file would run | pass |
| Wire names declared separately | n/a: no wire | n/a |

No violation; Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/056-cli-plugins/
├── spec.md
├── plan.md              # this file
├── research.md          # R1 to R11
├── data-model.md        # built-in command, candidate, plugin, environment, credential, outcome
├── quickstart.md        # how to see it work and when it is done
├── contracts/
│   └── cli-plugins.md   # what a plugin may rely on
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks, not written here
```

### Source Code (repository root)

```text
cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala           # builtIn set; dispatch before decline; `plugin list`; help appendix
├── Plugins.scala        # NEW: the path, candidates and statuses, running one, the usage errors
├── Session.scala        # Credential enum; `bearer` over it; `credential(settings)` for plugins
├── Settings.scala       # unchanged (resolve with no flags); path made absolute by the caller
└── Output.scala         # plugins(rows, format): table and JSON

cli/src/test/scala/com/thinkmorestupidless/ankka/cli/
├── PluginSuite.scala    # NEW: one test per scenario of features/command-line/plugins.feature
└── CliReferenceSuite.scala   # builtIn equals the root help's subcommands; no Plugins line in the block

cli/native-smoke.sh      # a stub plugin on a temporary PATH: run it, list it

docs/reference/
├── cli.md               # ## Plugins (prose), regenerated block with `ankka plugin list`
└── limitations.md       # `ankka mcp` serves no plugin

features/command-line/plugins.feature   # written at specify; unchanged by the plan
GLOSSARY.md                              # settled at clarify; unchanged by the plan
```

**Structure Decision**: everything lands in the existing `cli` module; the lookup and the run are
one new file so `Main` keeps only wiring. No new module, package or resource.

## Complexity Tracking

No constitution violations to justify.

## Design notes for the tasks

Pointers the task list should follow, each with its research item.

1. **`Session.Credential` first** (R5): refactor `bearer` to call `credential` and throw on the two
   non-token cases with the messages it has today. `DeviceFlowSuite` must stay green unchanged: that
   is the proof no built-in command's behaviour moved.
2. **`Plugins.scala`** (R2, R3, R4, R8): `Plugins.directories` (property, then PATH), `candidates()`
   (every `ankka-*` entry in path order with its status), `find(word)` (the one that runs, or why
   not), `run(program, args, env)` (ProcessBuilder, SIGINT ignored, the exit code), and the
   environment builder over `Settings.resolve(None, None, None)`, `Session.credential`, the CA path
   made absolute against the working directory (as `ControlPlaneClient` reads it) and
   `Settings.path`. Refuse a word with `/` or `\` before any lookup.
3. **`Main`** (R1, R6, R7): `builtIn`, the dispatch at the top of `run`, the `plugin` command with
   `list` and `formatOpt`, the help appendix when `args` is empty or `Seq("--help")`, on standard
   error after decline's help, which is where `run` prints every `Left(help)` today, exit code 2.
4. **`Output.plugins`** (R7): table headers `PLUGIN PROGRAM STATUS`, the status words of the
   contract, `no plugins on the path` for none; JSON with `word`, `program`, `status`, `hiddenBy`.
5. **`PluginSuite`** (R9): the stubs, the issuer, the scenario names as test names; the subprocess
   test for streams and SIGINT. Clean the scratch directory before removing `ankka.config` (the
   credentials trap in `control-plane.md`).
6. **`CliReferenceSuite`** (R1, R6): the two assertions, then `just docs-reference` and commit the
   page.
7. **Docs** (R10): the Plugins section, the limitations bullet, `just docs-sync`, `just docs`.
8. **Smoke** (R9): the step in `native-smoke.sh`; it runs in the release's `cli-native` job.
