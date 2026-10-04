package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.http.Principal

/**
 * What the control plane reads from a principal. Building one from verified claims is the shared
 * verifier's job (`ankka-auth-oidc`'s `Principals`), so the control plane and every service read a
 * token the same way. Only `subject` is ever a key.
 */
object Principals:

  /** The one installation-level role the control plane reads from a token (spec FR-012). */
  val PlatformAdmin = "platform-admin"

  def isPlatformAdmin(principal: Principal): Boolean = principal.roles.contains(PlatformAdmin)

  /** Email, else name, else the subject — a label for listings, never a key. */
  def display(principal: Principal): String =
    principal.email.orElse(principal.name).getOrElse(principal.subject)
