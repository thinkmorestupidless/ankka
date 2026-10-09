package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromArray,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.slf4j.LoggerFactory

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, KeyStore, PublicKey, Signature}
import java.time.{Clock, Duration, Instant}
import java.util.Base64
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * A machine's token, verified with the JDK alone (feature 040): a JWS signed RS256 by the control
 * plane, whose keys are read from its JWKS. A request through the gateway carrying a valid one is
 * `Caller.Machine(organization, name)`; with none, an expired one, or any other issuer's, it is the
 * gateway still. Nothing here talks to the control plane per request: the keys are fetched when the
 * server starts, held through an outage, and fetched again only when a token names a key not yet
 * seen, at most once every thirty seconds.
 *
 * What is checked, in order: the token is three parts; its issuer is this installation's (a token
 * of any other is not looked at further); `alg` is RS256 and `kid` names a key; the signature;
 * `aud` holds `ankka`; `exp` and `nbf` with a minute's skew; `typ` is `Bearer`; `sub` is
 * `machine:<organization>/<name>`.
 */
final class MachineTokens private[http] (
    val issuer: String,
    fetch: () => Option[Map[String, PublicKey]],
    clock: Clock,
    // A fetch is a network call, and a verification runs on the server's dispatcher: it is made on
    // a thread of its own, and the token that asked for it is judged on the keys already held.
    background: Boolean = true
):
  import MachineTokens.*

  private val keys = AtomicReference[Map[String, PublicKey]](Map.empty)
  // Never fetched: far enough back that the first token naming an unknown key fetches.
  private val lastFetched = AtomicLong(0L)

  /** Fetches the keys now, off the calling thread's way: what a server does when it starts. */
  def start(): Unit = refresh(force = true)

  /** The keys held, by `kid`: for a test, and for what a refusal names. */
  def held: Set[String] = keys.get.keySet

  /**
   * Fetches again when the last fetch was long enough ago, or `force`; keeps the old keys on
   * failure.
   */
  private def refresh(force: Boolean): Unit =
    val now  = clock.millis()
    val last = lastFetched.get
    if (force || now - last >= RefetchAfter.toMillis) && lastFetched.compareAndSet(last, now) then
      val read: Runnable = () =>
        try fetch().foreach(found => if found.nonEmpty then keys.set(found))
        catch case NonFatal(e) => log.warn("machine token keys could not be read: {}", e.toString)
      if background then Thread.ofVirtual().name("machine-token-keys").start(read): Unit
      else read.run()

  /** The issuer a token says it is from, read without verifying anything. */
  def issuerOf(token: String): Option[String] =
    parts(token).toOption.flatMap((_, payload, _) => claimsOf(payload).toOption).map(_.iss)

  /** The machine a token proves, or why it proves none. */
  def verify(token: String): Either[String, Caller] =
    for
      (headerText, payloadText, signature) <- parts(token)
      claims                               <- claimsOf(payloadText)
      _ <- Either.cond(
        claims.iss == issuer,
        (),
        s"issuer '${claims.iss}' is not this installation's"
      )
      header <- Try(readFromArray[Header](decode(headerText))).toEither.left.map(_ =>
        "an unreadable header"
      )
      _   <- Either.cond(header.alg == "RS256", (), s"algorithm '${header.alg}' is not RS256")
      kid <- header.kid.filter(_.nonEmpty).toRight("no key id")
      key <- keyFor(kid)
      _ <- Either.cond(verifies(key, s"$headerText.$payloadText", signature), (), "a bad signature")
      _ <- Either.cond(claims.aud.contains(Audience), (), s"audience is not '$Audience'")
      now = clock.instant()
      _ <- Either.cond(
        claims.exp.exists(e => Instant.ofEpochSecond(e).plus(Skew).isAfter(now)),
        (),
        "expired"
      )
      _ <- Either.cond(
        claims.nbf.forall(n => !Instant.ofEpochSecond(n).minus(Skew).isAfter(now)),
        (),
        "not yet valid"
      )
      _ <- Either.cond(claims.typ.contains("Bearer"), (), "not a bearer token")
      machine <- Caller.decode(claims.sub) match
        case Some(m: Caller.Machine) => Right(m)
        case _                       => Left(s"subject '${claims.sub}' is not a machine")
    yield machine

  private def keyFor(kid: String): Either[String, PublicKey] =
    keys.get.get(kid) match
      case Some(key) => Right(key)
      case None =>
        refresh(force = false)
        keys.get.get(kid).toRight(s"key '$kid' is not one of the installation's")

  private def verifies(key: PublicKey, signed: String, signature: String): Boolean =
    try
      val verifier = Signature.getInstance("SHA256withRSA")
      verifier.initVerify(key)
      verifier.update(signed.getBytes(StandardCharsets.US_ASCII))
      verifier.verify(decode(signature))
    catch case NonFatal(_) => false

object MachineTokens:

  private val log = LoggerFactory.getLogger(classOf[MachineTokens])

  /** The audience every machine token names. */
  val Audience: String = "ankka"

  /** How far a clock may be from the control plane's. */
  val Skew: Duration = Duration.ofSeconds(60)

  /** The least time between two fetches of the keys that a token's unknown `kid` asks for. */
  val RefetchAfter: Duration = Duration.ofSeconds(30)

  /**
   * The file, beside the project's declarations, in which the platform says where tokens come from.
   */
  val FileName: String = "machines.json"

  private[http] final case class Header(alg: String, kid: Option[String] = None)

  private[http] final case class Claims(
      iss: String,
      sub: String = "",
      aud: Vector[String] = Vector.empty,
      exp: Option[Long] = None,
      nbf: Option[Long] = None,
      typ: Option[String] = None
  )

  private[http] final case class Jwk(
      kty: String,
      kid: Option[String] = None,
      n: Option[String] = None,
      e: Option[String] = None
  )

  private[http] final case class Jwks(keys: Vector[Jwk] = Vector.empty)

  /** Where tokens come from and where their keys are, as the platform writes it. */
  private[http] final case class Settings(issuer: String, jwksUrl: String)

  // Fields a codec does not name are skipped, jsoniter's default: a token or a key set may carry
  // claims and members nothing here reads.
  private given JsonValueCodec[Header]   = JsonCodecMaker.make
  private given JsonValueCodec[Jwks]     = JsonCodecMaker.make
  private given JsonValueCodec[Settings] = JsonCodecMaker.make

  /** `aud` is a string or an array of them; either reads as the array. */
  private given audience: JsonValueCodec[Vector[String]] = new JsonValueCodec[Vector[String]]:
    def nullValue: Vector[String] = Vector.empty
    def encodeValue(x: Vector[String], out: JsonWriter): Unit =
      out.writeArrayStart()
      x.foreach(out.writeVal)
      out.writeArrayEnd()
    def decodeValue(in: JsonReader, default: Vector[String]): Vector[String] =
      if in.isNextToken('[') then
        if in.isNextToken(']') then Vector.empty
        else
          in.rollbackToken()
          val b = Vector.newBuilder[String]
          while
            b += in.readString(null)
            in.isNextToken(',')
          do ()
          if !in.isCurrentToken(']') then in.arrayEndOrCommaError()
          b.result()
      else
        in.rollbackToken()
        Vector(in.readString(null))

  private given JsonValueCodec[Claims] = JsonCodecMaker.make

  private def decode(part: String): Array[Byte] = Base64.getUrlDecoder.decode(part)

  private def parts(token: String): Either[String, (String, String, String)] =
    token.split('.') match
      case Array(h, p, s) if h.nonEmpty && p.nonEmpty && s.nonEmpty => Right((h, p, s))
      case _                                                        => Left("not a signed token")

  private def claimsOf(payload: String): Either[String, Claims] =
    Try(readFromArray[Claims](decode(payload))).toEither.left.map(_ => "unreadable claims")

  /** The RSA keys of a JWKS document, by `kid`; any other key is skipped. */
  def keysOf(jwks: String): Map[String, PublicKey] =
    val factory = KeyFactory.getInstance("RSA")
    readFromString[Jwks](jwks).keys.flatMap { k =>
      for
        kid <- k.kid
        n   <- k.n
        e   <- k.e
        if k.kty == "RSA"
        key <- Try(
          factory.generatePublic(
            RSAPublicKeySpec(BigInteger(1, decode(n)), BigInteger(1, decode(e)))
          )
        ).toOption
      yield kid -> key
    }.toMap

  /** A verifier holding `keys` and fetching nothing: for tests. */
  def withKeys(
      issuer: String,
      keys: Map[String, PublicKey],
      clock: Clock = Clock.systemUTC()
  ): MachineTokens =
    val tokens = new MachineTokens(issuer, () => Some(keys), clock, background = false)
    tokens.start()
    tokens

  /**
   * A verifier reading the keys at `jwksUrl` over TLS trusting `ca` (the service authority, which
   * issued the control plane's certificate), or the JDK's own trust when no CA is given.
   */
  def fetching(
      issuer: String,
      jwksUrl: URI,
      ca: Option[Path],
      clock: Clock = Clock.systemUTC()
  ): MachineTokens =
    val builder = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
    ca.foreach(path => builder.sslContext(trusting(path)))
    val client = builder.build()
    def fetch(): Option[Map[String, PublicKey]] =
      val response = client.send(
        HttpRequest.newBuilder(jwksUrl).timeout(java.time.Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      if response.statusCode() == 200 then Some(keysOf(response.body()))
      else
        log.warn("machine token keys at {} answered {}", jwksUrl, response.statusCode())
        None
    new MachineTokens(issuer, () => fetch(), clock)

  private def trusting(ca: Path): SSLContext =
    val certificates = CertificateFactory
      .getInstance("X.509")
      .generateCertificates(Files.newInputStream(ca))
    val store = KeyStore.getInstance(KeyStore.getDefaultType)
    store.load(null, null)
    certificates.toArray.zipWithIndex.foreach { (c, i) =>
      store.setCertificateEntry(s"ca-$i", c.asInstanceOf[X509Certificate])
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val context = SSLContext.getInstance("TLS")
    context.init(null, trust.getTrustManagers, null)
    context

  /**
   * The verifier the configuration names, or none: `ankka.machines.issuer` and `jwks-url`
   * (`ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_JWKS_URL`), else `machines.json` beside the project's
   * declarations, which is where the platform writes them. The CA is `ankka.machines.jwks-ca`, else
   * `ca.crt` in the service certificate's directory. With neither, no token is verified and a
   * bearer leaves the caller as it was.
   */
  def fromConfig(
      config: com.typesafe.config.Config,
      tlsDirectory: Option[Path]
  ): Option[MachineTokens] =
    def setting(path: String) = if config.hasPath(path) then config.getString(path) else ""
    val stated = Option.when(setting("ankka.machines.issuer").nonEmpty)(
      Settings(setting("ankka.machines.issuer"), setting("ankka.machines.jwks-url"))
    )
    val written = Option(setting("ankka.grants.declarations"))
      .filter(_.nonEmpty)
      .map(d => Path.of(d).resolveSibling(FileName))
      .filter(Files.isRegularFile(_))
      .flatMap(file => Try(readFromArray[Settings](Files.readAllBytes(file))).toOption)
    stated.orElse(written).filter(s => s.issuer.nonEmpty && s.jwksUrl.nonEmpty).map { s =>
      val ca = Option(setting("ankka.machines.jwks-ca"))
        .filter(_.nonEmpty)
        .map(Path.of(_))
        .orElse(tlsDirectory.map(_.resolve("ca.crt")))
        .filter(Files.isRegularFile(_))
      fetching(s.issuer, URI.create(s.jwksUrl), ca)
    }
