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
out (`docs/reference/cli.md`). The CLI holds no state beyond those two files. Today a first word
that names none of its thirteen commands is a usage error, exit code 2.

Products built on ankka want commands of their own: `ankka vendor modules list` for a vendor's
product, `ankka casino brands` for an operator's manifest tool. Building them into the CLI would
put product nouns into the platform and tie every product's release to ankka's. Giving each product
its own binary loses the login, the saved settings and the habit of one tool. The shape git,
kubectl and gh settled on resolves both: a program on the PATH named for the command, run by the
host tool with the context the host has already worked out.

This feature adds that dispatch and nothing else. The CLI learns no command a plugin provides, asks
a plugin nothing about itself, and serves no plugin to a coding agent through `ankka mcp`; those
are later features if they are wanted. The plugin is trusted exactly as any program on the PATH is:
`ankka plugin list` shows which file would run so a person can check.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A product's command runs as if it were built in (Priority: P1)

A person who has installed a product's plugin, `ankka-vendor`, runs `ankka vendor modules list`.
The CLI finds no built-in command named `vendor`, finds the program `ankka-vendor` on the PATH, and
runs it with the arguments `modules list`. What the program reads and prints is the person's
terminal, and the program's exit code is the command's. Flags before the word `vendor` belong to
the CLI; everything after it belongs to the plugin, including `--help`.

**Why this priority**: This is the feature. Without the dispatch nothing else here matters.

**Independent Test**: Put a small program named `ankka-echo` on the PATH that prints its
arguments and exits with a chosen code; run `ankka echo a b` and `ankka -o json echo --flag` and
compare output and exit codes.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: a word that names no built-in command runs the plugin of that name with the rest of the command line
- added `features/command-line/plugins.feature`: the plugin's exit code is the command's exit code
- added `features/command-line/plugins.feature`: a flag before the plugin's name is the command line's, a flag after it is the plugin's
- added `features/command-line/plugins.feature`: a word that names neither a built-in command nor a plugin is a usage error
- added `features/command-line/plugins.feature`: a plugin that cannot be run fails the command, naming the program
- added `features/command-line/plugins.feature`: a plugin's name holds no directory

---

### User Story 2 - The plugin is given what the CLI had already resolved (Priority: P1)

Before running the plugin the CLI resolves the control plane URL, the token, the project, the extra
trusted CA file and the output format exactly as it would for one of its own commands, and sets
them in the plugin's environment as `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA` and
`ANKKA_OUTPUT`. A saved login whose access token has run out is refreshed and saved first, as it
would be for a built-in command. When no token can be resolved the plugin still runs, with
`ANKKA_TOKEN` unset: a plugin may need no control plane, or may want to say `run 'ankka login'`
itself. The token reaches the plugin by its environment only, never by an argument or a file.

**Why this priority**: The login and the settings are the reason to be a plugin rather than a
separate tool.

**Independent Test**: With `ankka-env` on the PATH printing its environment, log in, set a project,
and run `ankka env`; then run with an expired saved login and with no login at all.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: a plugin is given the settings a built-in command would have resolved
- added `features/command-line/plugins.feature`: a saved login that has run out is refreshed before the plugin runs
- added `features/command-line/plugins.feature`: a plugin is given no token when none can be resolved, and still runs
- added `features/command-line/plugins.feature`: the token reaches the plugin by its environment alone

---

### User Story 3 - A person can see what would run (Priority: P2)

`ankka plugin list` names every program on the PATH whose name starts `ankka-`, with the file that
would run. A plugin named like a built-in command, `ankka-services` say, is listed as shadowed and
is never run, because built-in commands always win. `ankka --help` names the plugins it found
under their own heading, beside the built-in commands, so the person learns what the tool can do
on this machine.

**Why this priority**: A tool that runs whatever is on the PATH owes the person a way to see what
that is.

**Independent Test**: Put `ankka-vendor`, `ankka-casino` and `ankka-services` on the PATH; run
`ankka plugin list` and `ankka --help`; run `ankka services list` and confirm the built-in answers.

**Acceptance Scenarios**:

- added `features/command-line/plugins.feature`: the plugin list names every plugin on the path and where it is
- added `features/command-line/plugins.feature`: a plugin named like a built-in command is listed as shadowed and never run
- added `features/command-line/plugins.feature`: help names the plugins found beside the built-in commands

---

### Edge Cases

- A plugin that exits by a signal: the command's exit code is 128 plus the signal's number, as a
  shell would report it, and nothing is printed by the CLI.
- Two programs of the same plugin name on the PATH: the first in PATH order runs, as a shell would
  choose; `ankka plugin list` shows the one that would run and notes the other.
- A PATH that is unset or empty: no plugin is found; the usage error says so.
- The word is a built-in command's name given in a different case (`Services`): a usage error, not
  a plugin lookup; command names are exact.
- `ankka plugin` with no further word: usage, exit code 2. `plugin` is itself a built-in command
  and cannot be shadowed.
- A plugin that needs the CLI's trust store for a self-signed control plane: `ANKKA_CA` names the
  file; the CLI reads nothing on the plugin's behalf.
- `ankka mcp`: serves no plugin. A coding agent reaches a plugin's commands only by running the
  shell. Named in the reference as a limitation.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A first argument that names no built-in command MUST be looked up as a plugin: a
  program named `ankka-<word>` on the PATH, in PATH order.
- **FR-002**: The CLI MUST run the plugin with the remaining arguments unchanged, with standard
  input, standard output and standard error inherited, and MUST exit with the plugin's exit code.
- **FR-003**: Flags before the plugin's word are the CLI's; the CLI MUST pass every argument after
  the word to the plugin untouched, including `--help`.
- **FR-004**: Before running a plugin the CLI MUST resolve the control plane URL, token, project,
  extra trusted CA file and output format as it does for its own commands, and set
  `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA` and `ANKKA_OUTPUT` in the plugin's
  environment for each that resolves; a setting that does not resolve is left unset.
- **FR-005**: A saved login whose access token has run out MUST be refreshed and saved before the
  plugin runs, as for a built-in command. When no token can be resolved the plugin MUST still run.
- **FR-006**: The token MUST reach the plugin by its environment only: never as an argument, never
  written to a file, never printed.
- **FR-007**: A word that names neither a built-in command nor a plugin MUST be a usage error with
  exit code 2 whose message names the word and the program name looked for.
- **FR-008**: A plugin found but not runnable MUST fail the command with exit code 1 and a message
  naming the file.
- **FR-009**: A word containing a path separator MUST be refused as a usage error; a plugin is
  found by name on the PATH only.
- **FR-010**: Built-in commands MUST always win: a plugin named like one is never run.
- **FR-011**: `ankka plugin list` MUST name every plugin on the PATH with the file that would run,
  marking any that a built-in command shadows and any that a same-named earlier file hides.
- **FR-012**: `ankka --help` MUST list the plugins found, by word, under a heading of their own.
- **FR-013**: The reference documentation MUST describe the dispatch, the five environment
  variables, the exit codes, and that `ankka mcp` serves no plugin.

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
- **SC-002**: A product's commands ship as one program on the PATH with no change to ankka; the
  first, `ankka-vendor`, is built and released from ankka-vendor's repository.
- **SC-003**: Every setting a built-in command can see, a plugin can see, by the documented
  environment variable, in every case the CLI's own resolution order covers.
- **SC-004**: The living features above pass against the built CLI with stub programs on the PATH,
  on macOS and Linux.

## Assumptions

- Plugins are distributed by the family's Homebrew tap, as the CLI is. An install command of the
  CLI's own waits for a second channel.
- A plugin receives no description protocol: `ankka --help` shows plugin words only. If the console
  or `ankka mcp` later wants to describe plugin commands, that is a feature of its own.
- Windows is out of scope, as it is for the CLI today.
- The environment variable names are the CLI's existing ones, so a plugin written against the
  documented settings needs nothing new; `ANKKA_OUTPUT` is the one addition and is documented with
  the others.
