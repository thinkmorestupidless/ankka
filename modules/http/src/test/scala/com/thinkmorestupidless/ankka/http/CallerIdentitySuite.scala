package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.URI
import java.security.cert.X509Certificate

/**
 * Who a client certificate names, for every shape of `ankka://` URI a certificate can carry.
 *
 * A request under a web-hosted service's mount arrives with an identity of its own, chosen because
 * a runtime that predates it refuses it. That is only true while the reading of a certificate stays
 * what it is, so each shape is pinned here, and `PreFeatureCaller` keeps a copy of the reading as
 * it was, which no change to the runtime can move.
 */
class CallerIdentitySuite extends munit.FunSuite:

  private val authority = TestPki.root("caller-identity-suite")

  private def certificate(uris: String*): X509Certificate =
    authority.issue(uris = uris, dnsNames = Seq("localhost")).certificate

  private val unrecognised = Left("unrecognised caller certificate")

  /** The callee's own identity: a service of the project "shop". */
  private val shop = Some(RotatingTls.Identity("shop", "cart"))

  test("a service's URI names that service") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web"), shop),
      Right(Caller.Service("shop", "web"))
    )
  }

  test("the gateway's URI is the internet") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://gateway"), shop),
      Right(Caller.Gateway)
    )
  }

  test("a mount's URI of the callee's own project is the internet") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web/mount"), shop),
      Right(Caller.Gateway)
    )
  }

  test("a mount's URI of another project is refused, naming why") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://billing/portal/mount"), shop),
      Left("a request under a mount of another project")
    )
  }

  test("with no identity of its own to compare with, a mount's URI is refused") {
    assertEquals(Caller.fromCertificate(certificate("ankka://shop/web/mount"), None), unrecognised)
  }

  test("only the exact mount shape is a mount: another extra segment, or one more, is refused") {
    for uri <- Vector("ankka://shop/web/other", "ankka://shop/web/mount/x") do
      assertEquals(Caller.fromCertificate(certificate(uri), shop), unrecognised, uri)
  }

  test("a certificate with a service's URI and a mount's is the service") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web/mount", "ankka://shop/web"), shop),
      Right(Caller.Service("shop", "web"))
    )
  }

  test("a service's URI with a trailing slash, or with no service, is refused") {
    assertEquals(Caller.fromCertificate(certificate("ankka://shop/web/"), shop), unrecognised)
    assertEquals(Caller.fromCertificate(certificate("ankka://shop"), shop), unrecognised)
  }

  test("a certificate with no ankka URI is refused") {
    assertEquals(Caller.fromCertificate(certificate("spiffe://shop/web"), shop), unrecognised)
    assertEquals(Caller.fromCertificate(certificate(), shop), unrecognised)
  }

  test("a query or a fragment is no part of the identity: both read as the service") {
    // Which is why the mount's identity is a path segment and never either of these.
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web?mount"), shop),
      Right(Caller.Service("shop", "web"))
    )
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web#mount"), shop),
      Right(Caller.Service("shop", "web"))
    )
  }

  test("the gateway's URI among several is the internet, wherever it is") {
    assertEquals(
      Caller.fromCertificate(certificate("ankka://shop/web", "ankka://gateway"), shop),
      Right(Caller.Gateway)
    )
  }

  test("of two services' URIs, the first that parses is the caller") {
    assertEquals(
      Caller.fromCertificate(
        certificate("ankka://shop/web/mount", "ankka://shop/orders", "ankka://shop/web"),
        shop
      ),
      Right(Caller.Service("shop", "orders"))
    )
  }

  test("a runtime from before web hosting refuses a mount's URI") {
    assertEquals(
      PreFeatureCaller.fromCertificate(certificate("ankka://shop/web/mount")),
      unrecognised
    )
    assertEquals(
      PreFeatureCaller.fromCertificate(certificate("ankka://shop/web")),
      Right(Caller.Service("shop", "web"))
    )
  }

/**
 * How a runtime read a client certificate before web hosting existed, copied verbatim and frozen on
 * purpose: it stands in for a service still running such a runtime, which a request under a mount
 * must reach as a refusal and never as the web-hosted service's call. It must never be edited to
 * follow the runtime; if the runtime's reading changes, this is what it is compared with.
 */
object PreFeatureCaller:

  private val GatewayUri = "ankka://gateway"

  private def parseServiceUri(text: String): Option[(String, String)] =
    try
      val uri  = URI(text)
      val path = Option(uri.getPath).getOrElse("").stripPrefix("/")
      if uri.getScheme == "ankka" && Option(uri.getHost).exists(_.nonEmpty) && path.nonEmpty &&
        !path.contains('/')
      then Some((uri.getHost, path))
      else None
    catch case _: Exception => None

  def fromCertificate(certificate: X509Certificate): Either[String, Caller] =
    val uris = RotatingTls.ankkaUris(certificate)
    if uris.contains(GatewayUri) then Right(Caller.Gateway)
    else
      uris.flatMap(parseServiceUri).headOption match
        case Some((project, service)) => Right(Caller.Service(project, service))
        case None                     => Left("unrecognised caller certificate")
