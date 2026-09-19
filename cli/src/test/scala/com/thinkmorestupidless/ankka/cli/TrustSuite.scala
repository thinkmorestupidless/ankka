package com.thinkmorestupidless.ankka.cli

import java.nio.file.{Files, Path, Paths}
import java.security.cert.CertificateException

/**
 * The CLI trusts the platform's roots plus a named file — and nothing else. The fixtures are two
 * self-signed certificates whose private keys were discarded at generation; they sign nothing.
 */
class TrustSuite extends munit.FunSuite:

  private def fixture(name: String): Path =
    Paths.get(getClass.getResource(s"/$name").toURI)

  test("a certificate in the named file is trusted") {
    val manager = Trust.trustManager(fixture("trusted-test-ca.pem"))
    val chain   = Trust.certificates(fixture("trusted-test-ca.pem")).toArray
    manager.checkServerTrusted(chain, "ECDHE_ECDSA") // does not throw
  }

  test("a certificate not in the file — and not a platform root — is refused") {
    val manager = Trust.trustManager(fixture("trusted-test-ca.pem"))
    val chain   = Trust.certificates(fixture("untrusted-test-ca.pem")).toArray
    intercept[CertificateException](manager.checkServerTrusted(chain, "ECDHE_ECDSA"))
  }

  test("the platform's own roots stay trusted alongside the file") {
    val manager = Trust.trustManager(fixture("trusted-test-ca.pem"))
    assert(manager.getAcceptedIssuers.length > 1, "only the file's certificate is trusted")
  }

  test("an unreadable file is an error, not silent trust of nothing") {
    val missing = Files.createTempDirectory("ankka-trust").resolve("nope.pem")
    intercept[Exception](Trust.trustManager(missing))
  }
