package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

final case class Counter(value: Int, closed: Boolean = false)

enum CounterEvent:
  case Increased(by: Int)
  case Closed

final class CounterEntity(ctx: EventSourcedEntityContext)
    extends EventSourcedEntity[Counter, CounterEvent]:
  import CounterEvent.*

  val id: EntityId = ctx.entityId

  def emptyState: Counter = Counter(0)

  def applyEvent(event: CounterEvent): Counter = event match
    case Increased(by) => currentState.copy(value = currentState.value + by)
    case Closed        => currentState.copy(closed = true)

  def increase(by: Int): Effect[Int] =
    if currentState.closed then effects.error("counter is closed")
    else effects.persist(Increased(by)).thenReply(_.value)

  def get: ReadOnlyEffect[Counter] = effects.reply(currentState)

  def close: Effect[Done] =
    effects.persist(Closed).thenReply(_ => Done)

object CounterEntity
    extends EventSourcedEntity.Companion[CounterEntity, Counter, CounterEvent](
      ComponentId("counter"),
      Codecs.serializer[Counter]("counter"),
      Codecs.serializer[CounterEvent]("counter-event")
    ):
  def create(ctx: EventSourcedEntityContext) = new CounterEntity(ctx)

  // The bet: does `_.increase` pick the 1-arg overload and `_.get` the no-arg one?
  val increase = command("increase")(_.increase)
  val get      = query("get")(_.get)
  val close    = command("close")(_.close)

class CompanionShapeSuite extends munit.FunSuite:

  test("handles infer their input and output types from the method") {
    val _: CommandHandle[CounterEntity, Int, Int] = CounterEntity.increase
    val _: NoArgHandle[CounterEntity, Counter]    = CounterEntity.get
    val _: NoArgHandle[CounterEntity, Done]       = CounterEntity.close
  }

  test("descriptor collects every registered handler") {
    val d = CounterEntity.descriptor
    assertEquals(d.componentId, ComponentId("counter"))
    assertEquals(d.kind, ComponentKind.EventSourcedEntity)
    assertEquals(d.handlers.keySet.map(n => n: String), Set("increase", "get", "close"))
  }

  test("queries are marked read-only, commands are not") {
    assert(!CounterEntity.increase.readOnly)
    assert(CounterEntity.get.readOnly)
    assert(!CounterEntity.close.readOnly)
  }
