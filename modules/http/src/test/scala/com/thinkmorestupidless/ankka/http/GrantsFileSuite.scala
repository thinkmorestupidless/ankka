package com.thinkmorestupidless.ankka.http

import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.FileTime
import scala.concurrent.duration.DurationInt

/**
 * The grants file a deployed service reads (feature 040): its own routes' and methods' grants,
 * re-read when the real file's modification time moves, the last good read kept through a bad one,
 * and a change announced so open sockets can be ended.
 */
class GrantsFileSuite extends munit.FunSuite:

  private val merchant = Caller.Service("payments", "merchant")
  private val network  = Caller.Machine("affiliates", "network")
  private val deposits = GrantTarget.Route("POST", "/v1/wallets/{player}/{currency}/deposits")

  private val content =
    """{"project":"spinvibe","grants":[""" +
      """{"id":"a1","grantee":"service:payments/merchant","kind":"route","service":"wallet","httpMethod":"POST","path":"/v1/wallets/{player}/{currency}/deposits"},""" +
      """{"id":"b2","grantee":"machine:affiliates/network","kind":"method","service":"wallet","method":"WalletService/Deposit"},""" +
      """{"id":"c3","grantee":"service:payments/merchant","kind":"route","service":"lobby","httpMethod":"GET","path":"/v1/lobby"},""" +
      """{"id":"d4","grantee":"service:payments/merchant","kind":"topic","topic":"casino.players","right":"consume","decrypt":true},""" +
      """{"id":"e5","grantee":"service:payments/merchant","kind":"erasure"}]}"""

  private def write(file: Path, text: String, at: Long): Unit =
    Files.writeString(file, text)
    Files.setLastModifiedTime(file, FileTime.fromMillis(at)): Unit

  test("only this service's route and method grants are kept; topics and erasure are others'") {
    val entries = GrantsFile.parse(content.getBytes("UTF-8"), "wallet")
    assertEquals(
      entries,
      Vector(
        GrantEntry(merchant, deposits),
        GrantEntry(network, GrantTarget.Method("WalletService/Deposit"))
      )
    )
    assertEquals(GrantsFile.parse(content.getBytes("UTF-8"), "lobby").size, 1)
  }

  test("a changed file is read again once its time moves, and a listener is told") {
    val dir  = Files.createTempDirectory("grants")
    val file = dir.resolve("grants.json")
    write(file, content, 1_000_000L)
    val grants  = GrantsFile(file, "wallet", 0.millis)
    var changes = 0
    grants.onChange(() => changes += 1)
    assert(grants.admits(merchant, deposits))
    write(file, """{"project":"spinvibe","grants":[]}""", 2_000_000L)
    assert(!grants.admits(merchant, deposits), "the revocation is read")
    assertEquals(changes, 1)
  }

  test("within the interval nothing is read, however the file changed") {
    val dir  = Files.createTempDirectory("grants")
    val file = dir.resolve("grants.json")
    write(file, content, 1_000_000L)
    val grants = GrantsFile(file, "wallet", 1.hour)
    write(file, """{"project":"spinvibe","grants":[]}""", 2_000_000L)
    assert(grants.admits(merchant, deposits), "not yet looked at")
    grants.check()
    assert(!grants.admits(merchant, deposits), "a forced check reads it")
  }

  test("a file that cannot be parsed keeps the grants last read") {
    val dir  = Files.createTempDirectory("grants")
    val file = dir.resolve("grants.json")
    write(file, content, 1_000_000L)
    val grants = GrantsFile(file, "wallet", 0.millis)
    write(file, "{not json", 2_000_000L)
    assert(grants.admits(merchant, deposits))
  }

  test("the kubelet's swap of a symlinked directory is seen, though the link itself never moves") {
    val root  = Files.createTempDirectory("projected")
    val first = Files.createDirectory(root.resolve("..2026_10_08_1"))
    write(first.resolve("grants.json"), content, 1_000_000L)
    val data = root.resolve("..data")
    Files.createSymbolicLink(data, first.getFileName)
    val link = root.resolve("grants.json")
    Files.createSymbolicLink(link, Path.of("..data/grants.json"))
    val grants = GrantsFile(link, "wallet", 0.millis)
    assert(grants.admits(merchant, deposits))

    val second = Files.createDirectory(root.resolve("..2026_10_08_2"))
    write(second.resolve("grants.json"), """{"project":"spinvibe","grants":[]}""", 2_000_000L)
    val swap = root.resolve("..data_tmp")
    Files.createSymbolicLink(swap, second.getFileName)
    Files.move(swap, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    assert(!grants.admits(merchant, deposits))
  }

  test("no file is no grants, and a file that appears is read") {
    val dir    = Files.createTempDirectory("grants")
    val file   = dir.resolve("grants.json")
    val grants = GrantsFile(file, "wallet", 0.millis)
    assert(!grants.admits(merchant, deposits))
    write(file, content, 1_000_000L)
    assert(grants.admits(merchant, deposits))
  }

  test("configured from ankka.grants, and absent when no file is named") {
    val none = com.typesafe.config.ConfigFactory.parseString(
      """ankka.grants { file = "", reload-interval = 10s }"""
    )
    assertEquals(GrantsFile.fromConfig(none, "wallet"), None)
    val some = com.typesafe.config.ConfigFactory.parseString(
      """ankka.grants { file = "/var/run/ankka/project/grants.json", reload-interval = 10s }"""
    )
    assert(GrantsFile.fromConfig(some, "wallet").isDefined)
  }

  test("with no file named, the grants are read beside the project's declarations") {
    val dir = Files.createTempDirectory("project")
    write(dir.resolve("grants.json"), content, 1_000_000L)
    val config = com.typesafe.config.ConfigFactory.parseString(
      s"""ankka.grants { file = "", declarations = "${dir.resolve(
          "topics.json"
        )}", reload-interval = 10s }"""
    )
    val grants = GrantsFile.fromConfig(config, "wallet").getOrElse(fail("no grants file"))
    assert(grants.admits(merchant, deposits))
  }
