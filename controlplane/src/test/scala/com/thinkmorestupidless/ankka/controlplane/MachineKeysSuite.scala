package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.{MachineKeyWriter, MachineKeys}
import com.thinkmorestupidless.ankka.http.{Caller, MachineTokens}

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference

/**
 * The keys machine tokens are signed with (feature 040): signed with the newest, every key
 * published, read from the mounted Secret as it changes, a new key written there first, and an old
 * one dropped only when no token it signed can still be alive.
 */
class MachineKeysSuite extends munit.FunSuite:

  private val issuer = "https://api.example.test"

  private def claims(at: Instant) =
    s"""{"iss":"$issuer","sub":"machine:eitheror/affiliate-network","aud":["ankka"],""" +
      s""""iat":${at.getEpochSecond},"nbf":${at.getEpochSecond},"exp":${at.getEpochSecond + 900},"typ":"Bearer"}"""

  /** What a service would make of a token, given the key set the control plane publishes. */
  private def verified(keys: MachineKeys, token: String, at: Instant) =
    MachineTokens
      .withKeys(
        issuer,
        MachineTokens.keysOf(writeToString(keys.jwks)),
        Clock.fixed(at, ZoneOffset.UTC)
      )
      .verify(token)

  private val machine = Caller.Machine("eitheror", "affiliate-network")

  final class Recording extends MachineKeyWriter:
    val written                                = AtomicReference(Vector.empty[(String, String)])
    val removed                                = AtomicReference(Vector.empty[String])
    def addKey(kid: String, pem: String): Unit = written.updateAndGet(_ :+ (kid -> pem)): Unit
    def removeKeys(kids: Seq[String]): Unit    = removed.updateAndGet(_ ++ kids): Unit

  test("in memory, one key is made on first need, and a token it signs verifies by the key set") {
    val now  = Instant.parse("2026-10-09T10:00:00Z")
    val keys = MachineKeys.inMemory(Clock.fixed(now, ZoneOffset.UTC))
    assertEquals(keys.keys, Vector.empty)
    val token = keys.sign(claims(now))
    assertEquals(keys.keys.size, 1)
    assertEquals(verified(keys, token, now), Right(machine))
    assert(keys.keys.head.kid.startsWith("20261009100000"), keys.keys.head.kid)
  }

  test(
    "a rotation signs with the new key and publishes both, so a token from before still verifies"
  ) {
    val clock = AtomicReference(Instant.parse("2026-10-09T10:00:00Z"))
    val keys  = MachineKeys.inMemory(ticking(clock))
    val old   = keys.sign(claims(clock.get))
    clock.set(clock.get.plusSeconds(60))
    val kid   = keys.rotate()
    val fresh = keys.sign(claims(clock.get))
    assertEquals(keys.keys.map(_.kid).last, kid)
    assert(fresh.split('.')(0) != old.split('.')(0), "the new token names the new key")
    assertEquals(verified(keys, old, clock.get), Right(machine))
    assertEquals(verified(keys, fresh, clock.get), Right(machine))
  }

  test(
    "from a directory, the newest key signs, every key is published, and a key added later is seen"
  ) {
    val dir   = Files.createTempDirectory("machine-keys")
    val first = MachineKeys.inMemory()
    first.sign(claims(Instant.now())): Unit
    val a = first.keys.head
    write(dir, "20261001000000aaaa", a, Instant.parse("2026-10-01T00:00:00Z"))
    val writer = Recording()
    val now    = Instant.parse("2026-10-09T10:00:00Z")
    val keys   = MachineKeys.mounted(dir, writer, Clock.fixed(now, ZoneOffset.UTC))
    assertEquals(keys.keys.map(_.kid), Vector("20261001000000aaaa"))
    val token = keys.sign(claims(now))
    assertEquals(verified(keys, token, now), Right(machine))
    assertEquals(writer.written.get, Vector.empty, "a key the directory holds is not written again")

    val second = MachineKeys.inMemory()
    second.sign(claims(now)): Unit
    write(dir, "20261009000000bbbb", second.keys.head, Instant.parse("2026-10-09T00:00:00Z"))
    assertEquals(keys.keys.map(_.kid), Vector("20261001000000aaaa", "20261009000000bbbb"))
    assert(keys.sign(claims(now)).split('.')(0) != token.split('.')(0), "the newest signs")
  }

  test("an unreadable key file is passed over, and the keys last read are kept") {
    val dir = Files.createTempDirectory("machine-keys")
    val k   = MachineKeys.inMemory()
    k.sign(claims(Instant.now())): Unit
    write(dir, "20261001000000aaaa", k.keys.head, Instant.parse("2026-10-01T00:00:00Z"))
    val keys = MachineKeys.mounted(dir, Recording())
    assertEquals(keys.keys.size, 1)
    Files.writeString(dir.resolve("20261009000000cccc.pem"), "not a key"): Unit
    Files.setLastModifiedTime(dir.resolve("20261009000000cccc.pem"), FileTime.from(Instant.now()))
    assertEquals(keys.keys.map(_.kid), Vector("20261001000000aaaa"))
  }

  test("with no key in the directory, one is made and written there first, and used meanwhile") {
    val dir    = Files.createTempDirectory("machine-keys")
    val writer = Recording()
    val now    = Instant.parse("2026-10-09T10:00:00Z")
    val keys   = MachineKeys.mounted(dir, writer, Clock.fixed(now, ZoneOffset.UTC))
    val token  = keys.sign(claims(now))
    assertEquals(writer.written.get.size, 1)
    val (kid, pem) = writer.written.get.head
    assert(pem.startsWith("-----BEGIN PRIVATE KEY-----"), pem)
    assertEquals(keys.keys.map(_.kid), Vector(kid))
    assertEquals(verified(keys, token, now), Right(machine))
    // The mount catches up: the same key, now read from the file.
    Files.writeString(dir.resolve(s"$kid.pem"), pem): Unit
    assertEquals(keys.keys.map(_.kid), Vector(kid))
  }

  test("a sweep drops every key over 31 days old but the newest, and nothing younger") {
    val clock  = AtomicReference(Instant.parse("2026-08-01T00:00:00Z"))
    val writer = Recording()
    val keys =
      MachineKeys.mounted(Files.createTempDirectory("machine-keys"), writer, ticking(clock))
    val first = keys.rotate()
    clock.set(Instant.parse("2026-08-31T00:00:00Z"))
    assert(!keys.due)
    clock.set(Instant.parse("2026-09-01T00:00:00Z"))
    assert(keys.due, "thirty days on, a rotation is due")
    val second = keys.rotate()
    assertEquals(keys.sweep(), Vector.empty, "the first is a day younger than 31 days")
    clock.set(Instant.parse("2026-09-02T00:00:01Z"))
    assertEquals(keys.sweep(), Vector(first))
    assertEquals(writer.removed.get, Vector(first))
    assertEquals(keys.keys.map(_.kid), Vector(second))
    clock.set(Instant.parse("2027-01-01T00:00:00Z"))
    assertEquals(keys.sweep(), Vector.empty, "the newest is never dropped")
  }

  test("the rotation's look replaces a key thirty days old, and leaves a younger one alone") {
    val clock = AtomicReference(Instant.parse("2026-08-01T00:00:00Z"))
    val keys =
      MachineKeys.mounted(Files.createTempDirectory("machine-keys"), Recording(), ticking(clock))
    val rotation = com.thinkmorestupidless.ankka.controlplane.auth.MachineKeyRotation(keys)
    rotation.check()
    assertEquals(keys.keys, Vector.empty, "no key is made by a look: the first token makes one")
    val first = keys.rotate()
    clock.set(Instant.parse("2026-08-20T00:00:00Z"))
    rotation.check()
    assertEquals(keys.keys.map(_.kid), Vector(first))
    clock.set(Instant.parse("2026-09-01T00:00:00Z"))
    rotation.check()
    assertEquals(keys.keys.size, 2)
    assertEquals(keys.keys.head.kid, first)
  }

  private def ticking(now: AtomicReference[Instant]): Clock = new Clock:
    def getZone                                = ZoneOffset.UTC
    override def withZone(z: java.time.ZoneId) = this
    def instant(): Instant                     = now.get

  private def write(dir: Path, kid: String, key: MachineKeys.Key, at: Instant): Unit =
    val file = dir.resolve(s"$kid.pem")
    Files.writeString(file, MachineKeys.pem(key.privateKey)): Unit
    Files.setLastModifiedTime(file, FileTime.from(at)): Unit
