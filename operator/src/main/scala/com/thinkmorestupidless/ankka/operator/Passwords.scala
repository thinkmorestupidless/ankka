package com.thinkmorestupidless.ankka.operator

import java.security.SecureRandom

/**
 * Generates the one piece of state in this feature that must never be regenerated.
 *
 * CNPG does not generate passwords itself — verified during planning (research R3):
 * `DatabaseRole.spec.passwordSecret` requires a caller-supplied secret, and given none, CNPG
 * creates a role with no password at all. Something on ankka's side has to generate one, and this
 * is the only place that does.
 */
object Passwords:

  private val random = new SecureRandom()

  // Alphanumeric only: no quoting hazards in a URI, a pgpass line, or a `psql -c` argument.
  private val alphabet = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toArray

  private val Length = 40

  /**
   * A fresh, random credential.
   *
   * Callers must call this only when a service's credential secret does not already exist —
   * `Provisioning` and `EnsureCredentials` are structured so this happens exactly once per service,
   * ever. Calling it again for an existing service rotates the password under a running connection,
   * which is a self-inflicted outage on a timer.
   */
  def generate(): String =
    String(Array.fill(Length)(alphabet(random.nextInt(alphabet.length))))
