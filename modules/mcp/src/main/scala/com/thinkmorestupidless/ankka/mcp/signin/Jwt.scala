package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, PrivateKey, PublicKey, Signature}
import java.util.Base64
import scala.util.Try

/**
 * RS256 JSON Web Tokens with the JDK alone (feature 054), as `http.MachineTokens` reads them:
 * nimbus stays in `ankka-auth-oidc`. Only what the sign-in broker mints and reads: one algorithm,
 * compact form, an RSA key as a JWK.
 */
private[mcp] object Jwt:

  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder

  def encode(bytes: Array[Byte]): String = encoder.encodeToString(bytes)

  def decode(text: String): Option[Array[Byte]] = Try(decoder.decode(text)).toOption

  /** `header.claims.signature`, signed with `key`. */
  def sign(kid: String, claims: Json, key: PrivateKey): String =
    val header =
      Json.obj("alg" -> Json.str("RS256"), "kid" -> Json.str(kid), "typ" -> Json.str("JWT"))
    val input = s"${encode(header.render.getBytes(UTF_8))}.${encode(claims.render.getBytes(UTF_8))}"
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(key)
    signer.update(input.getBytes(UTF_8))
    s"$input.${encode(signer.sign())}"

  /** A token taken apart, nothing verified yet. */
  final case class Parts(header: Json, claims: Json, input: String, signature: Array[Byte]):
    def kid: Option[String] = header("kid").flatMap(_.asString)
    def alg: Option[String] = header("alg").flatMap(_.asString)

  def parts(token: String): Either[String, Parts] =
    token.split("\\.", -1) match
      case Array(h, c, s) =>
        (for
          headerBytes <- decode(h)
          claimBytes  <- decode(c)
          signature   <- decode(s)
          header      <- Json.parse(String(headerBytes, UTF_8)).toOption
          claims      <- Json.parse(String(claimBytes, UTF_8)).toOption
        yield Parts(header, claims, s"$h.$c", signature)).toRight("not a well-formed token")
      case _ => Left("not a well-formed token")

  def verifies(key: PublicKey, parts: Parts): Boolean =
    Try {
      val verifier = Signature.getInstance("SHA256withRSA")
      verifier.initVerify(key)
      verifier.update(parts.input.getBytes(UTF_8))
      verifier.verify(parts.signature)
    }.getOrElse(false)

  /** An RSA public key as a JWK. */
  def jwk(kid: String, key: RSAPublicKey): Json =
    def unsigned(n: BigInteger) =
      val bytes = n.toByteArray
      encode(if bytes.length > 1 && bytes(0) == 0 then bytes.drop(1) else bytes)
    Json.obj(
      "kty" -> Json.str("RSA"),
      "kid" -> Json.str(kid),
      "use" -> Json.str("sig"),
      "alg" -> Json.str("RS256"),
      "n"   -> Json.str(unsigned(key.getModulus)),
      "e"   -> Json.str(unsigned(key.getPublicExponent))
    )

  /** The public key a JWK names, when it is an RSA one. */
  def publicKey(jwk: Json): Option[PublicKey] =
    for
      n <- jwk("n").flatMap(_.asString).flatMap(decode)
      e <- jwk("e").flatMap(_.asString).flatMap(decode)
      key <- Try(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(RSAPublicKeySpec(BigInteger(1, n), BigInteger(1, e)))
      ).toOption
    yield key
