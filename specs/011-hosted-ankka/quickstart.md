# Quickstart: validating feature 011

Prerequisites: Docker running (every integration suite starts its own Postgres; the spoke case
starts k3s), JDK 21, sbt.

## 1. The default is unchanged (SC-001)

```bash
sbt -Dankka.cluster.tests=off controlPlane/test cli/test
```

Expected: green, with no existing test edited for the default to hold. `EventCompatibilitySuite`
still decodes the pre-feature `OrganizationCreated` JSON.

## 2. The creation policy (US1)

```bash
sbt 'controlPlane/testOnly *OrganizationPolicySuite *OrganizationCreationPolicySuite'
```

Expected:

- `open` and `platform-admin` load; `anything-else` fails startup naming
  `ANKKA_ORGANIZATION_CREATION` and both values.
- Under `platform-admin`: a member's `POST /organizations/x` is `403` with the documented message
  including the sign-up URL; the administrator's is `204`; the member's list, get, rename, members
  and project routes answer as under `open`.

By hand, against the compose stack:

```bash
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka \
ANKKA_ORGANIZATION_CREATION=platform-admin ANKKA_SIGNUP_URL=https://ankka.cloud sbt controlPlane/run
ankka login                       # dev / dev — not a platform admin
ankka organizations create acme --name Acme
# not permitted: organizations in this installation are created by the platform administrator; sign up at https://ankka.cloud
```

## 3. Create for an owner (US2)

```bash
sbt 'controlPlane/testOnly *TenancyEntitySuite *AuthorizationMatrixSuite'
```

Expected: carol (admin) creates `wonderland` for alice — members are exactly alice as owner with
her email and name, `addedBy` carol; alice lists it and renames it; bob naming an owner is `403`;
carol naming nobody is the owner herself; the fold of `OrganizationCreated(…, owner = Some)`
reproduces the same members from the events alone.

By hand, with a platform-admin login: `ankka organizations create acme --name Acme --owner <sub>
--owner-email alice@example.com --owner-name Alice`, then `ankka organizations members list acme`.

## 4. The seventh artifact (US3)

```bash
sbt publishLocal
ls ~/.ivy2/local/com.thinkmorestupidless/ | grep ankka-
```

Expected: seven directories, `ankka-controlplane-api_3` among them. From a scratch sbt project
depending only on `"com.thinkmorestupidless" %% "ankka-controlplane-api" % <version>`:

```scala
import com.thinkmorestupidless.ankka.controlplane.api.*
ServiceSpec.problems(...)   // compiles and runs; the classpath holds ankka-core and jsoniter, no Pekko
```

## 5. The spoke (US4)

```bash
caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite'
```

Expected: the new case starts a second control plane with the hub's issuer configured explicitly
and its own base domain, and asserts `GET /auth` advertises the hub issuer, the hub's token is
accepted on `/auth/whoami`, and a token from an in-process issuer is `401`.

## 6. Documentation

```bash
sbt -Dankka.docs.update=true 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite' 'cli/testOnly *CliReferenceSuite'
just docs
```

Expected: the CLI page shows the three new options; the routes page's prose for
`POST /organizations/{organizationId}` describes the owner and both refusals; the identity page has
"Who may create organizations"; the cloud install page has "A spoke installation"; the docs build
passes.
