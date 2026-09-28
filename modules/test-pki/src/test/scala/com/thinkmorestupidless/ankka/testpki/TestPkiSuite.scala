package com.thinkmorestupidless.ankka.testpki

import java.nio.file.Files
import java.security.cert.{CertPathValidator, CertificateFactory, PKIXParameters, TrustAnchor}
import scala.jdk.CollectionConverters.*

class TestPkiSuite extends munit.FunSuite:

  private def validates(leaf: TestPki.Leaf, root: TestPki): Boolean =
    val factory = CertificateFactory.getInstance("X.509")
    val path    = factory.generateCertPath(List(leaf.certificate).asJava)
    val params  = new PKIXParameters(Set(new TrustAnchor(root.certificate, null)).asJava)
    params.setRevocationEnabled(false)
    try
      CertPathValidator.getInstance("PKIX").validate(path, params)
      true
    catch case _: Exception => false

  test("a leaf validates against its own root and not against another") {
    val root  = TestPki.root("one")
    val other = TestPki.root("two")
    val leaf  = root.issue(uris = Seq("ankka://p/s"))
    assert(validates(leaf, root))
    assert(!validates(leaf, other))
  }

  test("subject alternative names carry the URI and DNS names asked for") {
    val leaf = TestPki.root("r").issue(uris = Seq("ankka://p/s"), dnsNames = Seq("s.ns.svc"))
    val sans = leaf.certificate.getSubjectAlternativeNames.asScala.map(_.asScala.toList).toSet
    assertEquals(sans, Set(List(6, "ankka://p/s"), List(2, "s.ns.svc")))
  }

  test("writeTo lays out the three files cert-manager writes") {
    val dir  = Files.createTempDirectory("pki")
    val leaf = TestPki.root("r").issue(cn = Some("orders"))
    leaf.writeTo(dir)
    assert(Files.readString(dir.resolve("tls.key")).startsWith("-----BEGIN PRIVATE KEY-----"))
    assert(Files.readString(dir.resolve("tls.crt")).startsWith("-----BEGIN CERTIFICATE-----"))
    assertEquals(Files.readString(dir.resolve("ca.crt")), leaf.caPem)
    assert(leaf.certificate.getSubjectX500Principal.getName.contains("CN=orders"))
  }
