package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.consumer.ConsumerEffect
import ankka.protocol.v1.payload as pb
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.runtime.remote.ConsumerOutcome

/** What a consumer's reply on the wire means to the runtime. No process, no actor system. */
class TranslateSuite extends munit.FunSuite:

  private def payload(text: String) =
    pb.Payload("application/json", "fanned", ByteString.copyFromUtf8(text))

  private def metadata(entries: (String, String)*) =
    pb.Metadata(entries.map((k, v) => pb.Metadata.Entry(k, v)))

  private def message(text: String, key: Option[String], entries: (String, String)*) =
    ConsumerEffect.Message(Some(payload(text)), Some(metadata(entries*)), key)

  private def produceAll(messages: ConsumerEffect.Message*) =
    ConsumerEffect(ConsumerEffect.Effect.ProduceAll(ConsumerEffect.ProduceAll(messages)))

  test("several messages arrive in the order given, each with its key and its metadata") {
    val effect = produceAll(
      message("""{"n":1}""", None),
      message("""{"n":2}""", Some("second:c1")),
      message("""{"n":3}""", None, "x-n" -> "3")
    )
    // Through the bytes, as a process would send it.
    val outcome = Translate.fromConsumerEffect(ConsumerEffect.parseFrom(effect.toByteArray))
    val ConsumerOutcome.ProduceAll(messages) = outcome: @unchecked
    assertEquals(
      messages.map(m => String(m.payload.data, "UTF-8")).toList,
      List(1, 2, 3).map(n => s"""{"n":$n}""")
    )
    assertEquals(messages.map(_.key).toList, List(None, Some("second:c1"), None))
    assertEquals(messages.map(_.payload.manifest).distinct.toList, List("fanned"))
    assertEquals(messages.map(_.metadata.get("x-n")).toList, List(None, None, Some("3")))
  }

  test("a message with an empty key is not a message with no key") {
    val ConsumerOutcome.ProduceAll(messages) =
      Translate.fromConsumerEffect(produceAll(message("{}", Some("")))): @unchecked
    // The runtime refuses it when it publishes; the translation must not lose the difference.
    assertEquals(messages.map(_.key).toList, List(Some("")))
  }

  test("several messages with none in it is an empty list, not an ignore") {
    assertEquals(
      Translate.fromConsumerEffect(ConsumerEffect.parseFrom(produceAll().toByteArray)),
      ConsumerOutcome.ProduceAll(Vector.empty)
    )
  }

  test("a single message is read as it always was") {
    val effect = ConsumerEffect(
      ConsumerEffect.Effect.Produce(
        ConsumerEffect.Produce(Some(payload("one")), Some(metadata("ce-subject" -> "c1")))
      )
    )
    val ConsumerOutcome.Produce(p, m) = Translate.fromConsumerEffect(effect): @unchecked
    assertEquals(String(p.data, "UTF-8"), "one")
    assertEquals(m.subject, Some("c1"))
  }

  test(
    "a reply with no case set is read as ignore: why an SDK must not send what a runtime does not know"
  ) {
    // A runtime at protocol 1.2 parses a 1.3 `produce_all` to exactly this — an effect with no
    // case — and so would record the change as handled with nothing published. The guard is the
    // SDK's: it answers `produce_all` only to a request that carried `ankka.protocol` >= 1.3.
    assertEquals(Translate.fromConsumerEffect(ConsumerEffect()), ConsumerOutcome.Ignore)
  }

  test("a keyed view's change carries its source and no row, and its rows are read in order") {
    import ankka.protocol.v1.view.{RowChange, RowChanges, ViewEffect}
    import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata}
    import com.thinkmorestupidless.ankka.runtime.remote.{ViewOutcome, ViewRequest}
    val sent = Translate.toViewRequest(
      ViewRequest(ComponentId("joined"), None, Metadata.empty, None, Some(ComponentId("customer")))
    )
    assertEquals(sent.sourceId, Some("customer"))
    assertEquals(sent.row, None)
    assert(sent.deleted)
    val rows = Translate.fromViewEffect(
      ViewEffect(
        ViewEffect.Effect.Rows(
          RowChanges(
            Vector(
              RowChange("b", RowChange.Change.Upsert(payload("{}"))),
              RowChange("a", RowChange.Change.Delete(pb.Empty()))
            )
          )
        )
      )
    )
    rows match
      case ViewOutcome.Rows(changes) =>
        assertEquals(
          changes.map((key, row) => key -> row.isDefined),
          Vector("b" -> true, "a" -> false)
        )
      case other => fail(s"expected rows, got $other")
  }
