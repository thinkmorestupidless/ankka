package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.remote.{RemoteKeyedViewDescriptor, RemoteSource}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * What a keyed view may read: `contracts/keyed-views.md`, K1–K5, for a Scala view and a discovered
 * one alike, and that a service breaking one does not validate.
 */
class KeyedViewRulesSuite extends munit.FunSuite:

  private val text = Codecs.serializer[String]("text")

  private final class Joined extends KeyedView[String]:
    val nothing: (String, Change) => Effect = (_, _) => effects.ignore()

  private def joined(read: ChangeSource[String]*): KeyedViewDescriptor[Joined, String] =
    new KeyedView.Companion[Joined, String](ComponentId("joined"), text):
      read.foreach(s => source(s)(_.nothing))
      def create(ctx: ViewComponentContext) = new Joined
    .descriptor

  private val shipment = ChangeSource.EventSourced(ComponentId("shipment"), text)
  private val customer = ChangeSource.KeyValue(ComponentId("customer"), text)
  private val topic    = ChangeSource.Topic("orders", text)

  private def problems(descriptor: ComponentDescriptor) = KeyedViewRules.problems(Seq(descriptor))

  test("K1: a keyed view with no source is refused") {
    assertEquals(
      problems(joined()),
      Vector("view 'joined' declares no source; a keyed view reads one or more")
    )
  }

  test("K2: a topic and an entity may not be sources of one view") {
    val found = problems(joined(shipment, topic))
    assertEquals(found.size, 1)
    assert(found.head.contains("a topic and an entity may not be sources of one view"), found.head)
  }

  test("K3: a keyed view that reads only topics is refused") {
    val found = problems(joined(topic))
    assert(found.exists(_.contains("a keyed view reads entities")), found.toString)
  }

  test("K4: two sources that read one component are refused") {
    val found = problems(joined(shipment, shipment))
    assertEquals(found, Vector("view 'joined' reads 'shipment' twice; each source is read once"))
  }

  test("K5: a discovered source that is no entity is refused") {
    val remote = RemoteKeyedViewDescriptor(
      ComponentId("joined"),
      Vector(RemoteSource.Component(ComponentKind.TimedAction, ComponentId("reminders"))),
      "row"
    )
    val found = problems(remote)
    assert(
      found.exists(_.contains("'reminders'")) && found.exists(_.contains("change stream")),
      found.toString
    )
  }

  test("a keyed view of an event sourced and a key value entity is accepted") {
    assertEquals(problems(joined(shipment, customer)), Vector.empty)
    val remote = RemoteKeyedViewDescriptor(
      ComponentId("joined"),
      Vector(
        RemoteSource.Component(ComponentKind.EventSourcedEntity, ComponentId("shipment")),
        RemoteSource.Component(ComponentKind.KeyValueEntity, ComponentId("customer"))
      ),
      "row"
    )
    assertEquals(problems(remote), Vector.empty)
  }

  test("a keyed view declares one handler per source, named for what it reads") {
    assertEquals(
      joined(shipment, customer).declaredHandlers.map(_.name),
      Vector("customer", "shipment")
    )
  }

  test("a service with a keyed view that breaks a rule does not validate, and says why") {
    val validated = Ankka.service.register(joined(shipment, topic)).validate
    assert(
      validated.left.exists(
        _.exists(_.contains("a topic and an entity may not be sources of one view"))
      ),
      validated.toString
    )
  }
