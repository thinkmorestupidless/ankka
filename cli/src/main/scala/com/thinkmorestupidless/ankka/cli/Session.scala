package com.thinkmorestupidless.ankka.cli

import java.time.Instant
import scala.util.{Failure, Success, Try}

/**
 * The credential a command presents (contracts/cli-commands.md):
 *
 *   1. `--token` / `ANKKA_TOKEN`, verbatim — a non-interactive client's own token; no login is
 *      attempted and no credentials file is read or written.
 *   2. Else the saved login for the URL: its access token while it has time left, otherwise a
 *      silent refresh, saved back.
 *   3. Else, or when the refresh is refused: "run `ankka login`".
 */
object Session:

  /**
   * What `bearer` would present, as a value rather than a failure: a plugin runs without a token
   * where a built-in command stops.
   */
  enum Credential:
    case Token(value: String)
    case NotLoggedIn
    case Refused(reason: String)

  def credential(settings: Settings): Credential =
    settings.token match
      case Some(token) => Credential.Token(token)
      case None        =>
        Credentials.get(settings.url) match
          case None        => Credential.NotLoggedIn
          case Some(login) =>
            val now = Instant.now().getEpochSecond
            if login.accessTokenUsable(now) then Credential.Token(login.accessToken)
            else
              // An issuer that cannot be reached is a refresh refused, with the reason why; it used
              // to escape as an exception and its stack trace.
              val attempt = Try {
                val flow = DeviceFlow(settings)
                flow.refresh(flow.discover(login.issuer), login.clientId, login.refreshToken)
              }
              attempt match
                case Success(Right(tokens)) =>
                  val renewed = login.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken.getOrElse(login.refreshToken),
                    expiresAt = now + tokens.expiresIn
                  )
                  Credentials.put(settings.url, renewed): Unit
                  Credential.Token(renewed.accessToken)
                case Success(Left(reason))    => Credential.Refused(reason)
                case Failure(error: ApiError) => Credential.Refused(error.detail)
                case Failure(error)           =>
                  Credential.Refused(
                    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                  )

  def bearer(settings: Settings): String =
    credential(settings) match
      case Credential.Token(value) => value
      case Credential.NotLoggedIn  =>
        throw ApiError(0, s"not logged in to ${settings.url}; run 'ankka login'")
      case Credential.Refused(reason) =>
        throw ApiError(0, s"${settings.url} rejected the login ($reason); run 'ankka login'")

  /** Whether a saved login exists for the URL — shown by `config get`, never its contents. */
  def saved(settings: Settings): Boolean = Credentials.get(settings.url).isDefined
