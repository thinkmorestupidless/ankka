# Contract: the command line's notice

## The line

Exactly, on standard error, after everything the command wrote:

```text
ankka version 0.16.0 is available (you're currently using version 0.13.0)
```

No prefix, no colour, nothing after it. The command's exit code is unchanged.

## Switches

| Switch | Effect |
|---|---|
| `ANKKA_NO_UPDATE_CHECK=1` | no read, no notice, `mcp` included |
| `CI` non-empty and not `false` | no read, no notice, `mcp` included |
| `-o json`, `--output json`, `--output=json` | no read, no notice |
| standard error not a terminal (R3) | no read, no notice; not applied to `mcp` |
| `ANKKA_LATEST_URL`, `-Dankka.latest.url` | where the latest release is read from |
| `-Dankka.terminal=true\|false` | tests: whether error output counts as a terminal |

## Files

`release-check.json` in the directory of the settings file (`-Dankka.config`, `ANKKA_CONFIG`, else
`~/.ankka/`). Shape in `../data-model.md`.

## `ankka version`

Prints the CLI's version on standard output, as today. Then reads the latest release at once (2 s
timeout) and gives the notice on standard error when a newer release exists, under the switches
above.
