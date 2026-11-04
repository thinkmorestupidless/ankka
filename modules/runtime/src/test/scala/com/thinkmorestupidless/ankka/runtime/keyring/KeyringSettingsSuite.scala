package com.thinkmorestupidless.ankka.runtime.keyring

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*

final class KeyringSettingsSuite extends munit.FunSuite:

  private def read(overrides: String) =
    KeyringSettings.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("the defaults are the data model's") {
    val s = read("")
    assertEquals(s.address, None)
    assertEquals(s.maxKeys, 10000)
    assertEquals(s.ttl, 5.minutes)
    assertEquals(s.outageBound, 15.minutes)
    assertEquals(s.noticeBound, 60.seconds)
    assertEquals(s.fetchTimeout, 2.seconds)
  }

  test("an address is read, and a blank one is none") {
    assertEquals(
      read("ankka.keyring.address = \"keyring.ankka-keyring:8443\"").address,
      Some("keyring.ankka-keyring:8443")
    )
    assertEquals(read("ankka.keyring.address = \"  \"").address, None)
  }

  test("a bound that is not positive is refused, naming the setting") {
    val e = intercept[IllegalArgumentException](read("ankka.keyring.fetch-timeout = 0s"))
    assert(e.getMessage.contains("ankka.keyring.fetch-timeout"), e.getMessage)
    val k = intercept[IllegalArgumentException](read("ankka.keyring.cache.max-keys = 0"))
    assert(k.getMessage.contains("max-keys"), k.getMessage)
  }

  test("an outage bound shorter than a key's lifetime is refused") {
    val e = intercept[IllegalArgumentException](
      read("ankka.keyring.cache { ttl = 10m, outage-bound = 5m }")
    )
    assert(e.getMessage.contains("outage-bound"), e.getMessage)
  }
