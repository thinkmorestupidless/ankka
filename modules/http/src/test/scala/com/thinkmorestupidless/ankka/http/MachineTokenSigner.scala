package com.thinkmorestupidless.ankka.http

import java.nio.charset.StandardCharsets
import java.security.interfaces.RSAPublicKey
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.time.Instant
import java.util.Base64

/**
 * Signs machine tokens as the control plane does, with a key the JDK generates: RS256, a `kid`, and
 * the claims of a machine's token, any of which a case may change or leave out.
 */
final class MachineTokenSigner(val kid: String = "test-key"):

  val keys: KeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  def publicKey: RSAPublicKey = keys.getPublic.asInstanceOf[RSAPublicKey]

  private def b64(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def unsigned(bigInt: java.math.BigInteger): Array[Byte] =
    val bytes = bigInt.toByteArray
    if bytes.length > 1 && bytes(0) == 0 then bytes.drop(1) else bytes

  /** This key as a JWKS document holds it. */
  def jwk: String =
    s"""{"kty":"RSA","use":"sig","alg":"RS256","kid":"$kid",""" +
      s""""n":"${b64(unsigned(publicKey.getModulus))}","e":"${b64(
          unsigned(publicKey.getPublicExponent)
        )}"}"""

  def jwks: String = s"""{"keys":[$jwk]}"""

  /**
   * A token with `claims` as the JSON object's members, signed RS256 unless `alg` says otherwise.
   */
  def sign(claims: String, alg: String = "RS256", kidOf: String = kid): String =
    val header = b64(
      s"""{"alg":"$alg","kid":"$kidOf","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8)
    )
    val payload = b64(s"{$claims}".getBytes(StandardCharsets.UTF_8))
    val signed  = s"$header.$payload"
    val signature =
      if alg == "RS256" then
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.getPrivate)
        signer.update(signed.getBytes(StandardCharsets.US_ASCII))
        b64(signer.sign())
      else b64("not a signature".getBytes(StandardCharsets.UTF_8))
    s"$signed.$signature"

  /** The claims of a machine's token from `issuer`, valid for fifteen minutes from `now`. */
  def claims(
      issuer: String,
      subject: String = "machine:eitheror/affiliate-network",
      now: Instant = Instant.now(),
      audience: String = """["ankka"]""",
      typ: Option[String] = Some("Bearer"),
      lifetime: Long = 900
  ): String =
    val at = now.getEpochSecond
    Vector(
      Some(s""""iss":"$issuer""""),
      Some(s""""sub":"$subject""""),
      Some(s""""aud":$audience"""),
      Some(s""""iat":$at"""),
      Some(s""""nbf":$at"""),
      Some(s""""exp":${at + lifetime}"""),
      typ.map(t => s""""typ":"$t"""")
    ).flatten.mkString(",")

  /** A valid machine token from `issuer`. */
  def token(issuer: String, subject: String = "machine:eitheror/affiliate-network"): String =
    sign(claims(issuer, subject))
