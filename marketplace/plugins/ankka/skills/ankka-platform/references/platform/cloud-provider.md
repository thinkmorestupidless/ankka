# The cloud provider

> How an installation names its one cloud account, the provider process that makes buckets, identities and keys there so the operator never holds that power, the six cloud requests, and how a credential is written once.

Source: https://docs.ankka.cloud/platform/cloud-provider/
Some of what the platform gives a service lives in a cloud account rather than in the cluster: a
bucket in the cloud's object store, an identity the cloud knows the service as, a key the cloud keeps.
Making those needs power over the cloud account, close to an owner's, and the operator is never given
it. Instead the installation runs a **cloud provider**: a process of its own, in its own namespace,
under its own identity, that does what the operator asks and nothing else.

The operator asks by writing a **cloud request**, a `CloudResource` in the project's namespace, and the
provider answers by writing the request's status. When the answer is a credential, the provider writes
it into a Secret the service's instances read, and neither process can read it back. The platform knows
a provider only by name; everything a request asks is in the platform's words, never a cloud's, so a
provider for another cloud answers the same requests.

An installation without a provider writes no cloud request at all, and is served entirely from inside
the cluster, as it always was.

## Naming the installation's cloud

The installation names its cloud once, in the `ankka-platform` ConfigMap of its overlay, and the
overlay copies each value to the operator, the control plane and the provider:

| Key | Variable | Meaning |
|---|---|---|
| `cloudProvider` | `ANKKA_CLOUD_PROVIDER` | `none`, or the name of a provider the platform knows: `gcp` |
| `cloudAccount` | `ANKKA_CLOUD_ACCOUNT` | the one cloud account the installation's cloud resources are made in |
| `cloudLocation` | `ANKKA_CLOUD_LOCATION` | where they are made, in the installation's own words, which the provider interprets |
| `cloudKmsKey` | `ANKKA_CLOUD_KMS_KEY` | the one key the installation wraps with, or empty |
| `cloudAcknowledgementBound` | `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` | how long a request may go unanswered before a service says so: `2m` |
| `cloudRotationGrace` | `ANKKA_CLOUD_ROTATION_GRACE` | how long a replaced credential goes on working: `1h` |

```yaml title="platform-configmap.yaml"
data:
  cloudProvider: gcp
  cloudAccount: acme-production
  cloudLocation: europe-west2
  cloudKmsKey: ""
  cloudAcknowledgementBound: 2m
  cloudRotationGrace: 1h
```

A provider name the platform does not know stops the operator and the control plane at start, naming
the ones it does; so does a provider named without its account or location. The variables are the
platform's: a descriptor that sets one is refused.

There is one account per installation. A project names no account of its own; a brand that needs an
account of its own, in another jurisdiction or with its own billing, is a second installation.

`ankka installation` shows what the installation names, to any member; the key's name only to an owner
of an organization:

```text
$ ankka installation
platform   0.12.0
provider   gcp
account    acme-production
location   europe-west2
```

## Installing a provider

The `cloud-provider` component of the overlay ships everything a provider needs from the platform but
its workload: the namespace `ankka-cloud-provider`, the ServiceAccount `ankka-cloud-provider`, the one
grant every provider runs under, and the ConfigMap `ankka-cloud` with the settings above. A provider's
own install adds only its Deployment in that namespace, reading `ankka-cloud` with `envFrom`, and, on a
cloud that binds Kubernetes identities to its own, the annotation that binds the ServiceAccount to the
provider's cloud identity.

Google Cloud's provider is `ankka-gcp`, its own project and image. It runs under Workload Identity and
is given no key file. Its cloud roles are owner-equivalent on the account, and that is exactly why the
operator does not hold them.

The grant every provider runs under:

| Resource | Verbs |
|---|---|
| `cloudresources` | `get`, `list`, `watch` |
| `cloudresources/status` | `get`, `update`, `patch` |
| `secrets` | `create`, `patch` |

The operator's grant is the other half: `get`, `list`, `watch`, `create` and `patch` on
`cloudresources`, and `get` on their status. Neither holds `delete` on a cloud request, neither holds
`get` or `list` on Secrets, and neither holds the other's verbs.

## A cloud request

```yaml
apiVersion: ankka.thinkmorestupidless.com/v1alpha1
kind: CloudResource
metadata:
  name: reports-bucket
  namespace: ankka-shop
  ownerReferences:
    - kind: AnkkaService
      name: reports
      controller: true
spec:
  provider: gcp
  kind: bucket
  subject: { project: shop, service: reports }
  parameters:
    purpose: service
    location: europe-west2
    versioning: "false"
    softDeleteDays: "0"
    corsOrigins: ""
    kmsKey: ""
    namePrefix: acme
    noncurrentVersionDays: ""
status:
  observedGeneration: 1
  phase: Ready
  account: acme-production
  location: europe-west2
  providerVersion: "ankka-gcp 0.1.0"
  outputs:
    bucket: acme-shop-reports-ae7cc739
    endpoint: https://storage.googleapis.com
    region: europe-west2
```

A request is named from what it is for and whom it serves: `<service>-<suffix>` for a service's and
`<project>.<suffix>` for a project's, so a project's can never share a name with any service's. It is
owned by the service or project it serves and goes with it; nothing in the cloud goes with it.

Every parameter and output is a string. A list is comma-separated, a boolean is `"true"` or `"false"`,
and a number is its decimal text.

## The six kinds

| Kind | Asks | Answers |
|---|---|---|
| `identity` | `serviceAccount`: a cloud identity for this Kubernetes ServiceAccount | `identity`, and `serviceAccountAnnotations`: the annotations, as `key=value` pairs, that bind the ServiceAccount to it |
| `secret-access` | `identity`, `own` and `read`: which secrets the identity may keep and which it may only read | nothing |
| `secret-sync` | `secretName`, `entries` as `NAME=id`, `entryGeneration`: a Secret kept in step with secrets in the account | `entryGeneration`, the one kept |
| `bucket` | `purpose` (`service` or `backup`), `location`, `versioning`, `softDeleteDays`, `corsOrigins`, `kmsKey`, `namePrefix`, `noncurrentVersionDays` (empty for none) | `bucket`, `endpoint`, `region` |
| `bucket-credential` | `bucket`, `identity`, `secretName`, and a credential generation | `secretName` |
| `wrapping-key` | `identity`, `key`: that the identity may wrap with the installation's key | `key` |

A service with `provisionObjectStorage` on an installation whose object store is its cloud account's
asks for an `identity`, a `bucket` and, once both are answered, a `bucket-credential` naming what they
answered. A `backup` bucket is named so no service's bucket can share its name, and its credential is
granted only to its project's database.

A provider names a service's bucket `<namePrefix>-<project>-<service>-<digest>`, where `digest` is the
first eight lowercase hex characters of SHA-256 over `<project>.<service>` and is never shortened. Two
services whose project and name join to the same hyphenated text therefore get different buckets. The
whole name fits 63 characters of `[a-z0-9-]`: when it would not, the service's part is shortened from
the right, then the project's, each keeping at least one character. A name another customer of the
cloud already holds is `Failed`, naming the bucket; a provider never adopts it.

The operator copies `serviceAccountAnnotations` onto the service's Kubernetes ServiceAccount as they
are, so a provider says how its cloud binds a workload to an identity and the platform needs no
cloud's annotation keys.

## The answer

A provider sets `observedGeneration` to the request's `metadata.generation` before it reports any phase,
and the operator acts on no answer whose `observedGeneration` is behind the request's. The phase is one
of four:

| Phase | Meaning |
|---|---|
| `Waiting` | the provider is working, and `detail` says on what |
| `Ready` | done; `outputs` is the answer |
| `Recovered` | done, and the thing already existed for this subject; `recovered` is `true` |
| `Failed` | it cannot; `detail` says why, in words a member can read and with nothing secret in them |

A provider refuses, with `Failed`, a kind it does not implement (naming the kind and its version), and a
request whose account or location is no longer the one its thing was made in (`made in another account
or location`): it never moves or remakes anything. It deletes nothing, in the cloud or in the cluster,
when a request is removed.

The operator folds each answer into the status of the service or project that asked, under the part of
it the request is for: a bucket's answer is the service's object storage, and `ankka services get` shows
the provider's words without naming the request. While a request a service's instances need is not
answered, the service's instances are not started, and its status is `UpdateInProgress` with what the
provider said. A request nobody has acknowledged for the acknowledgement bound is reported as
`no provider for <provider> has answered`; the status recovers the moment a provider answers.

## A credential is written once

A provider never reads a Secret: its grant does not allow it. It offers a credential as a `create` of
the named Secret, with `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`:

- the Secret was not there: the credential is in it, and the answer is `Ready`;
- the Secret was there: what is in it stays, the credential just made is in no Secret and is ended at
  once, and the answer is `Ready` naming the Secret.

A new credential is asked for by raising the request's credential generation. The provider makes one,
`patch`es it into the same Secret, reports the new generation in place with the time it did, and ends
the previous credential no sooner than the rotation grace after that report, counting from the status so
that a provider that restarts still ends it. The operator puts the generation on the service's pod
template, so its instances are replaced and start with the new credential. A service's storage credential
generation is set on its `AnkkaService` resource; there is no command for it yet.

## Testing a provider

The platform's own tests run a **scripted cloud provider** that answers every kind with made-up values
and made-up Secrets, reaches no cloud, and records every credential it issues and ends. It runs under
the same ServiceAccount and grant as any provider, so whatever it may do, every provider may.

The same scenarios run against a real provider: point the cloud provider suite at a cluster that runs
one, with the installation's settings in the environment.

```bash
ANKKA_CLOUD_PROVIDER=gcp ANKKA_CLOUD_ACCOUNT=acme-test ANKKA_CLOUD_LOCATION=europe-west2 \
  sbt -Dankka.cloud.external=$HOME/.kube/test-cluster.yaml \
  'controlPlane/testOnly *CloudProviderClusterFeatures'
```

A step that reads the scripted provider's records, or scripts a refusal, is skipped there.
