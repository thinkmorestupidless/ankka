# Quickstart: A nakka Application Built Outside This Repository

How to prove the feature, cheapest first.

## Tier 1 — Pure (seconds)

```bash
sbt 'controlPlaneApi/testOnly nakka.controlplane.api.CompatibilitySuite'   # the range rule, version parsing, describe()
sbt 'controlPlaneApi/testOnly nakka.controlplane.api.DescriptorSuite'      # service.runtime round-trips; a malformed one is a problem
sbt 'controlPlane/testOnly nakka.controlplane.ServiceProjectionSuite'      # unsupported runtime → Left naming both versions; absent → projects
sbt 'cli/testOnly nakka.cli.MainSuite'                                     # nakka version prints BuildInfo.version; init argument handling
```

## Tier 2 — The build itself (a minute)

```bash
sbt publishLocal
ls ~/.ivy2/local/com.thinkmorestupidless/            # exactly: nakka-core_3 nakka-sdk_3 nakka-runtime_3 nakka-http_3 nakka-agent_3 nakka-testkit_3
sbt 'show version'                                    # dynver: 0.1.0+N-sha-SNAPSHOT untagged; 0.2.0 on tag v0.2.0
sbt 'cli/run version'
```

## Tier 3 — The template, automated (~4 minutes, needs sbt on PATH and Docker)

```bash
sbt 'cli/testOnly nakka.cli.TemplateSuite'
```

Publishes locally, expands the template with `nakka init --template file://…` into a temp
directory, asserts nothing in it references this repository and the stub name is gone, then runs
the expanded project's `sbt test` and `sbt Docker/publishLocal` as subprocesses.

## Tier 4 — The release workflow, locally (a minute)

```bash
gpg --batch --gen-key <<EOF …throwaway… EOF
sbt -Dnakka.release.local=/tmp/nakka-repo publishSigned
ls /tmp/nakka-repo/com/thinkmorestupidless/                 # six, each with .asc, -sources, -javadoc
git stash && sbt 'show version'                              # ends in -SNAPSHOT: not releasable
```

## Tier 5 — The whole chain, by hand (20 minutes)

```bash
cd $(mktemp -d)
nakka init orders --template file:///path/to/nakka/nakka.g8
cd orders
sbt test
sbt schema && docker compose up -d && sbt run &            # then the two curls from the README
sbt Docker/publishLocal
kind load docker-image orders:latest --name nakka
nakka services apply -f service.json                       # against the kind cluster from feature 005
nakka services list                                        # Ready 1/1
nakka services expose orders
curl --cacert ~/.nakka/local-ca.crt -XPOST https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1 \
     -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
nakka services restart orders
curl --cacert ~/.nakka/local-ca.crt https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1   # survives
```

Then the negative: edit `service.json`'s `runtime` to `9.0.0`, `apply`, `services get` →
`Unavailable`, detail naming `9.0.0` and the supported range; put it back, `apply`, `Ready`.

## Reviewer's checklist

- [ ] `grep -rn "thinkmorestupidless/nakka\|modules/runtime\|../.." <expanded project>` finds
      only the template's own git URL in the README (SC-003).
- [ ] `grep -rn "my-service\|myservice\|MyService" <expanded project>` finds nothing (FR-014).
- [ ] `ThisBuild / version` no longer exists in `build.sbt`.
- [ ] Every command in `nakka.g8/src/main/g8/README.md` appears in Tier 5 above.
- [ ] `README.md`'s "Not implemented" says: no registry (still), no runtime-vs-image verification
      beyond the declaration, no compatibility matrix.
- [ ] Every existing suite passes; the samples still build from project references (SC-008).
