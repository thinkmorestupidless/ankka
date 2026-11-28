# Contract: `extra.docs.kept`

```yaml
extra:
  docs:
    kept:
      settings: true   # the tool keeps tools/docs/pyproject.toml; the default
      job: true        # the tool keeps .github/workflows/docs.yml; the default
```

A key other than these two, or a value that is not `true` or `false`, is a configuration error, as
every unknown key of `extra.docs` is. With a part disclaimed, `docs init` neither writes nor reports
it, and `docs check` holds it to nothing.

A repository that publishes its site somewhere other than GitHub Pages, or from a workflow of its
own, says `job: false`. ankka says both false.
