# Contract: what a provider must do

A provider is a process in `ankka-cloud-provider`, under the ServiceAccount the `cloud-provider`
component ships, that watches `CloudResource`s naming it and fulfils them. `ankka-gcp` is one; the
scripted cloud provider in the operator's test sources is another; both are held to this page by
the same feature files (`features/cloud-provider/`).

## Watching

- Watch `cloudresources` in every namespace; act on those whose `spec.provider` is this
  provider's name and ignore the rest.
- Read the settings the component gives it: ConfigMap `ankka-cloud` in its own namespace
  (`ANKKA_CLOUD_PROVIDER`, `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION`, `ANKKA_CLOUD_KMS_KEY`,
  `ANKKA_CLOUD_ROTATION_GRACE`), as environment through `envFrom`.
- A request whose `status.observedGeneration` already equals `metadata.generation` is done:
  do nothing, and in particular issue nothing.

## Fulfilling

For every kind: find or make, grant what is granted, and write the status with
`observedGeneration` set to the `metadata.generation` fulfilled **before** reporting any phase for
it. Every step must be safe to repeat after a restart.

- Set `status.account` and `status.location` to where the thing was made.
- Set `recovered: true` and `phase: Recovered` when the thing already existed for this subject.
- Set `providerVersion` on every status.
- A request whose `spec` now names an account or location other than the status's is `Failed`
  with `made in another account or location`; never move or recreate.
- A kind not implemented is `Failed` naming the kind and the version.
- A `bucket-credential` whose `bucket` has `purpose: backup` and whose `identity` is not its
  project's database's is `Failed` with `a backup bucket is granted only to its project's
  database`.

## A credential, written once (`bucket-credential`)

Honour this table, as `StorageCredential` does today for Garage:

| Secret `secretName` | What the provider does |
|---|---|
| absent | mint a credential; `create` the Secret with `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`; on `Created`, report `Ready` with `credentialGeneration` = the spec's |
| present (`create` answers 409) | end the credential just minted; report `Ready` naming the Secret, with the generation the status already held or the spec's if none |

Never `get` or `list` a Secret: the grant does not allow it, and the 409 is all the provider learns.

## A credential replaced (`spec.credentialGeneration` raised)

1. Mint a new credential.
2. `patch` the Secret's two entries with it.
3. Report `Ready` with `status.credentialGeneration` = the new generation, and the time of this
   report.
4. End the previous credential no sooner than `ANKKA_CLOUD_ROTATION_GRACE` after that report. A
   restart in between must still end it: derive the moment from the status, not from memory.

A credential minted and never written (a crash between 1 and 2) is ended at the next pass.

## Secrets kept in step (`secret-sync`)

`patch` the named Secret with the entries' values within one minute of any change to one of them,
then report `outputs.entryGeneration` = the parameter's `entryGeneration`. Never `get` the Secret.

## Removal

A deleted `CloudResource` is nothing to do. Delete nothing in the cloud and nothing in the cluster,
ever. Cleaning an account is an administrator's act outside the platform.

## The grant

ClusterRole `ankka-cloud-provider`, bound cluster-wide:

| Resource | Verbs |
|---|---|
| `cloudresources` | `get`, `list`, `watch` |
| `cloudresources/status` | `get`, `update`, `patch` |
| `secrets` | `create`, `patch` |

No `delete` anywhere; no `get` or `list` on Secrets; nothing on any other kind. A provider that
needs more has misread this page.

## The scripted cloud provider

`com.thinkmorestupidless.ankka.operator.cloud.ScriptedCloudProvider` (test sources), started by
the k3s suites with a client minted from the shipped ServiceAccount:

- fulfils all six kinds with made-up outputs (`bucket = <account>-<project>-<service>`,
  `endpoint = https://storage.scripted.invalid`, `region = <location>`,
  `identity = <serviceAccount>@<account>.scripted`, `key = <kmsKey>` or `scripted-key`,
  `entryGeneration` echoed);
- reaches nothing outside the cluster and counts any attempt (`reached`, always zero);
- remembers what it made and where, so a second request for a subject is `Recovered` and one
  naming another account or location is `Failed`;
- refuses a backup bucket's credential for any identity but the project's database's;
- records every credential issued and ended, with the generation and the clock;
- ends a credential on a 409 and after the grace, honouring the table above;
- fails a request a script names, with the script's reason;
- answers an unknown kind `Failed` naming it and `scripted <version>`.

## Running the features against a real provider

`CloudProviderClusterFeatures` with `-Dankka.cloud.external=<kubeconfig>` runs every scenario
against that cluster, starting no scripted provider, with the installation's `ANKKA_CLOUD_*` read
from the environment. `ankka-gcp`'s nightly workflow runs it against a real account; this repository
only exercises the mode by hand.
