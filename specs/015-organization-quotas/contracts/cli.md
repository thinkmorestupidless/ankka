# Contract: CLI

```
ankka organizations quota set <id> [--projects N] [--services N] [--instances N]
ankka organizations quota clear <id>
```

`set` needs at least one limit and refuses a negative one before the round trip (`Quota.problems`,
the same rule the server applies). Both are platform administrators' commands, as `disable` and
`enable` are. Output:

```
quota set on 'acme': projects 2, services 3, instances 4
quota cleared on 'acme'
```

`ankka organizations list` and `ankka organizations get` gain columns:

```
ID    NAME       PROJECTS  SERVICES  INSTANCES  QUOTA  ROLE   STATE
acme  Acme Corp  2         1         2          2/3/4  owner  active
ops   Ops        0         0         0          -      -      active
```

`QUOTA` is `projects/services/instances` with `-` for an unlimited slot (`-/3/-`), or `-` when
none is set. `SERVICES` and `INSTANCES` are usage. `--output json` prints the summary as the API
returns it, `quota` and `usage` included.
