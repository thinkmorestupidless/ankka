package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.runtime.{DatabaseSecretStore, SecretKey, ServiceIdentity}
import com.thinkmorestupidless.ankka.sdk.SecretStore
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

/**
 * The one place a service's secret store is chosen: the installation's backend, read once at start.
 *
 * A setting that is wrong stops the start, naming what is wrong, as a malformed secret key does: a
 * service that started on the wrong backend would keep secrets where nothing will look for them.
 */
private[ankka] object SecretStores:

  /** What the store is built from, gathered by `Ankka.host`. */
  final case class Inputs(
      config: Config,
      identity: Either[String, ServiceIdentity],
      secretKey: Option[SecretKey],
      noDatabase: Boolean,
      database: () => com.thinkmorestupidless.ankka.runtime.Database,
      /** Where each read, keep and removal is recorded before it returns. */
      recorder: ReadRecorder,
      /** How the service's program is hosted, as the record names it. */
      hosting: String,
      /** A component's kind by its id, as the record names it. */
      kindOf: String => Option[String]
  )

  /**
   * The store the service's components are given (`recorded`), the backend's own beneath it
   * (`underlying`, which a move reads and writes without recording the platform's own copying), and
   * whose secrets they are.
   */
  final case class Built(
      recorded: SecretStore,
      underlying: SecretStore,
      backend: SecretBackend,
      project: String,
      service: String
  )

  /** A component kind as a record names it: `EventSourcedEntity` is `event-sourced-entity`. */
  def kindWord(kind: String): String =
    kind.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

  def timeout(config: Config): FiniteDuration =
    FiniteDuration(config.getDuration("ankka.secrets.timeout").toMillis, "ms")

  /** The backend's store, recorded, or an exception naming why there can be none. */
  def build(inputs: Inputs): Built =
    val config  = inputs.config
    val backend = SecretBackend.from(config).fold(p => throw IllegalArgumentException(p), b => b)
    val (underlying, project, service) = backend match
      case SecretBackend.Postgres =>
        val (project, service) = recordedAs(inputs.identity)
        // A service with no database (feature 037) keeps no secrets on this backend.
        val store =
          if inputs.noDatabase then SecretStore.unavailable
          else DatabaseSecretStore(inputs.database(), inputs.secretKey, timeout(config))
        (store, project, service)
      case SecretBackend.SecretManager =>
        val (project, service) = whose(config, inputs.identity)
        (secretManager(config, project, service), project, service)
    val recorded =
      if underlying eq SecretStore.unavailable then underlying
      else
        RecordingSecretStore(
          underlying,
          inputs.recorder,
          project,
          service,
          inputs.hosting,
          backend,
          inputs.kindOf
        )
    Built(recorded, underlying, backend, project, service)

  /** Whose reads a record names on the Postgres backend, where a local run may have no project. */
  private def recordedAs(identity: Either[String, ServiceIdentity]): (String, String) =
    identity match
      case Right(ServiceIdentity(project, service)) =>
        (project.getOrElse(ServiceIdentity.LocalProject), service.getOrElse("unnamed"))
      case Left(_) => ("unknown", "unknown")

  private def secretManager(config: Config, project: String, service: String): SecretManagerStore =
    val account = config.getString("ankka.cloud.account").trim
    if account.isEmpty then
      throw IllegalArgumentException(
        s"${PlatformVariables.SecretBackend} is secret-manager and ${PlatformVariables.CloudAccount} " +
          "is not set, so there is no Google Cloud project to keep secrets in"
      )
    val token = config.getString("ankka.secrets.secret-manager.token").trim
    val tokens =
      if token.nonEmpty then AccessTokens.fixed(token) else AccessTokens.metadata()
    val client = SecretManager(
      config.getString("ankka.secrets.secret-manager.endpoint"),
      account,
      tokens,
      timeout(config)
    )
    val kept = config.getInt("ankka.secrets.versions-kept")
    if kept < 1 then
      throw IllegalArgumentException(
        s"${PlatformVariables.SecretVersionsKept} is $kept; at least one version must be kept"
      )
    SecretManagerStore(
      client,
      project,
      service,
      kept,
      Some(config.getString("ankka.cloud.location").trim).filter(_.nonEmpty),
      Option
        .when(config.hasPath("ankka.cloud.kms-key"))(config.getString("ankka.cloud.kms-key"))
        .map(_.trim)
        .filter(_.nonEmpty)
    )

  /**
   * Whose secrets these are: the certificate's identity for a deployed service, or one a test or a
   * local run states. Secret Manager is shared by every service of the installation, so a store
   * that did not know whose it was would have nothing to keep it to its own.
   */
  private def whose(
      config: Config,
      identity: Either[String, ServiceIdentity]
  ): (String, String) =
    config.getString("ankka.secrets.secret-manager.identity").trim match
      case "" =>
        identity match
          case Right(ServiceIdentity(Some(project), Some(service))) => (project, service)
          case Right(local) =>
            throw IllegalArgumentException(
              s"a service on the Secret Manager backend must know its project and name, and this " +
                s"one is $local; on a developer's machine use the Postgres backend"
            )
          case Left(problem) =>
            throw IllegalArgumentException(
              s"a service on the Secret Manager backend must know its project and name: $problem"
            )
      case stated =>
        stated.split('/') match
          case Array(project, service) if project.nonEmpty && service.nonEmpty =>
            (project, service)
          case _ =>
            throw IllegalArgumentException(
              s"ankka.secrets.secret-manager.identity is '$stated', not '<project>/<service>'"
            )
