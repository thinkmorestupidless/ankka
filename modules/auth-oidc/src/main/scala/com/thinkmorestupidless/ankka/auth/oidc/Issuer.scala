package com.thinkmorestupidless.ankka.auth.oidc

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.util.Try

/**
 * One identity provider a service accepts tokens from.
 *
 * `name` is the service's own word for it, the key in `ANKKA_AUTH_ISSUERS` and what a principal's
 * `issuer` says; `issuer` is what a token's `iss` must equal exactly. Two strings on purpose: a
 * handler that treats staff and customers differently reads a word it chose, not a URL.
 */
final case class Issuer(
    name: String,
    issuer: String,
    jwksUrl: String,
    audience: String,
    /** A PEM bundle the keys fetch trusts alone; `None` is the JVM's trust store. */
    ca: Option[Path] = None,
    /**
     * A `typ` value a token must carry. Keycloak writes `Bearer` on an access token, and checking
     * it keeps a refresh or ID token from being presented as one; other providers write something
     * else or nothing, so it is off unless asked for.
     */
    typ: Option[String] = None,
    clockSkew: FiniteDuration = Issuer.DefaultClockSkew
)

object Issuer:
  val DefaultClockSkew: FiniteDuration = 60.seconds

  /** What an issuer's name may be: a word, so it can become part of a variable's name. */
  val NamePattern: scala.util.matching.Regex = "[A-Za-z][A-Za-z0-9-]*".r

/** The issuers a service accepts, and the realm its challenges name. Empty means none. */
final case class OidcConfig(issuers: Vector[Issuer], realm: String = OidcConfig.DefaultRealm):
  def isEmpty: Boolean = issuers.isEmpty

object OidcConfig:
  val DefaultRealm = "ankka"

  val empty: OidcConfig = OidcConfig(Vector.empty)

  /** The variable that names the issuers. Every other variable hangs off a name in it. */
  val IssuersVariable = "ANKKA_AUTH_ISSUERS"
  val RealmVariable   = "ANKKA_AUTH_REALM"

  /** The part of a variable's name an issuer's name becomes: `customers-eu` is `CUSTOMERS_EU`. */
  def segment(name: String): String = name.toUpperCase.replace('-', '_')

  /**
   * Reads the named set: `ANKKA_AUTH_ISSUERS=customers,staff`, then `ANKKA_AUTH_CUSTOMERS_ISSUER`,
   * `_JWKS_URL`, `_AUDIENCE` and the optional `_CA`, `_TYP` and `_CLOCK_SKEW` for each name.
   *
   * Every problem is reported, not the first: a descriptor with three mistakes should take one
   * apply to fix. Only the variables of listed names are read, so a shell that also carries the
   * control plane's singular `ANKKA_AUTH_ISSUER` and `ANKKA_AUTH_JWKS_URL` is not refused for them.
   */
  def fromEnv(env: Map[String, String] = sys.env): Either[Vector[String], OidcConfig] =
    val realm = env.get(RealmVariable).map(_.trim).filter(_.nonEmpty).getOrElse(DefaultRealm)
    env.get(IssuersVariable).map(_.trim).filter(_.nonEmpty) match
      case None => Right(OidcConfig(Vector.empty, realm))
      case Some(listed) =>
        val names    = listed.split(',').toVector.map(_.trim).filter(_.nonEmpty)
        val problems = Vector.newBuilder[String]
        twice(names).foreach(n => problems += s"$IssuersVariable: '$n' is listed twice")
        val (valid, invalid) = names.distinct.partition(n => Issuer.NamePattern.matches(n))
        invalid.foreach(n => problems += s"$IssuersVariable: '$n' is not a valid issuer name")
        val issuers = valid.flatMap(name => read(name, env, problems))
        val found   = problems.result()
        if found.nonEmpty then Left(found) else Right(OidcConfig(issuers, realm))

  private def twice(names: Vector[String]): Vector[String] =
    names.groupBy(identity).collect { case (n, all) if all.sizeIs > 1 => n }.toVector.sorted

  private def read(
      name: String,
      env: Map[String, String],
      problems: mutable.Builder[String, Vector[String]]
  ): Option[Issuer] =
    val prefix                           = s"ANKKA_AUTH_${segment(name)}_"
    def value(v: String): Option[String] = env.get(prefix + v).map(_.trim).filter(_.nonEmpty)
    def required(v: String): Option[String] =
      val found = value(v)
      if found.isEmpty then problems += s"$prefix$v is not set"
      found
    val issuer   = required("ISSUER")
    val jwksUrl  = required("JWKS_URL")
    val audience = required("AUDIENCE")
    val ca = value("CA") match
      case None => Some(None)
      case Some(path) =>
        val p = Path.of(path)
        if Files.isRegularFile(p) then Some(Some(p))
        else
          problems += s"${prefix}CA: no file at $path"
          None
    val skew = value("CLOCK_SKEW") match
      case None => Some(Issuer.DefaultClockSkew)
      case Some(text) =>
        val parsed = duration(text)
        if parsed.isEmpty then problems += s"${prefix}CLOCK_SKEW: '$text' is not a duration"
        parsed
    for
      i <- issuer
      j <- jwksUrl
      a <- audience
      c <- ca
      s <- skew
    yield Issuer(name, i.stripSuffix("/"), j, a, c, value("TYP"), s)

  /** `60s`, `500ms`, `2m`, or a bare number of seconds. */
  private def duration(text: String): Option[FiniteDuration] =
    val t = text.trim
    Try {
      if t.endsWith("ms") then t.dropRight(2).trim.toLong.millis
      else if t.endsWith("s") then t.dropRight(1).trim.toLong.seconds
      else if t.endsWith("m") then t.dropRight(1).trim.toLong.minutes
      else t.toLong.seconds
    }.toOption.filter(_.length >= 0)

  /** For a configuration built in code: what `fromEnv` would have refused. */
  def problems(config: OidcConfig): Vector[String] =
    twice(config.issuers.map(_.name)).map(n => s"issuer '$n' is listed twice") ++
      config.issuers.flatMap { i =>
        Vector(
          Option.when(!Issuer.NamePattern.matches(i.name))(
            s"'${i.name}' is not a valid issuer name"
          ),
          Option.when(i.issuer.trim.isEmpty)(s"issuer '${i.name}' has no issuer string"),
          Option.when(i.jwksUrl.trim.isEmpty)(s"issuer '${i.name}' has no keys URL"),
          Option.when(i.audience.trim.isEmpty)(s"issuer '${i.name}' has no audience")
        ).flatten
      }
