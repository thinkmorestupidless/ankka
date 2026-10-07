package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteConsumerDescriptor,
  RemoteSource,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * What a view or consumer may declare about a topic, checked for components written in Scala and
 * components discovered from another process by one function.
 */
class TopicSourceRulesSuite extends munit.FunSuite:

  import TopicSourceRulesSuite.*

  private def problems(descriptors: ComponentDescriptor*) = TopicSourceRules.problems(descriptors)

  private val noStart =
    "consumer 'notifier' reads topic 'orders' and declares no start position; declare earliest, " +
      "latest or a time"

  test("a consumer reading a topic must declare its start position") {
    assertEquals(problems(consumer(None).descriptor), Vector(noStart))
    for start <- Seq(StartFrom.Earliest, StartFrom.Latest, StartFrom.At(java.time.Instant.EPOCH))
    do assertEquals(problems(consumer(Some(start)).descriptor), Vector.empty, start.toString)
  }

  test("a view reading a topic need not: it starts at the earliest message") {
    assertEquals(problems(view(None).descriptor), Vector.empty)
  }

  test("a discovered consumer reading a topic must declare its start position") {
    val remote = RemoteConsumerDescriptor(
      ComponentId("notifier"),
      RemoteSource.Topic("orders", None),
      producesTo = None
    )
    assertEquals(problems(remote), Vector(noStart))
    assertEquals(
      problems(remote.copy(source = RemoteSource.Topic("orders", Some(StartFrom.Latest)))),
      Vector.empty
    )
  }

  test("a discovered consumer whose SDK cannot declare a start position is not held to it") {
    val older = RemoteConsumerDescriptor(
      ComponentId("notifier"),
      RemoteSource.Topic("orders", None),
      producesTo = None,
      startDeclarable = false
    )
    assertEquals(problems(older), Vector.empty)
  }

  test("a discovered view, and a component reading an entity, declare nothing about topics") {
    val remoteView = RemoteViewDescriptor(
      ComponentId("summary"),
      RemoteSource.Topic("orders", None),
      "row",
      Set.empty
    )
    val overEntity = RemoteConsumerDescriptor(
      ComponentId("notifier"),
      RemoteSource.Component(ComponentKind.EventSourcedEntity, ComponentId("order")),
      producesTo = None
    )
    assertEquals(problems(remoteView, overEntity), Vector.empty)
  }

  test("a version on a consumer that reads an entity is refused") {
    val overEntity = ChangeSource.EventSourced(ComponentId("order"), serializer)
    for declared <- Seq(1, 2) do
      val viewed = new View.Companion[Summary, String, String](
        ComponentId("summary"),
        overEntity,
        serializer
      ):
        override def version                  = Some(declared)
        def create(ctx: ViewComponentContext) = new Summary
      val consumed = new Consumer.Companion[Notifier, String, Nothing](
        ComponentId("notifier"),
        overEntity
      ):
        override def version             = Some(declared)
        def create(ctx: ConsumerContext) = new Notifier
      // A view that reads an entity may: raising its version rebuilds it from the journal.
      assertEquals(
        problems(viewed.descriptor, consumed.descriptor),
        Vector(
          "consumer 'notifier' declares a version, which applies to a topic; it reads " +
            "event-sourced-entity(order)"
        )
      )
    val remoteView = RemoteViewDescriptor(
      ComponentId("summary"),
      RemoteSource.Component(ComponentKind.EventSourcedEntity, ComponentId("order")),
      "row",
      Set.empty,
      version = Some(2)
    )
    assertEquals(problems(remoteView), Vector.empty)
    val remoteConsumer = RemoteConsumerDescriptor(
      ComponentId("notifier"),
      RemoteSource.Component(ComponentKind.EventSourcedEntity, ComponentId("order")),
      None,
      startDeclarable = true,
      version = Some(2)
    )
    assertEquals(problems(remoteConsumer).size, 1)
  }

  test("a version that is not a positive whole number is refused") {
    for bad <- Seq(0, -1) do
      val versioned = new View.Companion[Summary, String, String](
        ComponentId("summary"),
        ChangeSource.Topic("orders", serializer, None),
        serializer
      ):
        override def version                  = Some(bad)
        def create(ctx: ViewComponentContext) = new Summary
      assertEquals(
        problems(versioned.descriptor),
        Vector(s"view 'summary' declares version $bad; a version is a whole number of 1 or more")
      )
    val remote = RemoteConsumerDescriptor(
      ComponentId("notifier"),
      RemoteSource.Topic("orders", Some(StartFrom.Latest)),
      producesTo = None,
      version = Some(0)
    )
    assertEquals(
      problems(remote),
      Vector("consumer 'notifier' declares version 0; a version is a whole number of 1 or more")
    )
  }

  test("a service with such a consumer does not validate, and says why") {
    val problems = Ankka.service.register(consumer(None).descriptor).validate.left.toOption
    assertEquals(problems, Some(Vector(noStart)))
  }

object TopicSourceRulesSuite:

  private val serializer = Codecs.serializer[String]("text")

  final class Notifier extends Consumer[String, Nothing]:
    def onMessage(message: String): Effect = effects.ignore()

  def consumer(start: Option[StartFrom]): Consumer.Companion[Notifier, String, Nothing] =
    new Consumer.Companion[Notifier, String, Nothing](
      ComponentId("notifier"),
      ChangeSource.Topic("orders", serializer, start)
    ):
      def create(ctx: ConsumerContext) = new Notifier

  final class Summary extends View[String, String]:
    def onChange(message: String): Effect = effects.updateRow(message)

  def view(start: Option[StartFrom]): View.Companion[Summary, String, String] =
    new View.Companion[Summary, String, String](
      ComponentId("summary"),
      ChangeSource.Topic("orders", serializer, start),
      serializer
    ):
      def create(ctx: ViewComponentContext) = new Summary
