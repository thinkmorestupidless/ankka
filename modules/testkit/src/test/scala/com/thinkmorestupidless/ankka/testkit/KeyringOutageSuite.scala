package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, Done, EntityId, ErrorCode}

import scala.concurrent.duration.*
import scala.util.Try

/**
 * SC-010: the keyring stopped and started again under a running service. The cache's own bound is
 * KeyringClientSuite's; here is what a service does around it, against the kit's keyring.
 */
final class KeyringOutageSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq(ContactCardEntity.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def afterEach(context: AfterEach): Unit = kit.keyring.recover()

  private def card(id: String) = kit.componentClient.forKeyValueEntity(EntityId(id))

  private def setEmail(id: String, email: String): Either[CommandError, Done] =
    Try(card(id).call(ContactCardEntity.setEmail).invoke(email)).toEither.left.map {
      case e: CommandError => e
      case other           => fail(s"not a CommandError: $other", other)
    }

  private def email(id: String): Either[CommandError, String] =
    Try(card(id).call(ContactCardEntity.email).invoke()).toEither.left.map {
      case e: CommandError => e
      case other           => fail(s"not a CommandError: $other", other)
    }

  test("in an outage a cached data subject is read and written, and one never fetched is refused") {
    assertEquals(setEmail("o-1", "ada@example.com"), Right(Done))
    kit.keyring.outage(10.minutes)
    assertEquals(email("o-1"), Right("ada@example.com"))
    // A second write seals again: the key comes from the cache, the keyring is never asked.
    assertEquals(setEmail("o-1", "ada@example.org"), Right(Done))
    assertEquals(email("o-1"), Right("ada@example.org"))
    val refused = setEmail("o-2", "grace@example.com")
    assertEquals(refused.left.map(_.code), Left(ErrorCode.Unavailable), refused.toString)
    assert(refused.left.exists(_.message.contains("keyring is unavailable")), refused.toString)
  }

  test("in an outage no erasure applies, and one asked again on its return does") {
    assertEquals(setEmail("o-3", "ida@example.com"), Right(Done))
    kit.keyring.outage(10.minutes)
    val subject = DataSubject("player/o-3")
    val refused = Try(kit.erase(subject)).failed.toOption
    assert(refused.exists {
      case e: CommandError => e.code == ErrorCode.Unavailable; case _ => false
    })
    assert(kit.keyring.holdsKey(kit.project, subject), "the key was destroyed in an outage")
    assertEquals(email("o-3"), Right("ida@example.com"))
    kit.keyring.recover()
    kit.erase(subject): Unit
    kit.restartService()
    assertEquals(email("o-3"), Right("erased"))
  }

  test(
    "a recovery that failed in an outage is not lost, and resumes within one fetch of the return"
  ) {
    assertEquals(setEmail("o-4", "lin@example.com"), Right(Done))
    val subject = DataSubject("player/o-4")
    // A restarted service holds no key: recovering the card needs the keyring.
    kit.restartService()
    kit.keyring.outage(10.minutes)
    // The recovery fails and is retried with backoff (R4); its caller waits out the ask.
    val failed = email("o-4")
    assert(
      failed.left.exists(e => e.code == ErrorCode.Timeout || e.code == ErrorCode.Unavailable),
      failed.toString
    )
    kit.keyring.recover()
    val before = kit.keyring.fetches(kit.project, subject)
    assertEquals(email("o-4"), Right("lin@example.com"))
    assertEquals(kit.keyring.fetches(kit.project, subject) - before, 1)
    // Nothing of the card was lost to the failed recovery: a write goes on from it.
    assertEquals(setEmail("o-4", "lin@example.org"), Right(Done))
    assertEquals(email("o-4"), Right("lin@example.org"))
  }
