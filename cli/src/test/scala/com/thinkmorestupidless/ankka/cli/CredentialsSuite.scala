package com.thinkmorestupidless.ankka.cli

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The credentials file: private, per installation, and relocated with the config. */
class CredentialsSuite extends munit.FunSuite:

  private val scratch = FunFixture[Path](
    setup = _ =>
      val dir = Files.createTempDirectory("ankka-credentials")
      sys.props("ankka.config") = dir.resolve("config.json").toString
      dir
    ,
    teardown = dir =>
      sys.props.remove("ankka.config"): Unit
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p): Unit)
  )

  scratch.test("the file sits beside the config and is readable by its owner only") { dir =>
    Credentials.put("http://cp:9000", Login("issuer", "ankka-cli", "r", "a", 10L)): Unit
    assertEquals(Credentials.path, dir.resolve("credentials.json"))
    assert(Files.exists(Credentials.path))
    val permissions = Files.getPosixFilePermissions(Credentials.path).asScala.toSet
    assertEquals(permissions, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
  }

  scratch.test("logins are keyed by installation and a trailing slash or case does not split one") {
    _ =>
      Credentials.put("http://one:9000", Login("i1", "c", "r1", "a1", 1L)): Unit
      Credentials.put("http://two:9000/", Login("i2", "c", "r2", "a2", 2L)): Unit
      assertEquals(Credentials.get("http://one:9000").map(_.refreshToken), Some("r1"))
      assertEquals(Credentials.get("HTTP://two:9000").map(_.refreshToken), Some("r2"))
      Credentials.remove("http://one:9000/"): Unit
      assertEquals(Credentials.get("http://one:9000"), None)
      assertEquals(Credentials.get("http://two:9000").map(_.refreshToken), Some("r2"))
  }

  scratch.test("an unreadable file reads as no logins rather than a failure") { _ =>
    Files.writeString(Credentials.path, "{not json")
    assertEquals(Credentials.load(), Map.empty)
  }

  scratch.test("an access token is usable until shortly before it expires") { _ =>
    val login = Login("i", "c", "r", "a", expiresAt = 1000L)
    assert(login.accessTokenUsable(now = 900L))
    assert(!login.accessTokenUsable(now = 980L), "inside the 30 second margin")
    assert(!login.accessTokenUsable(now = 1001L))
  }
