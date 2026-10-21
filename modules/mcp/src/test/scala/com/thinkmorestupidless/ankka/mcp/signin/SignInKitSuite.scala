package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.mcp.TestTools.MovableClock
import com.thinkmorestupidless.ankka.sdk.{
  ComponentClient,
  SecretStore,
  ServiceClients,
  TimedActionContext,
  TimerScheduler
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.{Clock, Instant}
import scala.concurrent.duration.*

/**
 * The sign-in broker's state on a running service (R11): a record's deletion leaves a record
 * written again since, and removes one whose time is up; the broker's keys rotate, every instance's
 * tokens verify across a rotation, and a key past its keeping is removed with its private half.
 */
class SignInKitSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val kit =
    FunFixture[AnkkaTestKit](_ => AnkkaTestKit.start(McpRuntime.descriptors), _.stop())

  private def expiry(client: ComponentClient) =
    SignInExpiry(new TimedActionContext:
      def componentId: ComponentId         = SignInExpiry.descriptor.componentId
      def componentClient: ComponentClient = client
      def timerName: String                = "sign-in:test"
      def previousAttempts: Int            = 0
      def dueTime: Instant                 = Instant.now()
      def secrets: SecretStore             = SecretStore.unavailable
      def services: ServiceClients = throw UnsupportedOperationException("not in this suite")
      def timers: TimerScheduler   = throw UnsupportedOperationException("not in this suite")
      def clock: Clock             = Clock.systemUTC())

  private val at = Instant.parse("2026-10-19T10:00:00Z")

  kit.test("the expiry deletes a record whose time is up, and leaves one written again since") {
    k =>
      val records = (id: String) => k.componentClient.forKeyValueEntity(EntityId(id))
      val client  = SignInRecord.Client("c1", Vector("https://app.example/cb"), None, at, at)
      records("client:gone").call(SignInRecordEntity.put).invoke(PutSignInRecord(client, 1)): Unit
      records("client:kept")
        .call(SignInRecordEntity.put)
        .invoke(PutSignInRecord(client, 600000)): Unit
      Thread.sleep(50)
      assertEquals(
        records("client:gone").call(SignInRecordEntity.get).invoke(),
        SignInRecord.Absent,
        "expired: read as nothing"
      )

      val action = expiry(k.componentClient)
      action.expire(SignInExpiry.Due("client:gone")): Unit
      action.expire(SignInExpiry.Due("client:kept")): Unit
      assertEquals(records("client:kept").call(SignInRecordEntity.get).invoke(), client)
      k.restartService()
      assertEquals(
        records("client:gone").call(SignInRecordEntity.get).invoke(),
        SignInRecord.Absent
      )
      assertEquals(records("client:kept").call(SignInRecordEntity.get).invoke(), client, "durable")
  }

  kit.test("keys rotate, a token verifies across the rotation, and a key past keeping is removed") {
    k =>
      val clock = MovableClock(at)
      val keys =
        BrokerKeys(k.componentClient, k.secrets, clock, rotateEvery = 30.days, keepFor = 60.days)
      val other =
        BrokerKeys(k.componentClient, k.secrets, clock, rotateEvery = 30.days, keepFor = 60.days)
      def tokens(of: BrokerKeys) =
        BrokerTokens(() => of.signing(), of.publicKey, clock, 90.days) // outlives a rotation
      val person = SignedIn("ada", None, None, false, Set.empty, Map.empty, "customers")
      val issuer = "https://shop.example/_ankka/sign-in/mcp"
      val minted = (of: BrokerKeys) => tokens(of).mint(issuer, "https://shop.example/mcp", person)

      val first    = minted(keys)
      val firstKid = Jwt.parts(first).toOption.flatMap(_.kid).get
      assert(
        k.secrets.get(BrokerKeys.SecretPrefix + firstKid).isDefined,
        "the private half is kept before the key"
      )
      assert(
        tokens(other).verify(first, "/mcp").isRight,
        "another instance verifies the key it has not seen"
      )

      clock.advance(java.time.Duration.ofDays(31))
      keys.rotate()
      val second = minted(other)
      assertNotEquals(Jwt.parts(second).toOption.flatMap(_.kid), Some(firstKid))
      assert(
        tokens(keys).verify(first, "/mcp").isRight,
        "a token of the old key verifies after the rotation"
      )
      assert(tokens(keys).verify(second, "/mcp").isRight)

      clock.advance(java.time.Duration.ofDays(30))
      keys.rotate()
      val held = k.componentClient
        .forEventSourcedEntity(EntityId(SignInKeysEntity.Id))
        .call(SignInKeysEntity.get)
        .invoke()
      assert(!held.keys.exists(_.kid == firstKid), held)
      assertEquals(
        k.secrets.get(BrokerKeys.SecretPrefix + firstKid),
        None,
        "the private half went with it"
      )
      assert(held.keys.nonEmpty, "the newest is never removed")
  }
