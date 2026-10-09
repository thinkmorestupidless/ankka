# Contract: the control plane and the CLI

## Settings

`CloudConfig` from `ankka.controlplane.cloud` in `reference.conf`:

```hocon
ankka.controlplane.cloud {
  provider = "none"
  provider = ${?ANKKA_CLOUD_PROVIDER}
  account = ""
  account = ${?ANKKA_CLOUD_ACCOUNT}
  location = ""
  location = ${?ANKKA_CLOUD_LOCATION}
  kms-key = ""
  kms-key = ${?ANKKA_CLOUD_KMS_KEY}
}
```

`CloudConfig.read` answers `None` for `none`, and refuses to start on a provider name not in
`PlatformVariables.CloudProviders`, as the operator does.

## `GET /installation`

Behind `Acl.Authenticate`, like every route. Answers:

```json
{
  "platformVersion": "0.7.0",
  "cloud": {
    "provider": "gcp",
    "account": "my-account",
    "location": "europe-west2",
    "kmsKey": "projects/…/cryptoKeys/ankka"
  }
}
```

- `cloud` is absent when the provider is `none`.
- `kmsKey` is present only when the caller is an owner of at least one organization (read from the
  organizations listing, in the endpoint); every other member sees the other three.

Wire type `Installation(platformVersion, cloud: Option[CloudInstallation])` and
`CloudInstallation(provider, account, location, kmsKey: Option[String])` in `controlplane-api`.
`just docs-reference` adds the route to `docs/reference/control-plane-api.md`.

## `ankka installation`

```
$ ankka installation
platform   0.7.0
provider   gcp
account    my-account
location   europe-west2
kms key    projects/…/cryptoKeys/ankka
```

Without a provider: `provider   none` and nothing else. `--json` prints the wire type.
`CliReferenceSuite` regenerates `docs/reference/cli.md`.

## `CloudProviderNeeded` (`controlplane-api`)

```scala
object CloudProviderNeeded:
  /** The problem with choosing `choice` on an installation whose provider is `provider`, if any. */
  def problem(choice: String, needs: String, provider: Option[String]): Option[String]
```

`problem("secret store secret-manager", "gcp", None)` is
`Some("secret store secret-manager needs the cloud provider gcp, and the installation has none")`;
with `Some("gcp")` it is `None`; with another provider it names both. Features 038, 039, 041 and
042 call it from their settings' validation; the four rows of `absent.feature`'s outline are its
suite until then.

## Status

Unchanged on the wire. On the cloud path `status.objectStorage.bucket` is the provider's bucket
name, `phase` and `detail` carry the provider's answer through the existing fold, and
`services get` shows `object storage: <detail>` for a `Waiting` or `Failed` bucket as it does today.
