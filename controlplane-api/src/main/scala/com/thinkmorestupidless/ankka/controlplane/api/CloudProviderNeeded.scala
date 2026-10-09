package com.thinkmorestupidless.ankka.controlplane.api

/**
 * The one wording of a choice refused because the installation has no cloud provider that could
 * make it (feature 044, FR-012). Each feature whose setting needs a provider — a project's secrets
 * in the cloud account (038), a bucket in it (039), backups in it (041), a keyring's wrapping key
 * (042) — calls this from its own validation, so the refusal reads the same wherever it is met.
 */
object CloudProviderNeeded:

  /**
   * The problem with choosing `choice` on an installation whose cloud provider is `installation`
   * (`None` when it has none), if there is one. `needs` names the provider the choice needs.
   */
  def problem(choice: String, needs: String, installation: Option[String]): Option[String] =
    installation match
      case Some(provider) if provider == needs => None
      case Some(provider) =>
        Some(s"$choice needs the cloud provider $needs, and the installation's is $provider")
      case None => Some(s"$choice needs the cloud provider $needs, and the installation has none")

  /**
   * Wrapping a keyring's keys needs more than a provider: the installation must name the one key it
   * wraps with (`ANKKA_CLOUD_KMS_KEY`). With none named, a keyring keeps its own secret (042).
   */
  def wrappingKey(installationKey: Option[String]): Option[String] =
    Option.when(installationKey.forall(_.isEmpty))(
      "wrapping the keys of the keyring needs the installation to name a wrapping key, and it names none"
    )
