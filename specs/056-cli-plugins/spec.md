# Feature Specification: Command Line Plugins

**Feature Branch**: `056-cli-plugins`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "Plugin support for the ankka CLI: a first word that names no
built-in command runs a program `ankka-<word>` found on the PATH with the rest of the command line,
passing through standard input, output, error and the exit code, and handing the program the
settings and credentials the CLI would have resolved for one of its own commands, in the
environment variables the CLI already documents. `ankka plugin list` shows what is found. A
product ships its own commands this way, `ankka vendor modules` first, without ankka knowing the
product."

## Context

`ankka` is thin by design: every command makes one HTTP call to a control plane and prints the
result, settings come from a flag, then an environment variable, then a saved file, and credentials
from a flag, then the environment, then a saved login that is refreshed silently when it has run
out (`docs/reference/cli.md`). The CLI holds no state beyond those two files. Its flags (`--url`,
`--token`, `--project`, `--output`) belong to each command and come after it; nothing may come
between `ankka` and the command's word. Today a first word that names none of its thirteen commands
is a usage error, exit code 2.

Products built on ankka want commands of their own: `ankka vendor modules list` for a vendor's
product, another product's commands for its own nouns. Building them into the CLI would
put product nouns into the platform and tie every product's release to ankka's. Giving each product
its own binary loses the login, the saved settings and the habit of one tool. The shape git,
kubectl and gh settled on resolves both: a program on the PATH named for the command, run by the
host tool with the context the host has already worked out.

This feature adds that dispatch and nothing else. The CLI learns no command a plugin provides, asks
a plugin nothing about itself, and serves no plugin to a coding agent through `ankka mcp`; those
are later features if they are wanted. The plugin is trusted exactly as any program on the PATH is:
`ankka plugin list` shows which file would run so a person can check.

## Clarifications

### Session 2026-10-21

- Q: Which of the six proposed glossary words stand? → A: *plugin*, *built-in command*, *shadowed* and *saved login* are terms; *path* is an everyday word; *plugin list* is not a term, the scenarios say "the list from `ankka plugin list`".
- Q: What does a plugin learn about the `ankka` that ran it, beyond the four settings? → A: `ANKKA_CONFIG`, set to the settings file's resolved path, so a plugin's own files sit beside the CLI's wherever the CLI was pointed. Not the CLI's own path.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A product's command runs as if it were built in (Priority: P1)

A person who has installed a product's plugin, `ankka-vendor`, runs `ankka vendor modules list`.
The CLI finds no built-in command named `vendor`, finds the program `ankka-vendor` on the PATH, and
runs it with the arguments `modules list`. What the program reads and prints is the person's
terminal, and the program's exit code is the command's. Everything after the word `vendor` belongs
to the plugin, untouched, including `--help`, `--project` and `--output`: the CLI parses none of
it, as it parses a built-in command's flags only after that command's word.

**Why this priority**: This is the feature. Without the dispatch nothing else here matters.

**Independent Test**: Put a small program named `ankka-echo` on the PATH that prints its
arguments and exits with a chosen code; run `ankka echo a b` and `ankka echo -o json --project p-2`
and compare output and exit codes.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: a word that names no built-in command runs the plugin of that name with the rest of the command line
- added `features/command-line/plugins.feature`: the plugin's exit code is the command's exit code
- added `features/command-line/plugins.feature`: every flag after the plugin's name is the plugin's, untouched
- added `features/command-line/plugins.feature`: a plugin ended by a signal ends the command with 128 and the signal's number
- added `features/command-line/plugins.feature`: a word that names neither a built-in command nor a plugin is a usage error
- added `features/command-line/plugins.feature`: a plugin's name is matched exactly, whatever case the filesystem ignores
- added `features/command-line/plugins.feature`: a program of a plugin's name that is not executable is passed over
- added `features/command-line/plugins.feature`: a plugin that cannot be run fails the command, naming the program
- added `features/command-line/plugins.feature`: a plugin's name holds no directory

---

### User Story 2 - The plugin is given what the CLI had already resolved (Priority: P1)

Before running the plugin the CLI resolves the control plane URL, the token, the project and the
extra trusted CA file exactly as it would for one of its own commands given no flags (the
environment, then the saved settings, then the saved login), and sets them in the plugin's
environment as `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT` and `ANKKA_CA`. The URL always resolves,
to its default if nothing else; the CA file is given as an absolute path. `ANKKA_CONFIG` is set to
the settings file the CLI itself read, as an absolute path, so a plugin that keeps files of its own
keeps them beside the CLI's wherever a test or a job pointed the CLI. The plugin is not told which
`ankka` ran it. A saved login whose access
token has run out is refreshed and saved first, as it would be for a built-in command. When no
token can be resolved the plugin still runs, with `ANKKA_TOKEN` unset: a plugin may need no control
plane, or may want to say `run 'ankka login'` itself. A refresh the identity provider refuses, or
cannot be asked for, does not stop the plugin either: it runs without a token, and the CLI says on
standard error why there is none. The token reaches the plugin by its environment only, never by an
argument, and is written nowhere but the saved login the CLI already keeps.

A plugin that takes its own `--url`, `--project` or `--output` flags reads them itself; the
environment it is given is what those flags would override.

**Why this priority**: The login and the settings are the reason to be a plugin rather than a
separate tool.

**Independent Test**: With `ankka-env` on the PATH printing its environment, log in, set a project,
and run `ankka env`; then run with an expired saved login and with no login at all.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: a plugin is given the settings a built-in command would have resolved
- added `features/command-line/plugins.feature`: a plugin is told where the settings are saved
- added `features/command-line/plugins.feature`: a saved login that has run out is refreshed before the plugin runs
- added `features/command-line/plugins.feature`: a plugin is given no token when none can be resolved, and still runs
- added `features/command-line/plugins.feature`: a refused refresh leaves the plugin without a token, and says why
- added `features/command-line/plugins.feature`: the token reaches the plugin by its environment alone

---

### User Story 3 - A person can see what would run (Priority: P2)

`ankka plugin list` names every program on the PATH whose name starts `ankka-`, with the file that
would run, in a table or, with `-o json`, as JSON. A file of that name that is not executable is
listed as such and is not a plugin. A plugin named like a built-in command, `ankka-services` say, is listed as shadowed and
is never run, because built-in commands always win. `ankka --help` names the plugins it found
under their own heading, beside the built-in commands, so the person learns what the tool can do
on this machine. The generated command reference never lists plugins: it describes the CLI, not the
machine it was built on.

**Why this priority**: A tool that runs whatever is on the PATH owes the person a way to see what
that is.

**Independent Test**: Put `ankka-vendor`, `ankka-reports` and `ankka-services` on the PATH; run
`ankka plugin list` and `ankka --help`; run `ankka services list` and confirm the built-in answers.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: the plugin list names every plugin on the path and where it is
- added `features/command-line/plugins.feature`: a plugin named like a built-in command is listed as shadowed and never run
- added `features/command-line/plugins.feature`: help names the plugins found beside the built-in commands
- added `features/command-line/plugins.feature`: the command reference names no plugin

---

### Edge Cases

- A plugin that exits by a signal: the command's exit code is 128 plus the signal's number, as a
  shell would report it, and nothing is printed by the CLI.
- Two programs of the same plugin name on the PATH: the first in PATH order runs, as a shell would
  choose; `ankka plugin list` shows the one that would run and notes the other.
- A person presses ctrl-c while a plugin runs: the interrupt reaches the plugin, and the CLI waits
  for it and exits with its code rather than ending first and leaving the plugin running.
- A file named `ankka-<word>` on the PATH that is not executable: skipped, as a shell skips it, and
  the search goes on; `ankka plugin list` shows it as not executable.
- A PATH that is unset or empty: no plugin is found; the usage error says so.
- The word is a built-in command's name given in a different case (`Services`): command names are
  exact, so it is looked up as a plugin, and plugin names are exact too: on a filesystem that
  ignores case, `ankka-services` is not the plugin `Services`. The result is a usage error.
- A flag where the command's word belongs (`ankka -o json vendor`): a usage error, as today; flags
  come after the word.
- `ankka plugin` with no further word: usage, exit code 2. `plugin` is itself a built-in command
  and cannot be shadowed.
- A plugin that needs the CLI's trust store for a self-signed control plane: `ANKKA_CA` names the
  file; the CLI reads nothing on the plugin's behalf.
- `ankka mcp`: serves no plugin. A coding agent reaches a plugin's commands only by running the
  shell. Named in the reference as a limitation.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A first argument that names no built-in command MUST be looked up as a plugin: an
  executable file named exactly `ankka-<word>`, compared by name and case whatever the filesystem,
  in the directories of the PATH, in PATH order.
- **FR-002**: The CLI MUST run the plugin with the remaining arguments unchanged, with standard
  input, standard output and standard error inherited, and MUST exit with the plugin's exit code.
- **FR-003**: The CLI MUST parse no argument after the plugin's word: every one, `--help`,
  `--url`, `--project` and `--output` included, reaches the plugin untouched.
- **FR-004**: Before running a plugin the CLI MUST resolve the control plane URL, token, project and
  extra trusted CA file as it does for its own commands given no flags, and set `ANKKA_URL`,
  `ANKKA_TOKEN`, `ANKKA_PROJECT` and `ANKKA_CA` in the plugin's environment for each that resolves;
  a setting that does not resolve is left unset. `ANKKA_CA` MUST be an absolute path.
- **FR-004a**: The CLI MUST set `ANKKA_CONFIG` in the plugin's environment to the absolute path of
  the settings file it read, whether that came from the default location, `ANKKA_CONFIG` or
  `-Dankka.config`.
- **FR-005**: A saved login whose access token has run out MUST be refreshed and saved before the
  plugin runs, as for a built-in command. When no token can be resolved, or a refresh is refused or
  cannot be made, the plugin MUST still run without a token, and in the second case the CLI MUST say
  why on standard error.
- **FR-006**: The token MUST reach the plugin by its environment only: never as an argument, never
  printed, and written nowhere but the saved login the CLI already keeps.
- **FR-007**: A word that names neither a built-in command nor a plugin MUST be a usage error with
  exit code 2 whose message names the word and the program name looked for.
- **FR-008**: A plugin found that fails to start (a missing interpreter, a file the system will not
  execute) MUST fail the command with exit code 1 and a message naming the file.
- **FR-009**: A word containing a path separator MUST be refused as a usage error; a plugin is
  found by name on the PATH only.
- **FR-010**: Built-in commands MUST always win: a plugin named like one is never run.
- **FR-011**: While a plugin runs the CLI MUST wait for it through an interrupt and exit with the
  plugin's code; a plugin ended by a signal exits the command with 128 plus the signal's number.
- **FR-012**: `ankka plugin list` MUST name every plugin on the PATH with the file that would run,
  marking any that a built-in command shadows, any that a same-named earlier file hides, and any
  file of a plugin's name that is not executable; `-o json` MUST print the same as JSON.
- **FR-013**: `ankka --help` MUST list the plugins found, by word, under a heading of their own. The
  generated command reference MUST NOT depend on what is on the PATH of the machine that generates
  it.
- **FR-014**: The reference documentation MUST describe the dispatch, the five environment
  variables a plugin is given, that a plugin parses its own flags, the exit codes, and that
  `ankka mcp` serves no plugin.

### Key Entities

- **Plugin**: a program on the PATH named `ankka-<word>`, run as the command `ankka <word>`.
- **Built-in command**: a command the CLI answers itself: `login`, `logout`, `whoami`, `status`,
  `installation`, `organizations`, `projects`, `services`, `config`, `version`, `init`, `local`,
  `mcp`, and now `plugin`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A plugin command's own work aside, the dispatch adds no more than 50 ms to the
  command on a laptop: finding the program and resolving the settings, with no network call unless
  a login needs refreshing.
- **SC-002**: A product's commands ship as one program on the PATH, built and released from the
  product's own repository, with no change to ankka.
- **SC-003**: Every setting a built-in command given no flags would use, a plugin is given, by the
  documented environment variable, in every case the CLI's own resolution order covers.
- **SC-004**: The living features above pass against the built CLI with stub programs on the PATH,
  on macOS and Linux.

## Assumptions

- Plugins are distributed by the family's Homebrew tap, as the CLI is. An install command of the
  CLI's own waits for a second channel.
- A plugin receives no description protocol: `ankka --help` shows plugin words only. If the console
  or `ankka mcp` later wants to describe plugin commands, that is a feature of its own.
- Windows is out of scope, as it is for the CLI today.
- The environment variable names are the CLI's existing ones, so a plugin written against the
  documented settings needs nothing new. There is no output format in the environment: the CLI has
  no such setting, and a plugin that offers `--output` parses it itself.
- The CLI's flags stay where they are, after a command's word. Accepting them before the word, for
  plugins or for every command, was considered and left out: a plugin is given resolved settings and
  parses its own flags, as kubectl's plugins do.
