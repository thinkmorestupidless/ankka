package com.thinkmorestupidless.ankka.auth.oidc

import com.nimbusds.jwt.JWTClaimsSet
import com.thinkmorestupidless.ankka.http.Principal

import scala.jdk.CollectionConverters.*

/** Verified claims to the principal a handler sees. Only `subject` is ever a key. */
object Principals:

  private val Structural =
    Set("sub", "name", "preferred_username", "email", "email_verified", "realm_access", "roles")

  def from(claims: JWTClaimsSet, issuer: Issuer): Principal =
    // Keycloak puts realm roles under `realm_access.roles`; many other providers put a `roles`
    // list at the top. Either is read, Keycloak's when both are present.
    val realmRoles = Option(claims.getJSONObjectClaim("realm_access"))
      .flatMap(access => Option(access.get("roles")))
      .collect { case list: java.util.List[?] => list.asScala.map(_.toString).toSet }
    val topRoles = Option(claims.getClaim("roles"))
      .collect { case list: java.util.List[?] => list.asScala.map(_.toString).toSet }
    val other = claims.getClaims.asScala.collect {
      case (key, value) if !Structural.contains(key) && value != null => key -> text(value)
    }.toMap
    Principal(
      subject = claims.getSubject,
      name = Option(claims.getStringClaim("name"))
        .orElse(Option(claims.getStringClaim("preferred_username"))),
      email = Option(claims.getStringClaim("email")).map(_.trim.toLowerCase).filter(_.nonEmpty),
      emailVerified = Option(claims.getBooleanClaim("email_verified")).exists(_.booleanValue),
      roles = realmRoles.orElse(topRoles).getOrElse(Set.empty),
      claims = other,
      issuer = Some(issuer.name)
    )

  /** A claim as text: a date as epoch seconds, as a token states it; anything else as it reads. */
  private def text(value: Any): String = value match
    case date: java.util.Date => (date.getTime / 1000).toString
    case other                => other.toString
