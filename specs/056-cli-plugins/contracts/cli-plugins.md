# Contract: a plugin's view of `ankka`

What a program named `ankka-<word>` may rely on, in the order things happen. This is the contract the
reference page documents; a plugin written against it needs nothing from ankka's code.

## 1. Being chosen

```
ankka <word> [args...]
```

- `<word>` is the first argument. It is looked up only when it does not start with `-` and is not a
  built-in command's word (`login`, `logout`, `whoami`, `status`, `installation`, `organizations`,
  `projects`, `services`, `config`, `version`, `init`, `local`, `mcp`, `plugin`). Comparison is exact.
- The program is the first executable regular file named exactly `ankka-<word>` in the directories
  of `PATH`, in order. A file that is not executable is passed over. The name is compared exactly,
  including case, whatever the filesystem would do.
- A `<word>` containing `/` or `\` is refused before any lookup.

## 2. What it receives

**Arguments**: `args...` exactly as typed, in order, nothing removed and nothing added. `--help`,
`--url`, `--project`, `--output` and every other flag after `<word>` are the plugin's to parse. The
CLI accepts no flag before `<word>`.

**Standard streams**: the CLI's own standard input, output and error, inherited. The plugin may test
whether they are terminals.

**Environment**: the CLI's environment, with these set over it:

| Variable | Set | Value |
|---|---|---|
| `ANKKA_URL` | always | the control plane URL the CLI would use: `ANKKA_URL` from the environment, else the saved `url`, else `http://localhost:9000` |
| `ANKKA_TOKEN` | when a token resolves | `ANKKA_TOKEN` from the environment, else the saved `token`, else the saved login's access token, refreshed and saved first if it had run out |
| `ANKKA_PROJECT` | when a project resolves | `ANKKA_PROJECT` from the environment, else the saved `project` |
| `ANKKA_CA` | when a CA file resolves | an absolute path to the PEM file of additional trusted authorities; a relative saved value is resolved against the working directory, as a built-in command resolves it |
| `ANKKA_CONFIG` | always | an absolute path to the settings file the CLI read; `credentials.json` is beside it |

When no token resolves the plugin still runs, with `ANKKA_TOKEN` unset. When a saved login had run
out and could not be refreshed, the plugin still runs with `ANKKA_TOKEN` unset, and the CLI has
written one line to standard error first:
`warning: <url> rejected the login (<reason>); run 'ankka login'`.

The token is never on the command line, never printed, and written nowhere but the saved login.

## 3. While it runs

The CLI waits. It ignores SIGINT itself, so a ctrl-c reaches the plugin from the terminal and the
CLI is still there to report how the plugin ended. The CLI reads or writes nothing on the plugin's
behalf: no settings file, no control plane call, no output.

## 4. How it ends

The command's exit code is the plugin's. A plugin ended by a signal gives 128 plus the signal's
number (130 after ctrl-c, 143 after SIGTERM). The CLI prints nothing of its own after a plugin that
ran.

## 5. When it is not run

| Case | Exit | Standard error |
|---|---|---|
| no program `ankka-<word>` on the path | 2 | `ankka: '<word>' is not an ankka command, and no program 'ankka-<word>' is on the path` then `See 'ankka --help'.` |
| PATH unset or empty | 2 | `ankka: '<word>' is not an ankka command, and PATH is empty, so no plugin can be found` |
| `<word>` holds a path separator | 2 | `ankka: a plugin is named by a word, not a path: '<word>'` |
| the program was found and would not start | 1 | `error: cannot run <program>: <reason>` |
| `<word>` is a built-in command | the built-in runs; a plugin of that name never does | |

## 6. Being seen

`ankka plugin list` prints every candidate on the path, in path order:

```
PLUGIN    PROGRAM                          STATUS
vendor    /opt/homebrew/bin/ankka-vendor   runs
vendor    /usr/local/bin/ankka-vendor      hidden by /opt/homebrew/bin/ankka-vendor
services  /usr/local/bin/ankka-services    shadowed by the built-in command
reports   /home/me/bin/ankka-reports       not executable
```

With no candidate: `no plugins on the path`. With `-o json`, an array in the same order:

```json
[
  {"word": "vendor", "program": "/opt/homebrew/bin/ankka-vendor", "status": "runs"},
  {"word": "vendor", "program": "/usr/local/bin/ankka-vendor", "status": "hidden", "hiddenBy": "/opt/homebrew/bin/ankka-vendor"},
  {"word": "services", "program": "/usr/local/bin/ankka-services", "status": "shadowed"},
  {"word": "reports", "program": "/home/me/bin/ankka-reports", "status": "not-executable"}
]
```

`ankka --help` (and `ankka` alone) ends with the plugins that would run, by word, after the CLI's
own help, on the stream and with the exit code the help has today (standard error, 2):

```
Plugins (programs named ankka-<word> on the path):
    vendor
```

The section is absent when none would run. The generated command reference never carries it.

## 7. What is not offered

- No description protocol: the CLI asks a plugin nothing, and help shows its word only.
- `ankka mcp` serves no plugin's commands.
- No install command; plugins arrive by whatever puts programs on the path.
- No search beside the `ankka` executable itself; the path alone.
- Windows is out of scope, as it is for the CLI.
