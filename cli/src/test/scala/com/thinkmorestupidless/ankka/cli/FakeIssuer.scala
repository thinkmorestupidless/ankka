package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted authorization server plus a two-route control plane, on loopback.
 *
 * Enough of RFC 8628 for `DeviceFlow` to be driven end to end — discovery, the device endpoint, the
 * token endpoint with `authorization_pending`, `slow_down` and `expired_token`, refresh and
 * revocation — and `GET /auth` / `GET /auth/whoami` so `ankka login` can run whole. What it never
 * does is anything a real issuer's *login form* does: that half is Keycloak's, and
 * `KeycloakRealmSuite` is where real tokens are read.
 */
final class FakeIssuer:

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  def url: String    = s"http://127.0.0.1:${server.getAddress.getPort}"
  def issuer: String = s"$url/realms/ankka"

  /** How many polls answer `authorization_pending` before a token is granted. */
  @volatile var pendingPolls: Int = 0

  /** Whether the first poll answers `slow_down`. */
  @volatile var slowDownFirst: Boolean = false

  /** Whether every poll answers `expired_token`. */
  @volatile var expired: Boolean = false

  /** Whether refreshes are refused. */
  @volatile var refuseRefresh: Boolean = false
  @volatile var accessToken: String    = "access-1"
  @volatile var expiresIn: Int         = 300

  val polls         = new AtomicInteger(0)
  val refreshes     = new AtomicInteger(0)
  val revocations   = new AtomicInteger(0)
  val whoamiBearers = new java.util.concurrent.CopyOnWriteArrayList[String]()
  val listBearers   = new java.util.concurrent.CopyOnWriteArrayList[String]()

  private def json(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def form(exchange: HttpExchange): Map[String, String] =
    new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      .split('&')
      .filter(_.nonEmpty)
      .map { pair =>
        val Array(k, v) = pair.split("=", 2).padTo(2, "")
        URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
      }
      .toMap

  server.createContext(
    "/realms/ankka/.well-known/openid-configuration",
    exchange =>
      json(
        exchange,
        200,
        s"""{"issuer":"$issuer","token_endpoint":"$issuer/token","device_authorization_endpoint":"$issuer/device","revocation_endpoint":"$issuer/revoke","jwks_uri":"$issuer/certs"}"""
      )
  )
  server.createContext(
    "/realms/ankka/device",
    exchange =>
      val fields = form(exchange)
      if !fields.get("scope").exists(_.contains("offline_access")) then
        json(exchange, 400, """{"error":"invalid_scope"}""")
      else
        json(
          exchange,
          200,
          s"""{"device_code":"dev-code","user_code":"WDJB-MJHT","verification_uri":"$issuer/device/verify","verification_uri_complete":"$issuer/device/verify?user_code=WDJB-MJHT","expires_in":600,"interval":1}"""
        )
  )
  server.createContext(
    "/realms/ankka/token",
    exchange =>
      val fields = form(exchange)
      fields.get("grant_type") match
        case Some("urn:ietf:params:oauth:grant-type:device_code") =>
          val n = polls.incrementAndGet()
          if expired then json(exchange, 400, """{"error":"expired_token"}""")
          else if slowDownFirst && n == 1 then json(exchange, 400, """{"error":"slow_down"}""")
          else if n <= pendingPolls + (if slowDownFirst then 1 else 0) then
            json(exchange, 400, """{"error":"authorization_pending"}""")
          else
            json(
              exchange,
              200,
              s"""{"access_token":"$accessToken","refresh_token":"refresh-1","expires_in":$expiresIn,"token_type":"Bearer"}"""
            )
        case Some("refresh_token") =>
          refreshes.incrementAndGet()
          if refuseRefresh then
            json(
              exchange,
              400,
              """{"error":"invalid_grant","error_description":"Session not active"}"""
            )
          else
            json(
              exchange,
              200,
              s"""{"access_token":"$accessToken","refresh_token":"refresh-2","expires_in":$expiresIn}"""
            )
        case other =>
          json(
            exchange,
            400,
            s"""{"error":"unsupported_grant_type","error_description":"$other"}"""
          )
  )
  server.createContext(
    "/realms/ankka/revoke",
    exchange =>
      revocations.incrementAndGet()
      val _ = form(exchange)
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
  )
  // The control plane's two identity routes, and a listing that records the bearer it saw.
  server.createContext(
    "/auth/whoami",
    exchange =>
      Option(exchange.getRequestHeaders.getFirst("Authorization")).foreach(whoamiBearers.add)
      Option(exchange.getRequestHeaders.getFirst("Authorization")) match
        case Some(header) if header == s"Bearer $accessToken" =>
          json(
            exchange,
            200,
            """{"subject":"alice","email":"alice@example.test","emailVerified":true,"platformAdmin":false,"organizations":[]}"""
          )
        case _ =>
          exchange.getResponseHeaders.add("WWW-Authenticate", """Bearer realm="ankka"""")
          json(exchange, 401, """{"status":401,"error":"authentication required"}""")
  )
  server.createContext(
    "/auth",
    exchange =>
      json(
        exchange,
        200,
        s"""{"issuer":"$issuer","clientId":"ankka-cli","audience":"ankka-controlplane"}"""
      )
  )
  server.createContext(
    "/organizations",
    exchange =>
      Option(exchange.getRequestHeaders.getFirst("Authorization")).foreach(listBearers.add)
      Option(exchange.getRequestHeaders.getFirst("Authorization")) match
        case Some(header) if header == s"Bearer $accessToken" => json(exchange, 200, "[]")
        case _ =>
          exchange.getResponseHeaders.add("WWW-Authenticate", """Bearer realm="ankka"""")
          json(exchange, 401, """{"status":401,"error":"authentication required"}""")
  )
  server.start()

  def stop(): Unit = server.stop(0)
