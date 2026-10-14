package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*

object ReplicatedCounterEntity
    extends EventSourcedEntity.Companion[CounterEntity, Counter, CounterEvent](
      ComponentId("replicated-counter"),
      Codecs.serializer[Counter]("counter"),
      Codecs.serializer[CounterEvent]("counter-event")
    ):
  override def replicated: Boolean           = true
  def create(ctx: EventSourcedEntityContext) = new CounterEntity(ctx)
  val get                                    = query("get")(_.get)

class ReplicatedDeclarationSuite extends munit.FunSuite:

  test("an entity that declares itself replicated says so in its descriptor") {
    assert(ReplicatedCounterEntity.descriptor.replicated)
    val asComponent: ComponentDescriptor = ReplicatedCounterEntity.descriptor
    assert(asComponent.replicated)
  }

  test("an entity that says nothing is not replicated") {
    assert(!CounterEntity.descriptor.replicated)
  }
