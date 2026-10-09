package com.thinkmorestupidless.ankka.testkit

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters.*

/**
 * Which secret backend a test's service keeps its service secrets on.
 *
 * The Postgres backend is the kit's own database, as it always was. The Secret Manager backend is a
 * `FakeSecretManager` the test started, reached as the service it names — so two kits on one fake
 * are two services, and each is refused the other's secrets as Google Cloud would refuse them.
 * Neither reaches a network or needs a credential.
 */
enum SecretBackendChoice:
  case Postgres
  case SecretManager(fake: FakeSecretManager, project: String, service: String)

object SecretBackendChoice:

  /** The service's own database: the default, and what a local platform is on. */
  val postgres: SecretBackendChoice = Postgres

  /** `fake`, reached as `service` of `project`. */
  def secretManager(
      fake: FakeSecretManager,
      project: String = "test",
      service: String = "service"
  ): SecretBackendChoice = SecretManager(fake, project, service)

  /**
   * The configuration that puts a service on `choice`. Always set, so a developer's own
   * `ANKKA_SECRET_BACKEND` or `ANKKA_SECRET_RECORDS_URL` never reaches a test's service.
   */
  private[testkit] def settings(choice: SecretBackendChoice): Config =
    val common = Map[String, AnyRef]("ankka.secrets.records-url" -> "", "ankka.secrets.move" -> "")
    val chosen = choice match
      case Postgres => Map[String, AnyRef]("ankka.secrets.backend" -> "postgres")
      case SecretManager(fake, project, service) =>
        Map[String, AnyRef](
          "ankka.secrets.backend"                 -> "secret-manager",
          "ankka.secrets.secret-manager.endpoint" -> fake.endpoint,
          "ankka.secrets.secret-manager.token"    -> FakeSecretManager.tokenFor(project, service),
          "ankka.secrets.secret-manager.identity" -> s"$project/$service",
          "ankka.cloud.provider"                  -> "gcp",
          "ankka.cloud.account"                   -> fake.account,
          "ankka.cloud.location"                  -> ""
        )
    ConfigFactory.parseMap((common ++ chosen).asJava)
