# Research: Command Line Plugins

Every decision below was checked against the CLI as it is in `cli/src/main/scala` on 2026-10-21, not
against how it is documented. Where the two differ, the code is cited.

## R1. Where the dispatch happens: before decline, on an explicit list of built-in names

**Decision**: `Main.run` looks at the first argument before handing anything to decline. A first
argument that does not start with `-` and is not in `Main.builtIn` (an explicit `Set[String]` of the
fourteen command words, `plugin` included) is a plugin word. Everything else goes to
`command.parse` as today. A test asserts `Main.builtIn` equals the subcommands the root help prints
(`CliReferenceSuite.subcommands(help(Seq.empty))`), so a command added to the tree without being added
to the list fails the build rather than becoming shadowable.

**Rationale**: decline reports an unknown subcommand as a `Help` whose `errors` say
`Unexpected argument: vendor`; recognising that by its text would make the dispatch depend on a
library's wording. The command tree does not expose its subcommand names as values. An explicit list
is the repository's habit ("explicit registration, never scanning") and the test keeps it honest.

**Alternatives considered**: parsing decline's `Help.errors` (fragile); a decline `Opts.arguments`
catch-all at the root (decline tries alternatives in order and a catch-all swallows usage errors for
built-in commands too).

## R2. Finding the program: PATH, overridable, matched by exact name

**Decision**: the directories come from `sys.props.get("ankka.path")`, else `sys.env.get("PATH")`,
split on `File.pathSeparator`, empty entries dropped. In each directory, in order, the CLI lists the
entries and takes the one whose file name *equals* `ankka-<word>` (a `Files.list` and a string
comparison, not `Files.exists`), that is a regular file, and that `Files.isExecutable`. A match that is
not executable is skipped and remembered for `plugin list`. A directory that cannot be listed is
skipped silently, as a shell skips it. An unset or empty PATH finds nothing and the usage error says so.

**Rationale**: `-Dankka.path` follows the `-Dankka.config` trap in `.claude/rules/control-plane.md`:
environment variables cannot be set in-process, so a test cannot otherwise drive the lookup through
`Main.run`. Matching by listing is what makes `ankka Services` miss `ankka-services` on APFS, which
ignores case on `Files.exists`; FR-001 and the glossary's exact-name rule need it. Skipping a
non-executable file is what `execvp` does.

**Alternatives considered**: `Files.exists(dir.resolve(name))` (case-insensitive on macOS, wrong);
also searching beside the `ankka` executable, as git does with its exec path (Homebrew puts plugins on
the PATH anyway; a second search root is a second thing to document and to get wrong).

## R3. Running it: `ProcessBuilder`, inherited streams, the child's exit code

**Decision**: `new ProcessBuilder((program +: rest)*).inheritIO()`, the resolved variables `put` into
`builder.environment()` over the inherited environment, `start()`, `waitFor()`, and the result is
`Main.run`'s return value through `ExitWith(code)`. The JDK's Unix process implementation reports a
child ended by a signal as `128 + signal` (`ProcessImpl`: `WIFSIGNALED → 0x80 + WTERMSIG`), which is
exactly FR-011's rule, so nothing maps it. A `start()` that throws `IOException` (an interpreter in the
shebang that is missing, a file the kernel refuses) is FR-008: exit 1, `error: cannot run <file>: <reason>`.

**Rationale**: `LocalWeb.run` and `Init` already spawn with `inheritIO()`; GraalVM's native image uses
the same process implementation, so the exit code rule holds for both builds. Inheriting the streams,
rather than pumping them, keeps the plugin's `isatty` true and its colours and prompts working.

**Alternatives considered**: pumping the child's streams through `Console.out` so a test could capture
them (breaks terminals for the plugin; the in-process suite proves the pass-through by a stub that
writes to a file, and one subprocess test plus `native-smoke.sh` prove the real streams, R9).

## R4. Ctrl-c: the CLI ignores SIGINT while a plugin runs

**Decision**: around `waitFor()`, `sun.misc.Signal.handle(new Signal("INT"), _ => ())`. The terminal
delivers SIGINT to the whole foreground process group, so the plugin receives it itself; the CLI, now
ignoring it, keeps waiting and returns the plugin's code, 130 when the plugin died of it. The handler
is not restored: the process ends right after.

**Rationale**: without this the JVM's default SIGINT handling runs shutdown hooks and exits while
the plugin is still finishing, so the shell prompt comes back under the plugin's output, which is the
behaviour FR-011 forbids. `sun.misc.Signal` is supported by Substrate VM, so the native build behaves
the same; the native image's properties install no exit handlers today, so the default there would be
even blunter (the image dies at once). `LocalWeb` destroys its child on interrupt instead, deliberately:
there the child is a server the CLI started for its own proxy, here the child *is* the command.

**Alternatives considered**: `Runtime.addShutdownHook` that waits for the child then `halt`s with its
code (works on HotSpot; needs `--install-exit-handlers` in the native image, a change to how every
other command dies); doing nothing (fails the scenario).

## R5. What the plugin is given: `Settings.resolve` with no flags, a non-throwing `Session`

**Decision**: `Settings.resolve(None, None, None)` gives the URL (always, `DefaultUrl` at least), the
project and the CA as the saved file and the environment resolve them. The token comes from a new
`Session.credential(settings): Session.Credential`, an enum the existing `Session.bearer` is
refactored over: `Token(value)`, `NotLoggedIn`, `Refused(reason)`. `bearer` throws on the last two as
it does today, so no built-in command changes; the plugin path sets `ANKKA_TOKEN` on `Token`, sets
nothing on `NotLoggedIn`, and on `Refused` sets nothing and prints
`warning: <url> rejected the login (<reason>); run 'ankka login'` on standard error. A refresh that
succeeds is saved by `Session`, as today. A refresh that cannot be made, because `flow.discover` or
`flow.refresh` threw, is `Refused` too, with the exception's message as the reason; `bearer` today
catches nothing there, so for a built-in command the refactor turns an escaping stack trace into the
same `rejected the login` message. `ANKKA_CA` is `Paths.get(ca).toAbsolutePath`, resolved against the
working directory exactly as `ControlPlaneClient` and `DeviceFlow` read the same value (`Trust` takes a
`Path` and expands nothing). `ANKKA_CONFIG` is `Settings.path.toAbsolutePath`.

**Rationale**: the spec's resolution order is the CLI's, so reuse the CLI's function; the only new
behaviour is not failing when there is no token, which is why `Session` grows a value-returning seam
instead of a second copy of the refresh. The warning goes to standard error so a plugin's standard
output stays a script's. `ANKKA_URL` is set even when nothing was configured because `Settings` has a
default and a plugin should not reimplement it.

**Alternatives considered**: catching `ApiError` from `bearer` and reading its message (text matching
again); passing the token on a file descriptor (no plugin runtime reads one without work; the
environment is what the reference already documents).

## R6. `--help` lists plugins without touching the generated reference

**Decision**: when the arguments are empty or exactly `--help`, `Main.run` prints decline's root help
as today (on standard error, with exit code 2: decline returns `--help` as a `Left`, and `run` treats
every `Left` the same, `Main.scala:1661-1665`) and, when the lookup finds any plugin that is not
shadowed, appends on the same stream

```
Plugins (programs named ankka-<word> on the path):
    vendor
    reports
```

`CliReferenceSuite` renders the page from `Main.command.parse(... "--help")`, never through
`Main.run`, so the block cannot carry a plugin, and the suite gains an assertion that the rendered block
has no `Plugins` line. The new `plugin` command appears in the generated block like any other.

**Rationale**: decline's help text is immutable from outside the tree, so the list is an appendix, not
a subcommand entry; FR-013 forbids the reference depending on the generating machine's PATH, and the
split between `run` and `command.parse` already gives that for free.

## R7. `ankka plugin list`: a table or JSON, from `Output`

**Decision**: a decline subcommand `plugin` with one subcommand `list` taking `formatOpt` only (no
settings are needed). `Plugins.list()` returns every candidate on the PATH in order: `word`, `program`
(absolute path) and a `status`: `runs`, `shadowed` (a built-in word), `hidden` (an earlier program of
the same name runs instead, named), `not executable`. `Output.plugins(rows, format)` renders the
`table` helper with headers `PLUGIN  PROGRAM  STATUS` (an empty list prints `no plugins on the path`,
following `table`'s `no results` idiom but saying what was searched) or a JSON array of objects with
those three fields plus `hiddenBy` where it applies (the program that runs instead). `ankka plugin` alone is a decline usage error, exit 2,
as every parent command is.

**Rationale**: `Output.table` is the one place the CLI aligns columns; a status word is more useful to a
person than a mark, and a JSON shape with the same fields is what a script would want.

## R8. The usage error for a word that is nothing

**Decision**: exit 2, on standard error:

```
ankka: 'reports' is not an ankka command, and no program 'ankka-reports' is on the path
See 'ankka --help'.
```

With PATH unset or empty, the second clause reads `and PATH is empty, so no plugin can be found`. A
word containing `/` (or `\`) is refused before any lookup with `ankka: a plugin is named by a word, not
a path: '../vendor'`, exit 2.

**Rationale**: FR-007 wants the word and the program name in the message; a person who typoed a
built-in command and a person who forgot to install a plugin both learn what was looked for.

## R9. Proving it

**Decision**: a new `PluginSuite` in `cli`'s tests drives `Main.run` in process, with `-Dankka.config`
pointed at a scratch directory and `-Dankka.path` at a scratch `bin/` holding `#!/bin/sh` stubs that
write their arguments and environment to a file the test reads. Scenarios map one to one onto
`features/command-line/plugins.feature`: the exit code (`exit 3`), the signal (`kill -TERM $$`, expect
143), the settings (a saved file and a `FakeIssuer` login, as `DeviceFlowSuite` uses), the expired
login (`expiresAt` in the past; the issuer records one refresh), the refused refresh
(`issuer.refuseRefresh = true`; stderr holds the warning, the env file lacks the token), no token, the
token nowhere in the arguments or in what the command printed, the usage error, the non-executable
file (`chmod -x`, earlier on the path), the exact case (`ankka Vendor` on a case-insensitive disk
exits 2), the directory in the word, `plugin list` in both formats with a shadowed `ankka-services`,
and the built-in winning. One test spawns the real CLI as a subprocess on the test classpath (the
pattern `ControlPlaneClusterSuite` uses) to prove the inherited standard output and the ctrl-c wait:
it sends SIGINT to the process group and reads the plugin's own exit code back. `CliReferenceSuite`
gains the no-`Plugins`-line assertion and `Main.builtIn` check. `native-smoke.sh` gains a plugin step:
a stub `ankka-smoke` in a temporary directory prepended to PATH, run as `ankka smoke hello`, its
output and exit code compared, and `ankka plugin list` naming it.

**Rationale**: `.claude/rules/testing.md` wants each acceptance scenario to be a test that fails
without the feature; the stubs make every one deterministic and offline. The feature file is not run by
`GherkinSuite` because its steps are shell-level (processes, signals, the PATH), which the CLI suites
in this tree hold by name in comments, as `LocalWebSuite` does for `features/web-hosting/local.feature`.

## R10. Documentation and the reference

**Decision**: `docs/reference/cli.md` gains a `## Plugins` section before `## Commands`: the dispatch,
the five variables in a table (`ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA`,
`ANKKA_CONFIG`, each with when it is set), that a plugin parses its own flags, the exit codes (the
plugin's; 2 for a word that is nothing; 1 for a program that will not start; 128 + signal), the
`plugin list` statuses, and that `ankka mcp` serves no plugin. The generated block is rewritten with
`just docs-reference` once the `plugin` command exists. `docs/reference/limitations.md` gains one
bullet under *SDKs and releases*: `ankka mcp` serves no plugin's commands; an agent reaches them by
running the shell. The page is already in the `ankka-deploy` and `ankka-platform` skills, so no skill
list changes; `just docs-sync` refreshes the rendered skill.

**Rationale**: `.claude/rules/docs.md`: reference facts beside the generated block are prose a page
stands alone with, and a new command fails `CliReferenceSuite` until the page is regenerated.

## R11. What does not change

No new library. No change to `controlplane-api`, the control plane or `ankka mcp` (`AnkkaTools`
hand-lists its tools; nothing enumerates the command tree). No change to the Homebrew formula, the
release workflow or `ci-coverage.py` (`cli/**` and `features/**` are already claimed). No new resource
in the native image (`native-smoke.sh` still gains a step, because process spawning is a thing a native
build can get wrong). Windows stays out of scope, as the limitations page already says.
