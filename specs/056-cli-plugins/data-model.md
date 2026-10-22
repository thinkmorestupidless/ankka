# Data Model: Command Line Plugins

Nothing here is stored. These are the values the CLI holds between reading the command line and
starting the plugin, and what `ankka plugin list` prints.

## Built-in command

The fourteen words the CLI answers itself: `login`, `logout`, `whoami`, `status`, `installation`,
`organizations`, `projects`, `services`, `config`, `version`, `init`, `local`, `mcp`, `plugin`. Held
once as `Main.builtIn: Set[String]`; a test holds it equal to the subcommands of the root help, so the
list and the tree cannot drift. Compared exactly: `Services` is not a built-in command.

## Candidate

One entry found on the path whose name is `ankka-` and a word.

| Field | Type | Meaning |
|---|---|---|
| `word` | String | The file name after `ankka-`; what a person types |
| `program` | Path, absolute | The file |
| `status` | `Runs` \| `Shadowed` \| `Hidden(by: Path)` \| `NotExecutable` | Whether `ankka <word>` would run it |

Rules:

- The status is decided per word in path order. The first executable candidate for a word that is
  not a built-in command is `Runs`; a later executable one is `Hidden(by = the first's program)`; any
  candidate whose word is a built-in command is `Shadowed`; a candidate that is not a regular,
  executable file is `NotExecutable`, whatever its position, and does not hide or run.
- A word is valid only when it holds no `/` and no `\`. The lookup is never made for one that does.
- Two candidates in one directory cannot share a word: file names are unique within a directory and
  compared exactly.

## Plugin

A candidate whose status is `Runs`: the one program `ankka <word>` starts. A word has at most one.

## Plugin environment

What the plugin is given beyond the CLI's own inherited environment. Each is set only when it
resolves; a variable the CLI's own environment already held is overwritten with the resolved value,
which under the resolution order is the same value.

| Variable | Source, in order | Set when |
|---|---|---|
| `ANKKA_URL` | `ANKKA_URL`, saved `url`, `http://localhost:9000` | always |
| `ANKKA_TOKEN` | `ANKKA_TOKEN`, saved `token`, the saved login's access token, refreshed and saved when it has run out | a token resolves; never on `NotLoggedIn` or `Refused` |
| `ANKKA_PROJECT` | `ANKKA_PROJECT`, saved `project` | a project resolves |
| `ANKKA_CA` | `ANKKA_CA`, saved `ca`; made absolute against the working directory, as a built-in command reads it | a CA file resolves |
| `ANKKA_CONFIG` | `-Dankka.config`, `ANKKA_CONFIG`, `~/.ankka/config.json`; made absolute | always |

## Credential

What `Session` now returns before `bearer` decides whether to throw.

| Value | Meaning | Built-in command | Plugin |
|---|---|---|---|
| `Token(value)` | a flag or environment token, or a saved login's usable or refreshed access token | presents it | `ANKKA_TOKEN` set |
| `NotLoggedIn` | no token and no saved login for the URL | fails: `run 'ankka login'` | runs, no `ANKKA_TOKEN` |
| `Refused(reason)` | the saved login has run out and the identity provider refused the refresh, or could not be asked | fails, naming the reason | runs, no `ANKKA_TOKEN`, warning on standard error |

## Outcome

The exit code `ankka <word> ...` ends with.

| Situation | Code | Standard error |
|---|---|---|
| The plugin ran and exited | the plugin's | nothing from the CLI |
| The plugin was ended by a signal | 128 + the signal's number, as the JDK reports it | nothing from the CLI |
| The word names neither a built-in command nor a plugin | 2 | the word, the program name looked for, and `See 'ankka --help'.` |
| The word holds a path separator | 2 | that a plugin is named by a word |
| PATH is unset or empty | 2 | that PATH is empty |
| The program was found and would not start | 1 | `error: cannot run <program>: <reason>` |
