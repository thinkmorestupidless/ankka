# Contract: the CLI

One command is added and one gains an option and two columns. Both take the options every
`services` command takes (`--url`, `--token`, `--project`, `--output`).

## `ankka services rollback <name> [--to-generation N]`

Help: `Apply the descriptor of an earlier generation again, as a new generation.`

With no `--to-generation`, the control plane chooses the most recent generation whose descriptor
differs from the current one.

Table output: the line `rolled back to generation 1`, then the status exactly as
`services get` prints it (`Output.service`).

JSON output (`--output json`): the `RolledBack` reply, unchanged.

A refusal is printed as the control plane gave it and the exit code is non-zero, as for every
command.

## `ankka services history <name> [--generation N]`

Without `--generation`, the table gains two columns, on by default:

```text
WHEN                      KIND              GEN  IMAGE   DIGEST        BY
2026-10-04T10:12:03.114Z  rolled-back to 1  4    cart:1  3f9a1c0be2d4  alice@example.com
2026-10-04T10:05:41.902Z  restarted         3    -       -             alice@example.com
2026-10-04T09:58:41.902Z  applied           2    cart:2  a41d77c09e15  token:4f1c9a
2026-10-04T09:57:10.337Z  applied           1    cart:1  3f9a1c0be2d4  bob@example.com
```

- `IMAGE` is the entry's image, `-` where it recorded none.
- `DIGEST` is the first twelve characters of the entry's digest, `-` where it recorded none.
- `KIND` of a rollback is `rolled-back to N`.

JSON output is the entries as the control plane returned them: the digest whole, `rolledBackTo` as
a field, and the kind `rolled-back`.

With `--generation N`, the command prints the descriptor recorded at generation N as indented
JSON, in either output format, and nothing else, so the output can be redirected to a file and
applied:

```bash
ankka services history cart --generation 1 > service.json
ankka services apply -f service.json
```

## Reference

`docs/reference/cli.md` is generated. `CliReferenceSuite` fails until it is rewritten with
`just docs-reference`, and the hand-written prose beside the table must mention the new command
and option.

## Not changed

`ankka mcp`'s tools. `service_history` returns the entries as JSON, so the new fields are in it.
The GitHub Action and the templates.
