package com.thinkmorestupidless.ankka.cli

import java.time.Instant

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

  def bearer(settings: Settings): String =
    settings.token.getOrElse {
      Credentials.get(settings.url) match
        case None => throw ApiError(0, s"not logged in to ${settings.url}; run 'ankka login'")
        case Some(login) =>
          val now = Instant.now().getEpochSecond
          if login.accessTokenUsable(now) then login.accessToken
          else
            val flow      = DeviceFlow(settings)
            val discovery = flow.discover(login.issuer)
            flow.refresh(discovery, login.clientId, login.refreshToken) match
              case Right(tokens) =>
                val renewed = login.copy(
                  accessToken = tokens.accessToken,
                  refreshToken = tokens.refreshToken.getOrElse(login.refreshToken),
                  expiresAt = now + tokens.expiresIn
                )
                Credentials.put(settings.url, renewed): Unit
                renewed.accessToken
              case Left(reason) =>
                throw ApiError(
                  0,
                  s"${settings.url} rejected the login ($reason); run 'ankka login'"
                )
    }

  /** Whether a saved login exists for the URL — shown by `config get`, never its contents. */
  def saved(settings: Settings): Boolean = Credentials.get(settings.url).isDefined
