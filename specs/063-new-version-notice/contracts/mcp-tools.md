# Contract: what `ankka mcp` adds

## Instructions (`initialize`)

When a newer release is known at `initialize`, the instructions end with a paragraph:

```text
ankka version 0.16.0 is available (you're currently using version 0.13.0). The documentation tools
serve this command line's release's pages; tell the person, who can upgrade the command line.
```

Otherwise the instructions are unchanged.

## `whoami`

The control plane's answer, unchanged, with one field added:

```json
{"subject": "…", "release": {"cli": "0.13.0", "latest": "0.16.0", "available": true, "notice": "ankka version 0.16.0 is available (you're currently using version 0.13.0)"}}
```

`latest`, `available` and `notice` are absent when the latest release is unknown, the check is off
or the CLI is a development build; `cli` is always present.

## `installation_status`

The installation's answer, unchanged, with `release` as above plus:

```json
{"release": {"cli": "0.13.0", "installation": "0.16.0", "installationNewer": true}}
```

`installation` is the answer's `platformVersion`; `installationNewer` is present only when true.

## `search_docs`, `read_doc`

When a newer release is known, the text begins with one line, then a blank line:

```text
Note: these pages are the documentation of ankka 0.13.0, the release of this command line. ankka version 0.16.0 is available (you're currently using version 0.13.0)
```

Otherwise unchanged. The `ankka://docs/` resources are not changed: a resource is content, and the
tools are where the model reads.

## Refresh

A read at start, then every 24 h on a daemon thread; each successful read replaces `latest`.
