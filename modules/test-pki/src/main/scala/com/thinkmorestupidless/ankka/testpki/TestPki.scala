package com.thinkmorestupidless.ankka.testpki

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{
  BasicConstraints,
  ExtendedKeyUsage,
  Extension,
  GeneralName,
  GeneralNames,
  KeyPurposeId,
  KeyUsage
}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator, PrivateKey, SecureRandom}
import java.time.{Duration, Instant}
import java.util.{Base64, Date}

/**
 * A certificate authority minted in-process, for tests.
 *
 * Certificates are RSA and PKCS#8 because that is the one shape every consumer in ankka reads:
 * Pekko's rotating-keys remoting engine reads RSA only and refuses an intermediate issuer, and the
 * JDK and Netty both take PKCS#8. Checked-in fixtures would expire and could not express rotation;
 * minting here lets a test ask for a certificate that expires in seconds or names any identity it
 * likes.
 */
final class TestPki private (val name: String, keyPair: KeyPair, val certificate: X509Certificate):

  /**
   * A leaf signed by this root. `uris` and `dnsNames` become subject alternative names; `cn`, when
   * given, is the subject's common name (a Postgres `cert` login matches it to the role).
   */
  def issue(
      cn: Option[String] = None,
      uris: Seq[String] = Nil,
      dnsNames: Seq[String] = Nil,
      validFor: Duration = Duration.ofDays(1),
      notBefore: Instant = Instant.now().minusSeconds(60)
  ): TestPki.Leaf =
    val leafKeys = TestPki.rsa()
    val subject = new X500Name(
      s"CN=${cn.getOrElse(uris.headOption.orElse(dnsNames.headOption).getOrElse("leaf"))}"
    )
    val builder = new JcaX509v3CertificateBuilder(
      new X500Name(certificate.getSubjectX500Principal.getName),
      TestPki.serial(),
      Date.from(notBefore),
      Date.from(notBefore.plus(validFor)),
      subject,
      leafKeys.getPublic
    )
    val names =
      uris.map(u => new GeneralName(GeneralName.uniformResourceIdentifier, u)) ++
        dnsNames.map(d => new GeneralName(GeneralName.dNSName, d))
    if names.nonEmpty then
      builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(names.toArray)
      ): Unit
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
    builder.addExtension(
      Extension.keyUsage,
      true,
      new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
    )
    builder.addExtension(
      Extension.extendedKeyUsage,
      false,
      new ExtendedKeyUsage(Array(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
    )
    val cert = TestPki.sign(builder, keyPair.getPrivate)
    TestPki.Leaf(leafKeys.getPrivate, cert, certificate)

  /** This root in PEM, as a `ca.crt` would carry it. */
  def pem: String = TestPki.certificatePem(certificate)

  override def toString: String = s"TestPki($name)"

object TestPki:

  /** A self-signed root CA. */
  def root(name: String, validFor: Duration = Duration.ofDays(3650)): TestPki =
    val keys    = rsa()
    val subject = new X500Name(s"CN=$name")
    val now     = Instant.now().minusSeconds(60)
    val builder = new JcaX509v3CertificateBuilder(
      subject,
      serial(),
      Date.from(now),
      Date.from(now.plus(validFor)),
      subject,
      keys.getPublic
    )
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
    builder.addExtension(
      Extension.keyUsage,
      true,
      new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
    )
    new TestPki(name, keys, sign(builder, keys.getPrivate))

  /** A leaf, its key, and the root that issued it. */
  final case class Leaf(key: PrivateKey, certificate: X509Certificate, ca: X509Certificate):

    def keyPem: String  = pem("PRIVATE KEY", key.getEncoded)
    def certPem: String = certificatePem(certificate)
    def caPem: String   = certificatePem(ca)

    /**
     * Writes `tls.key`, `tls.crt` and `ca.crt` into `directory` — the keys cert-manager writes into
     * a CA-issued Secret — each atomically, so a reader that polls never sees a half-written file.
     * Returns the directory.
     */
    def writeTo(directory: Path): Path =
      Files.createDirectories(directory): Unit
      write(directory.resolve("tls.key"), keyPem)
      write(directory.resolve("tls.crt"), certPem)
      write(directory.resolve("ca.crt"), caPem)
      directory

    def serial: BigInteger = certificate.getSerialNumber

  private def write(target: Path, content: String): Unit =
    val temp = Files.createTempFile(target.getParent, ".pki", ".tmp")
    Files.writeString(temp, content, StandardCharsets.US_ASCII)
    Files.move(
      temp,
      target,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    ): Unit

  def certificatePem(certificate: X509Certificate): String =
    pem("CERTIFICATE", certificate.getEncoded)

  private def pem(kind: String, der: Array[Byte]): String =
    val body =
      Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
    s"-----BEGIN $kind-----\n$body\n-----END $kind-----\n"

  private val random = new SecureRandom()

  private def serial(): BigInteger = new BigInteger(64, random).abs().add(BigInteger.ONE)

  private def rsa(): KeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048, random)
    generator.generateKeyPair()

  private def sign(builder: JcaX509v3CertificateBuilder, key: PrivateKey): X509Certificate =
    val signer = new JcaContentSignerBuilder("SHA256withRSA").build(key)
    new JcaX509CertificateConverter().getCertificate(builder.build(signer))
