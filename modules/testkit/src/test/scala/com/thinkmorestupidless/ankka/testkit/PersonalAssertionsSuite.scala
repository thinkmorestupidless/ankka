package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.InMemoryBroker

import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.*

/** `assertNoPersonalValue` can fail (feature 042): a value kept in the clear is found and named. */
final class PersonalAssertionsSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq(OrderEntity.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  test("a value a serializer kept in the clear is found, naming the table and column") {
    // The order's item is no personal field: its serializer writes it as given.
    assertEquals(
      kit.componentClient
        .forKeyValueEntity(EntityId("leak-1"))
        .call(OrderEntity.place)
        .invoke("ada@example.com"),
      Done
    )
    val e = intercept[AssertionError](kit.assertNoPersonalValue("ada@example.com"))
    assert(e.getMessage.contains("'ada@example.com'"), e.getMessage)
    assert(e.getMessage.contains("public."), e.getMessage)
  }

  test("a value is found as base64 and as hex as well as written") {
    val broker = InMemoryBroker()
    val text   = "grace@example.com"
    Await.result(
      broker.publish("t", Base64.getEncoder.encode(text.getBytes("UTF-8")), Metadata.empty),
      5.seconds
    )
    Await.result(
      broker.publish(
        "t",
        java.util.HexFormat.of().formatHex(text.getBytes("UTF-8")).getBytes,
        Metadata.empty
      ),
      5.seconds
    )
    val e = intercept[AssertionError](kit.assertNoPersonalValueIn(broker)(text))
    assert(e.getMessage.contains("as base64 in topic t at offset 0"), e.getMessage)
    assert(e.getMessage.contains("as hex in topic t at offset 1"), e.getMessage)
  }

  test("a value written nowhere is not found") {
    kit.assertNoPersonalValue("nobody-wrote-this@example.com")
  }
