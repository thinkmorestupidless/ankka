package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.typesafe.config.Config

/**
 * Where an installation keeps service secrets: one setting, the installation's, read once when a
 * service starts. A service's code, its descriptor and its calls on the store are the same on
 * either.
 */
private[ankka] enum SecretBackend(val word: String):
  /** Each service's own database, each value encrypted with its secret key: feature 023's store. */
  case Postgres extends SecretBackend("postgres")

  /** Google Secret Manager, each service reaching it as its own identity. */
  case SecretManager extends SecretBackend("secret-manager")

private[ankka] object SecretBackend:

  val Key: String = "ankka.secrets.backend"

  /** The words an installation may give, in the order a refusal names them. */
  val Words: Vector[String] = SecretBackend.values.toVector.map(_.word)

  /** Unset or empty is the Postgres backend; anything else must be one of `Words`. */
  def parse(word: String): Either[String, SecretBackend] =
    word.trim match
      case "" => Right(Postgres)
      case w =>
        SecretBackend.values
          .find(_.word == w)
          .toRight(
            s"${PlatformVariables.SecretBackend} is '$w', which is not a secret backend; " +
              s"use one of ${Words.mkString(", ")}"
          )

  def from(config: Config): Either[String, SecretBackend] =
    parse(if config.hasPath(Key) then config.getString(Key) else "")
