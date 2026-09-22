package com.thinkmorestupidless.ankka.controlplane.auth

import com.nimbusds.jwt.JWTClaimsSet
import com.thinkmorestupidless.ankka.http.Principal

import scala.jdk.CollectionConverters.*

/** Verified claims → the principal handlers see. Only `subject` is ever a key. */
object Principals:

  /** The one installation-level role the control plane reads from a token (spec FR-012). */
  val PlatformAdmin = "platform-admin"

  private val Structural =
    Set("sub", "name", "preferred_username", "email", "email_verified", "realm_access")

  def from(claims: JWTClaimsSet): Principal =
    val roles = Option(claims.getJSONObjectClaim("realm_access"))
      .flatMap(access => Option(access.get("roles")))
      .collect { case list: java.util.List[?] => list.asScala.map(_.toString).toSet }
      .getOrElse(Set.empty)
    val other = claims.getClaims.asScala.collect {
      case (key, value) if !Structural.contains(key) && value != null => key -> value.toString
    }.toMap
    Principal(
      subject = claims.getSubject,
      name = Option(claims.getStringClaim("name"))
        .orElse(Option(claims.getStringClaim("preferred_username"))),
      email = Option(claims.getStringClaim("email")).map(_.trim.toLowerCase).filter(_.nonEmpty),
      emailVerified = Option(claims.getBooleanClaim("email_verified")).exists(_.booleanValue),
      roles = roles,
      claims = other
    )

  def isPlatformAdmin(principal: Principal): Boolean = principal.roles.contains(PlatformAdmin)

  /** Email, else name, else the subject — a label for listings, never a key. */
  def display(principal: Principal): String =
    principal.email.orElse(principal.name).getOrElse(principal.subject)
