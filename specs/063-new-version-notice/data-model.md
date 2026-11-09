# Data model: A notice when a newer release exists

## Release (value)

A version `major.minor.patch`, three non-negative integers, written without a leading `v`. Ordered by
major, then minor, then patch. Parsed from text that matches `^v?\d+\.\d+\.\d+$`; anything else does
not parse. `0.0.0` is not a release.

## LatestRelease

| Field | Type | Rule |
|---|---|---|
| `version` | Release | required |
| `released` | date, `YYYY-MM-DD` | required; read, never compared |

Read from `latest.json` (`contracts/latest-release.md`). A body that does not parse, or whose
`version` does not parse, is no latest release.

## RememberedCheck

`release-check.json` beside the settings file.

| Field | Type | Rule |
|---|---|---|
| `checkedAt` | instant | when the latest release was last read successfully |
| `latest` | LatestRelease, optional | what was read then |
| `noticeAt` | instant, optional | when the command line last gave the notice |

- Read again when `checkedAt` is absent or 24 h or more before now (FR-005).
- Notice given when `latest` is newer than the command line's release and `noticeAt` is absent or
  24 h or more before now (FR-007); giving it writes `noticeAt`.
- `ankka version` reads regardless of `checkedAt` and gives the notice regardless of `noticeAt`
  (FR-007a), and writes both.
- A failed read leaves the file as it was. An unreadable file is an absent one.

## ReleaseStatus (in memory, the MCP server)

| Field | Type | Rule |
|---|---|---|
| `cli` | Release or the development build's version text | always |
| `latest` | LatestRelease, optional | the last successful read |
| `available` | boolean | `latest` newer than `cli`; false for a development build |

## The patterns file

`docs/release-patterns.txt`, one rule per line, `#` comments:

```text
# A place a page names a release of ankka; {version} is the release, without its v.
release ankka-go@v{version}
release ankkaVersion := "{version}"
release "runtime": "{version}"
# A place a page names another project's release, which the check leaves alone.
other spec-kit.git@v
```

`{version}` matches `\d+\.\d+\.\d+`. A `release` line is a literal with one `{version}`; an `other`
line is a literal prefix that the version follows.
