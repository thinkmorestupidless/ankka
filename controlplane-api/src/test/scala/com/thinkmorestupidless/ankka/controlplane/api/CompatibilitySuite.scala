package com.thinkmorestupidless.ankka.controlplane.api

/**
 * The one rule about which application runtimes a platform will run: same major, minor within one
 * below the platform's. Coarse on purpose — feature 006, research R3.
 */
class CompatibilitySuite extends munit.FunSuite:

  private def v(s: String) = Version.parse(s).fold(fail(_), identity)

  test("versions parse from the forms dynver produces, and refuse anything else") {
    assertEquals(v("0.2.0"), Version(0, 2, 0))
    assertEquals(v("0.2.0+3-abc1234-SNAPSHOT"), Version(0, 2, 0))
    assertEquals(v("0.2.0+12-e7365ae9+20260918-1900-SNAPSHOT"), Version(0, 2, 0))
    assertEquals(v("1.10.3"), Version(1, 10, 3))
    for bad <- Vector("x", "0.2", "", "v0.2.0", "0.2.0.1") do
      assert(Version.parse(bad).isLeft, s"'$bad' should not parse")
    assert(Version.parse("0.2").left.exists(_.contains("MAJOR.MINOR.PATCH")))
  }

  test("supported: same major, minor equal to the platform's or one below") {
    val platform = v("0.3.1")
    assert(Compatibility.supports(platform, v("0.3.0")))
    assert(Compatibility.supports(platform, v("0.3.9")))
    assert(Compatibility.supports(platform, v("0.2.9")))
    assert(!Compatibility.supports(platform, v("0.1.0")))
    assert(!Compatibility.supports(platform, v("0.4.0")), "a newer runtime than the platform")
    assert(!Compatibility.supports(v("1.0.0"), v("0.9.0")), "a major boundary")
  }

  test("the range is described in one phrase a person can read") {
    assertEquals(Compatibility.describe(v("0.3.1")), "runtimes 0.2.x–0.3.x (platform 0.3.1)")
    assertEquals(Compatibility.describe(v("0.0.5")), "runtimes 0.0.x (platform 0.0.5)")
    assertEquals(Compatibility.describe(v("2.0.0")), "runtimes 2.0.x (platform 2.0.0)")
  }

  test("from 0.8.0 a runtime without mutual TLS is refused, naming the floor") {
    val platform = v("0.8.0")
    assert(!Compatibility.supports(platform, v("0.7.9")), "one minor below, but below the floor")
    assert(Compatibility.supports(platform, v("0.8.0")))
    assertEquals(
      Compatibility.refusal(platform, v("0.7.1")),
      "runtime 0.7.1 predates mutual TLS; this platform requires 0.8.0 or later"
    )
    assert(Compatibility.refusal(v("0.9.0"), v("0.1.0")).contains("predates mutual TLS"))
  }

  test("the floor does not refuse the builds of the release that introduces it") {
    // Until 0.8.0 is tagged this branch builds as 0.7.1+N, and must run its own images.
    val snapshot = v("0.7.1+11-5623eff8-SNAPSHOT")
    assert(Compatibility.supports(snapshot, v("0.7.1")))
    assert(
      Compatibility.refusal(snapshot, v("0.5.0")).contains("outside the platform's supported range")
    )
  }

  test("the sidecar protocol is 1.13, and an SDK on an earlier minor is still supported") {
    assertEquals(Protocol.version, ProtocolVersion(1, 13))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 0)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 1)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 2)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 3)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 4)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 5)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 6)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 7)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 8)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 10)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 11)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 12)))
    assert(Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 13)))
    assert(!Compatibility.supportsProtocol(Protocol.version, ProtocolVersion(1, 14)))
  }
