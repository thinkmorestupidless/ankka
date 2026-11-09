# Contract: the latest release

`GET https://docs.ankka.cloud/latest.json`, a static file of the documentation site.

```json
{"version": "0.17.0", "released": "2026-11-20"}
```

- Written by `docs build --release 0.17.0` into the site directory, beside `llms.txt` and
  `docs-index.json`; `released` is the UTC date of the build. Without `--release`, not written.
- Published by the release workflow's `docs` job only (a `v*` tag, after `publish`).
- Readers ignore fields they do not know, so a later field (a minimum supported CLI, say) is
  additive.
- Served with GitHub Pages' `Cache-Control: max-age=600`.
