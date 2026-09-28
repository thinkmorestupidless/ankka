# Quickstart: validating organization quotas

## Offline, in seconds

```bash
sbt 'controlPlane/testOnly *TenancyEntitySuite'          # the entity: reserve, release, reconcile, refusals, replay
sbt 'controlPlaneApi/testOnly *DescriptorSuite'           # Quota.problems
sbt 'controlPlane/testOnly *EventCompatibilitySuite'      # pre-feature state and summaries still decode
```

## Over HTTP, against a throwaway Postgres (Docker)

```bash
sbt 'controlPlane/testOnly *QuotaSuite'                   # US1–US3 end to end, with a restart mid-way
sbt 'controlPlane/testOnly *AuthorizationMatrixSuite'     # only a platform administrator sets one
sbt 'controlPlane/testOnly *CliEndToEndSuite'             # quota set / get / clear through Main
```

`QuotaSuite` is the feature's proof. Expected shape: an administrator sets `2/3/4` on `acme`; two
projects are created and the third is refused naming "quota of 2 projects (2 in use)"; three
services land and the fourth is refused; a service asking for more instances than remain is refused
and the same service with one is accepted; re-applying with fewer is accepted and usage drops;
pausing changes nothing; the service is restarted (`restartService()`) and usage is unchanged; a
quota lowered below usage refuses only new things; clearing lets everything through.

## The reference pages

```bash
sbt -Dankka.docs.update=true 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'
sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite'
just docs                                                 # every page checked, site built
```

## By hand

```bash
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
ankka login                                               # dev / dev has platform-admin
ankka organizations create acme --name Acme
ankka organizations quota set acme --projects 1
ankka projects create checkout --name Checkout -O acme
ankka projects create billing --name Billing -O acme     # refused: quota of 1 projects (1 in use)
ankka organizations get acme                              # QUOTA 1/-/-, PROJECTS 1
ankka organizations quota clear acme
```
