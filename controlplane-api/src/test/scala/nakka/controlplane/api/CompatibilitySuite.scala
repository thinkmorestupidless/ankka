package nakka.controlplane.api

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
